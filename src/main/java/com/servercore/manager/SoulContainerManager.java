package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Item;
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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SoulContainerManager {

    private final ServerCorePlugin plugin;
    private final DatabaseManager databaseManager;

    public SoulContainerManager(ServerCorePlugin plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
    }

    private byte[] serializeItemsMap(Map<Integer, ItemStack> itemsMap) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
            
            dataOutput.writeInt(itemsMap.size());
            for (Map.Entry<Integer, ItemStack> entry : itemsMap.entrySet()) {
                dataOutput.writeInt(entry.getKey());
                dataOutput.writeObject(entry.getValue());
            }
            
            dataOutput.close();
            return outputStream.toByteArray();
        } catch (Exception e) {
            plugin.getComponentLogger().error(ServerCorePlugin.getMiniMessage().deserialize("<red>Failed to serialize soul container items!</red>"));
            e.printStackTrace();
            return new byte[0];
        }
    }

    private Map<Integer, ItemStack> deserializeItemsMap(byte[] data) {
        Map<Integer, ItemStack> itemsMap = new HashMap<>();
        if (data == null || data.length == 0) return itemsMap;
        
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            
            int size = dataInput.readInt();
            for (int i = 0; i < size; i++) {
                int slot = dataInput.readInt();
                ItemStack item = (ItemStack) dataInput.readObject();
                itemsMap.put(slot, item);
            }
            
            dataInput.close();
        } catch (Exception e) {
            plugin.getComponentLogger().error(ServerCorePlugin.getMiniMessage().deserialize("<red>Failed to deserialize soul container items!</red>"));
            e.printStackTrace();
        }
        return itemsMap;
    }

    public void createSoulContainer(Player owner, Location deathLocation, Map<Integer, ItemStack> newItemsMap) {
        if (newItemsMap.isEmpty()) return;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // 1. 查找是否存在旧容器
            String selectSql = "SELECT container_id, items_data FROM soul_containers WHERE owner_uuid = ?";
            String oldContainerId = null;
            byte[] oldItemsData = null;
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
                pstmt.setString(1, owner.getUniqueId().toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        oldContainerId = rs.getString("container_id");
                        oldItemsData = rs.getBytes("items_data");
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            // 2. 如果存在，将其数据合并，并删除旧记录
            Map<Integer, ItemStack> mergedMap = new HashMap<>(newItemsMap);
            if (oldContainerId != null && oldItemsData != null) {
                Map<Integer, ItemStack> oldItemsMap = deserializeItemsMap(oldItemsData);
                int virtualSlot = 1000; // 使用高位虚假槽位，防止冲突
                for (ItemStack item : oldItemsMap.values()) {
                    if (item != null && !item.getType().isAir()) {
                        mergedMap.put(virtualSlot++, item);
                    }
                }
                
                String deleteSql = "DELETE FROM soul_containers WHERE container_id = ?";
                try (Connection conn = databaseManager.getConnection();
                     PreparedStatement pstmt = conn.prepareStatement(deleteSql)) {
                    pstmt.setString(1, oldContainerId);
                    pstmt.executeUpdate();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            // 3. 正常生成新容器
            String containerId = UUID.randomUUID().toString();
            byte[] itemsData = serializeItemsMap(mergedMap);

            String insertSql = "INSERT INTO soul_containers (container_id, owner_uuid, items_data) VALUES (?, ?, ?)";
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                pstmt.setString(1, containerId);
                pstmt.setString(2, owner.getUniqueId().toString());
                pstmt.setBytes(3, itemsData);
                pstmt.executeUpdate();
            } catch (Exception e) {
                plugin.getComponentLogger().error(ServerCorePlugin.getMiniMessage().deserialize("<red>Failed to save soul container " + containerId + " to DB!</red>"));
                e.printStackTrace();
                return;
            }

            // 同步生成实体
            Bukkit.getScheduler().runTask(plugin, () -> {
                ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
                ItemMeta meta = book.getItemMeta();
                meta.displayName(ServerCorePlugin.getMiniMessage().deserialize("<gold>[" + owner.getName() + "的灵魂容器]</gold>"));
                book.setItemMeta(meta);

                Item itemEntity = deathLocation.getWorld().dropItem(deathLocation, book);
                itemEntity.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                itemEntity.teleport(deathLocation);
                itemEntity.setGlowing(true);
                itemEntity.setGravity(false);
                itemEntity.setInvulnerable(true);
                itemEntity.setUnlimitedLifetime(true);
                itemEntity.setCanMobPickup(false);
                itemEntity.setPickupDelay(0);
                
                Component customName = ServerCorePlugin.getMiniMessage().deserialize("<gold>[" + owner.getName() + "的灵魂容器]</gold>");
                itemEntity.customName(customName);
                itemEntity.setCustomNameVisible(true);

                // 打上 PDC 标签
                PDCManager pdc = PDCManager.getInstance();
                pdc.setString(itemEntity.getItemStack(), pdc.KEY_SOUL_CONTAINER_ID, containerId);
                pdc.setString(itemEntity.getItemStack(), pdc.KEY_SOUL_OWNER_UUID, owner.getUniqueId().toString());
            });
        });
    }

    public void restoreSoulContainer(Player owner, String containerId, Item itemEntity) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "SELECT items_data FROM soul_containers WHERE container_id = ?";
            byte[] itemsData = null;
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, containerId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        itemsData = rs.getBytes("items_data");
                    }
                }
            } catch (Exception e) {
                plugin.getComponentLogger().error(ServerCorePlugin.getMiniMessage().deserialize("<red>Failed to load soul container " + containerId + " from DB!</red>"));
                e.printStackTrace();
                return;
            }

            if (itemsData == null) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    owner.sendMessage(ServerCorePlugin.getMiniMessage().deserialize("<yellow>该灵魂容器的数据已被合并到最新的容器中并已失效。</yellow>"));
                    if (itemEntity != null && itemEntity.isValid()) {
                        itemEntity.remove();
                    }
                });
                return;
            }

            Map<Integer, ItemStack> itemsMap = deserializeItemsMap(itemsData);

            // 回到主线程执行还原逻辑
            Bukkit.getScheduler().runTask(plugin, () -> {
                StashManager stashManager = StashManager.getInstance();
                for (Map.Entry<Integer, ItemStack> entry : itemsMap.entrySet()) {
                    int slot = entry.getKey();
                    ItemStack item = entry.getValue();
                    
                    if (slot >= 100 && slot < 200) {
                        // 饰品槽恢复 (100-103)
                        int accIndex = slot - 100;
                        ItemStack[] accessories = AccessoryManager.getInstance().loadAccessories(owner);
                        if (accessories[accIndex] == null || accessories[accIndex].getType().isAir()) {
                            accessories[accIndex] = item;
                            AccessoryManager.getInstance().saveAccessories(owner, accessories);
                        } else {
                            stashManager.pushToStash(owner, item);
                        }
                    } else {
                        // 原版背包或虚假槽位(合并的旧物品 >= 1000) 统一恢复
                        // 如果是原版合法槽位(0-40)且空，直接放回
                        if (slot < 100) {
                            ItemStack existing = owner.getInventory().getItem(slot);
                            if (existing == null || existing.getType().isAir()) {
                                owner.getInventory().setItem(slot, item);
                                continue;
                            }
                        }
                        
                        // 否则尝试放入空闲槽位
                        HashMap<Integer, ItemStack> overflow = owner.getInventory().addItem(item);
                        if (!overflow.isEmpty()) {
                            // 连空闲槽位都没了，推入 Stash
                            for (ItemStack overflowItem : overflow.values()) {
                                stashManager.pushToStash(owner, overflowItem);
                            }
                        }
                    }
                }

                owner.sendMessage(ServerCorePlugin.getMiniMessage().deserialize("<green>已成功回收灵魂容器内的物资！</green>"));
                PlayerStatCache.getInstance().updateCache(owner);
                
                if (itemEntity != null && itemEntity.isValid()) {
                    itemEntity.remove();
                }

                // 销毁数据
                destroySoulContainer(containerId);
            });
        });
    }

    public void destroySoulContainer(String containerId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "DELETE FROM soul_containers WHERE container_id = ?";
            try (Connection conn = databaseManager.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, containerId);
                pstmt.executeUpdate();
            } catch (Exception e) {
                plugin.getComponentLogger().error(ServerCorePlugin.getMiniMessage().deserialize("<red>Failed to delete soul container " + containerId + " from DB!</red>"));
                e.printStackTrace();
            }
        });
    }
}
