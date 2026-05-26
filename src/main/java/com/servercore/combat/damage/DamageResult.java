package com.servercore.combat.damage;

public record DamageResult(
        boolean applied,
        boolean immune,
        double baseDamage,
        double categoryDamage,
        double finalDamage,
        String debugReason
) {
    public static DamageResult invalid(DamagePacket packet) {
        return new DamageResult(false, false, packet == null ? 0.0 : packet.baseDamage(), 0.0, 0.0,
                packet == null ? "invalid" : packet.debugReason());
    }
}
