package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import com.servercore.enchant.EnchantDefinition;
import com.servercore.enchant.EnchantDescriptionRenderer;
import com.servercore.enchant.EnchantRegistry;
import com.servercore.enchant.EnchantSettings;
import com.servercore.enchant.EnchantStatResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Final item panel renderer and vanilla rarity allocator.
 */
public class ItemFormatManager implements Listener {

    private static final int FORMAT_VERSION = 1;
    private static ItemFormatManager instance;

    private final ServerCorePlugin plugin;
    private final Map<Material, String> bundledMaterialNames = new EnumMap<>(Material.class);
    private final Map<Material, String> customMaterialNames = new EnumMap<>(Material.class);
    private final Map<String, String> customItemIdNames = new HashMap<>();

    public ItemFormatManager(ServerCorePlugin plugin) {
        this.plugin = plugin;
        instance = this;
        reloadNameMappings();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public static ItemFormatManager getInstance() {
        return instance;
    }

    public void reloadNameMappings() {
        bundledMaterialNames.clear();
        customMaterialNames.clear();
        customItemIdNames.clear();

        loadMaterialNames(loadResourceYaml("item_names_zh_cn.yml").getConfigurationSection("material_names"), bundledMaterialNames);
        ensureCustomItemNamesFile();

        File customFile = new File(plugin.getDataFolder(), "custom_item_names.yml");
        YamlConfiguration customConfig = YamlConfiguration.loadConfiguration(customFile);
        loadItemIdNames(customConfig.getConfigurationSection("item_ids"));
        loadMaterialNames(customConfig.getConfigurationSection("material_names"), customMaterialNames);

        plugin.getLogger().info("Loaded item names: " + bundledMaterialNames.size()
                + " bundled material names, " + customMaterialNames.size()
                + " custom material overrides, " + customItemIdNames.size()
                + " custom item id overrides.");
    }

    private YamlConfiguration loadResourceYaml(String resourceName) {
        try (InputStream stream = plugin.getResource(resourceName)) {
            if (stream == null) {
                plugin.getLogger().warning("Missing bundled resource: " + resourceName);
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            plugin.getLogger().warning("Unable to read bundled resource " + resourceName + ": " + exception.getMessage());
            return new YamlConfiguration();
        }
    }

    private void ensureCustomItemNamesFile() {
        File customFile = new File(plugin.getDataFolder(), "custom_item_names.yml");
        if (customFile.exists()) {
            return;
        }

        try (InputStream stream = plugin.getResource("custom_item_names.yml")) {
            if (stream != null) {
                plugin.saveResource("custom_item_names.yml", false);
                return;
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Unable to check bundled custom_item_names.yml: " + exception.getMessage());
        }

        try {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            customFile.createNewFile();
        } catch (IOException exception) {
            plugin.getLogger().warning("Unable to create custom_item_names.yml: " + exception.getMessage());
        }
    }

    private void loadItemIdNames(ConfigurationSection section) {
        if (section == null) {
            return;
        }

        for (String key : section.getKeys(false)) {
            String value = section.getString(key);
            if (value == null || value.isBlank()) {
                continue;
            }
            customItemIdNames.put(normalizeItemId(key), value.trim());
        }
    }

    private void loadMaterialNames(ConfigurationSection section, Map<Material, String> target) {
        if (section == null) {
            return;
        }

        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            String value = section.getString(key);
            if (material == null || value == null || value.isBlank()) {
                continue;
            }
            target.put(material, value.trim());
        }
    }

    public enum Rarity {
        COMMON("COMMON", "普通", NamedTextColor.WHITE, 0.4),
        UNCOMMON("UNCOMMON", "罕见", NamedTextColor.GREEN, 0.6),
        RARE("RARE", "稀有", NamedTextColor.BLUE, 0.8),
        EPIC("EPIC", "史诗", NamedTextColor.DARK_PURPLE, 1.0),
        LEGENDARY("LEGENDARY", "传说", NamedTextColor.GOLD, 1.2),
        MYTHIC("MYTHIC", "神话", NamedTextColor.AQUA, 1.5);

        private final String label;
        private final String localizedLabel;
        private final NamedTextColor color;
        private final double reforgeMultiplier;

        Rarity(String label, String localizedLabel, NamedTextColor color, double reforgeMultiplier) {
            this.label = label;
            this.localizedLabel = localizedLabel;
            this.color = color;
            this.reforgeMultiplier = reforgeMultiplier;
        }

        public String label() {
            return label;
        }

        public String localizedLabel() {
            return localizedLabel;
        }

        public NamedTextColor color() {
            return color;
        }

        public double reforgeMultiplier() {
            return reforgeMultiplier;
        }

        public Component colorize(String text) {
            return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
        }
    }

    public void assignDefaultRarity(ItemStack item) {
        if (item == null || item.getType().isAir()) return;
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer container = meta.getPersistentDataContainer();
        if (!container.has(pdc.KEY_ITEM_RARITY, PersistentDataType.STRING)) {
            container.set(pdc.KEY_ITEM_RARITY, PersistentDataType.STRING, mapVanillaRarity(item.getType()).name());
            item.setItemMeta(meta);
        }
    }

    public void setRarity(ItemStack item, Rarity rarity) {
        if (item == null || item.getType().isAir() || rarity == null) return;
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        meta.getPersistentDataContainer().set(pdc.KEY_ITEM_RARITY, PersistentDataType.STRING, rarity.name());
        item.setItemMeta(meta);
        ReforgeManager reforgeManager = ReforgeManager.getInstance();
        if (reforgeManager != null && reforgeManager.refreshReforge(item)) {
            return;
        }
        formatItem(item, true);
    }

    public Rarity getRarity(ItemStack item) {
        PDCManager pdc = PDCManager.getInstance();
        if (item == null || item.getType().isAir()) {
            return Rarity.COMMON;
        }
        if (!item.hasItemMeta() || pdc == null) {
            return mapVanillaRarity(item.getType());
        }

        String raw = item.getItemMeta().getPersistentDataContainer().get(pdc.KEY_ITEM_RARITY, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            return mapVanillaRarity(item.getType());
        }

        try {
            return Rarity.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Rarity.COMMON;
        }
    }

    public double getRarityMultiplier(ItemStack item) {
        return getRarity(item).reforgeMultiplier();
    }

    /**
     * Rewrites display name and lore from PDC state. No caller should append
     * stat lore by hand.
     */
    public void formatItem(ItemStack item) {
        formatItem(item, false);
    }

    public void formatItem(ItemStack item, boolean force) {
        if (item == null || item.getType().isAir()) return;
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer container = meta.getPersistentDataContainer();
        if (!force && !isManagedItem(item, container, pdc)) {
            return;
        }

        storeOriginalNameIfMissing(item, meta, container, pdc);
        if (!container.has(pdc.KEY_ITEM_RARITY, PersistentDataType.STRING)) {
            container.set(pdc.KEY_ITEM_RARITY, PersistentDataType.STRING, mapVanillaRarity(item.getType()).name());
        }

        Rarity rarity = readRarity(container, pdc, item.getType());
        ensureGuaranteedSocket(container, pdc, rarity, item.getType());
        String baseName = container.getOrDefault(pdc.KEY_ITEM_ORIGINAL_NAME, PersistentDataType.STRING, configuredMaterialName(item.getType()));
        String reforgePrefix = container.getOrDefault(pdc.KEY_ITEM_REFORGE_PREFIX, PersistentDataType.STRING, "");
        String displayName = reforgePrefix.isBlank() ? baseName : reforgePrefix + " " + baseName;

        meta.displayName(rarity.colorize(displayName));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);

        List<Component> lore = new ArrayList<>();
        renderStats(lore, item, pdc);
        renderGemstones(lore, container, pdc);
        addBlankIfNeeded(lore);
        renderEnchants(lore, container, pdc);
        addBlankIfNeeded(lore);
        renderAbilities(lore, container, pdc);
        addBlankIfNeeded(lore);
        lore.add(rarity.colorize(rarity.localizedLabel() + " " + getItemKind(item.getType())).decoration(TextDecoration.BOLD, true));
        renderStoryLore(lore, container, pdc);

        meta.lore(lore);
        container.set(new NamespacedKey(plugin, "item_format_version"), PersistentDataType.INTEGER, FORMAT_VERSION);
        item.setItemMeta(meta);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> formatItem(event.getItem().getItemStack()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result != null && !result.getType().isAir()) {
            formatItem(result);
            event.getInventory().setResult(result);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack result = event.getResult();
        if (result != null && !result.getType().isAir()) {
            formatItem(result);
            event.setResult(result);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> formatItem(event.getItem()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            formatInventory(event.getInventory());
            formatItem(event.getCursor());
            formatItem(player.getInventory().getItemInMainHand());
            formatItem(player.getInventory().getItemInOffHand());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> formatInventory(event.getInventory()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        formatInventory(event.getInventory());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        formatInventory(event.getInventory());
        if (event.getPlayer() instanceof Player player) {
            formatInventory(player.getInventory());
        }
    }

    public void formatInventory(Inventory inventory) {
        if (inventory == null) return;
        if (!(inventory.getHolder() instanceof Player)) return;
        for (ItemStack item : inventory.getContents()) {
            formatItem(item);
        }
    }

    private void renderStats(List<Component> lore, ItemStack item, PDCManager pdc) {
        Map<String, Double> enchantStats = resolveEnchantStats(item);
        addStat(lore, "伤害", pdc.getStat(item, pdc.KEY_BASE_DAMAGE), enchantBonus(enchantStats, pdc.KEY_BASE_DAMAGE), StatFormat.NUMBER, NamedTextColor.RED);
        addStat(lore, "增伤乘区", pdc.getStat(item, pdc.KEY_BASE_MULTIPLIER), enchantBonus(enchantStats, pdc.KEY_BASE_MULTIPLIER), StatFormat.PERCENT, NamedTextColor.GOLD);
        addStat(lore, "暴击几率", pdc.getStat(item, pdc.KEY_CRIT_CHANCE), enchantBonus(enchantStats, pdc.KEY_CRIT_CHANCE), StatFormat.PERCENT, NamedTextColor.YELLOW);
        addStat(lore, "暴击伤害", pdc.getStat(item, pdc.KEY_CRIT_DAMAGE), enchantBonus(enchantStats, pdc.KEY_CRIT_DAMAGE), StatFormat.PERCENT, NamedTextColor.YELLOW);
        addStat(lore, "残暴", pdc.getStat(item, pdc.KEY_BRUTALITY), enchantBonus(enchantStats, pdc.KEY_BRUTALITY), StatFormat.NUMBER, NamedTextColor.DARK_RED);
        addStat(lore, "吸血", pdc.getStat(item, pdc.KEY_LIFESTEAL), enchantBonus(enchantStats, pdc.KEY_LIFESTEAL), StatFormat.PERCENT, NamedTextColor.DARK_RED);
        addStat(lore, "破甲", pdc.getStat(item, pdc.KEY_ARMOR_PEN), enchantBonus(enchantStats, pdc.KEY_ARMOR_PEN), StatFormat.NUMBER, NamedTextColor.GREEN);
        addStat(lore, "护甲", pdc.getStat(item, pdc.KEY_BASE_ARMOR), enchantBonus(enchantStats, pdc.KEY_BASE_ARMOR), StatFormat.NUMBER, NamedTextColor.GREEN);
        addStat(lore, "攻速加成", pdc.getStat(item, pdc.KEY_ATTACK_SPEED_BONUS), enchantBonus(enchantStats, pdc.KEY_ATTACK_SPEED_BONUS), StatFormat.PERCENT_POINTS, NamedTextColor.AQUA);
        addStat(lore, "格挡阈值", pdc.getStat(item, pdc.KEY_SHIELD_BLOCK_THRESHOLD), enchantBonus(enchantStats, pdc.KEY_SHIELD_BLOCK_THRESHOLD), StatFormat.NUMBER, NamedTextColor.BLUE);
        addStat(lore, "有效格挡", pdc.getStat(item, pdc.KEY_SHIELD_EFFECTIVE_BLOCK), enchantBonus(enchantStats, pdc.KEY_SHIELD_EFFECTIVE_BLOCK), StatFormat.PERCENT, NamedTextColor.BLUE);
        addStat(lore, "盾牌冷却", pdc.getStat(item, pdc.KEY_SHIELD_COOLDOWN_SECONDS), enchantBonus(enchantStats, pdc.KEY_SHIELD_COOLDOWN_SECONDS), StatFormat.SECONDS, NamedTextColor.BLUE);
        addStat(lore, "力量", pdc.getStat(item, pdc.KEY_ATTR_TOUGHNESS), enchantBonus(enchantStats, pdc.KEY_ATTR_TOUGHNESS), StatFormat.NUMBER, NamedTextColor.RED);
        addStat(lore, "敏捷", pdc.getStat(item, pdc.KEY_ATTR_AGILITY), enchantBonus(enchantStats, pdc.KEY_ATTR_AGILITY), StatFormat.NUMBER, NamedTextColor.GREEN);
        addStat(lore, "智慧", pdc.getStat(item, pdc.KEY_ATTR_INTELLIGENCE), enchantBonus(enchantStats, pdc.KEY_ATTR_INTELLIGENCE), StatFormat.NUMBER, NamedTextColor.AQUA);
        addStat(lore, "意志", pdc.getStat(item, pdc.KEY_ATTR_WILLPOWER), enchantBonus(enchantStats, pdc.KEY_ATTR_WILLPOWER), StatFormat.NUMBER, NamedTextColor.YELLOW);
        addStat(lore, "幸运", pdc.getStat(item, pdc.KEY_ATTR_LUCK), enchantBonus(enchantStats, pdc.KEY_ATTR_LUCK), StatFormat.NUMBER, NamedTextColor.LIGHT_PURPLE);
        addStat(lore, "工具时运", pdc.getStat(item, pdc.KEY_TOOL_FORTUNE), enchantBonus(enchantStats, pdc.KEY_TOOL_FORTUNE), StatFormat.NUMBER, NamedTextColor.GOLD);
        addStat(lore, "采集时运", pdc.getStat(item, pdc.KEY_COLLECTION_FORTUNE), enchantBonus(enchantStats, pdc.KEY_COLLECTION_FORTUNE), StatFormat.NUMBER, NamedTextColor.GOLD);
        addStat(lore, "伐木时运", pdc.getStat(item, pdc.KEY_FORAGING_FORTUNE), enchantBonus(enchantStats, pdc.KEY_FORAGING_FORTUNE), StatFormat.NUMBER, NamedTextColor.GREEN);
        addStat(lore, "Bounty 赏金", pdc.getStat(item, pdc.KEY_BOUNTY), enchantBonus(enchantStats, pdc.KEY_BOUNTY), StatFormat.PERCENT_POINTS, NamedTextColor.DARK_GREEN);
        addStat(lore, "种植时运", pdc.getStat(item, pdc.KEY_FARMING_FORTUNE), enchantBonus(enchantStats, pdc.KEY_FARMING_FORTUNE), StatFormat.NUMBER, NamedTextColor.YELLOW);
        addStat(lore, "Overbloom 溢绽", pdc.getStat(item, pdc.KEY_OVERBLOOM), enchantBonus(enchantStats, pdc.KEY_OVERBLOOM), StatFormat.PERCENT_POINTS, NamedTextColor.GREEN);
        addStat(lore, "采掘时运", pdc.getStat(item, pdc.KEY_EXCAVATION_FORTUNE), enchantBonus(enchantStats, pdc.KEY_EXCAVATION_FORTUNE), StatFormat.NUMBER, NamedTextColor.GRAY);
        addStat(lore, "挖矿时运", pdc.getStat(item, pdc.KEY_MINING_FORTUNE), enchantBonus(enchantStats, pdc.KEY_MINING_FORTUNE), StatFormat.NUMBER, NamedTextColor.AQUA);
        addStat(lore, "连锁破坏", pdc.getStat(item, pdc.KEY_TOOL_SWEEP), enchantBonus(enchantStats, pdc.KEY_TOOL_SWEEP), StatFormat.NUMBER, NamedTextColor.GREEN);
        addStat(lore, "采集连锁", pdc.getStat(item, pdc.KEY_COLLECTION_SWEEP), enchantBonus(enchantStats, pdc.KEY_COLLECTION_SWEEP), StatFormat.NUMBER, NamedTextColor.GREEN);
        addStat(lore, "伐木连锁", pdc.getStat(item, pdc.KEY_FORAGING_SWEEP), enchantBonus(enchantStats, pdc.KEY_FORAGING_SWEEP), StatFormat.NUMBER, NamedTextColor.GREEN);
        addStat(lore, "矿物扩散", pdc.getStat(item, pdc.KEY_MINING_SPREAD), enchantBonus(enchantStats, pdc.KEY_MINING_SPREAD), StatFormat.NUMBER, NamedTextColor.AQUA);
        addStat(lore, "挖掘速度", pdc.getStat(item, pdc.KEY_TOOL_MINING_SPEED), enchantBonus(enchantStats, pdc.KEY_TOOL_MINING_SPEED), StatFormat.NUMBER, NamedTextColor.WHITE);
        addStat(lore, "破坏力", pdc.getStat(item, pdc.KEY_BREAKING_POWER), enchantBonus(enchantStats, pdc.KEY_BREAKING_POWER), StatFormat.NUMBER, NamedTextColor.RED);
        addStat(lore, "纯度", pdc.getStat(item, pdc.KEY_PURITY), enchantBonus(enchantStats, pdc.KEY_PURITY), StatFormat.NUMBER, NamedTextColor.LIGHT_PURPLE);
        addStat(lore, "晶石纯度", pdc.getStat(item, pdc.KEY_MINING_PURITY), enchantBonus(enchantStats, pdc.KEY_MINING_PURITY), StatFormat.NUMBER, NamedTextColor.LIGHT_PURPLE);
        addStat(lore, "钓鱼速度", pdc.getStat(item, pdc.KEY_FISHING_SPEED), enchantBonus(enchantStats, pdc.KEY_FISHING_SPEED), StatFormat.NUMBER, NamedTextColor.BLUE);
        addStat(lore, "海怪概率", pdc.getStat(item, pdc.KEY_SEA_CREATURE_CHANCE), enchantBonus(enchantStats, pdc.KEY_SEA_CREATURE_CHANCE), StatFormat.PERCENT_POINTS, NamedTextColor.DARK_AQUA);
        addStat(lore, "宝藏概率", pdc.getStat(item, pdc.KEY_TREASURE_CHANCE), enchantBonus(enchantStats, pdc.KEY_TREASURE_CHANCE), StatFormat.PERCENT_POINTS, NamedTextColor.GOLD);
    }

    private void addStat(List<Component> lore, String label, double value, StatFormat format, NamedTextColor color) {
        addStat(lore, label, value, 0.0, format, color);
    }

    private void addStat(List<Component> lore, String label, double value, double enchantBonus, StatFormat format, NamedTextColor color) {
        boolean hasBaseValue = Math.abs(value) >= 0.0001;
        boolean hasEnchantBonus = Math.abs(enchantBonus) >= 0.0001;
        if (!hasBaseValue && !hasEnchantBonus) return;

        Component line = Component.text(label + ": ", NamedTextColor.GRAY);
        if (hasBaseValue) {
            line = line.append(Component.text(formatSignedStat(value, format), color));
        }
        if (hasEnchantBonus) {
            if (hasBaseValue) {
                line = line.append(Component.space());
            }
            line = line.append(Component.text("(" + formatSignedStat(enchantBonus, format) + ")", NamedTextColor.BLUE));
        }
        lore.add(line
                .decoration(TextDecoration.ITALIC, false));
    }

    private Map<String, Double> resolveEnchantStats(ItemStack item) {
        EnchantStatResolver resolver = EnchantStatResolver.getInstance();
        if (resolver == null) {
            return Map.of();
        }
        return resolver.resolveNumeric(item);
    }

    private double enchantBonus(Map<String, Double> enchantStats, NamespacedKey statKey) {
        return enchantStats.getOrDefault(statKey.getKey(), 0.0);
    }

    private String formatSignedStat(double value, StatFormat format) {
        return (value >= 0.0 ? "+" : "") + renderStatValue(value, format);
    }

    private String renderStatValue(double value, StatFormat format) {
        return switch (format) {
            case PERCENT -> formatPercent(value);
            case PERCENT_POINTS -> formatNumber(value) + "%";
            case SECONDS -> formatNumber(value) + "s";
            case NUMBER -> formatNumber(value);
        };
    }

    private void renderGemstones(List<Component> lore, PersistentDataContainer container, PDCManager pdc) {
        List<String> socketTypes = splitList(container.get(pdc.KEY_ITEM_SOCKET_TYPES, PersistentDataType.STRING));
        if (socketTypes.isEmpty()) return;

        List<String> socketGems = splitList(container.get(pdc.KEY_ITEM_SOCKET_GEMS, PersistentDataType.STRING));
        Component line = Component.text("宝石槽: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
        for (int index = 0; index < socketTypes.size(); index++) {
            String gem = index < socketGems.size() ? socketGems.get(index) : "";
            GemstoneManager.SocketType socketType = parseSocketType(socketTypes.get(index));
            if (gem == null || gem.isBlank() || gem.equalsIgnoreCase("EMPTY")) {
                line = line.append(renderEmptySocket(socketType)).append(Component.space());
            } else {
                line = line.append(renderFilledSocket(gem, socketType)).append(Component.space());
            }
        }
        lore.add(line);
    }

    private Component renderEmptySocket(GemstoneManager.SocketType socketType) {
        return Component.text("[", NamedTextColor.DARK_GRAY)
                .append(Component.text(socketSymbol(socketType), NamedTextColor.DARK_GRAY))
                .append(Component.text("]", NamedTextColor.DARK_GRAY))
                .decoration(TextDecoration.ITALIC, false);
    }

    private Component renderFilledSocket(String gemstoneId, GemstoneManager.SocketType fallbackSocketType) {
        GemstoneManager gemstoneManager = GemstoneManager.getInstance();
        GemstoneManager.GemstoneDefinition gemstone = gemstoneManager == null ? null : gemstoneManager.getGemstone(gemstoneId);
        NamedTextColor bracketColor = gemstone == null ? NamedTextColor.WHITE : gemstone.rarity().color();
        NamedTextColor gemColor = gemstone == null ? NamedTextColor.WHITE : gemstone.color();
        String symbol = gemstone == null ? socketSymbol(fallbackSocketType) : socketSymbol(gemstone.socketType());

        return Component.text("[", bracketColor)
                .append(Component.text(symbol, gemColor))
                .append(Component.text("]", bracketColor))
                .decoration(TextDecoration.ITALIC, false);
    }

    private void ensureGuaranteedSocket(PersistentDataContainer container, PDCManager pdc, Rarity rarity, Material material) {
        if (!canReceiveGuaranteedSocket(material)) return;
        if (rarity.ordinal() < Rarity.RARE.ordinal()) return;
        if (container.has(pdc.KEY_ITEM_SOCKET_TYPES, PersistentDataType.STRING)) return;
        container.set(pdc.KEY_ITEM_SOCKET_TYPES, PersistentDataType.STRING, GemstoneManager.SocketType.UNIVERSAL.name());
        container.set(pdc.KEY_ITEM_SOCKET_GEMS, PersistentDataType.STRING, "EMPTY");
    }

    private boolean canReceiveGuaranteedSocket(Material material) {
        String name = material.name();
        return name.endsWith("_SWORD")
                || name.endsWith("_AXE")
                || name.endsWith("_PICKAXE")
                || name.endsWith("_SHOVEL")
                || name.endsWith("_HOE")
                || name.endsWith("_HELMET")
                || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS")
                || material == Material.BOW
                || material == Material.CROSSBOW
                || material == Material.TRIDENT
                || material == Material.SHIELD
                || material == Material.MACE;
    }

    private void renderEnchants(List<Component> lore, PersistentDataContainer container, PDCManager pdc) {
        Map<String, Integer> enchants = parseEnchantMap(container.get(pdc.KEY_ITEM_CUSTOM_ENCHANTS, PersistentDataType.STRING));
        if (enchants.isEmpty()) return;

        EnchantRegistry registry = EnchantRegistry.getInstance();
        for (Map.Entry<String, Integer> entry : enchants.entrySet()) {
            EnchantDefinition definition = registry == null ? null : registry.get(entry.getKey()).orElse(null);
            int level = definition == null ? entry.getValue() : Math.min(entry.getValue(), definition.maxLevel());
            if (definition == null) {
                EnchantSettings.LoreMode mode = registry == null ? EnchantSettings.LoreMode.SHOW_RAW : registry.settings().unknownEnchantLoreMode();
                if (mode == EnchantSettings.LoreMode.HIDE) {
                    continue;
                }
                lore.add(Component.text("[未知附魔] " + entry.getKey() + ":" + entry.getValue(), NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false));
                continue;
            }

            if (!definition.enabled()) {
                if (registry != null && registry.settings().disabledLoreMode() == EnchantSettings.LoreMode.HIDE) {
                    continue;
                }
                lore.add(Component.text("[已禁用] " + definition.display() + " " + toRoman(level), NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("此附魔当前不会生效。", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false));
                continue;
            }

            lore.add(Component.text(definition.display() + " " + toRoman(level), definition.rarity().color())
                    .decoration(TextDecoration.ITALIC, false));
            for (String description : EnchantDescriptionRenderer.render(definition, level)) {
                if (!description.isBlank()) {
                    lore.add(Component.text(description, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                }
            }
        }
    }

    private void renderAbilities(List<Component> lore, PersistentDataContainer container, PDCManager pdc) {
        List<String> abilities = splitTextBlock(container.get(pdc.KEY_ITEM_ABILITIES, PersistentDataType.STRING));
        if (abilities.isEmpty()) return;

        lore.add(Component.text("物品能力", NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false));
        for (String ability : abilities) {
            lore.add(Component.text(ability, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
    }

    private void renderStoryLore(List<Component> lore, PersistentDataContainer container, PDCManager pdc) {
        List<String> storyLines = splitTextBlock(container.get(pdc.KEY_ITEM_STORY_LORE, PersistentDataType.STRING));
        for (String storyLine : storyLines) {
            lore.add(Component.text(storyLine, NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, true));
        }
    }

    private void storeOriginalNameIfMissing(ItemStack item, ItemMeta meta, PersistentDataContainer container, PDCManager pdc) {
        String customItemName = resolveCustomItemIdName(container, pdc);
        if (customItemName != null) {
            container.set(pdc.KEY_ITEM_ORIGINAL_NAME, PersistentDataType.STRING, customItemName);
            return;
        }

        String itemId = container.get(pdc.KEY_ITEM_ID, PersistentDataType.STRING);
        if (isVanillaItemId(itemId)) {
            container.set(pdc.KEY_ITEM_ORIGINAL_NAME, PersistentDataType.STRING, configuredMaterialName(item.getType()));
            return;
        }

        String existing = container.get(pdc.KEY_ITEM_ORIGINAL_NAME, PersistentDataType.STRING);
        String previousAutoName = prettifyMaterial(item.getType());
        String legacyAutoName = legacyVanillaDisplayName(item.getType());
        String configuredAutoName = configuredMaterialName(item.getType());
        String reforgePrefix = container.getOrDefault(pdc.KEY_ITEM_REFORGE_PREFIX, PersistentDataType.STRING, "");
        if (existing != null && !existing.isBlank()) {
            String normalizedExisting = stripKnownRarityTail(existing);
            if (shouldReplaceGeneratedName(normalizedExisting, previousAutoName, legacyAutoName, configuredAutoName, reforgePrefix)) {
                container.set(pdc.KEY_ITEM_ORIGINAL_NAME, PersistentDataType.STRING, configuredAutoName);
            }
            return;
        }

        String name = null;
        if (meta.hasDisplayName() && meta.displayName() != null) {
            name = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        }
        name = stripKnownRarityTail(name == null ? "" : name);
        if (name.isBlank() || shouldReplaceGeneratedName(name, previousAutoName, legacyAutoName, configuredAutoName, reforgePrefix)) {
            name = configuredAutoName;
        }
        container.set(pdc.KEY_ITEM_ORIGINAL_NAME, PersistentDataType.STRING, name);
    }

    private String resolveCustomItemIdName(PersistentDataContainer container, PDCManager pdc) {
        String itemId = container.get(pdc.KEY_ITEM_ID, PersistentDataType.STRING);
        if (itemId == null || itemId.isBlank()) {
            return null;
        }

        String normalized = normalizeItemId(itemId);
        String mapped = customItemIdNames.get(normalized);
        if (mapped != null && !mapped.isBlank()) {
            return mapped;
        }

        CustomItemRegistry customItemRegistry = CustomItemRegistry.getInstance();
        if (customItemRegistry != null) {
            mapped = customItemRegistry.getDisplayName(normalized);
            if (mapped != null && !mapped.isBlank()) {
                return mapped;
            }
        }

        int namespaceSplit = normalized.indexOf(':');
        if (namespaceSplit >= 0 && namespaceSplit + 1 < normalized.length()) {
            String unqualified = normalized.substring(namespaceSplit + 1);
            mapped = customItemIdNames.get(unqualified);
            if (mapped != null && !mapped.isBlank()) {
                return mapped;
            }
            if (customItemRegistry != null) {
                mapped = customItemRegistry.getDisplayName(unqualified);
                if (mapped != null && !mapped.isBlank()) {
                    return mapped;
                }
            }
        }
        return null;
    }

    private String normalizeItemId(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isVanillaItemId(String raw) {
        String itemId = normalizeItemId(raw);
        return itemId.startsWith("vanilla_") || itemId.startsWith("minecraft:");
    }

    private String configuredMaterialName(Material material) {
        String customName = customMaterialNames.get(material);
        if (customName != null && !customName.isBlank()) {
            return customName;
        }

        String bundledName = bundledMaterialNames.get(material);
        if (bundledName != null && !bundledName.isBlank()) {
            return bundledName;
        }

        return prettifyMaterial(material);
    }

    private boolean shouldReplaceGeneratedName(String name, String previousAutoName, String legacyAutoName,
                                               String configuredAutoName, String reforgePrefix) {
        if (name == null || name.isBlank()) {
            return true;
        }
        if (isMinecraftTranslationKey(name)) {
            return true;
        }
        if (name.equals(previousAutoName) || name.equals(legacyAutoName) || name.equals(configuredAutoName)) {
            return true;
        }

        if (!reforgePrefix.isBlank()) {
            String prefix = reforgePrefix + " ";
            if (name.startsWith(prefix)) {
                String withoutPrefix = name.substring(prefix.length());
                return withoutPrefix.equals(previousAutoName)
                        || withoutPrefix.equals(legacyAutoName)
                        || withoutPrefix.equals(configuredAutoName)
                        || isMinecraftTranslationKey(withoutPrefix);
            }
        }

        return false;
    }

    private boolean isMinecraftTranslationKey(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("minecraft:")
                || lower.startsWith("item.minecraft.")
                || lower.startsWith("block.minecraft.");
    }

    private boolean isManagedItem(ItemStack item, PersistentDataContainer container, PDCManager pdc) {
        return container.has(pdc.KEY_ITEM_ID, PersistentDataType.STRING)
                || container.has(pdc.KEY_ACC_TYPE, PersistentDataType.STRING)
                || container.has(pdc.KEY_WEAPON_TEMPLATE, PersistentDataType.STRING)
                || container.has(pdc.KEY_WEAPON_HAND_RULE, PersistentDataType.STRING)
                || container.has(pdc.KEY_ITEM_REFORGE_ID, PersistentDataType.STRING)
                || container.has(pdc.KEY_ITEM_SOCKET_TYPES, PersistentDataType.STRING)
                || container.has(pdc.KEY_ITEM_CUSTOM_ENCHANTS, PersistentDataType.STRING)
                || hasAnyStat(container, pdc);
    }

    private boolean hasAnyStat(PersistentDataContainer container, PDCManager pdc) {
        return container.has(pdc.KEY_BASE_DAMAGE, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_BASE_MULTIPLIER, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_CRIT_CHANCE, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_CRIT_DAMAGE, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_BRUTALITY, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_LIFESTEAL, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_ARMOR_PEN, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_BASE_ARMOR, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_ATTACK_SPEED_BONUS, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_SHIELD_BLOCK_THRESHOLD, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_SHIELD_EFFECTIVE_BLOCK, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_SHIELD_COOLDOWN_SECONDS, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_ATTR_TOUGHNESS, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_ATTR_AGILITY, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_ATTR_INTELLIGENCE, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_ATTR_WILLPOWER, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_ATTR_LUCK, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_TOOL_FORTUNE, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_COLLECTION_FORTUNE, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_FORAGING_FORTUNE, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_BOUNTY, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_FARMING_FORTUNE, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_OVERBLOOM, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_EXCAVATION_FORTUNE, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_MINING_FORTUNE, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_TOOL_SWEEP, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_COLLECTION_SWEEP, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_FORAGING_SWEEP, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_FARMING_SWEEP, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_EXCAVATION_SWEEP, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_TOOL_SPREAD, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_MINING_SPREAD, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_TOOL_MINING_SPEED, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_BREAKING_POWER, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_PURITY, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_MINING_PURITY, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_FISHING_SPEED, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_SEA_CREATURE_CHANCE, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_TREASURE_CHANCE, PersistentDataType.DOUBLE);
    }

    private Rarity readRarity(PersistentDataContainer container, PDCManager pdc, Material material) {
        String raw = container.get(pdc.KEY_ITEM_RARITY, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) return mapVanillaRarity(material);
        try {
            return Rarity.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Rarity.COMMON;
        }
    }

    private Rarity mapVanillaRarity(Material material) {
        String name = material.name();
        if (name.startsWith("NETHERITE_")) {
            return Rarity.RARE;
        }
        if (name.startsWith("GOLDEN_") || name.startsWith("DIAMOND_") || name.startsWith("EMERALD_")
                || name.equals("GOLD_INGOT") || name.equals("GOLD_BLOCK")
                || name.equals("DIAMOND") || name.equals("EMERALD")) {
            return Rarity.UNCOMMON;
        }
        return Rarity.COMMON;
    }

    private String getItemKind(Material material) {
        String name = material.name();
        if (name.endsWith("_SWORD") || material == Material.MACE) return "剑";
        if (name.endsWith("_AXE")) return "斧";
        if (material == Material.BOW || material == Material.CROSSBOW) return "弓";
        if (name.endsWith("_PICKAXE") || name.endsWith("_SHOVEL") || name.endsWith("_HOE")) return "工具";
        if (name.endsWith("_HELMET")) return "头盔";
        if (name.endsWith("_CHESTPLATE")) return "胸甲";
        if (name.endsWith("_LEGGINGS")) return "护腿";
        if (name.endsWith("_BOOTS")) return "靴子";
        if (material == Material.SHIELD) return "盾牌";
        if (material == Material.TRIDENT) return "三叉戟";
        return "物品";
    }

    private GemstoneManager.SocketType parseSocketType(String raw) {
        try {
            return GemstoneManager.SocketType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return GemstoneManager.SocketType.UNIVERSAL;
        }
    }

    private String socketSymbol(GemstoneManager.SocketType socketType) {
        return switch (socketType) {
            case WEAPON -> "⚔";
            case ARMOR -> "❤";
            case TOOL -> "⛏";
            case UNIVERSAL -> "✦";
        };
    }

    private void addBlankIfNeeded(List<Component> lore) {
        if (!lore.isEmpty() && !PlainTextComponentSerializer.plainText().serialize(lore.get(lore.size() - 1)).isEmpty()) {
            lore.add(Component.empty());
        }
    }

    private List<String> splitList(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) result.add(part.trim());
        }
        return result;
    }

    private List<String> splitTextBlock(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        for (String part : raw.split("\\n|\\|\\|")) {
            if (!part.isBlank()) result.add(part.trim());
        }
        return result;
    }

    private Map<String, Integer> parseEnchantMap(String raw) {
        Map<String, Integer> enchants = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return enchants;

        for (String entry : raw.split(";")) {
            String[] parts = entry.split(":", 2);
            if (parts.length != 2 || parts[0].isBlank()) continue;
            try {
                enchants.put(parts[0].trim().toLowerCase(Locale.ROOT), Math.max(1, Integer.parseInt(parts[1].trim())));
            } catch (NumberFormatException ignored) {
                enchants.put(parts[0].trim().toLowerCase(Locale.ROOT), 1);
            }
        }
        return enchants;
    }

    private String titleCase(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String[] words = raw.toLowerCase(Locale.ROOT).replace('_', ' ').split(" ");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private String prettifyMaterial(Material material) {
        return titleCase(material.name());
    }

    private String legacyVanillaDisplayName(Material material) {
        String materialKey = material.name();
        String[] parts = materialKey.toLowerCase(Locale.ROOT).split("_");
        String tier = switch (parts[0]) {
            case "wooden" -> "木";
            case "stone" -> "石";
            case "iron" -> "铁";
            case "golden" -> "金";
            case "diamond" -> "钻石";
            case "netherite" -> "下界合金";
            case "leather" -> "皮革";
            case "chainmail" -> "锁链";
            case "turtle" -> "海龟壳";
            default -> "";
        };

        if (!tier.isBlank()) {
            if (materialKey.endsWith("_SWORD")) return tier + "剑";
            if (materialKey.endsWith("_AXE")) return tier + "斧";
            if (materialKey.endsWith("_PICKAXE")) return tier + "镐";
            if (materialKey.endsWith("_SHOVEL")) return tier + "锹";
            if (materialKey.endsWith("_HOE")) return tier + "锄";
            if (materialKey.endsWith("_HELMET")) return tier + (material == Material.TURTLE_HELMET ? "" : "头盔");
            if (materialKey.endsWith("_CHESTPLATE")) return tier + "胸甲";
            if (materialKey.endsWith("_LEGGINGS")) return tier + "护腿";
            if (materialKey.endsWith("_BOOTS")) return tier + "靴子";
        }

        return switch (material) {
            case BOW -> "弓";
            case CROSSBOW -> "弩";
            case TRIDENT -> "三叉戟";
            case SHIELD -> "盾牌";
            case MACE -> "重锤";
            case FISHING_ROD -> "钓鱼竿";
            case SHEARS -> "剪刀";
            case FLINT_AND_STEEL -> "打火石";
            default -> prettifyMaterial(material);
        };
    }

    private String stripKnownRarityTail(String name) {
        return name.replaceAll("(?i)\\s+(COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC)\\s+\\w+$", "").trim();
    }

    private String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return String.valueOf((int) Math.rint(value));
        }
        return String.format(Locale.US, "%.1f", value);
    }

    private String formatPercent(double value) {
        return String.format(Locale.US, "%.1f%%", value * 100.0);
    }

    private String toRoman(int value) {
        return switch (Math.max(1, Math.min(10, value))) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> String.valueOf(value);
        };
    }

    private enum StatFormat {
        NUMBER,
        PERCENT,
        PERCENT_POINTS,
        SECONDS
    }
}
