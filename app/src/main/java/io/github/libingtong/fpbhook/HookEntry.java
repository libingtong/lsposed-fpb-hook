package io.github.libingtong.fpbhook;

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
 * 装配分两个时机，因为 Context 的可用性不同：
 *
 *   onPackageLoaded（ClassLoader 就绪、Application 尚未创建）
 *       -> 只做响应层与属性层 hook，并预先加载 VipInfoBean 类型
 *
 *   onPackageReady（Application 已创建，Context 可用）
 *       -> 做宿主绑定：读缓存 -> 候选名 -> 深度扫描
 *
 * 宿主定位优先级：
 *   L0 缓存     目标应用 files/fpb_hook_target.cfg，带 versionCode 校验；
 *               版本没变直接命中（毫秒级），版本变了自动作废
 *   L1 候选名   已知混淆名 di.c / hi.c …（O(1)）
 *   L2 深度扫描 枚举全部 dex 类名按类型特征查找，只在 L0/L1 全失效时执行一次
 */
public final class HookEntry extends XposedModule {

    private static final String TAG = "FpbHook";
    private static final String TARGET = "com.quickwis.fapiaohezi";
    private static final String VIP = "com.quickwis.fapiaohezi.network.response.VipInfoBean";
    private static final String BASE = "com.quickwis.fapiaohezi.network.response.SystemBaseDataResponse";
    private static final String FAR = "2099-12-31 23:59:59";

    private static final String[] HOST_CANDIDATES = {"di.c", "hi.c", "dj.c", "ki.c", "ji.c"};

    /** 调试用：置 true 可跳过 L0/L1，强制测量深度扫描耗时 */
    private static final boolean FORCE_DEEP_SCAN = false;

    /** 扫描耗时超过该值才弹提示，避免打扰 */
    private static final long SLOW_SCAN_MS = 2000L;

    private ClassLoader cl = null;
    private Class<?> vipType = null;
    private Object fake = null;
    private Context appCtx = null;
    private int appVersionCode = -1;

    private volatile boolean hooksInstalled = false;
    private volatile boolean hostBound = false;

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
        prepare(param.getDefaultClassLoader());
        installHooks();
        // Application 还没创建，通常拿不到 Context；能拿到就直接绑定
        if (Reflect.appContext() != null) installHost();
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET.equals(param.getPackageName())) return;
        prepare(param.getClassLoader());
        installHooks();
        installHost();
    }

    /* ---------------- 装配 ---------------- */

    private synchronized void prepare(ClassLoader loader) {
        if (vipType != null || loader == null) return;
        cl = loader;
        vipType = Reflect.load(loader, VIP);
        if (vipType == null) {
            log(Log.ERROR, TAG, "VipInfoBean 未找到");
            return;
        }
        fake = Reflect.newVip(vipType, FAR);
        log(Log.INFO, TAG, "合成 VipInfoBean = " + fake);
    }

    /** 响应层 + 属性层：不依赖 Context，尽早安装 */
    private synchronized void installHooks() {
        if (hooksInstalled || vipType == null || cl == null) return;
        hooksInstalled = true;
        long t0 = System.currentTimeMillis();
        try {
            Class<?> base = Reflect.load(cl, BASE);
            if (base != null) {
                hookReturning(base, vipType, fake);
            } else {
                log(Log.WARN, TAG, "SystemBaseDataResponse 未找到");
            }
            hookIntNoArg(vipType, "expired", 0);
            hookStringNoArg(vipType, "expire", FAR);
            log(Log.INFO, TAG, "响应层/属性层装配完成，用时 " + (System.currentTimeMillis() - t0) + "ms");

            // Context 的可靠来源：Application.onCreate 一定早于业务网络请求
            hookApplicationCreate();
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "installHooks 失败", t);
        }
    }

    /**
     * hook Application.onCreate 拿到 Application 实例。
     * libxposed 的 onPackageReady 在部分设备/Rom 上不一定回调，
     * 而 Application.onCreate 是必过的早起点，用它作为 Context 的主来源。
     */
    private void hookApplicationCreate() {
        try {
            Class<?> appCls = Class.forName("android.app.Application");
            Method onCreate = appCls.getDeclaredMethod("onCreate");
            onCreate.setAccessible(true);
            hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Context && appCtx == null) {
                        appCtx = (Context) self;
                        log(Log.INFO, TAG, "从 Application.onCreate 捕获 Context: "
                                + ((Context) self).getPackageName());
                        installHost();
                    }
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "onCreate 内绑定失败: " + t);
                }
                return result;
            });
            log(Log.INFO, TAG, "已 hook Application.onCreate 以获取 Context");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "hook Application.onCreate 失败: " + t);
        }
    }

    /** 数据层：定位宿主并注入（依赖 Context 才能用缓存） */
    private synchronized void installHost() {
        if (hostBound || vipType == null || cl == null) return;

        appCtx = Reflect.appContext();
        if (appCtx == null) {
            log(Log.WARN, TAG, "Context 尚不可用，宿主绑定推迟到下次回调");
            return; // 不置位，等 onPackageReady
        }
        appVersionCode = Reflect.versionCode(appCtx);
        log(Log.INFO, TAG, "目标 versionCode=" + appVersionCode
                + " 包名=" + appCtx.getPackageName());

        // L0 缓存
        if (!FORCE_DEEP_SCAN) {
            HostCache.Entry e = HostCache.read(appCtx, appVersionCode);
            if (e != null) {
                Class<?> host = Reflect.load(cl, e.host);
                Field f = host == null ? null : Reflect.vipField(host, vipType);
                if (f != null) {
                    hostBound = true;
                    bind(host, f, "缓存命中（上次探测结果）");
                    return;
                }
                log(Log.WARN, TAG, "缓存 " + e.host + "." + e.field + " 已失效，重新定位");
                HostCache.clear(appCtx);
            } else {
                log(Log.INFO, TAG, "无可用缓存（首次运行或版本已变化）");
            }
        }

        // L1 候选名
        if (!FORCE_DEEP_SCAN && bindByCandidates()) {
            hostBound = true;
            return;
        }

        // L2 深度扫描
        hostBound = true; // 防止重复触发
        log(Log.WARN, TAG, "候选名全部失效，启动深度扫描");
        scanHostAsync();
    }

    private boolean bindByCandidates() {
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
        if (appCtx != null && appVersionCode > 0) {
            HostCache.write(appCtx,
                    new HostCache.Entry(appVersionCode, host.getName(), field.getName(), how));
            log(Log.INFO, TAG, "定位结果已缓存 vc=" + appVersionCode + "，下次启动直接复用");
        }
    }

    /** 深度扫描：枚举全部 dex 类名，按类型特征定位宿主；结果写回缓存 */
    private void scanHostAsync() {
        final Context ctx = appCtx;
        new Thread(() -> {
            long start = System.currentTimeMillis();
            final Handler main = new Handler(Looper.getMainLooper());
            // 只有在扫描真的慢时才打扰用户；实测通常 100~300ms，不会触发
            final Runnable hint = () -> toast("VIP 模块：正在适配新版本，请稍候");
            if (ctx != null) main.postDelayed(hint, 800L);
            try {
                java.util.List<String> names = Reflect.allClassNames(cl);
                log(Log.INFO, TAG, "深度扫描：枚举到 " + names.size() + " 个类名");
                if (names.isEmpty()) {
                    log(Log.WARN, TAG, "dexElements 反射受限，深度扫描不可用");
                    toast("VIP 模块：无法深度定位，功能可能受限");
                    return;
                }

                Class<?> host = Reflect.findHostByNames(names, cl, vipType, start + 30000L);
                long cost = System.currentTimeMillis() - start;

                if (host == null) {
                    log(Log.WARN, TAG, "深度扫描未命中，耗时 " + cost + "ms");
                    toast("VIP 模块：未定位到宿主（" + cost + "ms）");
                    return;
                }
                Field f = Reflect.vipField(host, vipType);
                if (f == null) return;

                bind(host, f, "深度扫描");
                log(Log.INFO, TAG, "深度扫描命中 " + host.getName() + "，耗时 " + cost + "ms，已缓存");
                if (cost > SLOW_SCAN_MS) {
                    toast("VIP 模块：已完成新版本适配（扫描 " + (cost / 1000.0) + "s）");
                }
            } catch (Throwable t) {
                log(Log.WARN, TAG, "深度扫描异常: " + t);
            } finally {
                main.removeCallbacks(hint);
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

    private static boolean installable(Method m) {
        if (m.isSynthetic() || m.isBridge()) return false;
        try {
            m.setAccessible(true);
        } catch (Throwable ignored) {
        }
        return true;
    }
}

