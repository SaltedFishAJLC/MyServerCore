package com.servercore.manager;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import com.servercore.ServerCorePlugin;
import com.servercore.passive.PassiveSnapshotService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class AccessoryListener implements Listener {

    public static final String ACC_GUI_TITLE = "✦ 独立饰品槽 ✦";
    public static final String TALISMAN_GUI_TITLE = "✦ 护符包 ✦";
    public static final String PASSIVE_GUI_TITLE = "✦ 被动总览 ✦";

    private static final int[] ACC_SLOTS = {10, 12, 14, 16};
    private static final int SLOT_IMPRINT = 22;
    private static final int SLOT_IMPRINT_INFO = 21;
    private static final int SLOT_PASSIVE_OVERVIEW = 23;
    private static final int BUTTON_TALISMAN = 26;

    private final ServerCorePlugin plugin;
    private final NamespacedKey placeholderKey;

    public AccessoryListener(ServerCorePlugin plugin) {
        this.plugin = plugin;
        this.placeholderKey = new NamespacedKey(plugin, "accessory_gui_placeholder");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void openAccessoryMenu(Player player) {
        AccessoryMenuHolder holder = new AccessoryMenuHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, 27, Component.text(ACC_GUI_TITLE));
        holder.setInventory(inventory);

        ItemStack glass = named(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, glass);
        }

        ItemStack[] saved = AccessoryManager.getInstance().loadAccessories(player);
        for (int index = 0; index < ACC_SLOTS.length; index++) {
            inventory.setItem(ACC_SLOTS[index], index < saved.length ? saved[index] : null);
        }

        ItemStack imprint = AccessoryManager.getInstance().loadImprint(player);
        inventory.setItem(SLOT_IMPRINT, imprint == null ? imprintPlaceholder() : imprint);
        inventory.setItem(SLOT_IMPRINT_INFO, buildImprintInfo(player, imprint));
        inventory.setItem(SLOT_PASSIVE_OVERVIEW, named(
                Material.ENCHANTED_BOOK,
                Component.text("被动总览", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false),
                List.of(Component.text("查看装备、饰品、护符、印记与套装的有效被动。", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false))
        ));
        inventory.setItem(BUTTON_TALISMAN, named(
                Material.CHEST,
                Component.text("打开护符包", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                List.of(Component.text("护符每格只能存放一个；同系列仅最高稀有度生效。", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false))
        ));

        player.openInventory(inventory);
    }

    public void openTalismanBag(Player player) {
        TalismanMenuHolder holder = new TalismanMenuHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text(TALISMAN_GUI_TITLE));
        holder.setInventory(inventory);
        inventory.setContents(normalizeTalismanContents(
                player,
                AccessoryManager.getInstance().loadTalismanBag(player, 54)
        ));
        player.openInventory(inventory);
    }

    public void openPassiveOverview(Player player) {
        PassiveOverviewHolder holder = new PassiveOverviewHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text(PASSIVE_GUI_TITLE));
        holder.setInventory(inventory);

        PassiveSnapshotService service = PassiveSnapshotService.getInstance();
        List<String> lines = service == null ? List.of("被动系统尚未初始化。") : service.describe(player, false);
        int slot = 0;
        for (String line : lines) {
            if (slot >= inventory.getSize()) {
                break;
            }
            boolean inactive = line.startsWith("未生效:");
            inventory.setItem(slot++, named(
                    inactive ? Material.GRAY_DYE : Material.LIME_DYE,
                    Component.text(inactive ? "未生效" : "有效被动",
                                    inactive ? NamedTextColor.GRAY : NamedTextColor.GREEN)
                            .decoration(TextDecoration.ITALIC, false),
                    List.of(Component.text(line, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
            ));
        }
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (holder instanceof AccessoryMenuHolder) {
            handleAccessoryClick(event, player);
        } else if (holder instanceof TalismanMenuHolder) {
            handleTalismanClick(event, player);
        } else if (holder instanceof PassiveOverviewHolder) {
            event.setCancelled(true);
        }
    }

    private void handleAccessoryClick(InventoryClickEvent event, Player player) {
        int rawSlot = event.getRawSlot();
        if (rawSlot >= event.getView().getTopInventory().getSize()) {
            if (event.getClick().isShiftClick()) {
                event.setCancelled(true);
                player.sendActionBar(Component.text("请使用光标手动放入饰品或印记。", NamedTextColor.RED));
            }
            return;
        }

        if (rawSlot == BUTTON_TALISMAN) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> openTalismanBag(player));
            return;
        }
        if (rawSlot == SLOT_PASSIVE_OVERVIEW) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> openPassiveOverview(player));
            return;
        }
        if (rawSlot == SLOT_IMPRINT) {
            handleImprintClick(event, player);
            return;
        }
        if (rawSlot == SLOT_IMPRINT_INFO || !isAccessorySlot(rawSlot)) {
            event.setCancelled(true);
            return;
        }
        if (!isSimpleCursorClick(event.getClick())) {
            event.setCancelled(true);
            return;
        }

        ItemStack cursor = event.getCursor();
        if (!isAir(cursor) && !validateAccessory(player, cursor, rawSlot)) {
            event.setCancelled(true);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> saveAccessoryMenu(player, event.getView().getTopInventory()));
    }

    private void handleImprintClick(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        if (!isSimpleCursorClick(event.getClick())) {
            return;
        }

        Inventory inventory = event.getView().getTopInventory();
        ItemStack current = inventory.getItem(SLOT_IMPRINT);
        if (isPlaceholder(current)) {
            current = null;
        }
        ItemStack cursor = event.getCursor();

        if (isAir(cursor)) {
            if (current == null) {
                return;
            }
            event.setCursor(current);
            inventory.setItem(SLOT_IMPRINT, imprintPlaceholder());
            AccessoryManager.getInstance().saveImprint(player, null);
            refreshAfterManagedChange(player, inventory);
            return;
        }

        if (!validateImprint(player, cursor)) {
            return;
        }

        if (current != null && cursor.getAmount() > 1) {
            player.sendActionBar(Component.text("交换印记前请先拆分物品堆。", NamedTextColor.RED));
            return;
        }

        ItemStack candidate = cursor.clone();
        candidate.setAmount(1);
        AccessoryManager.getInstance().ensureItemInstanceId(candidate);
        inventory.setItem(SLOT_IMPRINT, candidate);
        if (current == null) {
            ItemStack remainder = cursor.clone();
            remainder.setAmount(cursor.getAmount() - 1);
            event.setCursor(remainder.getAmount() <= 0 ? null : remainder);
        } else {
            event.setCursor(current);
        }
        AccessoryManager.getInstance().saveImprint(player, candidate);
        refreshAfterManagedChange(player, inventory);
    }

    private void handleTalismanClick(InventoryClickEvent event, Player player) {
        int topSize = event.getView().getTopInventory().getSize();
        int rawSlot = event.getRawSlot();
        if (rawSlot >= topSize) {
            if (event.getClick().isShiftClick()) {
                event.setCancelled(true);
                player.sendActionBar(Component.text("请使用光标手动放入护符。", NamedTextColor.RED));
            }
            return;
        }
        event.setCancelled(true);
        if (!isSimpleCursorClick(event.getClick())) {
            return;
        }

        Inventory inventory = event.getView().getTopInventory();
        ItemStack current = inventory.getItem(rawSlot);
        ItemStack cursor = event.getCursor();
        if (isAir(cursor)) {
            if (!isAir(current)) {
                event.setCursor(current);
                inventory.setItem(rawSlot, null);
                saveTalismanMenu(player, inventory);
            }
            return;
        }

        if (!validateTalisman(player, cursor, inventory, rawSlot)) {
            return;
        }
        if (!isAir(current) && cursor.getAmount() > 1) {
            player.sendActionBar(Component.text("交换护符前请先拆分物品堆。", NamedTextColor.RED));
            return;
        }

        ItemStack candidate = cursor.clone();
        candidate.setAmount(1);
        AccessoryManager.getInstance().ensureItemInstanceId(candidate);
        inventory.setItem(rawSlot, candidate);
        if (isAir(current)) {
            ItemStack remainder = cursor.clone();
            remainder.setAmount(cursor.getAmount() - 1);
            event.setCursor(remainder.getAmount() <= 0 ? null : remainder);
        } else {
            event.setCursor(current);
        }
        saveTalismanMenu(player, inventory);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof AccessoryMenuHolder) && !(holder instanceof TalismanMenuHolder)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof AccessoryMenuHolder) {
            saveAccessoryMenu(player, event.getInventory());
        } else if (holder instanceof TalismanMenuHolder) {
            saveTalismanMenu(player, event.getInventory());
        }
    }

    private void saveAccessoryMenu(Player player, Inventory inventory) {
        ItemStack[] items = new ItemStack[4];
        for (int index = 0; index < ACC_SLOTS.length; index++) {
            items[index] = inventory.getItem(ACC_SLOTS[index]);
        }
        AccessoryManager.getInstance().saveAccessories(player, items);
        refreshAfterManagedChange(player, inventory);
    }

    private void saveTalismanMenu(Player player, Inventory inventory) {
        ItemStack[] items = inventory.getContents();
        for (int index = 0; index < items.length; index++) {
            ItemStack item = items[index];
            if (isAir(item)) {
                continue;
            }
            if (AccessoryManager.getInstance().isRegisteredTalisman(item)) {
                item.setAmount(1);
                AccessoryManager.getInstance().ensureItemInstanceId(item);
            }
        }
        AccessoryManager.getInstance().saveTalismanBag(player, items);
        refreshAfterManagedChange(player, null);
    }

    private ItemStack[] normalizeTalismanContents(Player player, ItemStack[] items) {
        if (items == null) {
            return new ItemStack[54];
        }
        AccessoryManager manager = AccessoryManager.getInstance();
        for (ItemStack item : items) {
            if (isAir(item) || !manager.isRegisteredTalisman(item) || item.getAmount() <= 1) {
                continue;
            }
            ItemStack overflow = item.clone();
            overflow.setAmount(item.getAmount() - 1);
            item.setAmount(1);
            manager.ensureItemInstanceId(item);
            Map<Integer, ItemStack> remaining = player.getInventory().addItem(overflow);
            for (ItemStack leftover : remaining.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
        manager.saveTalismanBag(player, items);
        return items;
    }

    private void refreshAfterManagedChange(Player player, Inventory accessoryInventory) {
        PassiveSnapshotService passives = PassiveSnapshotService.getInstance();
        if (passives != null) {
            passives.scheduleRefresh(player);
        } else {
            PlayerStatCache.getInstance().updateCache(player);
        }
        if (accessoryInventory != null && accessoryInventory.getHolder() instanceof AccessoryMenuHolder) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                ItemStack imprint = AccessoryManager.getInstance().loadImprint(player);
                accessoryInventory.setItem(SLOT_IMPRINT_INFO, buildImprintInfo(player, imprint));
            });
        }
    }

    private boolean validateAccessory(Player player, ItemStack item, int slot) {
        if (!meetsRequirements(player, item)) {
            return false;
        }
        String actual = PDCManager.getInstance().getString(item, PDCManager.getInstance().KEY_ACC_TYPE);
        String required = switch (slot) {
            case 10 -> "necklace";
            case 12 -> "bracelet";
            case 14 -> "ring";
            case 16 -> "belt";
            default -> "";
        };
        if (!required.equalsIgnoreCase(actual == null ? "" : actual)) {
            player.sendActionBar(Component.text("该槽位只能放入 " + required + "。", NamedTextColor.RED));
            return false;
        }
        return true;
    }

    private boolean validateImprint(Player player, ItemStack item) {
        if (!meetsRequirements(player, item)) {
            return false;
        }
        if (!AccessoryManager.getInstance().isImprintEligible(item)) {
            player.sendActionBar(Component.text("该物品未声明印记资格，或没有可生效的被动/套装身份。", NamedTextColor.RED));
            return false;
        }
        return true;
    }

    private boolean validateTalisman(Player player, ItemStack item, Inventory inventory, int targetSlot) {
        if (!meetsRequirements(player, item)) {
            return false;
        }
        AccessoryManager manager = AccessoryManager.getInstance();
        if (!manager.isRegisteredTalisman(item)) {
            player.sendActionBar(Component.text("护符包只接受已注册的 TALISMAN 物品。", NamedTextColor.RED));
            return false;
        }

        CustomItemRegistry registry = CustomItemRegistry.getInstance();
        String itemId = registry == null ? null : registry.getItemId(item);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (slot == targetSlot) {
                continue;
            }
            ItemStack other = inventory.getItem(slot);
            if (!isAir(other) && itemId != null && itemId.equalsIgnoreCase(registry.getItemId(other))) {
                player.sendActionBar(Component.text("护符包中已经存在相同护符。", NamedTextColor.RED));
                return false;
            }
        }
        return true;
    }

    private boolean meetsRequirements(Player player, ItemStack item) {
        RequirementManager requirements = RequirementManager.getInstance();
        if (requirements == null || requirements.meetsRequirement(player, item)) {
            return true;
        }
        requirements.sendRequirementDenyMessage(player, item);
        return false;
    }

    private ItemStack buildImprintInfo(Player player, ItemStack imprint) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("仅被动能力与套装部件身份生效。", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("基础数值、重铸、宝石、附魔与主动技能均被屏蔽。", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        if (imprint == null) {
            lore.add(Component.text("当前：未放入印记", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        } else {
            CustomItemRegistry registry = CustomItemRegistry.getInstance();
            CustomItemRegistry.CustomItemDefinition definition =
                    registry == null ? null : registry.getDefinition(registry.getItemId(imprint));
            lore.add(Component.text("当前：" + (definition == null ? "定义已失效" : definition.displayName()),
                            definition == null ? NamedTextColor.RED : NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false));
            if (definition != null && !definition.setId().isBlank()) {
                lore.add(Component.text("套装：" + definition.setId() + " / " + definition.setPieceId(),
                                NamedTextColor.AQUA)
                        .decoration(TextDecoration.ITALIC, false));
            }
        }
        return named(
                Material.AMETHYST_SHARD,
                Component.text("印记状态", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false),
                lore
        );
    }

    private ItemStack imprintPlaceholder() {
        ItemStack item = named(
                Material.GRAY_DYE,
                Component.text("印记", NamedTextColor.DARK_PURPLE).decoration(TextDecoration.ITALIC, false),
                List.of(Component.text("点击并放入一个具有印记资格的物品。", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false))
        );
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(placeholderKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isPlaceholder(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer()
                .getOrDefault(placeholderKey, PersistentDataType.BYTE, (byte) 0) != 0;
    }

    private ItemStack named(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isAccessorySlot(int slot) {
        for (int accessorySlot : ACC_SLOTS) {
            if (slot == accessorySlot) {
                return true;
            }
        }
        return false;
    }

    private boolean isSimpleCursorClick(ClickType click) {
        return click == ClickType.LEFT || click == ClickType.RIGHT;
    }

    private boolean isAir(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        PassiveSnapshotService service = PassiveSnapshotService.getInstance();
        if (service != null) {
            service.scheduleRefresh(event.getPlayer());
        } else {
            PlayerStatCache.getInstance().updateCache(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        PlayerStatCache.getInstance().remove(event.getPlayer());
    }

    @EventHandler
    public void onArmorChange(PlayerArmorChangeEvent event) {
        PassiveSnapshotService service = PassiveSnapshotService.getInstance();
        if (service != null) {
            service.scheduleRefresh(event.getPlayer());
        } else {
            PlayerStatCache.getInstance().updateCache(event.getPlayer());
        }
    }

    private abstract static class BaseHolder implements InventoryHolder {
        private final UUID playerId;
        private Inventory inventory;

        private BaseHolder(UUID playerId) {
            this.playerId = playerId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        protected void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }
    }

    private static final class AccessoryMenuHolder extends BaseHolder {
        private AccessoryMenuHolder(UUID playerId) {
            super(playerId);
        }
    }

    private static final class TalismanMenuHolder extends BaseHolder {
        private TalismanMenuHolder(UUID playerId) {
            super(playerId);
        }
    }

    private static final class PassiveOverviewHolder extends BaseHolder {
        private PassiveOverviewHolder(UUID playerId) {
            super(playerId);
        }
    }
}
