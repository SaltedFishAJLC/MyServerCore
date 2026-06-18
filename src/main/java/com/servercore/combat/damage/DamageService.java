package com.servercore.combat.damage;

import com.servercore.ServerCorePlugin;
import com.servercore.combat.resistance.ResistanceResolver;
import com.servercore.enchant.EnchantEffectService;
import com.servercore.manager.AttributeManager;
import com.servercore.manager.PDCManager;
import com.servercore.manager.PowerLevelManager;
import com.servercore.passive.PassiveSnapshotService;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

public final class DamageService {

    private static final ThreadLocal<Integer> INTERNAL_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static DamageService instance;

    private final ServerCorePlugin plugin;
    private final ResistanceResolver resistanceResolver;

    public DamageService(ServerCorePlugin plugin, ResistanceResolver resistanceResolver) {
        instance = this;
        this.plugin = plugin;
        this.resistanceResolver = resistanceResolver;
    }

    public static DamageService getInstance() {
        return instance;
    }

    public static boolean isInternalDamageActive() {
        return INTERNAL_DEPTH.get() > 0;
    }

    public DamageResult applyDamage(DamagePacket packet) {
        return applyDamage(packet, true);
    }

    public DamageResult applyDamage(DamagePacket packet, boolean canKill) {
        if (packet == null || packet.target() == null || packet.target().isDead() || !packet.target().isValid()) {
            return DamageResult.invalid(packet);
        }
        if (packet.baseDamage() <= 0.0) {
            return new DamageResult(false, false, packet.baseDamage(), 0.0, 0.0, packet.debugReason());
        }

        LivingEntity target = packet.target();
        double categoryDamage = applyCategoryReduction(target, packet.baseDamage(), packet.category());
        double tagMultiplier = resistanceResolver.resolveDamageMultiplier(target, packet.tags());
        if (tagMultiplier <= 0.0) {
            return new DamageResult(false, true, packet.baseDamage(), categoryDamage, 0.0, packet.debugReason());
        }

        double finalDamage = categoryDamage * tagMultiplier;
        if (packet.tags().contains(DamageTag.DOT)) {
            finalDamage = resistanceResolver.resolveDotCapDamage(target, finalDamage);
        }
        if (!canKill) {
            finalDamage = capNonLethalDamage(target, finalDamage);
        }
        if (finalDamage <= 0.0) {
            return new DamageResult(false, false, packet.baseDamage(), categoryDamage, 0.0, packet.debugReason());
        }

        if (target instanceof Player player) {
            PassiveSnapshotService passives = PassiveSnapshotService.getInstance();
            if (passives != null && passives.tryPreventFatalDamage(player, null, finalDamage)) {
                return new DamageResult(true, false, packet.baseDamage(), categoryDamage, 0.0,
                        packet.debugReason() + ":revived");
            }
            EnchantEffectService enchantEffects = EnchantEffectService.getInstance();
            if (enchantEffects != null && enchantEffects.tryPreventFatalDamage(player, null, finalDamage)) {
                return new DamageResult(true, false, packet.baseDamage(), categoryDamage, 0.0,
                        packet.debugReason() + ":phoenix");
            }
        }

        applyInternalBukkitDamage(packet, finalDamage);
        return new DamageResult(true, false, packet.baseDamage(), categoryDamage, finalDamage, packet.debugReason());
    }

    private double applyCategoryReduction(LivingEntity target, double baseDamage, DamageCategory category) {
        if (category == DamageCategory.TRUE) {
            return baseDamage;
        }

        double result = baseDamage;
        if (target instanceof Player player) {
            PowerLevelManager powerLevelManager = PowerLevelManager.getInstance();
            if (powerLevelManager != null) {
                double reduction = powerLevelManager.calculateDamageReduction(player);
                if (reduction > 0.0) {
                    result *= Math.max(0.0, 1.0 - reduction);
                }
            }

            if (category == DamageCategory.MAGIC) {
                AttributeManager attributeManager = AttributeManager.getInstance();
                if (attributeManager != null) {
                    double magicReduction = attributeManager.getMagicDamageReduction(player);
                    if (magicReduction > 0.0) {
                        result *= Math.max(0.0, 1.0 - magicReduction);
                    }
                }
            }
            PassiveSnapshotService passives = PassiveSnapshotService.getInstance();
            if (passives != null) {
                result = passives.modifyIncomingDamage(player, result);
            }
            return result;
        }

        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return result;
        }

        double reduction = target.getPersistentDataContainer()
                .getOrDefault(pdc.KEY_MOB_DAMAGE_REDUCTION, PersistentDataType.DOUBLE, 0.0);
        if (reduction > 0.0) {
            result *= Math.max(0.0, 1.0 - Math.min(0.50, reduction));
        }

        if (category == DamageCategory.MAGIC) {
            double magicResist = target.getPersistentDataContainer()
                    .getOrDefault(pdc.KEY_MOB_MAGIC_RESIST, PersistentDataType.DOUBLE, 0.0);
            if (magicResist > 0.0) {
                result *= Math.max(0.0, 1.0 - Math.min(0.30, magicResist));
            }
        }
        return result;
    }

    private void applyInternalBukkitDamage(DamagePacket packet, double finalDamage) {
        LivingEntity target = packet.target();
        int previousNoDamageTicks = target.getNoDamageTicks();
        INTERNAL_DEPTH.set(INTERNAL_DEPTH.get() + 1);
        try {
            target.setNoDamageTicks(0);
            if (packet.source() != null && packet.source().isValid() && !packet.source().equals(target)) {
                target.damage(finalDamage, packet.source());
            } else {
                target.damage(finalDamage);
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not apply internal damage (" + packet.debugReason() + "): " + exception.getMessage());
        } finally {
            INTERNAL_DEPTH.set(Math.max(0, INTERNAL_DEPTH.get() - 1));
            if (!target.isDead() && target.isValid()) {
                target.setNoDamageTicks(previousNoDamageTicks);
            }
        }
    }

    private double capNonLethalDamage(LivingEntity target, double damage) {
        double health = getEffectiveHealth(target);
        if (health <= 1.0) {
            return 0.0;
        }
        return Math.min(damage, Math.max(0.0, health - 1.0));
    }

    private double getEffectiveHealth(LivingEntity target) {
        PDCManager pdc = PDCManager.getInstance();
        if (pdc != null) {
            Double virtualHealth = target.getPersistentDataContainer().get(pdc.KEY_MOB_VIRTUAL_HEALTH, PersistentDataType.DOUBLE);
            if (virtualHealth != null && virtualHealth > 0.0) {
                return virtualHealth;
            }
        }
        return Math.max(0.0, target.getHealth());
    }
}
