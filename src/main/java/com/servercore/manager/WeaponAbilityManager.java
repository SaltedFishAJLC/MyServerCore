package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Dispatches active weapon abilities while leaving ability cost/effect behavior
 * to registered custom item handlers.
 */
public class WeaponAbilityManager implements Listener {

    private final Map<UUID, Map<String, Long>> cooldownUntil = new HashMap<>();

    public WeaponAbilityManager(ServerCorePlugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onWeaponAbilityUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isRightClick(event.getAction())) {
            return;
        }

        Player player = event.getPlayer();
        if (player.isSneaking() && tryTrigger(player, player.getInventory().getItemInOffHand(), EquipmentSlot.OFF_HAND).handled()) {
            event.setCancelled(true);
            return;
        }

        if (tryTrigger(player, player.getInventory().getItemInMainHand(), EquipmentSlot.HAND).handled()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cooldownUntil.remove(event.getPlayer().getUniqueId());
    }

    private AbilityAttempt tryTrigger(Player player, ItemStack item, EquipmentSlot hand) {
        if (item == null || item.getType().isAir()) {
            return AbilityAttempt.NONE;
        }

        WeaponTemplateManager templateManager = WeaponTemplateManager.getInstance();
        if (templateManager != null) {
            boolean usable = hand == EquipmentSlot.OFF_HAND
                    ? templateManager.canUseOffhandEquipment(player, item)
                    : templateManager.canUseMainHandWeapon(player, item);
            if (!usable) {
                return AbilityAttempt.NONE;
            }
        }

        CustomItemRegistry registry = CustomItemRegistry.getInstance();
        if (registry == null) {
            return AbilityAttempt.NONE;
        }

        CustomItemRegistry.AbilityDefinition ability = registry.getActiveAbility(item, hand);
        if (ability == null) {
            return AbilityAttempt.NONE;
        }

        String cooldownKey = cooldownKey(registry, item, ability);
        long now = System.currentTimeMillis();
        long readyAt = cooldownUntil
                .getOrDefault(player.getUniqueId(), Map.of())
                .getOrDefault(cooldownKey, 0L);
        if (readyAt > now) {
            player.sendActionBar(Component.text("技能冷却中: " + formatSeconds(readyAt - now) + "s"));
            return AbilityAttempt.HANDLED;
        }

        boolean executed = registry.triggerAbility(player, item, ability);
        if (executed && ability.cooldown() > 0) {
            cooldownUntil
                    .computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>())
                    .put(cooldownKey, now + ability.cooldown() * 1000L);
        }
        return AbilityAttempt.HANDLED;
    }

    private boolean isRightClick(Action action) {
        return action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
    }

    private String cooldownKey(CustomItemRegistry registry, ItemStack item, CustomItemRegistry.AbilityDefinition ability) {
        String itemId = registry.getItemId(item);
        return (itemId == null || itemId.isBlank() ? item.getType().name() : itemId) + ":" + ability.id();
    }

    private long formatSeconds(long millis) {
        return Math.max(1L, (long) Math.ceil(millis / 1000.0));
    }

    private enum AbilityAttempt {
        NONE,
        HANDLED;

        private boolean handled() {
            return this == HANDLED;
        }
    }
}
