package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

/**
 * Custom shield block resolution. Shield absorption is applied before normal
 * damage reduction systems.
 */
public class ShieldManager {

    private static final double DEFAULT_BLOCK_THRESHOLD = 10.0;
    private static final double DEFAULT_EFFECTIVE_BLOCK = 0.6;
    private static final double DEFAULT_COOLDOWN_SECONDS = 2.0;
    private static final double SHIELD_BREAK_MULTIPLIER = 2.5;

    private static ShieldManager instance;

    public ShieldManager(ServerCorePlugin plugin) {
        instance = this;
    }

    public static ShieldManager getInstance() {
        return instance;
    }

    public double applyShieldBeforeReductions(Player defender, EntityDamageEvent event, double incomingDamage) {
        if (defender == null || event == null || incomingDamage <= 0.0 || !defender.isBlocking()) {
            return incomingDamage;
        }

        ItemStack shield = defender.getInventory().getItemInOffHand();
        if (!isShield(shield) || defender.hasCooldown(shield.getType())) {
            return incomingDamage;
        }

        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return incomingDamage;
        }

        double threshold = readStatOrDefault(pdc, shield, pdc.KEY_SHIELD_BLOCK_THRESHOLD, DEFAULT_BLOCK_THRESHOLD);
        double effectiveBlock = clamp(readStatOrDefault(pdc, shield, pdc.KEY_SHIELD_EFFECTIVE_BLOCK, DEFAULT_EFFECTIVE_BLOCK), 0.0, 1.0);
        double cooldownSeconds = Math.max(0.0, readStatOrDefault(pdc, shield, pdc.KEY_SHIELD_COOLDOWN_SECONDS, DEFAULT_COOLDOWN_SECONDS));

        if (threshold <= 0.0) {
            return incomingDamage;
        }

        if (incomingDamage <= threshold) {
            defender.getWorld().playSound(defender.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.85f, 1.15f);
            return 0.0;
        }

        boolean shieldBreak = incomingDamage > threshold * SHIELD_BREAK_MULTIPLIER;
        double blockedDamage = threshold * effectiveBlock;
        double remainingDamage = Math.max(0.0, incomingDamage - blockedDamage);
        int cooldownTicks = (int) Math.round(cooldownSeconds * (shieldBreak ? SHIELD_BREAK_MULTIPLIER : 1.0) * 20.0);
        if (cooldownTicks > 0) {
            defender.setCooldown(shield.getType(), cooldownTicks);
        }

        Entity attacker = findAttacker(event);
        knockBackBoth(defender, attacker);
        defender.getWorld().playSound(defender.getLocation(), shieldBreak ? Sound.ITEM_SHIELD_BREAK : Sound.ITEM_SHIELD_BLOCK, 1.0f, shieldBreak ? 0.75f : 0.9f);
        defender.sendActionBar(Component.text(shieldBreak ? "盾牌崩裂!" : "格挡被突破!"));
        return remainingDamage;
    }

    private boolean isShield(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        if (item.getType() == Material.SHIELD) {
            return true;
        }

        WeaponTemplateManager templateManager = WeaponTemplateManager.getInstance();
        return templateManager != null && templateManager.getTemplate(item) == WeaponTemplateManager.WeaponTemplate.SHIELD;
    }

    private double readStatOrDefault(PDCManager pdc, ItemStack item, org.bukkit.NamespacedKey key, double fallback) {
        double value = pdc.getStat(item, key);
        return Math.abs(value) < 0.0001 ? fallback : value;
    }

    private Entity findAttacker(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntityEvent)) {
            return null;
        }

        Entity damager = byEntityEvent.getDamager();
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            return shooter instanceof Entity shooterEntity ? shooterEntity : damager;
        }
        return damager;
    }

    private void knockBackBoth(Player defender, Entity attacker) {
        if (attacker == null || attacker.equals(defender)) {
            return;
        }

        Vector fromAttackerToDefender = defender.getLocation().toVector().subtract(attacker.getLocation().toVector());
        if (fromAttackerToDefender.lengthSquared() < 0.0001) {
            fromAttackerToDefender = defender.getEyeLocation().getDirection();
        }
        Vector defenderVelocity = fromAttackerToDefender.normalize().multiply(0.55).setY(0.22);
        Vector attackerVelocity = defenderVelocity.clone().multiply(-0.65).setY(0.18);
        defender.setVelocity(defender.getVelocity().multiply(0.35).add(defenderVelocity));
        attacker.setVelocity(attacker.getVelocity().multiply(0.35).add(attackerVelocity));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
