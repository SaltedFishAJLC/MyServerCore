package com.servercore.manager;

import com.servercore.enchant.EnchantBookFactory;
import com.servercore.enchant.EnchantDefinition;
import com.servercore.enchant.EnchantRegistry;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * YAML-driven custom mob registry.
 *
 * <p>Rules are evaluated in file order. A rule matches only when every
 * configured matcher passes, so content packs can combine loose vanilla signals
 * such as type/name/equipment with exact MythicMobs IDs when available.</p>
 */
public class CustomMobRegistry {

    private static final double DEFAULT_MOD = 1.0;
    private static final double MIN_MOD = 0.1;
    private static final int DEFAULT_SPAWNER_LIMIT = 16;
    private static final double DEFAULT_SPAWNER_HORIZONTAL_RADIUS = 32.0;
    private static final double DEFAULT_SPAWNER_VERTICAL_RADIUS = 16.0;
    private static final List<String> MYTHIC_PDC_KEYS = List.of(
            "mythicmobs:mobtype",
            "mythicmobs:mob_type",
            "mythicmobs:internal_name",
            "mythicmobs:type"
    );

    private final Plugin plugin;
    private final File configFile;
    private final Map<String, Rule> rules = new LinkedHashMap<>();

    public CustomMobRegistry(Plugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "custom_mobs.yml");
    }

    /**
     * Loads or reloads custom_mobs.yml.
     */
    public void loadConfig() {
        ensureConfigFile();

        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(configFile);
        } catch (IOException | InvalidConfigurationException exception) {
            rules.clear();
            plugin.getLogger().severe("Could not load custom_mobs.yml: " + exception.getMessage());
            plugin.getLogger().severe("Custom mob rules are disabled until the YAML syntax is fixed.");
            return;
        }
        ConfigurationSection root = config.getConfigurationSection("mobs");
        if (root == null) {
            root = config;
        }

        rules.clear();
        for (String id : root.getKeys(false)) {
            if (!root.isConfigurationSection(id)) {
                continue;
            }

            ConfigurationSection section = root.getConfigurationSection(id);
            Rule rule = parseRule(id, section);
            if (rule.hasAnyMatcher()) {
                rules.put(normalize(id), rule);
            } else {
                plugin.getLogger().warning("Skipped custom mob rule '" + id + "' because it has no matchers.");
            }
        }

        plugin.getLogger().info("Loaded " + rules.size() + " custom mob rule(s).");
    }

    /**
     * Identifies an entity using configured YAML matchers.
     *
     * @param entity living entity to inspect
     * @return configured mob ID, or null when no rule matches
     */
    public String identifyMob(LivingEntity entity) {
        if (entity == null || rules.isEmpty()) {
            return null;
        }

        for (Rule rule : rules.values()) {
            if (rule.matches(entity)) {
                return rule.id();
            }
        }

        return null;
    }

    /**
     * Returns the scaling modifier for a custom mob ID.
     */
    public double getMobMod(String mobId) {
        Rule rule = getRule(mobId);
        return rule == null ? DEFAULT_MOD : rule.mod();
    }

    /**
     * @return overridden base HP, or -1 when the rule does not override it
     */
    public double getOverrideBaseHp(String mobId) {
        Rule rule = getRule(mobId);
        return rule == null || rule.overrideBaseHp() == null ? -1.0 : rule.overrideBaseHp();
    }

    /**
     * @return fixed final max health, or -1 when the rule should use scaling
     */
    public double getFixedMaxHealth(String mobId) {
        Rule rule = getRule(mobId);
        return rule == null || rule.fixedMaxHealth() == null ? -1.0 : rule.fixedMaxHealth();
    }

    /**
     * @return overridden base attack, or -1 when the rule does not override it
     */
    public double getOverrideBaseAtk(String mobId) {
        Rule rule = getRule(mobId);
        return rule == null || rule.overrideBaseAtk() == null ? -1.0 : rule.overrideBaseAtk();
    }

    /**
     * @return fixed combat level, or -1 when the rule should use dynamic scaling
     */
    public int getOverridePowerLevel(String mobId) {
        Rule rule = getRule(mobId);
        return rule == null || rule.overridePowerLevel() == null ? -1 : rule.overridePowerLevel();
    }

    public MobLevelMode getLevelMode(String mobId) {
        Rule rule = getRule(mobId);
        return rule == null ? MobLevelMode.NATURAL_ADAPTIVE : rule.levelMode();
    }

    public double getBaseLevel(String mobId) {
        Rule rule = getRule(mobId);
        if (rule == null) {
            return 1.0;
        }
        if (rule.baseLevel() != null) {
            return rule.baseLevel();
        }
        return rule.overridePowerLevel() == null ? 1.0 : rule.overridePowerLevel();
    }

    public double getMinLevel(String mobId) {
        Rule rule = getRule(mobId);
        return rule == null || rule.minLevel() == null ? 1.0 : rule.minLevel();
    }

    public double getMaxLevel(String mobId) {
        Rule rule = getRule(mobId);
        return rule == null || rule.maxLevel() == null ? Double.MAX_VALUE : rule.maxLevel();
    }

    public double getPlayerScale(String mobId) {
        Rule rule = getRule(mobId);
        return rule == null || rule.playerScale() == null ? 0.0 : rule.playerScale();
    }

    /**
     * @return true when this rule should use raw nearby-player power without the world cap
     */
    public boolean bypassesWorldLevelCap(String mobId) {
        Rule rule = getRule(mobId);
        return rule != null && rule.bypassWorldLevelCap();
    }

    /**
     * @return display name used by the ServerCore hologram, or null for default type name
     */
    public String getDisplayName(String mobId) {
        Rule rule = getRule(mobId);
        return rule == null ? null : rule.displayName();
    }

    public boolean isAlwaysHostile(String mobId) {
        Rule rule = getRule(mobId);
        return rule != null && rule.alwaysHostile();
    }

    public boolean shouldClearVanillaDrops(String mobId) {
        Rule rule = getRule(mobId);
        return rule != null && rule.clearVanillaDrops();
    }

    public SpawnerThrottle getSpawnerThrottle(String mobId) {
        Rule rule = getRule(mobId);
        return rule == null ? SpawnerThrottle.DEFAULT : rule.spawnerThrottle();
    }

    public List<ItemStack> rollDrops(String mobId) {
        return rollDrops(mobId, null);
    }

    public List<ItemStack> rollDrops(String mobId, org.bukkit.entity.Player killer) {
        Rule rule = getRule(mobId);
        if (rule == null || rule.drops().isEmpty()) {
            return List.of();
        }

        List<ItemStack> result = new ArrayList<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (DropRule drop : rule.drops()) {
            ItemStack item = drop.roll(random, killer);
            if (item != null && !item.getType().isAir()) {
                result.add(item);
            }
        }
        return result;
    }

    public int getRuleCount() {
        return rules.size();
    }

    public boolean hasRule(String mobId) {
        return getRule(mobId) != null;
    }

    public List<String> getRuleIds() {
        return rules.values().stream().map(Rule::id).toList();
    }

    public LivingEntity spawnConfiguredMob(String mobId, Location location) {
        Rule rule = getRule(mobId);
        if (rule == null || location == null || location.getWorld() == null) {
            return null;
        }

        LivingEntity entity = null;
        if (rule.matchMythicId() != null) {
            entity = spawnMythicMob(rule.matchMythicId(), location);
        }

        if (entity == null) {
            EntityType type = rule.matchType() == null ? EntityType.ZOMBIE : rule.matchType();
            if (!type.isAlive()) {
                return null;
            }
            entity = (LivingEntity) location.getWorld().spawnEntity(location, type);
        }

        if (rule.displayName() != null) {
            entity.customName(net.kyori.adventure.text.Component.text(rule.displayName()));
            entity.setCustomNameVisible(false);
        } else if (rule.matchName() != null) {
            entity.customName(net.kyori.adventure.text.Component.text(rule.matchName()));
            entity.setCustomNameVisible(false);
        }

        for (String tag : rule.matchScoreboardTags()) {
            entity.addScoreboardTag(tag);
        }
        applyMatchEquipment(entity, rule.matchEquipment());

        PDCManager pdc = PDCManager.getInstance();
        if (pdc != null) {
            entity.getPersistentDataContainer().set(pdc.KEY_CUSTOM_MOB_ID, PersistentDataType.STRING, rule.id());
        }
        return entity;
    }

    private Rule getRule(String mobId) {
        if (mobId == null || mobId.isBlank()) {
            return null;
        }
        return rules.get(normalize(mobId));
    }

    private LivingEntity spawnMythicMob(String mythicMobId, Location location) {
        try {
            Class<?> mythicBukkitClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object mythicBukkit = mythicBukkitClass.getMethod("inst").invoke(null);
            Object mobManager = mythicBukkit.getClass().getMethod("getMobManager").invoke(mythicBukkit);
            Object activeMob = mobManager.getClass().getMethod("spawnMob", String.class, Location.class).invoke(mobManager, mythicMobId, location);
            if (activeMob == null) {
                return null;
            }

            Object abstractEntity = activeMob.getClass().getMethod("getEntity").invoke(activeMob);
            Method getBukkitEntity = abstractEntity.getClass().getMethod("getBukkitEntity");
            Object bukkitEntity = getBukkitEntity.invoke(abstractEntity);
            return bukkitEntity instanceof LivingEntity livingEntity ? livingEntity : null;
        } catch (ReflectiveOperationException | LinkageError exception) {
            plugin.getLogger().warning("Could not spawn MythicMob '" + mythicMobId + "': " + exception.getMessage());
            return null;
        }
    }

    private void applyMatchEquipment(LivingEntity entity, Map<EquipmentSlotKey, Material> equipment) {
        if (equipment.isEmpty() || entity.getEquipment() == null) {
            return;
        }
        for (Map.Entry<EquipmentSlotKey, Material> entry : equipment.entrySet()) {
            entry.getKey().write(entity.getEquipment(), new ItemStack(entry.getValue()));
        }
    }

    private Rule parseRule(String id, ConfigurationSection section) {
        EntityType matchType = parseEntityType(section.getString("match_type"));
        String matchName = blankToNull(firstPresentString(section, "match_name", "match_name_contains"));
        Pattern matchNameRegex = compilePattern(section.getString("match_name_regex"), id);
        String matchMythicId = blankToNull(section.getString("match_mythic_id"));
        String matchPdcId = blankToNull(section.getString("match_pdc_id"));
        List<String> scoreboardTags = stringList(section, "match_scoreboard_tags", "match_scoreboard_tag");
        Map<EquipmentSlotKey, Material> equipment = parseEquipment(section.getConfigurationSection("match_equipment"));

        Double configuredMod = positiveDouble(section, "mod");
        double mod = configuredMod == null ? DEFAULT_MOD : Math.max(MIN_MOD, configuredMod);
        Double overrideBaseHp = positiveDouble(section, "override_base_hp");
        Double fixedMaxHealth = positiveDouble(section, "fixed_max_health", "fixed_health", "max_health");
        Double overrideBaseAtk = nonNegativeDouble(section, "override_base_atk");
        Integer overridePowerLevel = positiveInt(section, "override_power_level", "power_level", "level");
        MobLevelMode levelMode = parseLevelMode(section.getString("level_mode"), overridePowerLevel, id);
        Double baseLevel = positiveDouble(section, "base_level", "area_level", "content_level");
        Double minLevel = positiveDouble(section, "min_level");
        Double maxLevel = positiveDouble(section, "max_level");
        Double playerScale = nonNegativeDouble(section, "player_scale");
        boolean bypassWorldLevelCap = firstPresentBoolean(section, false,
                "bypass_world_level_cap",
                "ignore_world_level_cap",
                "bypass_world_cap",
                "ignore_world_cap");
        String displayName = blankToNull(firstPresentString(section, "display_name", "name"));
        boolean alwaysHostile = firstPresentBoolean(section, false,
                "always_hostile",
                "hostile_to_players",
                "aggressive_to_players",
                "target_players");
        boolean clearVanillaDrops = firstPresentBoolean(section, false,
                "clear_vanilla_drops",
                "clear_drops",
                "replace_drops");
        SpawnerThrottle spawnerThrottle = parseSpawnerThrottle(id, section);
        List<DropRule> drops = parseDrops(id, section);

        return new Rule(
                id,
                matchType,
                matchName,
                matchNameRegex,
                matchMythicId,
                matchPdcId,
                scoreboardTags,
                equipment,
                mod,
                overrideBaseHp,
                fixedMaxHealth,
                overrideBaseAtk,
                overridePowerLevel,
                levelMode,
                baseLevel,
                minLevel,
                maxLevel,
                playerScale,
                bypassWorldLevelCap,
                displayName,
                alwaysHostile,
                clearVanillaDrops,
                spawnerThrottle,
                drops
        );
    }

    private void ensureConfigFile() {
        if (configFile.exists()) {
            return;
        }

        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder for custom_mobs.yml.");
            return;
        }

        if (plugin instanceof JavaPlugin javaPlugin && javaPlugin.getResource("custom_mobs.yml") != null) {
            javaPlugin.saveResource("custom_mobs.yml", false);
            return;
        }

        YamlConfiguration config = new YamlConfiguration();
        config.set("WDA_Dungeon_Guard.match_type", "ZOMBIE");
        config.set("WDA_Dungeon_Guard.match_name", "Dungeon Guard");
        config.set("WDA_Dungeon_Guard.display_name", "Abandoned Temple Guard");
        config.set("WDA_Dungeon_Guard.level_mode", "FIXED");
        config.set("WDA_Dungeon_Guard.override_power_level", 25);
        config.set("WDA_Dungeon_Guard.bypass_world_level_cap", true);
        config.set("WDA_Dungeon_Guard.mod", 1.8);
        config.set("WDA_Dungeon_Guard.override_base_hp", 40.0);
        config.set("WDA_Dungeon_Guard.drops", List.of(Map.of("material", "GOLD_NUGGET", "chance", 0.45, "amount", "2-5")));
        config.set("Mythic_Flame_Boss.match_mythic_id", "FlameDemon");
        config.set("Mythic_Flame_Boss.display_name", "Flame Demon");
        config.set("Mythic_Flame_Boss.level_mode", "FIXED");
        config.set("Mythic_Flame_Boss.level", 150);
        config.set("Mythic_Flame_Boss.mod", 6.4);

        try {
            config.save(configFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not create custom_mobs.yml: " + exception.getMessage());
        }
    }

    private EntityType parseEntityType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Unknown entity type in custom_mobs.yml: " + raw);
            return null;
        }
    }

    private Pattern compilePattern(String raw, String id) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return Pattern.compile(raw, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException exception) {
            plugin.getLogger().warning("Invalid match_name_regex for custom mob rule '" + id + "': " + raw);
            return null;
        }
    }

    private Map<EquipmentSlotKey, Material> parseEquipment(ConfigurationSection section) {
        if (section == null) {
            return Collections.emptyMap();
        }

        Map<EquipmentSlotKey, Material> equipment = new EnumMap<>(EquipmentSlotKey.class);
        for (EquipmentSlotKey slot : EquipmentSlotKey.values()) {
            String raw = section.getString(slot.configKey());
            if (raw == null || raw.isBlank()) {
                continue;
            }

            Material material = Material.matchMaterial(raw.trim());
            if (material == null) {
                plugin.getLogger().warning("Unknown material in custom_mobs.yml match_equipment." + slot.configKey() + ": " + raw);
                continue;
            }
            equipment.put(slot, material);
        }
        return equipment;
    }

    private List<DropRule> parseDrops(String mobId, ConfigurationSection section) {
        if (!section.isList("drops")) {
            return List.of();
        }

        List<DropRule> drops = new ArrayList<>();
        int index = 0;
        for (Map<?, ?> rawDrop : section.getMapList("drops")) {
            index++;
            DropRule drop = parseDrop(mobId, index, rawDrop);
            if (drop != null) {
                drops.add(drop);
            }
        }
        return drops;
    }

    private SpawnerThrottle parseSpawnerThrottle(String id, ConfigurationSection section) {
        boolean enabled = firstPresentBoolean(section, true,
                "spawner_limit_enabled",
                "spawner_throttle_enabled",
                "limit_spawner_mobs");
        Integer limit = nonNegativeInt(section,
                "spawner_limit",
                "spawner_max_nearby",
                "max_nearby_spawner_mobs");
        Double horizontalRadius = positiveDouble(section,
                "spawner_check_radius",
                "spawner_horizontal_radius",
                "spawner_radius");
        Double verticalRadius = positiveDouble(section,
                "spawner_vertical_radius",
                "spawner_y_radius");
        SpawnerLimitMode limitMode = parseSpawnerLimitMode(section.getString("spawner_limit_mode"), id);

        return new SpawnerThrottle(
                enabled,
                limit == null ? DEFAULT_SPAWNER_LIMIT : limit,
                horizontalRadius == null ? DEFAULT_SPAWNER_HORIZONTAL_RADIUS : horizontalRadius,
                verticalRadius == null ? DEFAULT_SPAWNER_VERTICAL_RADIUS : verticalRadius,
                limitMode
        );
    }

    private SpawnerLimitMode parseSpawnerLimitMode(String raw, String id) {
        if (raw == null || raw.isBlank()) {
            return SpawnerLimitMode.RULE;
        }

        String normalized = normalize(raw);
        return switch (normalized) {
            case "rule", "mob", "custom_mob", "custom" -> SpawnerLimitMode.RULE;
            case "type", "entity_type", "entity" -> SpawnerLimitMode.TYPE;
            default -> {
                plugin.getLogger().warning("Unknown spawner_limit_mode for custom mob rule '" + id + "': " + raw);
                yield SpawnerLimitMode.RULE;
            }
        };
    }

    private MobLevelMode parseLevelMode(String raw, Integer overridePowerLevel, String id) {
        if (raw == null || raw.isBlank()) {
            return overridePowerLevel == null ? MobLevelMode.NATURAL_ADAPTIVE : MobLevelMode.FIXED;
        }

        String normalized = normalize(raw).replace('-', '_');
        return switch (normalized) {
            case "natural", "natural_adaptive", "adaptive", "dynamic" -> MobLevelMode.NATURAL_ADAPTIVE;
            case "fixed", "static", "content", "content_level" -> MobLevelMode.FIXED;
            case "world", "world_cap", "world_tier" -> MobLevelMode.WORLD_CAP;
            case "area", "area_tier", "structure", "structure_tier" -> MobLevelMode.AREA;
            case "adaptive_clamped", "clamped", "clamped_adaptive" -> MobLevelMode.ADAPTIVE_CLAMPED;
            default -> {
                plugin.getLogger().warning("Unknown level_mode for custom mob rule '" + id + "': " + raw);
                yield overridePowerLevel == null ? MobLevelMode.NATURAL_ADAPTIVE : MobLevelMode.FIXED;
            }
        };
    }

    private DropRule parseDrop(String mobId, int index, Map<?, ?> rawDrop) {
        String itemId = blankToNull(stringValue(rawDrop.get("item_id")));
        String materialName = blankToNull(stringValue(rawDrop.get("material")));
        String enchantBookId = blankToNull(stringValue(rawDrop.get("enchant_book")));
        if (enchantBookId == null) {
            enchantBookId = blankToNull(stringValue(rawDrop.get("enchant_id")));
        }
        Material material = null;
        if (materialName != null) {
            material = Material.matchMaterial(materialName);
            if (material == null || material.isAir()) {
                plugin.getLogger().warning("Unknown material in custom_mobs.yml drop " + mobId + "[" + index + "]: " + materialName);
                return null;
            }
        }

        if (itemId == null && material == null && enchantBookId == null) {
            plugin.getLogger().warning("Skipped custom_mobs.yml drop " + mobId + "[" + index + "] because it has no item_id, material, or enchant_book.");
            return null;
        }

        double chance = parseChance(rawDrop.get("chance"), 1.0);
        AmountRange amount = parseAmount(rawDrop);
        AmountRange enchantLevel = parseLevelRange(rawDrop);
        return new DropRule(
                itemId == null ? null : normalize(itemId),
                material,
                enchantBookId == null ? null : normalize(enchantBookId),
                chance,
                amount.min(),
                amount.max(),
                enchantLevel.min(),
                enchantLevel.max()
        );
    }

    private double parseChance(Object raw, double defaultValue) {
        if (raw == null) {
            return defaultValue;
        }

        if (raw instanceof Number number) {
            return clampChance(number.doubleValue());
        }

        String value = String.valueOf(raw).trim();
        if (value.endsWith("%")) {
            try {
                return clampChance(Double.parseDouble(value.substring(0, value.length() - 1).trim()) / 100.0);
            } catch (NumberFormatException exception) {
                return defaultValue;
            }
        }

        try {
            return clampChance(Double.parseDouble(value));
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private double clampChance(double chance) {
        return Math.max(0.0, Math.min(1.0, chance));
    }

    private AmountRange parseAmount(Map<?, ?> rawDrop) {
        Integer min = intValue(rawDrop.get("min_amount"));
        Integer max = intValue(rawDrop.get("max_amount"));
        if (min == null) {
            min = intValue(rawDrop.get("amount_min"));
        }
        if (max == null) {
            max = intValue(rawDrop.get("amount_max"));
        }
        if (min != null || max != null) {
            int safeMin = Math.max(1, min == null ? max : min);
            int safeMax = Math.max(safeMin, max == null ? safeMin : max);
            return new AmountRange(safeMin, safeMax);
        }

        Object rawAmount = rawDrop.get("amount");
        if (rawAmount == null) {
            return new AmountRange(1, 1);
        }

        if (rawAmount instanceof Number number) {
            int amount = Math.max(1, number.intValue());
            return new AmountRange(amount, amount);
        }

        String amountText = String.valueOf(rawAmount).trim();
        String[] parts = amountText.split("-", 2);
        if (parts.length == 2) {
            try {
                int parsedMin = Math.max(1, Integer.parseInt(parts[0].trim()));
                int parsedMax = Math.max(parsedMin, Integer.parseInt(parts[1].trim()));
                return new AmountRange(parsedMin, parsedMax);
            } catch (NumberFormatException ignored) {
                return new AmountRange(1, 1);
            }
        }

        try {
            int amount = Math.max(1, Integer.parseInt(amountText));
            return new AmountRange(amount, amount);
        } catch (NumberFormatException ignored) {
            return new AmountRange(1, 1);
        }
    }

    private AmountRange parseLevelRange(Map<?, ?> rawDrop) {
        Object rawLevel = rawDrop.get("level");
        if (rawLevel == null) {
            rawLevel = rawDrop.get("enchant_level");
        }
        if (rawLevel == null) {
            return new AmountRange(1, 1);
        }

        if (rawLevel instanceof Number number) {
            int level = Math.max(1, number.intValue());
            return new AmountRange(level, level);
        }

        String levelText = String.valueOf(rawLevel).trim();
        String[] parts = levelText.split("-", 2);
        if (parts.length == 2) {
            try {
                int parsedMin = Math.max(1, Integer.parseInt(parts[0].trim()));
                int parsedMax = Math.max(parsedMin, Integer.parseInt(parts[1].trim()));
                return new AmountRange(parsedMin, parsedMax);
            } catch (NumberFormatException ignored) {
                return new AmountRange(1, 1);
            }
        }

        try {
            int level = Math.max(1, Integer.parseInt(levelText));
            return new AmountRange(level, level);
        } catch (NumberFormatException ignored) {
            return new AmountRange(1, 1);
        }
    }

    private Integer intValue(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String stringValue(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    private String firstPresentString(ConfigurationSection section, String... keys) {
        for (String key : keys) {
            String value = section.getString(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private List<String> stringList(ConfigurationSection section, String listKey, String singleKey) {
        if (section.isList(listKey)) {
            return section.getStringList(listKey).stream()
                    .filter(value -> value != null && !value.isBlank())
                    .toList();
        }

        List<String> values = new ArrayList<>();
        String single = section.getString(singleKey);
        if (single != null && !single.isBlank()) {
            values.add(single);
        }
        return values;
    }

    private Double positiveDouble(ConfigurationSection section, String... keys) {
        for (String key : keys) {
            Object raw = section.get(key);
            if (raw != null && !String.valueOf(raw).isBlank()) {
                double value = numberValue(raw, 0.0);
                return value <= 0.0 ? null : value;
            }
        }
        return null;
    }

    private Double nonNegativeDouble(ConfigurationSection section, String key) {
        Object raw = section.get(key);
        if (raw == null || String.valueOf(raw).isBlank()) {
            return null;
        }
        return Math.max(0.0, numberValue(raw, 0.0));
    }

    private Integer positiveInt(ConfigurationSection section, String... keys) {
        for (String key : keys) {
            Object raw = section.get(key);
            if (raw != null && !String.valueOf(raw).isBlank()) {
                Integer value = intValue(raw);
                return value == null || value <= 0 ? null : value;
            }
        }
        return null;
    }

    private Integer nonNegativeInt(ConfigurationSection section, String... keys) {
        for (String key : keys) {
            Object raw = section.get(key);
            if (raw != null && !String.valueOf(raw).isBlank()) {
                Integer value = intValue(raw);
                return value == null || value < 0 ? null : value;
            }
        }
        return null;
    }

    private double numberValue(Object raw, double defaultValue) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException exception) {
            return defaultValue;
        }
    }

    private boolean firstPresentBoolean(ConfigurationSection section, boolean defaultValue, String... keys) {
        for (String key : keys) {
            if (section.contains(key)) {
                return section.getBoolean(key);
            }
        }
        return defaultValue;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).trim();
    }

    private static boolean containsIgnoreCase(String text, String expectedNeedle) {
        return normalize(text).contains(normalize(expectedNeedle));
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && normalize(left).equals(normalize(right));
    }

    private static String getPlainEntityName(LivingEntity entity) {
        String legacyName = entity.getCustomName();
        if (legacyName != null && !legacyName.isBlank()) {
            return legacyName;
        }

        if (entity.customName() == null) {
            return null;
        }
        return PlainTextComponentSerializer.plainText().serialize(entity.customName());
    }

    private static String getServerCoreMobId(LivingEntity entity) {
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return null;
        }
        return entity.getPersistentDataContainer().get(pdc.KEY_CUSTOM_MOB_ID, PersistentDataType.STRING);
    }

    private static String resolveMythicMobId(LivingEntity entity) {
        String fromApi = resolveMythicMobIdFromApi(entity);
        if (fromApi != null && !fromApi.isBlank()) {
            return fromApi;
        }

        PersistentDataContainer container = entity.getPersistentDataContainer();
        for (String keyText : MYTHIC_PDC_KEYS) {
            NamespacedKey key = NamespacedKey.fromString(keyText);
            if (key == null) {
                continue;
            }
            String value = container.get(key, PersistentDataType.STRING);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }

        for (String tag : entity.getScoreboardTags()) {
            String lower = tag.toLowerCase(Locale.ROOT);
            if (lower.startsWith("mythicmob:")) {
                return tag.substring("mythicmob:".length());
            }
            if (lower.startsWith("mythicmobs:")) {
                return tag.substring("mythicmobs:".length());
            }
            if (lower.startsWith("mm:")) {
                return tag.substring("mm:".length());
            }
        }

        return null;
    }

    private static String resolveMythicMobIdFromApi(LivingEntity entity) {
        try {
            Class<?> mythicBukkitClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object mythicBukkit = mythicBukkitClass.getMethod("inst").invoke(null);
            Object mobManager = mythicBukkit.getClass().getMethod("getMobManager").invoke(mythicBukkit);
            Object activeMobResult = mobManager.getClass()
                    .getMethod("getActiveMob", java.util.UUID.class)
                    .invoke(mobManager, entity.getUniqueId());

            if (!(activeMobResult instanceof Optional<?> optional) || optional.isEmpty()) {
                return null;
            }

            Object activeMob = optional.get();
            Object mobType = invokeNoArg(activeMob, "getType");
            if (mobType == null) {
                mobType = invokeNoArg(activeMob, "getMobType");
            }
            if (mobType == null) {
                return null;
            }

            Object internalName = invokeNoArg(mobType, "getInternalName");
            if (internalName == null) {
                internalName = invokeNoArg(mobType, "getInternalNameString");
            }
            return internalName == null ? null : String.valueOf(internalName);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String methodName) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    public enum SpawnerLimitMode {
        RULE,
        TYPE
    }

    public enum MobLevelMode {
        NATURAL_ADAPTIVE,
        FIXED,
        WORLD_CAP,
        AREA,
        ADAPTIVE_CLAMPED
    }

    public record SpawnerThrottle(
            boolean enabled,
            int maxNearby,
            double horizontalRadius,
            double verticalRadius,
            SpawnerLimitMode limitMode
    ) {
        public static final SpawnerThrottle DEFAULT = new SpawnerThrottle(
                true,
                DEFAULT_SPAWNER_LIMIT,
                DEFAULT_SPAWNER_HORIZONTAL_RADIUS,
                DEFAULT_SPAWNER_VERTICAL_RADIUS,
                SpawnerLimitMode.RULE
        );
    }

    private record Rule(
            String id,
            EntityType matchType,
            String matchName,
            Pattern matchNameRegex,
            String matchMythicId,
            String matchPdcId,
            List<String> matchScoreboardTags,
            Map<EquipmentSlotKey, Material> matchEquipment,
            double mod,
            Double overrideBaseHp,
            Double fixedMaxHealth,
            Double overrideBaseAtk,
            Integer overridePowerLevel,
            MobLevelMode levelMode,
            Double baseLevel,
            Double minLevel,
            Double maxLevel,
            Double playerScale,
            boolean bypassWorldLevelCap,
            String displayName,
            boolean alwaysHostile,
            boolean clearVanillaDrops,
            SpawnerThrottle spawnerThrottle,
            List<DropRule> drops
    ) {
        boolean hasAnyMatcher() {
            return matchType != null
                    || matchName != null
                    || matchNameRegex != null
                    || matchMythicId != null
                    || matchPdcId != null
                    || !matchScoreboardTags.isEmpty()
                    || !matchEquipment.isEmpty();
        }

        boolean matches(LivingEntity entity) {
            if (matchType != null && entity.getType() != matchType) {
                return false;
            }

            String entityName = null;
            if (matchName != null || matchNameRegex != null) {
                entityName = getPlainEntityName(entity);
                if (entityName == null || entityName.isBlank()) {
                    return false;
                }
            }

            if (matchName != null && !containsIgnoreCase(entityName, matchName)) {
                return false;
            }

            if (matchNameRegex != null && !matchNameRegex.matcher(entityName).find()) {
                return false;
            }

            if (matchMythicId != null && !equalsIgnoreCase(matchMythicId, resolveMythicMobId(entity))) {
                return false;
            }

            if (matchPdcId != null && !equalsIgnoreCase(matchPdcId, getServerCoreMobId(entity))) {
                return false;
            }

            Set<String> scoreboardTags = entity.getScoreboardTags();
            for (String requiredTag : matchScoreboardTags) {
                if (!scoreboardTags.contains(requiredTag)) {
                    return false;
                }
            }

            return equipmentMatches(entity);
        }

        private boolean equipmentMatches(LivingEntity entity) {
            if (matchEquipment.isEmpty()) {
                return true;
            }

            EntityEquipment equipment = entity.getEquipment();
            if (equipment == null) {
                return false;
            }

            for (Map.Entry<EquipmentSlotKey, Material> entry : matchEquipment.entrySet()) {
                ItemStack item = entry.getKey().read(equipment);
                if (item == null || item.getType() != entry.getValue()) {
                    return false;
                }
            }
            return true;
        }
    }

    private record DropRule(
            String itemId,
            Material material,
            String enchantBookId,
            double chance,
            int minAmount,
            int maxAmount,
            int minLevel,
            int maxLevel
    ) {
        ItemStack roll(ThreadLocalRandom random, org.bukkit.entity.Player killer) {
            boolean success;
            GlobalStatManager globalStatManager = GlobalStatManager.getInstance();
            if (killer != null && globalStatManager != null) {
                success = globalStatManager.rollRareDrop(killer, chance, random);
            } else {
                success = chance > 0.0 && random.nextDouble() <= chance;
            }
            if (!success) {
                return null;
            }

            if (enchantBookId != null) {
                EnchantRegistry registry = EnchantRegistry.getInstance();
                EnchantDefinition definition = registry == null ? null : registry.get(enchantBookId).orElse(null);
                if (definition == null || !definition.enabled()) {
                    return null;
                }
                int safeMax = Math.min(Math.max(minLevel, maxLevel), definition.maxLevel());
                int safeMin = Math.min(Math.max(1, minLevel), safeMax);
                int level = safeMin == safeMax ? safeMin : random.nextInt(safeMin, safeMax + 1);
                return EnchantBookFactory.createBook(definition, level);
            }

            int amount = minAmount == maxAmount ? minAmount : random.nextInt(minAmount, maxAmount + 1);
            if (itemId != null) {
                CustomItemRegistry customItemRegistry = CustomItemRegistry.getInstance();
                return customItemRegistry == null ? null : customItemRegistry.createItem(itemId, amount);
            }

            if (material == null) {
                return null;
            }
            int stackAmount = Math.max(1, Math.min(amount, material.getMaxStackSize()));
            return new ItemStack(material, stackAmount);
        }
    }

    private record AmountRange(int min, int max) {
    }

    private enum EquipmentSlotKey {
        HELMET("helmet") {
            @Override
            ItemStack read(EntityEquipment equipment) {
                return equipment.getHelmet();
            }

            @Override
            void write(EntityEquipment equipment, ItemStack item) {
                equipment.setHelmet(item);
            }
        },
        CHESTPLATE("chestplate") {
            @Override
            ItemStack read(EntityEquipment equipment) {
                return equipment.getChestplate();
            }

            @Override
            void write(EntityEquipment equipment, ItemStack item) {
                equipment.setChestplate(item);
            }
        },
        LEGGINGS("leggings") {
            @Override
            ItemStack read(EntityEquipment equipment) {
                return equipment.getLeggings();
            }

            @Override
            void write(EntityEquipment equipment, ItemStack item) {
                equipment.setLeggings(item);
            }
        },
        BOOTS("boots") {
            @Override
            ItemStack read(EntityEquipment equipment) {
                return equipment.getBoots();
            }

            @Override
            void write(EntityEquipment equipment, ItemStack item) {
                equipment.setBoots(item);
            }
        },
        MAIN_HAND("main_hand") {
            @Override
            ItemStack read(EntityEquipment equipment) {
                return equipment.getItemInMainHand();
            }

            @Override
            void write(EntityEquipment equipment, ItemStack item) {
                equipment.setItemInMainHand(item);
            }
        },
        OFF_HAND("off_hand") {
            @Override
            ItemStack read(EntityEquipment equipment) {
                return equipment.getItemInOffHand();
            }

            @Override
            void write(EntityEquipment equipment, ItemStack item) {
                equipment.setItemInOffHand(item);
            }
        };

        private final String configKey;

        EquipmentSlotKey(String configKey) {
            this.configKey = configKey;
        }

        String configKey() {
            return configKey;
        }

        abstract ItemStack read(EntityEquipment equipment);

        abstract void write(EntityEquipment equipment, ItemStack item);
    }
}
