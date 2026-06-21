package com.servercore.combat.status.event;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * 单次流血结算完成事件。actualDamage 是经过 DOT 上限与外部事件修正后的实际伤害。
 */
public final class BleedDamageEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final LivingEntity source;
    private final LivingEntity target;
    private final double requestedDamage;
    private final double actualDamage;
    private final String reason;

    public BleedDamageEvent(LivingEntity source, LivingEntity target, double requestedDamage,
                            double actualDamage, String reason) {
        this.source = source;
        this.target = target;
        this.requestedDamage = requestedDamage;
        this.actualDamage = actualDamage;
        this.reason = reason == null ? "unknown" : reason;
    }

    public LivingEntity getSource() {
        return source;
    }

    public LivingEntity getTarget() {
        return target;
    }

    public double getRequestedDamage() {
        return requestedDamage;
    }

    public double getActualDamage() {
        return actualDamage;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
