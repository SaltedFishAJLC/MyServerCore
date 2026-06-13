package com.servercore.enchant;

import com.servercore.ServerCorePlugin;
import com.servercore.manager.CustomItemRegistry;
import com.servercore.manager.EnchantManager;
import com.servercore.manager.ItemFormatManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.inventory.GrindstoneInventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public final class EnchantGrindstoneListener implements Listener {

    public EnchantGrindstoneListener(ServerCorePlugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareGrindstone(PrepareGrindstoneEvent event) {
        ItemStack result = buildClearResult(event.getInventory().getItem(0));
        if (result == null) {
            result = buildClearResult(event.getInventory().getItem(1));
        }
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
        ItemStack source = inventory.getItem(0);
        if (source == null || source.getType().isAir()) {
            source = inventory.getItem(1);
        }
        refund(player, source);
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

    private void refund(Player player, ItemStack source) {
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
            ItemStack dustItem = null;
            CustomItemRegistry customItemRegistry = CustomItemRegistry.getInstance();
            if (customItemRegistry != null) {
                dustItem = customItemRegistry.createItem("magic_dust", dust);
            }
            if (dustItem == null) {
                dustItem = new ItemStack(Material.GLOWSTONE_DUST, Math.min(dust, Material.GLOWSTONE_DUST.getMaxStackSize()));
            }
            for (ItemStack leftover : player.getInventory().addItem(dustItem).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }
}
