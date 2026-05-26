package com.servercore.combat.status;

import com.servercore.ServerCorePlugin;
import com.servercore.combat.resistance.ResistanceResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FrostService {

    public static final int LIGHT_FROST = 5;
    public static final int MEDIUM_FROST = 10;
    public static final int HEAVY_FROST = 20;

    private static FrostService instance;

    private final ResistanceResolver resistanceResolver;
    private final StunController stunController;
    private final Map<UUID, FrostData> frostData = new ConcurrentHashMap<>();
    private final BukkitTask tickTask;
    private int currentTick;

    public FrostService(ServerCorePlugin plugin, ResistanceResolver resistanceResolver, StunController stunController) {
        instance = this;
        this.resistanceResolver = resistanceResolver;
        this.stunController = stunController;
        this.tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickFrost, 1L, 1L);
    }

    public static FrostService getInstance() {
        return instance;
    }

    public void stop() {
        tickTask.cancel();
        frostData.clear();
    }

    public boolean addFrost(LivingEntity target, int amount) {
        if (target == null || target.isDead() || !target.isValid() || amount <= 0) {
            return false;
        }

        double applyMultiplier = resistanceResolver.resolveStatusApplyMultiplier(target, StatusType.FROSTBITE);
        if (applyMultiplier <= 0.0) {
            clearFrostEffects(target);
            return false;
        }

        int adjustedAmount = Math.max(1, (int) Math.round(amount * applyMultiplier));
        FrostData data = frostData.computeIfAbsent(target.getUniqueId(), ignored -> new FrostData());
        data.frostCounter = Math.min(100, data.frostCounter + adjustedAmount);
        data.lastAppliedTick = currentTick;

        if (data.frostCounter >= 100) {
            data.frostCounter = 0;
            clearFrostEffects(target);
            stunController.stun(target, 60);
            return true;
        }

        applyFrostEffects(target, data.frostCounter);
        return true;
    }

    private void tickFrost() {
        currentTick++;
        if (currentTick % 5 == 0) {
            applyPowderSnowFrost();
        }
        if (currentTick % 20 == 0) {
            decayFrost();
        }
    }

    private void applyPowderSnowFrost() {
        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                if (entity.isDead() || !entity.isValid()) {
                    continue;
                }
                Material feet = entity.getLocation().getBlock().getType();
                Material eyes = entity.getEyeLocation().getBlock().getType();
                if (feet == Material.POWDER_SNOW || eyes == Material.POWDER_SNOW) {
                    addFrost(entity, LIGHT_FROST);
                }
            }
        }
    }

    private void decayFrost() {
        Iterator<Map.Entry<UUID, FrostData>> iterator = frostData.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, FrostData> entry = iterator.next();
            org.bukkit.entity.Entity rawEntity = Bukkit.getEntity(entry.getKey());
            if (!(rawEntity instanceof LivingEntity entity) || entity.isDead() || !entity.isValid()) {
                iterator.remove();
                continue;
            }

            FrostData data = entry.getValue();
            if (currentTick - data.lastAppliedTick <= 60) {
                continue;
            }
            data.frostCounter = Math.max(0, data.frostCounter - 5);
            if (data.frostCounter <= 0) {
                clearFrostEffects(entity);
                iterator.remove();
            } else {
                applyFrostEffects(entity, data.frostCounter);
            }
        }
    }

    private void applyFrostEffects(LivingEntity target, int frostCounter) {
        int level = frostCounter / 20;
        if (level <= 0) {
            clearFrostEffects(target);
            return;
        }

        int amplifier = Math.max(0, Math.min(3, level - 1));
        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 45, amplifier, false, false, true));
        target.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 45, amplifier, false, false, true));
    }

    private void clearFrostEffects(LivingEntity target) {
        target.removePotionEffect(PotionEffectType.SLOWNESS);
        target.removePotionEffect(PotionEffectType.MINING_FATIGUE);
    }

    public static final class FrostData {
        public int frostCounter;
        public int lastAppliedTick;
    }
}
