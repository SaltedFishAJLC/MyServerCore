package com.servercore.enchant;

import com.servercore.ServerCorePlugin;
import com.servercore.manager.CustomItemRegistry;
import com.servercore.manager.EnchantManager;
import com.servercore.manager.ItemFormatManager;
import com.servercore.manager.PDCManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.GrindstoneInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class EnchantGrindstoneListener implements Listener {

    private static final int GUI_SIZE = 45;
    private static final int ITEM_SLOT = 10;
    private static final int CHEAT_SLOT = 44;
    private static final int[] OPTION_SLOTS = {12, 13, 14, 15, 16, 21, 22, 23, 24, 25, 30, 31, 32, 33, 34};

    private final ServerCorePlugin plugin;

    public EnchantGrindstoneListener(ServerCorePlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareGrindstone(PrepareGrindstoneEvent event) {
        ItemStack result = buildClearResult(findCustomEnchantSource(event.getInventory()));
        if (result != null) {
            event.setResult(result);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTakeResult(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getType() != InventoryType.GRINDSTONE || event.getSlotType() != InventoryType.SlotType.RESULT) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getView().getTopInventory() instanceof GrindstoneInventory inventory)) {
            return;
        }
        ItemStack source = findCustomEnchantSource(inventory);
        refundClearAll(player, source);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSneakOpenGrindstoneGui(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || !event.getPlayer().isSneaking()) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.GRINDSTONE) {
            return;
        }

        event.setCancelled(true);
        openSingleRemoveGui(event.getPlayer(), null);
    }

    @EventHandler
    public void onSingleRemoveClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof SingleRemoveHolder holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (event.getClickedInventory() == event.getView().getBottomInventory()) {
            handlePlayerInventoryClick(event, holder);
            return;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot == ITEM_SLOT) {
            Bukkit.getScheduler().runTask(plugin, () -> renderSingleRemoveGui(holder));
            return;
        }

        event.setCancelled(true);
        if (rawSlot == CHEAT_SLOT && holder.adminAvailable() && hasEnchantAdmin(player)) {
            holder.setAdminMode(!holder.adminMode());
            renderSingleRemoveGui(holder);
            return;
        }

        String enchantId = holder.enchantAt(rawSlot);
        if (enchantId == null) {
            return;
        }
        removeSingleEnchant(player, holder, enchantId);
    }

    @EventHandler
    public void onSingleRemoveDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof SingleRemoveHolder)) {
            return;
        }
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < event.getView().getTopInventory().getSize() && rawSlot != ITEM_SLOT) {
                event.setCancelled(true);
                return;
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> renderSingleRemoveGui((SingleRemoveHolder) event.getInventory().getHolder()));
    }

    @EventHandler
    public void onSingleRemoveClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof SingleRemoveHolder)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        ItemStack item = event.getInventory().getItem(ITEM_SLOT);
        if (item != null && !item.getType().isAir()) {
            returnItem(player, item);
            event.getInventory().setItem(ITEM_SLOT, null);
        }
    }

    private void openSingleRemoveGui(Player player, ItemStack initialItem) {
        SingleRemoveHolder holder = new SingleRemoveHolder(hasEnchantAdmin(player));
        Inventory inventory = Bukkit.createInventory(holder, GUI_SIZE, Component.text("附魔拆除"));
        holder.setInventory(inventory);
        if (initialItem != null && !initialItem.getType().isAir()) {
            inventory.setItem(ITEM_SLOT, initialItem.clone());
        }
        renderSingleRemoveGui(holder);
        player.openInventory(inventory);
    }

    private void renderSingleRemoveGui(SingleRemoveHolder holder) {
        Inventory inventory = holder.getInventory();
        if (inventory == null) {
            return;
        }
        holder.clearOptions();
        for (int slot : OPTION_SLOTS) {
            inventory.setItem(slot, null);
        }
        inventory.setItem(CHEAT_SLOT, null);
        if (holder.adminAvailable()) {
            inventory.setItem(CHEAT_SLOT, createAdminModeIcon(holder.adminMode()));
        }

        ItemStack item = inventory.getItem(ITEM_SLOT);
        EnchantManager enchantManager = EnchantManager.getInstance();
        if (item == null || item.getType().isAir() || enchantManager == null) {
            inventory.setItem(22, namedItem(Material.PAPER, "放入要拆除附魔的物品", List.of(
                    Component.text("将装备放入左侧槽位。", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("点击中间的附魔条目进行单独拆除。", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            )));
            return;
        }

        Map<String, Integer> enchants = enchantManager.getAllCustomEnchants(item);
        if (enchants.isEmpty()) {
            inventory.setItem(22, namedItem(Material.BARRIER, "没有可拆除的自定义附魔", List.of(
                    Component.text("此物品没有自定义附魔。", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            )));
            return;
        }

        int index = 0;
        for (Map.Entry<String, Integer> entry : enchants.entrySet()) {
            if (index >= OPTION_SLOTS.length) {
                break;
            }
            int slot = OPTION_SLOTS[index++];
            holder.bind(slot, entry.getKey());
            inventory.setItem(slot, createRemoveIcon(entry.getKey(), entry.getValue(), holder.adminMode()));
        }
    }

    private void handlePlayerInventoryClick(InventoryClickEvent event, SingleRemoveHolder holder) {
        if (!event.getClick().isShiftClick()) {
            return;
        }
        event.setCancelled(true);
        Inventory top = event.getView().getTopInventory();
        if (top.getItem(ITEM_SLOT) != null && !top.getItem(ITEM_SLOT).getType().isAir()) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir()) {
            return;
        }
        top.setItem(ITEM_SLOT, clicked.clone());
        event.setCurrentItem(null);
        Bukkit.getScheduler().runTask(plugin, () -> renderSingleRemoveGui(holder));
    }

    private void removeSingleEnchant(Player player, SingleRemoveHolder holder, String enchantId) {
        Inventory inventory = holder.getInventory();
        if (inventory == null) {
            return;
        }

        ItemStack item = inventory.getItem(ITEM_SLOT);
        EnchantManager enchantManager = EnchantManager.getInstance();
        EnchantRegistry registry = EnchantRegistry.getInstance();
        if (item == null || item.getType().isAir() || enchantManager == null || registry == null) {
            renderSingleRemoveGui(holder);
            return;
        }

        Map<String, Integer> enchants = new LinkedHashMap<>(enchantManager.getAllCustomEnchants(item));
        int level = enchants.getOrDefault(enchantId, 0);
        if (level <= 0) {
            renderSingleRemoveGui(holder);
            return;
        }

        EnchantDefinition definition = registry.get(enchantId).orElse(null);
        boolean adminCheat = holder.adminMode() && hasEnchantAdmin(player);
        int dustCost = registry.pools().singleRemoveDustCost(definition, level);
        if (!adminCheat && countDust(player) < dustCost) {
            player.sendMessage(Component.text("粉尘不足，需要 " + dustCost + " 个魔尘。", NamedTextColor.RED));
            return;
        }

        String specialItemId = registry.pools().singleRemoveSpecialMaterialItemId(definition);
        int specialAmount = registry.pools().singleRemoveSpecialMaterialAmount(definition);
        if (!adminCheat
                && registry.pools().singleRemoveRequiresSpecialMaterial(definition)
                && countCustomItem(player, specialItemId) < specialAmount) {
            player.sendMessage(Component.text("终极附魔拆除需要 " + specialAmount + " 个 " + specialMaterialName(specialItemId) + "。", NamedTextColor.RED));
            return;
        }

        if (!adminCheat) {
            removeDust(player, dustCost);
        }
        if (!adminCheat && registry.pools().singleRemoveRequiresSpecialMaterial(definition)) {
            removeCustomItem(player, specialItemId, specialAmount);
        }

        enchants.remove(enchantId);
        enchantManager.writeEnchants(item, enchants);
        ItemFormatManager formatManager = ItemFormatManager.getInstance();
        if (formatManager != null) {
            formatManager.formatItem(item, true);
        }
        inventory.setItem(ITEM_SLOT, item);
        renderSingleRemoveGui(holder);

        String display = definition == null ? enchantId : definition.display();
        player.sendMessage(Component.text((adminCheat ? "管理员无消耗拆除: " : "已拆除附魔: ")
                + display + " " + toRoman(level), NamedTextColor.GREEN));
    }

    private ItemStack buildClearResult(ItemStack source) {
        EnchantManager enchantManager = EnchantManager.getInstance();
        if (enchantManager == null || source == null || source.getType().isAir() || enchantManager.getAllCustomEnchants(source).isEmpty()) {
            return null;
        }
        ItemStack result = source.clone();
        enchantManager.clearCustomEnchants(result);
        ItemFormatManager formatManager = ItemFormatManager.getInstance();
        if (formatManager != null) {
            formatManager.formatItem(result, true);
        }
        return result;
    }

    private ItemStack findCustomEnchantSource(GrindstoneInventory inventory) {
        if (inventory == null) {
            return null;
        }
        EnchantManager enchantManager = EnchantManager.getInstance();
        if (enchantManager == null) {
            return null;
        }
        ItemStack first = inventory.getItem(0);
        if (first != null && !first.getType().isAir() && !enchantManager.getAllCustomEnchants(first).isEmpty()) {
            return first;
        }
        ItemStack second = inventory.getItem(1);
        if (second != null && !second.getType().isAir() && !enchantManager.getAllCustomEnchants(second).isEmpty()) {
            return second;
        }
        return null;
    }

    private void refundClearAll(Player player, ItemStack source) {
        EnchantManager enchantManager = EnchantManager.getInstance();
        EnchantRegistry registry = EnchantRegistry.getInstance();
        if (player == null || enchantManager == null || registry == null || source == null || source.getType().isAir()) {
            return;
        }
        int dust = 0;
        int exp = 0;
        for (Map.Entry<String, Integer> entry : enchantManager.getAllCustomEnchants(source).entrySet()) {
            EnchantDefinition definition = registry.get(entry.getKey()).orElse(null);
            dust += registry.pools().dustRefund(definition, entry.getValue());
            exp += registry.pools().expRefund(definition, entry.getValue());
        }
        if (exp > 0) {
            player.giveExp(exp);
        }
        if (dust > 0) {
            giveDust(player, dust);
        }
    }

    private ItemStack createRemoveIcon(String enchantId, int level, boolean adminCheat) {
        EnchantRegistry registry = EnchantRegistry.getInstance();
        EnchantDefinition definition = registry == null ? null : registry.get(enchantId).orElse(null);
        int dustCost = registry == null ? 0 : registry.pools().singleRemoveDustCost(definition, level);
        boolean requiresSpecial = registry != null && registry.pools().singleRemoveRequiresSpecialMaterial(definition);
        String specialItemId = registry == null ? "" : registry.pools().singleRemoveSpecialMaterialItemId(definition);
        int specialAmount = registry == null ? 0 : registry.pools().singleRemoveSpecialMaterialAmount(definition);
        String display = definition == null ? enchantId : definition.display();
        NamedTextColor color = definition == null ? NamedTextColor.GRAY : definition.rarity().color();

        List<Component> lore = new java.util.ArrayList<>();
        lore.add(Component.text("等级: " + toRoman(level), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        if (adminCheat) {
            lore.add(Component.text("管理员模式: 无消耗拆除。", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("消耗: " + dustCost + " 魔尘", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        if (!adminCheat && requiresSpecial) {
            lore.add(Component.text("额外消耗: " + specialAmount + " " + specialMaterialName(specialItemId), NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.text(adminCheat ? "点击无消耗拆除该附魔" : "点击拆除该附魔", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));

        return namedItem(Material.ENCHANTED_BOOK, display + " " + toRoman(level), lore, color);
    }

    private void giveDust(Player player, int amount) {
        int remaining = Math.max(0, amount);
        while (remaining > 0) {
            int stackAmount = Math.min(remaining, Material.GLOWSTONE_DUST.getMaxStackSize());
            ItemStack dustItem = null;
            CustomItemRegistry customItemRegistry = CustomItemRegistry.getInstance();
            if (customItemRegistry != null) {
                dustItem = customItemRegistry.createItem("magic_dust", stackAmount);
                if (dustItem == null) {
                    dustItem = customItemRegistry.createItem("gem_dust", stackAmount);
                }
            }
            if (dustItem == null) {
                dustItem = new ItemStack(Material.GLOWSTONE_DUST, stackAmount);
            }
            for (ItemStack leftover : player.getInventory().addItem(dustItem).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
            remaining -= stackAmount;
        }
    }

    private int countDust(Player player) {
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (isDust(item)) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private void removeDust(Player player, int amount) {
        if (amount <= 0) {
            return;
        }
        int remaining = amount;
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (!isDust(item)) {
                continue;
            }
            int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take);
            remaining -= take;
            inventory.setItem(slot, item.getAmount() <= 0 ? null : item);
        }
    }

    private boolean isDust(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        String itemId = itemId(item);
        if (itemId != null) {
            String normalized = itemId.trim().toLowerCase(Locale.ROOT);
            return normalized.equals("magic_dust") || normalized.equals("gem_dust");
        }
        return item.getType() == Material.GLOWSTONE_DUST;
    }

    private int countCustomItem(Player player, String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return 0;
        }
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (itemId.equals(itemId(item))) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private void removeCustomItem(Player player, String itemId, int amount) {
        if (itemId == null || itemId.isBlank() || amount <= 0) {
            return;
        }
        int remaining = amount;
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (!itemId.equals(itemId(item))) {
                continue;
            }
            int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take);
            remaining -= take;
            inventory.setItem(slot, item.getAmount() <= 0 ? null : item);
        }
    }

    private String itemId(ItemStack item) {
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null || item == null || !item.hasItemMeta()) {
            return null;
        }
        String raw = item.getItemMeta().getPersistentDataContainer().get(pdc.KEY_ITEM_ID, PersistentDataType.STRING);
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    private String specialMaterialName(String itemId) {
        CustomItemRegistry registry = CustomItemRegistry.getInstance();
        String displayName = registry == null ? null : registry.getDisplayName(itemId);
        return displayName == null || displayName.isBlank() ? itemId : displayName;
    }

    private ItemStack createAdminModeIcon(boolean enabled) {
        return namedItem(
                enabled ? Material.COMMAND_BLOCK : Material.REDSTONE_TORCH,
                enabled ? "管理员作弊拆除：开启" : "管理员作弊拆除",
                List.of(Component.text(
                        enabled ? "点击恢复普通消耗拆除。" : "点击后本界面拆除附魔不消耗材料。",
                        NamedTextColor.GRAY
                ).decoration(TextDecoration.ITALIC, false)),
                enabled ? NamedTextColor.RED : NamedTextColor.GOLD
        );
    }

    private boolean hasEnchantAdmin(Player player) {
        return player != null && (player.hasPermission("sc.admin") || player.hasPermission("servercore.admin"));
    }

    private ItemStack namedItem(Material material, String name, List<Component> lore) {
        return namedItem(material, name, lore, NamedTextColor.WHITE);
    }

    private ItemStack namedItem(Material material, String name, List<Component> lore, NamedTextColor color) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void returnItem(Player player, ItemStack item) {
        for (ItemStack leftover : player.getInventory().addItem(item.clone()).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private String toRoman(int value) {
        return switch (Math.max(1, value)) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(value);
        };
    }

    private static final class SingleRemoveHolder implements InventoryHolder {
        private final Map<Integer, String> enchantsBySlot = new HashMap<>();
        private final boolean adminAvailable;
        private Inventory inventory;
        private boolean adminMode;

        private SingleRemoveHolder(boolean adminAvailable) {
            this.adminAvailable = adminAvailable;
        }

        private void bind(int slot, String enchantId) {
            enchantsBySlot.put(slot, enchantId);
        }

        private String enchantAt(int slot) {
            return enchantsBySlot.get(slot);
        }

        private void clearOptions() {
            enchantsBySlot.clear();
        }

        private boolean adminAvailable() {
            return adminAvailable;
        }

        private boolean adminMode() {
            return adminMode;
        }

        private void setAdminMode(boolean adminMode) {
            this.adminMode = adminMode;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
