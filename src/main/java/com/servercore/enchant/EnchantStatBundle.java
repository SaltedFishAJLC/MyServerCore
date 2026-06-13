package com.servercore.enchant;

public record EnchantStatBundle(
        double baseDamage,
        double baseMultiplier,
        double critChance,
        double critDamage,
        double brutality,
        double lifesteal,
        double armorPen,
        double baseArmor,
        double attackSpeedBonus,
        double shieldBlockThreshold,
        double shieldEffectiveBlock,
        double shieldCooldownSeconds
) {

    public static EnchantStatBundle empty() {
        return new EnchantStatBundle(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }
}
