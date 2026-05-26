package com.servercore.combat.status;

import org.bukkit.entity.LivingEntity;

public final class StatusInstance {

    private final StatusType type;
    private LivingEntity source;
    private double remainingDamage;
    private int remainingTicks;
    private final int tickInterval;
    private int ticksUntilNext;
    private int stacks;
    private final int maxStacks;
    private final boolean canKill;

    public StatusInstance(StatusType type, LivingEntity source, double totalDamage, int durationTicks,
                          int tickInterval, int stacks, int maxStacks, boolean canKill) {
        this.type = type;
        this.source = source;
        this.remainingDamage = Math.max(0.0, totalDamage);
        this.remainingTicks = Math.max(1, durationTicks);
        this.tickInterval = Math.max(1, tickInterval);
        this.ticksUntilNext = this.tickInterval;
        this.stacks = Math.max(1, stacks);
        this.maxStacks = Math.max(1, maxStacks);
        this.canKill = canKill;
    }

    public StatusType type() {
        return type;
    }

    public LivingEntity source() {
        return source != null && source.isValid() ? source : null;
    }

    public int tickInterval() {
        return tickInterval;
    }

    public boolean canKill() {
        return canKill;
    }

    public boolean tickClock() {
        remainingTicks--;
        ticksUntilNext--;
        return ticksUntilNext <= 0;
    }

    public double consumeTickDamage() {
        ticksUntilNext = tickInterval;
        int intervalsIncludingCurrent = remainingTicks <= 0
                ? 1
                : ((int) Math.ceil(remainingTicks / (double) tickInterval)) + 1;
        double baseTickDamage = remainingDamage / Math.max(1, intervalsIncludingCurrent);
        double intensity = type == StatusType.BLEEDING ? 1.0 + (stacks - 1) * 0.35 : 1.0;
        double damage = Math.min(remainingDamage, baseTickDamage * intensity);
        remainingDamage = Math.max(0.0, remainingDamage - damage);
        return damage;
    }

    public boolean expired() {
        return remainingTicks <= 0 || remainingDamage <= 0.0001;
    }

    public void addDamagePool(LivingEntity newSource, double addedDamage, int durationTicks) {
        if (newSource != null) {
            this.source = newSource;
        }
        this.remainingDamage += Math.max(0.0, addedDamage);
        this.remainingTicks = Math.max(this.remainingTicks, Math.max(1, durationTicks));
    }

    public void addStackIntensity(LivingEntity newSource, double addedDamage, int durationTicks) {
        addDamagePool(newSource, addedDamage, durationTicks);
        this.stacks = Math.min(maxStacks, stacks + 1);
    }
}
