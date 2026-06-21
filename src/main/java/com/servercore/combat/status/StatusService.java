package com.servercore.combat.status;

import com.servercore.ServerCorePlugin;
import com.servercore.combat.damage.DamageCategory;
import com.servercore.combat.damage.DamagePacket;
import com.servercore.combat.damage.DamageResult;
import com.servercore.combat.damage.DamageService;
import com.servercore.combat.damage.DamageSourceKind;
import com.servercore.combat.damage.DamageTag;
import com.servercore.combat.resistance.ResistanceResolver;
import com.servercore.combat.status.event.BleedApplyEvent;
import com.servercore.combat.status.event.BleedDamageEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class StatusService {

    private static StatusService instance;

    private final DamageService damageService;
    private final ResistanceResolver resistanceResolver;
    private final Map<UUID, EnumMap<StatusType, StatusInstance>> activeStatuses = new ConcurrentHashMap<>();
    private final Map<UUID, List<BleedApplication>> activeBleeds = new ConcurrentHashMap<>();
    private final BukkitTask tickTask;

    public StatusService(ServerCorePlugin plugin, DamageService damageService, ResistanceResolver resistanceResolver) {
        instance = this;
        this.damageService = damageService;
        this.resistanceResolver = resistanceResolver;
        this.tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickStatuses, 1L, 1L);
    }

    public static StatusService getInstance() {
        return instance;
    }

    public void stop() {
        tickTask.cancel();
        activeStatuses.clear();
        activeBleeds.clear();
    }

    public boolean applyStatus(LivingEntity source, LivingEntity target, StatusType type, double totalDamage, int durationTicks) {
        if (target == null || type == null || target.isDead() || !target.isValid()) {
            return false;
        }
        if (type == StatusType.BLEEDING) {
            return tryApplyBleed(source, target, 1.0, totalDamage, durationTicks, "status:bleeding");
        }
        StatusProfile profile = profile(type);
        if (profile == null || profile.category() == null) {
            return false;
        }

        double applyMultiplier = resistanceResolver.resolveStatusApplyMultiplier(target, type);
        double statusDamageMultiplier = resistanceResolver.resolveStatusDamageMultiplier(target, type);
        if (applyMultiplier <= 0.0 || statusDamageMultiplier <= 0.0) {
            return false;
        }

        double adjustedDamage = Math.max(0.0, totalDamage * applyMultiplier * statusDamageMultiplier);
        int safeDuration = Math.max(1, durationTicks);
        EnumMap<StatusType, StatusInstance> entityStatuses = activeStatuses.computeIfAbsent(target.getUniqueId(), ignored -> new EnumMap<>(StatusType.class));
        StatusInstance existing = entityStatuses.get(type);
        if (existing == null) {
            entityStatuses.put(type, new StatusInstance(type, source, adjustedDamage, safeDuration,
                    profile.tickInterval(), 1, profile.maxStacks(), profile.canKill()));
        } else {
            existing.addDamagePool(source, adjustedDamage, safeDuration);
        }

        if (type == StatusType.BURNING) {
            target.setFireTicks(Math.max(target.getFireTicks(), safeDuration));
        }
        return true;
    }

    /**
     * 标准流血入口：有 chance 概率，使目标在 durationTicks 内均匀承受 totalDamage。
     * 每次成功施加都是独立伤害池，因此不同来源与连续触发不会丢失归属。
     */
    public boolean tryApplyBleed(LivingEntity source, LivingEntity target, double chance,
                                 double totalDamage, int durationTicks, String reason) {
        if (target == null || target.isDead() || !target.isValid() || totalDamage <= 0.0 || durationTicks <= 0) {
            return false;
        }

        BleedApplyEvent event = new BleedApplyEvent(
                source,
                target,
                clamp(chance, 0.0, 1.0),
                Math.max(0.0, totalDamage),
                Math.max(1, durationTicks),
                reason
        );
        Bukkit.getPluginManager().callEvent(event);
        double resolvedChance = clamp(event.getChance(), 0.0, 1.0);
        if (event.isCancelled() || resolvedChance <= 0.0
                || ThreadLocalRandom.current().nextDouble() >= resolvedChance) {
            return false;
        }

        double applyMultiplier = resistanceResolver.resolveStatusApplyMultiplier(target, StatusType.BLEEDING);
        double damageMultiplier = resistanceResolver.resolveStatusDamageMultiplier(target, StatusType.BLEEDING);
        if (applyMultiplier <= 0.0 || damageMultiplier <= 0.0) {
            return false;
        }

        double adjustedDamage = Math.max(0.0, event.getTotalDamage() * applyMultiplier * damageMultiplier);
        if (adjustedDamage <= 0.0) {
            return false;
        }

        StatusProfile profile = profile(StatusType.BLEEDING);
        StatusInstance instance = new StatusInstance(
                StatusType.BLEEDING,
                source,
                adjustedDamage,
                Math.max(1, event.getDurationTicks()),
                profile.tickInterval(),
                1,
                1,
                profile.canKill()
        );
        activeBleeds.computeIfAbsent(target.getUniqueId(), ignored -> new ArrayList<>())
                .add(new BleedApplication(instance, event.getReason()));
        return true;
    }

    private void tickStatuses() {
        Iterator<Map.Entry<UUID, EnumMap<StatusType, StatusInstance>>> entityIterator = activeStatuses.entrySet().iterator();
        while (entityIterator.hasNext()) {
            Map.Entry<UUID, EnumMap<StatusType, StatusInstance>> entry = entityIterator.next();
            Entity rawEntity = Bukkit.getEntity(entry.getKey());
            if (!(rawEntity instanceof LivingEntity target) || target.isDead() || !target.isValid()) {
                entityIterator.remove();
                continue;
            }

            Iterator<Map.Entry<StatusType, StatusInstance>> statusIterator = entry.getValue().entrySet().iterator();
            while (statusIterator.hasNext()) {
                StatusInstance instance = statusIterator.next().getValue();
                if (instance.tickClock()) {
                    double damage = instance.consumeTickDamage();
                    if (damage > 0.0) {
                        StatusProfile profile = profile(instance.type());
                        if (profile != null) {
                            damageService.applyDamage(new DamagePacket(
                                    instance.source(),
                                    target,
                                    damage,
                                    profile.category(),
                                    profile.tags(),
                                    DamageSourceKind.CUSTOM_STATUS,
                                    instance.type().name()
                            ), instance.canKill());
                        }
                    }
                }
                if (instance.expired()) {
                    statusIterator.remove();
                }
            }

            if (entry.getValue().isEmpty()) {
                entityIterator.remove();
            }
        }
        tickBleeds();
    }

    private void tickBleeds() {
        Iterator<Map.Entry<UUID, List<BleedApplication>>> entityIterator = activeBleeds.entrySet().iterator();
        while (entityIterator.hasNext()) {
            Map.Entry<UUID, List<BleedApplication>> entry = entityIterator.next();
            Entity rawEntity = Bukkit.getEntity(entry.getKey());
            if (!(rawEntity instanceof LivingEntity target) || target.isDead() || !target.isValid()) {
                entityIterator.remove();
                continue;
            }

            Iterator<BleedApplication> bleedIterator = entry.getValue().iterator();
            while (bleedIterator.hasNext()) {
                BleedApplication application = bleedIterator.next();
                StatusInstance instance = application.instance();
                if (instance.tickClock()) {
                    double requestedDamage = instance.consumeTickDamage();
                    if (requestedDamage > 0.0) {
                        DamageResult result = damageService.applyDamage(new DamagePacket(
                                instance.source(),
                                target,
                                requestedDamage,
                                DamageCategory.TRUE,
                                EnumSet.of(DamageTag.BLEED, DamageTag.DOT, DamageTag.STATUS),
                                DamageSourceKind.CUSTOM_STATUS,
                                "bleed:" + application.reason()
                        ), instance.canKill());
                        double actualDamage = result == null ? 0.0 : Math.max(0.0, result.actualDamage());
                        Bukkit.getPluginManager().callEvent(new BleedDamageEvent(
                                instance.source(),
                                target,
                                requestedDamage,
                                actualDamage,
                                application.reason()
                        ));
                    }
                }
                if (instance.expired()) {
                    bleedIterator.remove();
                }
            }

            if (entry.getValue().isEmpty()) {
                entityIterator.remove();
            }
        }
    }

    private StatusProfile profile(StatusType type) {
        return switch (type) {
            case BURNING -> new StatusProfile(DamageCategory.MAGIC, EnumSet.of(DamageTag.FIRE, DamageTag.DOT, DamageTag.STATUS), 20, true, 1);
            case POISONED -> new StatusProfile(DamageCategory.MAGIC, EnumSet.of(DamageTag.POISON, DamageTag.DOT, DamageTag.STATUS), 20, false, 1);
            case WITHERED -> new StatusProfile(DamageCategory.MAGIC, EnumSet.of(DamageTag.WITHER, DamageTag.DOT, DamageTag.STATUS), 20, true, 1);
            case BLEEDING -> new StatusProfile(DamageCategory.TRUE, EnumSet.of(DamageTag.BLEED, DamageTag.DOT, DamageTag.STATUS), 20, true, 5);
            case FROSTBITE, STUNNED -> null;
        };
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record StatusProfile(DamageCategory category, Set<DamageTag> tags, int tickInterval, boolean canKill, int maxStacks) {
    }

    private record BleedApplication(StatusInstance instance, String reason) {
    }
}
