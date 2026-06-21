package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import com.servercore.enchant.EnchantEffectService;
import com.servercore.enchant.EnchantStatBundle;
import com.servercore.enchant.EnchantStatResolver;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Custom shield block resolution. Shield absorption is applied before normal
 * damage reduction systems.
 */
public class ShieldManager implements Listener {

    private static final double DEFAULT_BLOCK_THRESHOLD = 10.0;
    private static final double DEFAULT_EFFECTIVE_BLOCK = 0.6;
    private static final double DEFAULT_COOLDOWN_SECONDS = 2.0;
    private static final double SHIELD_BREAK_MULTIPLIER = 2.5;

    private static ShieldManager instance;
    private final Map<UUID, Long> shieldRaisedAt = new HashMap<>();

    public ShieldManager(ServerCorePlugin plugin) {
        instance = this;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public static ShieldManager getInstance() {
        return instance;
    }

    public double applyShieldBeforeReductions(Player defender, EntityDamageEvent event, double incomingDamage) {
        ShieldBlockPlan plan = previewShieldBlock(defender, event, incomingDamage);
        plan.commit().run();
        return plan.result().remainingDamage();
    }

    public ShieldBlockResult resolveShieldBlock(Player defender, EntityDamageEvent event, double incomingDamage) {
        ShieldBlockPlan plan = previewShieldBlock(defender, event, incomingDamage);
        plan.commit().run();
        return plan.result();
    }

    public ShieldBlockPlan previewShieldBlock(Player defender, EntityDamageEvent event, double incomingDamage) {
        return previewShieldBlock(defender, event == null ? null : findAttacker(event), incomingDamage);
    }

    public ShieldBlockPlan previewShieldBlock(Player defender, Entity attacker, double incomingDamage) {
        if (defender == null || incomingDamage <= 0.0 || !defender.isBlocking()) {
            return ShieldBlockPlan.none(incomingDamage);
        }

        ItemStack shield = defender.getInventory().getItemInOffHand();
        if (!isShield(shield) || defender.hasCooldown(shield.getType())) {
            return ShieldBlockPlan.none(incomingDamage);
        }

        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return ShieldBlockPlan.none(incomingDamage);
        }

        double threshold = readStatOrDefault(pdc, shield, pdc.KEY_SHIELD_BLOCK_THRESHOLD, DEFAULT_BLOCK_THRESHOLD);
        double effectiveBlock = clamp(readStatOrDefault(pdc, shield, pdc.KEY_SHIELD_EFFECTIVE_BLOCK, DEFAULT_EFFECTIVE_BLOCK), 0.0, 1.0);
        double cooldownSeconds = Math.max(0.0, readStatOrDefault(pdc, shield, pdc.KEY_SHIELD_COOLDOWN_SECONDS, DEFAULT_COOLDOWN_SECONDS));
        EnchantStatResolver resolver = EnchantStatResolver.getInstance();
        EnchantStatBundle enchantStats = resolver == null ? EnchantStatBundle.empty() : resolver.resolveCombatStats(shield, defender, org.bukkit.inventory.EquipmentSlot.OFF_HAND);
        threshold += enchantStats.shieldBlockThreshold();
        effectiveBlock = clamp(effectiveBlock + enchantStats.shieldEffectiveBlock(), 0.0, 1.0);
        cooldownSeconds = Math.max(0.0, cooldownSeconds + enchantStats.shieldCooldownSeconds());

        EnchantEffectService enchantEffects = EnchantEffectService.getInstance();
        EnchantEffectService.PerfectGuardPlan perfectGuardPlan = null;
        if (enchantEffects != null) {
            long now = System.currentTimeMillis();
            long raisedAt = shieldRaisedAt.getOrDefault(defender.getUniqueId(), now);
            perfectGuardPlan = enchantEffects.previewPerfectGuard(
                    defender, shield, threshold, cooldownSeconds, raisedAt, now);
            EnchantEffectService.ShieldAdjustment adjustment = perfectGuardPlan.adjustment();
            threshold = adjustment.threshold();
            cooldownSeconds = adjustment.cooldownSeconds();
        }

        if (threshold <= 0.0) {
            return ShieldBlockPlan.none(incomingDamage);
        }

        Runnable perfectGuardCommit = perfectGuardPlan == null ? () -> {
        } : perfectGuardPlan.commit();
        if (incomingDamage <= threshold) {
            ShieldBlockResult result = new ShieldBlockResult(
                    ShieldBlockType.FULL_BLOCK, incomingDamage, 0.0, 0.0, false);
            Runnable commit = () -> {
                perfectGuardCommit.run();
                defender.getWorld().playSound(defender.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.85f, 1.15f);
            };
            return new ShieldBlockPlan(result, commit);
        }

        boolean shieldBreak = incomingDamage > threshold * SHIELD_BREAK_MULTIPLIER;
        ShieldBlockType type = shieldBreak ? ShieldBlockType.SHIELD_BREAK : ShieldBlockType.GUARD_BREAK;
        double blockedDamage = threshold * effectiveBlock;
        double remainingDamage = Math.max(0.0, incomingDamage - blockedDamage);
        double appliedCooldownSeconds = cooldownSeconds * (shieldBreak ? SHIELD_BREAK_MULTIPLIER : 1.0);
        ShieldBlockResult result = new ShieldBlockResult(
                type, blockedDamage, remainingDamage, appliedCooldownSeconds, true);
        Runnable commit = () -> {
            perfectGuardCommit.run();
            int cooldownTicks = (int) Math.round(appliedCooldownSeconds * 20.0);
            if (cooldownTicks > 0) {
                defender.setCooldown(shield.getType(), cooldownTicks);
            }
            knockBackBoth(defender, attacker);
            defender.getWorld().playSound(defender.getLocation(),
                    shieldBreak ? Sound.ITEM_SHIELD_BREAK : Sound.ITEM_SHIELD_BLOCK,
                    1.0f, shieldBreak ? 0.75f : 0.9f);
            defender.sendActionBar(Component.text(shieldBreak ? "盾牌崩裂!" : "格挡被突破!"));
        };
        return new ShieldBlockPlan(result, commit);
    }

    public double estimateShieldValuePerSecond(Player player, double maxHealth) {
        if (player == null) {
            return 0.0;
        }

        ItemStack shield = player.getInventory().getItemInOffHand();
        if (!isShield(shield)) {
            return 0.0;
        }

        WeaponTemplateManager templateManager = WeaponTemplateManager.getInstance();
        if (templateManager != null && !templateManager.validateHands(player).canUseShield()) {
            return 0.0;
        }

        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return 0.0;
        }

        double threshold = readStatOrDefault(pdc, shield, pdc.KEY_SHIELD_BLOCK_THRESHOLD, DEFAULT_BLOCK_THRESHOLD);
        double effectiveBlock = clamp(readStatOrDefault(pdc, shield, pdc.KEY_SHIELD_EFFECTIVE_BLOCK, DEFAULT_EFFECTIVE_BLOCK), 0.0, 1.0);
        double cooldownSeconds = Math.max(1.0, readStatOrDefault(pdc, shield, pdc.KEY_SHIELD_COOLDOWN_SECONDS, DEFAULT_COOLDOWN_SECONDS));
        return Math.min(threshold * effectiveBlock / cooldownSeconds, Math.max(1.0, maxHealth) * 0.10);
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

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick()) {
            return;
        }
        ItemStack item = event.getItem();
        if (isShield(item) || isShield(event.getPlayer().getInventory().getItemInOffHand())) {
            shieldRaisedAt.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        shieldRaisedAt.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onHeldSlot(PlayerItemHeldEvent event) {
        shieldRaisedAt.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        shieldRaisedAt.remove(event.getPlayer().getUniqueId());
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

    public enum ShieldBlockType {
        NONE,
        FULL_BLOCK,
        GUARD_BREAK,
        SHIELD_BREAK
    }

    public record ShieldBlockResult(
            ShieldBlockType type,
            double blockedDamage,
            double remainingDamage,
            double cooldownSeconds,
            boolean knockbackBothSides
    ) {
        static ShieldBlockResult none(double incomingDamage) {
            return new ShieldBlockResult(ShieldBlockType.NONE, 0.0, Math.max(0.0, incomingDamage), 0.0, false);
        }
    }

    public record ShieldBlockPlan(ShieldBlockResult result, Runnable commit) {
        static ShieldBlockPlan none(double incomingDamage) {
            return new ShieldBlockPlan(ShieldBlockResult.none(incomingDamage), () -> {
            });
        }
    }
}
