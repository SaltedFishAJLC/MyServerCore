package com.servercore.combat.integration;

import com.servercore.enchant.EnchantDefinition;
import com.servercore.enchant.EnchantRegistry;
import com.servercore.enchant.ValueCurve;
import com.servercore.manager.EnchantManager;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public final class EnchantTargetMatcher {

    private EnchantTargetMatcher() {
    }

    public static double resolveHighestMultiplier(ItemStack weapon, LivingEntity target) {
        TargetBonus bonus = resolveTargetBonus(weapon, target);
        return 1.0 + bonus.mainTagBonus() + bonus.traitTagBonus() + bonus.bossBonus();
    }

    public static int resolveHighestLevel(ItemStack weapon, LivingEntity target) {
        EnchantManager enchantManager = EnchantManager.getInstance();
        EnchantRegistry registry = EnchantRegistry.getInstance();
        if (enchantManager == null || registry == null || weapon == null || target == null) {
            return 0;
        }

        int best = 0;
        for (Map.Entry<String, Integer> entry : enchantManager.getAllActiveCustomEnchants(weapon).entrySet()) {
            EnchantDefinition definition = registry.get(entry.getKey()).orElse(null);
            if (definition != null && definition.target().matchesAny(target) && targetDamageCurve(definition) != null) {
                best = Math.max(best, entry.getValue());
            }
        }
        return best;
    }

    private static TargetBonus resolveTargetBonus(ItemStack weapon, LivingEntity target) {
        EnchantManager enchantManager = EnchantManager.getInstance();
        EnchantRegistry registry = EnchantRegistry.getInstance();
        if (enchantManager == null || registry == null || weapon == null || target == null) {
            return TargetBonus.empty();
        }

        double mainTagBonus = 0.0;
        double traitTagBonus = 0.0;
        double bossBonus = 0.0;
        for (Map.Entry<String, Integer> entry : enchantManager.getAllActiveCustomEnchants(weapon).entrySet()) {
            EnchantDefinition definition = registry.get(entry.getKey()).orElse(null);
            if (definition == null || definition.target().isEmpty()) {
                continue;
            }

            ValueCurve curve = targetDamageCurve(definition);
            if (curve == null) {
                continue;
            }
            double value = Math.max(0.0, curve.valueAt(entry.getValue()));
            if (definition.target().matchesMainTag(target)) {
                mainTagBonus = Math.max(mainTagBonus, value);
            }
            if (definition.target().matchesTraitTag(target)) {
                traitTagBonus = Math.max(traitTagBonus, value);
            }
            if (definition.target().matchesBoss(target)) {
                bossBonus = Math.max(bossBonus, value);
            }
        }
        return new TargetBonus(mainTagBonus, traitTagBonus, bossBonus);
    }

    private static ValueCurve targetDamageCurve(EnchantDefinition definition) {
        ValueCurve curve = definition.numericBonuses().get("damage_to_target");
        if (curve != null) {
            return curve;
        }
        curve = definition.numericBonuses().get("damage_to_main_tag");
        if (curve != null) {
            return curve;
        }
        return definition.numericBonuses().get("damage_to_trait_tag");
    }

    private record TargetBonus(double mainTagBonus, double traitTagBonus, double bossBonus) {
        static TargetBonus empty() {
            return new TargetBonus(0.0, 0.0, 0.0);
        }
    }
}
