package com.yg.fpbhook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * 纯反射工具，不依赖任何混淆名。
 * 唯一硬编码的是业务包内的实体类全名，这类名字 R8 不会改。
 */
final class Reflect {

    private Reflect() {
    }

    static Class<?> load(ClassLoader cl, String name) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 反射构造一个「未过期」的 VipInfoBean。
     * 不写死构造签名：取参数最多的构造器，按参数类型填默认值。
     * 任何 int 填 0 —— 保证 is_expired == 0。
     */
    static Object newVip(Class<?> cls, String farExpiresAt) {
        if (cls == null) return null;
        Constructor<?> best = null;
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            if (best == null || c.getParameterCount() > best.getParameterCount()) {
                best = c;
            }
        }
        if (best == null) return null;
        best.setAccessible(true);
        Class<?>[] pts = best.getParameterTypes();
        Object[] args = new Object[pts.length];
        for (int i = 0; i < pts.length; i++) {
            Class<?> t = pts[i];
            if (t == int.class) args[i] = 0;
            else if (t == long.class) args[i] = 0L;
            else if (t == boolean.class) args[i] = Boolean.FALSE;
            else if (t == String.class) args[i] = farExpiresAt;
            else args[i] = null;
        }
        try {
            return best.newInstance(args);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 找宿主类中「静态且类型为 VipInfoBean」的字段，按类型找，不按名字找。 */
    static Field vipField(Class<?> host, Class<?> vipType) {
        if (host == null || vipType == null) return null;
        for (Field f : host.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (!f.getType().equals(vipType)) continue;
            f.setAccessible(true);
            return f;
        }
        return null;
    }

    static void setStatic(Field f, Object value) {
        if (f == null || value == null) return;
        try {
            f.set(null, value);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 深度搜索第一步：枚举当前 ClassLoader 装载的全部 dex 里的类名。
     *
     * 走 BaseDexClassLoader.pathList.dexElements[].dexFile.entries()，
     * 这样即使 AccountHelper 的类名每版都变，也能在运行时把候选集扩展到全量，
     * 而不再依赖硬编码的候选名单。
     *
     * 反射隐藏 API 在 LSPosed 注入的进程里通常可用；失败时返回空表，上层退化到候选名单。
     */
    static List<String> allClassNames(ClassLoader cl) {
        List<String> out = new ArrayList<>();
        if (cl == null) return out;
        try {
            Class<?> bdcl = Class.forName("dalvik.system.BaseDexClassLoader");
            Field pathListField = bdcl.getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(cl);
            if (pathList == null) return out;

            Field elemsField = pathList.getClass().getDeclaredField("dexElements");
            elemsField.setAccessible(true);
            Object[] elements = (Object[]) elemsField.get(pathList);
            if (elements == null) return out;

            for (Object element : elements) {
                try {
                    Field dexFileField = element.getClass().getDeclaredField("dexFile");
                    dexFileField.setAccessible(true);
                    Object dexFile = dexFileField.get(element);
                    if (dexFile == null) continue;
                    Object entries = dexFile.getClass().getMethod("entries").invoke(dexFile);
                    if (!(entries instanceof Enumeration)) continue;
                    Enumeration<?> en = (Enumeration<?>) entries;
                    while (en.hasMoreElements()) {
                        Object n = en.nextElement();
                        if (n instanceof String) out.add((String) n);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 深度搜索第二步：按「静态 + 类型为 VipInfoBean」特征在类名集合里找宿主类 */
    static Class<?> findHostByNames(List<String> names, ClassLoader cl, Class<?> vipType,
                                    long deadlineMs) {
        for (String name : names) {
            if (System.currentTimeMillis() > deadlineMs) return null;
            if (name == null) continue;
            // 跳过系统/三方包，减少无效 forName
            if (name.startsWith("java.") || name.startsWith("javax.")
                    || name.startsWith("android.") || name.startsWith("androidx.")
                    || name.startsWith("kotlin.") || name.startsWith("kotlinx.")
                    || name.startsWith("dalvik.") || name.startsWith("com.google.")
                    || name.startsWith("org.jetbrains.") || name.startsWith("com.tencent.")
                    || name.startsWith("com.alibaba.") || name.startsWith("com.taobao.")
                    || name.startsWith("com.umeng.") || name.startsWith("com.bytedance.")) {
                continue;
            }
            try {
                Class<?> c = Class.forName(name, false, cl);
                if (vipField(c, vipType) != null) return c;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
