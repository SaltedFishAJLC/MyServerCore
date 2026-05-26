package com.servercore.manager;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

public class AccessoryManager {

    private static AccessoryManager instance;
    private final Plugin plugin;

    private final NamespacedKey KEY_ACCESSORIES;
    private final NamespacedKey KEY_TALISMAN_BAG;

    public AccessoryManager(Plugin plugin) {
        this.plugin = plugin;
        this.KEY_ACCESSORIES = new NamespacedKey(plugin, "accessories_data");
        this.KEY_TALISMAN_BAG = new NamespacedKey(plugin, "talisman_bag_data");
        instance = this;
    }

    public static AccessoryManager getInstance() {
        return instance;
    }

    /**
     * 将 ItemStack 数组极速序列化为字节流
     */
    private byte[] serializeItems(ItemStack[] items) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
            
            dataOutput.writeInt(items.length);
            for (ItemStack item : items) {
                dataOutput.writeObject(item);
            }
            
            dataOutput.close();
            return outputStream.toByteArray();
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to serialize items!");
            e.printStackTrace();
            return new byte[0];
        }
    }

    /**
     * 从字节流反序列化为 ItemStack 数组
     */
    private ItemStack[] deserializeItems(byte[] data) {
        if (data == null || data.length == 0) return new ItemStack[0];
        
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
            BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);
            
            int length = dataInput.readInt();
            ItemStack[] items = new ItemStack[length];
            
            for (int i = 0; i < length; i++) {
                items[i] = (ItemStack) dataInput.readObject();
            }
            
            dataInput.close();
            return items;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to deserialize items!");
            e.printStackTrace();
            return new ItemStack[0];
        }
    }

    /**
     * 保存 4个饰品槽 的数据到玩家 PDC
     */
    public void saveAccessories(Player player, ItemStack[] items) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.set(KEY_ACCESSORIES, PersistentDataType.BYTE_ARRAY, serializeItems(items));
    }

    /**
     * 加载 4个饰品槽 的数据
     */
    public ItemStack[] loadAccessories(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (pdc.has(KEY_ACCESSORIES, PersistentDataType.BYTE_ARRAY)) {
            byte[] data = pdc.get(KEY_ACCESSORIES, PersistentDataType.BYTE_ARRAY);
            ItemStack[] items = deserializeItems(data);
            if (items.length == 4) return items;
            
            // 补偿或修补数组大小
            ItemStack[] fixed = new ItemStack[4];
            System.arraycopy(items, 0, fixed, 0, Math.min(items.length, 4));
            return fixed;
        }
        return new ItemStack[4];
    }

    /**
     * 保存 护符包 数据到玩家 PDC
     */
    public void saveTalismanBag(Player player, ItemStack[] items) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.set(KEY_TALISMAN_BAG, PersistentDataType.BYTE_ARRAY, serializeItems(items));
    }

    /**
     * 加载 护符包 数据
     * @param expectedSize 护符包容量 (方便后续动态扩容)
     */
    public ItemStack[] loadTalismanBag(Player player, int expectedSize) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (pdc.has(KEY_TALISMAN_BAG, PersistentDataType.BYTE_ARRAY)) {
            byte[] data = pdc.get(KEY_TALISMAN_BAG, PersistentDataType.BYTE_ARRAY);
            ItemStack[] items = deserializeItems(data);
            
            if (items.length == expectedSize) return items;
            
            // 动态扩容或缩减适配
            ItemStack[] fixed = new ItemStack[expectedSize];
            System.arraycopy(items, 0, fixed, 0, Math.min(items.length, expectedSize));
            return fixed;
        }
        return new ItemStack[expectedSize];
    }
}
