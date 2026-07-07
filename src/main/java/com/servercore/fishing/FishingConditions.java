package com.servercore.fishing;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public record FishingConditions(
        boolean requireOpenWater,
        boolean requireRainInfluenced,
        boolean requireSkyInfluenced,
        Set<String> biomeTags,
        Set<String> environmentTags,
        Set<String> weather,
        Set<String> time
) {
    public FishingConditions {
        biomeTags = normalizeSet(biomeTags);
        environmentTags = normalizeSet(environmentTags);
        weather = normalizeSet(weather);
        time = normalizeSet(time);
    }

    public static FishingConditions empty() {
        return new FishingConditions(false, false, false, Set.of(), Set.of(), Set.of(), Set.of());
    }

    public static FishingConditions legacyBiomeTags(String legacyBiomeTags) {
        return new FishingConditions(false, false, false, splitLegacyTags(legacyBiomeTags), Set.of(), Set.of(), Set.of());
    }

    public static FishingConditions fromConfig(ConfigurationSection section, String legacyBiomeTags) {
        if (section == null) {
            return legacyBiomeTags == null || legacyBiomeTags.isBlank() ? empty() : legacyBiomeTags(legacyBiomeTags);
        }
        Set<String> biomeTags = readSet(section, "biome_tags");
        if (biomeTags.isEmpty()) {
            biomeTags = splitLegacyTags(legacyBiomeTags);
        }
        return new FishingConditions(
                section.getBoolean("require_open_water", false),
                section.getBoolean("require_rain_influenced", false),
                section.getBoolean("require_sky_influenced", false),
                biomeTags,
                readSet(section, "environment_tags"),
                readSet(section, "weather"),
                readSet(section, "time")
        );
    }

    public boolean matches(FishingContext context) {
        if (context == null) {
            return false;
        }
        if (requireOpenWater && !context.openWater()) {
            return false;
        }
        if (requireRainInfluenced && !context.rainInfluenced()) {
            return false;
        }
        if (requireSkyInfluenced && !context.skyInfluenced()) {
            return false;
        }
        if (!biomeTags.isEmpty() && biomeTags.stream().noneMatch(context.biomeTags()::contains)) {
            return false;
        }
        if (!environmentTags.isEmpty() && !context.environmentTags().containsAll(environmentTags)) {
            return false;
        }
        return matchesWeather(context) && matchesTime(context.worldTime());
    }

    private boolean matchesWeather(FishingContext context) {
        if (weather.isEmpty() || weather.contains("ANY")) {
            return true;
        }
        if (weather.contains("THUNDER") && context.thundering()) {
            return true;
        }
        if (weather.contains("RAIN") && context.raining()) {
            return true;
        }
        return weather.contains("CLEAR") && !context.raining() && !context.thundering();
    }

    private boolean matchesTime(long worldTime) {
        if (time.isEmpty() || time.contains("ANY")) {
            return true;
        }
        long t = Math.floorMod(worldTime, 24000L);
        for (String slot : time) {
            if (switch (slot) {
                case "DAY" -> t >= 0 && t < 12000;
                case "DUSK" -> t >= 12000 && t < 14000;
                case "NIGHT" -> t >= 13000 && t < 23000;
                case "DAWN" -> t >= 23000 || t < 1000;
                default -> false;
            }) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> readSet(ConfigurationSection section, String key) {
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

    private static Set<String> splitLegacyTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<String> tags = new HashSet<>();
        Arrays.stream(raw.split(",")).map(FishingConditions::normalize).filter(value -> !value.isBlank()).forEach(tags::add);
        return tags;
    }

    private static Set<String> normalizeSet(Set<String> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new HashSet<>();
        source.stream().map(FishingConditions::normalize).filter(value -> !value.isBlank()).forEach(result::add);
        return Set.copyOf(result);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
