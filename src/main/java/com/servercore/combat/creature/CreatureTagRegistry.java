package com.servercore.combat.creature;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CreatureTagRegistry {

    private final Plugin plugin;
    private final File configFile;
    private final EnumMap<EntityType, CreatureTagProfile> profiles = new EnumMap<>(EntityType.class);
    private CreatureTagRenderer renderer;

    public CreatureTagRegistry(Plugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "creature-tags.yml");
        reload();
    }

    public void reload() {
        ensureConfigFile();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        profiles.clear();
        renderer = new CreatureTagRenderer(config);

        ConfigurationSection entities = config.getConfigurationSection("entities");
        if (entities == null) {
            plugin.getLogger().warning("creature-tags.yml has no entities section; default classifications will be used.");
            return;
        }

        for (String key : entities.getKeys(false)) {
            EntityType type = parseEntityType(key);
            if (type == null) {
                continue;
            }

            ConfigurationSection section = entities.getConfigurationSection(key);
            if (section == null) {
                continue;
            }

            CreatureMainTag mainTag = parseMainTag(section.getString("main"), defaultProfile(type).mainTag());
            Set<CreatureTraitTag> traits = parseTraits(section, key);
            profiles.put(type, new CreatureTagProfile(mainTag, traits));
        }
        plugin.getLogger().info("Loaded creature tags for " + profiles.size() + " entity type(s).");
    }

    public CreatureTagProfile getProfile(EntityType type) {
        if (type == null) {
            return new CreatureTagProfile(CreatureMainTag.ABERRANT, Set.of());
        }
        CreatureTagProfile configured = profiles.get(type);
        return configured == null ? defaultProfile(type) : configured;
    }

    public CreatureTagRenderer renderer() {
        return renderer;
    }

    private Set<CreatureTraitTag> parseTraits(ConfigurationSection section, String entityKey) {
        List<String> rawTraits = section.getStringList("traits");
        if (rawTraits.isEmpty() && section.isString("traits")) {
            rawTraits = List.of(section.getString("traits", ""));
        }

        LinkedHashSet<CreatureTraitTag> parsed = new LinkedHashSet<>();
        for (String raw : rawTraits) {
            for (String part : raw.split(",")) {
                if (part.isBlank()) {
                    continue;
                }
                try {
                    parsed.add(CreatureTraitTag.valueOf(part.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException exception) {
                    plugin.getLogger().warning("Unknown trait tag in creature-tags.yml for " + entityKey + ": " + part);
                }
            }
        }
        return trimTraits(parsed, entityKey);
    }

    private Set<CreatureTraitTag> trimTraits(Set<CreatureTraitTag> traits, String entityKey) {
        boolean boss = traits.contains(CreatureTraitTag.BOSS);
        List<CreatureTraitTag> ordinary = traits.stream()
                .filter(tag -> tag != CreatureTraitTag.BOSS)
                .sorted(CreatureTraitTag.DISPLAY_ORDER)
                .toList();

        if (ordinary.size() > 2) {
            plugin.getLogger().warning("creature-tags.yml gives more than two non-boss traits to " + entityKey + "; extra traits were trimmed.");
        }

        LinkedHashSet<CreatureTraitTag> result = new LinkedHashSet<>(ordinary.stream().limit(2).toList());
        if (boss) {
            result.add(CreatureTraitTag.BOSS);
        }
        return Set.copyOf(result);
    }

    private CreatureMainTag parseMainTag(String raw, CreatureMainTag fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return CreatureMainTag.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning("Unknown main creature tag in creature-tags.yml: " + raw);
            return fallback;
        }
    }

    private EntityType parseEntityType(String raw) {
        try {
            return EntityType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            if ("CREAKING".equalsIgnoreCase(raw)) {
                return null;
            }
            plugin.getLogger().warning("Unknown entity type in creature-tags.yml: " + raw);
            return null;
        }
    }

    private CreatureTagProfile defaultProfile(EntityType type) {
        String name = type.name();
        return switch (name) {
            case "ZOMBIE", "HUSK", "DROWNED", "ZOMBIE_VILLAGER", "ZOMBIFIED_PIGLIN", "ZOMBIE_HORSE", "ZOGLIN" ->
                    new CreatureTagProfile(CreatureMainTag.UNDEAD, Set.of());
            case "SKELETON", "STRAY", "WITHER_SKELETON", "SKELETON_HORSE", "BOGGED" ->
                    new CreatureTagProfile(CreatureMainTag.SKELETON, "STRAY".equals(name) ? Set.of(CreatureTraitTag.FROST)
                            : "WITHER_SKELETON".equals(name) ? Set.of(CreatureTraitTag.VOID) : Set.of());
            case "SPIDER", "CAVE_SPIDER", "SILVERFISH" ->
                    new CreatureTagProfile(CreatureMainTag.ARTHROPOD, Set.of());
            case "ENDERMITE" -> new CreatureTagProfile(CreatureMainTag.ARTHROPOD, Set.of(CreatureTraitTag.VOID));
            case "BEE" -> new CreatureTagProfile(CreatureMainTag.ARTHROPOD, Set.of(CreatureTraitTag.FLYING));
            case "PLAYER", "VILLAGER", "WANDERING_TRADER", "PILLAGER", "VINDICATOR", "EVOKER", "ILLUSIONER",
                    "WITCH", "PIGLIN", "PIGLIN_BRUTE" -> new CreatureTagProfile(CreatureMainTag.HUMANOID, Set.of());
            case "ENDERMAN" -> new CreatureTagProfile(CreatureMainTag.HUMANOID, Set.of(CreatureTraitTag.VOID));
            case "SLIME" -> new CreatureTagProfile(CreatureMainTag.GELATINOUS, Set.of());
            case "MAGMA_CUBE" -> new CreatureTagProfile(CreatureMainTag.GELATINOUS, Set.of(CreatureTraitTag.FIRE));
            case "IRON_GOLEM", "CREAKING" -> new CreatureTagProfile(CreatureMainTag.CONSTRUCT, Set.of());
            case "SNOW_GOLEM" -> new CreatureTagProfile(CreatureMainTag.CONSTRUCT, Set.of(CreatureTraitTag.FROST));
            case "GHAST" -> new CreatureTagProfile(CreatureMainTag.GHOST, Set.of(CreatureTraitTag.FIRE, CreatureTraitTag.FLYING));
            case "VEX", "ALLAY", "PHANTOM" -> new CreatureTagProfile(CreatureMainTag.GHOST, Set.of(CreatureTraitTag.FLYING));
            case "RAVAGER" -> new CreatureTagProfile(CreatureMainTag.GIANT, Set.of());
            case "WARDEN" -> new CreatureTagProfile(CreatureMainTag.GIANT, Set.of(CreatureTraitTag.BOSS));
            case "WITHER", "ENDER_DRAGON" -> new CreatureTagProfile(CreatureMainTag.GIANT, Set.of(CreatureTraitTag.VOID, CreatureTraitTag.BOSS));
            case "BLAZE" -> new CreatureTagProfile(CreatureMainTag.ABERRANT, Set.of(CreatureTraitTag.FIRE, CreatureTraitTag.FLYING));
            case "BREEZE" -> new CreatureTagProfile(CreatureMainTag.ABERRANT, Set.of(CreatureTraitTag.FLYING));
            case "SHULKER" -> new CreatureTagProfile(CreatureMainTag.ABERRANT, Set.of(CreatureTraitTag.VOID));
            case "ELDER_GUARDIAN" -> new CreatureTagProfile(CreatureMainTag.ABERRANT, Set.of(CreatureTraitTag.BOSS));
            default -> isAnimalName(name)
                    ? new CreatureTagProfile(CreatureMainTag.ANIMAL, Set.of())
                    : new CreatureTagProfile(CreatureMainTag.ABERRANT, Set.of());
        };
    }

    private boolean isAnimalName(String name) {
        return switch (name) {
            case "COW", "PIG", "SHEEP", "CHICKEN", "WOLF", "FOX", "POLAR_BEAR", "HORSE", "DONKEY",
                    "MULE", "LLAMA", "TRADER_LLAMA", "CAMEL", "GOAT", "TURTLE", "DOLPHIN", "FROG",
                    "AXOLOTL", "RABBIT", "OCELOT", "CAT", "PARROT", "BAT", "COD", "SALMON",
                    "PUFFERFISH", "TROPICAL_FISH", "SQUID", "GLOW_SQUID", "HOGLIN", "PANDA",
                    "MOOSHROOM", "ARMADILLO", "SNIFFER" -> true;
            default -> false;
        };
    }

    private void ensureConfigFile() {
        if (configFile.exists()) {
            return;
        }
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder for creature-tags.yml.");
            return;
        }
        if (plugin instanceof JavaPlugin javaPlugin && javaPlugin.getResource("creature-tags.yml") != null) {
            javaPlugin.saveResource("creature-tags.yml", false);
            return;
        }

        YamlConfiguration config = new YamlConfiguration();
        config.set("entities.ZOMBIE.main", "UNDEAD");
        config.set("entities.SKELETON.main", "SKELETON");
        config.set("entities.WITHER_SKELETON.main", "SKELETON");
        config.set("entities.WITHER_SKELETON.traits", List.of("VOID"));
        config.set("entities.CREEPER.main", "ABERRANT");
        try {
            config.save(configFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not create creature-tags.yml: " + exception.getMessage());
        }
    }
}
