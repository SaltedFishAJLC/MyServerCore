package com.servercore.manager;

import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import com.servercore.ServerCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class StashManager {
    
    private static StashManager instance;
    private final ServerCorePlugin plugin;
    private final DatabaseManager databaseManager;

    private final Set<Integer> processingIds = ConcurrentHashMap.newKeySet();

    public StashManager(ServerCorePlugin plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        instance = this;
    }

    public static StashManager getInstance() {
        return instance;
    }

    private byte[] serializeItem(ItemStack item) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
            dataOutput.writeObject(item);
            dataOutput.close();
            return outputStream.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
            return new byte[0];
        }
    }

    private ItemStack deserializeItem(byte[] data) {
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            ItemStack item = (ItemStack) dataInput.readObject();
            dataInput.close();
            return item;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public void pushToStash(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) return;
        
        byte[] itemData = serializeItem(item);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "INSERT INTO player_stash (owner_uuid, item_data) VALUES (?, ?)";
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, player.getUniqueId().toString());
                pstmt.setBytes(2, itemData);
                pstmt.executeUpdate();
                
                player.sendMessage(ServerCorePlugin.getMiniMessage().deserialize("<gold>⚠ 你的背包已满，一件物品已被存入暂存箱！请使用 /sc stash 提取。</gold>"));
            } catch (Exception e) {
                plugin.getComponentLogger().error(ServerCorePlugin.getMiniMessage().deserialize("<red>Failed to push item to stash for " + player.getName() + "!</red>"));
                e.printStackTrace();
            }
        });
    }

    public static class StashItem {
        public int id;
        public ItemStack item;
        public StashItem(int id, ItemStack item) {
            this.id = id;
            this.item = item;
        }
    }

    public void getStashItemsAsync(Player player, java.util.function.Consumer<List<StashItem>> callback) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<StashItem> items = new ArrayList<>();
            String sql = "SELECT id, item_data FROM player_stash WHERE owner_uuid = ?";
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, player.getUniqueId().toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        int id = rs.getInt("id");
                        byte[] data = rs.getBytes("item_data");
                        ItemStack item = deserializeItem(data);
                        if (item != null) {
                            items.add(new StashItem(id, item));
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(items));
        });
    }

    public void removeFromStash(int stashDatabaseId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "DELETE FROM player_stash WHERE id = ?";
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, stashDatabaseId);
                pstmt.executeUpdate();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                // 数据库操作完成后释放锁
                processingIds.remove(stashDatabaseId);
            }
        });
    }

    public void openStashGUI(Player player) {
        getStashItemsAsync(player, (stashItems) -> {
            if (stashItems.isEmpty()) {
                player.sendMessage(ServerCorePlugin.getMiniMessage().deserialize("<green>你的暂存箱是空的。</green>"));
                return;
            }

            ChestGui gui = new ChestGui(6, "✦ 暂存箱 ✦");
            PaginatedPane pages = new PaginatedPane(0, 0, 9, 5);

            List<GuiItem> guiItems = new ArrayList<>();
            for (StashItem stashItem : stashItems) {
                // To avoid variable effectively final requirement in lambda
                final GuiItem[] guiItemHolder = new GuiItem[1];
                guiItemHolder[0] = new GuiItem(stashItem.item, event -> {
                    event.setCancelled(true);
                    
                    // 防抖锁
                    if (!processingIds.add(stashItem.id)) {
                        return; // 正在处理该物品的提取，忽略本次点击
                    }

                    Player p = (Player) event.getWhoClicked();
                    
                    // 尝试给玩家
                    java.util.HashMap<Integer, ItemStack> overflow = p.getInventory().addItem(stashItem.item);
                    
                    if (overflow.isEmpty()) {
                        // 成功放入背包
                        removeFromStash(stashItem.id); // 异步删除数据库，完成后会释放锁
                        p.sendMessage(ServerCorePlugin.getMiniMessage().deserialize("<green>成功取出物品！</green>"));
                        
                        // 内存层面立即刷新 GUI
                        guiItems.remove(guiItemHolder[0]);
                        if (guiItems.isEmpty()) {
                            // 箱子取空后自动关闭界面
                            p.closeInventory();
                        } else {
                            pages.clear();
                            pages.populateWithGuiItems(guiItems);
                            
                            // 调整页码防越界
                            if (pages.getPage() >= pages.getPages()) {
                                pages.setPage(Math.max(0, pages.getPages() - 1));
                            }
                            gui.update();
                        }
                    } else {
                        // 包满了，回滚物品（由 Inventory 自动处理，我们只需释放锁并拿走塞入的物品）
                        p.getInventory().removeItem(stashItem.item); // 将刚好放进去的部分也拿出来，保持一致性
                        processingIds.remove(stashItem.id);
                        p.sendMessage(ServerCorePlugin.getMiniMessage().deserialize("<red>你的背包已满，无法取出该物品！</red>"));
                    }
                });
                guiItems.add(guiItemHolder[0]);
            }

            pages.populateWithGuiItems(guiItems);
            gui.addPane(pages);

            StaticPane navPane = new StaticPane(0, 5, 9, 1);
            ItemStack backBtn = new ItemStack(Material.ARROW);
            ItemMeta backMeta = backBtn.getItemMeta();
            backMeta.displayName(ServerCorePlugin.getMiniMessage().deserialize("<white>上一页</white>"));
            backBtn.setItemMeta(backMeta);
            
            ItemStack nextBtn = new ItemStack(Material.ARROW);
            ItemMeta nextMeta = nextBtn.getItemMeta();
            nextMeta.displayName(ServerCorePlugin.getMiniMessage().deserialize("<white>下一页</white>"));
            nextBtn.setItemMeta(nextMeta);

            navPane.addItem(new GuiItem(backBtn, event -> {
                event.setCancelled(true);
                if (pages.getPage() > 0) {
                    pages.setPage(pages.getPage() - 1);
                    gui.update();
                }
            }), 3, 0);

            navPane.addItem(new GuiItem(nextBtn, event -> {
                event.setCancelled(true);
                if (pages.getPage() < pages.getPages() - 1) {
                    pages.setPage(pages.getPage() + 1);
                    gui.update();
                }
            }), 5, 0);

            gui.addPane(navPane);
            gui.show(player);
        });
    }
}
