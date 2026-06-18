package com.servercore.enchant;

import com.servercore.manager.EnchantManager;
import com.servercore.manager.PDCManager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Map;

public final class EnchantStatResolver {

    private static EnchantStatResolver instance;

    public EnchantStatResolver() {
        instance = this;
    }

    public static EnchantStatResolver getInstance() {
        return instance;
    }

    public EnchantStatBundle resolveCombatStats(ItemStack item, Player player, EquipmentSlot slot) {
        Map<String, Double> numeric = resolveNumeric(item);
        double baseDamage = numeric.getOrDefault("base_damage", 0.0);
        EnchantEffectService effects = EnchantEffectService.getInstance();
        if (effects != null) {
            baseDamage += effects.resolveSoulEaterBaseDamageBonus(player, item);
        }
        return new EnchantStatBundle(
                baseDamage,
                numeric.getOrDefault("base_multiplier", 0.0),
                numeric.getOrDefault("crit_chance", 0.0),
                numeric.getOrDefault("crit_damage", 0.0),
                numeric.getOrDefault("brutality", 0.0),
                numeric.getOrDefault("lifesteal", 0.0),
                numeric.getOrDefault("armor_pen", 0.0),
                numeric.getOrDefault("base_armor", 0.0),
                numeric.getOrDefault("attack_speed_bonus", 0.0),
                numeric.getOrDefault("shield_block_threshold", 0.0),
                numeric.getOrDefault("shield_effective_block", 0.0),
                numeric.getOrDefault("shield_cooldown_seconds", 0.0)
        );
    }

    public Map<String, Double> resolveNumeric(ItemStack item) {
        EnchantManager enchantManager = EnchantManager.getInstance();
        EnchantRegistry registry = EnchantRegistry.getInstance();
        if (enchantManager == null || registry == null || item == null || item.getType().isAir()) {
            return Map.of();
        }

        java.util.Map<String, Double> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : enchantManager.getAllActiveCustomEnchants(item).entrySet()) {
            EnchantDefinition definition = registry.get(entry.getKey()).orElse(null);
            if (definition == null) {
                continue;
            }
            int level = entry.getValue();
            if (definition.id().equals("one_for_all")) {
                PDCManager pdc = PDCManager.getInstance();
                if (pdc != null) {
                    result.merge("base_damage", pdc.getStat(item, pdc.KEY_BASE_DAMAGE) * 1.5, Double::sum);
                }
            }
            boolean hasNumericLifesteal = false;
            for (Map.Entry<String, ValueCurve> numeric : definition.numericBonuses().entrySet()) {
                String key = normalize(numeric.getKey());
                if (key.equals("lifesteal")) {
                    hasNumericLifesteal = true;
                }
                result.merge(key, numeric.getValue().valueAt(level), Double::sum);
            }
            if (!hasNumericLifesteal && definition.effect().type() == EnchantEffectType.VAMPIRISM) {
                result.merge("lifesteal", definition.effect().param("heal_ratio", level, 0.0), Double::sum);
            }
        }
        return result;
    }

    public double resolveNumeric(ItemStack item, String key) {
        return resolveNumeric(item).getOrDefault(normalize(key), 0.0);
    }

    private String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
