package com.servercore.combat.damage;

import java.util.List;

public final class DamagePlan {

    private final DamagePacket packet;
    private final double categoryDamage;
    private final double serverCoreDamage;
    private final boolean immune;
    private final List<Runnable> commits;
    private boolean committed;

    public DamagePlan(DamagePacket packet, double categoryDamage, double serverCoreDamage,
                      boolean immune, List<Runnable> commits) {
        this.packet = packet;
        this.categoryDamage = Math.max(0.0, categoryDamage);
        this.serverCoreDamage = Math.max(0.0, serverCoreDamage);
        this.immune = immune;
        this.commits = commits == null ? List.of() : List.copyOf(commits);
    }

    public DamagePacket packet() {
        return packet;
    }

    public double categoryDamage() {
        return categoryDamage;
    }

    public double serverCoreDamage() {
        return serverCoreDamage;
    }

    public boolean immune() {
        return immune;
    }

    public void commit() {
        if (committed) {
            return;
        }
        committed = true;
        commits.forEach(Runnable::run);
    }

    public DamageResult result(double actualDamage) {
        return new DamageResult(
                serverCoreDamage > 0.0 || committed,
                immune,
                packet == null ? 0.0 : packet.baseDamage(),
                categoryDamage,
                serverCoreDamage,
                Math.max(0.0, actualDamage),
                packet == null ? "invalid" : packet.debugReason()
        );
    }
}
