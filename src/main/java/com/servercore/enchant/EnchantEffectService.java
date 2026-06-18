package com.servercore.enchant;

import com.servercore.ServerCorePlugin;
import com.servercore.combat.creature.CreatureTagService;
import com.servercore.combat.creature.CreatureMainTag;
import com.servercore.combat.creature.CreatureTraitTag;
import com.servercore.combat.damage.DamageCategory;
import com.servercore.combat.damage.DamagePacket;
import com.servercore.combat.damage.DamageService;
import com.servercore.combat.damage.DamageSourceKind;
import com.servercore.combat.damage.DamageTag;
import com.servercore.combat.status.StunController;
import com.servercore.manager.ClassManager;
import com.servercore.manager.EnchantManager;
import com.servercore.manager.PDCManager;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class EnchantEffectService implements Listener {

    private static EnchantEffectService instance;

    private final ServerCorePlugin plugin;
    private final Map<UUID, Long> perfectGuardCooldownUntil = new HashMap<>();
    private final Map<UUID, ApexState> apexStates = new HashMap<>();
    private final Map<UUID, Long> phoenixCooldownUntil = new HashMap<>();
    private final Map<String, Long> firstStrikeLastHit = new HashMap<>();
    private final Map<UUID, TripleStrikeState> tripleStrikeStates = new HashMap<>();
    private final Map<UUID, Long> thunderCooldownUntil = new HashMap<>();
    private final Map<UUID, Long> manaStealCooldownUntil = new HashMap<>();
    private final Map<UUID, Long> drainCooldownUntil = new HashMap<>();
    private final Map<UUID, Long> executeCooldownUntil = new HashMap<>();
    private final Map<UUID, ComboState> comboStates = new HashMap<>();
    private final Map<UUID, SoulEaterState> soulEaterStates = new HashMap<>();

    public EnchantEffectService(ServerCorePlugin plugin) {
        this.plugin = plugin;
        instance = this;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public static EnchantEffectService getInstance() {
        return instance;
    }

    public double applyOutgoingDamageModifiers(Player player, LivingEntity target, double damage, boolean isRanged, boolean isMagic) {
        return applyOutgoingDamageModifiers(player, target, damage, isRanged, isMagic, false);
    }

    public double applyOutgoingDamageModifiers(Player player, LivingEntity target, double damage, boolean isRanged, boolean isMagic, boolean isCrit) {
        if (player == null || target == null || damage <= 0.0 || EnchantDamageContext.isSecondaryDamage()) {
            return damage;
        }
        double result = damage;
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ActiveEffect apex = activeEffect(mainHand, EnchantEffectType.APEX_SLAYER);
        if (apex != null && isBossOrSlayerTarget(target)) {
            ApexState state = nextApexState(player.getUniqueId(), target.getUniqueId(), apex);
            result *= 1.0 + state.stacks() * apex.effect().param("damage_bonus_per_stack", apex.level(), 0.02);
        }

        ActiveEffect berserker = activeEffect(mainHand, EnchantEffectType.BERSERKER_OATH);
        if (berserker != null && !isRanged && !isMagic && berserker.effect().booleanParam("require_no_shield", true)) {
            ItemStack offHand = player.getInventory().getItemInOffHand();
            if (offHand == null || offHand.getType().isAir() || offHand.getType() != org.bukkit.Material.SHIELD) {
                result *= 1.0 + berserker.effect().param("damage_bonus", berserker.level(), 0.25);
            }
        }
        result = applyIdBasedDamageModifiers(player, target, mainHand, result, isRanged, isMagic, isCrit);
        return result;
    }

    public double applyIncomingDamageModifiers(Player player, double damage) {
        if (player == null || damage <= 0.0 || EnchantDamageContext.isSecondaryDamage()) {
            return damage;
        }
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ActiveEffect berserker = activeEffect(mainHand, EnchantEffectType.BERSERKER_OATH);
        if (berserker == null) {
            return damage;
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        boolean noShield = offHand == null || offHand.getType().isAir() || offHand.getType() != org.bukkit.Material.SHIELD;
        if (!noShield && berserker.effect().booleanParam("require_no_shield", true)) {
            return damage;
        }
        return damage * (1.0 + berserker.effect().param("incoming_damage_penalty", berserker.level(), 0.12));
    }

    public boolean tryPreventFatalDamage(Player player, EntityDamageEvent event, double finalDamage) {
        if (player == null || event == null || finalDamage <= 0.0 || EnchantDamageContext.isSecondaryDamage()) {
            return false;
        }
        if (player.getHealth() - finalDamage > 0.0) {
            return false;
        }

        ItemStack chestplate = player.getInventory().getChestplate();
        ActiveEffect phoenix = activeEffect(chestplate, EnchantEffectType.PHOENIX_CORE);
        if (phoenix == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        long until = phoenixCooldownUntil.getOrDefault(player.getUniqueId(), 0L);
        if (until > now) {
            return false;
        }

        double cooldownSeconds = phoenix.effect().param("cooldown_seconds", phoenix.level(), 180.0);
        double remainHealth = phoenix.effect().param("remain_health", phoenix.level(), 1.0);
        phoenixCooldownUntil.put(player.getUniqueId(), now + Math.round(cooldownSeconds * 1000.0));
        event.setCancelled(true);
        player.setHealth(Math.max(1.0, Math.min(maxHealth(player), remainHealth)));
        if (phoenix.effect().booleanParam("clear_negative_effects", true)) {
            clearNegativeEffects(player);
        }
        player.getWorld().spawnParticle(Particle.FLAME, player.getLocation().add(0.0, 1.0, 0.0), 40, 0.6, 0.8, 0.6, 0.03);
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 0.9f, 0.9f);
        player.sendActionBar(net.kyori.adventure.text.Component.text("不灭余烬阻止了致死伤害。"));
        return true;
    }

    public void afterPlayerAttack(EntityDamageEvent event, Player player, LivingEntity target, double finalDamage,
                                  boolean isRanged, boolean isMagic) {
        afterPlayerAttack(event, player, target, finalDamage, isRanged, isMagic, false);
    }

    public void afterPlayerAttack(EntityDamageEvent event, Player player, LivingEntity target, double finalDamage,
                                  boolean isRanged, boolean isMagic, boolean isCrit) {
        if (event == null || player == null || target == null || finalDamage <= 0.0 || EnchantDamageContext.isSecondaryDamage()) {
            return;
        }
        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (!isRanged && !isMagic) {
            triggerCleave(player, target, weapon, finalDamage);
        }
        triggerIdBasedAfterHit(event, player, target, weapon, finalDamage, isRanged, isMagic, isCrit);
    }

    public ShieldAdjustment applyPerfectGuard(Player defender, ItemStack shield, double threshold, double cooldownSeconds,
                                             long shieldRaisedAtMs, long nowMs) {
        if (defender == null || shield == null || shield.getType().isAir()) {
            return new ShieldAdjustment(threshold, cooldownSeconds, false);
        }
        ActiveEffect effect = activeEffect(shield, EnchantEffectType.PERFECT_GUARD);
        if (effect == null) {
            return new ShieldAdjustment(threshold, cooldownSeconds, false);
        }
        long until = perfectGuardCooldownUntil.getOrDefault(defender.getUniqueId(), 0L);
        if (until > nowMs || shieldRaisedAtMs <= 0L) {
            return new ShieldAdjustment(threshold, cooldownSeconds, false);
        }
        double windowMs = effect.effect().param("timing_window_seconds", effect.level(), 0.35) * 1000.0;
        if (nowMs - shieldRaisedAtMs > windowMs) {
            return new ShieldAdjustment(threshold, cooldownSeconds, false);
        }
        double bonus = effect.effect().param("threshold_bonus", effect.level(), 0.0);
        double effectCooldown = effect.effect().param("cooldown_seconds", effect.level(), cooldownSeconds);
        perfectGuardCooldownUntil.put(defender.getUniqueId(), nowMs + Math.round(effectCooldown * 1000.0));
        defender.getWorld().spawnParticle(Particle.END_ROD, defender.getLocation().add(0.0, 1.0, 0.0), 16, 0.35, 0.45, 0.35, 0.02);
        defender.getWorld().playSound(defender.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.95f, 1.45f);
        return new ShieldAdjustment(threshold + bonus, Math.max(cooldownSeconds, effectCooldown), true);
    }

    private double applyIdBasedDamageModifiers(Player player, LivingEntity target, ItemStack weapon, double damage,
                                               boolean isRanged, boolean isMagic, boolean isCrit) {
        double result = damage;

        int power = activeLevel(weapon, "power");
        if (power > 0 && isRanged) {
            result *= 1.0 + power * 0.08;
        }

        int anatomy = activeLevel(weapon, "anatomy");
        if (anatomy > 0) {
            double[] values = {0.001, 0.002, 0.004, 0.005, 0.007};
            double currentPercent = getEffectiveMaxHealth(target) <= 0.0
                    ? 0.0
                    : clamp(getEffectiveHealth(target) / getEffectiveMaxHealth(target), 0.0, 1.0) * 100.0;
            result *= 1.0 + currentPercent * valueAt(values, anatomy);
        }

        int firstStrike = activeLevel(weapon, "first_strike");
        if (firstStrike > 0) {
            String key = player.getUniqueId() + ":" + target.getUniqueId();
            long now = System.currentTimeMillis();
            long last = firstStrikeLastHit.getOrDefault(key, 0L);
            if (last <= 0L || now - last >= 20_000L) {
                result *= 1.0 + firstStrike * 0.15;
            }
            firstStrikeLastHit.put(key, now);
        }

        int tripleStrike = activeLevel(weapon, "triple_strike");
        if (tripleStrike > 0 && !isRanged && !isMagic) {
            TripleStrikeState previous = tripleStrikeStates.get(player.getUniqueId());
            int nextCount = previous != null && previous.targetId().equals(target.getUniqueId())
                    ? previous.count() + 1
                    : 1;
            tripleStrikeStates.put(player.getUniqueId(), new TripleStrikeState(target.getUniqueId(), nextCount));
            if (nextCount % 3 == 0) {
                result *= 1.0 + tripleStrike * 0.08;
            }
        }

        int thunderbolt = activeLevel(weapon, "thunderbolt");
        if (thunderbolt > 0 && isCrit && !isRanged && !isMagic) {
            long now = System.currentTimeMillis();
            long until = thunderCooldownUntil.getOrDefault(player.getUniqueId(), 0L);
            if (until <= now) {
                double[] values = {0.10, 0.20, 0.30, 0.40};
                result *= 1.0 + valueAt(values, thunderbolt);
                thunderCooldownUntil.put(player.getUniqueId(), now + 4000L);
                StunController stunController = StunController.getInstance();
                if (stunController != null) {
                    stunController.stun(target, 10);
                }
                target.getWorld().strikeLightningEffect(target.getLocation());
            }
        }

        int armorPiercer = activeLevel(weapon, "armor_piercer");
        if (armorPiercer > 0 && isRanged) {
            double[] values = {0.04, 0.08, 0.12, 0.16, 0.20};
            double value = valueAt(values, armorPiercer);
            if (hasMainTag(target, CreatureMainTag.CONSTRUCT) || hasTrait(target, CreatureTraitTag.HEAVY)) {
                value *= 1.5;
            }
            result *= 1.0 + value;
        }

        int fullDraw = activeLevel(weapon, "full_draw");
        if (fullDraw > 0 && isRanged) {
            double[] values = {0.04, 0.07, 0.10, 0.13, 0.16};
            result *= 1.0 + valueAt(values, fullDraw);
        }

        int cloudpiercer = activeLevel(weapon, "cloudpiercer");
        if (cloudpiercer > 0 && isRanged && hasTrait(target, CreatureTraitTag.FLYING)) {
            double[] values = {0.08, 0.12, 0.16, 0.20, 0.25};
            result *= 1.0 + valueAt(values, cloudpiercer);
        }

        int swarm = activeLevel(weapon, "swarm");
        if (swarm > 0) {
            double[] values = {0.03, 0.04, 0.05, 0.06, 0.07};
            long enemies = player.getNearbyEntities(10.0, 10.0, 10.0).stream()
                    .filter(entity -> entity instanceof Enemy)
                    .filter(entity -> entity instanceof LivingEntity living && !living.isDead() && living.isValid())
                    .limit(10)
                    .count();
            result *= 1.0 + enemies * valueAt(values, swarm);
        }

        int combo = activeLevel(weapon, "combo");
        ComboState comboState = comboStates.get(player.getUniqueId());
        if (combo > 0 && comboState != null && comboState.expiresAtMs() >= System.currentTimeMillis()) {
            double[] values = {0.02, 0.03, 0.05, 0.06, 0.08};
            result *= 1.0 + comboState.stacks() * valueAt(values, combo);
        }

        return result;
    }

    private void triggerIdBasedAfterHit(EntityDamageEvent event, Player player, LivingEntity target, ItemStack weapon,
                                        double finalDamage, boolean isRanged, boolean isMagic, boolean isCrit) {
        int fireAspect = activeLevel(weapon, "fire_aspect");
        if (fireAspect > 0 && !isMagic) {
            int seconds = fireAspect * 2;
            target.setFireTicks(Math.max(target.getFireTicks(), seconds * 20));
            double total = finalDamage * fireAspect * 0.03;
            if (total > 0.0) {
                applySecondaryDamage(player, target, total, DamageCategory.MAGIC,
                        Set.of(DamageTag.FIRE, DamageTag.STATUS), "enchant_fire_aspect");
            }
        }

        int chainLightning = activeLevel(weapon, "chain_lightning");
        if (chainLightning > 0 && isCrit && !isRanged && !isMagic) {
            double[] values = {0.04, 0.08, 0.12};
            triggerChainLightning(player, target, finalDamage * valueAt(values, chainLightning));
        }

        int manaSteal = activeLevel(weapon, "mana_steal");
        if (manaSteal > 0 && !isRanged) {
            tryManaSteal(player, finalDamage * manaSteal * 0.004);
        }

        int knockback = activeLevel(weapon, "knockback");
        int punch = activeLevel(weapon, "punch");
        int knockLevel = isRanged ? punch : knockback;
        if (knockLevel > 0) {
            applyKnockback(player, target, knockLevel);
        }

        int explosiveBolt = activeLevel(weapon, "explosive_bolt");
        if (explosiveBolt > 0 && isRanged) {
            double[] values = {0.12, 0.16, 0.20, 0.24, 0.30};
            triggerExplosiveBolt(player, target, finalDamage * valueAt(values, explosiveBolt));
        }

        int execute = activeLevel(weapon, "execute");
        if (execute > 0 && !isRanged && !isMagic) {
            tryExecute(player, target, execute);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) {
            return;
        }
        ItemStack weapon = killer.getInventory().getItemInMainHand();
        int level = mobLevel(event.getEntity());

        int drain = activeLevel(weapon, "drain");
        if (drain > 0) {
            tryDrain(killer, drain);
        }

        int combo = activeLevel(weapon, "combo");
        if (combo > 0) {
            double[] windows = {4.0, 4.0, 6.0, 8.0, 8.0};
            long expire = System.currentTimeMillis() + Math.round(valueAt(windows, combo) * 1000.0);
            ComboState previous = comboStates.get(killer.getUniqueId());
            int stacks = previous == null || previous.expiresAtMs() < System.currentTimeMillis()
                    ? 1
                    : Math.min(6, previous.stacks() + 1);
            comboStates.put(killer.getUniqueId(), new ComboState(stacks, expire));
        }

        int soulEater = activeLevel(weapon, "soul_eater");
        if (soulEater > 0 && level > 0) {
            SoulEaterState previous = soulEaterStates.get(killer.getUniqueId());
            int best = previous == null || previous.expiresAtMs() < System.currentTimeMillis()
                    ? level
                    : Math.max(previous.bestLevel(), level);
            soulEaterStates.put(killer.getUniqueId(), new SoulEaterState(best, System.currentTimeMillis() + 30_000L));
        }
    }

    public double resolveScavengerBountyMultiplier(Player player) {
        if (player == null) {
            return 1.0;
        }
        int level = activeLevel(player.getInventory().getItemInMainHand(), "scavenger");
        return level <= 0 ? 1.0 : 1.0 + level * 0.3;
    }

    public double resolveSoulEaterBaseDamageBonus(Player player, ItemStack weapon) {
        if (player == null || activeLevel(weapon, "soul_eater") <= 0) {
            return 0.0;
        }
        SoulEaterState state = soulEaterStates.get(player.getUniqueId());
        if (state == null || state.expiresAtMs() < System.currentTimeMillis()) {
            return 0.0;
        }
        int level = activeLevel(weapon, "soul_eater");
        double[] values = {0.10, 0.15, 0.20, 0.25, 0.30};
        return Math.min(500.0, state.bestLevel() * valueAt(values, level));
    }

    private void triggerCleave(Player player, LivingEntity primaryTarget, ItemStack weapon, double finalDamage) {
        ActiveEffect effect = activeEffect(weapon, EnchantEffectType.CLEAVE);
        if (effect == null || finalDamage <= 0.0) {
            return;
        }
        double radius = Math.max(0.0, effect.effect().param("radius", effect.level(), 3.0));
        int maxTargets = Math.max(0, (int) Math.round(effect.effect().param("max_targets", effect.level(), 2.0)));
        double ratio = Math.max(0.0, effect.effect().param("damage_ratio", effect.level(), 0.10));
        if (radius <= 0.0 || maxTargets <= 0 || ratio <= 0.0) {
            return;
        }

        double damage = finalDamage * ratio;
        DamageService damageService = DamageService.getInstance();
        primaryTarget.getNearbyEntities(radius, radius, radius).stream()
                .filter(entity -> entity instanceof LivingEntity)
                .map(entity -> (LivingEntity) entity)
                .filter(entity -> !entity.equals(primaryTarget))
                .filter(entity -> !entity.equals(player))
                .filter(entity -> !(entity instanceof Player))
                .filter(entity -> !entity.isDead() && entity.isValid())
                .sorted(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(primaryTarget.getLocation())))
                .limit(maxTargets)
                .forEach(entity -> EnchantDamageContext.runAsSecondaryDamage(() -> {
                    if (damageService != null) {
                        damageService.applyDamage(new DamagePacket(
                                player,
                                entity,
                                damage,
                                DamageCategory.PHYSICAL,
                                Set.of(DamageTag.AOE, DamageTag.MELEE),
                                DamageSourceKind.CUSTOM_ITEM,
                                "enchant_cleave"
                        ));
                    } else {
                        entity.damage(damage, player);
                    }
                    entity.getWorld().spawnParticle(Particle.SWEEP_ATTACK, entity.getLocation().add(0.0, 1.0, 0.0), 1);
                }));
    }

    private void triggerChainLightning(Player player, LivingEntity primaryTarget, double damage) {
        if (damage <= 0.0) {
            return;
        }
        int maxTargets = 10;
        double radius = 2.5;
        Set<UUID> visited = new HashSet<>();
        Deque<LivingEntity> queue = new ArrayDeque<>();
        visited.add(primaryTarget.getUniqueId());
        queue.add(primaryTarget);
        int hits = 0;
        while (!queue.isEmpty() && hits < maxTargets) {
            LivingEntity source = queue.removeFirst();
            List<LivingEntity> nearby = source.getNearbyEntities(radius, radius, radius).stream()
                    .filter(entity -> entity instanceof LivingEntity)
                    .map(entity -> (LivingEntity) entity)
                    .filter(entity -> !entity.equals(player))
                    .filter(entity -> !(entity instanceof Player))
                    .filter(entity -> !entity.isDead() && entity.isValid())
                    .filter(entity -> !visited.contains(entity.getUniqueId()))
                    .sorted(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(source.getLocation())))
                    .toList();
            for (LivingEntity next : nearby) {
                if (hits >= maxTargets) {
                    break;
                }
                visited.add(next.getUniqueId());
                queue.addLast(next);
                hits++;
                applySecondaryDamage(player, next, damage, DamageCategory.MAGIC,
                        Set.of(DamageTag.SHOCK, DamageTag.AOE), "enchant_chain_lightning");
                next.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, next.getLocation().add(0.0, 1.0, 0.0), 14, 0.25, 0.35, 0.25, 0.04);
            }
        }
    }

    private void triggerExplosiveBolt(Player player, LivingEntity primaryTarget, double damage) {
        if (damage <= 0.0) {
            return;
        }
        primaryTarget.getNearbyEntities(2.5, 2.5, 2.5).stream()
                .filter(entity -> entity instanceof LivingEntity)
                .map(entity -> (LivingEntity) entity)
                .filter(entity -> !entity.equals(primaryTarget))
                .filter(entity -> !entity.equals(player))
                .filter(entity -> !(entity instanceof Player))
                .filter(entity -> !entity.isDead() && entity.isValid())
                .forEach(entity -> {
                    applySecondaryDamage(player, entity, damage, DamageCategory.PHYSICAL,
                            Set.of(DamageTag.EXPLOSION, DamageTag.AOE, DamageTag.PROJECTILE), "enchant_explosive_bolt");
                    entity.getWorld().spawnParticle(Particle.EXPLOSION, entity.getLocation().add(0.0, 0.7, 0.0), 1);
                });
    }

    private void tryManaSteal(Player player, double amount) {
        if (amount <= 0.0) {
            return;
        }
        long now = System.currentTimeMillis();
        long until = manaStealCooldownUntil.getOrDefault(player.getUniqueId(), 0L);
        if (until > now) {
            return;
        }
        manaStealCooldownUntil.put(player.getUniqueId(), now + 750L);
        ManaAccess.restoreMana(player, amount);
        player.getWorld().spawnParticle(Particle.ENCHANT, player.getLocation().add(0.0, 1.0, 0.0), 10, 0.25, 0.35, 0.25, 0.02);
    }

    private void tryDrain(Player player, int level) {
        long now = System.currentTimeMillis();
        long until = drainCooldownUntil.getOrDefault(player.getUniqueId(), 0L);
        if (until > now) {
            return;
        }

        AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double maxHealth = maxHealthAttribute == null ? 20.0 : maxHealthAttribute.getValue();
        double missing = Math.max(0.0, maxHealth - player.getHealth());
        if (missing <= 0.0) {
            return;
        }

        double heal = missing * level * 0.025;
        ClassManager classManager = ClassManager.getInstance();
        if (classManager != null) {
            heal *= classManager.getLifestealMultiplier(player);
        }
        if (heal <= 0.0) {
            return;
        }
        drainCooldownUntil.put(player.getUniqueId(), now + 750L);
        player.setHealth(Math.min(maxHealth, player.getHealth() + heal));
        player.getWorld().spawnParticle(Particle.HEART, player.getLocation().add(0.0, 1.0, 0.0), 3, 0.25, 0.25, 0.25, 0.01);
    }

    private void tryExecute(Player player, LivingEntity target, int level) {
        if (target.isDead() || hasTrait(target, CreatureTraitTag.BOSS)) {
            return;
        }
        double maxHealth = getEffectiveMaxHealth(target);
        if (maxHealth <= 0.0) {
            return;
        }
        double[] thresholds = {0.06, 0.08, 0.10, 0.12, 0.15};
        if (getEffectiveHealth(target) / maxHealth > valueAt(thresholds, level)) {
            return;
        }
        long now = System.currentTimeMillis();
        long until = executeCooldownUntil.getOrDefault(player.getUniqueId(), 0L);
        if (until > now) {
            return;
        }

        double[] costs = {0.18, 0.16, 0.14, 0.12, 0.10};
        double healthCost = Math.max(0.0, player.getHealth() * valueAt(costs, level));
        if (player.getHealth() - healthCost <= 1.0) {
            return;
        }
        executeCooldownUntil.put(player.getUniqueId(), now + 3000L);
        player.setHealth(Math.max(1.0, player.getHealth() - healthCost));
        target.setHealth(0.0);
        StunController stunController = StunController.getInstance();
        if (stunController != null) {
            target.getNearbyEntities(5.0, 5.0, 5.0).stream()
                    .filter(entity -> entity instanceof LivingEntity)
                    .map(entity -> (LivingEntity) entity)
                    .filter(entity -> !entity.equals(player))
                    .filter(entity -> entity instanceof Enemy)
                    .forEach(entity -> stunController.stun(entity, 20));
        }
    }

    private void applyKnockback(Player player, LivingEntity target, int level) {
        Vector direction = target.getLocation().toVector().subtract(player.getLocation().toVector());
        direction.setY(0.0);
        if (direction.lengthSquared() <= 0.0001) {
            return;
        }
        direction.normalize().multiply(0.25 + level * 0.18).setY(0.18);
        target.setVelocity(target.getVelocity().add(direction));
    }

    private void applySecondaryDamage(Player player, LivingEntity target, double damage, DamageCategory category,
                                      Set<DamageTag> tags, String reason) {
        if (damage <= 0.0 || target == null || target.isDead() || !target.isValid()) {
            return;
        }
        DamageService damageService = DamageService.getInstance();
        EnchantDamageContext.runAsSecondaryDamage(() -> {
            if (damageService != null) {
                damageService.applyDamage(new DamagePacket(
                        player,
                        target,
                        damage,
                        category,
                        tags,
                        DamageSourceKind.CUSTOM_ITEM,
                        reason
                ));
            } else {
                target.damage(damage, player);
            }
        });
    }

    private int activeLevel(ItemStack item, String id) {
        EnchantManager enchantManager = EnchantManager.getInstance();
        return enchantManager == null ? 0 : enchantManager.getActiveEnchantLevel(item, id);
    }

    private boolean hasTrait(LivingEntity target, CreatureTraitTag tag) {
        CreatureTagService tagService = CreatureTagService.getInstance();
        return tagService != null && tagService.hasTraitTag(target, tag);
    }

    private boolean hasMainTag(LivingEntity target, CreatureMainTag tag) {
        CreatureTagService tagService = CreatureTagService.getInstance();
        return tagService != null && tagService.hasMainTag(target, tag);
    }

    private int mobLevel(LivingEntity entity) {
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null || entity == null) {
            return 0;
        }
        Integer level = entity.getPersistentDataContainer().get(pdc.KEY_MOB_POWER_LEVEL, PersistentDataType.INTEGER);
        return level == null ? 0 : Math.max(0, level);
    }

    private double getEffectiveMaxHealth(LivingEntity target) {
        PDCManager pdc = PDCManager.getInstance();
        if (pdc != null && target != null) {
            Double virtualMax = target.getPersistentDataContainer().get(pdc.KEY_MOB_VIRTUAL_MAX_HEALTH, PersistentDataType.DOUBLE);
            if (virtualMax != null && virtualMax > 0.0) {
                return virtualMax;
            }
        }
        AttributeInstance attribute = target == null ? null : target.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        return attribute == null ? 20.0 : Math.max(1.0, attribute.getValue());
    }

    private double getEffectiveHealth(LivingEntity target) {
        PDCManager pdc = PDCManager.getInstance();
        if (pdc != null && target != null) {
            Double virtualHealth = target.getPersistentDataContainer().get(pdc.KEY_MOB_VIRTUAL_HEALTH, PersistentDataType.DOUBLE);
            if (virtualHealth != null && virtualHealth >= 0.0) {
                return virtualHealth;
            }
        }
        return target == null ? 0.0 : Math.max(0.0, target.getHealth());
    }

    private double valueAt(double[] values, int level) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        return values[Math.max(0, Math.min(level - 1, values.length - 1))];
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private ActiveEffect activeEffect(ItemStack item, EnchantEffectType type) {
        EnchantManager enchantManager = EnchantManager.getInstance();
        EnchantRegistry registry = EnchantRegistry.getInstance();
        if (enchantManager == null || registry == null || item == null || item.getType().isAir()) {
            return null;
        }
        for (Map.Entry<String, Integer> entry : enchantManager.getAllActiveCustomEnchants(item).entrySet()) {
            EnchantDefinition definition = registry.get(entry.getKey()).orElse(null);
            if (definition == null || !definition.enabled() || definition.rarity().ordinal() < EnchantRarity.RARE.ordinal()) {
                continue;
            }
            if (definition.effect().type() == type) {
                return new ActiveEffect(definition, entry.getValue());
            }
        }
        return null;
    }

    private ApexState nextApexState(UUID playerId, UUID targetId, ActiveEffect effect) {
        long now = System.currentTimeMillis();
        long expireMs = Math.round(effect.effect().param("expire_seconds", effect.level(), 5.0) * 1000.0);
        int maxStacks = Math.max(1, (int) Math.round(effect.effect().param("max_stacks", effect.level(), 10.0)));
        ApexState previous = apexStates.get(playerId);
        int nextStacks = 1;
        if (previous != null && previous.targetId().equals(targetId) && now - previous.lastHitAtMs() <= expireMs) {
            nextStacks = Math.min(maxStacks, previous.stacks() + 1);
        }
        ApexState next = new ApexState(targetId, nextStacks, now);
        apexStates.put(playerId, next);
        return next;
    }

    private boolean isBossOrSlayerTarget(LivingEntity target) {
        if (target == null) {
            return false;
        }
        if (target.getScoreboardTags().contains("servercore_boss")) {
            return true;
        }
        CreatureTagService tagService = CreatureTagService.getInstance();
        return tagService != null && tagService.hasTraitTag(target, CreatureTraitTag.BOSS);
    }

    private double maxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        return attribute == null ? 20.0 : Math.max(1.0, attribute.getValue());
    }

    private void clearNegativeEffects(Player player) {
        for (PotionEffectType type : java.util.List.of(
                PotionEffectType.POISON,
                PotionEffectType.WITHER,
                PotionEffectType.SLOWNESS,
                PotionEffectType.WEAKNESS,
                PotionEffectType.BLINDNESS,
                PotionEffectType.MINING_FATIGUE,
                PotionEffectType.NAUSEA
        )) {
            player.removePotionEffect(type);
        }
    }

    public record ShieldAdjustment(double threshold, double cooldownSeconds, boolean triggered) {
    }

    private record ActiveEffect(EnchantDefinition definition, int level) {
        EnchantEffectSpec effect() {
            return definition.effect();
        }
    }

    private record ApexState(UUID targetId, int stacks, long lastHitAtMs) {
    }

    private record TripleStrikeState(UUID targetId, int count) {
    }

    private record ComboState(int stacks, long expiresAtMs) {
    }

    private record SoulEaterState(int bestLevel, long expiresAtMs) {
    }
}
