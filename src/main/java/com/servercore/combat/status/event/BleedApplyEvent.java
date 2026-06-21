package com.servercore.combat.status.event;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * 通用流血施加事件。所有流血来源应通过 StatusService#tryApplyBleed 进入此事件。
 */
public final class BleedApplyEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final LivingEntity source;
    private final LivingEntity target;
    private final String reason;
    private double chance;
    private double totalDamage;
    private int durationTicks;
    private boolean cancelled;

    public BleedApplyEvent(LivingEntity source, LivingEntity target, double chance,
                           double totalDamage, int durationTicks, String reason) {
        this.source = source;
        this.target = target;
        this.chance = chance;
        this.totalDamage = totalDamage;
        this.durationTicks = durationTicks;
        this.reason = reason == null ? "unknown" : reason;
    }

    public LivingEntity getSource() {
        return source;
    }

    public LivingEntity getTarget() {
        return target;
    }

    public double getChance() {
        return chance;
    }

    public void setChance(double chance) {
        this.chance = chance;
    }

    public double getTotalDamage() {
        return totalDamage;
    }

    public void setTotalDamage(double totalDamage) {
        this.totalDamage = totalDamage;
    }

    public int getDurationTicks() {
        return durationTicks;
    }

    public void setDurationTicks(int durationTicks) {
        this.durationTicks = durationTicks;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
