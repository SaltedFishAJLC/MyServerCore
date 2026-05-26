package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import com.servercore.combat.creature.CreatureTagService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 怪物动态生成与标签引擎 骨架
 */
public class MobSpawnManager implements Listener {

    private static final double NORMAL_MOD = 1.0;
    private static final double ELITE_MOD = 1.8;
    private static final double MINI_BOSS_MOD = 3.0;
    private static final double BOSS_MOD = 6.4;

    private static MobSpawnManager instance;

    private final ServerCorePlugin plugin;
    private final PowerLevelManager powerLevelManager;
    private final HologramManager hologramManager;
    private final EconomyManager economyManager;
    private final CustomMobRegistry customMobRegistry;
    private final Map<EntityType, VanillaMobStats> baseVanillaStats = new EnumMap<>(EntityType.class);
    private final Map<String, Double> customMobModifiers = new ConcurrentHashMap<>();
    private final BukkitTask hostileTargetTask;

    public MobSpawnManager(ServerCorePlugin plugin, PowerLevelManager powerLevelManager,
                           HologramManager hologramManager, EconomyManager economyManager,
                           CustomMobRegistry customMobRegistry) {
        this.plugin = plugin;
        this.powerLevelManager = powerLevelManager;
        this.hologramManager = hologramManager;
        this.economyManager = economyManager;
        this.customMobRegistry = customMobRegistry;
        instance = this;
        initDefaultVanillaStats();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        this.hostileTargetTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickAlwaysHostileMobs, 20L, 40L);
    }

    public static MobSpawnManager getInstance() {
        return instance;
    }

    public void stop() {
        hostileTargetTask.cancel();
    }

    /**
     * 自定义怪物倍率接口。外部模块可用 MythicMobs 内部名或自定义 PDC 标识覆盖 mod。
     */
    public void registerCustomMobModifier(String mythicName, double customMod) {
        if (mythicName == null || mythicName.isBlank()) return;
        customMobModifiers.put(normalizeCustomMobId(mythicName), Math.max(0.1, customMod));
    }

    /**
     * 自定义基础属性接口。用于覆盖某类生物参与生态公式的原版底数。
     */
    public void setBaseVanillaStats(EntityType type, double hp, double atk) {
        if (type == null) return;
        baseVanillaStats.put(type, new VanillaMobStats(Math.max(1.0, hp), Math.max(0.0, atk)));
    }

    /**
     * 拦截原版怪物生成
     * 1. 获取生成点 64 格内的玩家，计算战斗等级 (Power Level) 中位数。
     * 2. 世界限幅：主世界 50，下界 150，末地 250。
     * 3. 刷怪笼：强制拉满到世界限幅。
     * 4. 设置怪物原版 Attribute：生命值、攻击力。并记录战斗等级到 PDC。
     * 5. 根据怪物类型，写入隐形 Tags (如 undead, zombie, animal) 到 PDC。
     * 6. 调用 HologramManager 挂载头顶全息面板。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity instanceof Enemy)) return;

        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) return;

        PersistentDataContainer container = entity.getPersistentDataContainer();
        if (container.has(pdc.KEY_MOB_POWER_LEVEL, PersistentDataType.INTEGER)) return;

        applyScaling(entity, event.getSpawnReason(), null, false);
    }

    public void applyCustomMobScaling(LivingEntity entity, String registryMobId) {
        applyScaling(entity, CreatureSpawnEvent.SpawnReason.CUSTOM, registryMobId, true);
    }

    private void applyScaling(LivingEntity entity, CreatureSpawnEvent.SpawnReason spawnReason, String forcedMobId, boolean force) {
        if (!force && !(entity instanceof Enemy)) return;

        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) return;

        PersistentDataContainer container = entity.getPersistentDataContainer();
        if (!force && container.has(pdc.KEY_MOB_POWER_LEVEL, PersistentDataType.INTEGER)) return;

        String registryMobId = forcedMobId == null || forcedMobId.isBlank()
                ? customMobRegistry == null ? null : customMobRegistry.identifyMob(entity)
                : forcedMobId;
        int worldCap = getWorldCap(entity.getWorld());
        double nearbyPowerLevel = Math.max(1.0, getNearbyMedianPower(entity));
        boolean bypassWorldCap = customMobRegistry != null
                && registryMobId != null
                && customMobRegistry.bypassesWorldLevelCap(registryMobId);
        double rawPowerLevel = resolveRawPowerLevel(spawnReason, worldCap, nearbyPowerLevel, bypassWorldCap);
        int powerLevel = resolvePowerLevel(rawPowerLevel, registryMobId);
        MobScalingResult result = applyAttributes(entity, powerLevel, registryMobId);
        applyRegistryDisplayName(entity, registryMobId);
        if (registryMobId != null) {
            container.set(pdc.KEY_CUSTOM_MOB_ID, PersistentDataType.STRING, registryMobId);
        }
        container.set(pdc.KEY_MOB_POWER_LEVEL, PersistentDataType.INTEGER, powerLevel);
        CreatureTagService tagService = CreatureTagService.getInstance();
        container.set(pdc.KEY_MOB_TAGS, PersistentDataType.STRING, tagService == null
                ? classifyTags(entity.getType())
                : tagService.getProfile(entity).toStorageString());
        container.set(pdc.KEY_MOB_DAMAGE_REDUCTION, PersistentDataType.DOUBLE, result.damageReduction());
        container.set(pdc.KEY_MOB_ATTACK_DAMAGE, PersistentDataType.DOUBLE, result.attackDamage());
        container.set(pdc.KEY_MOB_MAGIC_RESIST, PersistentDataType.DOUBLE, result.magicResist());
        container.set(pdc.KEY_MOB_SCALING_MOD, PersistentDataType.DOUBLE, result.modifier());

        retargetAlwaysHostileMob(entity, registryMobId);
        hologramManager.attachHologram(entity, powerLevel);
    }

    private void tickAlwaysHostileMobs() {
        if (customMobRegistry == null) {
            return;
        }

        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return;
        }

        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                String registryMobId = entity.getPersistentDataContainer().get(pdc.KEY_CUSTOM_MOB_ID, PersistentDataType.STRING);
                retargetAlwaysHostileMob(entity, registryMobId);
            }
        }
    }

    private void retargetAlwaysHostileMob(LivingEntity entity, String registryMobId) {
        if (!(entity instanceof Mob mob) || registryMobId == null || customMobRegistry == null) {
            return;
        }
        if (!customMobRegistry.isAlwaysHostile(registryMobId) || entity.isDead() || !entity.isValid()) {
            return;
        }

        Player target = findNearestPlayer(entity, 48.0);
        if (target != null && mob.getTarget() != target) {
            mob.setTarget(target);
        }
    }

    private Player findNearestPlayer(LivingEntity entity, double radius) {
        Player nearest = null;
        double bestDistance = radius * radius;
        for (Player player : entity.getWorld().getPlayers()) {
            if (!player.isValid() || player.isDead() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                continue;
            }
            double distance = player.getLocation().distanceSquared(entity.getLocation());
            if (distance <= bestDistance) {
                bestDistance = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    private int resolvePowerLevel(double rawPowerLevel, String registryMobId) {
        if (customMobRegistry != null && registryMobId != null) {
            int overridePowerLevel = customMobRegistry.getOverridePowerLevel(registryMobId);
            if (overridePowerLevel > 0) {
                return overridePowerLevel;
            }
        }

        return Math.max(1, (int) Math.round(rawPowerLevel));
    }

    private double resolveRawPowerLevel(CreatureSpawnEvent.SpawnReason spawnReason, int worldCap,
                                        double nearbyPowerLevel, boolean bypassWorldCap) {
        if (spawnReason == CreatureSpawnEvent.SpawnReason.SPAWNER) {
            return bypassWorldCap ? Math.max(worldCap, nearbyPowerLevel) : worldCap;
        }

        return bypassWorldCap ? nearbyPowerLevel : Math.min(worldCap, nearbyPowerLevel);
    }

    private void applyRegistryDisplayName(LivingEntity entity, String registryMobId) {
        if (customMobRegistry == null || registryMobId == null) {
            return;
        }

        String displayName = customMobRegistry.getDisplayName(registryMobId);
        if (displayName == null || displayName.isBlank()) {
            return;
        }

        entity.customName(Component.text(displayName));
        entity.setCustomNameVisible(false);
    }

    /**
     * 在 EntityDeathEvent 中，根据怪物的战斗等级分配金币和经验掉落。
     */
    @EventHandler
    public void onCreatureDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) return;

        Integer powerLevel = entity.getPersistentDataContainer().get(pdc.KEY_MOB_POWER_LEVEL, PersistentDataType.INTEGER);
        if (powerLevel == null || powerLevel <= 0) return;

        applyRegistryDrops(event, entity, pdc);
        event.setDroppedExp(event.getDroppedExp() + Math.max(1, powerLevel / 2));

        Player killer = entity.getKiller();
        if (killer != null && economyManager != null) {
            long reward = Math.max(1L, powerLevel * 5L);
            economyManager.addBalance(killer.getUniqueId(), reward);
            killer.sendActionBar(ServerCorePlugin.getMiniMessage().deserialize("<gold>+" + reward + " coins</gold> <dark_gray>|</dark_gray> <gray>Lv." + powerLevel + " 生态击杀</gray>"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVirtualMobDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity)) {
            return;
        }

        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return;
        }

        PersistentDataContainer container = entity.getPersistentDataContainer();
        Double virtualMaxHealth = container.get(pdc.KEY_MOB_VIRTUAL_MAX_HEALTH, PersistentDataType.DOUBLE);
        Double virtualHealth = container.get(pdc.KEY_MOB_VIRTUAL_HEALTH, PersistentDataType.DOUBLE);
        if (virtualMaxHealth == null || virtualMaxHealth <= 0.0 || virtualHealth == null || virtualHealth <= 0.0) {
            return;
        }

        double damage = Math.max(0.0, event.getFinalDamage());
        if (damage <= 0.0) {
            return;
        }

        double nextVirtualHealth = Math.max(0.0, virtualHealth - damage);
        container.set(pdc.KEY_MOB_VIRTUAL_HEALTH, PersistentDataType.DOUBLE, nextVirtualHealth);

        if (nextVirtualHealth <= 0.0) {
            event.setDamage(entity.getHealth() + 1_000_000.0);
            return;
        }

        double physicalHealth = calculatePhysicalHealth(entity, nextVirtualHealth, virtualMaxHealth);
        double physicalDamage = Math.max(0.0, entity.getHealth() - physicalHealth);
        event.setDamage(physicalDamage);
    }

    private void applyRegistryDrops(EntityDeathEvent event, LivingEntity entity, PDCManager pdc) {
        if (customMobRegistry == null) {
            return;
        }

        String registryMobId = entity.getPersistentDataContainer().get(pdc.KEY_CUSTOM_MOB_ID, PersistentDataType.STRING);
        if (registryMobId == null || registryMobId.isBlank()) {
            return;
        }

        if (customMobRegistry.shouldClearVanillaDrops(registryMobId)) {
            event.getDrops().clear();
        }

        List<ItemStack> drops = customMobRegistry.rollDrops(registryMobId);
        if (!drops.isEmpty()) {
            event.getDrops().addAll(drops);
        }
    }

    private double getNearbyMedianPower(LivingEntity entity) {
        List<Double> powers = new ArrayList<>();
        for (Player player : entity.getWorld().getPlayers()) {
            if (!player.isValid() || player.isDead()) continue;
            if (player.getLocation().distanceSquared(entity.getLocation()) > 64.0 * 64.0) continue;
            powers.add(powerLevelManager.getCurrentPower(player));
        }

        if (powers.isEmpty()) return 1.0;

        Collections.sort(powers);
        int middle = powers.size() / 2;
        if (powers.size() % 2 == 1) {
            return powers.get(middle);
        }
        return (powers.get(middle - 1) + powers.get(middle)) / 2.0;
    }

    private int getWorldCap(World world) {
        return switch (world.getEnvironment()) {
            case NETHER -> 150;
            case THE_END -> 250;
            default -> 50;
        };
    }

    private MobScalingResult applyAttributes(LivingEntity entity, int powerLevel, String registryMobId) {
        double modifier = resolveModifier(entity, registryMobId);
        VanillaMobStats baseStats = getBaseStats(entity, registryMobId);
        double roundedLevel = Math.round(powerLevel);

        double maxHealth = roundedLevel * baseStats.hp() * (1.1 / 3.0) * modifier + baseStats.hp();
        double def = powerLevel * modifier;
        double damageReduction = Math.min(0.50, def / (def + 500.0));
        double attackDamage = baseStats.atk() * (1.0 + roundedLevel / Math.max(1.0, baseStats.hp())) * modifier + baseStats.atk() * 0.7;
        double magicResist = modifier >= ELITE_MOD ? Math.min(0.30, (powerLevel * modifier) / 2000.0) : 0.0;
        double fixedMaxHealth = customMobRegistry == null || registryMobId == null ? -1.0 : customMobRegistry.getFixedMaxHealth(registryMobId);
        if (fixedMaxHealth > 0.0) {
            maxHealth = fixedMaxHealth;
        }

        double physicalMaxHealth = setMaxHealthAttribute(entity, maxHealth);
        syncVirtualHealth(entity, maxHealth, physicalMaxHealth);
        setAttribute(entity, Attribute.GENERIC_ATTACK_DAMAGE, attackDamage);
        setAttribute(entity, Attribute.GENERIC_ARMOR, 0.0);
        setAttribute(entity, Attribute.GENERIC_ARMOR_TOUGHNESS, 0.0);

        entity.customName(null);
        entity.setCustomNameVisible(false);

        return new MobScalingResult(maxHealth, attackDamage, damageReduction, magicResist, modifier);
    }

    private double setMaxHealthAttribute(LivingEntity entity, double desiredMaxHealth) {
        AttributeInstance instance = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (instance == null) {
            return Math.max(1.0, desiredMaxHealth);
        }

        try {
            instance.setBaseValue(Math.max(1.0, desiredMaxHealth));
        } catch (IllegalArgumentException exception) {
            for (double fallback : List.of(2048.0, 2000.0, 1024.0, 512.0, 256.0, 100.0, 20.0)) {
                try {
                    instance.setBaseValue(Math.min(Math.max(1.0, desiredMaxHealth), fallback));
                    break;
                } catch (IllegalArgumentException ignored) {
                    // Try the next lower vanilla-safe value.
                }
            }
        }
        return Math.max(1.0, instance.getValue());
    }

    private void syncVirtualHealth(LivingEntity entity, double desiredMaxHealth, double physicalMaxHealth) {
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            entity.setHealth(Math.min(desiredMaxHealth, physicalMaxHealth));
            return;
        }

        PersistentDataContainer container = entity.getPersistentDataContainer();
        if (desiredMaxHealth > physicalMaxHealth + 0.001) {
            container.set(pdc.KEY_MOB_VIRTUAL_MAX_HEALTH, PersistentDataType.DOUBLE, desiredMaxHealth);
            container.set(pdc.KEY_MOB_VIRTUAL_HEALTH, PersistentDataType.DOUBLE, desiredMaxHealth);
            entity.setHealth(physicalMaxHealth);
            return;
        }

        container.remove(pdc.KEY_MOB_VIRTUAL_MAX_HEALTH);
        container.remove(pdc.KEY_MOB_VIRTUAL_HEALTH);
        entity.setHealth(Math.min(desiredMaxHealth, physicalMaxHealth));
    }

    private double calculatePhysicalHealth(LivingEntity entity, double virtualHealth, double virtualMaxHealth) {
        AttributeInstance maxHealth = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double physicalMaxHealth = maxHealth == null ? Math.max(1.0, entity.getHealth()) : Math.max(1.0, maxHealth.getValue());
        double ratio = Math.max(0.0, Math.min(1.0, virtualHealth / Math.max(1.0, virtualMaxHealth)));
        return Math.max(1.0, Math.min(physicalMaxHealth, physicalMaxHealth * ratio));
    }

    private void setAttribute(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private VanillaMobStats getBaseStats(LivingEntity entity, String registryMobId) {
        VanillaMobStats baseStats = getVanillaBaseStats(entity);

        if (customMobRegistry != null && registryMobId != null) {
            double overrideHp = customMobRegistry.getOverrideBaseHp(registryMobId);
            double overrideAtk = customMobRegistry.getOverrideBaseAtk(registryMobId);
            if (overrideHp > 0.0 || overrideAtk >= 0.0) {
                return new VanillaMobStats(
                        overrideHp > 0.0 ? overrideHp : baseStats.hp(),
                        overrideAtk >= 0.0 ? overrideAtk : baseStats.atk()
                );
            }
        }

        return baseStats;
    }

    private VanillaMobStats getVanillaBaseStats(LivingEntity entity) {
        VanillaMobStats configured = baseVanillaStats.get(entity.getType());
        if (configured != null) {
            return configured;
        }

        AttributeInstance health = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        AttributeInstance attack = entity.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE);
        return new VanillaMobStats(
                health == null ? 20.0 : Math.max(1.0, health.getBaseValue()),
                attack == null ? 2.0 : Math.max(0.0, attack.getBaseValue())
        );
    }

    private double resolveModifier(LivingEntity entity, String registryMobId) {
        if (customMobRegistry != null && registryMobId != null) {
            return customMobRegistry.getMobMod(registryMobId);
        }

        String customMobId = resolveCustomMobId(entity);
        if (customMobId != null) {
            Double customMod = customMobModifiers.get(normalizeCustomMobId(customMobId));
            if (customMod != null) {
                return customMod;
            }
        }

        if (entity.getScoreboardTags().contains("servercore_boss")) {
            return BOSS_MOD;
        }
        if (entity.getScoreboardTags().contains("servercore_miniboss")) {
            return MINI_BOSS_MOD;
        }
        if (entity.getScoreboardTags().contains("servercore_elite")) {
            return ELITE_MOD;
        }

        return NORMAL_MOD;
    }

    private String resolveCustomMobId(LivingEntity entity) {
        PDCManager pdc = PDCManager.getInstance();
        if (pdc != null) {
            String customMobId = entity.getPersistentDataContainer().get(pdc.KEY_CUSTOM_MOB_ID, PersistentDataType.STRING);
            if (customMobId != null && !customMobId.isBlank()) {
                return customMobId;
            }
        }

        for (String tag : entity.getScoreboardTags()) {
            if (tag.startsWith("servercore_mob:")) {
                return tag.substring("servercore_mob:".length());
            }
            if (tag.startsWith("mythicmob:")) {
                return tag.substring("mythicmob:".length());
            }
        }

        return null;
    }

    private String normalizeCustomMobId(String id) {
        return id.toLowerCase(Locale.ROOT).trim();
    }

    private void initDefaultVanillaStats() {
        setBaseVanillaStats(EntityType.ZOMBIE, 20.0, 4.5);
        setBaseVanillaStats(EntityType.ZOMBIE_VILLAGER, 20.0, 4.5);
        setBaseVanillaStats(EntityType.HUSK, 20.0, 4.5);
        setBaseVanillaStats(EntityType.DROWNED, 20.0, 4.5);
        setBaseVanillaStats(EntityType.SKELETON, 20.0, 4.0);
        setBaseVanillaStats(EntityType.STRAY, 20.0, 4.0);
        setBaseVanillaStats(EntityType.BOGGED, 16.0, 3.0);
        setBaseVanillaStats(EntityType.WITHER_SKELETON, 20.0, 5.0);
        setBaseVanillaStats(EntityType.SPIDER, 16.0, 3.0);
        setBaseVanillaStats(EntityType.CAVE_SPIDER, 12.0, 2.0);
        setBaseVanillaStats(EntityType.CREEPER, 20.0, 0.0);
        setBaseVanillaStats(EntityType.SLIME, 16.0, 4.0);
        setBaseVanillaStats(EntityType.MAGMA_CUBE, 16.0, 6.0);
        setBaseVanillaStats(EntityType.ENDERMAN, 40.0, 10.0);
        setBaseVanillaStats(EntityType.BLAZE, 20.0, 6.0);
        setBaseVanillaStats(EntityType.GHAST, 10.0, 6.0);
        setBaseVanillaStats(EntityType.ZOMBIFIED_PIGLIN, 20.0, 7.0);
        setBaseVanillaStats(EntityType.WITCH, 26.0, 6.0);
        setBaseVanillaStats(EntityType.PHANTOM, 20.0, 6.0);
        setBaseVanillaStats(EntityType.RAVAGER, 100.0, 18.0);
        setBaseVanillaStats(EntityType.IRON_GOLEM, 100.0, 21.0);
        setBaseVanillaStats(EntityType.ELDER_GUARDIAN, 80.0, 8.0);
        setBaseVanillaStats(EntityType.WARDEN, 500.0, 45.0);
    }

    private String classifyTags(EntityType type) {
        return switch (type) {
            case ZOMBIE, ZOMBIE_VILLAGER, HUSK, DROWNED -> "undead,zombie,slayer_zombie";
            case SKELETON, STRAY, BOGGED, WITHER_SKELETON -> "undead,skeleton,slayer_skeleton";
            case WOLF -> "beast,wolf,slayer_wolf";
            case ENDERMAN, ENDERMITE, SHULKER -> "end,shadow,slayer_shadow";
            case BLAZE, MAGMA_CUBE, GHAST, ZOMBIFIED_PIGLIN -> "nether,lava,slayer_lava";
            case RAVAGER, WARDEN, IRON_GOLEM, ELDER_GUARDIAN -> "beast,giant,slayer_giant";
            case SPIDER, CAVE_SPIDER -> "arthropod,beast";
            case CREEPER -> "creeper,beast";
            case SLIME -> "slime,beast";
            case WITCH -> "humanoid,magic";
            case PHANTOM -> "undead,phantom";
            default -> type.name().toLowerCase(java.util.Locale.ROOT);
        };
    }

    private record VanillaMobStats(double hp, double atk) {
    }

    private record MobScalingResult(
            double maxHealth,
            double attackDamage,
            double damageReduction,
            double magicResist,
            double modifier
    ) {
    }
}
