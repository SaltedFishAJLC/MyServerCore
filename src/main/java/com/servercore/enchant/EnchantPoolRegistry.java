package com.servercore.enchant;

import com.servercore.ServerCorePlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class EnchantPoolRegistry {

    private final ServerCorePlugin plugin;
    private boolean vanillaEnchantTableEnabled = true;
    private final List<RewardEntry> vanillaRewards = new ArrayList<>();
    private final Set<EnchantRarity> vanillaBlockedRarities = EnumSet.of(EnchantRarity.ULTIMATE);
    private final Map<EnchantRarity, Integer> grindstoneDustPerLevel = new EnumMap<>(EnchantRarity.class);
    private double clearAllDustRatio = 0.35;
    private double clearAllExpRatio = 0.25;

    public EnchantPoolRegistry(ServerCorePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        vanillaRewards.clear();
        vanillaBlockedRarities.clear();
        grindstoneDustPerLevel.clear();

        File file = new File(plugin.getDataFolder(), "enchant_pools.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection table = config.getConfigurationSection("vanilla_enchant_table");
        if (table != null) {
            vanillaEnchantTableEnabled = table.getBoolean("enabled", true);
            vanillaBlockedRarities.addAll(parseRarities(table.getStringList("blocked_rarities")));
            ConfigurationSection rewards = table.getConfigurationSection("rewards");
            if (rewards != null) {
                for (String key : rewards.getKeys(false)) {
                    ConfigurationSection reward = rewards.getConfigurationSection(key);
                    if (reward == null || reward.contains("item_id")) {
                        continue;
                    }
                    int weight = Math.max(0, reward.getInt("weight", 0));
                    Set<EnchantRarity> rarities = parseRarities(reward.getStringList("rarities"));
                    if (weight > 0 && !rarities.isEmpty()) {
                        vanillaRewards.add(new RewardEntry(weight, rarities));
                    }
                }
            }
        }
        if (vanillaBlockedRarities.isEmpty()) {
            vanillaBlockedRarities.add(EnchantRarity.ULTIMATE);
        }

        ConfigurationSection grindstone = config.getConfigurationSection("grindstone");
        if (grindstone != null) {
            ConfigurationSection removeSingle = grindstone.getConfigurationSection("remove_single");
            if (removeSingle != null) {
                for (EnchantRarity rarity : EnchantRarity.values()) {
                    int dust = removeSingle.getInt(rarity.name() + ".dust_per_level", defaultDustPerLevel(rarity));
                    grindstoneDustPerLevel.put(rarity, Math.max(0, dust));
                }
            }
            clearAllDustRatio = clamp(grindstone.getDouble("clear_all.refund_dust_ratio", clearAllDustRatio), 0.0, 1.0);
            clearAllExpRatio = clamp(grindstone.getDouble("clear_all.refund_exp_ratio", clearAllExpRatio), 0.0, 1.0);
        }
    }

    public Optional<EnchantDefinition> rollVanillaEnchantTable(ItemStack item, int power) {
        if (!vanillaEnchantTableEnabled || item == null || item.getType().isAir()) {
            return Optional.empty();
        }
        EnchantRegistry registry = EnchantRegistry.getInstance();
        if (registry == null || vanillaRewards.isEmpty()) {
            return Optional.empty();
        }

        int total = vanillaRewards.stream().mapToInt(RewardEntry::weight).sum();
        if (total <= 0) {
            return Optional.empty();
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        Set<EnchantRarity> rolledRarities = Set.of();
        for (RewardEntry entry : vanillaRewards) {
            roll -= entry.weight();
            if (roll < 0) {
                rolledRarities = entry.rarities();
                break;
            }
        }
        final Set<EnchantRarity> selectedRarities = rolledRarities;

        List<EnchantDefinition> candidates = registry.getEnabledDefinitions().stream()
                .filter(definition -> selectedRarities.contains(definition.rarity()))
                .filter(definition -> !vanillaBlockedRarities.contains(definition.rarity()))
                .filter(definition -> EnchantSlotMatcher.matches(item, definition.slots()))
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(candidates.get(ThreadLocalRandom.current().nextInt(candidates.size())));
    }

    public int rollEnchantLevel(EnchantDefinition definition, int power) {
        if (definition == null) {
            return 1;
        }
        int reachable = Math.max(1, Math.min(definition.softMaxLevel(), 1 + Math.max(0, power) / 10));
        return ThreadLocalRandom.current().nextInt(1, reachable + 1);
    }

    public int dustRefund(EnchantDefinition definition, int level) {
        EnchantRarity rarity = definition == null ? EnchantRarity.COMMON : definition.rarity();
        int perLevel = grindstoneDustPerLevel.getOrDefault(rarity, defaultDustPerLevel(rarity));
        return (int) Math.floor(perLevel * Math.max(1, level) * clearAllDustRatio);
    }

    public int expRefund(EnchantDefinition definition, int level) {
        EnchantRarity rarity = definition == null ? EnchantRarity.COMMON : definition.rarity();
        int base = switch (rarity) {
            case COMMON -> 4;
            case UNCOMMON -> 8;
            case RARE -> 16;
            case ULTIMATE -> 80;
        };
        return (int) Math.floor(base * Math.max(1, level) * clearAllExpRatio);
    }

    private Set<EnchantRarity> parseRarities(List<String> values) {
        EnumSet<EnchantRarity> result = EnumSet.noneOf(EnchantRarity.class);
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                result.add(EnchantRarity.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Unknown enchant rarity in pool: " + value);
            }
        }
        return result;
    }

    private int defaultDustPerLevel(EnchantRarity rarity) {
        return switch (rarity) {
            case COMMON -> 8;
            case UNCOMMON -> 24;
            case RARE -> 80;
            case ULTIMATE -> 500;
        };
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record RewardEntry(int weight, Set<EnchantRarity> rarities) {
    }
}
