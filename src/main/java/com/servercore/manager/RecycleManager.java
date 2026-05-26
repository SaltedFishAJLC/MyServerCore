package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Chest-GUI recycling station. Epic and better items require two clicks with
 * unchanged contents before they are recycled.
 */
public class RecycleManager implements Listener {

    private static final int GUI_SIZE = 54;
    private static final int BUTTON_SLOT = 49;
    private static final int INPUT_END_EXCLUSIVE = 45;

    private final ServerCorePlugin plugin;
    private final EconomyManager economyManager;
    private final File configFile;
    private final Map<ItemFormatManager.Rarity, Long> rarityPrices = new EnumMap<>(ItemFormatManager.Rarity.class);
    private final Map<String, Long> itemIdPrices = new HashMap<>();
    private final Map<Material, Long> materialPrices = new EnumMap<>(Material.class);

    public RecycleManager(ServerCorePlugin plugin, EconomyManager economyManager) {
        this.plugin = plugin;
        this.economyManager = economyManager;
        this.configFile = new File(plugin.getDataFolder(), "recycle.yml");
        reloadConfig();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void reloadConfig() {
        rarityPrices.clear();
        itemIdPrices.clear();
        materialPrices.clear();

        loadDefaultPrices();
        ensureConfigFile();

        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        loadRarityPrices(config.getConfigurationSection("prices.rarity"));
        loadItemIdPrices(config.getConfigurationSection("prices.item_ids"));
        loadMaterialPrices(config.getConfigurationSection("prices.materials"));

        plugin.getLogger().info("Loaded recycle prices: " + itemIdPrices.size()
                + " item id override(s), " + materialPrices.size() + " material override(s).");
    }

    public void openRecycleGui(Player player) {
        RecycleHolder holder = new RecycleHolder();
        Inventory inventory = Bukkit.createInventory(holder, GUI_SIZE, Component.text("物品回收"));
        holder.setInventory(inventory);

        ItemStack filler = namedItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = INPUT_END_EXCLUSIVE; slot < GUI_SIZE; slot++) {
            inventory.setItem(slot, filler);
        }
        refreshButton(holder);
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof RecycleHolder holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }

        int rawSlot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        if (rawSlot < topSize) {
            if (rawSlot == BUTTON_SLOT) {
                event.setCancelled(true);
                handleRecycleClick(player, holder);
                return;
            }
            if (!isInputSlot(rawSlot)) {
                event.setCancelled(true);
                return;
            }

            holder.resetProtection();
            scheduleButtonRefresh(holder);
            return;
        }

        if (event.getClick().isShiftClick()) {
            event.setCancelled(true);
            moveShiftClickedItem(event, holder);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof RecycleHolder holder)) {
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= topSize) {
                continue;
            }
            if (!isInputSlot(rawSlot)) {
                event.setCancelled(true);
                return;
            }
        }

        holder.resetProtection();
        scheduleButtonRefresh(holder);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof RecycleHolder)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        for (int slot = 0; slot < INPUT_END_EXCLUSIVE; slot++) {
            ItemStack item = event.getInventory().getItem(slot);
            if (isEmpty(item)) {
                continue;
            }
            returnItem(player, item);
            event.getInventory().setItem(slot, null);
        }
    }

    private void handleRecycleClick(Player player, RecycleHolder holder) {
        RecycleQuote quote = calculateQuote(holder.getInventory());
        if (quote.itemCount() <= 0) {
            player.sendMessage(Component.text("放入要回收的物品后再点击回收。", NamedTextColor.YELLOW));
            refreshButton(holder);
            return;
        }

        if (quote.hasProtectedItems()) {
            String signature = inventorySignature(holder.getInventory());
            if (!holder.isProtectionConfirmed(signature)) {
                holder.awaitProtectionConfirmation(signature);
                refreshButton(holder);
                player.sendMessage(Component.text("检测到史诗及以上物品。再次点击回收按钮才会确认回收。", NamedTextColor.GOLD));
                return;
            }
        }

        for (int slot = 0; slot < INPUT_END_EXCLUSIVE; slot++) {
            holder.getInventory().setItem(slot, null);
        }
        holder.resetProtection();
        economyManager.addBalance(player.getUniqueId(), quote.totalValue());
        player.sendMessage(Component.text("回收完成，获得 " + quote.totalValue() + " Coins。", NamedTextColor.GREEN));
        refreshButton(holder);
    }

    private RecycleQuote calculateQuote(Inventory inventory) {
        long total = 0L;
        int itemCount = 0;
        boolean hasProtectedItems = false;

        for (int slot = 0; slot < INPUT_END_EXCLUSIVE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (isEmpty(item)) {
                continue;
            }

            long unitPrice = getUnitPrice(item);
            total = saturatingAdd(total, saturatingMultiply(unitPrice, item.getAmount()));
            itemCount += item.getAmount();
            if (isProtected(item)) {
                hasProtectedItems = true;
            }
        }
        return new RecycleQuote(total, itemCount, hasProtectedItems);
    }

    private long getUnitPrice(ItemStack item) {
        String itemId = readItemId(item);
        if (itemId != null && !itemId.isBlank()) {
            Long price = itemIdPrices.get(normalizeId(itemId));
            if (price != null) {
                return Math.max(0L, price);
            }

            int namespaceSplit = itemId.indexOf(':');
            if (namespaceSplit >= 0 && namespaceSplit + 1 < itemId.length()) {
                price = itemIdPrices.get(normalizeId(itemId.substring(namespaceSplit + 1)));
                if (price != null) {
                    return Math.max(0L, price);
                }
            }
        }

        Long materialPrice = materialPrices.get(item.getType());
        if (materialPrice != null) {
            return Math.max(0L, materialPrice);
        }

        ItemFormatManager formatManager = ItemFormatManager.getInstance();
        ItemFormatManager.Rarity rarity = formatManager == null
                ? ItemFormatManager.Rarity.COMMON
                : formatManager.getRarity(item);
        return Math.max(0L, rarityPrices.getOrDefault(rarity, 1L));
    }

    private boolean isProtected(ItemStack item) {
        ItemFormatManager formatManager = ItemFormatManager.getInstance();
        ItemFormatManager.Rarity rarity = formatManager == null
                ? ItemFormatManager.Rarity.COMMON
                : formatManager.getRarity(item);
        return rarity.ordinal() >= ItemFormatManager.Rarity.EPIC.ordinal();
    }

    private String readItemId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(pdc.KEY_ITEM_ID, PersistentDataType.STRING);
    }

    private void moveShiftClickedItem(InventoryClickEvent event, RecycleHolder holder) {
        ItemStack clicked = event.getCurrentItem();
        if (isEmpty(clicked) || event.getClickedInventory() == null) {
            return;
        }

        Inventory recycleInventory = holder.getInventory();
        for (int slot = 0; slot < INPUT_END_EXCLUSIVE; slot++) {
            if (isEmpty(recycleInventory.getItem(slot))) {
                recycleInventory.setItem(slot, clicked.clone());
                event.getClickedInventory().setItem(event.getSlot(), null);
                holder.resetProtection();
                refreshButton(holder);
                return;
            }
        }

        event.getWhoClicked().sendMessage(Component.text("回收箱已满。", NamedTextColor.YELLOW));
    }

    private void refreshButton(RecycleHolder holder) {
        Inventory inventory = holder.getInventory();
        if (inventory == null) {
            return;
        }

        RecycleQuote quote = calculateQuote(inventory);
        boolean awaitingConfirm = quote.hasProtectedItems() && holder.isAwaitingProtectionConfirmation();
        Material material = awaitingConfirm ? Material.ORANGE_DYE : Material.LIME_DYE;
        ItemStack button = new ItemStack(material);
        ItemMeta meta = button.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(awaitingConfirm ? "再次点击确认回收" : "回收", awaitingConfirm ? NamedTextColor.GOLD : NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("预计获得: " + quote.totalValue() + " Coins", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text("物品数量: " + quote.itemCount(), NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false));
            if (quote.hasProtectedItems()) {
                lore.add(Component.text("含史诗及以上物品，需要二次确认。", NamedTextColor.GOLD)
                        .decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
            button.setItemMeta(meta);
        }
        inventory.setItem(BUTTON_SLOT, button);
    }

    private void scheduleButtonRefresh(RecycleHolder holder) {
        Bukkit.getScheduler().runTask(plugin, () -> refreshButton(holder));
    }

    private String inventorySignature(Inventory inventory) {
        StringBuilder signature = new StringBuilder();
        for (int slot = 0; slot < INPUT_END_EXCLUSIVE; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (isEmpty(item)) {
                continue;
            }
            signature.append(slot)
                    .append(':')
                    .append(item.getType().name())
                    .append(':')
                    .append(item.getAmount())
                    .append(':')
                    .append(item.hashCode())
                    .append(';');
        }
        return signature.toString();
    }

    private void returnItem(Player player, ItemStack item) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item.clone());
        for (ItemStack leftover : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private ItemStack namedItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name).decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isInputSlot(int slot) {
        return slot >= 0 && slot < INPUT_END_EXCLUSIVE;
    }

    private boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    private void loadDefaultPrices() {
        rarityPrices.put(ItemFormatManager.Rarity.COMMON, 1L);
        rarityPrices.put(ItemFormatManager.Rarity.UNCOMMON, 5L);
        rarityPrices.put(ItemFormatManager.Rarity.RARE, 10L);
        rarityPrices.put(ItemFormatManager.Rarity.EPIC, 50L);
        rarityPrices.put(ItemFormatManager.Rarity.LEGENDARY, 100L);
        rarityPrices.put(ItemFormatManager.Rarity.MYTHIC, 1000L);
    }

    private void ensureConfigFile() {
        if (configFile.exists()) {
            return;
        }
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder for recycle.yml.");
            return;
        }

        try (InputStream stream = plugin.getResource("recycle.yml")) {
            if (stream != null) {
                plugin.saveResource("recycle.yml", false);
                return;
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Unable to check bundled recycle.yml: " + exception.getMessage());
        }

        try {
            YamlConfiguration config = new YamlConfiguration();
            config.createSection("prices.rarity");
            config.save(configFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not create recycle.yml: " + exception.getMessage());
        }
    }

    private void loadRarityPrices(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                ItemFormatManager.Rarity rarity = ItemFormatManager.Rarity.valueOf(key.toUpperCase(Locale.ROOT));
                rarityPrices.put(rarity, Math.max(0L, section.getLong(key)));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Unknown recycle rarity price key: " + key);
            }
        }
    }

    private void loadItemIdPrices(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            itemIdPrices.put(normalizeId(key), Math.max(0L, section.getLong(key)));
        }
    }

    private void loadMaterialPrices(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material == null) {
                plugin.getLogger().warning("Unknown recycle material price key: " + key);
                continue;
            }
            materialPrices.put(material, Math.max(0L, section.getLong(key)));
        }
    }

    private String normalizeId(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private long saturatingMultiply(long price, int amount) {
        if (price <= 0L || amount <= 0) {
            return 0L;
        }
        if (price > Long.MAX_VALUE / amount) {
            return Long.MAX_VALUE;
        }
        return price * amount;
    }

    private long saturatingAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private record RecycleQuote(long totalValue, int itemCount, boolean hasProtectedItems) {
    }

    private static class RecycleHolder implements InventoryHolder {
        private Inventory inventory;
        private boolean awaitingProtectionConfirmation;
        private String protectionSignature = "";

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        private void awaitProtectionConfirmation(String signature) {
            this.awaitingProtectionConfirmation = true;
            this.protectionSignature = signature;
        }

        private boolean isProtectionConfirmed(String signature) {
            return awaitingProtectionConfirmation && protectionSignature.equals(signature);
        }

        private boolean isAwaitingProtectionConfirmation() {
            return awaitingProtectionConfirmation;
        }

        private void resetProtection() {
            this.awaitingProtectionConfirmation = false;
            this.protectionSignature = "";
        }
    }
}
