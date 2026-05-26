package com.servercore.manager;

import io.lumine.mythic.bukkit.MythicBukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public class MythicCompatModule {
    
    private final boolean enabled;
    
    public MythicCompatModule(Plugin plugin) {
        this.enabled = plugin.getServer().getPluginManager().getPlugin("MythicMobs") != null;
    }
    
    public boolean isMythicItem(ItemStack item) {
        if (!enabled || item == null) return false;
        try {
            String internalName = MythicBukkit.inst().getItemManager().getMythicTypeFromItem(item);
            return internalName != null;
        } catch (Exception e) {
            return false;
        }
    }
    
    public String getMythicName(ItemStack item) {
        if (!enabled || item == null) return null;
        return MythicBukkit.inst().getItemManager().getMythicTypeFromItem(item);
    }
}
