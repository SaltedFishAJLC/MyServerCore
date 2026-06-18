package com.servercore.enchant;

import com.servercore.ServerCorePlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class EnchantPoolRegistry {

    private final ServerCorePlugin plugin;
    private boolean vanillaEnchantTableEnabled = true;
    private final List<RewardEntry> vanillaRewards = new ArrayList<>();
    private final Set<EnchantRarity> vanillaBlockedRarities = EnumSet.of(EnchantRarity.ULTIMATE);
    private boolean specialEnchantTableEnabled = false;
    private final Set<EnchantRarity> specialTableAllowedRarities = EnumSet.noneOf(EnchantRarity.class);
    private boolean specialTableAllowChooseLevel = true;
    private final Map<EnchantRarity, SpecialEnchantCost> specialTableCosts = new EnumMap<>(EnchantRarity.class);
    private boolean npcBooksEnabled = false;
    private final List<NpcBookPool> npcBookPools = new ArrayList<>();
    private final Map<EnchantRarity, Integer> grindstoneDustPerLevel = new EnumMap<>(EnchantRarity.class);
    private final Map<EnchantRarity, GrindstoneRemoveRule> grindstoneRemoveRules = new EnumMap<>(EnchantRarity.class);
    private double clearAllDustRatio = 0.35;
    private double clearAllExpRatio = 0.25;

    public EnchantPoolRegistry(ServerCorePlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        vanillaRewards.clear();
        vanillaBlockedRarities.clear();
        specialEnchantTableEnabled = false;
        specialTableAllowChooseLevel = true;
        specialTableAllowedRarities.clear();
        specialTableCosts.clear();
        npcBooksEnabled = false;
        npcBookPools.clear();
        grindstoneDustPerLevel.clear();
        grindstoneRemoveRules.clear();

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

        ConfigurationSection specialTable = config.getConfigurationSection("special_enchant_table");
        if (specialTable != null) {
            specialEnchantTableEnabled = specialTable.getBoolean("enabled", false);
            specialTableAllowedRarities.addAll(parseRarities(specialTable.getStringList("allowed_rarities")));
            specialTableAllowChooseLevel = specialTable.getBoolean("allow_choose_level", true);
            ConfigurationSection costs = specialTable.getConfigurationSection("cost");
            for (EnchantRarity rarity : EnchantRarity.values()) {
                ConfigurationSection cost = costs == null ? null : costs.getConfigurationSection(rarity.name());
                int dustPerLevelSquare = cost == null
                        ? defaultSpecialDustPerLevelSquare(rarity)
                        : cost.getInt("dust_per_level_square", defaultSpecialDustPerLevelSquare(rarity));
                int expPerLevel = cost == null
                        ? defaultSpecialExpPerLevel(rarity)
                        : cost.getInt("exp_per_level", defaultSpecialExpPerLevel(rarity));
                specialTableCosts.put(rarity, new SpecialEnchantCost(Math.max(0, dustPerLevelSquare), Math.max(0, expPerLevel)));
            }
        }
        if (specialTableAllowedRarities.isEmpty()) {
            specialTableAllowedRarities.add(EnchantRarity.COMMON);
            specialTableAllowedRarities.add(EnchantRarity.UNCOMMON);
        }

        ConfigurationSection npcBooks = config.getConfigurationSection("npc_books");
        if (npcBooks != null) {
            npcBooksEnabled = npcBooks.getBoolean("enabled", false);
            ConfigurationSection pools = npcBooks.getConfigurationSection("pools");
            if (pools != null) {
                for (String key : pools.getKeys(false)) {
                    ConfigurationSection pool = pools.getConfigurationSection(key);
                    if (pool == null) {
                        continue;
                    }
                    Set<EnchantRarity> rarities = parseRarities(pool.getStringList("rarities"));
                    if (rarities.isEmpty()) {
                        plugin.getLogger().warning("Skipped npc_books pool '" + key + "' because it has no valid rarities.");
                        continue;
                    }
                    int entries = Math.max(1, pool.getInt("entries", defaultNpcBookEntries(rarities)));
                    int refreshHours = Math.max(1, pool.getInt("refresh_hours", 24));
                    int minLevel = Math.max(1, pool.getInt("level_min", pool.getInt("min_level", 1)));
                    int maxLevel = Math.max(minLevel, pool.getInt("level_max", pool.getInt("max_level", 0)));
                    int dustPerLevel = Math.max(0, pool.getInt("dust_per_level",
                            pool.getInt("cost.dust_per_level", defaultNpcBookDustPerLevel(rarities))));
                    int expPerLevel = Math.max(0, pool.getInt("exp_per_level",
                            pool.getInt("cost.exp_per_level", defaultNpcBookExpPerLevel(rarities))));
                    npcBookPools.add(new NpcBookPool(key, rarities, entries, refreshHours, minLevel, maxLevel, dustPerLevel, expPerLevel));
                }
            }
        }

        ConfigurationSection grindstone = config.getConfigurationSection("grindstone");
        if (grindstone != null) {
            ConfigurationSection removeSingle = grindstone.getConfigurationSection("remove_single");
            if (removeSingle != null) {
                for (EnchantRarity rarity : EnchantRarity.values()) {
                    ConfigurationSection raritySection = removeSingle.getConfigurationSection(rarity.name());
                    int dust = raritySection == null
                            ? removeSingle.getInt(rarity.name() + ".dust_per_level", defaultDustPerLevel(rarity))
                            : raritySection.getInt("dust_per_level", defaultDustPerLevel(rarity));
                    boolean requireSpecialMaterial = raritySection != null && raritySection.getBoolean("require_special_material", false);
                    String specialMaterialItemId = raritySection == null
                            ? defaultSpecialMaterialItemId(rarity)
                            : raritySection.getString("special_material_item_id", defaultSpecialMaterialItemId(rarity));
                    int specialMaterialAmount = raritySection == null
                            ? defaultSpecialMaterialAmount(rarity)
                            : raritySection.getInt("special_material_amount", defaultSpecialMaterialAmount(rarity));
                    grindstoneDustPerLevel.put(rarity, Math.max(0, dust));
                    grindstoneRemoveRules.put(rarity, new GrindstoneRemoveRule(
                            Math.max(0, dust),
                            requireSpecialMaterial,
                            normalizeSpecialItemId(specialMaterialItemId),
                            Math.max(1, specialMaterialAmount)
                    ));
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

    public boolean isSpecialEnchantTableEnabled() {
        return specialEnchantTableEnabled;
    }

    public boolean isSpecialTableLevelChoiceAllowed() {
        return specialTableAllowChooseLevel;
    }

    public boolean isAllowedAtSpecialTable(EnchantDefinition definition) {
        return definition != null
                && definition.enabled()
                && specialTableAllowedRarities.contains(definition.rarity());
    }

    public int specialDustCost(EnchantDefinition definition, int level) {
        EnchantRarity rarity = definition == null ? EnchantRarity.COMMON : definition.rarity();
        SpecialEnchantCost cost = specialTableCosts.getOrDefault(rarity,
                new SpecialEnchantCost(defaultSpecialDustPerLevelSquare(rarity), defaultSpecialExpPerLevel(rarity)));
        int safeLevel = Math.max(1, level);
        return cost.dustPerLevelSquare() * safeLevel * safeLevel;
    }

    public int specialExpLevelCost(EnchantDefinition definition, int level) {
        EnchantRarity rarity = definition == null ? EnchantRarity.COMMON : definition.rarity();
        SpecialEnchantCost cost = specialTableCosts.getOrDefault(rarity,
                new SpecialEnchantCost(defaultSpecialDustPerLevelSquare(rarity), defaultSpecialExpPerLevel(rarity)));
        return cost.expPerLevel() * Math.max(1, level);
    }

    public boolean isNpcBooksEnabled() {
        return npcBooksEnabled;
    }

    public List<NpcBookOffer> currentNpcBookOffers() {
        if (!npcBooksEnabled || npcBookPools.isEmpty()) {
            return List.of();
        }

        EnchantRegistry registry = EnchantRegistry.getInstance();
        if (registry == null) {
            return List.of();
        }

        long now = System.currentTimeMillis();
        List<NpcBookOffer> offers = new ArrayList<>();
        for (NpcBookPool pool : npcBookPools) {
            List<EnchantDefinition> candidates = new ArrayList<>();
            for (EnchantDefinition definition : registry.getEnabledDefinitions()) {
                if (pool.rarities().contains(definition.rarity())) {
                    candidates.add(definition);
                }
            }
            if (candidates.isEmpty()) {
                continue;
            }

            long windowMillis = Math.max(1L, pool.refreshHours()) * 60L * 60L * 1000L;
            long window = now / windowMillis;
            long refreshAt = (window + 1L) * windowMillis;
            Random random = new Random(((long) pool.id().hashCode() << 32) ^ window);
            Collections.shuffle(candidates, random);

            int count = Math.min(pool.entries(), candidates.size());
            for (int i = 0; i < count; i++) {
                EnchantDefinition definition = candidates.get(i);
                int levelMax = pool.maxLevel() <= 0 ? definition.maxLevel() : Math.min(pool.maxLevel(), definition.maxLevel());
                int minLevel = Math.min(pool.minLevel(), levelMax);
                int level = minLevel == levelMax ? minLevel : minLevel + random.nextInt(levelMax - minLevel + 1);
                offers.add(new NpcBookOffer(
                        pool.id(),
                        definition,
                        level,
                        pool.dustPerLevel() * level,
                        pool.expPerLevel() * level,
                        refreshAt
                ));
            }
        }
        return List.copyOf(offers);
    }

    public int dustRefund(EnchantDefinition definition, int level) {
        EnchantRarity rarity = definition == null ? EnchantRarity.COMMON : definition.rarity();
        int perLevel = grindstoneDustPerLevel.getOrDefault(rarity, defaultDustPerLevel(rarity));
        return (int) Math.floor(perLevel * Math.max(1, level) * clearAllDustRatio);
    }

    public int singleRemoveDustCost(EnchantDefinition definition, int level) {
        EnchantRarity rarity = definition == null ? EnchantRarity.COMMON : definition.rarity();
        GrindstoneRemoveRule rule = grindstoneRemoveRules.getOrDefault(rarity, defaultGrindstoneRemoveRule(rarity));
        return rule.dustPerLevel() * Math.max(1, level);
    }

    public boolean singleRemoveRequiresSpecialMaterial(EnchantDefinition definition) {
        EnchantRarity rarity = definition == null ? EnchantRarity.COMMON : definition.rarity();
        return grindstoneRemoveRules.getOrDefault(rarity, defaultGrindstoneRemoveRule(rarity)).requireSpecialMaterial();
    }

    public String singleRemoveSpecialMaterialItemId(EnchantDefinition definition) {
        EnchantRarity rarity = definition == null ? EnchantRarity.COMMON : definition.rarity();
        return grindstoneRemoveRules.getOrDefault(rarity, defaultGrindstoneRemoveRule(rarity)).specialMaterialItemId();
    }

    public int singleRemoveSpecialMaterialAmount(EnchantDefinition definition) {
        EnchantRarity rarity = definition == null ? EnchantRarity.COMMON : definition.rarity();
        return grindstoneRemoveRules.getOrDefault(rarity, defaultGrindstoneRemoveRule(rarity)).specialMaterialAmount();
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

    private int defaultSpecialDustPerLevelSquare(EnchantRarity rarity) {
        return switch (rarity) {
            case COMMON -> 4;
            case UNCOMMON -> 12;
            case RARE -> 40;
            case ULTIMATE -> 300;
        };
    }

    private int defaultSpecialExpPerLevel(EnchantRarity rarity) {
        return switch (rarity) {
            case COMMON -> 5;
            case UNCOMMON -> 8;
            case RARE -> 20;
            case ULTIMATE -> 80;
        };
    }

    private int defaultNpcBookEntries(Set<EnchantRarity> rarities) {
        return rarities.contains(EnchantRarity.ULTIMATE) ? 1 : 3;
    }

    private int defaultNpcBookDustPerLevel(Set<EnchantRarity> rarities) {
        if (rarities.contains(EnchantRarity.ULTIMATE)) {
            return 1200;
        }
        if (rarities.contains(EnchantRarity.RARE)) {
            return 180;
        }
        if (rarities.contains(EnchantRarity.UNCOMMON)) {
            return 60;
        }
        return 20;
    }

    private int defaultNpcBookExpPerLevel(Set<EnchantRarity> rarities) {
        if (rarities.contains(EnchantRarity.ULTIMATE)) {
            return 80;
        }
        if (rarities.contains(EnchantRarity.RARE)) {
            return 24;
        }
        if (rarities.contains(EnchantRarity.UNCOMMON)) {
            return 12;
        }
        return 6;
    }

    private int defaultDustPerLevel(EnchantRarity rarity) {
        return switch (rarity) {
            case COMMON -> 8;
            case UNCOMMON -> 24;
            case RARE -> 80;
            case ULTIMATE -> 500;
        };
    }

    private GrindstoneRemoveRule defaultGrindstoneRemoveRule(EnchantRarity rarity) {
        return new GrindstoneRemoveRule(
                defaultDustPerLevel(rarity),
                rarity == EnchantRarity.ULTIMATE,
                defaultSpecialMaterialItemId(rarity),
                defaultSpecialMaterialAmount(rarity)
        );
    }

    private String defaultSpecialMaterialItemId(EnchantRarity rarity) {
        return rarity == EnchantRarity.ULTIMATE ? "ultimate_enchant_catalyst" : "";
    }

    private int defaultSpecialMaterialAmount(EnchantRarity rarity) {
        return rarity == EnchantRarity.ULTIMATE ? 1 : 0;
    }

    private String normalizeSpecialItemId(String itemId) {
        return itemId == null ? "" : itemId.trim().toLowerCase(Locale.ROOT);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record RewardEntry(int weight, Set<EnchantRarity> rarities) {
    }

    private record SpecialEnchantCost(int dustPerLevelSquare, int expPerLevel) {
    }

    private record GrindstoneRemoveRule(
            int dustPerLevel,
            boolean requireSpecialMaterial,
            String specialMaterialItemId,
            int specialMaterialAmount
    ) {
    }

    private record NpcBookPool(
            String id,
            Set<EnchantRarity> rarities,
            int entries,
            int refreshHours,
            int minLevel,
            int maxLevel,
            int dustPerLevel,
            int expPerLevel
    ) {
        private NpcBookPool {
            id = id == null || id.isBlank() ? "rotation" : id;
            rarities = rarities == null ? Set.of() : Set.copyOf(new HashSet<>(rarities));
        }
    }

    public record NpcBookOffer(
            String poolId,
            EnchantDefinition definition,
            int level,
            int dustCost,
            int expLevelCost,
            long refreshesAtEpochMillis
    ) {
    }
}
