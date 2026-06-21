package com.servercore.enchant;

import com.servercore.ServerCorePlugin;
import com.servercore.combat.damage.DamageCategory;
import com.servercore.combat.damage.DamagePacket;
import com.servercore.combat.damage.DamageService;
import com.servercore.combat.damage.DamageSourceKind;
import com.servercore.combat.damage.DamageTag;
import com.servercore.manager.AccessoryManager;
import com.servercore.manager.AttributeManager;
import com.servercore.manager.EnchantManager;
import com.servercore.manager.PDCManager;
import com.servercore.manager.WeaponTemplateManager;
import dev.aurelium.auraskills.api.event.mana.ManaAbilityActivateEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityAirChangeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Runtime state and cross-system hooks for armor enchants and Chimera.
 */
public final class EquipmentEnchantService implements Listener {

    private static final long MANA_WINDOW_MS = 10_000L;
    private static final long OUT_OF_COMBAT_MS = 5_000L;
    private static EquipmentEnchantService instance;

    private final ServerCorePlugin plugin;
    private final NamespacedKey movementSpeedKey;
    private final NamespacedKey sneakingSpeedKey;
    private final NamespacedKey submergedMiningKey;
    private final NamespacedKey waterMovementKey;
    private final NamespacedKey jumpStrengthKey;
    private final NamespacedKey safeFallKey;
    private final NamespacedKey fallMultiplierKey;
    private final NamespacedKey syncedFrostWalkerKey;
    private final NamespacedKey syncedSoulSpeedKey;
    private final NamespacedKey syncedHideEnchantsKey;
    private final Map<UUID, Long> lastDamageAt = new HashMap<>();
    private final Map<UUID, Long> lastManaSpendAt = new HashMap<>();
    private final Map<UUID, Deque<ManaSpend>> manaSpends = new HashMap<>();
    private final Map<UUID, List<PendingHeal>> pendingHeals = new HashMap<>();
    private final Map<UUID, Long> counterCooldownUntil = new HashMap<>();
    private final Map<UUID, TempArmor> counterArmor = new HashMap<>();
    private final Map<UUID, Double> metallicArmor = new HashMap<>();
    private final Map<UUID, Long> arcaneBufferCooldownUntil = new HashMap<>();
    private final Map<UUID, Long> emergencyReserveCooldownUntil = new HashMap<>();
    private final Map<UUID, Long> mindFortressCooldownUntil = new HashMap<>();
    private final Map<UUID, Long> nimbleUntil = new HashMap<>();
    private final Map<UUID, Location> lastLocations = new HashMap<>();
    private final Map<UUID, Long> movingSince = new HashMap<>();
    private final Map<UUID, Boolean> shadeArmed = new HashMap<>();
    private final Map<UUID, Long> shadeCooldownUntil = new HashMap<>();
    private final Map<UUID, Long> shadeSpeedUntil = new HashMap<>();
    private final Map<UUID, Map<UUID, Long>> hunterGlowUntil = new HashMap<>();
    private BukkitTask tickTask;
    private int tickCounter;

    public EquipmentEnchantService(ServerCorePlugin plugin) {
        this.plugin = plugin;
        this.movementSpeedKey = new NamespacedKey(plugin, "enchant_movement_speed");
        this.sneakingSpeedKey = new NamespacedKey(plugin, "enchant_sneaking_speed");
        this.submergedMiningKey = new NamespacedKey(plugin, "enchant_submerged_mining");
        this.waterMovementKey = new NamespacedKey(plugin, "enchant_water_movement");
        this.jumpStrengthKey = new NamespacedKey(plugin, "enchant_jump_strength");
        this.safeFallKey = new NamespacedKey(plugin, "enchant_safe_fall");
        this.fallMultiplierKey = new NamespacedKey(plugin, "enchant_fall_multiplier");
        this.syncedFrostWalkerKey = new NamespacedKey(plugin, "synced_frost_walker");
        this.syncedSoulSpeedKey = new NamespacedKey(plugin, "synced_soul_speed");
        this.syncedHideEnchantsKey = new NamespacedKey(plugin, "synced_hide_enchants");
        instance = this;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        this.tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5L, 5L);
    }

    public static EquipmentEnchantService getInstance() {
        return instance;
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            clearAttributeModifiers(player);
            clearHunterGlow(player);
        }
    }

    public double getChimeraBonus(Player player, ItemStack weapon, NamespacedKey statKey) {
        return statKey == null ? 0.0 : getChimeraBonuses(player, weapon).getOrDefault(statKey.getKey(), 0.0);
    }

    public Map<String, Double> getChimeraBonuses(Player player, ItemStack weapon) {
        if (player == null || weapon == null) {
            return Map.of();
        }
        int level = activeLevel(weapon, "chimera");
        AccessoryManager accessoryManager = AccessoryManager.getInstance();
        PDCManager pdc = PDCManager.getInstance();
        if (level <= 0 || accessoryManager == null || pdc == null) {
            return Map.of();
        }
        ItemStack imprint = accessoryManager.loadImprint(player);
        if (imprint == null || imprint.getType().isAir()) {
            return Map.of();
        }
        double ratio = value("chimera", level, "imprint_ratio", 0.0);
        Map<String, Double> enchantNumeric = EnchantStatResolver.getInstance() == null
                ? Map.of()
                : EnchantStatResolver.getInstance().resolveNumeric(imprint);
        Map<String, Double> result = new HashMap<>();
        for (NamespacedKey key : panelStatKeys(pdc)) {
            double stat = pdc.getStat(imprint, key) + enchantNumeric.getOrDefault(key.getKey(), 0.0);
            if (Math.abs(stat) > 0.0001) {
                result.put(key.getKey(), stat * ratio);
            }
        }
        for (Map.Entry<String, Double> entry : enchantNumeric.entrySet()) {
            result.putIfAbsent(entry.getKey(), entry.getValue() * ratio);
        }
        return Map.copyOf(result);
    }

    public double getEquippedChimeraBonus(Player player, String key) {
        if (player == null || key == null) {
            return 0.0;
        }
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        WeaponTemplateManager templates = WeaponTemplateManager.getInstance();
        double mainMultiplier = templates == null ? 1.0
                : templates.getEquipmentStatMultiplier(player, mainHand, EquipmentSlot.HAND);
        double offMultiplier = templates == null ? 0.0
                : templates.getEquipmentStatMultiplier(player, offHand, EquipmentSlot.OFF_HAND);
        return getChimeraBonuses(player, mainHand).getOrDefault(key, 0.0) * mainMultiplier
                + getChimeraBonuses(player, offHand).getOrDefault(key, 0.0) * offMultiplier;
    }

    public double getLegionPanelMultiplier(Player player, ItemStack armor) {
        int level = activeLevel(armor, "legion");
        if (player == null || level <= 0) {
            return 1.0;
        }
        int nearby = 0;
        double radiusSquared = 30.0 * 30.0;
        for (Player candidate : player.getWorld().getPlayers()) {
            if (!candidate.isValid() || candidate.isDead()) {
                continue;
            }
            if (candidate.getLocation().distanceSquared(player.getLocation()) <= radiusSquared) {
                nearby++;
            }
        }
        double perPlayer = value("legion", level, "panel_ratio_per_player", 0.0);
        return 1.0 + Math.min(10, nearby) * perPlayer;
    }

    public double modifyArmorProvided(Player player, ItemStack armor, double baseArmor) {
        if (player == null || armor == null || baseArmor <= 0.0) {
            return Math.max(0.0, baseArmor);
        }
        double result = baseArmor * getLegionPanelMultiplier(player, armor);
        int lastStand = activeLevel(armor, "last_stand");
        if (lastStand > 0 && healthRatio(player) < 0.40) {
            result *= 1.0 + value("last_stand", lastStand, "armor_ratio", 0.0);
        }
        return Math.max(0.0, result);
    }

    public double getDynamicArmor(Player player) {
        if (player == null) {
            return 0.0;
        }
        long now = System.currentTimeMillis();
        TempArmor counter = counterArmor.get(player.getUniqueId());
        double total = counter != null && counter.expiresAtMs() > now ? counter.amount() : 0.0;
        total += metallicArmor.getOrDefault(player.getUniqueId(), 0.0);
        total += Math.min(1_000.0,
                getRecentManaSpent(player) * sumArmorCurve(player, "refrigerate", "armor_per_mana"));

        ItemStack helmet = player.getInventory().getHelmet();
        int coolheaded = activeLevel(helmet, "coolheaded");
        if (coolheaded > 0) {
            long enemies = player.getNearbyEntities(10.0, 10.0, 10.0).stream()
                    .filter(entity -> entity instanceof Enemy)
                    .filter(entity -> entity instanceof LivingEntity living && living.isValid() && !living.isDead())
                    .limit(10)
                    .count();
            total += enemies * value("coolheaded", coolheaded, "armor_per_enemy", 0.0);
        }
        total += getEquippedChimeraBonus(player, "base_armor");
        return Math.max(0.0, total);
    }

    public double getProjectileArmor(Player player) {
        if (player == null) {
            return 0.0;
        }
        double total = 0.0;
        for (ItemStack armor : armor(player)) {
            int level = activeLevel(armor, "projectile_protection");
            if (level > 0) {
                total += value("projectile_protection", level, "projectile_armor", 0.0)
                        * getLegionPanelMultiplier(player, armor);
            }
        }
        if (nimbleUntil.getOrDefault(player.getUniqueId(), 0L) >= System.currentTimeMillis()) {
            ItemStack boots = player.getInventory().getBoots();
            int level = activeLevel(boots, "nimble_evasion");
            total += value("nimble_evasion", level, "projectile_armor", 0.0);
        }
        total += getEquippedChimeraBonus(player, "projectile_armor");
        return Math.max(0.0, total);
    }

    public double getFerociousManaCritDamage(Player player) {
        double ratio = sumArmorCurve(player, "ferocious_mana", "crit_damage_per_mana");
        return Math.min(1.50, Math.max(0.0, getRecentManaSpent(player) * ratio));
    }

    public double getMaxHealthBonus(Player player) {
        return sumArmorNumeric(player, "growth", "max_health")
                + getEquippedChimeraBonus(player, "max_health");
    }

    public double getMaxManaBonus(Player player) {
        return sumArmorNumeric(player, "big_brain", "max_mana")
                + sumArmorNumeric(player, "smarty_pants", "max_mana")
                + sumArmorNumeric(player, "reflection", "max_mana")
                + getEquippedChimeraBonus(player, "max_mana");
    }

    public int getInsightsWisdomBonus(Player player) {
        int level = activeLevel(player == null ? null : player.getInventory().getHelmet(), "insights");
        if (player == null || level <= 0) {
            return 0;
        }
        int levelsPerPoint = Math.max(1,
                (int) Math.round(value("insights", level, "experience_levels_per_wisdom", 7.0)));
        return Math.min(40, player.getLevel() / levelsPerPoint);
    }

    public double getUnpenalizedNaturalRegen(Player player, double baseRegen) {
        double result = Math.max(0.0, baseRegen);
        if (System.currentTimeMillis() - lastDamageAt.getOrDefault(player.getUniqueId(), 0L) >= OUT_OF_COMBAT_MS) {
            result += sumArmorNumeric(player, "respite", "out_of_combat_regen");
        }
        return result;
    }

    public double modifyNaturalRegen(Player player, double baseRegen) {
        int pieces = countArmorWith(player, "no_pain_no_gain");
        return getUnpenalizedNaturalRegen(player, baseRegen) * Math.max(0.0, 1.0 - pieces * 0.25);
    }

    public double modifyHealingAmount(Player player, double amount) {
        if (player == null || amount <= 0.0 || healthRatio(player) >= 0.30) {
            return Math.max(0.0, amount);
        }
        int level = activeLevel(player.getInventory().getChestplate(), "emergency_treatment");
        return amount * (1.0 + value("emergency_treatment", level, "healing_ratio", 0.0));
    }

    public void heal(Player player, double amount) {
        if (player == null || player.isDead() || amount <= 0.0) {
            return;
        }
        double maxHealth = maxHealth(player);
        double adjusted = modifyHealingAmount(player, amount);
        player.setHealth(Math.min(maxHealth, player.getHealth() + adjusted));
    }

    public double modifyIncomingDamage(Player player, LivingEntity source, double damage, DamageCategory category,
                                       boolean projectile, boolean direct) {
        IncomingDamagePlan plan = previewIncomingDamage(player, source, damage, category, projectile, direct);
        plan.commit().run();
        return plan.damage();
    }

    public IncomingDamagePlan previewIncomingDamage(Player player, LivingEntity source, double damage,
                                                    DamageCategory category, boolean projectile, boolean direct) {
        if (player == null || damage <= 0.0) {
            return IncomingDamagePlan.noop(damage);
        }
        double result = damage;
        long now = System.currentTimeMillis();
        List<Runnable> commits = new ArrayList<>();

        if (category == DamageCategory.MAGIC) {
            int level = activeLevel(player.getInventory().getHelmet(), "mind_fortress");
            if (level > 0) {
                double mana = ManaAccess.getMana(player);
                double maxMana = Math.max(1.0, ManaAccess.getMaxMana(player));
                if (mana / maxMana < 0.25) {
                    commits.add(() -> mindFortressCooldownUntil.put(player.getUniqueId(), now + 5_000L));
                } else if (mana / maxMana >= 0.50
                        && mindFortressCooldownUntil.getOrDefault(player.getUniqueId(), 0L) <= now) {
                    result *= 1.0 - value("mind_fortress", level, "magic_reduction", 0.0);
                }
            }
        }

        int arcane = activeLevel(player.getInventory().getChestplate(), "arcane_buffer");
        if (arcane > 0
                && result > maxHealth(player) * 0.12
                && arcaneBufferCooldownUntil.getOrDefault(player.getUniqueId(), 0L) <= now) {
            double maxSpend = ManaAccess.getMaxMana(player)
                    * value("arcane_buffer", arcane, "max_mana_spend_ratio", 0.0);
            double plannedSpend = Math.min(ManaAccess.getMana(player), Math.min(result, maxSpend));
            if (plannedSpend > 0.0) {
                result = Math.max(0.0, result - plannedSpend);
                commits.add(() -> {
                    ManaAccess.consumeMana(player, plannedSpend);
                    arcaneBufferCooldownUntil.put(player.getUniqueId(), now + 4_000L);
                });
            }
        }

        int shade = activeLevel(player.getInventory().getBoots(), "shade_step");
        if (shade > 0 && direct && shadeArmed.getOrDefault(player.getUniqueId(), false)
                && shadeCooldownUntil.getOrDefault(player.getUniqueId(), 0L) <= now) {
            result *= 0.50;
            long cooldownUntil = now
                    + Math.round(value("shade_step", shade, "cooldown_seconds", 14.0) * 1_000.0);
            commits.add(() -> {
                shadeArmed.put(player.getUniqueId(), false);
                shadeSpeedUntil.put(player.getUniqueId(), now + 1_000L);
                shadeCooldownUntil.put(player.getUniqueId(), cooldownUntil);
                movingSince.put(player.getUniqueId(), now);
                player.getWorld().spawnParticle(Particle.PORTAL, player.getLocation().add(0.0, 1.0, 0.0),
                        24, 0.35, 0.6, 0.35, 0.05);
            });
        }
        return new IncomingDamagePlan(Math.max(0.0, result), combine(commits));
    }

    public void afterInternalDamage(DamagePacket packet, double finalDamage) {
        if (packet == null || !(packet.target() instanceof Player player) || finalDamage <= 0.0
                || EnchantDamageContext.isSecondaryDamage()) {
            return;
        }
        boolean projectile = packet.tags().contains(DamageTag.PROJECTILE);
        boolean direct = packet.source() != null && !packet.tags().contains(DamageTag.DOT);
        afterDamage(player, packet.source(), finalDamage, packet.category(), projectile, direct, true);
    }

    public void recordManaSpent(Player player, double amount) {
        if (player == null || amount <= 0.0) {
            return;
        }
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        lastManaSpendAt.put(id, now);
        Deque<ManaSpend> spends = manaSpends.computeIfAbsent(id, ignored -> new ArrayDeque<>());
        spends.addLast(new ManaSpend(now, amount));
        pruneManaSpends(spends, now);

        double reboundRatio = sumArmorCurve(player, "mana_rebound", "healing_ratio");
        if (reboundRatio > 0.0) {
            queueHeal(player, amount * reboundRatio, 40, HealKind.MANA_REBOUND);
        }
    }

    public void recordCombatAction(Player player) {
        if (player != null) {
            lastDamageAt.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    public double reduceDeathPenalty(Player player, double basePenalty) {
        double reduction = 0.0;
        for (ItemStack armor : armor(player)) {
            int level = activeLevel(armor, "bank");
            reduction += value("bank", level, "coin_loss_reduction", 0.0);
        }
        return Math.max(0.0, basePenalty * (1.0 - Math.min(1.0, reduction)));
    }

    public boolean keepsOnDeath(ItemStack item) {
        return activeLevel(item, "bank") > 0;
    }

    public boolean shouldIgnoreEnvironmentalDamage(Player player, String cause) {
        if (player == null || cause == null) {
            return false;
        }
        ItemStack boots = player.getInventory().getBoots();
        if (cause.equals("HOT_FLOOR") && activeLevel(boots, "frost_walker") > 0) {
            return true;
        }
        if (activeLevel(boots, "walk_thru_fire") <= 0 || isInLava(player)) {
            return false;
        }
        return cause.equals("FIRE") || cause.equals("FIRE_TICK") || cause.equals("HOT_FLOOR");
    }

    private void tick() {
        long now = System.currentTimeMillis();
        tickCounter++;
        for (Player player : Bukkit.getOnlinePlayers()) {
            tickMovement(player, now);
            tickMetallicize(player);
            tickMindFortress(player, now);
            tickPendingHeals(player);
            tickHunterGlow(player, now);
            syncVanillaBootEnchants(player);
            applyAttributeModifiers(player, now);
            if (tickCounter % 4 == 0) {
                tickMeditation(player, now);
            }
        }
    }

    private void tickMovement(Player player, long now) {
        UUID id = player.getUniqueId();
        Location current = player.getLocation();
        Location previous = lastLocations.put(id, current.clone());
        boolean moved = previous != null
                && previous.getWorld() == current.getWorld()
                && horizontalDistanceSquared(previous, current) > 0.0004
                && horizontalDistanceSquared(previous, current) < 16.0;
        if (moved) {
            movingSince.putIfAbsent(id, now);
        } else {
            movingSince.remove(id);
            shadeArmed.put(id, false);
        }

        ItemStack boots = player.getInventory().getBoots();
        int shade = activeLevel(boots, "shade_step");
        if (shade <= 0) {
            shadeArmed.remove(id);
            movingSince.remove(id);
            shadeSpeedUntil.remove(id);
        } else if (moved
                && now - movingSince.getOrDefault(id, now) >= 2_000L
                && shadeCooldownUntil.getOrDefault(id, 0L) <= now) {
            shadeArmed.put(id, true);
        }

        if (activeLevel(boots, "nimble_evasion") > 0 && (player.isSprinting() || player.isGliding())) {
            nimbleUntil.put(id, now + 500L);
        }
    }

    private void tickMetallicize(Player player) {
        int level = activeLevel(player.getInventory().getChestplate(), "metallicize");
        UUID id = player.getUniqueId();
        if (level <= 0) {
            metallicArmor.remove(id);
            return;
        }
        double gain = value("metallicize", level, "armor_per_interval", 0.0);
        metallicArmor.put(id, Math.min(300.0, metallicArmor.getOrDefault(id, 0.0) + gain));
    }

    private void tickMeditation(Player player, long now) {
        int level = activeLevel(player.getInventory().getHelmet(), "meditation");
        if (level <= 0) {
            return;
        }
        UUID id = player.getUniqueId();
        if (now - lastDamageAt.getOrDefault(id, 0L) < 4_000L
                || now - lastManaSpendAt.getOrDefault(id, 0L) < 4_000L) {
            return;
        }
        double amount = ManaAccess.getMaxMana(player)
                * value("meditation", level, "mana_regen_ratio", 0.0);
        ManaAccess.restoreMana(player, amount);
    }

    private void tickMindFortress(Player player, long now) {
        if (activeLevel(player.getInventory().getHelmet(), "mind_fortress") <= 0) {
            return;
        }
        double maxMana = Math.max(1.0, ManaAccess.getMaxMana(player));
        if (ManaAccess.getMana(player) / maxMana < 0.25) {
            mindFortressCooldownUntil.put(player.getUniqueId(), now + 5_000L);
        }
    }

    private void tickPendingHeals(Player player) {
        List<PendingHeal> heals = pendingHeals.get(player.getUniqueId());
        if (heals == null || heals.isEmpty()) {
            return;
        }
        double amount = 0.0;
        Iterator<PendingHeal> iterator = heals.iterator();
        while (iterator.hasNext()) {
            PendingHeal pending = iterator.next();
            if (pending.ticksRemaining <= 0 || pending.remaining <= 0.0001) {
                iterator.remove();
                continue;
            }
            double portion = pending.remaining / pending.ticksRemaining;
            pending.remaining -= portion;
            pending.ticksRemaining--;
            amount += portion;
            if (pending.ticksRemaining <= 0 || pending.remaining <= 0.0001) {
                iterator.remove();
            }
        }
        if (amount > 0.0) {
            heal(player, amount);
        }
        if (heals.isEmpty()) {
            pendingHeals.remove(player.getUniqueId());
        }
    }

    private void tickHunterGlow(Player player, long now) {
        Map<UUID, Long> glows = hunterGlowUntil.get(player.getUniqueId());
        if (glows == null) {
            return;
        }
        Iterator<Map.Entry<UUID, Long>> iterator = glows.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> entry = iterator.next();
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entry.getValue() <= now || !(entity instanceof LivingEntity living) || !living.isValid()) {
                if (entity instanceof LivingEntity living) {
                    player.sendPotionEffectChangeRemove(living, PotionEffectType.GLOWING);
                }
                iterator.remove();
            }
        }
        if (glows.isEmpty()) {
            hunterGlowUntil.remove(player.getUniqueId());
        }
    }

    private void applyAttributeModifiers(Player player, long now) {
        ItemStack helmet = player.getInventory().getHelmet();
        ItemStack boots = player.getInventory().getBoots();

        double submergedMining = activeLevel(helmet, "aqua_affinity") > 0 ? 0.8 : 0.0;
        replaceModifier(player.getAttribute(Attribute.PLAYER_SUBMERGED_MINING_SPEED), submergedMiningKey,
                submergedMining, AttributeModifier.Operation.ADD_NUMBER);

        int depth = activeLevel(boots, "depth_strider");
        replaceModifier(player.getAttribute(Attribute.GENERIC_WATER_MOVEMENT_EFFICIENCY), waterMovementKey,
                depth <= 0 ? 0.0 : Math.min(1.0, depth / 3.0), AttributeModifier.Operation.ADD_NUMBER);

        int swiftSneak = activeLevel(player.getInventory().getLeggings(), "swift_sneak");
        double sneakTarget = value("swift_sneak", swiftSneak, "sneak_ratio", 0.0);
        AttributeInstance sneaking = player.getAttribute(Attribute.PLAYER_SNEAKING_SPEED);
        double sneakAmount = swiftSneak <= 0 || sneaking == null ? 0.0 : sneakTarget - sneaking.getBaseValue();
        replaceModifier(sneaking, sneakingSpeedKey, sneakAmount, AttributeModifier.Operation.ADD_NUMBER);

        double movementPoints = sumArmorNumeric(player, "sugar_rush", "movement_speed")
                + getEquippedChimeraBonus(player, "movement_speed");
        if (shadeSpeedUntil.getOrDefault(player.getUniqueId(), 0L) > now) {
            movementPoints += 40.0;
        }
        replaceModifier(player.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED), movementSpeedKey,
                movementPoints / 100.0, AttributeModifier.Operation.ADD_SCALAR);

        double jumpHeight = sumArmorNumeric(player, "lightweight", "jump_height")
                + getEquippedChimeraBonus(player, "jump_height");
        AttributeInstance jump = player.getAttribute(Attribute.GENERIC_JUMP_STRENGTH);
        double jumpAmount = 0.0;
        if (jump != null && jumpHeight > 0.0) {
            double base = jump.getBaseValue();
            jumpAmount = Math.sqrt(base * base + 2.0 * 0.08 * jumpHeight) - base;
        }
        replaceModifier(jump, jumpStrengthKey, jumpAmount, AttributeModifier.Operation.ADD_NUMBER);

        int feather = activeLevel(boots, "feather_falling");
        replaceModifier(player.getAttribute(Attribute.GENERIC_SAFE_FALL_DISTANCE), safeFallKey,
                feather, AttributeModifier.Operation.ADD_NUMBER);
        replaceModifier(player.getAttribute(Attribute.GENERIC_FALL_DAMAGE_MULTIPLIER), fallMultiplierKey,
                -Math.min(0.50, feather * 0.05), AttributeModifier.Operation.ADD_SCALAR);
    }

    private void afterDamage(Player player, LivingEntity source, double damage, DamageCategory category,
                             boolean projectile, boolean direct, boolean healthAlreadyApplied) {
        if (player == null || damage <= 0.0 || EnchantDamageContext.isSecondaryDamage()) {
            return;
        }
        if (player.isDead() || player.getHealth() <= 0.0) {
            return;
        }
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        lastDamageAt.put(id, now);
        metallicArmor.put(id, 0.0);

        double maxHealth = maxHealth(player);
        double beforeHealth = healthAlreadyApplied
                ? Math.min(maxHealth, player.getHealth() + damage)
                : player.getHealth();
        double afterHealth = healthAlreadyApplied
                ? player.getHealth()
                : Math.max(0.0, player.getHealth() - damage);
        if (afterHealth <= 0.0) {
            return;
        }
        double beforeRatio = beforeHealth / Math.max(1.0, maxHealth);
        double afterRatio = afterHealth / Math.max(1.0, maxHealth);

        int counter = activeLevel(player.getInventory().getChestplate(), "counter_strike");
        if (counter > 0 && counterCooldownUntil.getOrDefault(id, 0L) <= now) {
            double armor = value("counter_strike", counter, "temporary_armor", 0.0);
            counterArmor.put(id, new TempArmor(armor, now + 7_000L));
            counterCooldownUntil.put(id, now + 15_000L);
        }
        int reserve = activeLevel(player.getInventory().getLeggings(), "emergency_reserve");
        if (reserve > 0 && beforeRatio >= 0.40 && afterRatio < 0.40
                && emergencyReserveCooldownUntil.getOrDefault(id, 0L) <= now) {
            ManaAccess.restoreMana(player, ManaAccess.getMaxMana(player)
                    * value("emergency_reserve", reserve, "mana_restore_ratio", 0.0));
            emergencyReserveCooldownUntil.put(id, now + 30_000L);
        }

        int adaptive = activeLevel(player.getInventory().getLeggings(), "adaptive_plating");
        if (adaptive > 0) {
            double cap = maxHealth(player) * 0.15;
            double pending = pendingAmount(player, HealKind.ADAPTIVE_PLATING);
            double amount = Math.min(Math.max(0.0, cap - pending),
                    damage * value("adaptive_plating", adaptive, "healing_ratio", 0.0));
            queueHeal(player, amount, 20, HealKind.ADAPTIVE_PLATING);
        }

        double noPainRatio = sumArmorCurve(player, "no_pain_no_gain", "damage_heal_ratio");
        AttributeManager attributes = AttributeManager.getInstance();
        if (noPainRatio > 0.0 && attributes != null) {
            double natural = getUnpenalizedNaturalRegen(player, attributes.getBaseHealthRegenPerSecond(player));
            queueHeal(player, natural * noPainRatio, 12, HealKind.NO_PAIN_NO_GAIN);
        }

        int hunter = activeLevel(player.getInventory().getHelmet(), "hunters_sense");
        if (hunter > 0 && source != null && !source.equals(player)) {
            int ticks = Math.max(1, (int) Math.round(value("hunters_sense", hunter, "reveal_seconds", 0.0) * 20.0));
            player.sendPotionEffectChange(source,
                    new PotionEffect(PotionEffectType.GLOWING, ticks, 0, false, false, false));
            hunterGlowUntil.computeIfAbsent(id, ignored -> new HashMap<>())
                    .put(source.getUniqueId(), now + ticks * 50L);
        }

        if (source != null && !source.equals(player)) {
            int thorns = highestArmorLevel(player, "thorns");
            int reflection = highestArmorLevel(player, "reflection");
            if (thorns > 0 && category == DamageCategory.PHYSICAL && !projectile
                    && ThreadLocalRandom.current().nextBoolean()) {
                retaliate(player, source, damage * value("thorns", thorns, "return_ratio", 0.0),
                        DamageCategory.MAGIC, "enchant_thorns");
            } else if (reflection > 0 && (projectile || category == DamageCategory.MAGIC)
                    && ThreadLocalRandom.current().nextBoolean()) {
                retaliate(player, source, damage * value("reflection", reflection, "return_ratio", 0.0),
                        category, "enchant_reflection");
            }
        }
    }

    private void retaliate(Player defender, LivingEntity attacker, double damage, DamageCategory category, String reason) {
        if (damage <= 0.0 || attacker.isDead() || !attacker.isValid()) {
            return;
        }
        DamageService damageService = DamageService.getInstance();
        EnchantDamageContext.runAsSecondaryDamage(() -> {
            if (damageService != null) {
                damageService.applyDamage(new DamagePacket(
                        defender,
                        attacker,
                        damage,
                        category,
                        Set.of(),
                        DamageSourceKind.CUSTOM_ITEM,
                        reason
                ));
            } else {
                attacker.damage(damage, defender);
            }
        });
    }

    private void queueHeal(Player player, double total, int ticks, HealKind kind) {
        if (player == null || total <= 0.0 || ticks <= 0) {
            return;
        }
        pendingHeals.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayList<>())
                .add(new PendingHeal(total, ticks, kind));
    }

    private double pendingAmount(Player player, HealKind kind) {
        return pendingHeals.getOrDefault(player.getUniqueId(), List.of()).stream()
                .filter(heal -> heal.kind == kind)
                .mapToDouble(heal -> heal.remaining)
                .sum();
    }

    private double sumArmorNumeric(Player player, String enchantId, String key) {
        double total = 0.0;
        for (ItemStack armor : armor(player)) {
            int level = activeLevel(armor, enchantId);
            if (level > 0) {
                total += value(enchantId, level, key, 0.0) * getLegionPanelMultiplier(player, armor);
            }
        }
        return total;
    }

    private double sumArmorCurve(Player player, String enchantId, String key) {
        double total = 0.0;
        for (ItemStack armor : armor(player)) {
            int level = activeLevel(armor, enchantId);
            total += value(enchantId, level, key, 0.0);
        }
        return total;
    }

    private int countArmorWith(Player player, String enchantId) {
        int count = 0;
        for (ItemStack armor : armor(player)) {
            if (activeLevel(armor, enchantId) > 0) {
                count++;
            }
        }
        return count;
    }

    private int highestArmorLevel(Player player, String enchantId) {
        int level = 0;
        for (ItemStack armor : armor(player)) {
            level = Math.max(level, activeLevel(armor, enchantId));
        }
        return level;
    }

    private double getRecentManaSpent(Player player) {
        if (player == null) {
            return 0.0;
        }
        Deque<ManaSpend> spends = manaSpends.get(player.getUniqueId());
        if (spends == null) {
            return 0.0;
        }
        pruneManaSpends(spends, System.currentTimeMillis());
        return spends.stream().mapToDouble(ManaSpend::amount).sum();
    }

    private void pruneManaSpends(Deque<ManaSpend> spends, long now) {
        while (!spends.isEmpty() && now - spends.peekFirst().atMs() > MANA_WINDOW_MS) {
            spends.removeFirst();
        }
    }

    private int activeLevel(ItemStack item, String id) {
        EnchantManager manager = EnchantManager.getInstance();
        return manager == null ? 0 : manager.getActiveEnchantLevel(item, id);
    }

    private double value(String enchantId, int level, String key, double fallback) {
        if (level <= 0) {
            return fallback;
        }
        EnchantRegistry registry = EnchantRegistry.getInstance();
        EnchantDefinition definition = registry == null ? null : registry.get(enchantId).orElse(null);
        ValueCurve curve = definition == null ? null : definition.numericBonuses().get(key);
        return curve == null ? fallback : curve.valueAt(level);
    }

    private List<ItemStack> armor(Player player) {
        if (player == null) {
            return List.of();
        }
        List<ItemStack> result = new ArrayList<>(4);
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (item != null && !item.getType().isAir()) {
                result.add(item);
            }
        }
        return result;
    }

    private List<NamespacedKey> panelStatKeys(PDCManager pdc) {
        return List.of(
                pdc.KEY_BASE_DAMAGE,
                pdc.KEY_BASE_MULTIPLIER,
                pdc.KEY_CRIT_CHANCE,
                pdc.KEY_CRIT_DAMAGE,
                pdc.KEY_BRUTALITY,
                pdc.KEY_LIFESTEAL,
                pdc.KEY_ARMOR_PEN,
                pdc.KEY_BASE_ARMOR,
                pdc.KEY_ATTACK_SPEED_BONUS,
                pdc.KEY_SHIELD_BLOCK_THRESHOLD,
                pdc.KEY_SHIELD_EFFECTIVE_BLOCK,
                pdc.KEY_SHIELD_COOLDOWN_SECONDS,
                pdc.KEY_ATTR_TOUGHNESS,
                pdc.KEY_ATTR_AGILITY,
                pdc.KEY_ATTR_INTELLIGENCE,
                pdc.KEY_ATTR_WILLPOWER,
                pdc.KEY_ATTR_LUCK,
                pdc.KEY_TOOL_FORTUNE,
                pdc.KEY_COLLECTION_FORTUNE,
                pdc.KEY_FORAGING_FORTUNE,
                pdc.KEY_FARMING_FORTUNE,
                pdc.KEY_EXCAVATION_FORTUNE,
                pdc.KEY_MINING_FORTUNE,
                pdc.KEY_TOOL_SWEEP,
                pdc.KEY_COLLECTION_SWEEP,
                pdc.KEY_FORAGING_SWEEP,
                pdc.KEY_FARMING_SWEEP,
                pdc.KEY_EXCAVATION_SWEEP,
                pdc.KEY_TOOL_SPREAD,
                pdc.KEY_MINING_SPREAD,
                pdc.KEY_TOOL_MINING_SPEED,
                pdc.KEY_BREAKING_POWER,
                pdc.KEY_PURITY,
                pdc.KEY_MINING_PURITY,
                pdc.KEY_FISHING_SPEED,
                pdc.KEY_SEA_CREATURE_CHANCE,
                pdc.KEY_TREASURE_CHANCE,
                pdc.KEY_BOUNTY,
                pdc.KEY_OVERBLOOM
        );
    }

    private double maxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        return attribute == null ? 20.0 : Math.max(1.0, attribute.getValue());
    }

    private double healthRatio(Player player) {
        return player == null ? 0.0 : player.getHealth() / Math.max(1.0, maxHealth(player));
    }

    private void syncVanillaBootEnchants(Player player) {
        ItemStack boots = player.getInventory().getBoots();
        if (boots == null || boots.getType().isAir()) {
            return;
        }
        syncVanillaEnchant(boots, activeLevel(boots, "frost_walker"), Enchantment.FROST_WALKER,
                syncedFrostWalkerKey);
        syncVanillaEnchant(boots, activeLevel(boots, "soul_speed"), Enchantment.SOUL_SPEED,
                syncedSoulSpeedKey);
        ItemMeta meta = boots.getItemMeta();
        if (meta != null
                && !meta.getPersistentDataContainer().has(syncedFrostWalkerKey, PersistentDataType.BYTE)
                && !meta.getPersistentDataContainer().has(syncedSoulSpeedKey, PersistentDataType.BYTE)
                && meta.getPersistentDataContainer().has(syncedHideEnchantsKey, PersistentDataType.BYTE)) {
            meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS);
            meta.getPersistentDataContainer().remove(syncedHideEnchantsKey);
            boots.setItemMeta(meta);
        }
    }

    private void syncVanillaEnchant(ItemStack item, int customLevel, Enchantment enchantment, NamespacedKey markerKey) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        boolean managed = meta.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
        boolean changed = false;
        if (customLevel > 0) {
            if (meta.getEnchantLevel(enchantment) != customLevel) {
                meta.addEnchant(enchantment, customLevel, true);
                changed = true;
            }
            if (!managed) {
                meta.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
                if (!meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS)) {
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                    meta.getPersistentDataContainer().set(syncedHideEnchantsKey, PersistentDataType.BYTE, (byte) 1);
                }
                changed = true;
            }
        } else if (managed) {
            meta.removeEnchant(enchantment);
            meta.getPersistentDataContainer().remove(markerKey);
            changed = true;
        }
        if (changed) {
            item.setItemMeta(meta);
        }
    }

    private boolean isInLava(Player player) {
        return player.getLocation().getBlock().getType() == Material.LAVA
                || player.getEyeLocation().getBlock().getType() == Material.LAVA;
    }

    private double horizontalDistanceSquared(Location left, Location right) {
        double x = left.getX() - right.getX();
        double z = left.getZ() - right.getZ();
        return x * x + z * z;
    }

    private void replaceModifier(AttributeInstance attribute, NamespacedKey key, double amount,
                                 AttributeModifier.Operation operation) {
        if (attribute == null) {
            return;
        }
        for (AttributeModifier modifier : new ArrayList<>(attribute.getModifiers())) {
            if (modifier.getKey().equals(key)) {
                attribute.removeModifier(modifier);
            }
        }
        if (Math.abs(amount) > 0.0001) {
            attribute.addModifier(new AttributeModifier(key, amount, operation));
        }
    }

    private void clearAttributeModifiers(Player player) {
        replaceModifier(player.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED), movementSpeedKey, 0.0,
                AttributeModifier.Operation.ADD_SCALAR);
        replaceModifier(player.getAttribute(Attribute.PLAYER_SNEAKING_SPEED), sneakingSpeedKey, 0.0,
                AttributeModifier.Operation.ADD_NUMBER);
        replaceModifier(player.getAttribute(Attribute.PLAYER_SUBMERGED_MINING_SPEED), submergedMiningKey, 0.0,
                AttributeModifier.Operation.ADD_NUMBER);
        replaceModifier(player.getAttribute(Attribute.GENERIC_WATER_MOVEMENT_EFFICIENCY), waterMovementKey, 0.0,
                AttributeModifier.Operation.ADD_NUMBER);
        replaceModifier(player.getAttribute(Attribute.GENERIC_JUMP_STRENGTH), jumpStrengthKey, 0.0,
                AttributeModifier.Operation.ADD_NUMBER);
        replaceModifier(player.getAttribute(Attribute.GENERIC_SAFE_FALL_DISTANCE), safeFallKey, 0.0,
                AttributeModifier.Operation.ADD_NUMBER);
        replaceModifier(player.getAttribute(Attribute.GENERIC_FALL_DAMAGE_MULTIPLIER), fallMultiplierKey, 0.0,
                AttributeModifier.Operation.ADD_SCALAR);
    }

    private LivingEntity resolveSource(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            if (byEntity.getDamager() instanceof LivingEntity living) {
                return living;
            }
            if (byEntity.getDamager() instanceof Projectile projectile) {
                ProjectileSource shooter = projectile.getShooter();
                if (shooter instanceof LivingEntity living) {
                    return living;
                }
            }
        }
        Entity causing = event.getDamageSource().getCausingEntity();
        return causing instanceof LivingEntity living ? living : null;
    }

    private DamageCategory category(EntityDamageEvent event) {
        AttributeManager attributes = AttributeManager.getInstance();
        return attributes != null && attributes.isMagicDamageCause(event.getCause())
                ? DamageCategory.MAGIC
                : DamageCategory.PHYSICAL;
    }

    private boolean isProjectile(EntityDamageEvent event) {
        return event instanceof EntityDamageByEntityEvent byEntity
                && byEntity.getDamager() instanceof Projectile;
    }

    private void clearHunterGlow(Player player) {
        Map<UUID, Long> glows = hunterGlowUntil.remove(player.getUniqueId());
        if (glows == null) {
            return;
        }
        for (UUID targetId : glows.keySet()) {
            Entity entity = Bukkit.getEntity(targetId);
            if (entity instanceof LivingEntity living) {
                player.sendPotionEffectChangeRemove(living, PotionEffectType.GLOWING);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onManaAbility(ManaAbilityActivateEvent event) {
        recordManaSpent(event.getPlayer(), event.getManaUsed());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageMonitor(EntityDamageEvent event) {
        if (DamageService.isInternalDamageActive() || EnchantDamageContext.isSecondaryDamage()
                || !(event.getEntity() instanceof Player player)) {
            return;
        }
        DamageService damageService = DamageService.getInstance();
        com.servercore.combat.damage.DamageResult result =
                damageService == null ? null : damageService.getFinalizedResult(event);
        double actualDamage = result == null ? event.getFinalDamage() : result.actualDamage();
        if (actualDamage <= 0.0) {
            return;
        }
        afterDamage(player, resolveSource(event), actualDamage, category(event),
                isProjectile(event), event instanceof EntityDamageByEntityEvent, false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAirChange(EntityAirChangeEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getAmount() >= player.getRemainingAir()) {
            return;
        }
        int level = activeLevel(player.getInventory().getHelmet(), "respiration");
        if (level > 0 && ThreadLocalRandom.current().nextDouble() < level / (level + 1.0)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onRegainHealth(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player player) {
            event.setAmount(modifyHealingAmount(player, event.getAmount()));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        lastLocations.put(event.getPlayer().getUniqueId(), event.getPlayer().getLocation().clone());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        clearAttributeModifiers(player);
        clearHunterGlow(player);
        lastDamageAt.remove(id);
        lastManaSpendAt.remove(id);
        manaSpends.remove(id);
        pendingHeals.remove(id);
        counterCooldownUntil.remove(id);
        counterArmor.remove(id);
        metallicArmor.remove(id);
        arcaneBufferCooldownUntil.remove(id);
        emergencyReserveCooldownUntil.remove(id);
        mindFortressCooldownUntil.remove(id);
        nimbleUntil.remove(id);
        lastLocations.remove(id);
        movingSince.remove(id);
        shadeArmed.remove(id);
        shadeCooldownUntil.remove(id);
        shadeSpeedUntil.remove(id);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        clearHunterGlow(player);
        manaSpends.remove(id);
        pendingHeals.remove(id);
        counterArmor.remove(id);
        metallicArmor.remove(id);
        nimbleUntil.remove(id);
        movingSince.remove(id);
        shadeArmed.remove(id);
        shadeSpeedUntil.remove(id);
    }

    private record ManaSpend(long atMs, double amount) {
    }

    public record IncomingDamagePlan(double damage, Runnable commit) {
        static IncomingDamagePlan noop(double damage) {
            return new IncomingDamagePlan(damage, () -> {
            });
        }
    }

    private record TempArmor(double amount, long expiresAtMs) {
    }

    private enum HealKind {
        MANA_REBOUND,
        NO_PAIN_NO_GAIN,
        ADAPTIVE_PLATING
    }

    private static final class PendingHeal {
        private double remaining;
        private int ticksRemaining;
        private final HealKind kind;

        private PendingHeal(double remaining, int ticksRemaining, HealKind kind) {
            this.remaining = remaining;
            this.ticksRemaining = ticksRemaining;
            this.kind = kind;
        }
    }

    private Runnable combine(List<Runnable> actions) {
        List<Runnable> snapshot = List.copyOf(actions);
        return () -> snapshot.forEach(Runnable::run);
    }
}
