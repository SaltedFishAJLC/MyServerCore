package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
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
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * Handles ranged weapon templates that need custom firing behavior.
 */
public class RangedWeaponManager implements Listener {

    private static final double SHORTBOW_ARROW_SPEED = 3.0;
    private static final int MARKSMAN_ASSIST_TICKS = 16;
    private static final double MARKSMAN_MAX_ASSIST_ANGLE = Math.toRadians(10.0);
    private static final double MARKSMAN_MAX_TURN_PER_TICK = Math.toRadians(2.0);
    private static final double MARKSMAN_TARGET_RADIUS = 24.0;
    private static final double MARKSMAN_MIN_TARGET_DISTANCE = 2.0;

    private final ServerCorePlugin plugin;

    public RangedWeaponManager(ServerCorePlugin plugin) {
        this.plugin = plugin;
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
        startMarksmanArrowAssist(player, arrow);

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
            return;
        }

        if (event.getProjectile() instanceof AbstractArrow arrow) {
            Bukkit.getScheduler().runTask(plugin, () -> startMarksmanArrowAssist(player, arrow));
        }
    }

    private void startMarksmanArrowAssist(Player player, AbstractArrow arrow) {
        ClassManager classManager = ClassManager.getInstance();
        if (classManager == null || classManager.getPlayerClass(player) != ClassManager.PlayerClass.MARKSMAN) {
            return;
        }
        if (arrow == null || arrow.getVelocity().lengthSquared() <= 0.0001) {
            return;
        }

        Vector originalDirection = arrow.getVelocity().clone().normalize();
        new BukkitRunnable() {
            private int ticks;

            @Override
            public void run() {
                ticks++;
                if (ticks > MARKSMAN_ASSIST_TICKS || arrow.isDead() || !arrow.isValid()) {
                    cancel();
                    return;
                }

                LivingEntity target = findMarksmanTarget(player, arrow, originalDirection);
                if (target == null) {
                    return;
                }

                Vector velocity = arrow.getVelocity();
                double speed = velocity.length();
                if (speed <= 0.0001) {
                    cancel();
                    return;
                }

                Vector currentDirection = velocity.clone().normalize();
                Vector targetDirection = target.getBoundingBox().getCenter().subtract(arrow.getLocation().toVector()).normalize();
                Vector nextDirection = rotateTowards(currentDirection, targetDirection, MARKSMAN_MAX_TURN_PER_TICK);
                arrow.setVelocity(nextDirection.multiply(speed));
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private LivingEntity findMarksmanTarget(Player player, AbstractArrow arrow, Vector originalDirection) {
        LivingEntity bestTarget = null;
        double bestDistance = MARKSMAN_TARGET_RADIUS * MARKSMAN_TARGET_RADIUS;
        double minDot = Math.cos(MARKSMAN_MAX_ASSIST_ANGLE);

        for (Entity entity : arrow.getNearbyEntities(MARKSMAN_TARGET_RADIUS, MARKSMAN_TARGET_RADIUS, MARKSMAN_TARGET_RADIUS)) {
            if (!(entity instanceof LivingEntity target) || !(target instanceof Enemy) || target.isDead() || !target.isValid()) {
                continue;
            }

            double distance = target.getLocation().distanceSquared(arrow.getLocation());
            if (distance < MARKSMAN_MIN_TARGET_DISTANCE * MARKSMAN_MIN_TARGET_DISTANCE || distance >= bestDistance) {
                continue;
            }

            Vector toTarget = target.getBoundingBox().getCenter().subtract(arrow.getLocation().toVector());
            if (toTarget.lengthSquared() <= 0.0001) {
                continue;
            }
            Vector targetDirection = toTarget.normalize();
            if (originalDirection.dot(targetDirection) < minDot || arrow.getVelocity().clone().normalize().dot(targetDirection) < minDot) {
                continue;
            }
            if (arrow.getWorld().rayTraceBlocks(arrow.getLocation(), targetDirection, Math.sqrt(distance),
                    FluidCollisionMode.NEVER, true) != null) {
                continue;
            }

            bestTarget = target;
            bestDistance = distance;
        }
        return bestTarget;
    }

    private Vector rotateTowards(Vector from, Vector to, double maxRadians) {
        double angle = from.angle(to);
        if (angle <= maxRadians) {
            return to.clone().normalize();
        }

        double t = maxRadians / angle;
        Vector blended = from.clone().multiply(1.0 - t).add(to.clone().multiply(t));
        return blended.lengthSquared() <= 0.0001 ? from.clone().normalize() : blended.normalize();
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
