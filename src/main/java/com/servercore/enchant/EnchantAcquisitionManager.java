package com.servercore.enchant;

import com.servercore.ServerCorePlugin;
import com.servercore.manager.EnchantManager;
import com.servercore.manager.PDCManager;
import com.servercore.manager.PlayerStatCache;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class EnchantAcquisitionManager implements Listener {

    private static final int SHOP_SIZE = 27;
    private static final int[] OFFER_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final int SPECIAL_TABLE_SIZE = 54;
    private static final int SPECIAL_TABLE_INPUT_SLOT = 13;
    private static final int[] SPECIAL_TABLE_OFFER_SLOTS = {
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private final ServerCorePlugin plugin;

    public EnchantAcquisitionManager(ServerCorePlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void openNpcBookShop(Player player) {
        EnchantRegistry registry = EnchantRegistry.getInstance();
        if (registry == null || registry.pools() == null || !registry.pools().isNpcBooksEnabled()) {
            player.sendMessage(Component.text("轮换附魔书商店未启用。", NamedTextColor.RED));
            return;
        }

        List<EnchantPoolRegistry.NpcBookOffer> offers = registry.pools().currentNpcBookOffers();
        if (offers.isEmpty()) {
            player.sendMessage(Component.text("当前没有可购买的轮换附魔书。", NamedTextColor.YELLOW));
            return;
        }

        BookShopHolder holder = new BookShopHolder();
        Inventory inventory = Bukkit.createInventory(holder, SHOP_SIZE, Component.text("轮换附魔书"));
        holder.setInventory(inventory);

        int index = 0;
        for (EnchantPoolRegistry.NpcBookOffer offer : offers) {
            if (index >= OFFER_SLOTS.length) {
                break;
            }
            int slot = OFFER_SLOTS[index++];
            holder.bind(slot, offer);
            inventory.setItem(slot, createOfferIcon(offer));
        }

        player.openInventory(inventory);
    }

    public void openSpecialEnchantTable(Player player) {
        EnchantRegistry registry = EnchantRegistry.getInstance();
        if (registry == null || registry.pools() == null || !registry.pools().isSpecialEnchantTableEnabled()) {
            player.sendMessage(Component.text("定向附魔台未启用。", NamedTextColor.RED));
            return;
        }

        SpecialTableHolder holder = new SpecialTableHolder();
        Inventory inventory = Bukkit.createInventory(holder, SPECIAL_TABLE_SIZE, Component.text("定向附魔台"));
        holder.setInventory(inventory);
        renderSpecialTable(holder);
        player.openInventory(inventory);
    }

    public void applySpecialEnchant(Player player, String enchantId, int level) {
        EnchantRegistry registry = EnchantRegistry.getInstance();
        EnchantManager enchantManager = EnchantManager.getInstance();
        if (registry == null || registry.pools() == null || enchantManager == null) {
            player.sendMessage(Component.text("附魔系统尚未加载。", NamedTextColor.RED));
            return;
        }
        if (!registry.pools().isSpecialEnchantTableEnabled()) {
            player.sendMessage(Component.text("定向附魔台未启用。", NamedTextColor.RED));
            return;
        }

        EnchantDefinition definition = registry.get(enchantId).orElse(null);
        if (definition == null || !definition.enabled()) {
            player.sendMessage(Component.text("未知或已禁用的附魔: " + enchantId, NamedTextColor.RED));
            return;
        }
        if (!registry.pools().isAllowedAtSpecialTable(definition)) {
            player.sendMessage(Component.text("该附魔不在定向附魔台允许的稀有度内。", NamedTextColor.RED));
            return;
        }

        int targetLevel = registry.pools().isSpecialTableLevelChoiceAllowed() ? level : 1;
        if (targetLevel < 1 || targetLevel > definition.maxLevel()) {
            player.sendMessage(Component.text("等级必须在 1-" + definition.maxLevel() + " 之间。", NamedTextColor.RED));
            return;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            player.sendMessage(Component.text("请将要附魔的物品拿在主手。", NamedTextColor.RED));
            return;
        }

        int existing = enchantManager.getCustomEnchantLevel(item, definition.id());
        if (existing >= targetLevel) {
            player.sendMessage(Component.text(definition.display() + " 当前等级已经不低于目标等级。", NamedTextColor.YELLOW));
            return;
        }

        EnchantApplyResult validation = enchantManager.canApply(item, definition.id(), targetLevel);
        if (!validation.success()) {
            player.sendMessage(Component.text(validation.message(), NamedTextColor.RED));
            return;
        }

        int dustCost = registry.pools().specialDustCost(definition, targetLevel);
        int expLevelCost = registry.pools().specialExpLevelCost(definition, targetLevel);
        if (!canPay(player, dustCost, expLevelCost)) {
            sendCostFailure(player, dustCost, expLevelCost);
            return;
        }

        EnchantApplyResult result = enchantManager.addCustomEnchantChecked(item, definition.id(), targetLevel);
        if (!result.success()) {
            player.sendMessage(Component.text(result.message(), NamedTextColor.RED));
            return;
        }
        pay(player, dustCost, expLevelCost);
        player.getInventory().setItemInMainHand(item);
        refreshStats(player);
        player.sendMessage(Component.text("已定向附魔: " + definition.display() + " " + toRoman(targetLevel), NamedTextColor.GREEN));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getInventory().getHolder() instanceof SpecialTableHolder specialHolder) {
            handleSpecialTableClick(event, player, specialHolder);
            return;
        }
        if (!(event.getInventory().getHolder() instanceof BookShopHolder holder)) {
            return;
        }

        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        EnchantPoolRegistry.NpcBookOffer offer = holder.offerAt(event.getRawSlot());
        if (offer == null) {
            return;
        }

        buyOffer(player, offer);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpecialTableInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        if (event.getClickedBlock().getType() != Material.ENCHANTING_TABLE || !event.getPlayer().isSneaking()) {
            return;
        }

        event.setCancelled(true);
        openSpecialEnchantTable(event.getPlayer());
    }

    @EventHandler
    public void onSpecialTableClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof SpecialTableHolder holder)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        ItemStack input = holder.input();
        if (input == null || input.getType().isAir()) {
            return;
        }
        holder.setInput(null);
        for (ItemStack leftover : player.getInventory().addItem(input).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private void handleSpecialTableClick(InventoryClickEvent event, Player player, SpecialTableHolder holder) {
        event.setCancelled(true);
        int rawSlot = event.getRawSlot();
        Inventory top = event.getView().getTopInventory();

        if (rawSlot == SPECIAL_TABLE_INPUT_SLOT) {
            swapSpecialTableInput(event, holder);
            renderSpecialTable(holder);
            return;
        }

        SpecialTableOffer offer = holder.offerAt(rawSlot);
        if (offer != null) {
            applySpecialTableOffer(player, holder, offer);
            renderSpecialTable(holder);
            return;
        }

        if (event.isShiftClick() && event.getClickedInventory() == event.getView().getBottomInventory()
                && holder.input() == null) {
            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType().isAir()) {
                return;
            }
            ItemStack moved = clicked.clone();
            holder.setInput(moved);
            top.setItem(SPECIAL_TABLE_INPUT_SLOT, moved);
            event.setCurrentItem(null);
            renderSpecialTable(holder);
        }
    }

    private void swapSpecialTableInput(InventoryClickEvent event, SpecialTableHolder holder) {
        ItemStack cursor = emptyToNull(event.getCursor());
        ItemStack current = emptyToNull(event.getCurrentItem());
        if (cursor == null && current == null) {
            return;
        }

        event.setCursor(current == null ? null : current.clone());
        ItemStack nextInput = cursor == null ? null : cursor.clone();
        holder.setInput(nextInput);
        event.getView().getTopInventory().setItem(SPECIAL_TABLE_INPUT_SLOT, nextInput);
    }

    private void applySpecialTableOffer(Player player, SpecialTableHolder holder, SpecialTableOffer offer) {
        ItemStack input = holder.input();
        if (input == null || input.getType().isAir()) {
            return;
        }
        EnchantManager enchantManager = EnchantManager.getInstance();
        if (enchantManager == null) {
            player.sendMessage(Component.text("附魔系统尚未加载。", NamedTextColor.RED));
            return;
        }
        EnchantApplyResult validation = enchantManager.canApply(input, offer.definition().id(), offer.level());
        if (!validation.success()) {
            player.sendMessage(Component.text(validation.message(), NamedTextColor.RED));
            return;
        }
        if (!canPay(player, offer.dustCost(), offer.expLevelCost())) {
            sendCostFailure(player, offer.dustCost(), offer.expLevelCost());
            return;
        }

        EnchantApplyResult result = enchantManager.addCustomEnchantChecked(input, offer.definition().id(), offer.level());
        if (!result.success()) {
            player.sendMessage(Component.text(result.message(), NamedTextColor.RED));
            return;
        }

        pay(player, offer.dustCost(), offer.expLevelCost());
        holder.setInput(input);
        holder.getInventory().setItem(SPECIAL_TABLE_INPUT_SLOT, input);
        refreshStats(player);
        player.sendMessage(Component.text("定向附魔成功: " + offer.definition().display() + " " + toRoman(offer.level()), NamedTextColor.GREEN));
    }

    private void renderSpecialTable(SpecialTableHolder holder) {
        Inventory inventory = holder.getInventory();
        if (inventory == null) {
            return;
        }

        holder.clearOffers();
        for (int slot : SPECIAL_TABLE_OFFER_SLOTS) {
            inventory.setItem(slot, null);
        }

        ItemStack input = holder.input();
        inventory.setItem(SPECIAL_TABLE_INPUT_SLOT, input);
        if (input == null || input.getType().isAir()) {
            inventory.setItem(31, createInfoIcon(Material.GRAY_STAINED_GLASS_PANE, "放入要定向附魔的物品", "Shift+右键附魔台打开界面。"));
            return;
        }

        EnchantRegistry registry = EnchantRegistry.getInstance();
        EnchantManager enchantManager = EnchantManager.getInstance();
        if (registry == null || registry.pools() == null || enchantManager == null) {
            inventory.setItem(31, createInfoIcon(Material.BARRIER, "附魔系统未加载", "请稍后再试。"));
            return;
        }

        int index = 0;
        for (EnchantDefinition definition : registry.getEnabledDefinitions()) {
            if (index >= SPECIAL_TABLE_OFFER_SLOTS.length) {
                break;
            }
            if (!registry.pools().isAllowedAtSpecialTable(definition)) {
                continue;
            }
            if (!EnchantSlotMatcher.matches(input, definition.slots())) {
                continue;
            }
            int existing = enchantManager.getCustomEnchantLevel(input, definition.id());
            if (existing >= definition.softMaxLevel()) {
                continue;
            }
            int targetLevel = Math.max(1, existing + 1);
            targetLevel = Math.min(targetLevel, definition.softMaxLevel());
            EnchantApplyResult validation = enchantManager.canApply(input, definition.id(), targetLevel);
            if (!validation.success()) {
                continue;
            }

            int dustCost = registry.pools().specialDustCost(definition, targetLevel);
            int expCost = registry.pools().specialExpLevelCost(definition, targetLevel);
            SpecialTableOffer offer = new SpecialTableOffer(definition, targetLevel, dustCost, expCost);
            int slot = SPECIAL_TABLE_OFFER_SLOTS[index++];
            holder.bind(slot, offer);
            inventory.setItem(slot, createSpecialTableOfferIcon(offer, existing));
        }

        if (index == 0) {
            inventory.setItem(31, createInfoIcon(Material.BARRIER, "没有可用定向附魔", "该物品没有可继续提升的普通或罕见附魔。"));
        }
    }

    private void buyOffer(Player player, EnchantPoolRegistry.NpcBookOffer offer) {
        if (!canPay(player, offer.dustCost(), offer.expLevelCost())) {
            sendCostFailure(player, offer.dustCost(), offer.expLevelCost());
            return;
        }

        ItemStack book = EnchantBookFactory.createBook(offer.definition(), offer.level());
        if (book == null) {
            player.sendMessage(Component.text("无法生成该附魔书。", NamedTextColor.RED));
            return;
        }

        pay(player, offer.dustCost(), offer.expLevelCost());
        for (ItemStack leftover : player.getInventory().addItem(book).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        player.sendMessage(Component.text("购买了 " + offer.definition().display() + " " + toRoman(offer.level()) + " 附魔书。", NamedTextColor.GREEN));
    }

    private ItemStack createOfferIcon(EnchantPoolRegistry.NpcBookOffer offer) {
        ItemStack icon = EnchantBookFactory.createBook(offer.definition(), offer.level());
        if (icon == null) {
            icon = new ItemStack(Material.ENCHANTED_BOOK);
        }

        ItemMeta meta = icon.getItemMeta();
        if (meta == null) {
            return icon;
        }

        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        if (!lore.isEmpty()) {
            lore.add(Component.empty());
        }
        lore.add(Component.text("费用: " + offer.dustCost() + " 粉尘, " + offer.expLevelCost() + " 级经验", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("来源: " + offer.poolId(), NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("刷新: " + formatRemaining(offer.refreshesAtEpochMillis() - System.currentTimeMillis()), NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("点击购买", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack createSpecialTableOfferIcon(SpecialTableOffer offer, int existingLevel) {
        ItemStack icon = EnchantBookFactory.createBook(offer.definition(), offer.level());
        if (icon == null) {
            icon = new ItemStack(Material.ENCHANTED_BOOK);
        }

        ItemMeta meta = icon.getItemMeta();
        if (meta == null) {
            return icon;
        }
        meta.displayName(Component.text(offer.definition().display() + " " + toRoman(offer.level()), offer.definition().rarity().color())
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("当前: " + (existingLevel <= 0 ? "未拥有" : toRoman(existingLevel)), NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("目标: " + toRoman(offer.level()) + " / 软上限 " + offer.definition().softMaxLevel(), NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("费用: " + offer.dustCost() + " 魔尘, " + offer.expLevelCost() + " 级经验", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        for (String description : EnchantDescriptionRenderer.render(offer.definition(), offer.level())) {
            lore.add(Component.text(description, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(Component.text("点击附魔", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private ItemStack createInfoIcon(Material material, String title, String body) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(title, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text(body, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack emptyToNull(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item;
    }

    private boolean canPay(Player player, int dustCost, int expLevelCost) {
        return countDust(player) >= Math.max(0, dustCost) && player.getLevel() >= Math.max(0, expLevelCost);
    }

    private void pay(Player player, int dustCost, int expLevelCost) {
        removeDust(player, Math.max(0, dustCost));
        if (expLevelCost > 0) {
            player.setLevel(Math.max(0, player.getLevel() - expLevelCost));
        }
    }

    private void sendCostFailure(Player player, int dustCost, int expLevelCost) {
        player.sendMessage(Component.text("材料不足，需要 " + dustCost + " 粉尘和 " + expLevelCost + " 级经验。", NamedTextColor.RED));
        player.sendMessage(Component.text("当前: " + countDust(player) + " 粉尘, " + player.getLevel() + " 级经验。", NamedTextColor.GRAY));
    }

    private int countDust(Player player) {
        int total = 0;
        PlayerInventory inventory = player.getInventory();
        for (ItemStack item : inventory.getContents()) {
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

        PlayerInventory inventory = player.getInventory();
        int remaining = amount;
        for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (!isDust(item)) {
                continue;
            }

            int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take);
            remaining -= take;
            if (item.getAmount() <= 0) {
                inventory.setItem(slot, null);
            } else {
                inventory.setItem(slot, item);
            }
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

    private String itemId(ItemStack item) {
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null || item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(pdc.KEY_ITEM_ID, PersistentDataType.STRING);
    }

    private void refreshStats(Player player) {
        PlayerStatCache statCache = PlayerStatCache.getInstance();
        if (statCache != null) {
            statCache.updateCache(player);
        }
    }

    private String formatRemaining(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
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

    private static final class BookShopHolder implements InventoryHolder {
        private final Map<Integer, EnchantPoolRegistry.NpcBookOffer> offersBySlot = new HashMap<>();
        private Inventory inventory;

        private void bind(int slot, EnchantPoolRegistry.NpcBookOffer offer) {
            offersBySlot.put(slot, offer);
        }

        private EnchantPoolRegistry.NpcBookOffer offerAt(int slot) {
            return offersBySlot.get(slot);
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class SpecialTableHolder implements InventoryHolder {
        private final Map<Integer, SpecialTableOffer> offersBySlot = new HashMap<>();
        private Inventory inventory;
        private ItemStack input;

        private void bind(int slot, SpecialTableOffer offer) {
            offersBySlot.put(slot, offer);
        }

        private SpecialTableOffer offerAt(int slot) {
            return offersBySlot.get(slot);
        }

        private void clearOffers() {
            offersBySlot.clear();
        }

        private ItemStack input() {
            return input;
        }

        private void setInput(ItemStack input) {
            this.input = input;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record SpecialTableOffer(EnchantDefinition definition, int level, int dustCost, int expLevelCost) {
    }
}
