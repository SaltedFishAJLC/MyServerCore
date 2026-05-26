package com.servercore.combat.resistance;

import com.servercore.combat.creature.CreatureMainTag;
import com.servercore.combat.creature.CreatureTraitTag;
import com.servercore.combat.damage.DamageTag;
import com.servercore.combat.status.StatusType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TagRuleRegistry {

    private static TagRuleRegistry instance;

    private final Plugin plugin;
    private final File configFile;
    private final Map<CreatureMainTag, TagRuleSet> mainRules = new EnumMap<>(CreatureMainTag.class);
    private final Map<CreatureTraitTag, TagRuleSet> traitRules = new EnumMap<>(CreatureTraitTag.class);

    public TagRuleRegistry(Plugin plugin) {
        instance = this;
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "tag-rules.yml");
        reload();
    }

    public static TagRuleRegistry getInstance() {
        return instance;
    }

    public void reload() {
        ensureConfigFile();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        mainRules.clear();
        traitRules.clear();

        loadMainRules(config.getConfigurationSection("rules.main"));
        loadTraitRules(config.getConfigurationSection("rules.trait"));
        plugin.getLogger().info("Loaded tag resistance rules: " + mainRules.size() + " main, " + traitRules.size() + " trait.");
    }

    public List<TagRuleSet> collectRules(CreatureMainTag mainTag, Iterable<CreatureTraitTag> traits) {
        List<TagRuleSet> result = new ArrayList<>();
        collectMainRule(mainTag, result, 0);
        if (traits != null) {
            for (CreatureTraitTag trait : traits) {
                TagRuleSet rule = traitRules.get(trait);
                if (rule != null) {
                    result.add(rule);
                }
            }
        }
        return result;
    }

    private void collectMainRule(CreatureMainTag tag, List<TagRuleSet> result, int depth) {
        if (tag == null || depth > CreatureMainTag.values().length) {
            return;
        }
        TagRuleSet rule = mainRules.get(tag);
        if (rule == null) {
            return;
        }
        collectMainRule(rule.inherit(), result, depth + 1);
        result.add(rule);
    }

    private void loadMainRules(ConfigurationSection mainRoot) {
        if (mainRoot == null) {
            return;
        }
        for (String key : mainRoot.getKeys(false)) {
            try {
                CreatureMainTag tag = CreatureMainTag.valueOf(key.toUpperCase(Locale.ROOT));
                mainRules.put(tag, parseRuleSet(mainRoot.getConfigurationSection(key)));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Unknown main tag in tag-rules.yml: " + key);
            }
        }
    }

    private void loadTraitRules(ConfigurationSection traitRoot) {
        if (traitRoot == null) {
            return;
        }
        for (String key : traitRoot.getKeys(false)) {
            try {
                CreatureTraitTag tag = CreatureTraitTag.valueOf(key.toUpperCase(Locale.ROOT));
                traitRules.put(tag, parseRuleSet(traitRoot.getConfigurationSection(key)));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Unknown trait tag in tag-rules.yml: " + key);
            }
        }
    }

    private TagRuleSet parseRuleSet(ConfigurationSection section) {
        CreatureMainTag inherit = null;
        Map<DamageTag, DamageRule> damageRules = new EnumMap<>(DamageTag.class);
        Map<StatusType, StatusRule> statusRules = new EnumMap<>(StatusType.class);
        Map<String, ControlRule> controlRules = new java.util.HashMap<>();
        DotCapRule dotCapRule = DotCapRule.NONE;

        if (section == null) {
            return new TagRuleSet(null, damageRules, statusRules, controlRules, dotCapRule);
        }

        String inheritText = section.getString("inherit");
        if (inheritText != null && !inheritText.isBlank()) {
            try {
                inherit = CreatureMainTag.valueOf(inheritText.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Unknown inherited main tag in tag-rules.yml: " + inheritText);
            }
        }

        ConfigurationSection damage = section.getConfigurationSection("damage");
        if (damage != null) {
            for (String key : damage.getKeys(false)) {
                try {
                    DamageTag tag = DamageTag.valueOf(key.toUpperCase(Locale.ROOT));
                    ConfigurationSection entry = damage.getConfigurationSection(key);
                    damageRules.put(tag, new DamageRule(entry == null ? damage.getDouble(key, 1.0) : entry.getDouble("damageMultiplier", 1.0)));
                } catch (IllegalArgumentException exception) {
                    plugin.getLogger().warning("Unknown damage tag in tag-rules.yml: " + key);
                }
            }
        }

        ConfigurationSection status = section.getConfigurationSection("status");
        if (status != null) {
            for (String key : status.getKeys(false)) {
                try {
                    StatusType type = StatusType.valueOf(key.toUpperCase(Locale.ROOT));
                    ConfigurationSection entry = status.getConfigurationSection(key);
                    statusRules.put(type, entry == null
                            ? new StatusRule(1.0, status.getDouble(key, 1.0))
                            : new StatusRule(entry.getDouble("applyMultiplier", 1.0), entry.getDouble("damageMultiplier", 1.0)));
                } catch (IllegalArgumentException exception) {
                    plugin.getLogger().warning("Unknown status type in tag-rules.yml: " + key);
                }
            }
        }

        ConfigurationSection control = section.getConfigurationSection("control");
        if (control != null) {
            for (String key : control.getKeys(false)) {
                ConfigurationSection entry = control.getConfigurationSection(key);
                if (entry == null) {
                    continue;
                }
                controlRules.put(normalizeControlKey(key), new ControlRule(
                        entry.getDouble("durationMultiplier", 1.0),
                        Math.max(0, entry.getInt("maxDurationTicks", 0)),
                        Math.max(0, entry.getInt("internalCooldownTicks", 0)),
                        entry.getDouble("multiplier", 1.0)
                ));
            }
        }

        ConfigurationSection dot = section.getConfigurationSection("dot");
        if (dot != null) {
            dotCapRule = new DotCapRule(Math.max(0.0, dot.getDouble("maxPercentHealthPerSecond", 0.0)));
        }

        return new TagRuleSet(inherit, damageRules, statusRules, controlRules, dotCapRule);
    }

    static String normalizeControlKey(String key) {
        return key == null ? "" : key.trim().toUpperCase(Locale.ROOT);
    }

    private void ensureConfigFile() {
        if (configFile.exists()) {
            return;
        }
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder for tag-rules.yml.");
            return;
        }
        if (plugin instanceof JavaPlugin javaPlugin && javaPlugin.getResource("tag-rules.yml") != null) {
            javaPlugin.saveResource("tag-rules.yml", false);
            return;
        }

        YamlConfiguration config = new YamlConfiguration();
        config.set("rules.main.SKELETON.inherit", "UNDEAD");
        config.set("rules.main.SKELETON.status.BLEEDING.applyMultiplier", 0.0);
        config.set("rules.main.SKELETON.status.BLEEDING.damageMultiplier", 0.0);
        try {
            config.save(configFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not create tag-rules.yml: " + exception.getMessage());
        }
    }

    public record TagRuleSet(
            CreatureMainTag inherit,
            Map<DamageTag, DamageRule> damageRules,
            Map<StatusType, StatusRule> statusRules,
            Map<String, ControlRule> controlRules,
            DotCapRule dotCapRule
    ) {
    }
}
