package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;

/**
 * Converts the five core attributes into non-combat global stats.
 */
public class GlobalStatManager {

    private static final double GLOBAL_FORTUNE_PER_LUCK = 0.6;
    private static final double MAGIC_FIND_PER_LUCK = 0.5;
    private static final int AGILITY_PER_BREAK_SPEED_STEP = 125;
    private static final double BLOCK_BREAK_SPEED_PER_STEP = 0.2;

    private static GlobalStatManager instance;

    private final NamespacedKey agilityBreakSpeedKey;
    private BukkitTask breakSpeedTask;

    public GlobalStatManager(ServerCorePlugin plugin) {
        instance = this;
        this.agilityBreakSpeedKey = new NamespacedKey(plugin, "agility_block_break_speed");
        this.breakSpeedTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAgilityBreakSpeed, 20L, 40L);
    }

    public static GlobalStatManager getInstance() {
        return instance;
    }

    public void stop() {
        if (breakSpeedTask != null) {
            breakSpeedTask.cancel();
            breakSpeedTask = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyAgilityBreakSpeed(player, 0.0);
        }
    }

    /**
     * 1 Luck -> 0.6 Global Fortune.
     */
    public double getGlobalFortune(Player player) {
        return Math.max(0.0, getLuck(player) * GLOBAL_FORTUNE_PER_LUCK);
    }

    /**
     * 1 Luck -> 0.5 Magic Find.
     */
    public double getMagicFind(Player player) {
        return Math.max(0.0, getLuck(player) * MAGIC_FIND_PER_LUCK);
    }

    /**
     * Applies Magic Find only to rare combat drops below 5%.
     */
    public double applyMagicFind(Player player, double baseChance) {
        if (baseChance <= 0.0 || baseChance >= 0.05) {
            return baseChance;
        }
        return baseChance * (100.0 + getMagicFind(player)) / 100.0;
    }

    public void tickAgilityBreakSpeed() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            int steps = Math.max(0, getAgility(player) / AGILITY_PER_BREAK_SPEED_STEP);
            applyAgilityBreakSpeed(player, steps * BLOCK_BREAK_SPEED_PER_STEP);
        }
    }

    private void applyAgilityBreakSpeed(Player player, double speedValue) {
        AttributeInstance attribute = player.getAttribute(Attribute.PLAYER_BLOCK_BREAK_SPEED);
        if (attribute == null) {
            return;
        }

        for (AttributeModifier modifier : new ArrayList<>(attribute.getModifiers())) {
            if (modifier.getKey().equals(agilityBreakSpeedKey)) {
                attribute.removeModifier(modifier);
            }
        }

        if (speedValue > 0.0001) {
            attribute.addModifier(new AttributeModifier(agilityBreakSpeedKey, speedValue, AttributeModifier.Operation.ADD_SCALAR));
        }
    }

    private int getLuck(Player player) {
        AttributeManager attributeManager = AttributeManager.getInstance();
        if (attributeManager != null) {
            return attributeManager.getLuck(player);
        }

        AuraSkillsBridge bridge = AuraSkillsBridge.getInstance();
        return bridge == null ? 0 : bridge.getLuckBonus(player);
    }

    private int getAgility(Player player) {
        AttributeManager attributeManager = AttributeManager.getInstance();
        if (attributeManager != null) {
            return attributeManager.getAgility(player);
        }

        AuraSkillsBridge bridge = AuraSkillsBridge.getInstance();
        return bridge == null ? 0 : bridge.getAgilityBonus(player);
    }
}
