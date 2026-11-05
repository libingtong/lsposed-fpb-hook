package com.yg.fpbhook;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.libxposed.api.XposedModule;

/**
 * 发票盒子 VIP 解锁模块 —— libxposed API 102
 *
 * 设计要点：不依赖任何混淆后的类名/方法名/字段名。
 *
 *   L1 类型锚点 : com.quickwis.fapiaohezi.network.response.VipInfoBean
 *                 业务包内的实体类，R8 不改名
 *   L2 数据层   : 让所有「返回 VipInfoBean 的无参方法」返回一个合成 Bean，
 *                 并把宿主类的静态字段按「类型」定位后覆盖；写入器被拦截后重新覆盖。
 *                 1.75.0 宿主为 hi.c，1.97.7 为 di.c，字段名 d/c 各不相同，
 *                 但"静态 + 类型为 VipInfoBean"这个特征不变。
 *   L3 属性层   : is_expired() 恒 0、getExpires_at() 恒远期 —— 这两个方法名来自
 *                 JSON 字段名，跨版本稳定。
 *
 * 三者叠加后 isVIP / isVIPExpired / isValidVIP 三个判定必然成立，
 * 无需知道它们被混淆成了什么名字。
 */
public final class HookEntry extends XposedModule {

    private static final String TAG = "FpbHook";
    private static final String TARGET = "com.quickwis.fapiaohezi";
    private static final String VIP = "com.quickwis.fapiaohezi.network.response.VipInfoBean";
    private static final String BASE = "com.quickwis.fapiaohezi.network.response.SystemBaseDataResponse";
    private static final String FAR = "2099-12-31 23:59:59";

    /** 已知的 AccountHelper 宿主混淆名；找不到时会退化为纯 L1/L3 路径，依然有效 */
    private static final String[] HOST_CANDIDATES = {"di.c", "hi.c", "dj.c", "ki.c", "ji.c"};

    private volatile boolean installed = false;
    private volatile Object fake = null;
    private Class<?> vipType = null;

    /* ---------------- 生命周期 ---------------- */

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "onModuleLoaded process=" + param.getProcessName()
                + " framework=" + getFrameworkName()
                + " version=" + getFrameworkVersion()
                + " api=" + getApiVersion());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (!TARGET.equals(param.getPackageName())) return;
        install(param.getDefaultClassLoader());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET.equals(param.getPackageName())) return;
        install(param.getClassLoader());
    }

    /* ---------------- 装配 ---------------- */

    private synchronized void install(ClassLoader cl) {
        if (installed || cl == null) return;
        installed = true;

        try {
            vipType = Reflect.load(cl, VIP);
            if (vipType == null) {
                log(Log.ERROR, TAG, "VipInfoBean 未找到，放弃");
                installed = false;
                return;
            }

            fake = Reflect.newVip(vipType, FAR);
            log(Log.INFO, TAG, "合成 VipInfoBean = " + fake);

            // L2-a: 基础资料响应里的 vip_info getter 直接返回合成对象
            Class<?> base = Reflect.load(cl, BASE);
            if (base != null) {
                hookReturning(base, vipType, fake);
            } else {
                log(Log.WARN, TAG, "SystemBaseDataResponse 未找到，跳过 getter hook");
            }

            // L3: 属性 getter 恒定为未过期 / 远期
            hookIntNoArg(vipType, "expired", 0);
            hookStringNoArg(vipType, "expire", FAR);

            // L2-b: 宿主类静态字段按类型注入 + 拦截写入器
            boolean hosted = bindHost(cl);
            if (!hosted) {
                // 候选名失效 -> 启动全量 dex 深度搜索（跨版本，不依赖混淆名）
                log(Log.WARN, TAG, "候选宿主未命中，启动深度搜索");
                scanHostAsync(cl);
            }

            log(Log.INFO, TAG, "装配完成");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "装配失败", t);
            installed = false;
        }
    }

    /* ---------------- 宿主绑定 ---------------- */

    /** 先用已知候选快速命中；失败返回 false 交给深度搜索 */
    private boolean bindHost(ClassLoader cl) {
        for (String name : HOST_CANDIDATES) {
            Class<?> host = Reflect.load(cl, name);
            if (host == null) continue;
            Field f = Reflect.vipField(host, vipType);
            if (f == null) continue;
            bind(host, f, "候选名 " + name);
            return true;
        }
        return false;
    }

    private void bind(Class<?> host, Field field, String how) {
        Reflect.setStatic(field, fake);
        log(Log.INFO, TAG, "宿主 " + host.getName() + " 字段 " + field.getName()
                + " 已写入（" + how + "）");
        hookWriters(host, vipType, field);
    }

    /**
     * 深度搜索：全量枚举进程内所有 dex 的类名，按「静态 + 类型为 VipInfoBean」找宿主。
     * 这样即便 AccountHelper 的混淆名每版都变，也能自动定位，无需改代码。
     */
    private void scanHostAsync(final ClassLoader cl) {
        new Thread(() -> {
            try {
                long start = System.currentTimeMillis();
                java.util.List<String> names = Reflect.allClassNames(cl);
                log(Log.INFO, TAG, "深度搜索：枚举到 " + names.size() + " 个类");
                if (names.isEmpty()) {
                    log(Log.WARN, TAG, "深度搜索不可用（dexElements 反射受限），退化为 L1/L3");
                    return;
                }
                Class<?> host = Reflect.findHostByNames(
                        names, cl, vipType, start + 30000L);
                if (host == null) {
                    log(Log.WARN, TAG, "深度搜索未命中宿主，仅依赖 L1/L3");
                    return;
                }
                Field f = Reflect.vipField(host, vipType);
                if (f == null) return;
                bind(host, f, "深度搜索，耗时 " + (System.currentTimeMillis() - start) + "ms");
            } catch (Throwable t) {
                log(Log.WARN, TAG, "深度搜索异常: " + t);
            }
        }, "fpb-deep-scan").start();
    }

    /* ---------------- hook 原语 ---------------- */

    /** 该类所有「无参且返回 ret」的实例方法 -> 固定返回 value */
    private void hookReturning(Class<?> owner, Class<?> ret, final Object value) {
        for (Method m : owner.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() != 0) continue;
            if (!m.getReturnType().equals(ret)) continue;
            if (!installable(m)) continue;
            try {
                hook(m).intercept(chain -> value);
                log(Log.INFO, TAG, "hook " + owner.getSimpleName() + "." + m.getName() + "() -> 合成对象");
                // 同一声明类只处理一个即可，避免重复 hook 同一属性的多个别名
                return;
            } catch (Throwable t) {
                log(Log.WARN, TAG, "hook " + m.getName() + " 失败: " + t);
            }
        }
    }

    /** 名字包含 keyword 的无参 int 方法 -> 常量 */
    private void hookIntNoArg(Class<?> owner, String keyword, final int value) {
        for (Method m : owner.getDeclaredMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (m.getReturnType() != int.class) continue;
            if (!m.getName().toLowerCase().contains(keyword)) continue;
            if (!installable(m)) continue;
            try {
                hook(m).intercept(chain -> value);
                log(Log.INFO, TAG, "hook " + owner.getSimpleName() + "." + m.getName() + "() -> " + value);
            } catch (Throwable t) {
                log(Log.WARN, TAG, "hook " + m.getName() + " 失败: " + t);
            }
        }
    }

    /** 名字包含 keyword 的无参 String 方法 -> 常量 */
    private void hookStringNoArg(Class<?> owner, String keyword, final String value) {
        for (Method m : owner.getDeclaredMethods()) {
            if (m.getParameterCount() != 0) continue;
            if (m.getReturnType() != String.class) continue;
            if (!m.getName().toLowerCase().contains(keyword)) continue;
            if (!installable(m)) continue;
            try {
                hook(m).intercept(chain -> value);
                log(Log.INFO, TAG, "hook " + owner.getSimpleName() + "." + m.getName() + "() -> " + value);
            } catch (Throwable t) {
                log(Log.WARN, TAG, "hook " + m.getName() + " 失败: " + t);
            }
        }
    }

    /** 拦截所有 (VipInfoBean)V 写入器，写完立即把字段重设为合成对象 */
    private void hookWriters(Class<?> host, Class<?> vip, final Field field) {
        final Object bean = fake;
        for (Method m : host.getDeclaredMethods()) {
            if (m.getParameterCount() != 1) continue;
            if (!m.getParameterTypes()[0].equals(vip)) continue;
            if (!installable(m)) continue;
            try {
                hook(m).intercept(chain -> {
                    Object r = chain.proceed();
                    Reflect.setStatic(field, bean);
                    return r;
                });
                log(Log.INFO, TAG, "hook 写入器 " + host.getSimpleName() + "." + m.getName() + "(VipInfoBean)");
            } catch (Throwable t) {
                log(Log.WARN, TAG, "hook 写入器 " + m.getName() + " 失败: " + t);
            }
        }
    }

    /** 排除合成方法、桥接方法，避免 hook 到编译器生成物 */
    private static boolean installable(Method m) {
        if (m.isSynthetic() || m.isBridge()) return false;
        try {
            m.setAccessible(true);
        } catch (Throwable ignored) {
        }
        return true;
    }
}
