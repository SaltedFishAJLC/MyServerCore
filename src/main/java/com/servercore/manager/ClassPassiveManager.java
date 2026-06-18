package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import com.servercore.combat.creature.CreatureTagService;
import com.servercore.combat.creature.CreatureTraitTag;
import com.servercore.combat.damage.DamageCategory;
import com.servercore.combat.damage.DamagePacket;
import com.servercore.combat.damage.DamageService;
import com.servercore.combat.damage.DamageSourceKind;
import dev.aurelium.auraskills.api.event.mana.ManaAbilityActivateEvent;
import dev.aurelium.auraskills.api.user.SkillsUser;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class ClassPassiveManager implements Listener {

    private static final double BLOOD_POOL_DAMAGE_ABSORB_PER_POINT = 1.0;
    private static final double CALAMITY_HEALTH_LOSS_RATE = 0.04;
    private static final double GUARDIAN_TRANSFER_RATE = 0.25;
    private static final double GUARDIAN_RADIUS = 8.0;
    private static final double MAX_RANGER_SPEED = 75.0;
    private static final long RANGER_DECAY_DELAY_MS = 3_000L;
    private static final long ASSASSIN_INVISIBILITY_MS = 5_000L;

    private static ClassPassiveManager instance;

    private final ServerCorePlugin plugin;
    private final Map<UUID, Double> prophetDistortion = new HashMap<>();
    private final Map<UUID, Double> prophetManaRemainder = new HashMap<>();
    private final Map<UUID, Double> calamityBloodPool = new HashMap<>();
    private final Map<UUID, Double> rangerSpeedBonus = new HashMap<>();
    private final Map<UUID, Long> rangerLastDamageAt = new HashMap<>();
    private final Map<UUID, Long> assassinAmbushUntil = new HashMap<>();
    private final Map<UUID, Long> lifestealCooldownUntil = new HashMap<>();
    private BukkitTask tickTask;
    private int tickCounter;

    public ClassPassiveManager(ServerCorePlugin plugin) {
        this.plugin = plugin;
        instance = this;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        this.tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPassives, 5L, 5L);
    }

    public static ClassPassiveManager getInstance() {
        return instance;
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isSurvivalLike(player) && player.getAllowFlight()) {
                player.setAllowFlight(false);
            }
        }
    }

    public double getRangerSpeedBonus(Player player) {
        if (!hasClass(player, ClassManager.PlayerClass.RANGER)) {
            return 0.0;
        }
        return rangerSpeedBonus.getOrDefault(player.getUniqueId(), 0.0);
    }

    public double getCalamityBloodPoolMagicMultiplier(Player player) {
        if (!hasClass(player, ClassManager.PlayerClass.CALAMITY_FAMILIAR)) {
            return 0.0;
        }
        return Math.max(0.0, calamityBloodPool.getOrDefault(player.getUniqueId(), 0.0)) / 10.0 * 0.01;
    }

    public boolean rollCritical(Player player, double critChance) {
        double chance = clamp(critChance, 0.0, 1.0);
        if (consumeAssassinAmbush(player)) {
            return true;
        }

        boolean firstRoll = ThreadLocalRandom.current().nextDouble() < chance;
        if (!hasClass(player, ClassManager.PlayerClass.GAMBLER)) {
            return firstRoll;
        }
        return firstRoll || ThreadLocalRandom.current().nextDouble() < chance;
    }

    public void onPlayerDealtDamage(Player player, LivingEntity target) {
        if (player == null || target == null || target.equals(player)) {
            return;
        }
        if (hasClass(player, ClassManager.PlayerClass.RANGER)) {
            UUID id = player.getUniqueId();
            double next = Math.min(MAX_RANGER_SPEED, rangerSpeedBonus.getOrDefault(id, 0.0) + 3.0);
            rangerSpeedBonus.put(id, next);
            rangerLastDamageAt.put(id, System.currentTimeMillis());
            refreshPlayer(player);
        }
    }

    public void applyLifesteal(Player player, double effectiveDamage, double lifestealRate,
                               boolean isRanged, boolean isMagic, boolean forceAllowed) {
        if (player == null || player.isDead() || effectiveDamage <= 0.0 || lifestealRate <= 0.0) {
            return;
        }
        if (!forceAllowed && (isRanged || isMagic)) {
            return;
        }
        long now = System.currentTimeMillis();
        long until = lifestealCooldownUntil.getOrDefault(player.getUniqueId(), 0L);
        if (until > now) {
            return;
        }

        double heal = Math.min(effectiveDamage * lifestealRate, maxLifestealPerHit(player));
        if (heal <= 0.0) {
            return;
        }

        AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double maxHealth = maxHealthAttribute == null ? 20.0 : maxHealthAttribute.getValue();
        if (player.getHealth() >= maxHealth) {
            return;
        }
        lifestealCooldownUntil.put(player.getUniqueId(), now + 750L);
        player.setHealth(Math.min(maxHealth, player.getHealth() + heal));
    }

    private double maxLifestealPerHit(Player player) {
        AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double maxHealth = maxHealthAttribute == null ? 20.0 : maxHealthAttribute.getValue();
        double ratio = clamp(plugin.getConfig().getDouble("power.sustain.max_lifesteal_per_hit_ratio", 0.15), 0.0, 1.0);
        return Math.max(0.0, maxHealth * ratio);
    }

    public double getReaperDamageMultiplier(Player player, LivingEntity target) {
        if (!hasClass(player, ClassManager.PlayerClass.REAPER) || target == null) {
            return 1.0;
        }

        double maxHealth = getEffectiveMaxHealth(target);
        if (maxHealth <= 0.0) {
            return 1.0;
        }
        double currentHealth = clamp(getEffectiveHealth(target), 0.0, maxHealth);
        double missingPercent = (maxHealth - currentHealth) / maxHealth * 100.0;
        return 1.0 + missingPercent * 0.015;
    }

    public void onPlayerDodged(Player player) {
        if (!hasClass(player, ClassManager.PlayerClass.ASSASSIN)) {
            return;
        }

        player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 100, 0, false, false, true));
        assassinAmbushUntil.put(player.getUniqueId(), System.currentTimeMillis() + ASSASSIN_INVISIBILITY_MS);
        clearNearbyNonBossTargets(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onManaAbilityActivate(ManaAbilityActivateEvent event) {
        recordManaSpent(event.getPlayer(), event.getManaUsed());
    }

    public void recordManaSpent(Player player, double manaSpent) {
        if (player == null || manaSpent <= 0.0) {
            return;
        }

        if (hasClass(player, ClassManager.PlayerClass.PROPHET)) {
            UUID id = player.getUniqueId();
            double total = prophetManaRemainder.getOrDefault(id, 0.0) + manaSpent;
            int gained = (int) Math.floor(total / 15.0);
            prophetManaRemainder.put(id, total - gained * 15.0);
            if (gained > 0) {
                prophetDistortion.merge(id, (double) gained, Double::sum);
            }
        }

        if (hasClass(player, ClassManager.PlayerClass.CALAMITY_FAMILIAR)) {
            bleedCalamityCaster(player);
            calamityBloodPool.merge(player.getUniqueId(), manaSpent * 2.0, Double::sum);
            refreshPlayer(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIncomingDamageBeforeReduction(EntityDamageEvent event) {
        if (DamageService.isInternalDamageActive() || !(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!hasClass(player, ClassManager.PlayerClass.CALAMITY_FAMILIAR)) {
            return;
        }

        UUID id = player.getUniqueId();
        double pool = calamityBloodPool.getOrDefault(id, 0.0);
        double damage = Math.max(0.0, event.getDamage());
        if (pool <= 0.0 || damage <= 0.0) {
            return;
        }

        double absorbed = Math.min(pool / BLOOD_POOL_DAMAGE_ABSORB_PER_POINT, damage);
        calamityBloodPool.put(id, Math.max(0.0, pool - absorbed * BLOOD_POOL_DAMAGE_ABSORB_PER_POINT));
        event.setDamage(Math.max(0.0, damage - absorbed));
        refreshPlayer(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onIncomingDamageAfterReduction(EntityDamageEvent event) {
        if (DamageService.isInternalDamageActive() || !(event.getEntity() instanceof Player player)) {
            return;
        }
        if (hasClass(player, ClassManager.PlayerClass.GUARDIAN)) {
            return;
        }

        double finalDamage = Math.max(0.0, event.getFinalDamage());
        if (finalDamage <= 0.0) {
            return;
        }

        Player guardian = findNearestGuardian(player);
        if (guardian == null) {
            return;
        }

        double transfer = Math.min(finalDamage, player.getHealth()) * GUARDIAN_TRANSFER_RATE;
        if (transfer <= 0.0) {
            return;
        }

        event.setDamage(Math.max(0.0, event.getDamage() - transfer));
        applyGuardianTransferDamage(guardian, transfer, event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRangerDoubleJump(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (!hasClass(player, ClassManager.PlayerClass.RANGER) || !isSurvivalLike(player)) {
            return;
        }

        event.setCancelled(true);
        player.setFlying(false);
        player.setAllowFlight(false);

        Vector direction = player.getLocation().getDirection();
        direction.setY(0.0);
        if (direction.lengthSquared() > 0.0001) {
            direction.normalize().multiply(0.35);
        }
        direction.setY(0.75);
        player.setVelocity(direction);
        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation(), 12, 0.25, 0.1, 0.25, 0.02);
        player.playSound(player.getLocation(), Sound.ENTITY_BREEZE_JUMP, 0.6f, 1.25f);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> updateRangerFlight(event.getPlayer()));
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> updateRangerFlight(event.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        prophetDistortion.remove(id);
        prophetManaRemainder.remove(id);
        calamityBloodPool.remove(id);
        rangerSpeedBonus.remove(id);
        rangerLastDamageAt.remove(id);
        assassinAmbushUntil.remove(id);
        lifestealCooldownUntil.remove(id);
    }

    private boolean consumeAssassinAmbush(Player player) {
        if (!hasClass(player, ClassManager.PlayerClass.ASSASSIN)) {
            return false;
        }

        UUID id = player.getUniqueId();
        Long until = assassinAmbushUntil.get(id);
        if (until == null || until < System.currentTimeMillis()) {
            assassinAmbushUntil.remove(id);
            return false;
        }

        assassinAmbushUntil.remove(id);
        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        return true;
    }

    private void tickPassives() {
        tickCounter++;
        long now = System.currentTimeMillis();

        for (Player player : Bukkit.getOnlinePlayers()) {
            updateRangerFlight(player);
            tickAssassinAmbush(player, now);
        }

        if (tickCounter % 4 == 0) {
            tickRangerDecay(now);
            tickProphetAuras();
        }
        if (tickCounter % 8 == 0) {
            tickProphetDistortionDecay();
        }
    }

    private void updateRangerFlight(Player player) {
        if (player == null) {
            return;
        }

        boolean ranger = hasClass(player, ClassManager.PlayerClass.RANGER);
        if (!isSurvivalLike(player)) {
            return;
        }
        if (!ranger) {
            if (player.getAllowFlight()) {
                player.setAllowFlight(false);
            }
            return;
        }
        if (player.isOnGround()) {
            player.setAllowFlight(true);
        }
    }

    private void tickRangerDecay(long now) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            if (!hasClass(player, ClassManager.PlayerClass.RANGER)) {
                if (rangerSpeedBonus.remove(id) != null) {
                    refreshPlayer(player);
                }
                rangerLastDamageAt.remove(id);
                continue;
            }

            double speed = rangerSpeedBonus.getOrDefault(id, 0.0);
            if (speed <= 0.0) {
                continue;
            }
            long lastDamage = rangerLastDamageAt.getOrDefault(id, 0L);
            if (now - lastDamage <= RANGER_DECAY_DELAY_MS) {
                continue;
            }

            rangerSpeedBonus.put(id, Math.max(0.0, speed - 10.0));
            refreshPlayer(player);
        }
    }

    private void tickProphetAuras() {
        for (Player prophet : Bukkit.getOnlinePlayers()) {
            if (!hasClass(prophet, ClassManager.PlayerClass.PROPHET)) {
                prophetDistortion.remove(prophet.getUniqueId());
                prophetManaRemainder.remove(prophet.getUniqueId());
                continue;
            }

            double distortion = prophetDistortion.getOrDefault(prophet.getUniqueId(), 0.0);
            if (distortion <= 0.0) {
                continue;
            }

            double radius = Math.min(16.0, 3.0 + distortion * 0.5);
            spawnProphetAura(prophet, radius);
            double radiusSquared = radius * radius;
            for (Player target : prophet.getWorld().getPlayers()) {
                if (target.isDead() || target.getLocation().distanceSquared(prophet.getLocation()) > radiusSquared) {
                    continue;
                }
                heal(target, distortion);
                restoreMana(target, distortion * 0.5);
            }
        }
    }

    private void tickProphetDistortionDecay() {
        for (UUID id : Set.copyOf(prophetDistortion.keySet())) {
            double next = prophetDistortion.getOrDefault(id, 0.0) - 1.0;
            if (next <= 0.0) {
                prophetDistortion.remove(id);
            } else {
                prophetDistortion.put(id, next);
            }
        }
    }

    private void tickAssassinAmbush(Player player, long now) {
        UUID id = player.getUniqueId();
        Long until = assassinAmbushUntil.get(id);
        if (until == null) {
            return;
        }
        if (!hasClass(player, ClassManager.PlayerClass.ASSASSIN) || until < now) {
            assassinAmbushUntil.remove(id);
            return;
        }
        clearNearbyNonBossTargets(player);
    }

    private void bleedCalamityCaster(Player player) {
        AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double maxHealth = maxHealthAttribute == null ? 20.0 : maxHealthAttribute.getValue();
        double nextHealth = Math.max(1.0, player.getHealth() - maxHealth * CALAMITY_HEALTH_LOSS_RATE);
        player.setHealth(nextHealth);
    }

    private Player findNearestGuardian(Player damagedPlayer) {
        Player nearest = null;
        double bestDistance = GUARDIAN_RADIUS * GUARDIAN_RADIUS;
        for (Player candidate : damagedPlayer.getWorld().getPlayers()) {
            if (candidate.equals(damagedPlayer) || candidate.isDead() || !candidate.isValid()) {
                continue;
            }
            if (!hasClass(candidate, ClassManager.PlayerClass.GUARDIAN)) {
                continue;
            }
            double distance = candidate.getLocation().distanceSquared(damagedPlayer.getLocation());
            if (distance <= bestDistance) {
                bestDistance = distance;
                nearest = candidate;
            }
        }
        return nearest;
    }

    private void applyGuardianTransferDamage(Player guardian, double amount, EntityDamageEvent originalEvent) {
        DamageService damageService = DamageService.getInstance();
        LivingEntity source = MobDamageSourceResolver.resolveMobAttacker(originalEvent);
        if (damageService != null) {
            damageService.applyDamage(new DamagePacket(
                    source,
                    guardian,
                    amount,
                    DamageCategory.TRUE,
                    Set.of(),
                    DamageSourceKind.SYSTEM,
                    "guardian_transfer"
            ));
            return;
        }
        if (source != null) {
            guardian.damage(amount, source);
        } else {
            guardian.damage(amount);
        }
    }

    private void clearNearbyNonBossTargets(Player player) {
        for (Entity entity : player.getNearbyEntities(32.0, 16.0, 32.0)) {
            if (entity instanceof Mob mob && player.equals(mob.getTarget()) && !isBoss(mob)) {
                mob.setTarget(null);
            }
        }
    }

    private boolean isBoss(LivingEntity entity) {
        if (entity.getScoreboardTags().contains("servercore_boss")) {
            return true;
        }
        CreatureTagService tagService = CreatureTagService.getInstance();
        if (tagService != null && tagService.hasTraitTag(entity, CreatureTraitTag.BOSS)) {
            return true;
        }
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return false;
        }
        double mod = entity.getPersistentDataContainer().getOrDefault(pdc.KEY_MOB_SCALING_MOD, PersistentDataType.DOUBLE, 0.0);
        return mod >= 6.4;
    }

    private void spawnProphetAura(Player prophet, double radius) {
        int points = 24;
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2.0 * i / points;
            prophet.getWorld().spawnParticle(
                    Particle.END_ROD,
                    prophet.getLocation().clone().add(Math.cos(angle) * radius, 0.1, Math.sin(angle) * radius),
                    1,
                    0.0,
                    0.0,
                    0.0,
                    0.0
            );
        }
    }

    private void heal(Player player, double amount) {
        AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double maxHealth = maxHealthAttribute == null ? 20.0 : maxHealthAttribute.getValue();
        if (player.getHealth() < maxHealth) {
            player.setHealth(Math.min(maxHealth, player.getHealth() + amount));
        }
    }

    private void restoreMana(Player player, double amount) {
        AuraSkillsBridge bridge = AuraSkillsBridge.getInstance();
        SkillsUser user = bridge == null ? null : bridge.getUser(player);
        if (user != null && amount > 0.0) {
            user.setMana(Math.min(user.getMaxMana(), user.getMana() + amount));
        }
    }

    private double getEffectiveHealth(LivingEntity target) {
        PDCManager pdc = PDCManager.getInstance();
        if (pdc != null) {
            Double virtualHealth = target.getPersistentDataContainer().get(pdc.KEY_MOB_VIRTUAL_HEALTH, PersistentDataType.DOUBLE);
            if (virtualHealth != null && virtualHealth > 0.0) {
                return virtualHealth;
            }
        }
        return Math.max(0.0, target.getHealth());
    }

    private double getEffectiveMaxHealth(LivingEntity target) {
        PDCManager pdc = PDCManager.getInstance();
        if (pdc != null) {
            Double virtualMaxHealth = target.getPersistentDataContainer().get(pdc.KEY_MOB_VIRTUAL_MAX_HEALTH, PersistentDataType.DOUBLE);
            if (virtualMaxHealth != null && virtualMaxHealth > 0.0) {
                return virtualMaxHealth;
            }
        }
        AttributeInstance maxHealthAttribute = target.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        return maxHealthAttribute == null ? Math.max(1.0, target.getHealth()) : Math.max(1.0, maxHealthAttribute.getValue());
    }

    private boolean hasClass(Player player, ClassManager.PlayerClass playerClass) {
        if (player == null) {
            return false;
        }
        ClassManager classManager = ClassManager.getInstance();
        return classManager != null && classManager.getPlayerClass(player) == playerClass;
    }

    private boolean isSurvivalLike(Player player) {
        return player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE;
    }

    private void refreshPlayer(Player player) {
        PlayerStatCache cache = PlayerStatCache.getInstance();
        if (cache != null) {
            cache.updateCache(player);
            return;
        }
        AttributeManager attributeManager = AttributeManager.getInstance();
        if (attributeManager != null) {
            attributeManager.refreshPlayer(player);
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
