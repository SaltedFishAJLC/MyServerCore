package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Locale;

public class ItemDurabilityManager implements Listener {

    private static ItemDurabilityManager instance;

    private final ServerCorePlugin plugin;

    public ItemDurabilityManager(ServerCorePlugin plugin) {
        this.plugin = plugin;
        instance = this;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                migratePlayerItems(player);
            }
        });
    }

    public static ItemDurabilityManager getInstance() {
        return instance;
    }

    public static boolean shouldForceUnbreakable(Material material) {
        if (material == null || material.isAir() || material.getMaxDurability() <= 0) {
            return false;
        }

        String name = material.name().toUpperCase(Locale.ROOT);
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
                || material == Material.FISHING_ROD
                || material == Material.MACE
                || material == Material.SHEARS
                || material == Material.FLINT_AND_STEEL
                || material == Material.ELYTRA
                || material == Material.BRUSH;
    }

    public static void applyDurabilityRule(ItemStack item, ItemMeta meta) {
        if (item == null || meta == null || !shouldForceUnbreakable(item.getType())) {
            return;
        }

        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        if (meta instanceof Damageable damageable && damageable.getDamage() != 0) {
            damageable.setDamage(0);
        }
    }

    public static boolean normalizeItem(ItemStack item) {
        if (item == null || item.getType().isAir() || !shouldForceUnbreakable(item.getType())) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        boolean changed = !meta.isUnbreakable() || !meta.hasItemFlag(ItemFlag.HIDE_UNBREAKABLE);
        if (meta instanceof Damageable damageable && damageable.getDamage() != 0) {
            changed = true;
        }
        applyDurabilityRule(item, meta);
        if (changed) {
            item.setItemMeta(meta);
        }
        return changed;
    }

    public void migratePlayerItems(Player player) {
        if (player == null) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        normalizeInventory(inventory);
        ItemStack[] armor = inventory.getArmorContents();
        boolean armorChanged = false;
        for (ItemStack item : armor) {
            armorChanged |= normalizeItem(item);
        }
        if (armorChanged) {
            inventory.setArmorContents(armor);
        }

        normalizeItem(inventory.getItemInOffHand());
        normalizeInventory(player.getEnderChest());
    }

    private void normalizeInventory(Inventory inventory) {
        if (inventory == null) {
            return;
        }

        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (normalizeItem(item)) {
                inventory.setItem(slot, item);
            }
        }
    }

    private void scheduleMigration(Player player) {
        if (player != null) {
            plugin.getServer().getScheduler().runTask(plugin, () -> migratePlayerItems(player));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        ItemStack item = event.getItem();
        if (!shouldForceUnbreakable(item == null ? null : item.getType())) {
            return;
        }

        normalizeItem(item);
        event.setCancelled(true);
        event.setDamage(0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player) {
            normalizeItem(event.getItem().getItemStack());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            scheduleMigration(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            scheduleMigration(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            scheduleMigration(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        scheduleMigration(event.getPlayer());
    }
}
