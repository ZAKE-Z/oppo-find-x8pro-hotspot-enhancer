package dev.hotspot.enhancer;

import android.content.Context;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MainHook implements IXposedHookLoadPackage {

    private static final String NET_PKG = "com.android.networkstack.tethering";
    private static final String UI_PKG = "com.oplus.wirelesssettings";
    private static final String PANEL_FRAG = "com.oplus.wirelesssettings.wifi.tether.WifiTetherPanelFragment";

    private static boolean sApexHooked = false;

    // --- State tracking for custom gateway input ---
    private static volatile String draftGateway = "";
    private static volatile boolean gatewayEdited = false;
    private static volatile WeakReference<Object> panelRef = new WeakReference<>(null);

    private static void log(String msg) {
        Log.i(Config.TAG, msg);
        XposedBridge.log(msg);
    }

    private static void tryHook(String className, ClassLoader cl, String method, Object... args) {
        try {
            XposedHelpers.findAndHookMethod(className, cl, method, args);
            log("hooked: " + className + "#" + method);
        } catch (Throwable t) {
            log("hook FAILED: " + className + "#" + method + " -> " + t.getMessage());
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (NET_PKG.equals(lpparam.packageName)) {
            hookIpServer(lpparam.classLoader);
        } else if (UI_PKG.equals(lpparam.packageName)) {
            hookHotspotUi(lpparam.classLoader);
        } else if ("android".equals(lpparam.packageName)) {
            hookSystem(lpparam);
        }
    }

    // =========================================================================
    // Part 1: Fixed Gateway IP Server Hook (NetworkStack)
    // =========================================================================

    private void hookIpServer(final ClassLoader cl) {
        try {
            Class<?> server = Class.forName("android.net.ip.IpServer", false, cl);
            final Class<?> linkAddress = Class.forName("android.net.LinkAddress", true, cl);
            final Field type = server.getDeclaredField("mInterfaceType");
            type.setAccessible(true);

            XposedHelpers.findAndHookMethod(server, "requestIpv4Address", int.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (type.getInt(param.thisObject) != 0) return; // Wi-Fi hotspot only
                            String gw = Utils.getGlobalString(null, Config.KEY_FIXED_GATEWAY);
                            if (gw == null || gw.isEmpty()) return;
                            if (!Utils.validIp(gw)) {
                                Log.w(Config.TAG, "ignoring invalid gateway setting: " + gw);
                                return;
                            }
                            if (Utils.subnetInUse(gw)) {
                                Log.w(Config.TAG, "gateway subnet conflicts with existing interface; using system choice");
                                return;
                            }
                            param.setResult(linkAddress.getConstructor(String.class).newInstance(gw + "/24"));
                            log("hotspot gateway override applied: " + gw);
                        } catch (Throwable t) {
                            Log.e(Config.TAG, "gateway override failed; falling back to system", t);
                        }
                    }
                });
            log("IpServer hook installed");
        } catch (Throwable t) {
            log("IpServer hook install failed: " + t.getMessage());
        }
    }

    // =========================================================================
    // Part 2: Hotspot UI & Band / Gateway Settings (WirelessSettings)
    // =========================================================================

    private void hookHotspotUi(final ClassLoader cl) {
        log("hookHotspotUi: hooking com.oplus.wirelesssettings");

        // 1. WifiManager capabilities query
        tryHook("android.net.wifi.WifiManager", cl, "isBridgedApConcurrencySupported",
            new ReturnConstantHook(true));
        tryHook("android.net.wifi.WifiManager", cl, "isStaBridgedApConcurrencySupported",
            new ReturnConstantHook(true));

        // 2. initBandValues -> append Dual-Band option to dropdown list
        tryHook("com.android.settings.wifi.tether.WifiTetherApBandPreferenceController", cl, "initBandValues",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getResult() instanceof String[]) {
                        param.setResult(Utils.appendDualStrings((String[]) param.getResult()));
                    }
                }
            });

        // 3. COUIMenuPreference.setEntries & setEntryValues
        XC_MethodHook entriesSetterHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length > 0 && param.args[0] instanceof CharSequence[]) {
                    param.args[0] = Utils.appendDual((CharSequence[]) param.args[0]);
                }
            }
        };
        tryHook("com.coui.appcompat.preference.COUIMenuPreference", cl, "setEntries",
            CharSequence[].class, entriesSetterHook);
        tryHook("com.coui.appcompat.preference.COUIMenuPreference", cl, "setEntryValues",
            CharSequence[].class, entriesSetterHook);

        // 4. COUIMenuPreference.getEntries & getEntryValues
        XC_MethodHook entriesGetterHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.getResult() instanceof CharSequence[]) {
                    param.setResult(Utils.appendDual((CharSequence[]) param.getResult()));
                }
            }
        };
        tryHook("com.coui.appcompat.preference.COUIMenuPreference", cl, "getEntries",
            entriesGetterHook);
        tryHook("com.coui.appcompat.preference.COUIMenuPreference", cl, "getEntryValues",
            entriesGetterHook);

        // 5. initBandIndex -> select Dual-Band if dual is active
        tryHook("com.android.settings.wifi.tether.WifiTetherApBandPreferenceController", cl, "initBandIndex",
            "android.net.wifi.SoftApConfiguration", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Context ctx = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                        SoftApConfiguration config = (SoftApConfiguration) param.args[0];
                        if (Utils.isDualEnabled(ctx) || Utils.isDualConfig(config)) {
                            Object pref = XposedHelpers.getObjectField(param.thisObject, "mBandPreference");
                            if (pref != null) {
                                String label = Config.getDualLabel();
                                int idx = (Integer) XposedHelpers.callMethod(pref, "findIndexOfValue", label);
                                if (idx < 0) {
                                    // Fallback to check other language representation
                                    String alt = Config.DUAL_LABEL_ZH.equals(label) ? Config.DUAL_LABEL_EN : Config.DUAL_LABEL_ZH;
                                    idx = (Integer) XposedHelpers.callMethod(pref, "findIndexOfValue", alt);
                                }
                                if (idx >= 0) {
                                    param.setResult(idx);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        log("initBandIndex hook err: " + t.getMessage());
                    }
                }
            });

        // 6. covertBandTypeOfBindIndex -> return 3 (BAND_2GHZ | BAND_5GHZ) for Dual-Band
        tryHook("com.android.settings.wifi.tether.WifiTetherApBandPreferenceController", cl, "covertBandTypeOfBindIndex",
            String[].class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        String[] vals = (String[]) param.args[0];
                        int idx = (Integer) param.args[1];
                        if (vals != null && idx >= 0 && idx < vals.length) {
                            if (Config.isDualLabel(vals[idx])) {
                                param.setResult(3);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });

        // 7. onPreferenceChange -> persist dual band immediately & write flag
        tryHook("com.android.settings.wifi.tether.WifiTetherApBandPreferenceController", cl, "onPreferenceChange",
            "androidx.preference.Preference", "java.lang.Object", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String val = (String) param.args[1];
                        Context ctx = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                        if (ctx == null) return;
                        boolean isDual = Config.isDualLabel(val);
                        Settings.Global.putInt(ctx.getContentResolver(), Config.KEY_DUAL_ENABLED, isDual ? 1 : 0);
                        WifiManager wm = ctx.getSystemService(WifiManager.class);
                        if (wm != null) {
                            SoftApConfiguration cur = wm.getSoftApConfiguration();
                            if (cur != null) {
                                SoftApConfiguration.Builder b = new SoftApConfiguration.Builder(cur);
                                if (isDual) {
                                    b.setBands(new int[]{1, 2});
                                } else if (val != null && val.startsWith("5")) {
                                    b.setBand(2);
                                } else {
                                    b.setBand(1);
                                }
                                wm.setSoftApConfiguration(b.build());
                                log("onPreferenceChange: applied band for " + val);
                            }
                        }
                    } catch (Throwable t) {
                        log("onPreferenceChange hook err: " + t.getMessage());
                    }
                }
            });

        // 8. COUIPreference.setAssignment -> force Dual-Band label
        tryHook("com.coui.appcompat.preference.COUIPreference", cl, "setAssignment",
            CharSequence.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String key = (String) XposedHelpers.callMethod(param.thisObject, "getKey");
                        if ("band".equals(key)) {
                            Context ctx = (Context) XposedHelpers.callMethod(param.thisObject, "getContext");
                            if (Utils.isDualEnabled(ctx)) {
                                param.args[0] = Config.getDualLabel();
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });

        // 9. updateCurrentApConfig -> ensure display assignment
        tryHook("com.android.settings.wifi.tether.WifiTetherApBandPreferenceController", cl, "updateCurrentApConfig",
            "android.net.wifi.SoftApConfiguration", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Context ctx = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                        if (Utils.isDualEnabled(ctx)) {
                            Object pref = XposedHelpers.getObjectField(param.thisObject, "mBandPreference");
                            if (pref != null) {
                                XposedHelpers.callMethod(pref, "setAssignment", Config.getDualLabel());
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });

        // 10. Gateway input UI injection
        try {
            Class<?> frag = Class.forName("com.android.settings.wifi.tether.WifiTetherSettings", false, cl);
            XposedHelpers.findAndHookMethod(frag, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        addGatewayOption(param.thisObject, cl);
                    } catch (Throwable t) {
                        Log.e(Config.TAG, "gateway option injection failed", t);
                    }
                }
            });
        } catch (Throwable t) {
            log("WifiTetherSettings hook failed: " + t.getMessage());
        }

        // 11. Panel fragment save button and lifecycle handling
        try {
            Class<?> panel = Class.forName(PANEL_FRAG, false, cl);
            XposedHelpers.findAndHookMethod(panel, "initView", View.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    panelRef = new WeakReference<>(param.thisObject);
                }
            });
            XposedHelpers.findAndHookMethod(panel, "onShow", Boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    gatewayEdited = false;
                    String g = Utils.getGlobalString(null, Config.KEY_FIXED_GATEWAY);
                    draftGateway = (g == null) ? "" : g.trim();
                }
            });
            XposedHelpers.findAndHookMethod(panel, "i", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (gatewayEdited) {
                        boolean pageOk = (Boolean) param.args[0];
                        String v = draftGateway == null ? "" : draftGateway.trim();
                        boolean gwOk = v.isEmpty() || Utils.validIp(v);
                        param.args[0] = pageOk && gwOk;
                    }
                }
            });
            Class<?> clickCls = Class.forName("com.oplus.wirelesssettings.wifi.tether.M", false, cl);
            XposedHelpers.findAndHookMethod(clickCls, "onMenuItemClick", MenuItem.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Field af = param.thisObject.getClass().getDeclaredField("a");
                        af.setAccessible(true);
                        if (af.getInt(param.thisObject) == 1) { // 1 = Save
                            commitGatewayDraft();
                        }
                    } catch (Throwable t) {
                        Log.e(Config.TAG, "commit hook failed", t);
                    }
                }
            });
        } catch (Throwable t) {
            log("Panel fragment hooks failed: " + t.getMessage());
        }

        // 12. WifiManager.setSoftApConfiguration inside settings -> protect against single-band overwrite
        tryHook("android.net.wifi.WifiManager", cl, "setSoftApConfiguration",
            "android.net.wifi.SoftApConfiguration", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Context ctx = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                        if (Utils.isDualEnabled(ctx)) {
                            SoftApConfiguration cfg = (SoftApConfiguration) param.args[0];
                            if (cfg != null && !Utils.isDualConfig(cfg)) {
                                param.args[0] = Utils.makeBridgedConfig(cfg);
                                log("settings WifiManager.setSoftApConfiguration: protected bridged bands");
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });
    }

    // =========================================================================
    // Part 3: Framework & System Server Hooks (system_server)
    // =========================================================================

    private void hookSystem(XC_LoadPackage.LoadPackageParam lpparam) {
        log("hookSystem: injecting into system_server");
        ClassLoader cl = lpparam.classLoader;

        tryHook("android.net.wifi.WifiManager", cl, "isBridgedApConcurrencySupported",
            new ReturnConstantHook(true));

        tryHook("com.android.server.SystemServiceManager", cl, "startServiceFromJar",
            String.class, String.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String className = (String) param.args[0];
                    if ("com.android.server.wifi.WifiService".equals(className)) {
                        Object service = param.getResult();
                        if (service != null) {
                            hookWifiApex(service.getClass().getClassLoader());
                        }
                    }
                }
            });

        try {
            Class<?> c = Class.forName("com.android.server.wifi.WifiNative", false, cl);
            hookWifiApex(c.getClassLoader());
        } catch (Throwable ignored) {}
    }

    public synchronized void hookWifiApex(ClassLoader cl) {
        if (sApexHooked || cl == null) return;
        sApexHooked = true;
        log("hookWifiApex: injecting into service-wifi classes...");

        // 1. ApConfigUtil.isBridgedModeSupported -> true
        tryHook("com.android.server.wifi.util.ApConfigUtil", cl, "isBridgedModeSupported",
            "android.net.wifi.WifiContext", "com.android.server.wifi.WifiNative",
            new ReturnConstantHook(true));

        // 2. ApConfigUtil.isBridgedModeSupportedInConfig -> true
        tryHook("com.android.server.wifi.util.ApConfigUtil", cl, "isBridgedModeSupportedInConfig",
            "android.net.wifi.WifiContext",
            new ReturnConstantHook(true));

        // 3. ApConfigUtil.isStaWithBridgedModeSupported -> true
        tryHook("com.android.server.wifi.util.ApConfigUtil", cl, "isStaWithBridgedModeSupported",
            "android.net.wifi.WifiContext", "com.android.server.wifi.WifiNative",
            new ReturnConstantHook(true));

        // 4. WifiServiceImpl.startTetheredHotspot -> rewrite to bridged if dual enabled
        tryHook("com.android.server.wifi.WifiServiceImpl", cl, "startTetheredHotspot",
            "android.net.wifi.SoftApConfiguration", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Context ctx = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                        if (Utils.isDualEnabled(ctx)) {
                            SoftApConfiguration cfg = (SoftApConfiguration) param.args[0];
                            if (cfg != null && !Utils.isDualConfig(cfg)) {
                                param.args[0] = Utils.makeBridgedConfig(cfg);
                                log("startTetheredHotspot: request rewritten to bridged bands");
                            }
                        }
                    } catch (Throwable t) {
                        log("startTetheredHotspot hook err: " + t.getMessage());
                    }
                }
            });

        // 5. WifiServiceImpl.startTetheredHotspotInternal -> rewrite to bridged
        tryHook("com.android.server.wifi.WifiServiceImpl", cl, "startTetheredHotspotInternal",
            "com.android.server.wifi.SoftApModeConfiguration", int.class, String.class, "android.net.wifi.ISoftApCallback",
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Context ctx = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                        if (Utils.isDualEnabled(ctx)) {
                            Object modeConfig = param.args[0];
                            if (modeConfig != null) {
                                SoftApConfiguration cfg = (SoftApConfiguration) XposedHelpers.getObjectField(modeConfig, "mSoftApConfig");
                                if (cfg != null && !Utils.isDualConfig(cfg)) {
                                    SoftApConfiguration bridged = Utils.makeBridgedConfig(cfg);
                                    XposedHelpers.setObjectField(modeConfig, "mSoftApConfig", bridged);
                                    log("startTetheredHotspotInternal: modeConfig rewritten to bridged bands");
                                }
                            }
                        }
                    } catch (Throwable t) {
                        log("startTetheredHotspotInternal hook err: " + t.getMessage());
                    }
                }
            });

        // 6. WifiServiceImpl.setSoftApConfiguration -> protect against single-band overwrite
        tryHook("com.android.server.wifi.WifiServiceImpl", cl, "setSoftApConfiguration",
            "android.net.wifi.SoftApConfiguration", String.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Context ctx = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
                        if (Utils.isDualEnabled(ctx)) {
                            SoftApConfiguration cfg = (SoftApConfiguration) param.args[0];
                            if (cfg != null && !Utils.isDualConfig(cfg)) {
                                param.args[0] = Utils.makeBridgedConfig(cfg);
                                log("WifiServiceImpl.setSoftApConfiguration: protected bridged bands");
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });
    }

    // =========================================================================
    // UI Helpers for Gateway Preference Insertion
    // =========================================================================

    private static void commitGatewayDraft() {
        String v = draftGateway == null ? "" : draftGateway.trim();
        if (v.isEmpty()) {
            Utils.putGlobalString(null, Config.KEY_FIXED_GATEWAY, "");
        } else if (Utils.validIp(v)) {
            Utils.putGlobalString(null, Config.KEY_FIXED_GATEWAY, v);
        }
    }

    private static void pushSaveState() {
        Object panel = panelRef == null ? null : panelRef.get();
        if (panel == null) return;
        try {
            Method m = panel.getClass().getMethod("i", boolean.class);
            m.setAccessible(true);
            String v = draftGateway == null ? "" : draftGateway.trim();
            m.invoke(panel, v.isEmpty() || Utils.validIp(v));
        } catch (Throwable ignored) {}
    }

    private static void initNullLocks(Object o) {
        try {
            Class<?> c = o.getClass();
            while (c != null && c != Object.class) {
                for (Field fld : c.getDeclaredFields()) {
                    if ("mLock".equals(fld.getName()) && !fld.getType().isPrimitive()) {
                        fld.setAccessible(true);
                        if (fld.get(o) == null) {
                            fld.set(o, new Object());
                        }
                        return;
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
    }

    private static Method findMethod(Class<?> c, String name, int paramCount) {
        for (Method m : c.getMethods()) {
            if (name.equals(m.getName()) && m.getParameterTypes().length == paramCount) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static void invokeFirst(Class<?> c, Object target, String name, Object value) throws Exception {
        for (Method mm : c.getMethods()) {
            if (!name.equals(mm.getName()) || mm.getParameterTypes().length != 1) continue;
            Class<?> pt = mm.getParameterTypes()[0];
            boolean match;
            if (pt.isPrimitive()) {
                match = (pt == boolean.class && value instanceof Boolean)
                        || (pt == int.class && value instanceof Integer)
                        || (pt == long.class && value instanceof Long);
            } else {
                match = pt.isInstance(value);
            }
            if (match) {
                mm.setAccessible(true);
                mm.invoke(target, value);
                return;
            }
        }
        throw new NoSuchMethodException(c.getName() + "." + name + " for " + value.getClass());
    }

    private void addGatewayOption(final Object fragment, final ClassLoader cl) throws Exception {
        Method getScreen = findMethod(fragment.getClass(), "getPreferenceScreen", 0);
        if (getScreen == null) return;
        Object screen = getScreen.invoke(fragment);
        if (screen == null) return;

        Method findPref = findMethod(screen.getClass(), "findPreference", 1);
        Object advanceGroup = findPref != null ? findPref.invoke(screen, "advance_group") : null;

        Method getCtx = findMethod(fragment.getClass(), "getContext", 0);
        Context ctx = getCtx != null ? (Context) getCtx.invoke(fragment) : null;
        if (ctx == null) return;

        gatewayEdited = false;

        boolean isZh = "zh".equals(ctx.getResources().getConfiguration().locale.getLanguage());

        Class<?> catCls = Class.forName("com.coui.appcompat.preference.COUIPreferenceCategory", true, cl);
        final Object cat = catCls.getConstructor(Context.class, android.util.AttributeSet.class)
                .newInstance(ctx, null);
        initNullLocks(cat);
        invokeFirst(catCls, cat, "setTitle", isZh ? "网关地址" : "Gateway Address");

        if (advanceGroup != null) {
            try {
                Method getOrder = findMethod(advanceGroup.getClass(), "getOrder", 0);
                if (getOrder != null) {
                    int agOrder = (Integer) getOrder.invoke(advanceGroup);
                    invokeFirst(catCls, cat, "setOrder", agOrder);
                    invokeFirst(advanceGroup.getClass(), advanceGroup, "setOrder", agOrder + 1);
                }
            } catch (Throwable ignored) {}
        }

        String g = Utils.getGlobalString(ctx, Config.KEY_FIXED_GATEWAY);
        draftGateway = (g == null) ? "" : g.trim();

        Class<?> prefCls = Class.forName("com.coui.appcompat.preference.COUIInputPreference", true, cl);
        final Object pref = prefCls.getConstructor(Context.class).newInstance(ctx);
        initNullLocks(pref);
        invokeFirst(prefCls, pref, "setKey", "fixed_gateway");
        invokeFirst(prefCls, pref, "setTitle", isZh ? "网关 IP" : "Gateway IP");
        invokeFirst(prefCls, pref, "setHint", isZh ? "输入网关地址" : "Enter gateway IP");
        invokeFirst(prefCls, pref, "setSummary", isZh ? "留空时网关地址随机，格式参考：192.168.2.1" : "Leave empty for random subnet, e.g. 192.168.2.1");
        invokeFirst(prefCls, pref, "setPersistent", Boolean.FALSE);
        invokeFirst(prefCls, pref, "setContent", draftGateway);

        Method catAdd = findMethod(catCls, "addPreference", 1);
        if (catAdd == null) return;

        Method getEdit = prefCls.getMethod("getEditText");
        final Object edit = getEdit.invoke(pref);
        Class<?> textWatcherCls = android.text.TextWatcher.class;
        Object watcher = Proxy.newProxyInstance(cl, new Class<?>[]{textWatcherCls}, new InvocationHandler() {
            @Override
            public Object invoke(Object p, Method method, Object[] args) {
                if ("afterTextChanged".equals(method.getName())) {
                    try {
                        Object editable = args[0];
                        CharSequence cs = (CharSequence) editable.getClass()
                                .getMethod("toString").invoke(editable);
                        draftGateway = cs.toString().trim();
                        gatewayEdited = true;
                        pushSaveState();
                    } catch (Throwable ignored) {}
                }
                return null;
            }
        });
        edit.getClass().getMethod("addTextChangedListener", textWatcherCls).invoke(edit, watcher);

        Method screenAdd = findMethod(screen.getClass(), "addPreference", 1);
        if (screenAdd == null) return;

        boolean reordered = false;
        try {
            Method mCount = findMethod(screen.getClass(), "getPreferenceCount", 0);
            Method mGet = null;
            for (Method mm : screen.getClass().getMethods()) {
                if ("getPreference".equals(mm.getName()) && mm.getParameterTypes().length == 1
                        && mm.getParameterTypes()[0] == int.class) {
                    mGet = mm;
                    break;
                }
            }
            Method mRemove = null;
            Class<?> prefBase = screenAdd.getParameterTypes()[0];
            for (Method mm : screen.getClass().getMethods()) {
                if ("removePreference".equals(mm.getName()) && mm.getParameterTypes().length == 1
                        && mm.getParameterTypes()[0] == prefBase) {
                    mRemove = mm;
                    break;
                }
            }
            if (mCount != null && mGet != null && mRemove != null && advanceGroup != null) {
                int n = (Integer) mCount.invoke(screen);
                List<Object> children = new ArrayList<>();
                for (int i = 0; i < n; i++) children.add(mGet.invoke(screen, i));
                for (Object child : children) mRemove.invoke(screen, child);
                List<Object> order = new ArrayList<>();
                boolean inserted = false;
                for (Object child : children) {
                    if (child == advanceGroup && !inserted) {
                        order.add(cat);
                        inserted = true;
                    }
                    order.add(child);
                }
                if (!inserted) order.add(cat);
                for (Object o : order) screenAdd.invoke(screen, o);
                reordered = true;
            }
        } catch (Throwable ignored) {}

        if (!reordered) {
            screenAdd.invoke(screen, cat);
        }
        catAdd.invoke(cat, pref);
    }

    public static class ReturnConstantHook extends XC_MethodHook {
        private final Object val;
        public ReturnConstantHook(Object val) { this.val = val; }
        @Override
        protected void beforeHookedMethod(MethodHookParam param) { param.setResult(val); }
    }
}
