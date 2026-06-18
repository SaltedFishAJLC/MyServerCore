package com.servercore.passive;

import com.servercore.ServerCorePlugin;
import com.servercore.manager.CustomItemRegistry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class EquipmentSetRegistry {

    private static EquipmentSetRegistry instance;

    private final ServerCorePlugin plugin;
    private final File file;
    private Map<String, EquipmentSetDefinition> definitions = Map.of();

    public EquipmentSetRegistry(ServerCorePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "equipment_sets.yml");
        instance = this;
        reload();
    }

    public static EquipmentSetRegistry getInstance() {
        return instance;
    }

    public boolean reload() {
        ensureFile();
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().severe("Could not reload equipment_sets.yml; keeping the previous registry: "
                    + exception.getMessage());
            return false;
        }
        ConfigurationSection root = config.getConfigurationSection("sets");
        Map<String, EquipmentSetDefinition> loaded = new LinkedHashMap<>();
        if (root != null) {
            for (String rawId : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(rawId);
                if (section == null) {
                    continue;
                }
                String id = normalize(rawId);
                ThresholdMode mode = parseMode(section.getString("threshold_mode", "CUMULATIVE"));
                List<SetThreshold> thresholds = readThresholds(id, section.getConfigurationSection("thresholds"));
                loaded.put(id, new EquipmentSetDefinition(
                        id,
                        section.getString("name", rawId),
                        mode,
                        thresholds
                ));
            }
        }
        definitions = Map.copyOf(loaded);
        plugin.getLogger().info("Loaded " + definitions.size() + " equipment set definition(s).");
        return true;
    }

    public EquipmentSetDefinition get(String id) {
        return definitions.get(normalize(id));
    }

    public Map<String, EquipmentSetDefinition> all() {
        return definitions;
    }

    private List<SetThreshold> readThresholds(String setId, ConfigurationSection root) {
        List<SetThreshold> thresholds = new ArrayList<>();
        if (root == null) {
            return thresholds;
        }
        for (String rawCount : root.getKeys(false)) {
            int count;
            try {
                count = Integer.parseInt(rawCount);
            } catch (NumberFormatException exception) {
                plugin.getLogger().warning("Invalid set threshold '" + rawCount + "' for " + setId);
                continue;
            }
            if (count <= 0) {
                continue;
            }
            ConfigurationSection thresholdSection = root.getConfigurationSection(rawCount);
            if (thresholdSection == null) {
                continue;
            }
            List<CustomItemRegistry.AbilityDefinition> abilities = new ArrayList<>();
            ConfigurationSection abilityRoot = thresholdSection.getConfigurationSection("abilities");
            if (abilityRoot != null) {
                for (String abilityId : abilityRoot.getKeys(false)) {
                    ConfigurationSection abilitySection = abilityRoot.getConfigurationSection(abilityId);
                    if (abilitySection == null) {
                        abilities.add(new CustomItemRegistry.AbilityDefinition(
                                normalize(abilityId), "PASSIVE", 0, List.of(), Map.of()));
                    } else {
                        abilities.add(readAbility(abilityId, abilitySection));
                    }
                }
            } else {
                for (String abilityId : thresholdSection.getStringList("abilities")) {
                    abilities.add(new CustomItemRegistry.AbilityDefinition(
                            normalize(abilityId), "PASSIVE", 0, List.of(), Map.of()));
                }
            }
            thresholds.add(new SetThreshold(count, List.copyOf(abilities)));
        }
        thresholds.sort(Comparator.comparingInt(SetThreshold::pieces));
        return List.copyOf(thresholds);
    }

    private CustomItemRegistry.AbilityDefinition readAbility(String id, ConfigurationSection section) {
        Map<String, Object> options = new LinkedHashMap<>();
        ConfigurationSection nestedOptions = section.getConfigurationSection("options");
        if (nestedOptions != null) {
            for (String key : nestedOptions.getKeys(false)) {
                options.put(key, nestedOptions.get(key));
            }
        }
        for (String key : section.getKeys(false)) {
            if (key.equalsIgnoreCase("trigger") || key.equalsIgnoreCase("cooldown")
                    || key.equalsIgnoreCase("lore") || key.equalsIgnoreCase("options")) {
                continue;
            }
            options.put(key, section.get(key));
        }
        return new CustomItemRegistry.AbilityDefinition(
                normalize(id),
                section.getString("trigger", "PASSIVE").trim().toUpperCase(Locale.ROOT),
                Math.max(0, section.getInt("cooldown", 0)),
                section.getStringList("lore"),
                Map.copyOf(options)
        );
    }

    private ThresholdMode parseMode(String raw) {
        try {
            return ThresholdMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return ThresholdMode.CUMULATIVE;
        }
    }

    private void ensureFile() {
        if (!file.exists()) {
            plugin.saveResource("equipment_sets.yml", false);
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public enum ThresholdMode {
        CUMULATIVE,
        HIGHEST_ONLY
    }

    public record EquipmentSetDefinition(
            String id,
            String displayName,
            ThresholdMode thresholdMode,
            List<SetThreshold> thresholds
    ) {
    }

    public record SetThreshold(int pieces, List<CustomItemRegistry.AbilityDefinition> abilities) {
    }
}
