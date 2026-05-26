package com.servercore.combat.resistance;

public record DamageRule(double damageMultiplier) {
    public static final DamageRule DEFAULT = new DamageRule(1.0);
}
