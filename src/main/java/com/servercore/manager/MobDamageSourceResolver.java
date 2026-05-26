package com.servercore.manager;

import org.bukkit.damage.DamageSource;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves indirect vanilla damage carriers back to the living mob that should
 * provide ServerCore attack damage.
 */
public final class MobDamageSourceResolver {

    private MobDamageSourceResolver() {
    }

    public static LivingEntity resolveMobAttacker(EntityDamageEvent event) {
        LivingEntity attacker = resolveAnyAttacker(event);
        return attacker instanceof Player ? null : attacker;
    }

    public static boolean isPlayerCaused(EntityDamageEvent event) {
        return resolveAnyAttacker(event) instanceof Player;
    }

    private static LivingEntity resolveAnyAttacker(EntityDamageEvent event) {
        Set<UUID> visited = new HashSet<>();

        if (event instanceof EntityDamageByEntityEvent byEntityEvent) {
            LivingEntity attacker = resolveEntity(byEntityEvent.getDamager(), visited);
            if (attacker != null) {
                return attacker;
            }
        }

        DamageSource damageSource = event.getDamageSource();
        Entity causingEntity = damageSource.getCausingEntity();
        LivingEntity attacker = resolveEntity(causingEntity, visited);
        if (attacker != null) {
            return attacker;
        }

        Entity directEntity = damageSource.getDirectEntity();
        return resolveEntity(directEntity, visited);
    }

    private static LivingEntity resolveEntity(Entity entity, Set<UUID> visited) {
        if (entity == null) {
            return null;
        }

        if (!visited.add(entity.getUniqueId())) {
            return null;
        }

        if (entity instanceof LivingEntity livingEntity) {
            return livingEntity;
        }

        if (entity instanceof Projectile projectile) {
            return resolveProjectileSource(projectile.getShooter(), visited);
        }

        if (entity instanceof EvokerFangs fangs) {
            return resolveEntity(fangs.getOwner(), visited);
        }

        if (entity instanceof AreaEffectCloud cloud) {
            return resolveProjectileSource(cloud.getSource(), visited);
        }

        if (entity instanceof TNTPrimed tnt) {
            return resolveEntity(tnt.getSource(), visited);
        }

        return null;
    }

    private static LivingEntity resolveProjectileSource(ProjectileSource source, Set<UUID> visited) {
        if (source instanceof LivingEntity livingEntity) {
            return livingEntity;
        }

        if (source instanceof Entity entity) {
            return resolveEntity(entity, visited);
        }

        return null;
    }
}
