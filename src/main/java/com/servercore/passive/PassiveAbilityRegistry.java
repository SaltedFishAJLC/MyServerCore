package com.servercore.passive;

import com.servercore.ServerCorePlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class PassiveAbilityRegistry {

    private static PassiveAbilityRegistry instance;

    private final ServerCorePlugin plugin;
    private final File file;
    private Map<String, PassiveDefinition> definitions = Map.of();

    public PassiveAbilityRegistry(ServerCorePlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "passive_abilities.yml");
        instance = this;
        reload();
    }

    public static PassiveAbilityRegistry getInstance() {
        return instance;
    }

    public boolean reload() {
        ensureFile();
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file);
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().severe("Could not reload passive_abilities.yml; keeping the previous registry: "
                    + exception.getMessage());
            return false;
        }
        ConfigurationSection root = config.getConfigurationSection("abilities");
        Map<String, PassiveDefinition> loaded = new LinkedHashMap<>();
        if (root != null) {
            for (String rawId : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(rawId);
                if (section == null) {
                    continue;
                }
                String id = normalize(rawId);
                String handler = normalize(section.getString("handler", id));
                Set<PassiveSourceType> allowedSources = readSources(section.getStringList("allowed_sources"));
                Map<String, Object> defaults = readOptions(section.getConfigurationSection("default_options"));
                loaded.put(id, new PassiveDefinition(
                        id,
                        handler,
                        section.getString("name", rawId),
                        section.getStringList("description"),
                        defaults,
                        allowedSources,
                        normalize(section.getString("survival_group", "")),
                        section.getInt("event_priority", 0),
                        Math.max(1, section.getInt("period_ticks", 10))
                ));
            }
        }
        definitions = Map.copyOf(loaded);
        plugin.getLogger().info("Loaded " + definitions.size() + " passive ability definition(s).");
        return true;
    }

    public PassiveDefinition get(String id) {
        return definitions.get(normalize(id));
    }

    public Map<String, PassiveDefinition> all() {
        return definitions;
    }

    private Map<String, Object> readOptions(ConfigurationSection section) {
        if (section == null) {
            return Map.of();
        }
        Map<String, Object> options = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            options.put(key, section.get(key));
        }
        return Map.copyOf(options);
    }

    private Set<PassiveSourceType> readSources(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return EnumSet.allOf(PassiveSourceType.class);
        }
        EnumSet<PassiveSourceType> result = EnumSet.noneOf(PassiveSourceType.class);
        for (String value : raw) {
            try {
                result.add(PassiveSourceType.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Unknown passive source '" + value + "' in passive_abilities.yml");
            }
        }
        return result.isEmpty() ? EnumSet.allOf(PassiveSourceType.class) : Set.copyOf(result);
    }

    private void ensureFile() {
        if (file.exists()) {
            return;
        }
        plugin.saveResource("passive_abilities.yml", false);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record PassiveDefinition(
            String id,
            String handler,
            String displayName,
            List<String> description,
            Map<String, Object> defaultOptions,
            Set<PassiveSourceType> allowedSources,
            String survivalGroup,
            int eventPriority,
            int periodTicks
    ) {
        public List<String> renderDescription(Map<String, Object> options) {
            List<String> rendered = new ArrayList<>();
            for (String line : description) {
                String value = line;
                for (Map.Entry<String, Object> entry : options.entrySet()) {
                    value = value.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
                }
                rendered.add(value);
            }
            return rendered;
        }
    }
}
