package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;

/**
 * Handles ranged weapon templates that need custom firing behavior.
 */
public class RangedWeaponManager implements Listener {

    private static final double SHORTBOW_ARROW_SPEED = 3.0;

    public RangedWeaponManager(ServerCorePlugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShortbowUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isRightClick(event.getAction())) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        WeaponTemplateManager templateManager = WeaponTemplateManager.getInstance();
        if (item == null || templateManager == null) {
            return;
        }

        WeaponTemplateManager.WeaponTemplate template = templateManager.getTemplate(item);
        if (template != WeaponTemplateManager.WeaponTemplate.SHORTBOW) {
            return;
        }

        event.setCancelled(true);

        if (!templateManager.canUseMainHandWeapon(player, item)) {
            player.sendActionBar(net.kyori.adventure.text.Component.text(templateManager.isTwoHandBlocked(player, item)
                    ? "双手武器需要空出副手。"
                    : "这件武器不能在主手使用。"));
            return;
        }

        if (player.hasCooldown(item.getType())) {
            return;
        }

        boolean freeShot = templateManager.canShootWithoutConsumingArrow(player, item);
        if (!freeShot && !consumeOneArrow(player.getInventory())) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.35f, 0.7f);
            return;
        }

        Vector velocity = player.getEyeLocation().getDirection().normalize().multiply(SHORTBOW_ARROW_SPEED);
        Arrow arrow = player.launchProjectile(Arrow.class, velocity);
        applyBowEnchantments(item, arrow);
        if (freeShot) {
            arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        }

        int cooldownTicks = templateManager.getCooldownTicks(player, template);
        if (cooldownTicks > 0) {
            player.setCooldown(item.getType(), cooldownTicks);
        }
        player.swingMainHand();
        player.playSound(player.getLocation(), Sound.ENTITY_ARROW_SHOOT, 0.8f, 1.35f);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBowShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        WeaponTemplateManager templateManager = WeaponTemplateManager.getInstance();
        ItemStack bow = event.getBow();
        if (templateManager == null || bow == null) {
            return;
        }

        if (event.getHand() != EquipmentSlot.HAND || !templateManager.canUseMainHandWeapon(player, bow)) {
            event.setCancelled(true);
            player.sendActionBar(net.kyori.adventure.text.Component.text(templateManager.isTwoHandBlocked(player, bow)
                    ? "双手武器需要空出副手。"
                    : "远程武器只能从主手使用。"));
        }
    }

    private boolean isRightClick(Action action) {
        return action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
    }

    private boolean consumeOneArrow(PlayerInventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType() != Material.ARROW) {
                continue;
            }

            int amount = stack.getAmount();
            if (amount <= 1) {
                inventory.setItem(slot, null);
            } else {
                stack.setAmount(amount - 1);
                inventory.setItem(slot, stack);
            }
            return true;
        }
        return false;
    }

    private void applyBowEnchantments(ItemStack bow, Arrow arrow) {
        int power = bow.getEnchantmentLevel(Enchantment.POWER);
        if (power > 0) {
            arrow.setDamage(arrow.getDamage() + power * 0.5 + 0.5);
        }

        int punch = bow.getEnchantmentLevel(Enchantment.PUNCH);
        if (punch > 0) {
            arrow.setKnockbackStrength(punch);
        }

        if (bow.getEnchantmentLevel(Enchantment.FLAME) > 0) {
            arrow.setFireTicks(100);
        }
    }
}
