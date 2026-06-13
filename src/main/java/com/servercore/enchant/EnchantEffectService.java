package com.servercore.enchant;

import com.servercore.ServerCorePlugin;
import com.servercore.combat.creature.CreatureTagService;
import com.servercore.combat.creature.CreatureTraitTag;
import com.servercore.combat.damage.DamageCategory;
import com.servercore.combat.damage.DamagePacket;
import com.servercore.combat.damage.DamageService;
import com.servercore.combat.damage.DamageSourceKind;
import com.servercore.combat.damage.DamageTag;
import com.servercore.manager.EnchantManager;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class EnchantEffectService implements Listener {

    private static EnchantEffectService instance;

    private final ServerCorePlugin plugin;
    private final Map<UUID, Long> vampirismCooldownUntil = new HashMap<>();
    private final Map<UUID, Long> perfectGuardCooldownUntil = new HashMap<>();
    private final Map<UUID, ApexState> apexStates = new HashMap<>();
    private final Map<UUID, Long> phoenixCooldownUntil = new HashMap<>();

    public EnchantEffectService(ServerCorePlugin plugin) {
        this.plugin = plugin;
        instance = this;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public static EnchantEffectService getInstance() {
        return instance;
    }

    public double applyOutgoingDamageModifiers(Player player, LivingEntity target, double damage, boolean isRanged, boolean isMagic) {
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
                                  double actualDamage, boolean isRanged, boolean isMagic) {
        if (event == null || player == null || target == null || finalDamage <= 0.0 || EnchantDamageContext.isSecondaryDamage()) {
            return;
        }
        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (!isRanged && !isMagic) {
            triggerCleave(player, target, weapon, finalDamage);
            triggerVampirism(player, target, weapon, actualDamage);
        }
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

    private void triggerVampirism(Player player, LivingEntity target, ItemStack weapon, double actualDamage) {
        ActiveEffect effect = activeEffect(weapon, EnchantEffectType.VAMPIRISM);
        if (effect == null || actualDamage <= 0.0) {
            return;
        }
        long now = System.currentTimeMillis();
        long until = vampirismCooldownUntil.getOrDefault(player.getUniqueId(), 0L);
        if (until > now) {
            return;
        }
        double ratio = effect.effect().param("heal_ratio", effect.level(), 0.01);
        if (isBossOrSlayerTarget(target)) {
            ratio *= effect.effect().param("boss_multiplier", effect.level(), 0.5);
        }
        double heal = actualDamage * Math.max(0.0, ratio);
        if (heal <= 0.0) {
            return;
        }
        double maxHealth = maxHealth(player);
        if (player.getHealth() < maxHealth) {
            player.setHealth(Math.min(maxHealth, player.getHealth() + heal));
            player.getWorld().spawnParticle(Particle.HEART, player.getLocation().add(0.0, 1.2, 0.0), 2, 0.25, 0.25, 0.25, 0.01);
        }
        double cooldownSeconds = effect.effect().param("cooldown_seconds", effect.level(), 1.0);
        vampirismCooldownUntil.put(player.getUniqueId(), now + Math.round(Math.max(0.0, cooldownSeconds) * 1000.0));
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
}
