package com.servercore.combat.status;

import com.servercore.ServerCorePlugin;
import com.servercore.combat.damage.DamageCategory;
import com.servercore.combat.damage.DamagePacket;
import com.servercore.combat.damage.DamageService;
import com.servercore.combat.damage.DamageSourceKind;
import com.servercore.combat.damage.DamageTag;
import com.servercore.combat.resistance.ResistanceResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class StatusService {

    private static StatusService instance;

    private final DamageService damageService;
    private final ResistanceResolver resistanceResolver;
    private final Map<UUID, EnumMap<StatusType, StatusInstance>> activeStatuses = new ConcurrentHashMap<>();
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
    }

    public boolean applyStatus(LivingEntity source, LivingEntity target, StatusType type, double totalDamage, int durationTicks) {
        if (target == null || type == null || target.isDead() || !target.isValid()) {
            return false;
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
        } else if (type == StatusType.BLEEDING) {
            existing.addStackIntensity(source, adjustedDamage, safeDuration);
        } else {
            existing.addDamagePool(source, adjustedDamage, safeDuration);
        }

        if (type == StatusType.BURNING) {
            target.setFireTicks(Math.max(target.getFireTicks(), safeDuration));
        }
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

    private record StatusProfile(DamageCategory category, Set<DamageTag> tags, int tickInterval, boolean canKill, int maxStacks) {
    }
}
