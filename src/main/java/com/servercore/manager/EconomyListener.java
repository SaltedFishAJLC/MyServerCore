package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class EconomyListener implements Listener {

    private final EconomyManager economyManager;

    public EconomyListener(ServerCorePlugin plugin, EconomyManager economyManager) {
        this.economyManager = economyManager;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        // 玩家加入时异步加载金币数据
        economyManager.loadPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        // 玩家退出时异步保存并从内存中卸载金币数据
        economyManager.saveAndUnloadPlayer(event.getPlayer().getUniqueId());
    }
}
