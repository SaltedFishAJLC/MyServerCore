package com.servercore.enchant;

import com.servercore.ServerCorePlugin;
import com.servercore.manager.EnchantManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;

public final class EnchantTableListener implements Listener {

    private final ServerCorePlugin plugin;

    public EnchantTableListener(ServerCorePlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnchantItem(EnchantItemEvent event) {
        EnchantRegistry registry = EnchantRegistry.getInstance();
        EnchantManager enchantManager = EnchantManager.getInstance();
        if (registry == null || registry.pools() == null || enchantManager == null) {
            return;
        }

        var rolled = registry.pools().rollVanillaEnchantTable(event.getItem(), event.getExpLevelCost());
        if (rolled.isEmpty()) {
            event.setCancelled(true);
            event.getEnchanter().sendActionBar(Component.text("没有可用于该物品的自定义附魔。", NamedTextColor.RED));
            return;
        }

        EnchantDefinition definition = rolled.get();
        int level = registry.pools().rollEnchantLevel(definition, event.getExpLevelCost());
        EnchantApplyResult validation = enchantManager.canApplyFromEnchantTable(event.getItem(), definition.id(), level);
        if (!validation.success()) {
            event.setCancelled(true);
            event.getEnchanter().sendActionBar(Component.text(validation.message(), NamedTextColor.RED));
            return;
        }

        event.getEnchantsToAdd().clear();
        Bukkit.getScheduler().runTask(plugin, () -> enchantManager.addFromEnchantTable(event.getItem(), definition.id(), level));
    }
}
