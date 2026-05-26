package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.Map;

public class DeathListener implements Listener {

    private final ServerCorePlugin plugin;
    private final SoulContainerManager soulContainerManager;
    private final EconomyManager economyManager;

    public DeathListener(ServerCorePlugin plugin, SoulContainerManager soulContainerManager, EconomyManager economyManager) {
        this.plugin = plugin;
        this.soulContainerManager = soulContainerManager;
        this.economyManager = economyManager;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    private Location getSmartSpawnLocation(Location deathLoc) {
        Location spawnLoc = deathLoc.clone();
        World world = spawnLoc.getWorld();

        // 虚空判定
        if (spawnLoc.getY() < -64) {
            spawnLoc.setY(64);
            return spawnLoc;
        }

        Block currentBlock = spawnLoc.getBlock();
        
        // 岩浆中死亡，向上寻找安全点
        if (currentBlock.getType() == Material.LAVA || currentBlock.getType() == Material.WATER && currentBlock.getType().name().contains("LAVA")) {
            while (spawnLoc.getY() < world.getMaxHeight()) {
                spawnLoc.add(0, 1, 0);
                if (spawnLoc.getBlock().getType() != Material.LAVA) {
                    break;
                }
            }
            return spawnLoc;
        }

        // 水中死亡，保持原位
        if (currentBlock.getType() == Material.WATER) {
            return spawnLoc;
        }

        // 陆地情况
        // 检查上方两格是否为空心方块 (Passable)
        Block blockAbove1 = spawnLoc.clone().add(0, 1, 0).getBlock();
        Block blockAbove2 = spawnLoc.clone().add(0, 2, 0).getBlock();

        if (blockAbove1.isPassable() && blockAbove2.isPassable()) {
            spawnLoc.add(0, 2, 0);
        } else {
            // 空间狭小，保持原高度（如果原来就是实体内部，游戏自身会做一定程度的推挤，这里保持同高）
        }

        return spawnLoc;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();

        // 1. 清空原版掉落
        event.getDrops().clear();

        // 2. 提取背包、装备栏、副手以及 4个饰品槽
        Map<Integer, ItemStack> savedItems = new HashMap<>();
        PlayerInventory inv = player.getInventory();

        // 保存原版背包 (0-35), 装备 (36-39), 副手 (40)
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && !item.getType().isAir()) {
                savedItems.put(i, item.clone());
                inv.setItem(i, null);
            }
        }

        // 保存 4个饰品槽
        ItemStack[] accessories = AccessoryManager.getInstance().loadAccessories(player);
        for (int i = 0; i < accessories.length; i++) {
            if (accessories[i] != null && !accessories[i].getType().isAir()) {
                // 用 100+ 的槽位 ID 表示饰品槽
                savedItems.put(100 + i, accessories[i].clone());
                accessories[i] = null;
            }
        }
        AccessoryManager.getInstance().saveAccessories(player, accessories);

        // 注意：护符包数据保持不变，相当于保留在身上不掉落

        // 3. 计算智能生成点并生成灵魂容器
        Location smartLoc = getSmartSpawnLocation(player.getLocation());
        soulContainerManager.createSoulContainer(player, smartLoc, savedItems);

        // 4. 经济惩罚
        long balance = economyManager.getBalance(player.getUniqueId());
        long penalty = balance / 2;
        economyManager.removeBalance(player.getUniqueId(), penalty);

        player.sendMessage(ServerCorePlugin.getMiniMessage().deserialize("<red>⚠ 你死了！你的灵魂散落在坐标: " + smartLoc.getBlockX() + ", " + smartLoc.getBlockY() + ", " + smartLoc.getBlockZ() + "，你损失了 " + penalty + " 金币！</red>"));
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        Item itemEntity = event.getItem();
        ItemStack itemStack = itemEntity.getItemStack();

        PDCManager pdc = PDCManager.getInstance();
        String containerId = pdc.getString(itemStack, pdc.KEY_SOUL_CONTAINER_ID);

        if (containerId != null) {
            // 阻止物理拾取
            event.setCancelled(true);

            String ownerUuid = pdc.getString(itemStack, pdc.KEY_SOUL_OWNER_UUID);
            if (player.getUniqueId().toString().equals(ownerUuid)) {
                // 立即移除实体，防止因为异步查询导致重复拾取事件触发
                itemEntity.remove();
                // 还原
                soulContainerManager.restoreSoulContainer(player, containerId, null);
            } else {
                // 非拥有者
                player.sendActionBar(ServerCorePlugin.getMiniMessage().deserialize("<red>你无法触碰他人的灵魂容器</red>"));
            }
        }
    }
}
