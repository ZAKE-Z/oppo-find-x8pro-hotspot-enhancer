package dev.hotspot.enhancer;

import android.content.Context;
import android.net.wifi.SoftApConfiguration;
import android.provider.Settings;
import android.util.Log;
import android.util.SparseIntArray;

import java.lang.reflect.Method;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public final class Utils {

    private Utils() {}

    public static Context getAnyContext(Context fallback) {
        if (fallback != null) return fallback;
        try {
            Class<?> atCls = Class.forName("android.app.ActivityThread");
            Object app = atCls.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) {}
        return null;
    }

    public static boolean isDualEnabled(Context ctx) {
        ctx = getAnyContext(ctx);
        if (ctx == null) return false;
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), Config.KEY_DUAL_ENABLED, 0) == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isDualConfig(SoftApConfiguration config) {
        if (config == null) return false;
        try {
            SparseIntArray channels = config.getChannels();
            return channels != null && channels.size() == 2
                && channels.indexOfKey(1) >= 0 && channels.indexOfKey(2) >= 0;
        } catch (Throwable ignored) {}
        return false;
    }

    public static SoftApConfiguration makeBridgedConfig(SoftApConfiguration base) {
        SoftApConfiguration.Builder b;
        if (base != null) {
            b = new SoftApConfiguration.Builder(base);
        } else {
            b = new SoftApConfiguration.Builder();
        }
        b.setBands(new int[]{1, 2});
        return b.build();
    }

    public static CharSequence[] appendDual(CharSequence[] arr) {
        if (arr == null || arr.length == 0) return arr;
        CharSequence first = arr[0];
        if (first == null) return arr;
        String s = first.toString();
        if (!s.startsWith("2.4") && !s.startsWith("5") && !s.equals(Config.DUAL_LABEL)) return arr;
        CharSequence last = arr[arr.length - 1];
        if (Config.DUAL_LABEL.equals(last)) return arr;
        CharSequence[] res = new CharSequence[arr.length + 1];
        System.arraycopy(arr, 0, res, 0, arr.length);
        res[arr.length] = Config.DUAL_LABEL;
        return res;
    }

    public static String[] appendDualStrings(String[] arr) {
        if (arr == null || arr.length == 0) return arr;
        String first = arr[0];
        if (first == null) return arr;
        if (!first.startsWith("2.4") && !first.startsWith("5") && !first.equals(Config.DUAL_LABEL)) return arr;
        String last = arr[arr.length - 1];
        if (Config.DUAL_LABEL.equals(last)) return arr;
        String[] res = new String[arr.length + 1];
        System.arraycopy(arr, 0, res, 0, arr.length);
        res[arr.length] = Config.DUAL_LABEL;
        return res;
    }

    public static boolean validIp(String s) {
        if (s == null) return false;
        String[] parts = s.trim().split("\\.");
        if (parts.length != 4) return false;
        long val = 0;
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("\\d{1,3}")) return false;
            int octet = Integer.parseInt(parts[i]);
            if (octet > 255) return false;
            val = (val << 8) | octet;
        }
        return val != 0 && val != 0xFFFFFFFFL;
    }

    public static long ipToInt(String s) {
        String[] parts = s.trim().split("\\.");
        long val = 0;
        for (int i = 0; i < 4; i++) {
            val = (val << 8) | (Integer.parseInt(parts[i]) & 255);
        }
        return val;
    }

    public static boolean subnetInUse(String ip) {
        try {
            long net = ipToInt(ip) & 0xFFFFFF00L;
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                for (InterfaceAddress addr : ifaces.nextElement().getInterfaceAddresses()) {
                    byte[] raw = addr.getAddress().getAddress();
                    if (raw.length != 4) continue;
                    long val = ((long) (raw[0] & 255) << 24) | ((long) (raw[1] & 255) << 16)
                            | ((long) (raw[2] & 255) << 8) | (raw[3] & 255);
                    if ((val & 0xFFFFFF00L) == net) return true;
                }
            }
        } catch (Throwable t) {
            Log.w(Config.TAG, "conflict check failed, assuming free", t);
        }
        return false;
    }

    public static String getGlobalString(Context ctx, String key) {
        ctx = getAnyContext(ctx);
        if (ctx == null) return null;
        try {
            return Settings.Global.getString(ctx.getContentResolver(), key);
        } catch (Throwable t) {
            Log.e(Config.TAG, "getGlobalString failed for key: " + key, t);
            return null;
        }
    }

    public static void putGlobalString(Context ctx, String key, String value) {
        ctx = getAnyContext(ctx);
        if (ctx == null) return;
        try {
            Settings.Global.putString(ctx.getContentResolver(), key, value);
        } catch (Throwable t) {
            Log.e(Config.TAG, "putGlobalString failed for key: " + key, t);
        }
    }
}
