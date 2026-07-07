package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import com.servercore.fishing.FishingConditions;
import com.servercore.fishing.FishingContext;
import com.servercore.fishing.FishingEnvironmentResult;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class FishingEnvironmentManager {

    private static FishingEnvironmentManager instance;

    private final ServerCorePlugin plugin;
    private final File environmentFile;

    private volatile boolean requireOpenWaterForCustomFishing = true;
    private volatile boolean requireOpenWaterForEnvironmentBonus = true;
    private volatile boolean useVanillaOpenWaterCheck = true;
    private volatile boolean debug = false;
    private volatile Map<String, Set<String>> biomeTags = Map.of();
    private volatile List<EnvironmentRule> rules = List.of();

    public FishingEnvironmentManager(ServerCorePlugin plugin) {
        this.plugin = plugin;
        this.environmentFile = new File(plugin.getDataFolder(), "fishing_environment.yml");
        instance = this;
        reload();
    }

    public static FishingEnvironmentManager getInstance() {
        return instance;
    }

    public int reload() {
        ensureResource();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(environmentFile);
        ConfigurationSection settings = config.getConfigurationSection("settings");
        this.requireOpenWaterForCustomFishing = settings == null
                || settings.getBoolean("require_open_water_for_custom_fishing", true);
        this.requireOpenWaterForEnvironmentBonus = settings == null
                || settings.getBoolean("require_open_water_for_environment_bonus", true);
        this.useVanillaOpenWaterCheck = settings == null
                || settings.getBoolean("use_vanilla_open_water_check", true);
        this.debug = settings != null && settings.getBoolean("debug", false);
        this.biomeTags = readBiomeTags(config.getConfigurationSection("biome_tags"));
        this.rules = readRules(config.getConfigurationSection("modifiers"));
        plugin.getLogger().info("Loaded fishing environment rules: " + rules.size() + ".");
        return rules.size();
    }

    public boolean debugEnabled() {
        return debug;
    }

    public boolean shouldUseCustomFishing(FishingContext context) {
        return !requireOpenWaterForCustomFishing || context.openWater();
    }

    public FishingContext buildContext(Player player, FishHook hook) {
        Location location = hook.getLocation();
        World world = location.getWorld();
        Biome biome = location.getBlock().getBiome();
        boolean openWater = isOpenWater(hook);
        boolean raining = world != null && world.hasStorm();
        boolean thundering = world != null && world.isThundering();
        long worldTime = world == null ? 0L : world.getTime();
        long fullTime = world == null ? 0L : world.getFullTime();
        Set<String> resolvedBiomeTags = resolveBiomeTags(biome);
        return new FishingContext(
                player,
                hook,
                location,
                world,
                biome,
                openWater,
                raining,
                thundering,
                isRainInfluenced(hook),
                isSkyInfluenced(hook),
                worldTime,
                fullTime,
                fullTime / 24000L,
                resolvedBiomeTags,
                resolvedBiomeTags
        );
    }

    public FishingEnvironmentResult resolve(FishingContext context) {
        if (context == null) {
            return FishingEnvironmentResult.empty(Set.of());
        }
        Set<String> tags = new HashSet<>(context.environmentTags());
        if (requireOpenWaterForEnvironmentBonus && !context.openWater()) {
            return FishingEnvironmentResult.empty(tags);
        }

        FishingManager.FishingStatContribution bonus = FishingManager.FishingStatContribution.empty();
        List<String> matchedRules = new ArrayList<>();
        FishingContext current = context.withEnvironmentTags(tags);
        for (EnvironmentRule rule : rules) {
            if (!rule.enabled() || !rule.conditions().matches(current)) {
                continue;
            }
            bonus = bonus.plus(rule.stats());
            tags.addAll(rule.environmentTags());
            matchedRules.add(rule.id());
            current = context.withEnvironmentTags(tags);
        }
        return new FishingEnvironmentResult(bonus, tags, matchedRules);
    }

    public List<String> describe(Player player, FishHook hook) {
        FishingContext context = buildContext(player, hook);
        FishingEnvironmentResult result = resolve(context);
        List<String> lines = new ArrayList<>();
        lines.add("Biome: " + context.biome().name());
        lines.add("OpenWater: " + context.openWater());
        lines.add("Weather: " + (context.thundering() ? "THUNDER" : context.raining() ? "RAIN" : "CLEAR"));
        lines.add("RainInfluenced: " + context.rainInfluenced() + " SkyInfluenced: " + context.skyInfluenced());
        lines.add("BiomeTags: " + context.biomeTags());
        lines.add("EnvTags: " + result.environmentTags());
        lines.add("FS Bonus: " + formatSigned(result.bonus().fishingSpeed()));
        lines.add("SCC Bonus: " + formatSigned(result.bonus().seaCreatureChance()) + "%");
        lines.add("TC Bonus: " + formatSigned(result.bonus().treasureChance()) + "%");
        lines.add("Matched Rules: " + (result.matchedRuleIds().isEmpty() ? "none" : String.join(", ", result.matchedRuleIds())));
        return lines;
    }

    public boolean isOpenWater(FishHook hook) {
        if (hook == null) {
            return false;
        }
        if (useVanillaOpenWaterCheck) {
            try {
                return hook.isInOpenWater();
            } catch (Throwable ignored) {
                // Fallback keeps compatibility if the runtime API changes.
            }
        }
        return isCustomOpenWaterFallback(hook.getLocation());
    }

    private boolean isRainInfluenced(FishHook hook) {
        try {
            return hook.isRainInfluenced();
        } catch (Throwable ignored) {
            return hook.getWorld().hasStorm();
        }
    }

    private boolean isSkyInfluenced(FishHook hook) {
        try {
            return hook.isSkyInfluenced();
        } catch (Throwable ignored) {
            return hook.getLocation().getBlock().getLightFromSky() > 0;
        }
    }

    private Set<String> resolveBiomeTags(Biome biome) {
        String biomeName = normalize(biome.name());
        Set<String> tags = new HashSet<>();
        tags.add(biomeName);
        for (Map.Entry<String, Set<String>> entry : biomeTags.entrySet()) {
            if (entry.getValue().contains(biomeName)) {
                tags.add(entry.getKey());
            }
        }
        return tags;
    }

    private List<EnvironmentRule> readRules(ConfigurationSection section) {
        List<EnvironmentRule> loaded = new ArrayList<>();
        if (section == null) {
            return loaded;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection rule = section.getConfigurationSection(id);
            if (rule == null) {
                continue;
            }
            ConfigurationSection stats = rule.getConfigurationSection("stats");
            loaded.add(new EnvironmentRule(
                    normalize(id),
                    rule.getBoolean("enabled", true),
                    rule.getInt("priority", 0),
                    FishingConditions.fromConfig(rule.getConfigurationSection("conditions"), ""),
                    readStats(stats),
                    readStringSet(rule, "environment_tags")
            ));
        }
        loaded.sort(Comparator.comparingInt(EnvironmentRule::priority).thenComparing(EnvironmentRule::id));
        return List.copyOf(loaded);
    }

    private FishingManager.FishingStatContribution readStats(ConfigurationSection section) {
        if (section == null) {
            return FishingManager.FishingStatContribution.empty();
        }
        return new FishingManager.FishingStatContribution(
                section.getDouble("fishing_speed_flat", section.getDouble("fishing_speed", 0.0)),
                section.getDouble("sea_creature_chance", 0.0),
                section.getDouble("treasure_chance", 0.0)
        );
    }

    private Map<String, Set<String>> readBiomeTags(ConfigurationSection section) {
        Map<String, Set<String>> loaded = new LinkedHashMap<>();
        if (section == null) {
            return loaded;
        }
        for (String tag : section.getKeys(false)) {
            Set<String> biomes = new HashSet<>();
            for (String biome : section.getStringList(tag)) {
                biomes.add(normalize(biome));
            }
            if (!biomes.isEmpty()) {
                loaded.put(normalize(tag), Set.copyOf(biomes));
            }
        }
        return Map.copyOf(loaded);
    }

    private Set<String> readStringSet(ConfigurationSection section, String key) {
        if (section == null || !section.contains(key)) {
            return Set.of();
        }
        Set<String> values = new HashSet<>();
        if (section.isList(key)) {
            for (String value : section.getStringList(key)) {
                values.add(normalize(value));
            }
        } else {
            values.add(normalize(section.getString(key, "")));
        }
        values.remove("");
        return values;
    }

    private boolean isCustomOpenWaterFallback(Location location) {
        Block center = location.getBlock();
        if (!isWater(center)) {
            return false;
        }

        int waterBlocks = 0;
        int openColumns = 0;
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                Block surface = center.getRelative(x, 0, z);
                if (isWater(surface)) {
                    waterBlocks++;
                    if (!surface.getRelative(0, 1, 0).getType().isSolid()) {
                        openColumns++;
                    }
                }
            }
        }
        return waterBlocks >= 16 && openColumns >= 10;
    }

    private boolean isWater(Block block) {
        return block.getType() == Material.WATER || block.getType() == Material.BUBBLE_COLUMN;
    }

    private void ensureResource() {
        if (environmentFile.exists()) {
            return;
        }
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder for fishing_environment.yml.");
            return;
        }
        if (plugin.getResource("fishing_environment.yml") != null) {
            plugin.saveResource("fishing_environment.yml", false);
            return;
        }
        try {
            environmentFile.createNewFile();
        } catch (IOException exception) {
            plugin.getLogger().warning("Unable to create fishing_environment.yml: " + exception.getMessage());
        }
    }

    private String formatSigned(double value) {
        String prefix = value > 0.0 ? "+" : "";
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return prefix + (int) Math.rint(value);
        }
        return prefix + String.format(Locale.US, "%.1f", value);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record EnvironmentRule(
            String id,
            boolean enabled,
            int priority,
            FishingConditions conditions,
            FishingManager.FishingStatContribution stats,
            Set<String> environmentTags
    ) {
        private EnvironmentRule {
            conditions = conditions == null ? FishingConditions.empty() : conditions;
            stats = stats == null ? FishingManager.FishingStatContribution.empty() : stats;
            environmentTags = environmentTags == null ? Set.of() : Set.copyOf(environmentTags);
        }
    }
}
