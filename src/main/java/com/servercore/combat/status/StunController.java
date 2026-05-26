package com.servercore.combat.status;

import com.servercore.ServerCorePlugin;
import com.servercore.combat.resistance.ControlRule;
import com.servercore.combat.resistance.ResistanceResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class StunController implements Listener {

    private static StunController instance;

    private final ResistanceResolver resistanceResolver;
    private final Map<UUID, StunState> stunned = new HashMap<>();
    private final Map<UUID, Long> stunCooldownUntil = new HashMap<>();
    private final BukkitTask tickTask;
    private long currentTick;

    public StunController(ServerCorePlugin plugin, ResistanceResolver resistanceResolver) {
        instance = this;
        this.resistanceResolver = resistanceResolver;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        this.tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickStuns, 1L, 1L);
    }

    public static StunController getInstance() {
        return instance;
    }

    public void stop() {
        tickTask.cancel();
        for (UUID uuid : stunned.keySet()) {
            restoreAi(uuid);
        }
        stunned.clear();
        stunCooldownUntil.clear();
    }

    public boolean stun(LivingEntity target, int durationTicks) {
        if (target == null || target.isDead() || !target.isValid() || durationTicks <= 0) {
            return false;
        }

        ControlRule rule = resistanceResolver.resolveControlRule(target, "STUNNED");
        int adjustedDuration = (int) Math.ceil(durationTicks * rule.durationMultiplier());
        if (rule.maxDurationTicks() > 0) {
            adjustedDuration = Math.min(adjustedDuration, rule.maxDurationTicks());
        }
        if (adjustedDuration <= 0) {
            return false;
        }

        long cooldownUntil = stunCooldownUntil.getOrDefault(target.getUniqueId(), 0L);
        if (cooldownUntil > currentTick) {
            return false;
        }

        long expiresAt = currentTick + adjustedDuration;
        StunState existing = stunned.get(target.getUniqueId());
        if (existing != null && existing.expiresAt() >= expiresAt) {
            return false;
        }

        boolean previousAi = true;
        if (target instanceof Mob mob) {
            previousAi = mob.hasAI();
            mob.setAI(false);
            mob.setVelocity(new Vector(0, 0, 0));
        }

        stunned.put(target.getUniqueId(), new StunState(expiresAt, previousAi));
        if (rule.internalCooldownTicks() > 0) {
            stunCooldownUntil.put(target.getUniqueId(), currentTick + rule.internalCooldownTicks());
        }
        return true;
    }

    public boolean isStunned(LivingEntity entity) {
        StunState state = entity == null ? null : stunned.get(entity.getUniqueId());
        return state != null && state.expiresAt() > currentTick;
    }

    private void tickStuns() {
        currentTick++;
        Iterator<Map.Entry<UUID, StunState>> iterator = stunned.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, StunState> entry = iterator.next();
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (!(entity instanceof LivingEntity livingEntity) || livingEntity.isDead() || !livingEntity.isValid()) {
                iterator.remove();
                continue;
            }
            if (entry.getValue().expiresAt() <= currentTick) {
                restoreAi(entry.getKey());
                iterator.remove();
                continue;
            }

            livingEntity.setVelocity(new Vector(0, 0, 0));
            if (livingEntity instanceof Mob mob && mob.hasAI()) {
                mob.setAI(false);
            }
        }
    }

    private void restoreAi(UUID uuid) {
        Entity entity = Bukkit.getEntity(uuid);
        StunState state = stunned.get(uuid);
        if (entity instanceof Mob mob && state != null && state.previousAi()) {
            mob.setAI(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!isStunned(event.getPlayer()) || event.getTo() == null) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return;
        }
        Location locked = from.clone();
        locked.setYaw(to.getYaw());
        locked.setPitch(to.getPitch());
        event.setTo(locked);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (isStunned(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (isStunned(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isStunned(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isStunned(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && isStunned(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof LivingEntity livingEntity && isStunned(livingEntity)) {
            event.setCancelled(true);
            return;
        }
        if (event.getDamager() instanceof Projectile projectile && isProjectileSourceStunned(projectile.getShooter())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (isProjectileSourceStunned(event.getEntity().getShooter())) {
            event.setCancelled(true);
        }
    }

    private boolean isProjectileSourceStunned(ProjectileSource source) {
        return source instanceof LivingEntity livingEntity && isStunned(livingEntity);
    }

    private record StunState(long expiresAt, boolean previousAi) {
    }
}
