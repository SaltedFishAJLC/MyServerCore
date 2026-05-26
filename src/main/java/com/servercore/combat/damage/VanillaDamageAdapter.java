package com.servercore.combat.damage;

import com.servercore.ServerCorePlugin;
import com.servercore.combat.status.FrostService;
import com.servercore.combat.status.StatusService;
import com.servercore.combat.status.StatusType;
import com.servercore.combat.status.StunController;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.EnumSet;
import java.util.Set;

public final class VanillaDamageAdapter implements Listener {

    private final DamageService damageService;
    private final StatusService statusService;
    private final FrostService frostService;
    private final StunController stunController;

    public VanillaDamageAdapter(ServerCorePlugin plugin, DamageService damageService, StatusService statusService,
                                FrostService frostService, StunController stunController) {
        this.damageService = damageService;
        this.statusService = statusService;
        this.frostService = frostService;
        this.stunController = stunController;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVanillaDamage(EntityDamageEvent event) {
        if (DamageService.isInternalDamageActive() || !(event.getEntity() instanceof LivingEntity target)) {
            return;
        }

        String cause = event.getCause().name();
        LivingEntity source = resolveLivingSource(event);
        double damage = Math.max(0.0, event.getDamage());

        switch (cause) {
            case "FIRE_TICK" -> {
                event.setCancelled(true);
                event.setDamage(0.0);
                statusService.applyStatus(source, target, StatusType.BURNING, Math.max(1.0, damage * 2.0), 40);
            }
            case "POISON" -> {
                event.setCancelled(true);
                event.setDamage(0.0);
                statusService.applyStatus(source, target, StatusType.POISONED, Math.max(0.5, damage), 20);
            }
            case "WITHER" -> {
                event.setCancelled(true);
                event.setDamage(0.0);
                statusService.applyStatus(source, target, StatusType.WITHERED, Math.max(0.5, damage), 20);
            }
            case "FREEZE" -> {
                event.setCancelled(true);
                event.setDamage(0.0);
                frostService.addFrost(target, FrostService.MEDIUM_FROST);
                damageService.applyDamage(packet(source, target, damage, DamageCategory.MAGIC,
                        EnumSet.of(DamageTag.FROST, DamageTag.DOT, DamageTag.STATUS),
                        DamageSourceKind.VANILLA_STATUS, "vanilla_freeze"));
            }
            case "FIRE", "LAVA", "HOT_FLOOR", "CAMPFIRE" -> {
                event.setCancelled(true);
                event.setDamage(0.0);
                damageService.applyDamage(packet(source, target, damage, DamageCategory.MAGIC,
                        EnumSet.of(DamageTag.FIRE),
                        DamageSourceKind.VANILLA_ENVIRONMENT, "vanilla_" + cause.toLowerCase(java.util.Locale.ROOT)));
            }
            case "LIGHTNING" -> {
                event.setCancelled(true);
                event.setDamage(0.0);
                damageService.applyDamage(packet(source, target, damage, DamageCategory.MAGIC,
                        EnumSet.of(DamageTag.SHOCK, DamageTag.CONTROL),
                        DamageSourceKind.VANILLA_LIGHTNING, "vanilla_lightning"));
                stunController.stun(target, 40);
            }
            case "ENTITY_EXPLOSION", "BLOCK_EXPLOSION" -> adaptExplosion(event, target, source, damage, cause);
            default -> {
            }
        }
    }

    private void adaptExplosion(EntityDamageEvent event, LivingEntity target, LivingEntity source, double damage, String cause) {
        boolean chargedCreeper = event instanceof EntityDamageByEntityEvent byEntityEvent
                && byEntityEvent.getDamager() instanceof Creeper creeper
                && creeper.isPowered();

        event.setCancelled(true);
        event.setDamage(0.0);

        if (chargedCreeper) {
            damageService.applyDamage(packet(source, target, damage, DamageCategory.MAGIC,
                    EnumSet.of(DamageTag.SHOCK, DamageTag.EXPLOSION, DamageTag.AOE, DamageTag.CONTROL),
                    DamageSourceKind.VANILLA_EXPLOSION, "charged_creeper_explosion"));
            stunController.stun(target, 40);
            return;
        }

        damageService.applyDamage(packet(source, target, damage, DamageCategory.PHYSICAL,
                EnumSet.of(DamageTag.EXPLOSION, DamageTag.AOE),
                DamageSourceKind.VANILLA_EXPLOSION, "vanilla_" + cause.toLowerCase(java.util.Locale.ROOT)));
    }

    private DamagePacket packet(LivingEntity source, LivingEntity target, double damage, DamageCategory category,
                                Set<DamageTag> tags, DamageSourceKind kind, String reason) {
        return new DamagePacket(source, target, damage, category, tags, kind, reason);
    }

    private LivingEntity resolveLivingSource(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent byEntityEvent) {
            LivingEntity direct = resolveEntity(byEntityEvent.getDamager());
            if (direct != null) {
                return direct;
            }
        }

        DamageSource damageSource = event.getDamageSource();
        LivingEntity causing = resolveEntity(damageSource.getCausingEntity());
        if (causing != null) {
            return causing;
        }
        return resolveEntity(damageSource.getDirectEntity());
    }

    private LivingEntity resolveEntity(Entity entity) {
        if (entity instanceof LivingEntity livingEntity) {
            return livingEntity;
        }
        if (entity instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            return shooter instanceof LivingEntity livingEntity ? livingEntity : null;
        }
        return null;
    }
}
