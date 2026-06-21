package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class VanillaItemOverrideManager implements Listener {

    private final ServerCorePlugin plugin;
    private final File configFile;
    private final Map<Material, ItemOverride> overrides = new LinkedHashMap<>();

    public VanillaItemOverrideManager(ServerCorePlugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "vanilla_item_overrides.yml");
        reload();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                applyInventory(player);
            }
        });
    }

    public int reload() {
        ensureConfigFile();
        loadConfig();
        return overrides.size();
    }

    public List<String> getOverrideIds() {
        return overrides.values().stream().map(ItemOverride::id).toList();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (apply(result)) {
            event.getInventory().setResult(result);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player && apply(event.getItem().getItemStack())) {
            event.getItem().setItemStack(event.getItem().getItemStack());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        ItemStack current = event.getCurrentItem();
        if (current != null) {
            apply(current);
        }
        ItemStack cursor = event.getCursor();
        if (cursor != null) {
            apply(cursor);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            plugin.getServer().getScheduler().runTask(plugin, () -> applyInventory(player));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> applyInventory(event.getPlayer()));
    }

    public void applyInventory(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            apply(item);
        }
        ItemStack[] armor = player.getInventory().getArmorContents();
        for (ItemStack item : armor) {
            apply(item);
        }
        player.getInventory().setArmorContents(armor);
        apply(player.getInventory().getItemInOffHand());
    }

    public boolean apply(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }

        ItemOverride override = overrides.get(item.getType());
        if (override == null || !override.enabled()) {
            return false;
        }

        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        PersistentDataContainer container = meta.getPersistentDataContainer();
        String itemId = container.get(pdc.KEY_ITEM_ID, PersistentDataType.STRING);
        if (itemId != null && !itemId.startsWith("vanilla_")) {
            return false;
        }

        String appliedOverride = container.get(pdc.KEY_ITEM_OVERRIDE_ID, PersistentDataType.STRING);
        if (appliedOverride != null && appliedOverride.equals("skip:" + override.id())) {
            return false;
        }
        boolean alreadyApplied = override.id().equals(appliedOverride);
        if (alreadyApplied) {
            return false;
        }
        if (hasPlayerCustomization(container, pdc)) {
            return false;
        }

        if (override.customItemId() != null && !override.customItemId().isBlank()) {
            if (ThreadLocalRandom.current().nextDouble() > override.chance()) {
                container.set(pdc.KEY_ITEM_OVERRIDE_ID, PersistentDataType.STRING, "skip:" + override.id());
                item.setItemMeta(meta);
                return false;
            }
            if (replaceWithCustomItem(item, override)) {
                return true;
            }
        }

        if (override.stats().isEmpty()) {
            return false;
        }

        if (!alreadyApplied && ThreadLocalRandom.current().nextDouble() > override.chance()) {
            container.set(pdc.KEY_ITEM_OVERRIDE_ID, PersistentDataType.STRING, "skip:" + override.id());
            item.setItemMeta(meta);
            return false;
        }

        for (Map.Entry<String, Double> stat : override.stats().entrySet()) {
            NamespacedKey key = statKey(pdc, stat.getKey());
            if (key != null) {
                container.set(key, PersistentDataType.DOUBLE, stat.getValue());
            }
        }
        if (itemId == null || itemId.isBlank()) {
            container.set(pdc.KEY_ITEM_ID, PersistentDataType.STRING, "vanilla_" + item.getType().name().toLowerCase(Locale.ROOT));
        }
        container.set(pdc.KEY_ITEM_OVERRIDE_ID, PersistentDataType.STRING, override.id());
        item.setItemMeta(meta);

        ItemFormatManager formatManager = ItemFormatManager.getInstance();
        if (formatManager != null) {
            formatManager.formatItem(item, true);
        }
        return true;
    }

    private boolean hasPlayerCustomization(PersistentDataContainer container, PDCManager pdc) {
        return container.has(pdc.KEY_ITEM_REFORGE_ID, PersistentDataType.STRING)
                || container.has(pdc.KEY_ITEM_REFORGE_STATS, PersistentDataType.STRING)
                || container.has(pdc.KEY_ITEM_SOCKET_TYPES, PersistentDataType.STRING)
                || container.has(pdc.KEY_ITEM_SOCKET_GEMS, PersistentDataType.STRING)
                || container.has(pdc.KEY_ITEM_CUSTOM_ENCHANTS, PersistentDataType.STRING)
                || container.has(pdc.KEY_ITEM_ABILITIES, PersistentDataType.STRING)
                || container.has(pdc.KEY_ITEM_STORY_LORE, PersistentDataType.STRING)
                || container.has(pdc.KEY_ITEM_ORIGINAL_NAME, PersistentDataType.STRING);
    }

    private boolean replaceWithCustomItem(ItemStack target, ItemOverride override) {
        CustomItemRegistry registry = CustomItemRegistry.getInstance();
        if (registry == null) {
            return false;
        }

        ItemStack replacement = registry.createItem(override.customItemId(), target.getAmount());
        if (replacement == null || replacement.getType().isAir()) {
            plugin.getLogger().warning("Vanilla item override '" + override.id() + "' references unknown custom item '" + override.customItemId() + "'.");
            return false;
        }

        target.setType(replacement.getType());
        target.setAmount(replacement.getAmount());
        target.setItemMeta(replacement.getItemMeta());
        return true;
    }

    private void loadConfig() {
        overrides.clear();

        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        ConfigurationSection root = config.getConfigurationSection("items");
        if (root == null) {
            root = config;
        }

        for (String id : root.getKeys(false)) {
            if (!root.isConfigurationSection(id)) {
                continue;
            }
            ItemOverride override = parseOverride(id, root.getConfigurationSection(id));
            if (override != null) {
                overrides.put(override.material(), override);
            }
        }

        plugin.getLogger().info("Loaded " + overrides.size() + " vanilla item override(s).");
    }

    private ItemOverride parseOverride(String id, ConfigurationSection section) {
        Material material = Material.matchMaterial(firstPresentString(section, "material", "source", "from", "type", "item"));
        if (material == null || material.isAir()) {
            material = Material.matchMaterial(id);
        }
        if (material == null || material.isAir()) {
            plugin.getLogger().warning("Skipped vanilla item override '" + id + "' because it has an unknown material.");
            return null;
        }

        return new ItemOverride(
                normalize(id),
                section.getBoolean("enabled", true),
                material,
                firstPresentString(section, "custom_item", "custom_item_id", "replacement_item", "to"),
                parseChance(section.get("chance"), 1.0),
                readStats(section.getConfigurationSection("stats"))
        );
    }

    private Map<String, Double> readStats(ConfigurationSection section) {
        Map<String, Double> stats = new LinkedHashMap<>();
        if (section == null) {
            return stats;
        }

        for (String key : section.getKeys(false)) {
            if (section.isDouble(key) || section.isInt(key)) {
                stats.put(normalizeStatName(key), section.getDouble(key));
            }
        }
        return stats;
    }

    private NamespacedKey statKey(PDCManager pdc, String statName) {
        return switch (normalizeStatName(statName)) {
            case "base_damage" -> pdc.KEY_BASE_DAMAGE;
            case "base_multiplier" -> pdc.KEY_BASE_MULTIPLIER;
            case "crit_chance" -> pdc.KEY_CRIT_CHANCE;
            case "crit_damage" -> pdc.KEY_CRIT_DAMAGE;
            case "brutality" -> pdc.KEY_BRUTALITY;
            case "lifesteal" -> pdc.KEY_LIFESTEAL;
            case "armor_pen" -> pdc.KEY_ARMOR_PEN;
            case "base_armor" -> pdc.KEY_BASE_ARMOR;
            case "max_health" -> pdc.KEY_MAX_HEALTH;
            case "attack_speed_bonus" -> pdc.KEY_ATTACK_SPEED_BONUS;
            case "shield_block_threshold" -> pdc.KEY_SHIELD_BLOCK_THRESHOLD;
            case "shield_effective_block" -> pdc.KEY_SHIELD_EFFECTIVE_BLOCK;
            case "shield_cooldown_seconds" -> pdc.KEY_SHIELD_COOLDOWN_SECONDS;
            case "attr_toughness" -> pdc.KEY_ATTR_TOUGHNESS;
            case "attr_agility" -> pdc.KEY_ATTR_AGILITY;
            case "attr_intelligence" -> pdc.KEY_ATTR_INTELLIGENCE;
            case "attr_willpower" -> pdc.KEY_ATTR_WILLPOWER;
            case "attr_luck" -> pdc.KEY_ATTR_LUCK;
            case "tool_fortune" -> pdc.KEY_TOOL_FORTUNE;
            case "collection_fortune" -> pdc.KEY_COLLECTION_FORTUNE;
            case "foraging_fortune" -> pdc.KEY_FORAGING_FORTUNE;
            case "farming_fortune" -> pdc.KEY_FARMING_FORTUNE;
            case "excavation_fortune" -> pdc.KEY_EXCAVATION_FORTUNE;
            case "mining_fortune" -> pdc.KEY_MINING_FORTUNE;
            case "tool_sweep" -> pdc.KEY_TOOL_SWEEP;
            case "collection_sweep" -> pdc.KEY_COLLECTION_SWEEP;
            case "foraging_sweep" -> pdc.KEY_FORAGING_SWEEP;
            case "mining_spread" -> pdc.KEY_MINING_SPREAD;
            case "tool_mining_speed" -> pdc.KEY_TOOL_MINING_SPEED;
            case "breaking_power" -> pdc.KEY_BREAKING_POWER;
            case "purity" -> pdc.KEY_PURITY;
            case "mining_purity" -> pdc.KEY_MINING_PURITY;
            case "fishing_speed" -> pdc.KEY_FISHING_SPEED;
            case "sea_creature_chance" -> pdc.KEY_SEA_CREATURE_CHANCE;
            case "treasure_chance" -> pdc.KEY_TREASURE_CHANCE;
            case "bounty" -> pdc.KEY_BOUNTY;
            case "overbloom" -> pdc.KEY_OVERBLOOM;
            default -> null;
        };
    }

    private String normalizeStatName(String raw) {
        if (raw == null) {
            return "";
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "damage", "base_damage" -> "base_damage";
            case "mult", "multiplier", "base_multiplier" -> "base_multiplier";
            case "crit", "crit_chance" -> "crit_chance";
            case "critdmg", "crit_damage" -> "crit_damage";
            case "brutality" -> "brutality";
            case "lifesteal", "life_steal", "vampirism", "vamp" -> "lifesteal";
            case "armorpen", "armor_pen" -> "armor_pen";
            case "armor", "base_armor" -> "base_armor";
            case "hp", "health", "maxhp", "max_health" -> "max_health";
            case "attackspeed", "attack_speed", "attack_speed_bonus", "aspeed" -> "attack_speed_bonus";
            case "shieldthreshold", "shield_threshold", "block_threshold", "shield_block_threshold" -> "shield_block_threshold";
            case "effectiveblock", "effective_block", "shield_effective_block" -> "shield_effective_block";
            case "shieldcooldown", "shield_cooldown", "shield_cooldown_seconds" -> "shield_cooldown_seconds";
            case "str", "strength", "attr_strength", "tou", "toughness", "attr_toughness" -> "attr_toughness";
            case "agi", "agility", "attr_agility" -> "attr_agility";
            case "int", "intelligence", "attr_intelligence" -> "attr_intelligence";
            case "wil", "will", "willpower", "attr_willpower" -> "attr_willpower";
            case "luk", "luck", "attr_luck" -> "attr_luck";
            case "toolfortune", "fortune", "tool_fortune" -> "tool_fortune";
            case "collectionfortune", "gatherfortune", "collection_fortune" -> "collection_fortune";
            case "foragingfortune", "foraging_fortune" -> "foraging_fortune";
            case "farmingfortune", "farming_fortune" -> "farming_fortune";
            case "excavationfortune", "excavation_fortune" -> "excavation_fortune";
            case "miningfortune", "mining_fortune" -> "mining_fortune";
            case "toolsweep", "sweep", "tool_sweep" -> "tool_sweep";
            case "collectionsweep", "gathersweep", "collection_sweep" -> "collection_sweep";
            case "foragingsweep", "foraging_sweep" -> "foraging_sweep";
            case "toolspread", "spread", "tool_spread", "miningspread", "mining_spread" -> "mining_spread";
            case "miningspeed", "toolminingspeed", "tool_mining_speed" -> "tool_mining_speed";
            case "breakingpower", "bp", "breaking_power" -> "breaking_power";
            case "purity" -> "purity";
            case "miningpurity", "mining_purity" -> "mining_purity";
            case "fishingspeed", "fishing_speed" -> "fishing_speed";
            case "seacreaturechance", "sea_creature_chance" -> "sea_creature_chance";
            case "treasurechance", "treasure_chance" -> "treasure_chance";
            case "bounty", "foragingbounty", "foraging_bounty" -> "bounty";
            case "overbloom", "over_bloom", "bloom" -> "overbloom";
            default -> "";
        };
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
            plugin.getLogger().warning("Could not create plugin data folder for vanilla_item_overrides.yml.");
            return;
        }

        if (plugin.getResource("vanilla_item_overrides.yml") != null) {
            plugin.saveResource("vanilla_item_overrides.yml", false);
            return;
        }

        YamlConfiguration config = new YamlConfiguration();
        config.set("items.netherite_pickaxe.material", "NETHERITE_PICKAXE");
        config.set("items.netherite_pickaxe.stats.mining_spread", 1);
        try {
            config.save(configFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not create vanilla_item_overrides.yml: " + exception.getMessage());
        }
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).trim();
    }

    private record ItemOverride(
            String id,
            boolean enabled,
            Material material,
            String customItemId,
            double chance,
            Map<String, Double> stats
    ) {
    }
}
