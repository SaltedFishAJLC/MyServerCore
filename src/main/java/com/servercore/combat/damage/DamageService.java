package com.servercore.combat.damage;

import com.servercore.ServerCorePlugin;
import com.servercore.combat.resistance.ResistanceResolver;
import com.servercore.enchant.EnchantEffectService;
import com.servercore.enchant.EquipmentEnchantService;
import com.servercore.manager.AttributeManager;
import com.servercore.manager.ClassPassiveManager;
import com.servercore.manager.PDCManager;
import com.servercore.manager.PlayerRecoveryManager;
import com.servercore.manager.PowerLevelManager;
import com.servercore.manager.ShieldManager;
import com.servercore.passive.PassiveSnapshotService;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class DamageService implements Listener {

    private static final ThreadLocal<Integer> INTERNAL_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Deque<DamagePlan>> INTERNAL_PLANS =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static DamageService instance;

    private final ServerCorePlugin plugin;
    private final ResistanceResolver resistanceResolver;
    private final Map<EntityDamageEvent, DamagePlan> eventPlans = new IdentityHashMap<>();
    private final Map<EntityDamageEvent, DamageResult> finalizedResults = new IdentityHashMap<>();
    private final Map<DamagePlan, DamageResult> internalResults = new IdentityHashMap<>();

    public DamageService(ServerCorePlugin plugin, ResistanceResolver resistanceResolver) {
        instance = this;
        this.plugin = plugin;
        this.resistanceResolver = resistanceResolver;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public static DamageService getInstance() {
        return instance;
    }

    public static boolean isInternalDamageActive() {
        return INTERNAL_DEPTH.get() > 0;
    }

    public DamagePlan prepareEventDamage(EntityDamageEvent event, DamagePacket packet) {
        DamagePlan plan = resolve(packet, event, true);
        if (event != null) {
            eventPlans.put(event, plan);
            event.setDamage(plan.serverCoreDamage());
        }
        return plan;
    }

    public DamageResult finalizeEvent(EntityDamageEvent event) {
        if (event == null) {
            return DamageResult.invalid(null);
        }
        DamageResult existing = finalizedResults.get(event);
        if (existing != null) {
            return existing;
        }

        DamagePlan plan = eventPlans.remove(event);
        if (plan == null && isInternalDamageActive() && !INTERNAL_PLANS.get().isEmpty()) {
            plan = INTERNAL_PLANS.get().peekLast();
        }
        if (plan == null) {
            return DamageResult.invalid(null);
        }

        if (event.isCancelled()) {
            DamageResult result = plan.result(0.0);
            finalizedResults.put(event, result);
            if (isInternalDamageActive() && INTERNAL_PLANS.get().contains(plan)) {
                internalResults.put(plan, result);
            }
            return result;
        }

        plan.commit();
        if (plan.serverCoreDamage() <= 0.0) {
            event.setDamage(0.0);
        }
        double actualDamage = observedImpactDamage(event);
        DamagePacket packet = plan.packet();
        if (packet != null && packet.target() instanceof Player player && actualDamage > 0.0) {
            ClassPassiveManager classPassives = ClassPassiveManager.getInstance();
            if (classPassives != null) {
                classPassives.applyGuardianTransfer(player, event, actualDamage, packet.sourceKind());
                actualDamage = observedImpactDamage(event);
            }

            if (tryPreventFatalDamage(player, event, actualDamage)) {
                actualDamage = 0.0;
            }
        }

        DamageResult result = plan.result(actualDamage);
        PlayerRecoveryManager recoveryManager = PlayerRecoveryManager.getInstance();
        if (recoveryManager != null) {
            recoveryManager.markCombat(packet, result);
        }
        finalizedResults.put(event, result);
        if (isInternalDamageActive() && INTERNAL_PLANS.get().contains(plan)) {
            internalResults.put(plan, result);
        }
        return result;
    }

    public DamageResult getFinalizedResult(EntityDamageEvent event) {
        return event == null ? null : finalizedResults.get(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamageMonitor(EntityDamageEvent event) {
        DamageResult result = finalizeEvent(event);
        if (!result.applied() || event.isCancelled() || result.actualDamage() <= 0.0) {
            scheduleResultCleanup(event);
            return;
        }

        DamagePlan plan = isInternalDamageActive() && !INTERNAL_PLANS.get().isEmpty()
                ? INTERNAL_PLANS.get().peekLast()
                : null;
        if (plan != null) {
            EquipmentEnchantService equipmentEnchants = EquipmentEnchantService.getInstance();
            if (equipmentEnchants != null) {
                DamagePacket packet = plan.packet();
                if (packet.source() instanceof Player sourcePlayer
                        && !com.servercore.enchant.EnchantDamageContext.isSecondaryDamage()) {
                    equipmentEnchants.recordCombatAction(sourcePlayer);
                }
                equipmentEnchants.afterInternalDamage(packet, result.actualDamage());
            }
        }
        scheduleResultCleanup(event);
    }

    public DamageResult applyDamage(DamagePacket packet) {
        return applyDamage(packet, true);
    }

    public DamageResult applyDamage(DamagePacket packet, boolean canKill) {
        if (!isValid(packet)) {
            return DamageResult.invalid(packet);
        }

        DamagePlan plan = resolve(packet, null, canKill);
        if (plan.immune()) {
            plan.commit();
            return plan.result(0.0);
        }
        if (plan.serverCoreDamage() <= 0.0) {
            plan.commit();
            return plan.result(0.0);
        }

        return applyInternalBukkitDamage(packet, plan);
    }

    private DamagePlan resolve(DamagePacket packet, EntityDamageEvent event, boolean canKill) {
        if (!isValid(packet)) {
            return new DamagePlan(packet, 0.0, 0.0, false, List.of());
        }

        LivingEntity target = packet.target();
        double damage = Math.max(0.0, packet.baseDamage());
        double categoryDamage = damage;
        List<Runnable> commits = new ArrayList<>();

        if (packet.category() == DamageCategory.TRUE) {
            if (packet.tags().contains(DamageTag.DOT)) {
                damage = resistanceResolver.resolveDotCapDamage(target, damage);
            }
            if (!canKill) {
                damage = capNonLethalDamage(target, damage);
            }
            return new DamagePlan(packet, categoryDamage, damage, false, commits);
        }

        if (target instanceof Player player) {
            if (dodgeEligible(packet)) {
                AttributeManager attributes = AttributeManager.getInstance();
                if (attributes != null) {
                    AttributeManager.DodgePlan dodgePlan = attributes.previewDodge(player);
                    if (dodgePlan.dodged()) {
                        commits.add(dodgePlan.commit());
                        return new DamagePlan(packet, 0.0, 0.0, false, commits);
                    }
                }
            }

            if (shieldEligible(packet)) {
                ShieldManager shieldManager = ShieldManager.getInstance();
                if (shieldManager != null) {
                    ShieldManager.ShieldBlockPlan shieldPlan =
                            event == null
                                    ? shieldManager.previewShieldBlock(player, packet.source(), damage)
                                    : shieldManager.previewShieldBlock(player, event, damage);
                    damage = shieldPlan.result().remainingDamage();
                    commits.add(shieldPlan.commit());
                }
            }

            PowerLevelManager power = PowerLevelManager.getInstance();
            if (power != null) {
                EquipmentEnchantService equipmentEnchants = EquipmentEnchantService.getInstance();
                double projectileArmor = equipmentEnchants != null
                        && packet.tags().contains(DamageTag.PROJECTILE)
                        ? equipmentEnchants.getProjectileArmor(player)
                        : 0.0;
                damage *= 1.0 - power.calculateDamageReduction(player, projectileArmor);
            }
            categoryDamage = damage;

            if (packet.category() == DamageCategory.MAGIC) {
                AttributeManager attributes = AttributeManager.getInstance();
                if (attributes != null) {
                    damage *= 1.0 - attributes.getMagicDamageReduction(player);
                }
            }
        } else {
            PDCManager pdc = PDCManager.getInstance();
            if (pdc != null) {
                double reduction = target.getPersistentDataContainer()
                        .getOrDefault(pdc.KEY_MOB_DAMAGE_REDUCTION, PersistentDataType.DOUBLE, 0.0);
                damage *= 1.0 - Math.min(0.50, Math.max(0.0, reduction));
                categoryDamage = damage;

                if (packet.category() == DamageCategory.MAGIC) {
                    double magicResist = target.getPersistentDataContainer()
                            .getOrDefault(pdc.KEY_MOB_MAGIC_RESIST, PersistentDataType.DOUBLE, 0.0);
                    damage *= 1.0 - Math.min(0.30, Math.max(0.0, magicResist));
                }
            }
        }

        double tagMultiplier = resistanceResolver.resolveDamageMultiplier(target, packet.tags());
        if (tagMultiplier <= 0.0) {
            return new DamagePlan(packet, categoryDamage, 0.0, true, commits);
        }
        damage *= tagMultiplier;

        if (packet.tags().contains(DamageTag.DOT)) {
            damage = resistanceResolver.resolveDotCapDamage(target, damage);
        }

        if (target instanceof Player player) {
            EnchantEffectService enchantEffects = EnchantEffectService.getInstance();
            if (enchantEffects != null) {
                damage = enchantEffects.applyIncomingDamageModifiers(player, damage);
            }

            EquipmentEnchantService equipmentEnchants = EquipmentEnchantService.getInstance();
            if (equipmentEnchants != null) {
                EquipmentEnchantService.IncomingDamagePlan equipmentPlan =
                        equipmentEnchants.previewIncomingDamage(
                                player,
                                packet.source(),
                                damage,
                                packet.category(),
                                packet.tags().contains(DamageTag.PROJECTILE),
                                directImpact(packet)
                        );
                damage = equipmentPlan.damage();
                commits.add(equipmentPlan.commit());
            }

            PassiveSnapshotService passives = PassiveSnapshotService.getInstance();
            if (passives != null) {
                damage = passives.modifyIncomingDamage(player, damage);
            }

            ClassPassiveManager classPassives = ClassPassiveManager.getInstance();
            if (classPassives != null) {
                ClassPassiveManager.CalamityAbsorptionPlan calamityPlan =
                        classPassives.previewCalamityAbsorption(
                                player, damage, packet.category(), packet.sourceKind());
                damage = calamityPlan.damage();
                commits.add(calamityPlan.commit());
            }
        }

        if (!canKill) {
            damage = capNonLethalDamage(target, damage);
        }
        return new DamagePlan(packet, categoryDamage, Math.max(0.0, damage), false, commits);
    }

    private boolean tryPreventFatalDamage(Player player, EntityDamageEvent event, double finalDamage) {
        if (finalDamage <= 0.0) {
            return false;
        }
        PassiveSnapshotService passives = PassiveSnapshotService.getInstance();
        if (passives != null && passives.tryPreventFatalDamage(player, event, finalDamage)) {
            return true;
        }
        EnchantEffectService enchantEffects = EnchantEffectService.getInstance();
        return enchantEffects != null && enchantEffects.tryPreventFatalDamage(player, event, finalDamage);
    }

    private boolean shieldEligible(DamagePacket packet) {
        if (packet.category() == DamageCategory.TRUE
                || packet.tags().contains(DamageTag.DOT)
                || packet.tags().contains(DamageTag.STATUS)
                || packet.sourceKind() == DamageSourceKind.SYSTEM
                || packet.sourceKind() == DamageSourceKind.VANILLA_ENVIRONMENT) {
            return false;
        }
        return packet.tags().contains(DamageTag.MELEE)
                || packet.tags().contains(DamageTag.PROJECTILE)
                || packet.tags().contains(DamageTag.EXPLOSION)
                || (packet.category() == DamageCategory.MAGIC && directImpact(packet));
    }

    private boolean dodgeEligible(DamagePacket packet) {
        if (packet.category() == DamageCategory.TRUE
                || packet.tags().contains(DamageTag.DOT)
                || packet.tags().contains(DamageTag.STATUS)
                || packet.tags().contains(DamageTag.AOE)
                || packet.tags().contains(DamageTag.EXPLOSION)
                || packet.sourceKind() == DamageSourceKind.SYSTEM
                || packet.sourceKind() == DamageSourceKind.VANILLA_ENVIRONMENT
                || packet.sourceKind() == DamageSourceKind.VANILLA_STATUS
                || packet.sourceKind() == DamageSourceKind.VANILLA_EXPLOSION) {
            return false;
        }
        return packet.tags().contains(DamageTag.MELEE)
                || packet.tags().contains(DamageTag.PROJECTILE)
                || (packet.category() == DamageCategory.MAGIC && directImpact(packet));
    }

    private boolean directImpact(DamagePacket packet) {
        return packet.source() != null
                && !packet.tags().contains(DamageTag.DOT)
                && !packet.tags().contains(DamageTag.STATUS)
                && packet.sourceKind() != DamageSourceKind.SYSTEM
                && packet.sourceKind() != DamageSourceKind.VANILLA_ENVIRONMENT;
    }

    private DamageResult applyInternalBukkitDamage(DamagePacket packet, DamagePlan plan) {
        LivingEntity target = packet.target();
        int previousNoDamageTicks = target.getNoDamageTicks();
        boolean restoreNoDamageTicks = shouldRestoreNoDamageTicksAfterInternalDamage(packet);
        INTERNAL_DEPTH.set(INTERNAL_DEPTH.get() + 1);
        INTERNAL_PLANS.get().addLast(plan);
        try {
            target.setNoDamageTicks(0);
            if (packet.source() != null && packet.source().isValid() && !packet.source().equals(target)) {
                target.damage(plan.serverCoreDamage(), packet.source());
            } else {
                target.damage(plan.serverCoreDamage());
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not apply internal damage (" + packet.debugReason() + "): "
                    + exception.getMessage());
        } finally {
            INTERNAL_PLANS.get().removeLastOccurrence(plan);
            INTERNAL_DEPTH.set(Math.max(0, INTERNAL_DEPTH.get() - 1));
            if (restoreNoDamageTicks && !target.isDead() && target.isValid()) {
                target.setNoDamageTicks(previousNoDamageTicks);
            }
        }
        DamageResult result = internalResults.remove(plan);
        result = result == null ? plan.result(plan.serverCoreDamage()) : result;
        PlayerRecoveryManager recoveryManager = PlayerRecoveryManager.getInstance();
        if (recoveryManager != null) {
            recoveryManager.markCombat(packet, result);
        }
        return result;
    }

    private boolean shouldRestoreNoDamageTicksAfterInternalDamage(DamagePacket packet) {
        return packet.sourceKind() == DamageSourceKind.CUSTOM_STATUS
                || packet.sourceKind() == DamageSourceKind.VANILLA_STATUS
                || packet.tags().contains(DamageTag.DOT)
                || packet.tags().contains(DamageTag.STATUS);
    }

    private double capNonLethalDamage(LivingEntity target, double damage) {
        double health = getEffectiveHealth(target);
        if (health <= 1.0) {
            return 0.0;
        }
        return Math.min(damage, Math.max(0.0, health - 1.0));
    }

    public double getEffectiveHealth(LivingEntity target) {
        PDCManager pdc = PDCManager.getInstance();
        if (pdc != null) {
            Double virtualHealth = target.getPersistentDataContainer()
                    .get(pdc.KEY_MOB_VIRTUAL_HEALTH, PersistentDataType.DOUBLE);
            if (virtualHealth != null && virtualHealth > 0.0) {
                return virtualHealth;
            }
        }
        if (target instanceof Player player) {
            return Math.max(0.0, player.getHealth() + player.getAbsorptionAmount());
        }
        return Math.max(0.0, target.getHealth());
    }

    @SuppressWarnings("deprecation")
    private double observedImpactDamage(EntityDamageEvent event) {
        double damage = Math.max(0.0, event.getFinalDamage());
        try {
            if (event.isApplicable(EntityDamageEvent.DamageModifier.ABSORPTION)) {
                double absorptionModifier = event.getDamage(EntityDamageEvent.DamageModifier.ABSORPTION);
                if (absorptionModifier < 0.0) {
                    damage -= absorptionModifier;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Some custom events do not expose the deprecated modifier map.
        }
        return Math.max(0.0, damage);
    }

    private void scheduleResultCleanup(EntityDamageEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> finalizedResults.remove(event));
    }

    private boolean isValid(DamagePacket packet) {
        return packet != null
                && packet.target() != null
                && !packet.target().isDead()
                && packet.target().isValid()
                && packet.baseDamage() > 0.0;
    }
}
