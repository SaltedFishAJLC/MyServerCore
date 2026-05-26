package com.servercore.combat.damage;

import org.bukkit.entity.LivingEntity;

import java.util.Set;

public record DamagePacket(
        LivingEntity source,
        LivingEntity target,
        double baseDamage,
        DamageCategory category,
        Set<DamageTag> tags,
        DamageSourceKind sourceKind,
        String debugReason
) {
    public DamagePacket {
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }
}
