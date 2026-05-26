package com.servercore.combat.resistance;

public record ControlRule(
        double durationMultiplier,
        int maxDurationTicks,
        int internalCooldownTicks,
        double multiplier
) {
    public static final ControlRule DEFAULT = new ControlRule(1.0, 0, 0, 1.0);

    public ControlRule merge(ControlRule other) {
        if (other == null) {
            return this;
        }

        double duration = Math.min(durationMultiplier, other.durationMultiplier);
        int maxDuration = stricterPositive(maxDurationTicks, other.maxDurationTicks);
        int cooldown = Math.max(internalCooldownTicks, other.internalCooldownTicks);
        double genericMultiplier = Math.min(multiplier, other.multiplier);
        return new ControlRule(duration, maxDuration, cooldown, genericMultiplier);
    }

    private int stricterPositive(int left, int right) {
        if (left <= 0) {
            return right;
        }
        if (right <= 0) {
            return left;
        }
        return Math.min(left, right);
    }
}
