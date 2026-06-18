package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import dev.aurelium.auraskills.api.AuraSkillsApi;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TargetPower 是即时理论战力；SpawnPower 是自然刷怪使用的滑动采样值。
 */
public class PowerLevelManager {

    private static final double DEFAULT_MIN_POWER = 1.0;
    private static final double DEFAULT_MAX_POWER = 10000.0;

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
        long interval = Math.max(1L, plugin.getConfig().getLong("power.spawn_power.update_interval_ticks", 20L));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSpawnPower, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public double calculateTargetPower(Player player) {
        return calculatePowerBreakdown(player, CombatStats.getFullStats(player)).targetPower();
    }

    public double getTargetPower(Player player) {
        return calculateTargetPower(player);
    }

    public PowerBreakdown calculatePowerBreakdown(Player player) {
        return calculatePowerBreakdown(player, CombatStats.getFullStats(player));
    }

    public PowerBreakdown calculatePowerBreakdown(Player player, CombatStats stats) {
        double meleeDps = calculateMeleeDps(player, stats);
        double rangedDps = calculateRangedDps(player, stats);
        double magicDps = calculateMagicDps(player, stats);
        double offenseScore = calculateOffenseScore(meleeDps, rangedDps, magicDps);

        double maxHealth = getMaxHealth(player);
        double armor = calculateEffectiveArmorValue(player);
        double damageReduction = armor <= 0.0 ? 0.0 : armor / (armor + 100.0);
        double effectiveHealth = maxHealth / Math.max(0.05, 1.0 - damageReduction);

        double lifestealRate = getEffectiveLifestealRate(player, stats);
        double lifestealPerSecond = calculateLifestealEffectiveDps(player, stats) * lifestealRate;
        double regenPerSecond = getRegenPerSecond(player);
        double shieldValuePerSecond = estimateShieldValuePerSecond(player, maxHealth);
        double sustainPerSecond = lifestealPerSecond + regenPerSecond + shieldValuePerSecond;
        double maxSustainFactor = plugin.getConfig().getDouble("power.sustain.max_sustain_factor", 0.50);
        double sustainFactor = clamp(sustainPerSecond / Math.max(1.0, maxHealth), 0.0, maxSustainFactor);
        double survivalScore = effectiveHealth * (1.0 + sustainFactor);

        double denominator = Math.max(0.0001, plugin.getConfig().getDouble("power.denominator", 4.0));
        double globalScale = plugin.getConfig().getDouble("power.global_scale", 1.0);
        double targetPower = Math.sqrt(Math.max(1.0, offenseScore) * Math.sqrt(Math.max(1.0, survivalScore)) / denominator) * globalScale;
        targetPower = clamp(targetPower, minPower(), maxPower());

        double spawnPower = readCachedSpawnPower(player, targetPower);

        return new PowerBreakdown(
                clamp(meleeDps, 0.0, Double.MAX_VALUE),
                clamp(rangedDps, 0.0, Double.MAX_VALUE),
                clamp(magicDps, 0.0, Double.MAX_VALUE),
                clamp(offenseScore, 0.0, Double.MAX_VALUE),
                clamp(maxHealth, 1.0, Double.MAX_VALUE),
                clamp(armor, 0.0, Double.MAX_VALUE),
                clamp(damageReduction, 0.0, 0.95),
                clamp(effectiveHealth, 1.0, Double.MAX_VALUE),
                clamp(lifestealPerSecond, 0.0, Double.MAX_VALUE),
                clamp(regenPerSecond, 0.0, Double.MAX_VALUE),
                clamp(shieldValuePerSecond, 0.0, Double.MAX_VALUE),
                clamp(sustainFactor, 0.0, maxSustainFactor),
                clamp(survivalScore, 1.0, Double.MAX_VALUE),
                targetPower,
                spawnPower
        );
    }

    public double calculateMeleeEdph(CombatStats stats) {
        return calculateMeleeHit(null, stats);
    }

    private double calculateMeleeDps(Player player, CombatStats stats) {
        WeaponTemplateManager templateManager = WeaponTemplateManager.getInstance();
        WeaponTemplateManager.WeaponProfile profile = getMainHandProfile(player, templateManager);
        if (profile != null && !profile.melee()) {
            return 0.0;
        }
        if (isMainWeaponBlocked(player, templateManager)) {
            return 0.0;
        }

        double attacksPerSecond = profile == null || profile.attackSpeed() <= 0.0 ? 1.0 : profile.attackSpeed();
        attacksPerSecond *= 1.0 + getAttackSpeedBonus(player, templateManager) / 100.0;
        double reliability = profile == null ? 1.0 : profile.reliabilityFactor();
        double uptime = profile == null ? 1.0 : profile.uptimeFactor();
        double aoe = profile == null ? 1.0 : profile.aoeFactor();

        return calculateMeleeHit(player, stats) * attacksPerSecond * reliability * uptime * aoe;
    }

    private double calculateMeleeHit(Player player, CombatStats stats) {
        double baseDamage = Math.max(DEFAULT_MIN_POWER, stats.baseDamage());
        double multiplier = Math.max(0.1, stats.baseMultiplier());
        double skillMultiplier = getSkillMultiplier(player, "fighting");
        double critChance = clamp(stats.critChance(), 0.0, 1.0);
        double critMultiplier = Math.max(1.0, stats.critDamage());
        double expectedCrit = 1.0 + critChance * (critMultiplier - 1.0);
        double expectedBrutality = expectedBrutalityFactor(stats.brutality());
        return baseDamage * multiplier * skillMultiplier * expectedCrit * expectedBrutality;
    }

    public double calculateRangedEdph(CombatStats stats) {
        return calculateRangedHit(null, stats);
    }

    private double calculateRangedDps(Player player, CombatStats stats) {
        WeaponTemplateManager templateManager = WeaponTemplateManager.getInstance();
        WeaponTemplateManager.WeaponProfile profile = getMainHandProfile(player, templateManager);
        if (profile == null || !profile.ranged()) {
            return 0.0;
        }
        if (isMainWeaponBlocked(player, templateManager)) {
            return 0.0;
        }

        double attacksPerSecond = profile.attackSpeed() <= 0.0 ? 0.75 : profile.attackSpeed();
        attacksPerSecond *= 1.0 + getAttackSpeedBonus(player, templateManager) / 100.0;
        return calculateRangedHit(player, stats) * attacksPerSecond * profile.reliabilityFactor() * profile.uptimeFactor() * profile.aoeFactor();
    }

    private double calculateRangedHit(Player player, CombatStats stats) {
        double baseDamage = Math.max(DEFAULT_MIN_POWER, stats.baseDamage());
        double multiplier = Math.max(0.1, stats.baseMultiplier());
        double skillMultiplier = getSkillMultiplier(player, "archery");
        double critChance = clamp(stats.critChance(), 0.0, 1.0);
        double critMultiplier = Math.max(1.0, stats.critDamage());
        double expectedCrit = 1.0 + critChance * (critMultiplier - 1.0);
        double penetrationValue = 1.0 + clamp(stats.armorPen() / 100.0, 0.0, 1.0);
        return baseDamage * multiplier * skillMultiplier * expectedCrit * penetrationValue;
    }

    public double calculateMagicEdph(Player player, CombatStats stats) {
        return calculateMagicDps(player, stats);
    }

    private double calculateMagicDps(Player player, CombatStats stats) {
        double baseMagicDamage = Math.max(DEFAULT_MIN_POWER, stats.baseDamage());
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
        double health = getMaxHealth(player);
        double damageReduction = calculateDamageReduction(player);
        return health / Math.max(0.05, 1.0 - damageReduction);
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

    public double getSpawnPower(Player player) {
        PlayerStatCache cache = PlayerStatCache.getInstance();
        if (cache == null) {
            return calculateTargetPower(player);
        }

        double spawnPower = cache.getSpawnPower(player);
        if (spawnPower <= minPower()) {
            spawnPower = calculateTargetPower(player);
            cache.setSpawnPower(player, spawnPower);
        }
        return spawnPower;
    }

    /**
     * Compatibility alias for older call sites. Natural spawning should treat this as SpawnPower.
     */
    public double getCurrentPower(Player player) {
        return getSpawnPower(player);
    }

    public void tickPowerLevels() {
        tickSpawnPower();
    }

    public void tickSpawnPower() {
        PlayerStatCache cache = PlayerStatCache.getInstance();
        if (cache == null) return;

        double riseAlpha = clamp(plugin.getConfig().getDouble("power.spawn_power.rise_alpha", 0.25), 0.0, 1.0);
        double fallAlpha = clamp(plugin.getConfig().getDouble("power.spawn_power.fall_alpha", 0.05), 0.0, 1.0);

        for (Player player : Bukkit.getOnlinePlayers()) {
            double targetPower = calculateTargetPower(player);
            double spawnPower = cache.getSpawnPower(player);

            if (spawnPower <= minPower()) {
                spawnPower = targetPower;
            } else {
                double alpha = targetPower > spawnPower ? riseAlpha : fallAlpha;
                spawnPower += (targetPower - spawnPower) * alpha;
            }

            cache.setTargetPower(player, targetPower);
            cache.setSpawnPower(player, clamp(spawnPower, minPower(), maxPower()));
        }
    }

    public double getNearbyMedianSpawnPower(Location location, double radius) {
        if (location == null || location.getWorld() == null) {
            return minPower();
        }

        double safeRadius = radius <= 0.0
                ? plugin.getConfig().getDouble("power.spawn_power.nearby_radius", 64.0)
                : radius;
        double radiusSquared = safeRadius * safeRadius;
        List<Double> powers = new ArrayList<>();
        for (Player player : location.getWorld().getPlayers()) {
            if (!player.isValid() || player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            if (player.getLocation().distanceSquared(location) > radiusSquared) {
                continue;
            }
            powers.add(getSpawnPower(player));
        }

        if (powers.isEmpty()) {
            return minPower();
        }

        Collections.sort(powers);
        int middle = powers.size() / 2;
        if (powers.size() % 2 == 1) {
            return powers.get(middle);
        }
        return (powers.get(middle - 1) + powers.get(middle)) / 2.0;
    }

    private double calculateOffenseScore(double meleeDps, double rangedDps, double magicDps) {
        List<Double> values = new ArrayList<>(List.of(meleeDps, rangedDps, magicDps));
        values.sort(Collections.reverseOrder());
        double secondaryWeight = plugin.getConfig().getDouble("power.offense.secondary_weight", 0.25);
        double thirdWeight = plugin.getConfig().getDouble("power.offense.third_weight", 0.10);
        return values.get(0) + secondaryWeight * values.get(1) + thirdWeight * values.get(2);
    }

    private double calculateLifestealEffectiveDps(Player player, CombatStats stats) {
        WeaponTemplateManager templateManager = WeaponTemplateManager.getInstance();
        WeaponTemplateManager.WeaponProfile profile = getMainHandProfile(player, templateManager);
        if (profile != null && !profile.melee()) {
            return 0.0;
        }
        if (isMainWeaponBlocked(player, templateManager)) {
            return 0.0;
        }

        double attacksPerSecond = profile == null || profile.attackSpeed() <= 0.0 ? 1.0 : profile.attackSpeed();
        attacksPerSecond *= 1.0 + getAttackSpeedBonus(player, templateManager) / 100.0;
        double reliability = profile == null ? 1.0 : profile.reliabilityFactor();
        double uptime = profile == null ? 1.0 : profile.uptimeFactor();
        return Math.max(DEFAULT_MIN_POWER, stats.baseDamage())
                * Math.max(0.1, stats.baseMultiplier())
                * getSkillMultiplier(player, "fighting")
                * expectedBrutalityFactor(stats.brutality())
                * attacksPerSecond
                * reliability
                * uptime;
    }

    private double getEffectiveLifestealRate(Player player, CombatStats stats) {
        double lifestealRate = stats.lifesteal();
        ClassManager classManager = ClassManager.getInstance();
        if (classManager != null) {
            lifestealRate *= classManager.getLifestealMultiplier(player);
        }
        return Math.max(0.0, lifestealRate);
    }

    private double getRegenPerSecond(Player player) {
        AttributeManager attributeManager = AttributeManager.getInstance();
        return attributeManager == null ? 0.0 : attributeManager.getHealthRegenPerSecond(player);
    }

    private double estimateShieldValuePerSecond(Player player, double maxHealth) {
        ShieldManager shieldManager = ShieldManager.getInstance();
        return shieldManager == null ? 0.0 : shieldManager.estimateShieldValuePerSecond(player, maxHealth);
    }

    private double getMaxHealth(Player player) {
        AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        return maxHealthAttribute == null ? 20.0 : Math.max(1.0, maxHealthAttribute.getValue());
    }

    private WeaponTemplateManager.WeaponProfile getMainHandProfile(Player player, WeaponTemplateManager templateManager) {
        if (player == null || templateManager == null) {
            return null;
        }
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        WeaponTemplateManager.WeaponTemplate template = templateManager.getTemplate(mainHand);
        if (template == null) {
            template = templateManager.getDefaultTemplate(mainHand == null ? null : mainHand.getType());
        }
        return template == null ? null : templateManager.getProfile(template);
    }

    private boolean isMainWeaponBlocked(Player player, WeaponTemplateManager templateManager) {
        if (player == null || templateManager == null) {
            return false;
        }
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        return mainHand != null && !mainHand.getType().isAir() && !templateManager.canUseMainHandWeapon(player, mainHand);
    }

    private double getAttackSpeedBonus(Player player, WeaponTemplateManager templateManager) {
        return templateManager == null ? 0.0 : templateManager.getPlayerAttackSpeedBonusScore(player);
    }

    private double expectedBrutalityFactor(double brutality) {
        return 1.0 + Math.max(0.0, brutality) / 100.0;
    }

    private double readCachedSpawnPower(Player player, double fallback) {
        PlayerStatCache cache = PlayerStatCache.getInstance();
        return cache == null ? fallback : Math.max(minPower(), cache.getSpawnPower(player));
    }

    private double minPower() {
        return plugin.getConfig().getDouble("power.min_power", DEFAULT_MIN_POWER);
    }

    private double maxPower() {
        return plugin.getConfig().getDouble("power.max_power", DEFAULT_MAX_POWER);
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
            double meleeDps,
            double rangedDps,
            double magicDps,
            double offenseScore,
            double maxHealth,
            double armor,
            double damageReduction,
            double effectiveHealth,
            double lifestealPerSecond,
            double regenPerSecond,
            double shieldValuePerSecond,
            double sustainFactor,
            double survivalScore,
            double targetPower,
            double spawnPower
    ) {
        public double meleeEdph() {
            return meleeDps;
        }

        public double rangedEdph() {
            return rangedDps;
        }

        public double magicEdph() {
            return magicDps;
        }

        public double level() {
            return targetPower;
        }
    }
}
