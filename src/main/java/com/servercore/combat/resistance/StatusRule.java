package com.servercore.combat.resistance;

public record StatusRule(double applyMultiplier, double damageMultiplier) {
    public static final StatusRule DEFAULT = new StatusRule(1.0, 1.0);
}
