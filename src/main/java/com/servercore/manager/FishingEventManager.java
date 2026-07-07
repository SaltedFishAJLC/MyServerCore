package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import com.servercore.fishing.FishingConditions;
import com.servercore.fishing.FishingContext;
import dev.aurelium.auraskills.api.skill.Skills;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Test-version multiplayer fishing events. Events are triggered only after the
 * base sea creature or treasure roll has already succeeded.
 */
public class FishingEventManager implements Listener {

    private static FishingEventManager instance;

    private final ServerCorePlugin plugin;
    private final File eventsFile;
    private final Map<UUID, ActiveFishingEvent> activeEvents = new HashMap<>();
    private final Map<String, CooldownState> cooldowns = new HashMap<>();

    private volatile int maxActiveEvents = 1;
    private volatile boolean cleanupOnDisable = true;
    private volatile boolean debug = false;
    private volatile Map<String, FishingEventDefinition> definitions = Map.of();

    public FishingEventManager(ServerCorePlugin plugin) {
        this.plugin = plugin;
        this.eventsFile = new File(plugin.getDataFolder(), "fishing_events.yml");
        instance = this;
        reload();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public static FishingEventManager getInstance() {
        return instance;
    }

    public int reload() {
        ensureResource();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(eventsFile);
        ConfigurationSection settings = config.getConfigurationSection("settings");
        this.maxActiveEvents = settings == null ? 1 : Math.max(0, settings.getInt("max_active_events", 1));
        this.cleanupOnDisable = settings == null || settings.getBoolean("cleanup_on_disable", true);
        this.debug = settings != null && settings.getBoolean("debug", false);
        this.definitions = readDefinitions(config.getConfigurationSection("events"));
        plugin.getLogger().info("Loaded fishing multiplayer events: " + definitions.size() + ".");
        return definitions.size();
    }

    public void stop() {
        List<ActiveFishingEvent> snapshot = new ArrayList<>(activeEvents.values());
        for (ActiveFishingEvent event : snapshot) {
            finishEvent(event, false, "Plugin disabled.", cleanupOnDisable);
        }
        activeEvents.clear();
    }

    public FishingEventStartResult tryStartFromSeaCreatureRoll(FishingContext context, String rolledSeaCreatureId) {
        return tryStart(TriggerType.SEA_CREATURE_ROLL, context, rolledSeaCreatureId);
    }

    public FishingEventStartResult tryStartFromTreasureRoll(FishingContext context, String rolledTreasureId) {
        return tryStart(TriggerType.TREASURE_ROLL, context, rolledTreasureId);
    }

    private FishingEventStartResult tryStart(TriggerType triggerType, FishingContext context, String sourceId) {
        if (context == null || context.player() == null || context.world() == null) {
            return FishingEventStartResult.notStarted("missing-context");
        }
        if (maxActiveEvents <= 0 || activeEvents.size() >= maxActiveEvents) {
            return FishingEventStartResult.notStarted("active-limit");
        }

        String normalizedSource = normalizeId(sourceId);
        for (FishingEventDefinition definition : definitions.values()) {
            if (!definition.enabled()
                    || definition.triggerType() != triggerType
                    || !definition.matchesSource(normalizedSource)
                    || !definition.conditions().matches(context)) {
                continue;
            }
            if (isCoolingDown(definition, context)) {
                continue;
            }
            if (!hasCatalyst(context.player(), definition.catalystItem())) {
                continue;
            }
            if (ThreadLocalRandom.current().nextDouble() > definition.chance()) {
                return FishingEventStartResult.notStarted("chance-missed");
            }
            if (!consumeCatalyst(context.player(), definition.catalystItem())) {
                return FishingEventStartResult.notStarted("missing-catalyst");
            }
            ActiveFishingEvent event = startEvent(definition, context, normalizedSource);
            return event == null
                    ? FishingEventStartResult.notStarted("start-failed")
                    : FishingEventStartResult.started(event.definition().id());
        }

        return FishingEventStartResult.notStarted("no-matching-event");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEventMobDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        ActiveFishingEvent activeEvent = activeEventForEntity(target);
        if (activeEvent == null) {
            return;
        }
        Player attacker = attackingPlayer(event.getDamager());
        if (attacker == null) {
            return;
        }
        double weight = contributionWeight(target);
        activeEvent.addContribution(attacker, event.getFinalDamage()
                * activeEvent.definition().damageContributionMultiplier() * weight);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEventMobDeath(EntityDeathEvent event) {
        ActiveFishingEvent activeEvent = activeEventForEntity(event.getEntity());
        if (activeEvent == null) {
            return;
        }
        activeEvent.spawnedMobs().remove(event.getEntity().getUniqueId());
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            activeEvent.addContribution(killer, activeEvent.definition().killContribution());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEventFishingCatch(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item)) {
            return;
        }
        ActiveFishingEvent activeEvent = activeEventAt(event.getHook().getLocation());
        if (activeEvent == null) {
            return;
        }
        activeEvent.addContribution(event.getPlayer(), activeEvent.definition().fishingCatchContribution());
    }

    private ActiveFishingEvent startEvent(FishingEventDefinition definition, FishingContext context, String sourceId) {
        UUID instanceId = UUID.randomUUID();
        Location center = context.hookLocation().clone();
        ActiveFishingEvent event = new ActiveFishingEvent(
                instanceId,
                definition,
                center,
                context.player().getUniqueId(),
                context.player().getName(),
                getFishingLevel(context.player()),
                sourceId
        );
        activeEvents.put(instanceId, event);
        cooldowns.put(cooldownKey(definition, context.world()), new CooldownState(context.gameDay(), System.currentTimeMillis()));

        broadcast(formatMessage(definition.startMessage(), event));
        center.getWorld().playSound(center, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.9f, 0.8f);
        center.getWorld().spawnParticle(Particle.SPLASH, center, 120, 2.5, 0.7, 2.5, 0.12);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tickEvent(instanceId), 20L, 20L);
        event.setTask(task);
        spawnDueWaves(event);
        return event;
    }

    private void tickEvent(UUID instanceId) {
        ActiveFishingEvent event = activeEvents.get(instanceId);
        if (event == null) {
            return;
        }
        event.elapsedTicks(event.elapsedTicks() + 20);
        cleanupDeadMobs(event);
        spawnDueWaves(event);
        triggerMilestones(event);

        if (event.definition().progressTarget() > 0.0 && event.progress() >= event.definition().progressTarget()) {
            finishEvent(event, true, "progress-complete", true);
            return;
        }
        if (!hasNearbyPlayers(event)) {
            event.emptySeconds(event.emptySeconds() + 1);
            if (event.emptySeconds() >= event.definition().emptyTimeoutSeconds()) {
                finishEvent(event, false, "no-nearby-players", true);
                return;
            }
        } else {
            event.emptySeconds(0);
        }
        if (event.elapsedTicks() >= event.definition().durationTicks()) {
            finishEvent(event, event.definition().completeOnDuration(), "duration-ended", true);
        }
    }

    private void spawnDueWaves(ActiveFishingEvent event) {
        for (EventWave wave : event.definition().waves()) {
            if (wave.atTicks() > event.elapsedTicks() || !event.completedWaves().add(wave.index())) {
                continue;
            }
            spawnMobs(event, wave.mobs());
        }
    }

    private void triggerMilestones(ActiveFishingEvent event) {
        if (event.definition().milestones().isEmpty()) {
            return;
        }
        for (EventMilestone milestone : event.definition().milestones()) {
            if (event.progress() < milestone.progress() || !event.completedMilestones().add(milestone.index())) {
                continue;
            }
            spawnMobs(event, milestone.mobs());
            grantRewards(event, milestone.rewards(), false);
            broadcast(formatMessage(milestone.message(), event));
        }
    }

    private void spawnMobs(ActiveFishingEvent event, List<EventMob> mobs) {
        if (mobs.isEmpty()) {
            return;
        }
        MobSpawnManager mobSpawnManager = MobSpawnManager.getInstance();
        if (mobSpawnManager == null) {
            plugin.getLogger().warning("Fishing event mob spawn skipped because MobSpawnManager is unavailable.");
            return;
        }

        for (EventMob mob : mobs) {
            for (int index = 0; index < mob.amount(); index++) {
                Location spawnLocation = randomSpawnLocation(event.center(), event.definition().radius());
                int powerLevel = Math.max(1, event.triggerFishingLevel() + mob.levelOffset());
                double levelScale = Math.max(1.0, powerLevel);
                MobSpawnManager.FishingSeaCreatureSpec spec = new MobSpawnManager.FishingSeaCreatureSpec(
                        "fishing_event_" + event.definition().id() + "_" + mob.id(),
                        mob.name(),
                        powerLevel,
                        mob.baseHealth() + levelScale * mob.mod() * 4.0,
                        3.0 + levelScale * mob.mod() * 0.25,
                        mob.mod(),
                        "sea,water,fishing,event," + event.definition().id()
                );
                LivingEntity entity = mobSpawnManager.spawnFishingSeaCreature(
                        () -> spawnLivingEntity(spawnLocation, mob.type()),
                        spec
                );
                if (entity == null) {
                    continue;
                }
                markEventMob(entity, event, mob);
                event.spawnedMobs().add(entity.getUniqueId());
                Player owner = Bukkit.getPlayer(event.ownerId());
                if (owner != null && entity instanceof Mob bukkitMob) {
                    bukkitMob.setTarget(owner);
                }
            }
        }
    }

    private LivingEntity spawnLivingEntity(Location location, EntityType type) {
        Entity spawned = location.getWorld().spawnEntity(location, type);
        return spawned instanceof LivingEntity livingEntity ? livingEntity : null;
    }

    private void markEventMob(LivingEntity entity, ActiveFishingEvent event, EventMob mob) {
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return;
        }
        PersistentDataContainer container = entity.getPersistentDataContainer();
        container.set(pdc.KEY_FISHING_EVENT_ID, PersistentDataType.STRING, event.definition().id());
        container.set(pdc.KEY_FISHING_EVENT_INSTANCE, PersistentDataType.STRING, event.instanceId().toString());
        container.set(pdc.KEY_FISHING_EVENT_CONTRIBUTION_WEIGHT, PersistentDataType.DOUBLE, mob.contributionWeight());
        entity.addScoreboardTag("servercore_fishing_event");
    }

    private Location randomSpawnLocation(Location center, double radius) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble(Math.PI * 2.0);
        double distance = random.nextDouble(Math.max(4.0, radius * 0.35), Math.max(5.0, radius));
        return center.clone().add(Math.cos(angle) * distance, 0.2, Math.sin(angle) * distance);
    }

    private void cleanupDeadMobs(ActiveFishingEvent event) {
        event.spawnedMobs().removeIf(mobId -> {
            Entity entity = Bukkit.getEntity(mobId);
            return entity == null || entity.isDead() || !entity.isValid();
        });
    }

    private void finishEvent(ActiveFishingEvent event, boolean success, String reason, boolean cleanupMobs) {
        activeEvents.remove(event.instanceId());
        if (event.task() != null) {
            event.task().cancel();
            event.setTask(null);
        }

        grantRewards(event, success ? event.definition().completionRewards() : event.definition().participationRewards(), true);
        if (cleanupMobs) {
            for (UUID mobId : event.spawnedMobs()) {
                Entity entity = Bukkit.getEntity(mobId);
                if (entity != null) {
                    entity.remove();
                }
            }
            event.spawnedMobs().clear();
        }

        broadcast(formatMessage(success ? event.definition().completeMessage() : event.definition().failMessage(), event)
                + " (" + reason + ")");
    }

    private void grantRewards(ActiveFishingEvent event, List<EventReward> rewards, boolean finalReward) {
        if (rewards.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Double> entry : event.contributions().entrySet()) {
            if (finalReward && entry.getValue() < event.definition().minContribution()) {
                continue;
            }
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            EventReward reward = rollReward(rewards);
            if (reward == null) {
                continue;
            }
            giveReward(player, reward);
        }
    }

    private EventReward rollReward(List<EventReward> rewards) {
        double totalWeight = rewards.stream().mapToDouble(EventReward::weight).sum();
        if (totalWeight <= 0.0) {
            return null;
        }
        double roll = ThreadLocalRandom.current().nextDouble(totalWeight);
        for (EventReward reward : rewards) {
            roll -= reward.weight();
            if (roll < 0.0) {
                return reward;
            }
        }
        return rewards.get(0);
    }

    private void giveReward(Player player, EventReward reward) {
        if (reward.xp() > 0.0) {
            AuraSkillsBridge bridge = AuraSkillsBridge.getInstance();
            if (bridge != null) {
                bridge.addSkillXp(player, Skills.FISHING, reward.xp());
            }
        }
        ItemStack item = null;
        CustomItemRegistry registry = CustomItemRegistry.getInstance();
        if (!reward.itemId().isBlank() && registry != null) {
            item = registry.createItem(reward.itemId(), reward.amount());
        }
        if (item == null && reward.fallbackMaterial() != Material.AIR) {
            item = new ItemStack(reward.fallbackMaterial(), reward.amount());
        }
        if (item == null) {
            return;
        }
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        for (ItemStack leftover : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        player.sendMessage(Component.text("Fishing event reward: " + reward.displayId() + " x" + reward.amount()));
    }

    private boolean hasNearbyPlayers(ActiveFishingEvent event) {
        World world = event.center().getWorld();
        if (world == null) {
            return false;
        }
        double radiusSquared = event.definition().joinRadius() * event.definition().joinRadius();
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(event.center()) <= radiusSquared) {
                return true;
            }
        }
        return false;
    }

    private ActiveFishingEvent activeEventAt(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        ActiveFishingEvent best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ActiveFishingEvent event : activeEvents.values()) {
            if (!location.getWorld().equals(event.center().getWorld())) {
                continue;
            }
            double distance = location.distanceSquared(event.center());
            double radius = event.definition().joinRadius();
            if (distance <= radius * radius && distance < bestDistance) {
                best = event;
                bestDistance = distance;
            }
        }
        return best;
    }

    private ActiveFishingEvent activeEventForEntity(LivingEntity entity) {
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return null;
        }
        String instanceId = entity.getPersistentDataContainer().get(pdc.KEY_FISHING_EVENT_INSTANCE, PersistentDataType.STRING);
        if (instanceId == null || instanceId.isBlank()) {
            return null;
        }
        try {
            return activeEvents.get(UUID.fromString(instanceId));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private double contributionWeight(LivingEntity entity) {
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return 1.0;
        }
        return Math.max(0.0, entity.getPersistentDataContainer().getOrDefault(
                pdc.KEY_FISHING_EVENT_CONTRIBUTION_WEIGHT,
                PersistentDataType.DOUBLE,
                1.0
        ));
    }

    private Player attackingPlayer(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            return source instanceof Player player ? player : null;
        }
        return null;
    }

    private boolean isCoolingDown(FishingEventDefinition definition, FishingContext context) {
        CooldownState state = cooldowns.get(cooldownKey(definition, context.world()));
        if (state == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (definition.cooldownSameGameDay() && state.gameDay() == context.gameDay()) {
            return true;
        }
        return definition.cooldownRealMillis() > 0L && now < state.realMillis() + definition.cooldownRealMillis();
    }

    private String cooldownKey(FishingEventDefinition definition, World world) {
        return definition.id() + ":" + (world == null ? "unknown" : world.getUID());
    }

    private boolean hasCatalyst(Player player, String catalystItem) {
        if (catalystItem == null || catalystItem.isBlank()) {
            return true;
        }
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (isCustomItem(item, catalystItem)) {
                return true;
            }
        }
        return false;
    }

    private boolean consumeCatalyst(Player player, String catalystItem) {
        if (catalystItem == null || catalystItem.isBlank()) {
            return true;
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack[] storage = inventory.getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            ItemStack item = storage[slot];
            if (!isCustomItem(item, catalystItem)) {
                continue;
            }
            ItemStack replacement = item.clone();
            replacement.setAmount(item.getAmount() - 1);
            inventory.setItem(slot, replacement.getAmount() <= 0 ? null : replacement);
            return true;
        }
        return false;
    }

    private boolean isCustomItem(ItemStack item, String itemId) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        CustomItemRegistry registry = CustomItemRegistry.getInstance();
        String actual = registry == null ? null : registry.getItemId(item);
        if (actual == null || actual.isBlank()) {
            PDCManager pdc = PDCManager.getInstance();
            ItemMeta meta = item.getItemMeta();
            if (pdc != null && meta != null) {
                actual = meta.getPersistentDataContainer().get(pdc.KEY_ITEM_ID, PersistentDataType.STRING);
            }
        }
        return normalizeId(actual).equals(normalizeId(itemId));
    }

    private int getFishingLevel(Player player) {
        AuraSkillsBridge bridge = AuraSkillsBridge.getInstance();
        return bridge == null ? 0 : bridge.getSkillLevel(player, Skills.FISHING);
    }

    private void broadcast(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        Bukkit.broadcast(Component.text(message));
    }

    private String formatMessage(String message, ActiveFishingEvent event) {
        if (message == null || message.isBlank()) {
            return event.definition().displayName();
        }
        Location center = event.center();
        return message
                .replace("{event}", event.definition().displayName())
                .replace("{player}", event.ownerName())
                .replace("{source}", event.sourceId())
                .replace("{progress}", formatNumber(event.progress()))
                .replace("{target}", formatNumber(event.definition().progressTarget()))
                .replace("{x}", String.valueOf(center.getBlockX()))
                .replace("{y}", String.valueOf(center.getBlockY()))
                .replace("{z}", String.valueOf(center.getBlockZ()));
    }

    private Map<String, FishingEventDefinition> readDefinitions(ConfigurationSection section) {
        Map<String, FishingEventDefinition> loaded = new LinkedHashMap<>();
        if (section == null) {
            return loaded;
        }
        for (String rawId : section.getKeys(false)) {
            ConfigurationSection event = section.getConfigurationSection(rawId);
            if (event == null) {
                continue;
            }
            FishingEventDefinition definition = readDefinition(rawId, event);
            if (definition != null) {
                loaded.put(definition.id(), definition);
            }
        }
        return Map.copyOf(loaded);
    }

    private FishingEventDefinition readDefinition(String rawId, ConfigurationSection section) {
        String id = normalizeId(rawId);
        ConfigurationSection trigger = section.getConfigurationSection("trigger");
        ConfigurationSection cooldown = trigger == null
                ? section.getConfigurationSection("cooldown")
                : trigger.getConfigurationSection("cooldown");
        ConfigurationSection conditions = trigger == null
                ? section.getConfigurationSection("conditions")
                : trigger.getConfigurationSection("conditions");
        ConfigurationSection rewards = section.getConfigurationSection("rewards");
        TriggerType triggerType = parseTriggerType(trigger == null
                ? section.getString("trigger", "SEA_CREATURE_ROLL")
                : trigger.getString("type", "SEA_CREATURE_ROLL"));

        List<EventMilestone> milestones = readMilestones(section.getConfigurationSection("milestones"));
        milestones.sort(Comparator.comparingDouble(EventMilestone::progress));
        return new FishingEventDefinition(
                id,
                section.getString("name", rawId),
                section.getBoolean("enabled", true),
                triggerType,
                clampChance(trigger == null ? section.getDouble("chance", 0.0) : trigger.getDouble("chance", 0.0)),
                normalizeId(trigger == null ? section.getString("catalyst_item", "") : trigger.getString("catalyst_item", "")),
                readStringSet(trigger, "source_ids"),
                cooldown == null || cooldown.getBoolean("same_world_game_day", true),
                Math.max(0L, Math.round((cooldown == null ? 0.0 : cooldown.getDouble("real_seconds", 0.0)) * 1000.0)),
                FishingConditions.fromConfig(conditions, ""),
                Math.max(20, section.getInt("duration_seconds", 300) * 20),
                Math.max(4.0, section.getDouble("radius", 32.0)),
                Math.max(4.0, section.getDouble("join_radius", 48.0)),
                Math.max(0.0, section.getDouble("min_contribution", 0.0)),
                Math.max(0.0, section.getDouble("progress_target", 0.0)),
                section.getBoolean("complete_on_duration", true),
                Math.max(10, section.getInt("empty_timeout_seconds", 90)),
                Math.max(0.0, section.getDouble("contribution.damage_multiplier", 1.0)),
                Math.max(0.0, section.getDouble("contribution.kill", 80.0)),
                Math.max(0.0, section.getDouble("contribution.valid_fishing_catch", 35.0)),
                readWaves(section.getConfigurationSection("waves")),
                List.copyOf(milestones),
                readRewards(rewards == null ? List.of() : rewards.getMapList("completion")),
                readRewards(rewards == null ? List.of() : rewards.getMapList("participation")),
                section.getString("messages.start", "[Fishing] {event} started near {x}, {z}."),
                section.getString("messages.complete", "[Fishing] {event} completed."),
                section.getString("messages.fail", "[Fishing] {event} ended.")
        );
    }

    private List<EventWave> readWaves(ConfigurationSection section) {
        List<EventWave> waves = new ArrayList<>();
        if (section == null) {
            return waves;
        }
        int index = 0;
        for (String waveId : section.getKeys(false)) {
            ConfigurationSection wave = section.getConfigurationSection(waveId);
            if (wave == null) {
                continue;
            }
            waves.add(new EventWave(
                    index++,
                    Math.max(0, wave.getInt("at_seconds", 0) * 20),
                    readMobs(wave.getConfigurationSection("mobs"))
            ));
        }
        waves.sort(Comparator.comparingInt(EventWave::atTicks));
        return List.copyOf(waves);
    }

    private List<EventMilestone> readMilestones(ConfigurationSection section) {
        List<EventMilestone> milestones = new ArrayList<>();
        if (section == null) {
            return milestones;
        }
        int index = 0;
        for (String milestoneId : section.getKeys(false)) {
            ConfigurationSection milestone = section.getConfigurationSection(milestoneId);
            if (milestone == null) {
                continue;
            }
            milestones.add(new EventMilestone(
                    index++,
                    Math.max(0.0, milestone.getDouble("progress", 0.0)),
                    readMobs(milestone.getConfigurationSection("spawn_mobs")),
                    readRewards(milestone.getMapList("rewards")),
                    milestone.getString("message", "[Fishing] {event} progress: {progress}/{target}.")
            ));
        }
        return milestones;
    }

    private List<EventMob> readMobs(ConfigurationSection section) {
        List<EventMob> mobs = new ArrayList<>();
        if (section == null) {
            return mobs;
        }
        for (String rawId : section.getKeys(false)) {
            ConfigurationSection mob = section.getConfigurationSection(rawId);
            if (mob == null) {
                continue;
            }
            EntityType fallback = inferEntityType(rawId);
            EntityType type = parseEntityType(mob.getString("entity_type", fallback.name()), fallback);
            mobs.add(new EventMob(
                    normalizeId(rawId),
                    type,
                    mob.getString("name", rawId),
                    Math.max(1, mob.getInt("amount", 1)),
                    mob.getInt("level_offset", 0),
                    Math.max(1.0, mob.getDouble("base_health", 40.0)),
                    Math.max(0.1, mob.getDouble("mod", 1.0)),
                    Math.max(0.0, mob.getDouble("contribution_weight", 1.0))
            ));
        }
        return List.copyOf(mobs);
    }

    private List<EventReward> readRewards(List<Map<?, ?>> rewardMaps) {
        List<EventReward> rewards = new ArrayList<>();
        for (Map<?, ?> raw : rewardMaps) {
            String itemId = normalizeId(String.valueOf(mapValue(raw, "custom_item", mapValue(raw, "item", ""))));
            Material fallback = Material.matchMaterial(String.valueOf(mapValue(raw, "fallback_material", "AIR")));
            if (fallback == null) {
                fallback = Material.AIR;
            }
            rewards.add(new EventReward(
                    itemId,
                    fallback,
                    Math.max(1, asInt(raw.get("amount"), 1)),
                    Math.max(0.0, asDouble(raw.get("weight"), 1.0)),
                    Math.max(0.0, asDouble(raw.get("xp"), 0.0))
            ));
        }
        return rewards.stream().filter(reward -> reward.weight() > 0.0).toList();
    }

    private Object mapValue(Map<?, ?> map, String key, Object fallback) {
        Object value = map.get(key);
        return value == null ? fallback : value;
    }

    private Set<String> readStringSet(ConfigurationSection section, String key) {
        if (section == null || !section.contains(key)) {
            return Set.of();
        }
        Set<String> values = new HashSet<>();
        if (section.isList(key)) {
            for (String value : section.getStringList(key)) {
                values.add(normalizeId(value));
            }
        } else {
            values.add(normalizeId(section.getString(key, "")));
        }
        values.remove("");
        return Set.copyOf(values);
    }

    private TriggerType parseTriggerType(String raw) {
        try {
            return TriggerType.valueOf(raw == null ? "SEA_CREATURE_ROLL" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return TriggerType.SEA_CREATURE_ROLL;
        }
    }

    private EntityType parseEntityType(String raw, EntityType fallback) {
        try {
            EntityType parsed = EntityType.valueOf(raw == null ? fallback.name() : raw.trim().toUpperCase(Locale.ROOT));
            return parsed.isAlive() ? parsed : fallback;
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private EntityType inferEntityType(String id) {
        String normalized = normalizeId(id);
        if (normalized.contains("elder") || normalized.contains("beast")) {
            return EntityType.ELDER_GUARDIAN;
        }
        if (normalized.contains("guardian")) {
            return EntityType.GUARDIAN;
        }
        return EntityType.DROWNED;
    }

    private void ensureResource() {
        if (eventsFile.exists()) {
            return;
        }
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder for fishing_events.yml.");
            return;
        }
        if (plugin.getResource("fishing_events.yml") != null) {
            plugin.saveResource("fishing_events.yml", false);
            return;
        }
        try {
            eventsFile.createNewFile();
        } catch (IOException exception) {
            plugin.getLogger().warning("Unable to create fishing_events.yml: " + exception.getMessage());
        }
    }

    private double clampChance(double chance) {
        return Math.max(0.0, Math.min(1.0, chance));
    }

    private int asInt(Object raw, int fallback) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return raw == null ? fallback : Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private double asDouble(Object raw, double fallback) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return raw == null ? fallback : Double.parseDouble(String.valueOf(raw));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return String.valueOf((int) Math.rint(value));
        }
        return String.format(Locale.US, "%.1f", value);
    }

    private enum TriggerType {
        SEA_CREATURE_ROLL,
        TREASURE_ROLL
    }

    public record FishingEventStartResult(boolean started, String eventId, String reason) {
        public static FishingEventStartResult started(String eventId) {
            return new FishingEventStartResult(true, eventId, "started");
        }

        public static FishingEventStartResult notStarted(String reason) {
            return new FishingEventStartResult(false, "", reason);
        }
    }

    private record FishingEventDefinition(
            String id,
            String displayName,
            boolean enabled,
            TriggerType triggerType,
            double chance,
            String catalystItem,
            Set<String> sourceIds,
            boolean cooldownSameGameDay,
            long cooldownRealMillis,
            FishingConditions conditions,
            int durationTicks,
            double radius,
            double joinRadius,
            double minContribution,
            double progressTarget,
            boolean completeOnDuration,
            int emptyTimeoutSeconds,
            double damageContributionMultiplier,
            double killContribution,
            double fishingCatchContribution,
            List<EventWave> waves,
            List<EventMilestone> milestones,
            List<EventReward> completionRewards,
            List<EventReward> participationRewards,
            String startMessage,
            String completeMessage,
            String failMessage
    ) {
        private FishingEventDefinition {
            sourceIds = sourceIds == null ? Set.of() : Set.copyOf(sourceIds);
            conditions = conditions == null ? FishingConditions.empty() : conditions;
            waves = waves == null ? List.of() : List.copyOf(waves);
            milestones = milestones == null ? List.of() : List.copyOf(milestones);
            completionRewards = completionRewards == null ? List.of() : List.copyOf(completionRewards);
            participationRewards = participationRewards == null ? List.of() : List.copyOf(participationRewards);
        }

        boolean matchesSource(String sourceId) {
            return sourceIds.isEmpty() || sourceIds.contains(sourceId);
        }
    }

    private record EventWave(int index, int atTicks, List<EventMob> mobs) {
        private EventWave {
            mobs = mobs == null ? List.of() : List.copyOf(mobs);
        }
    }

    private record EventMilestone(int index, double progress, List<EventMob> mobs, List<EventReward> rewards, String message) {
        private EventMilestone {
            mobs = mobs == null ? List.of() : List.copyOf(mobs);
            rewards = rewards == null ? List.of() : List.copyOf(rewards);
        }
    }

    private record EventMob(
            String id,
            EntityType type,
            String name,
            int amount,
            int levelOffset,
            double baseHealth,
            double mod,
            double contributionWeight
    ) {
    }

    private record EventReward(String itemId, Material fallbackMaterial, int amount, double weight, double xp) {
        String displayId() {
            return itemId.isBlank() ? fallbackMaterial.name().toLowerCase(Locale.ROOT) : itemId;
        }
    }

    private record CooldownState(long gameDay, long realMillis) {
    }

    private static final class ActiveFishingEvent {
        private final UUID instanceId;
        private final FishingEventDefinition definition;
        private final Location center;
        private final UUID ownerId;
        private final String ownerName;
        private final int triggerFishingLevel;
        private final String sourceId;
        private final Map<UUID, Double> contributions = new HashMap<>();
        private final Set<UUID> spawnedMobs = new HashSet<>();
        private final Set<Integer> completedWaves = new HashSet<>();
        private final Set<Integer> completedMilestones = new HashSet<>();
        private BukkitTask task;
        private int elapsedTicks;
        private int emptySeconds;
        private double progress;

        private ActiveFishingEvent(UUID instanceId, FishingEventDefinition definition, Location center,
                                   UUID ownerId, String ownerName, int triggerFishingLevel, String sourceId) {
            this.instanceId = instanceId;
            this.definition = definition;
            this.center = center;
            this.ownerId = ownerId;
            this.ownerName = ownerName;
            this.triggerFishingLevel = triggerFishingLevel;
            this.sourceId = sourceId;
        }

        void addContribution(Player player, double amount) {
            if (player == null || amount <= 0.0) {
                return;
            }
            contributions.merge(player.getUniqueId(), amount, Double::sum);
            progress += amount;
        }

        UUID instanceId() {
            return instanceId;
        }

        FishingEventDefinition definition() {
            return definition;
        }

        Location center() {
            return center;
        }

        UUID ownerId() {
            return ownerId;
        }

        String ownerName() {
            return ownerName;
        }

        int triggerFishingLevel() {
            return triggerFishingLevel;
        }

        String sourceId() {
            return sourceId;
        }

        Map<UUID, Double> contributions() {
            return contributions;
        }

        Set<UUID> spawnedMobs() {
            return spawnedMobs;
        }

        Set<Integer> completedWaves() {
            return completedWaves;
        }

        Set<Integer> completedMilestones() {
            return completedMilestones;
        }

        BukkitTask task() {
            return task;
        }

        void setTask(BukkitTask task) {
            this.task = task;
        }

        int elapsedTicks() {
            return elapsedTicks;
        }

        void elapsedTicks(int elapsedTicks) {
            this.elapsedTicks = elapsedTicks;
        }

        int emptySeconds() {
            return emptySeconds;
        }

        void emptySeconds(int emptySeconds) {
            this.emptySeconds = emptySeconds;
        }

        double progress() {
            return progress;
        }
    }
}
