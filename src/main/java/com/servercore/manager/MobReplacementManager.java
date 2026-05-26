package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class MobReplacementManager implements Listener {

    private final ServerCorePlugin plugin;
    private final CustomMobRegistry customMobRegistry;
    private final File configFile;
    private final Map<String, ReplacementRule> rules = new LinkedHashMap<>();
    private boolean internalSpawn;

    public MobReplacementManager(ServerCorePlugin plugin, CustomMobRegistry customMobRegistry) {
        this.plugin = plugin;
        this.customMobRegistry = customMobRegistry;
        this.configFile = new File(plugin.getDataFolder(), "mob_replacements.yml");
        reload();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public int reload() {
        ensureConfigFile();
        loadConfig();
        return rules.size();
    }

    public List<String> getRuleIds() {
        return new ArrayList<>(rules.keySet());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (internalSpawn || rules.isEmpty() || event.getEntity() == null) {
            return;
        }

        Location location = event.getLocation();
        for (ReplacementRule rule : rules.values()) {
            if (!rule.matches(event, location)) {
                continue;
            }
            if (ThreadLocalRandom.current().nextDouble() > rule.chance()) {
                return;
            }

            LivingEntity replacement = spawnReplacement(rule, location);
            if (replacement == null) {
                return;
            }
            event.setCancelled(true);
            return;
        }
    }

    private LivingEntity spawnReplacement(ReplacementRule rule, Location location) {
        internalSpawn = true;
        try {
            LivingEntity entity = customMobRegistry.spawnConfiguredMob(rule.replacementMob(), location);
            if (entity == null) {
                plugin.getLogger().warning("Mob replacement '" + rule.id() + "' could not spawn custom mob rule '" + rule.replacementMob() + "'.");
                return null;
            }

            MobSpawnManager mobSpawnManager = MobSpawnManager.getInstance();
            if (mobSpawnManager != null) {
                mobSpawnManager.applyCustomMobScaling(entity, rule.replacementMob());
            }
            return entity;
        } finally {
            internalSpawn = false;
        }
    }

    private void loadConfig() {
        rules.clear();

        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        ConfigurationSection root = config.getConfigurationSection("replacements");
        if (root == null) {
            root = config;
        }

        for (String id : root.getKeys(false)) {
            if (!root.isConfigurationSection(id)) {
                continue;
            }

            ReplacementRule rule = parseRule(id, root.getConfigurationSection(id));
            if (rule != null) {
                rules.put(rule.id(), rule);
            }
        }

        plugin.getLogger().info("Loaded " + rules.size() + " mob replacement rule(s).");
    }

    private ReplacementRule parseRule(String id, ConfigurationSection section) {
        EntityType sourceType = parseEntityType(firstPresentString(section, "source_type", "source", "from"));
        String replacementMob = firstPresentString(section, "replacement_mob", "custom_mob", "mob", "to");
        if (sourceType == null || replacementMob == null) {
            plugin.getLogger().warning("Skipped mob replacement '" + id + "' because it needs source_type and replacement_mob.");
            return null;
        }
        if (customMobRegistry == null || !customMobRegistry.hasRule(replacementMob)) {
            plugin.getLogger().warning("Skipped mob replacement '" + id + "' because replacement_mob '" + replacementMob
                    + "' is not loaded in custom_mobs.yml. Add that rule, then run /sc admin mobs reload and /sc admin mobreplacements reload.");
            return null;
        }

        return new ReplacementRule(
                normalize(id),
                section.getBoolean("enabled", true),
                sourceType,
                replacementMob,
                parseChance(section.get("chance"), 1.0),
                parseSpawnReasons(section),
                normalizeSet(section.getStringList("worlds")),
                normalizeSet(section.getStringList("biomes"))
        );
    }

    private Set<CreatureSpawnEvent.SpawnReason> parseSpawnReasons(ConfigurationSection section) {
        List<String> raw = section.getStringList("spawn_reasons");
        if (raw.isEmpty()) {
            String single = firstPresentString(section, "spawn_reason", "reason");
            if (single != null) {
                raw = List.of(single);
            }
        }
        if (raw.isEmpty()) {
            return EnumSet.noneOf(CreatureSpawnEvent.SpawnReason.class);
        }

        Set<CreatureSpawnEvent.SpawnReason> reasons = EnumSet.noneOf(CreatureSpawnEvent.SpawnReason.class);
        for (String value : raw) {
            try {
                reasons.add(CreatureSpawnEvent.SpawnReason.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Unknown spawn reason in mob_replacements.yml: " + value);
            }
        }
        return reasons;
    }

    private EntityType parseEntityType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return EntityType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Unknown entity type in mob_replacements.yml: " + raw);
            return null;
        }
    }

    private double parseChance(Object raw, double fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number number) {
            return clampChance(number.doubleValue());
        }

        String text = String.valueOf(raw).trim();
        try {
            if (text.endsWith("%")) {
                return clampChance(Double.parseDouble(text.substring(0, text.length() - 1).trim()) / 100.0);
            }
            return clampChance(Double.parseDouble(text));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private double clampChance(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private Set<String> normalizeSet(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        Set<String> values = new java.util.HashSet<>();
        for (String value : raw) {
            if (value != null && !value.isBlank()) {
                values.add(normalize(value));
            }
        }
        return values;
    }

    private String firstPresentString(ConfigurationSection section, String... keys) {
        for (String key : keys) {
            String value = section.getString(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private void ensureConfigFile() {
        if (configFile.exists()) {
            return;
        }

        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder for mob_replacements.yml.");
            return;
        }

        if (plugin.getResource("mob_replacements.yml") != null) {
            plugin.saveResource("mob_replacements.yml", false);
            return;
        }

        YamlConfiguration config = new YamlConfiguration();
        String path = "replacements.rogue_enderman.";
        config.set(path + "enabled", true);
        config.set(path + "source_type", "ENDERMAN");
        config.set(path + "spawn_reasons", List.of("NATURAL"));
        config.set(path + "chance", "10%");
        config.set(path + "replacement_mob", "Rogue_Enderman");
        try {
            config.save(configFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not create mob_replacements.yml: " + exception.getMessage());
        }
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).trim();
    }

    private record ReplacementRule(
            String id,
            boolean enabled,
            EntityType sourceType,
            String replacementMob,
            double chance,
            Set<CreatureSpawnEvent.SpawnReason> spawnReasons,
            Set<String> worlds,
            Set<String> biomes
    ) {
        boolean matches(CreatureSpawnEvent event, Location location) {
            if (!enabled || event.getEntityType() != sourceType) {
                return false;
            }
            if (!spawnReasons.isEmpty() && !spawnReasons.contains(event.getSpawnReason())) {
                return false;
            }

            World world = location.getWorld();
            if (world != null && !worlds.isEmpty() && !worlds.contains(normalize(world.getName()))) {
                return false;
            }

            if (!biomes.isEmpty()) {
                Biome biome = location.getBlock().getBiome();
                if (!biomes.contains(normalize(biome.name()))) {
                    return false;
                }
            }
            return true;
        }
    }
}
