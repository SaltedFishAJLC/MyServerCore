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
import com.servercore.passive.AbilityCooldownService;

/**
 * Dispatches active weapon abilities while leaving ability cost/effect behavior
 * to registered custom item handlers.
 */
public class WeaponAbilityManager implements Listener {

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
        // Persistent cooldowns are owned by AbilityCooldownService.
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
        AbilityCooldownService cooldownService = AbilityCooldownService.getInstance();
        long remaining = cooldownService == null ? 0L : cooldownService.remainingMillis(player, cooldownKey);
        if (remaining > 0L) {
            player.sendActionBar(Component.text("技能冷却中: " + formatSeconds(remaining) + "s"));
            return AbilityAttempt.HANDLED;
        }

        boolean executed = registry.triggerAbility(player, item, ability);
        if (executed && ability.cooldown() > 0 && cooldownService != null) {
            cooldownService.start(player, cooldownKey, ability.cooldown() * 1000L);
        }
        return AbilityAttempt.HANDLED;
    }

    private boolean isRightClick(Action action) {
        return action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
    }

    private String cooldownKey(CustomItemRegistry registry, ItemStack item, CustomItemRegistry.AbilityDefinition ability) {
        AbilityCooldownService cooldownService = AbilityCooldownService.getInstance();
        String group = String.valueOf(ability.options().getOrDefault("cooldown_group", ""));
        String scope = String.valueOf(ability.options().getOrDefault("cooldown_scope", "SHARED"));
        String sourceId = AccessoryManager.getInstance() == null
                ? registry.getItemId(item)
                : AccessoryManager.getInstance().ensureItemInstanceId(item);
        return cooldownService == null
                ? "shared:" + ability.id()
                : cooldownService.key(ability.id(), group, scope, sourceId);
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
