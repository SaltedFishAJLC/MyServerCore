package com.servercore.enchant;

import com.servercore.ServerCorePlugin;
import com.servercore.combat.creature.CreatureMainTag;
import com.servercore.combat.creature.CreatureTraitTag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class EnchantRegistry {

    private static EnchantRegistry instance;

    private final ServerCorePlugin plugin;
    private final Map<String, EnchantDefinition> definitions = new LinkedHashMap<>();
    private EnchantSettings settings = EnchantSettings.defaults();
    private EnchantPoolRegistry poolRegistry;

    public EnchantRegistry(ServerCorePlugin plugin) {
        this.plugin = plugin;
        instance = this;
        reload();
    }

    public static EnchantRegistry getInstance() {
        return instance;
    }

    public void reload() {
        ensureResource("enchants.yml");
        ensureResource("enchant_pools.yml");
        definitions.clear();

        File file = new File(plugin.getDataFolder(), "enchants.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        settings = EnchantSettings.from(config.getConfigurationSection("settings"));

        ConfigurationSection section = config.getConfigurationSection("enchants");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection enchantSection = section.getConfigurationSection(id);
                if (enchantSection == null) {
                    continue;
                }
                EnchantDefinition definition = parseDefinition(normalize(id), enchantSection);
                if (!definition.id().isBlank()) {
                    definitions.put(definition.id(), definition);
                }
            }
        }

        poolRegistry = new EnchantPoolRegistry(plugin);
        plugin.getLogger().info("Loaded custom enchants: " + definitions.size());
    }

    public Optional<EnchantDefinition> get(String id) {
        return Optional.ofNullable(definitions.get(normalize(id)));
    }

    public boolean isEnabled(String id) {
        return get(id).map(EnchantDefinition::enabled).orElse(false);
    }

    public Collection<EnchantDefinition> getAllDefinitions() {
        return List.copyOf(definitions.values());
    }

    public Collection<EnchantDefinition> getEnabledDefinitions() {
        return definitions.values().stream()
                .filter(EnchantDefinition::enabled)
                .toList();
    }

    public EnchantSettings settings() {
        return settings;
    }

    public EnchantPoolRegistry pools() {
        return poolRegistry;
    }

    private EnchantDefinition parseDefinition(String id, ConfigurationSection section) {
        boolean enabled = section.getBoolean("enabled", true);
        String display = section.getString("display", id);
        EnchantRarity rarity = parseEnum(EnchantRarity.class, section.getString("rarity"), EnchantRarity.COMMON);
        int maxLevel = Math.max(1, section.getInt("max_level", 1));
        int softMaxLevel = section.getInt("soft_max_level",
                section.getInt("table_max_level",
                        section.getInt("enchant_table_max_level", maxLevel)));
        Set<EnchantSlot> slots = parseEnumSet(EnchantSlot.class, section.getStringList("slots"));
        String conflictGroup = normalize(section.getString("conflict_group", ""));
        if (rarity == EnchantRarity.ULTIMATE) {
            conflictGroup = "ultimate";
        }

        List<String> description = section.getStringList("description");
        Map<String, ValueCurve> numeric = parseCurveMap(section.getConfigurationSection("numeric"), "enchants." + id + ".numeric");
        EnchantTargetSpec target = parseTarget(section.getConfigurationSection("target"));
        EnchantEffectSpec effect = parseEffect(id, rarity, section.getConfigurationSection("effect"));

        if (id.isBlank()) {
            plugin.getLogger().warning("Skipping enchant with blank id.");
        }
        if (slots.isEmpty()) {
            plugin.getLogger().warning("Enchant " + id + " has no slots; it will not be newly applicable.");
        }
        if (maxLevel < 1) {
            plugin.getLogger().warning("Enchant " + id + " has invalid max_level; using 1.");
        }
        if (softMaxLevel > maxLevel) {
            plugin.getLogger().warning("Enchant " + id + " soft_max_level exceeds max_level; clamping to hard max.");
        }

        return new EnchantDefinition(
                id,
                enabled,
                display,
                rarity,
                maxLevel,
                softMaxLevel,
                slots,
                conflictGroup,
                description,
                numeric,
                effect,
                target
        );
    }

    private EnchantEffectSpec parseEffect(String id, EnchantRarity rarity, ConfigurationSection section) {
        if (section == null) {
            return EnchantEffectSpec.none();
        }

        if (!rarity.isMechanicAllowed()) {
            plugin.getLogger().warning("Enchant " + id + " is " + rarity + " but declares effect; effect ignored.");
            return EnchantEffectSpec.none();
        }

        EnchantEffectType type = parseEnum(EnchantEffectType.class, section.getString("type"), EnchantEffectType.NONE);
        String trigger = section.getString("trigger", "");
        ConfigurationSection params = section.getConfigurationSection("params");
        Map<String, ValueCurve> numericParams = parseCurveMap(params, "enchants." + id + ".effect.params");
        Map<String, Object> rawParams = new LinkedHashMap<>();
        if (params != null) {
            for (String key : params.getKeys(false)) {
                Object raw = params.get(key);
                rawParams.put(normalize(key), raw);
            }
        }
        return new EnchantEffectSpec(type, trigger, numericParams, rawParams);
    }

    private Map<String, ValueCurve> parseCurveMap(ConfigurationSection section, String debugPath) {
        Map<String, ValueCurve> curves = new LinkedHashMap<>();
        if (section == null) {
            return curves;
        }
        for (String key : section.getKeys(false)) {
            Object raw = section.get(key);
            String normalized = normalize(key);
            if (raw instanceof Number number) {
                curves.put(normalized, ValueCurve.constant(number.doubleValue()));
                continue;
            }
            ConfigurationSection curveSection = section.getConfigurationSection(key);
            if (curveSection == null) {
                continue;
            }
            ValueCurve curve = ValueCurve.fromSection(curveSection, debugPath + "." + key);
            curves.put(normalized, curve);
        }
        return curves;
    }

    private EnchantTargetSpec parseTarget(ConfigurationSection section) {
        if (section == null) {
            return EnchantTargetSpec.empty();
        }

        Set<CreatureMainTag> mainTags = parseEnumSet(CreatureMainTag.class, section.getStringList("main_tags"));
        Set<CreatureTraitTag> traitTags = parseEnumSet(CreatureTraitTag.class, section.getStringList("trait_tags"));
        boolean boss = section.getBoolean("boss", false)
                || section.getBoolean("boss_targets", false)
                || traitTags.contains(CreatureTraitTag.BOSS);
        return new EnchantTargetSpec(mainTags, traitTags, boss);
    }

    private <E extends Enum<E>> Set<E> parseEnumSet(Class<E> enumClass, List<String> values) {
        EnumSet<E> result = EnumSet.noneOf(enumClass);
        if (values == null) {
            return result;
        }
        for (String value : values) {
            E parsed = parseEnum(enumClass, value, null);
            if (parsed != null) {
                result.add(parsed);
            }
        }
        return result;
    }

    private <E extends Enum<E>> E parseEnum(Class<E> enumClass, String raw, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(enumClass, raw.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            plugin.getLogger().warning("Unknown enum value " + raw + " for " + enumClass.getSimpleName());
            return fallback;
        }
    }

    private void ensureResource(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (file.exists()) {
            return;
        }
        plugin.saveResource(name, false);
    }

    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
