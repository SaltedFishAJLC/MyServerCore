package com.servercore.enchant;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record EnchantDefinition(
        String id,
        boolean enabled,
        String display,
        EnchantRarity rarity,
        int maxLevel,
        int softMaxLevel,
        Set<EnchantSlot> slots,
        String conflictGroup,
        List<String> description,
        Map<String, ValueCurve> numericBonuses,
        EnchantEffectSpec effect,
        EnchantTargetSpec target
) {

    public EnchantDefinition {
        id = normalize(id);
        display = display == null || display.isBlank() ? id : display;
        rarity = rarity == null ? EnchantRarity.COMMON : rarity;
        maxLevel = Math.max(1, maxLevel);
        softMaxLevel = Math.max(1, Math.min(softMaxLevel <= 0 ? maxLevel : softMaxLevel, maxLevel));
        slots = slots == null ? Set.of() : Set.copyOf(slots);
        conflictGroup = conflictGroup == null ? "" : normalize(conflictGroup);
        description = description == null ? List.of() : List.copyOf(description);
        numericBonuses = numericBonuses == null ? Map.of() : Map.copyOf(numericBonuses);
        effect = effect == null ? EnchantEffectSpec.none() : effect;
        target = target == null ? EnchantTargetSpec.empty() : target;
    }

    public boolean isUltimate() {
        return rarity == EnchantRarity.ULTIMATE;
    }

    public boolean hasConflictGroup() {
        return !conflictGroup.isBlank();
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
