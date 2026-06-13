package com.servercore.enchant;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

public record EnchantSettings(
        LoreMode disabledLoreMode,
        LoreMode unknownEnchantLoreMode,
        int ultimateLimitPerItem,
        boolean rareEffectsRequireEnabled,
        boolean numericBonusRequireEnabled,
        boolean countDisabledUltimateInLimit
) {

    public static EnchantSettings defaults() {
        return new EnchantSettings(LoreMode.SHOW_GRAY, LoreMode.SHOW_RAW, 1, true, true, false);
    }

    public static EnchantSettings from(ConfigurationSection section) {
        EnchantSettings defaults = defaults();
        if (section == null) {
            return defaults;
        }
        return new EnchantSettings(
                LoreMode.parse(section.getString("disabled_lore_mode"), defaults.disabledLoreMode()),
                LoreMode.parse(section.getString("unknown_enchant_lore_mode"), defaults.unknownEnchantLoreMode()),
                Math.max(1, section.getInt("ultimate_limit_per_item", defaults.ultimateLimitPerItem())),
                section.getBoolean("rare_effects_require_enabled", defaults.rareEffectsRequireEnabled()),
                section.getBoolean("numeric_bonus_require_enabled", defaults.numericBonusRequireEnabled()),
                section.getBoolean("count_disabled_ultimate_in_limit", defaults.countDisabledUltimateInLimit())
        );
    }

    public enum LoreMode {
        SHOW_GRAY,
        SHOW_RAW,
        HIDE;

        static LoreMode parse(String raw, LoreMode fallback) {
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            try {
                return LoreMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }
    }
}
