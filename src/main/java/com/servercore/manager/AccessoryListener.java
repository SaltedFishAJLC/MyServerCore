package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class AccessoryListener implements Listener {

    private final ServerCorePlugin plugin;
    public static final String ACC_GUI_TITLE = "✦ 独立饰品槽 ✦";
    public static final String TALISMAN_GUI_TITLE = "✦ 护符包 ✦";

    private final int[] ACC_SLOTS = {10, 12, 14, 16}; // 饰品槽位
    private final int BUTTON_TALISMAN = 26; // 打开护符包的按钮

    public AccessoryListener(ServerCorePlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /**
     * 打开独立饰品栏
     */
    public void openAccessoryMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, Component.text(ACC_GUI_TITLE));

        // 填充背景玻璃
        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = glass.getItemMeta();
        meta.displayName(Component.empty());
        glass.setItemMeta(meta);

        for (int i = 0; i < 27; i++) {
            inv.setItem(i, glass);
        }

        // 清空允许放入饰品的槽位并放入已保存的饰品
        ItemStack[] saved = AccessoryManager.getInstance().loadAccessories(player);
        for (int i = 0; i < ACC_SLOTS.length; i++) {
            int slot = ACC_SLOTS[i];
            if (i < saved.length && saved[i] != null) {
                inv.setItem(slot, saved[i]);
            } else {
                inv.setItem(slot, null); // 留空
            }
        }

        // 放入打开护符包的按钮
        ItemStack talismanBtn = new ItemStack(Material.CHEST);
        ItemMeta btnMeta = talismanBtn.getItemMeta();
        btnMeta.displayName(ServerCorePlugin.getMiniMessage().deserialize("<gold>打开护符包</gold>"));
        talismanBtn.setItemMeta(btnMeta);
        inv.setItem(BUTTON_TALISMAN, talismanBtn);

        player.openInventory(inv);
    }

    /**
     * 打开护符包
     */
    public void openTalismanBag(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, Component.text(TALISMAN_GUI_TITLE));
        ItemStack[] saved = AccessoryManager.getInstance().loadTalismanBag(player, 54);
        inv.setContents(saved);
        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        
        String title = ServerCorePlugin.getMiniMessage().serialize(event.getView().title());
        if (title.contains("独立饰品槽")) {
            // 如果玩家按住 Shift 从底部点击，直接拦截以防止混乱
            if (event.getClickedInventory() == event.getView().getBottomInventory() && event.getClick().isShiftClick()) {
                event.setCancelled(true);
                player.sendMessage(ServerCorePlugin.getMiniMessage().deserialize("<red>请手动将饰品拖入槽位！</red>"));
                return;
            }

            int slot = event.getRawSlot();
            // 允许操作下方的玩家背包
            if (slot >= 27) return;

            // 检查点击的是否是饰品槽
            boolean isAccSlot = false;
            for (int s : ACC_SLOTS) {
                if (slot == s) {
                    isAccSlot = true;
                    break;
                }
            }

            if (slot == BUTTON_TALISMAN) {
                event.setCancelled(true);
                // 必须在下一个 tick 打开新界面，否则 Bukkit 会报错
                Bukkit.getScheduler().runTask(plugin, () -> openTalismanBag(player));
                return;
            }

            if (!isAccSlot) {
                event.setCancelled(true); // 拦截背景玻璃的点击
            } else {
                // 如果是饰品槽位，防止快捷键交换
                if (event.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY) {
                    event.setCancelled(true);
                    return;
                }
                
                // 检查放入的物品类型是否符合部位
                ItemStack cursor = event.getCursor();
                if (cursor != null && !cursor.getType().isAir()) {
                    RequirementManager requirementManager = RequirementManager.getInstance();
                    if (requirementManager != null && !requirementManager.meetsRequirement(player, cursor)) {
                        event.setCancelled(true);
                        requirementManager.sendRequirementDenyMessage(player, cursor);
                        return;
                    }

                    String accType = PDCManager.getInstance().getString(cursor, PDCManager.getInstance().KEY_ACC_TYPE);
                    String requiredType = switch (slot) {
                        case 10 -> "necklace";
                        case 12 -> "bracelet";
                        case 14 -> "ring";
                        case 16 -> "belt";
                        default -> "";
                    };
                    if (!requiredType.equals(accType)) {
                        event.setCancelled(true);
                        player.sendMessage(ServerCorePlugin.getMiniMessage().deserialize("<red>⚠ 你不能把这个饰品放到这里！只能放: " + requiredType + " !</red>"));
                    }
                }
            }
        } else if (title.contains("护符包")) {
            // 护符包的所有格子都可以自由存取，除了可以限制某些特定物品（后期可加）
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        String title = ServerCorePlugin.getMiniMessage().serialize(event.getView().title());

        if (title.contains("独立饰品槽")) {
            Inventory inv = event.getInventory();
            ItemStack[] accessories = new ItemStack[4];
            for (int i = 0; i < ACC_SLOTS.length; i++) {
                accessories[i] = inv.getItem(ACC_SLOTS[i]);
            }
            AccessoryManager.getInstance().saveAccessories(player, accessories);
            // 更新缓存
            PlayerStatCache.getInstance().updateCache(player);
            player.sendMessage(ServerCorePlugin.getMiniMessage().deserialize("<gray>饰品栏已保存并更新属性。</gray>"));

        } else if (title.contains("护符包")) {
            Inventory inv = event.getInventory();
            AccessoryManager.getInstance().saveTalismanBag(player, inv.getContents());
            // 更新缓存
            PlayerStatCache.getInstance().updateCache(player);
            player.sendMessage(ServerCorePlugin.getMiniMessage().deserialize("<gray>护符包已保存并更新属性。</gray>"));
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // 玩家加入时生成快照
        PlayerStatCache.getInstance().updateCache(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // 玩家退出时清除内存
        PlayerStatCache.getInstance().remove(event.getPlayer());
    }

    @EventHandler
    public void onArmorChange(PlayerArmorChangeEvent event) {
        // 当玩家更换装备时更新快照
        PlayerStatCache.getInstance().updateCache(event.getPlayer());
    }
}
