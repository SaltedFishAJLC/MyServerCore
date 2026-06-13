package com.servercore.enchant;

import java.util.Locale;
import java.util.Map;

public record EnchantEffectSpec(
        EnchantEffectType type,
        String trigger,
        Map<String, ValueCurve> numericParams,
        Map<String, Object> rawParams
) {

    public static EnchantEffectSpec none() {
        return new EnchantEffectSpec(EnchantEffectType.NONE, "", Map.of(), Map.of());
    }

    public boolean hasEffect() {
        return type != null && type != EnchantEffectType.NONE;
    }

    public double param(String key, int level, double fallback) {
        ValueCurve curve = numericParams.get(normalize(key));
        return curve == null ? fallback : curve.valueAt(level);
    }

    public boolean booleanParam(String key, boolean fallback) {
        Object value = rawParams.get(normalize(key));
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
