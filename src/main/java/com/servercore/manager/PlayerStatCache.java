package com.servercore.manager;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerStatCache {
    private static PlayerStatCache instance;
    private final Map<UUID, CombatStats> cache = new HashMap<>();
    private final Map<UUID, Double> currentPowerLevels = new HashMap<>();
    private final Map<UUID, Double> targetPowerLevels = new HashMap<>();

    public PlayerStatCache() {
        instance = this;
    }

    public static PlayerStatCache getInstance() {
        return instance;
    }

    /**
     * 更新该玩家的静态属性快照（内存快照）
     */
    public void updateCache(Player player) {
        AttributeManager attributeManager = AttributeManager.getInstance();
        if (attributeManager != null) {
            attributeManager.refreshPlayer(player);
        }

        CombatStats staticStats = CombatStats.calculateStatic(player);
        cache.put(player.getUniqueId(), staticStats);
    }

    /**
     * 获取玩家的极速内存快照
     */
    public CombatStats getCachedStats(Player player) {
        if (!cache.containsKey(player.getUniqueId())) {
            updateCache(player);
        }
        return cache.get(player.getUniqueId());
    }

    /**
     * 玩家离线时清理内存
     */
    public void remove(Player player) {
        cache.remove(player.getUniqueId());
        currentPowerLevels.remove(player.getUniqueId());
        targetPowerLevels.remove(player.getUniqueId());
    }

    public double getCurrentPower(Player player) {
        return currentPowerLevels.getOrDefault(player.getUniqueId(), 1.0);
    }

    public void setCurrentPower(Player player, double power) {
        currentPowerLevels.put(player.getUniqueId(), power);
    }

    public double getTargetPower(Player player) {
        return targetPowerLevels.getOrDefault(player.getUniqueId(), getCurrentPower(player));
    }

    public void setTargetPower(Player player, double power) {
        targetPowerLevels.put(player.getUniqueId(), power);
    }
}
