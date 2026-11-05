package com.yg.fpbhook;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import io.github.libxposed.api.XposedModule;

/**
 * 发票盒子 VIP 解锁模块 —— libxposed API 102
 *
 * 定位策略，从快到慢：
 *
 *   L0 缓存     读目标应用 files/fpb_hook_target.cfg，带 versionCode 校验。
 *               版本没变 -> 直接命中，毫秒级；版本变了 -> 自动作废。
 *   L1 候选名   已知混淆名（di.c / hi.c …），O(1)。
 *   L2 深度扫描  enumerate 进程内所有 dex 的类名，按「static 字段类型 == VipInfoBean」找宿主。
 *               较慢，只在 L0/L1 都失败时执行一次，结果写回 L0。
 *
 * 判定不依赖任何混淆名：
 *   - 响应层：所有「无参返回 VipInfoBean」的方法返回合成对象
 *   - 属性层：is_expired() -> 0，getExpires_at() -> 远期
 *   - 数据层：宿主静态字段按类型定位并写入，写入器被拦截后重新覆盖
 */
public final class HookEntry extends XposedModule {

    private static final String TAG = "FpbHook";
    private static final String TARGET = "com.quickwis.fapiaohezi";
    private static final String VIP = "com.quickwis.fapiaohezi.network.response.VipInfoBean";
    private static final String BASE = "com.quickwis.fapiaohezi.network.response.SystemBaseDataResponse";
    private static final String FAR = "2099-12-31 23:59:59";

    /** 已知宿主混淆名，仅作为快速路径；失效会自动走深度扫描 */
    private static final String[] HOST_CANDIDATES = {"di.c", "hi.c", "dj.c", "ki.c", "ji.c"};

    /** 调试用：置 true 可跳过 L0/L1，强制测量深度扫描耗时 */
    private static final boolean FORCE_DEEP_SCAN = false;

    /** 扫描耗时超过该值才弹提示，避免打扰 */
    private static final long SLOW_SCAN_MS = 2000L;

    private volatile boolean installed = false;
    private volatile Object fake = null;
    private Class<?> vipType = null;
    private Context appCtx = null;
    private int appVersionCode = -1;

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
        long t0 = System.currentTimeMillis();

        try {
            vipType = Reflect.load(cl, VIP);
            if (vipType == null) {
                log(Log.ERROR, TAG, "VipInfoBean 未找到，放弃");
                installed = false;
                return;
            }

            fake = Reflect.newVip(vipType, FAR);
            log(Log.INFO, TAG, "合成 VipInfoBean = " + fake);

            // 响应层：所有无参返回 VipInfoBean 的方法
            Class<?> base = Reflect.load(cl, BASE);
            if (base != null) {
                hookReturning(base, vipType, fake);
            } else {
                log(Log.WARN, TAG, "SystemBaseDataResponse 未找到，跳过 getter hook");
            }

            // 属性层：恒定未过期 / 远期
            hookIntNoArg(vipType, "expired", 0);
            hookStringNoArg(vipType, "expire", FAR);

            // 数据层：宿主定位（缓存 -> 候选 -> 深度扫描）
            bindHostWithCache(cl);

            log(Log.INFO, TAG, "装配完成，用时 " + (System.currentTimeMillis() - t0) + "ms");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "装配失败", t);
            installed = false;
        }
    }

    /* ---------------- 宿主定位：L0/L1/L2 ---------------- */

    private void bindHostWithCache(ClassLoader cl) {
        appCtx = Reflect.appContext();
        appVersionCode = Reflect.versionCode(appCtx);
        log(Log.INFO, TAG, "目标版本 versionCode=" + appVersionCode
                + (appCtx == null ? "（无 Context，缓存不可用）" : ""));

        // L0 缓存
        if (appCtx != null && !FORCE_DEEP_SCAN) {
            HostCache.Entry e = HostCache.read(appCtx, appVersionCode);
            if (e != null) {
                Class<?> host = Reflect.load(cl, e.host);
                Field f = host == null ? null : Reflect.vipField(host, vipType);
                if (f != null) {
                    bind(host, f, "缓存命中，上次探测结果");
                    return;
                }
                log(Log.WARN, TAG, "缓存 " + e.host + "." + e.field + " 已失效，重新定位");
                HostCache.clear(appCtx);
            } else {
                log(Log.INFO, TAG, "无可用缓存（首次运行或版本已变化）");
            }
        }

        // L1 候选名
        if (!FORCE_DEEP_SCAN && bindByCandidates(cl)) return;

        // L2 深度扫描
        log(Log.WARN, TAG, "候选名全部失效，启动深度扫描");
        scanHostAsync(cl);
    }

    private boolean bindByCandidates(ClassLoader cl) {
        for (String name : HOST_CANDIDATES) {
            Class<?> host = Reflect.load(cl, name);
            if (host == null) continue;
            Field f = Reflect.vipField(host, vipType);
            if (f == null) continue;
            bind(host, f, "候选名");
            return true;
        }
        return false;
    }

    private void bind(Class<?> host, Field field, String how) {
        Reflect.setStatic(field, fake);
        log(Log.INFO, TAG, "宿主 " + host.getName() + " 字段 " + field.getName() + " 已写入（" + how + "）");
        hookWriters(host, vipType, field);
        if (appCtx != null) {
            HostCache.write(appCtx,
                    new HostCache.Entry(appVersionCode, host.getName(), field.getName(), how));
            log(Log.INFO, TAG, "定位结果已缓存，下次启动直接复用");
        }
    }

    /**
     * 深度扫描：枚举进程内全部 dex 的类名，按「static + 类型 == VipInfoBean」定位宿主。
     * 只在 L0/L1 都失败时执行；扫描结果写回缓存，同一版本不会重复扫描。
     */
    private void scanHostAsync(final ClassLoader cl) {
        final Context ctx = appCtx;

        new Thread(() -> {
            long start = System.currentTimeMillis();
            boolean slowNotified = false;
            try {
                java.util.List<String> names = Reflect.allClassNames(cl);
                log(Log.INFO, TAG, "深度扫描：枚举到 " + names.size() + " 个类名");
                if (names.isEmpty()) {
                    log(Log.WARN, TAG, "dexElements 反射受限，深度扫描不可用，仅依赖 L0/L1 + 响应层/属性层");
                    toast("VIP 模块：本版本无法深度定位，功能可能受限");
                    return;
                }

                log(Log.INFO, TAG, "深度扫描进行中，预计数秒…");
                Class<?> host = Reflect.findHostByNames(names, cl, vipType, start + 30000L);
                long cost = System.currentTimeMillis() - start;

                if (host == null) {
                    log(Log.WARN, TAG, "深度扫描未命中宿主，耗时 " + cost + "ms");
                    toast("VIP 模块：未能定位宿主类（" + cost + "ms）");
                    return;
                }
                Field f = Reflect.vipField(host, vipType);
                if (f == null) return;

                bind(host, f, "深度扫描");
                log(Log.INFO, TAG, "深度扫描命中 " + host.getName() + "，耗时 " + cost + "ms，已缓存");

                if (cost > SLOW_SCAN_MS) {
                    toast("VIP 模块：已适配新版本（扫描 " + (cost / 1000.0) + "s）");
                }
            } catch (Throwable t) {
                log(Log.WARN, TAG, "深度扫描异常: " + t);
            }
        }, "fpb-deep-scan").start();
    }

    private void toast(final String msg) {
        if (appCtx == null) return;
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Toast.makeText(appCtx.getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /* ---------------- hook 原语 ---------------- */

    /** 该类所有「无参且返回 ret」的实例方法 -> 固定返回 value（全部 hook，不只第一个） */
    private void hookReturning(Class<?> owner, Class<?> ret, final Object value) {
        int n = 0;
        for (Method m : owner.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() != 0) continue;
            if (!m.getReturnType().equals(ret)) continue;
            if (!installable(m)) continue;
            try {
                hook(m).intercept(chain -> value);
                n++;
                log(Log.INFO, TAG, "hook " + owner.getSimpleName() + "." + m.getName() + "() -> 合成对象");
            } catch (Throwable t) {
                log(Log.WARN, TAG, "hook " + m.getName() + " 失败: " + t);
            }
        }
        if (n == 0) log(Log.WARN, TAG, owner.getSimpleName() + " 未发现返回 VipInfoBean 的无参方法");
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
