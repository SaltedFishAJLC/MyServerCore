package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import dev.aurelium.auraskills.api.AuraSkillsApi;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * 战斗力等级 (Power Level) 计算与防作弊滑动平均引擎 骨架
 */
public class PowerLevelManager {

    private static final double SMOOTHING_FACTOR = 0.10;
    private static final double MIN_POWER = 1.0;
    private static final double MAX_TRACKED_POWER = 1000.0;

    private static PowerLevelManager instance;

    private final ServerCorePlugin plugin;
    private BukkitTask task;

    public PowerLevelManager(ServerCorePlugin plugin) {
        this.plugin = plugin;
        instance = this;
    }

    public static PowerLevelManager getInstance() {
        return instance;
    }

    public void start() {
        if (task != null) return;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPowerLevels, 20L, 100L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * 根据玩家 EDPH 与 EHP 闭环计算理论战斗等级。
     */
    public double calculateTargetPower(Player player) {
        CombatStats stats = CombatStats.getFullStats(player);
        return calculatePowerBreakdown(player, stats).level();
    }

    public PowerBreakdown calculatePowerBreakdown(Player player) {
        return calculatePowerBreakdown(player, CombatStats.getFullStats(player));
    }

    public PowerBreakdown calculatePowerBreakdown(Player player, CombatStats stats) {
        double meleeEdph = calculateMeleeEdph(player, stats);
        double rangedEdph = calculateRangedEdph(player, stats);
        double magicEdph = calculateMagicEdph(player, stats);
        double effectiveDamagePerHit = Math.max(meleeEdph, Math.max(rangedEdph, magicEdph));
        double ehp = calculateEffectiveHealth(player);
        double level = Math.sqrt(Math.max(MIN_POWER, effectiveDamagePerHit) * Math.max(1.0, ehp) / 20.0) * 1.5;

        return new PowerBreakdown(
                clamp(meleeEdph, 0.0, Double.MAX_VALUE),
                clamp(rangedEdph, 0.0, Double.MAX_VALUE),
                clamp(magicEdph, 0.0, Double.MAX_VALUE),
                clamp(ehp, 1.0, Double.MAX_VALUE),
                clamp(level, MIN_POWER, MAX_TRACKED_POWER)
        );
    }

    public double calculateMeleeEdph(CombatStats stats) {
        return calculateMeleeEdph(null, stats);
    }

    private double calculateMeleeEdph(Player player, CombatStats stats) {
        double baseDamage = Math.max(MIN_POWER, stats.baseDamage());
        double emul = Math.max(0.1, stats.baseMultiplier());
        double skillMultiplier = getSkillMultiplier(player, "fighting");
        double critChance = clamp(stats.critChance(), 0.0, 1.0);
        double critMultiplier = Math.max(1.0, stats.critDamage());
        double expectedCrit = 1.0 + critChance * (critMultiplier - 1.0);
        double brutalityExpected = 1.0 + Math.max(0.0, stats.brutality()) / 200.0;

        return baseDamage * emul * skillMultiplier * expectedCrit * brutalityExpected;
    }

    public double calculateRangedEdph(CombatStats stats) {
        return calculateRangedEdph(null, stats);
    }

    private double calculateRangedEdph(Player player, CombatStats stats) {
        double baseDamage = Math.max(MIN_POWER, stats.baseDamage());
        double emul = Math.max(0.1, stats.baseMultiplier());
        double skillMultiplier = getSkillMultiplier(player, "archery");
        double rawDamage = baseDamage * emul * skillMultiplier;
        double penetration = clamp(stats.armorPen() / 100.0, 0.0, 1.0);
        double trueDamage = rawDamage * penetration;
        double blockedDamage = rawDamage * (1.0 - penetration);

        return blockedDamage + trueDamage * 2.0;
    }

    public double calculateMagicEdph(Player player, CombatStats stats) {
        double baseMagicDamage = Math.max(MIN_POWER, stats.baseDamage());
        double maxMana = 100.0;
        AttributeManager attributeManager = AttributeManager.getInstance();
        if (attributeManager != null) {
            maxMana = attributeManager.getEffectiveMaxMana(player);
        } else {
            AuraSkillsApi auraSkills = AuraSkillsApi.get();
            if (auraSkills != null && auraSkills.getUser(player.getUniqueId()) != null) {
                maxMana = auraSkills.getUser(player.getUniqueId()).getMaxMana();
            }
        }

        baseMagicDamage *= 1.0 + Math.max(0.0, maxMana - 100.0) / 800.0;
        ClassManager classManager = ClassManager.getInstance();
        if (classManager != null) {
            baseMagicDamage *= 1.0 + Math.max(0.0, classManager.getMagicMultiplierBonus(player));
        }

        return baseMagicDamage * Math.max(0.1, stats.baseMultiplier()) * getSkillMultiplier(player, "sorcery");
    }

    public double calculateEffectiveHealth(Player player) {
        AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);

        double health = maxHealthAttribute == null ? 20.0 : maxHealthAttribute.getValue();
        double damageReduction = calculateDamageReduction(player);

        return health / Math.max(0.01, 1.0 - damageReduction);
    }

    public double calculateArmorValue(Player player) {
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) return 0.0;

        double armor = 0.0;
        for (org.bukkit.inventory.ItemStack item : player.getInventory().getArmorContents()) {
            armor += pdc.getStat(item, pdc.KEY_BASE_ARMOR);
        }

        AccessoryManager accessoryManager = AccessoryManager.getInstance();
        if (accessoryManager != null) {
            for (org.bukkit.inventory.ItemStack item : accessoryManager.loadAccessories(player)) {
                armor += pdc.getStat(item, pdc.KEY_BASE_ARMOR);
            }
            for (org.bukkit.inventory.ItemStack item : accessoryManager.loadTalismanBag(player, 54)) {
                armor += pdc.getStat(item, pdc.KEY_BASE_ARMOR);
            }
        }

        AttributeManager attributeManager = AttributeManager.getInstance();
        if (attributeManager != null) {
            armor += attributeManager.getArmorBonus(player);
        }

        return Math.max(0.0, armor);
    }

    public double calculateEffectiveArmorValue(Player player) {
        double armor = calculateArmorValue(player);

        ClassManager classManager = ClassManager.getInstance();
        if (classManager != null) {
            armor *= Math.max(0.0, 1.0 - classManager.getArmorPenaltyRate(player));
        }

        return Math.max(0.0, armor);
    }

    public double calculateDamageReduction(Player player) {
        double armor = calculateEffectiveArmorValue(player);
        return armor <= 0.0 ? 0.0 : armor / (armor + 100.0);
    }

    /**
     * 获取玩家当前的实际战斗力 (Current Power)
     * 获取该值用于动态刷怪。
     */
    public double getCurrentPower(Player player) {
        PlayerStatCache cache = PlayerStatCache.getInstance();
        if (cache == null) {
            return calculateTargetPower(player);
        }

        double current = cache.getCurrentPower(player);
        if (current <= MIN_POWER) {
            current = calculateTargetPower(player);
            cache.setCurrentPower(player, current);
        }
        return current;
    }

    /**
     * 定时任务/事件调用：使所有在线玩家的 Current Power 以 10% 的步长向 Target Power 靠拢。
     * 防止玩家通过瞬间穿脱装备来改变周围怪物的生成等级。
     */
    public void tickPowerLevels() {
        PlayerStatCache cache = PlayerStatCache.getInstance();
        if (cache == null) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            double targetPower = calculateTargetPower(player);
            double currentPower = cache.getCurrentPower(player);

            if (currentPower <= MIN_POWER) {
                currentPower = targetPower;
            } else {
                currentPower += (targetPower - currentPower) * SMOOTHING_FACTOR;
            }

            cache.setTargetPower(player, targetPower);
            cache.setCurrentPower(player, clamp(currentPower, MIN_POWER, MAX_TRACKED_POWER));
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double getSkillMultiplier(Player player, String skillType) {
        if (player == null) {
            return 1.0;
        }

        AuraSkillsBridge bridge = AuraSkillsBridge.getInstance();
        if (bridge == null) {
            return 1.0;
        }

        return Math.max(0.1, 1.0 + bridge.getCombatSkillMultiplier(player, skillType));
    }

    public record PowerBreakdown(
            double meleeEdph,
            double rangedEdph,
            double magicEdph,
            double effectiveHealth,
            double level
    ) {
    }
}
