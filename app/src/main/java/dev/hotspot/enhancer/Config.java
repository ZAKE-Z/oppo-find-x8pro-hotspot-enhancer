package dev.hotspot.enhancer;

import java.util.Locale;

public final class Config {
    public static final String TAG = "HotspotEnhancer";
    public static final String DUAL_LABEL_ZH = "双频";
    public static final String DUAL_LABEL_EN = "Dual-Band";

    public static final String KEY_DUAL_ENABLED = "wifi_bridge_dual";
    public static final String KEY_FIXED_GATEWAY = "hotspot_fixed_gateway";

    private Config() {}

    public static String getDualLabel() {
        Locale l = Locale.getDefault();
        if (l != null && l.getLanguage() != null && l.getLanguage().startsWith("zh")) {
            return DUAL_LABEL_ZH;
        }
        return DUAL_LABEL_EN;
    }

    public static boolean isDualLabel(String s) {
        if (s == null) return false;
        return DUAL_LABEL_ZH.equals(s) || DUAL_LABEL_EN.equalsIgnoreCase(s);
    }
}
