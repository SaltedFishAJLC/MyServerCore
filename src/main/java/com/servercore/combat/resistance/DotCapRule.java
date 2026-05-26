package com.servercore.combat.resistance;

public record DotCapRule(double maxPercentHealthPerSecond) {
    public static final DotCapRule NONE = new DotCapRule(0.0);

    public DotCapRule merge(DotCapRule other) {
        if (other == null || other.maxPercentHealthPerSecond <= 0.0) {
            return this;
        }
        if (maxPercentHealthPerSecond <= 0.0) {
            return other;
        }
        return new DotCapRule(Math.min(maxPercentHealthPerSecond, other.maxPercentHealthPerSecond));
    }
}
