package io.github.libingtong.fpbhook;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 宿主类定位结果缓存。
 *
 * 缓存写在**目标应用自己的私有目录**（files/fpb_hook_target.cfg），因为：
 *  - 模块进程与目标进程不同 uid，互相读不到对方 data 目录；
 *  - LSPosed 的 remote prefs 需要模块 APP 主动写入，链路更长；
 *  - 目标应用私有目录由被 hook 的进程直接可写，零权限问题。
 *
 * 缓存内容带 versionCode：应用升级后自动作废，只在"新版本第一次启动"时才做一次
 * 代价较高的定位（候选名或深度扫描），之后都走缓存，毫秒级。
 */
final class HostCache {

    private static final String FILE_NAME = "fpb_hook_target.cfg";

    static final class Entry {
        final int versionCode;
        final String host;
        final String field;
        final String via;

        Entry(int versionCode, String host, String field, String via) {
            this.versionCode = versionCode;
            this.host = host;
            this.field = field;
            this.via = via;
        }

        @Override
        public String toString() {
            return "Entry{vc=" + versionCode + ", host=" + host + ", field=" + field + ", via=" + via + "}";
        }
    }

    private HostCache() {
    }

    private static File file(Context ctx) {
        if (ctx == null) return null;
        try {
            File dir = ctx.getFilesDir();
            if (dir == null) return null;
            return new File(dir, FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读取缓存；versionCode 不匹配或内容残缺返回 null */
    static Entry read(Context ctx, int currentVersionCode) {
        File f = file(ctx);
        if (f == null || !f.isFile()) return null;
        String text;
        try {
            text = readAll(f);
        } catch (Throwable t) {
            return null;
        }
        int vc = -1;
        String host = null;
        String field = null;
        String via = null;
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            if (k.equals("vc")) vc = parseInt(v, -1);
            else if (k.equals("host")) host = v;
            else if (k.equals("field")) field = v;
            else if (k.equals("via")) via = v;
        }
        if (vc != currentVersionCode) return null;
        if (host == null || field == null || host.isEmpty() || field.isEmpty()) return null;
        return new Entry(vc, host, field, via == null ? "cache" : via);
    }

    static void write(Context ctx, Entry e) {
        File f = file(ctx);
        if (f == null || e == null) return;
        String body = "vc=" + e.versionCode + "\n"
                + "host=" + e.host + "\n"
                + "field=" + e.field + "\n"
                + "via=" + e.via + "\n";
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f, false);
            out.write(body.getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            if (out != null) try {
                out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    static void clear(Context ctx) {
        File f = file(ctx);
        if (f != null) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static String readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) Math.min(f.length(), 4096)];
            int n = in.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, "UTF-8");
        } finally {
            in.close();
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}

