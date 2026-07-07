package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import com.servercore.enchant.EnchantStatResolver;
import com.servercore.fishing.FishingConditions;
import com.servercore.fishing.FishingContext;
import com.servercore.fishing.FishingEnvironmentResult;
import dev.aurelium.auraskills.api.skill.Skills;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fishing speed, sea creature, and treasure engine.
 */
public class FishingManager implements Listener {

    private static final double SEA_CREATURE_BASE_CHANCE = 0.05;
    private static final double SEA_CREATURE_CHANCE_PER_LEVEL = 0.002;
    private static final double TREASURE_BASE_CHANCE = 0.035;
    private static final double TREASURE_CHANCE_PER_LEVEL = 0.0015;
    private static final int BASE_MIN_WAIT_TICKS = 150;
    private static final int BASE_MAX_WAIT_TICKS = 300;
    private static final int MIN_WAIT_FLOOR_TICKS = 15;
    private static final int MAX_WAIT_FLOOR_TICKS = 60;
    private static final int MAX_FISHING_LEVEL = 100;
    private static final double FISHING_SPEED_PER_LEVEL = 3.0;
    private static final double FULL_BUILD_FISHING_SPEED_TARGET = 1500.0;
    private static final double FISHING_SPEED_CAP = FULL_BUILD_FISHING_SPEED_TARGET;
    private static final double MIN_WAIT_SPEED_SCALE = MIN_WAIT_FLOOR_TICKS * FULL_BUILD_FISHING_SPEED_TARGET / (BASE_MIN_WAIT_TICKS - MIN_WAIT_FLOOR_TICKS);
    private static final double MAX_WAIT_SPEED_SCALE = MAX_WAIT_FLOOR_TICKS * FULL_BUILD_FISHING_SPEED_TARGET / (BASE_MAX_WAIT_TICKS - MAX_WAIT_FLOOR_TICKS);

    private static FishingManager instance;
    private final ServerCorePlugin plugin;
    private final GlobalStatManager globalStats;
    private final FishingContentManager fishingContent;
    private final FishingEnvironmentManager fishingEnvironment;
    private final FishingEventManager fishingEvents;
    private final File gatheringLootFile;
    private final Map<UUID, PendingCatchResult> pendingCatchResults = new HashMap<>();
    private final Map<UUID, UUID> activeHooks = new HashMap<>();
    private volatile List<SeaCreatureEntry> seaCreatures = List.of();
    private volatile List<TreasureTier> treasureTiers = List.of();

    public FishingManager(ServerCorePlugin plugin) {
        this(plugin, GlobalStatManager.getInstance());
    }

    public FishingManager(ServerCorePlugin plugin, GlobalStatManager globalStats) {
        this(plugin, globalStats, FishingContentManager.getInstance());
    }

    public FishingManager(ServerCorePlugin plugin, GlobalStatManager globalStats, FishingContentManager fishingContent) {
        this(plugin, globalStats, fishingContent, FishingEnvironmentManager.getInstance(), FishingEventManager.getInstance());
    }

    public FishingManager(ServerCorePlugin plugin, GlobalStatManager globalStats, FishingContentManager fishingContent,
                          FishingEnvironmentManager fishingEnvironment, FishingEventManager fishingEvents) {
        this.plugin = plugin;
        this.globalStats = globalStats;
        this.fishingContent = fishingContent;
        this.fishingEnvironment = fishingEnvironment;
        this.fishingEvents = fishingEvents;
        this.gatheringLootFile = new File(plugin.getDataFolder(), "gathering_loot.yml");
        instance = this;
        reloadLootTables();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public static FishingManager getInstance() {
        return instance;
    }

    public int reloadLootTables() {
        ensureGatheringLootFile();

        YamlConfiguration config = YamlConfiguration.loadConfiguration(gatheringLootFile);
        this.seaCreatures = readSeaCreatures(config.getConfigurationSection("fishing.sea_creatures.entries"));
        this.treasureTiers = readTreasureTiers(config.getConfigurationSection("fishing.treasures"));
        if (seaCreatures.isEmpty()) {
            seaCreatures = defaultSeaCreatures();
        }
        if (treasureTiers.isEmpty()) {
            treasureTiers = defaultTreasureTiers();
        }
        int loaded = seaCreatures.size() + treasureTiers.stream().mapToInt(tier -> tier.entries().size()).sum();
        plugin.getLogger().info("Loaded " + loaded + " fishing content entr" + (loaded == 1 ? "y." : "ies.")
                + " (sea_creatures=" + seaCreatures.size() + ", treasure_tiers=" + treasureTiers.size() + ").");
        return loaded;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        FishHook hook = event.getHook();

        if (event.getState() == PlayerFishEvent.State.FISHING) {
            FishingContentManager.BaitDefinition bait = fishingContent == null
                    ? null
                    : fishingContent.reserveBait(player, hook);
            activeHooks.put(player.getUniqueId(), hook.getUniqueId());
            scheduleApplyFishingSpeed(player, hook, bait);
            return;
        }

        if (event.getState() == PlayerFishEvent.State.CAUGHT_ENTITY) {
            activeHooks.remove(player.getUniqueId());
            if (fishingContent != null) {
                fishingContent.finalizeBait(hook);
            }
            return;
        }

        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            activeHooks.remove(player.getUniqueId());
            if (shouldRefundBait(event.getState()) && fishingContent != null) {
                fishingContent.refundBait(hook);
            }
            return;
        }

        activeHooks.remove(player.getUniqueId());
        FishingContentManager.BaitDefinition bait = fishingContent == null
                ? null
                : fishingContent.getReservedBait(hook);

        FishingContext fishingContext = buildFishingContext(player, hook);
        FishingEnvironmentResult environmentResult = resolveEnvironment(fishingContext);
        fishingContext = fishingContext.withEnvironmentTags(environmentResult.environmentTags());
        if (!shouldUseCustomFishing(fishingContext)) {
            if (event.getCaught() != null && fishingContent != null) {
                fishingContent.finalizeBait(hook);
            }
            return;
        }

        int fishingLevel = getFishingLevel(player);
        FishingStatSnapshot fishingStats = getFishingStatSnapshot(player, bait, environmentResult.bonus());
        double seaCreatureChance = calculateSeaCreatureChance(fishingLevel, fishingStats.total().seaCreatureChance());
        ThreadLocalRandom random = ThreadLocalRandom.current();

        if (random.nextDouble() < seaCreatureChance) {
            SeaCreatureEntry seaCreature = rollSeaCreature(player, fishingContext, fishingLevel, bait, random);
            if (seaCreature != null) {
                FishingEventManager.FishingEventStartResult eventResult = fishingEvents == null
                        ? FishingEventManager.FishingEventStartResult.notStarted("events-disabled")
                        : fishingEvents.tryStartFromSeaCreatureRoll(fishingContext, seaCreature.id());
                if (eventResult.started() || spawnSeaCreature(player, hook.getLocation(), fishingLevel, seaCreature)) {
                    event.setExpToDrop(0);
                    queueCaughtItemRemoval(event.getCaught());
                    if (fishingContent != null) {
                        fishingContent.finalizeBait(hook);
                    }
                    return;
                }
            }
        }

        TreasureEntry treasure = rollTreasure(
                player,
                fishingLevel,
                calculateTreasureChance(fishingLevel, fishingStats.total().treasureChance()),
                bait,
                random
        );
        if (treasure != null && event.getCaught() instanceof Item item) {
            FishingEventManager.FishingEventStartResult eventResult = fishingEvents == null
                    ? FishingEventManager.FishingEventStartResult.notStarted("events-disabled")
                    : fishingEvents.tryStartFromTreasureRoll(fishingContext, treasure.id());
            if (eventResult.started()) {
                event.setExpToDrop(0);
                queueCaughtItemRemoval(event.getCaught());
                if (fishingContent != null) {
                    fishingContent.finalizeBait(hook);
                }
                return;
            }
            pendingCatchResults.put(item.getUniqueId(), PendingCatchResult.replaceWith(createTreasureItem(treasure)));
            event.setExpToDrop(Math.max(event.getExpToDrop(), 3));
            addFishingXp(player, treasure.xp());
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.35f);
            player.sendActionBar(Component.text("Deep sea treasure surfaced: " + treasure.tier() + "."));
            if (fishingContent != null) {
                fishingContent.finalizeBait(hook);
            }
            return;
        }

        if (event.getCaught() instanceof Item) {
            if (fishingContent != null) {
                FishingContentManager.NormalFishDefinition fish =
                        fishingContent.rollNormalFish(player, fishingLevel, bait, random);
                if (fish != null && event.getCaught() instanceof Item item) {
                    pendingCatchResults.put(item.getUniqueId(), PendingCatchResult.replaceWith(
                            fishingContent.createFishItem(fish.id(), 1)
                    ));
                    event.setExpToDrop(Math.max(event.getExpToDrop(), 1));
                    player.sendActionBar(Component.text("Caught " + fish.displayName() + "."));
                }
                fishingContent.finalizeBait(hook);
            }
        }
    }

    /**
     * AuraSkills reads the original caught item at MONITOR priority to grant the
     * normal fishing source XP. ServerCore finalizes its custom result afterwards,
     * making each configured entry XP an intentional extra award.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void finalizeCustomCatch(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item item)) {
            return;
        }

        PendingCatchResult result = pendingCatchResults.remove(item.getUniqueId());
        if (result == null) {
            return;
        }

        if (result.removeCaughtItem()) {
            item.remove();
        } else if (result.replacement() != null) {
            item.setItemStack(result.replacement());
        }
    }

    private void scheduleApplyFishingSpeed(Player player, FishHook hook, FishingContentManager.BaitDefinition bait) {
        scheduleApplyFishingSpeed(player, hook, bait, 0);
    }

    private void scheduleApplyFishingSpeed(Player player, FishHook hook, FishingContentManager.BaitDefinition bait, int attempt) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (hook == null || hook.isDead() || !hook.isValid() || !player.isOnline()) {
                return;
            }
            FishingContext context = buildFishingContext(player, hook);
            if (!shouldUseCustomFishing(context)) {
                if (attempt < 6) {
                    scheduleApplyFishingSpeed(player, hook, bait, attempt + 1);
                }
                return;
            }
            FishingEnvironmentResult environmentResult = resolveEnvironment(context);
            applyFishingSpeed(player, hook, bait, environmentResult.bonus());
        }, 10L);
    }

    private void applyFishingSpeed(Player player, FishHook hook, FishingContentManager.BaitDefinition bait,
                                   FishingStatContribution environmentBonus) {
        int level = getFishingLevel(player);
        FishingWaitWindow waitWindow = calculateWaitWindow(getEffectiveFishingSpeed(
                level,
                getFishingStatSnapshot(player, bait, environmentBonus).total().fishingSpeed()
        ));
        hook.setMinWaitTime(waitWindow.minTicks());
        hook.setMaxWaitTime(waitWindow.maxTicks());
    }

    private boolean shouldRefundBait(PlayerFishEvent.State state) {
        return state == PlayerFishEvent.State.REEL_IN
                || state == PlayerFishEvent.State.FAILED_ATTEMPT
                || state == PlayerFishEvent.State.IN_GROUND;
    }

    private FishingContext buildFishingContext(Player player, FishHook hook) {
        FishingEnvironmentManager manager = fishingEnvironment == null
                ? FishingEnvironmentManager.getInstance()
                : fishingEnvironment;
        if (manager != null) {
            return manager.buildContext(player, hook);
        }

        Location location = hook.getLocation();
        World world = location.getWorld();
        String biomeTag = location.getBlock().getBiome().name().toUpperCase(Locale.ROOT);
        return new FishingContext(
                player,
                hook,
                location,
                world,
                location.getBlock().getBiome(),
                true,
                world != null && world.hasStorm(),
                world != null && world.isThundering(),
                world != null && world.hasStorm(),
                true,
                world == null ? 0L : world.getTime(),
                world == null ? 0L : world.getFullTime(),
                world == null ? 0L : world.getFullTime() / 24000L,
                java.util.Set.of(biomeTag),
                java.util.Set.of(biomeTag)
        );
    }

    private FishingEnvironmentResult resolveEnvironment(FishingContext context) {
        FishingEnvironmentManager manager = fishingEnvironment == null
                ? FishingEnvironmentManager.getInstance()
                : fishingEnvironment;
        return manager == null ? FishingEnvironmentResult.empty(context.environmentTags()) : manager.resolve(context);
    }

    private boolean shouldUseCustomFishing(FishingContext context) {
        FishingEnvironmentManager manager = fishingEnvironment == null
                ? FishingEnvironmentManager.getInstance()
                : fishingEnvironment;
        return manager == null || manager.shouldUseCustomFishing(context);
    }

    public static double getLevelFishingSpeed(int fishingLevel) {
        int safeLevel = Math.max(0, Math.min(MAX_FISHING_LEVEL, fishingLevel));
        return safeLevel * FISHING_SPEED_PER_LEVEL;
    }

    public static double getRawFishingSpeed(int fishingLevel, double equipmentFishingSpeed) {
        return getLevelFishingSpeed(fishingLevel) + Math.max(0.0, equipmentFishingSpeed);
    }

    public static double getEffectiveFishingSpeed(int fishingLevel, double equipmentFishingSpeed) {
        return capFishingSpeed(getRawFishingSpeed(fishingLevel, equipmentFishingSpeed));
    }

    public static double getFishingSpeedCap() {
        return FISHING_SPEED_CAP;
    }

    public static double capFishingSpeed(double fishingSpeed) {
        return Math.min(FISHING_SPEED_CAP, Math.max(0.0, fishingSpeed));
    }

    public static FishingWaitWindow calculateWaitWindow(double fishingSpeed) {
        double safeSpeed = capFishingSpeed(fishingSpeed);
        int minWait = Math.max(MIN_WAIT_FLOOR_TICKS, scaleWait(BASE_MIN_WAIT_TICKS, MIN_WAIT_SPEED_SCALE, safeSpeed));
        int maxWait = Math.max(MAX_WAIT_FLOOR_TICKS, scaleWait(BASE_MAX_WAIT_TICKS, MAX_WAIT_SPEED_SCALE, safeSpeed));
        return new FishingWaitWindow(minWait, Math.max(minWait, maxWait));
    }

    private static int scaleWait(int baseTicks, double speedScale, double fishingSpeed) {
        return (int) Math.round(baseTicks * speedScale / (speedScale + fishingSpeed));
    }

    public boolean hasEnoughFishingPower(Player player, FishingPool pool) {
        if (pool == null || pool.requiredFishingPower() <= 0) {
            return true;
        }
        return player != null && getFishingPower(player) >= pool.requiredFishingPower();
    }

    public int getFishingPower(Player player) {
        if (player == null) {
            return 0;
        }
        return getFishingPower(player.getInventory().getItemInMainHand());
    }

    public int getFishingPower(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return 0;
        }
        PDCManager pdc = PDCManager.getInstance();
        ItemMeta meta = item.getItemMeta();
        if (pdc == null || meta == null) {
            return 0;
        }
        return Math.max(0, meta.getPersistentDataContainer().getOrDefault(
                pdc.KEY_ITEM_FISHING_POWER,
                PersistentDataType.INTEGER,
                0
        ));
    }

    private boolean spawnSeaCreature(Player player, Location hookLocation, int fishingLevel, SeaCreatureEntry entry) {
        Location spawnLocation = hookLocation.clone().add(0.5, 0.25, 0.5);
        MobSpawnManager mobSpawnManager = MobSpawnManager.getInstance();
        if (mobSpawnManager == null) {
            plugin.getLogger().warning("Fishing sea creature skipped because MobSpawnManager is unavailable.");
            return false;
        }

        double levelScale = Math.max(1.0, fishingLevel);
        MobSpawnManager.FishingSeaCreatureSpec spec = new MobSpawnManager.FishingSeaCreatureSpec(
                "sea_creature_" + entry.id(),
                entry.name(),
                Math.max(1, fishingLevel + entry.minLevel()),
                entry.baseHealth() + levelScale * entry.mod() * 4.0,
                3.0 + levelScale * entry.mod() * 0.25,
                entry.mod(),
                "sea,water,fishing"
        );
        LivingEntity entity = mobSpawnManager.spawnFishingSeaCreature(
                () -> spawnSeaCreatureEntity(entry, spawnLocation),
                spec
        );
        if (entity == null) {
            return false;
        }

        addFishingXp(player, entry.xp());
        World world = hookLocation.getWorld();
        world.spawnParticle(Particle.SPLASH, spawnLocation, 45, 0.7, 0.25, 0.7, 0.08);
        world.playSound(spawnLocation, Sound.ENTITY_DROWNED_AMBIENT_WATER, 0.9f, 0.75f);
        player.sendActionBar(Component.text("A sea creature answered the hook."));
        return true;
    }

    private LivingEntity spawnSeaCreatureEntity(SeaCreatureEntry entry, Location spawnLocation) {
        LivingEntity entity = entry.mythicMob().isBlank() ? null : spawnMythicMob(entry.mythicMob(), spawnLocation);
        if (entity != null) {
            return entity;
        }

        Entity spawned = spawnLocation.getWorld().spawnEntity(spawnLocation, entry.type());
        return spawned instanceof LivingEntity livingEntity ? livingEntity : null;
    }

    private SeaCreatureEntry rollSeaCreature(Player player, FishingContext context, int fishingLevel,
                                             FishingContentManager.BaitDefinition bait, ThreadLocalRandom random) {
        List<SeaCreatureEntry> eligible = seaCreatures.stream()
                .filter(entry -> fishingLevel >= entry.minLevel())
                .filter(entry -> fishingContent == null || fishingContent.isSeaCreatureUnlocked(bait, entry.id()))
                .filter(entry -> entry.matchesContext(context))
                .toList();
        if (eligible.isEmpty()) {
            eligible = seaCreatures.stream()
                    .filter(entry -> fishingLevel >= entry.minLevel())
                    .filter(entry -> fishingContent == null || fishingContent.isSeaCreatureUnlocked(bait, entry.id()))
                    .filter(entry -> !entry.explicitConditions())
                    .toList();
        }
        if (eligible.isEmpty()) {
            return null;
        }

        double totalWeight = eligible.stream()
                .mapToDouble(entry -> adjustedSeaCreatureWeight(player, bait, entry))
                .sum();
        double roll = random.nextDouble(Math.max(0.0001, totalWeight));
        for (SeaCreatureEntry entry : eligible) {
            roll -= adjustedSeaCreatureWeight(player, bait, entry);
            if (roll < 0.0) {
                return entry;
            }
        }
        return eligible.getFirst();
    }

    private double adjustedSeaCreatureWeight(Player player, FishingContentManager.BaitDefinition bait, SeaCreatureEntry entry) {
        double bonus = fishingContent == null
                ? 0.0
                : fishingContent.getSeaCreatureWeightBonus(player, bait, entry.id(), entry.minLevel());
        return entry.weight() * Math.max(0.05, 1.0 + bonus);
    }

    private TreasureEntry rollTreasure(Player player, int fishingLevel, double treasureChance,
                                       FishingContentManager.BaitDefinition bait, ThreadLocalRandom random) {
        if (!rollRareFishingDrop(player, treasureChance, random)) {
            return null;
        }

        TreasureTier tier = rollTreasureTier(player, fishingLevel, bait, random);
        if (tier == null) {
            return null;
        }

        List<TreasureEntry> eligible = tier.entries().stream()
                .filter(entry -> fishingLevel >= entry.minLevel())
                .filter(entry -> fishingContent == null || fishingContent.isTreasureUnlocked(bait, entry.id()))
                .toList();
        double totalWeight = eligible.stream()
                .mapToDouble(entry -> adjustedTreasureEntryWeight(player, bait, entry))
                .sum();
        double roll = random.nextDouble(Math.max(0.0001, totalWeight));
        for (TreasureEntry entry : eligible) {
            roll -= adjustedTreasureEntryWeight(player, bait, entry);
            if (roll < 0.0) {
                return entry;
            }
        }
        return eligible.isEmpty() ? null : eligible.getFirst();
    }

    private boolean rollRareFishingDrop(Player player, double baseChance, ThreadLocalRandom random) {
        double chance = Math.max(0.0, Math.min(1.0, baseChance));
        if (chance <= 0.0) {
            return false;
        }

        GlobalStatManager stats = globalStats == null ? GlobalStatManager.getInstance() : globalStats;
        if (stats != null) {
            return stats.rollRareDrop(player, chance, random);
        }
        return random.nextDouble() < chance;
    }

    private ItemStack createTreasureItem(TreasureEntry treasure) {
        CustomItemRegistry registry = CustomItemRegistry.getInstance();
        ItemStack custom = registry == null ? null : registry.createItem(treasure.id(), treasure.amount());
        return custom == null ? new ItemStack(treasure.material(), treasure.amount()) : custom;
    }

    private TreasureTier rollTreasureTier(Player player, int fishingLevel,
                                          FishingContentManager.BaitDefinition bait, ThreadLocalRandom random) {
        List<TreasureTier> eligible = treasureTiers.stream()
                .filter(tier -> fishingLevel >= tier.minLevel())
                .filter(tier -> fishingContent == null || fishingContent.isTreasureUnlocked(bait, tier.id()))
                .filter(tier -> !tier.entries().isEmpty())
                .toList();
        double totalWeight = eligible.stream()
                .mapToDouble(tier -> adjustedTreasureTierWeight(player, bait, tier))
                .sum();
        if (totalWeight <= 0) {
            return null;
        }

        double roll = random.nextDouble(totalWeight);
        for (TreasureTier tier : eligible) {
            roll -= adjustedTreasureTierWeight(player, bait, tier);
            if (roll < 0.0) {
                return tier;
            }
        }
        return eligible.isEmpty() ? null : eligible.getFirst();
    }

    private double adjustedTreasureTierWeight(Player player, FishingContentManager.BaitDefinition bait, TreasureTier tier) {
        double bonus = fishingContent == null ? 0.0 : fishingContent.getTreasureTierWeightBonus(player, bait, tier.id());
        return tier.weight() * Math.max(0.05, 1.0 + bonus);
    }

    private double adjustedTreasureEntryWeight(Player player, FishingContentManager.BaitDefinition bait, TreasureEntry entry) {
        double bonus = fishingContent == null ? 0.0 : fishingContent.getTreasureEntryWeightBonus(player, bait, entry.id());
        return entry.weight() * Math.max(0.05, 1.0 + bonus);
    }

    private LivingEntity spawnMythicMob(String mythicMob, Location location) {
        try {
            Class<?> mythicBukkitClass = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object mythicBukkit = mythicBukkitClass.getMethod("inst").invoke(null);
            Object mobManager = mythicBukkit.getClass().getMethod("getMobManager").invoke(mythicBukkit);
            Object activeMob = mobManager.getClass().getMethod("spawnMob", String.class, Location.class).invoke(mobManager, mythicMob, location);
            if (activeMob == null) {
                return null;
            }

            Method getEntity = activeMob.getClass().getMethod("getEntity");
            Object abstractEntity = getEntity.invoke(activeMob);
            Method getBukkitEntity = abstractEntity.getClass().getMethod("getBukkitEntity");
            Object bukkitEntity = getBukkitEntity.invoke(abstractEntity);
            return bukkitEntity instanceof LivingEntity livingEntity ? livingEntity : null;
        } catch (ReflectiveOperationException | LinkageError exception) {
            plugin.getLogger().fine("Could not spawn MythicMob '" + mythicMob + "': " + exception.getMessage());
            return null;
        }
    }

    private void addFishingXp(Player player, double amount) {
        AuraSkillsBridge bridge = AuraSkillsBridge.getInstance();
        if (bridge != null) {
            bridge.addSkillXp(player, Skills.FISHING, amount);
        }
    }

    private String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return String.valueOf((int) Math.rint(value));
        }
        return String.format(Locale.US, "%.1f", value);
    }

    private boolean isOpenWater(Location location) {
        Block center = location.getBlock();
        if (!isWater(center)) {
            return false;
        }

        int waterBlocks = 0;
        int openColumns = 0;
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                Block surface = center.getRelative(x, 0, z);
                if (isWater(surface)) {
                    waterBlocks++;
                    if (!surface.getRelative(0, 1, 0).getType().isSolid()) {
                        openColumns++;
                    }
                }
            }
        }
        return waterBlocks >= 16 && openColumns >= 10;
    }

    private boolean isWater(Block block) {
        return block.getType() == Material.WATER || block.getType() == Material.BUBBLE_COLUMN;
    }

    private void queueCaughtItemRemoval(Entity caught) {
        if (caught instanceof Item item) {
            pendingCatchResults.put(item.getUniqueId(), PendingCatchResult.remove());
        }
    }

    private int getFishingLevel(Player player) {
        AuraSkillsBridge bridge = AuraSkillsBridge.getInstance();
        return bridge == null ? 0 : bridge.getSkillLevel(player, Skills.FISHING);
    }

    public double getFishingSpeed(Player player) {
        return getFishingStatSnapshot(player).total().fishingSpeed();
    }

    public double getSeaCreatureChance(Player player, int fishingLevel) {
        return calculateSeaCreatureChance(
                fishingLevel,
                getFishingStatSnapshot(player).total().seaCreatureChance()
        );
    }

    public double getTreasureChance(Player player, int fishingLevel) {
        return calculateTreasureChance(
                fishingLevel,
                getFishingStatSnapshot(player).total().treasureChance()
        );
    }

    public List<String> describeCurrentFishingContext(Player player) {
        if (player == null) {
            return List.of("No player.");
        }
        UUID hookId = activeHooks.get(player.getUniqueId());
        Entity entity = hookId == null ? null : Bukkit.getEntity(hookId);
        if (!(entity instanceof FishHook hook) || hook.isDead() || !hook.isValid()) {
            return List.of("Fishing Debug", "No active fishing hook for " + player.getName() + ".");
        }

        FishingContext context = buildFishingContext(player, hook);
        FishingEnvironmentResult result = resolveEnvironment(context);
        FishingContext enriched = context.withEnvironmentTags(result.environmentTags());
        FishingContentManager.BaitDefinition bait = fishingContent == null ? null : fishingContent.getReservedBait(hook);
        FishingStatSnapshot stats = getFishingStatSnapshot(player, bait, result.bonus());
        int level = getFishingLevel(player);
        FishingStatContribution total = stats.total();
        FishingWaitWindow wait = calculateWaitWindow(getEffectiveFishingSpeed(level, total.fishingSpeed()));

        List<String> lines = new ArrayList<>();
        lines.add("Fishing Debug: " + player.getName());
        FishingEnvironmentManager manager = fishingEnvironment == null
                ? FishingEnvironmentManager.getInstance()
                : fishingEnvironment;
        if (manager != null) {
            lines.addAll(manager.describe(player, hook));
        } else {
            lines.add("Biome: " + context.biome().name());
            lines.add("OpenWater: " + context.openWater());
        }
        lines.add("Custom Fishing Enabled: " + shouldUseCustomFishing(enriched));
        lines.add("Bait: " + (bait == null ? "none" : bait.id()));
        lines.add("Fishing Level: " + level);
        lines.add("Total FS/SCC/TC: " + formatNumber(total.fishingSpeed())
                + " / " + formatNumber(total.seaCreatureChance()) + "%"
                + " / " + formatNumber(total.treasureChance()) + "%");
        lines.add("Effective FS: " + formatNumber(getEffectiveFishingSpeed(level, total.fishingSpeed()))
                + " / " + formatNumber(getFishingSpeedCap()));
        lines.add("Wait Window: " + wait.minTicks() + "-" + wait.maxTicks() + " ticks");
        lines.add("Sea Creature Chance: " + formatNumber(calculateSeaCreatureChance(level, total.seaCreatureChance()) * 100.0) + "%");
        lines.add("Treasure Chance: " + formatNumber(calculateTreasureChance(level, total.treasureChance()) * 100.0) + "%");
        return lines;
    }

    private double calculateSeaCreatureChance(int fishingLevel, double equipmentBonusPercent) {
        double bonus = equipmentBonusPercent / 100.0;
        return Math.max(0.0, Math.min(0.95, SEA_CREATURE_BASE_CHANCE + fishingLevel * SEA_CREATURE_CHANCE_PER_LEVEL + bonus));
    }

    private double calculateTreasureChance(int fishingLevel, double equipmentBonusPercent) {
        double bonus = equipmentBonusPercent / 100.0;
        return Math.max(0.0, Math.min(0.95, TREASURE_BASE_CHANCE + fishingLevel * TREASURE_CHANCE_PER_LEVEL + bonus));
    }

    public FishingStatSnapshot getFishingStatSnapshot(Player player) {
        return getFishingStatSnapshot(player, null);
    }

    public FishingStatSnapshot getFishingStatSnapshot(Player player, FishingContentManager.BaitDefinition bait) {
        return getFishingStatSnapshot(player, bait, FishingStatContribution.empty());
    }

    public FishingStatSnapshot getFishingStatSnapshot(Player player, FishingContentManager.BaitDefinition bait,
                                                      FishingStatContribution environmentBonus) {
        PDCManager pdc = PDCManager.getInstance();
        if (player == null || pdc == null) {
            return FishingStatSnapshot.empty();
        }

        FishingStatContribution mainHand = getItemFishingStats(player.getInventory().getItemInMainHand(), pdc);
        FishingStatContribution armor = sumFishingStats(player.getInventory().getArmorContents(), pdc);

        AccessoryManager accessoryManager = AccessoryManager.getInstance();
        FishingStatContribution accessories = FishingStatContribution.empty();
        FishingStatContribution talismanBag = FishingStatContribution.empty();
        if (accessoryManager != null) {
            accessories = sumFishingStats(accessoryManager.loadAccessories(player), pdc);
            talismanBag = sumFishingStats(accessoryManager.loadActiveTalismans(player), pdc);
        }

        com.servercore.passive.PassiveSnapshotService passiveService =
                com.servercore.passive.PassiveSnapshotService.getInstance();
        if (passiveService != null) {
            talismanBag = talismanBag.plus(new FishingStatContribution(
                    passiveService.getStatBonus(player, pdc.KEY_FISHING_SPEED.getKey()),
                    passiveService.getStatBonus(player, pdc.KEY_SEA_CREATURE_CHANCE.getKey()),
                    passiveService.getStatBonus(player, pdc.KEY_TREASURE_CHANCE.getKey())
            ));
        }

        return new FishingStatSnapshot(
                mainHand,
                armor,
                accessories,
                talismanBag,
                getTemporaryFishingStats(player, bait),
                environmentBonus
        );
    }

    private FishingStatContribution getTemporaryFishingStats(Player player, FishingContentManager.BaitDefinition bait) {
        FishingStatContribution temporary = FishingStatContribution.empty();
        if (fishingContent != null) {
            temporary = temporary.plus(fishingContent.getActiveFoodBuffStats(player));
        }
        if (bait != null) {
            temporary = temporary.plus(bait.stats());
        }

        if (fishingContent == null || temporary.treasureChance() <= 0.0) {
            return temporary;
        }
        return new FishingStatContribution(
                temporary.fishingSpeed(),
                temporary.seaCreatureChance(),
                Math.min(temporary.treasureChance(), fishingContent.getTemporaryTreasureChanceBonusCap())
        );
    }

    private FishingStatContribution sumFishingStats(ItemStack[] items, PDCManager pdc) {
        FishingStatContribution total = FishingStatContribution.empty();
        if (items == null) {
            return total;
        }

        for (ItemStack item : items) {
            total = total.plus(getItemFishingStats(item, pdc));
        }
        return total;
    }

    private FishingStatContribution getItemFishingStats(ItemStack item, PDCManager pdc) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return FishingStatContribution.empty();
        }

        EnchantStatResolver resolver = EnchantStatResolver.getInstance();
        return new FishingStatContribution(
                pdc.getStat(item, pdc.KEY_FISHING_SPEED)
                        + (resolver == null ? 0.0 : resolver.resolveNumeric(item, pdc.KEY_FISHING_SPEED.getKey())),
                pdc.getStat(item, pdc.KEY_SEA_CREATURE_CHANCE)
                        + (resolver == null ? 0.0 : resolver.resolveNumeric(item, pdc.KEY_SEA_CREATURE_CHANCE.getKey())),
                pdc.getStat(item, pdc.KEY_TREASURE_CHANCE)
                        + (resolver == null ? 0.0 : resolver.resolveNumeric(item, pdc.KEY_TREASURE_CHANCE.getKey()))
        );
    }

    private List<SeaCreatureEntry> readSeaCreatures(ConfigurationSection section) {
        List<SeaCreatureEntry> entries = new ArrayList<>();
        if (section == null) {
            return entries;
        }

        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) {
                continue;
            }

            EntityType type = parseEntityType(entry.getString("entity_type", "DROWNED"), EntityType.DROWNED);
            entries.add(new SeaCreatureEntry(
                    id,
                    entry.getString("name", id),
                    type,
                    Math.max(0, entry.getInt("min_fishing_level", entry.getInt("min_level", 0))),
                    Math.max(0, entry.getInt("weight", 1)),
                    Math.max(0.1, entry.getDouble("mod", 1.0)),
                    Math.max(1.0, entry.getDouble("base_health", 24.0)),
                    FishingConditions.fromConfig(entry.getConfigurationSection("conditions"), entry.getString("biome_tags", "")),
                    entry.isConfigurationSection("conditions"),
                    entry.getString("mythic_mob", ""),
                    Math.max(0.0, entry.getDouble("xp", 0.0))
            ));
        }
        return entries.stream().filter(entry -> entry.weight() > 0).toList();
    }

    private List<TreasureTier> readTreasureTiers(ConfigurationSection section) {
        List<TreasureTier> tiers = new ArrayList<>();
        if (section == null) {
            return tiers;
        }

        for (String tierName : List.of("rare", "epic", "legendary")) {
            ConfigurationSection tier = section.getConfigurationSection(tierName);
            if (tier == null) {
                continue;
            }
            tiers.add(new TreasureTier(
                    tierName,
                    Math.max(0, tier.getInt("weight", 1)),
                    Math.max(0, tier.getInt("min_fishing_level", tier.getInt("min_level", 0))),
                    readTreasureEntries(tierName, tier.getConfigurationSection("entries"))
            ));
        }
        return tiers.stream().filter(tier -> tier.weight() > 0 && !tier.entries().isEmpty()).toList();
    }

    private List<TreasureEntry> readTreasureEntries(String tier, ConfigurationSection section) {
        List<TreasureEntry> entries = new ArrayList<>();
        if (section == null) {
            return entries;
        }

        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) {
                continue;
            }

            Material fallback = Material.matchMaterial(entry.getString("fallback_material", entry.getString("material", "PRISMARINE_CRYSTALS")));
            if (fallback == null || fallback.isAir()) {
                plugin.getLogger().warning("Invalid fishing treasure material: " + id);
                continue;
            }

            String customItemId = entry.getString("custom_item", entry.getString("item", id));
            entries.add(new TreasureEntry(
                    customItemId == null || customItemId.isBlank() ? id : customItemId.trim(),
                    fallback,
                    Math.max(0, entry.getInt("min_fishing_level", entry.getInt("min_level", 0))),
                    Math.max(1, entry.getInt("amount", 1)),
                    Math.max(0, entry.getInt("weight", 1)),
                    Math.max(0.0, entry.getDouble("xp", 0.0)),
                    tier
            ));
        }
        return entries.stream().filter(entry -> entry.weight() > 0).toList();
    }

    private EntityType parseEntityType(String raw, EntityType fallback) {
        try {
            EntityType parsed = EntityType.valueOf(raw == null ? fallback.name() : raw.trim().toUpperCase(Locale.ROOT));
            return parsed.isAlive() ? parsed : fallback;
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private void ensureGatheringLootFile() {
        if (gatheringLootFile.exists()) {
            return;
        }

        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder for gathering_loot.yml.");
            return;
        }

        try (InputStream stream = plugin.getResource("gathering_loot.yml")) {
            if (stream != null) {
                plugin.saveResource("gathering_loot.yml", false);
                return;
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Unable to check bundled gathering_loot.yml: " + exception.getMessage());
        }
    }

    private List<SeaCreatureEntry> defaultSeaCreatures() {
        return List.of(
                new SeaCreatureEntry("deep_drowned", "Deep Drowned", EntityType.DROWNED, 1, 60, 1.0, 24.0,
                        FishingConditions.legacyBiomeTags("OCEAN,RIVER,SWAMP"), false, "", 8.0),
                new SeaCreatureEntry("reef_guardian", "Reef Guardian", EntityType.GUARDIAN, 10, 95, 1.4, 42.0,
                        FishingConditions.legacyBiomeTags("OCEAN,WARM"), false, "", 18.0),
                new SeaCreatureEntry("abyss_guardian", "Abyss Guardian", EntityType.GUARDIAN, 25, 150, 2.0, 80.0,
                        FishingConditions.legacyBiomeTags("DEEP_OCEAN,COLD,FROZEN"), false, "", 36.0),
                new SeaCreatureEntry("elder_tidecaller", "Elder Tidecaller", EntityType.ELDER_GUARDIAN, 45, 300, 4.0, 180.0,
                        FishingConditions.legacyBiomeTags("DEEP_OCEAN"), false, "", 80.0)
        );
    }

    private List<TreasureTier> defaultTreasureTiers() {
        return List.of(
                new TreasureTier("rare", 80, 0, List.of(
                        new TreasureEntry("deep_sea_cache", Material.PRISMARINE_CRYSTALS, 1, 1, 60, 6.0, "rare"),
                        new TreasureEntry("sunken_emerald", Material.EMERALD, 5, 1, 35, 8.0, "rare")
                )),
                new TreasureTier("epic", 18, 12, List.of(
                        new TreasureEntry("ancient_shell", Material.NAUTILUS_SHELL, 12, 1, 22, 18.0, "epic")
                )),
                new TreasureTier("legendary", 2, 35, List.of(
                        new TreasureEntry("tide_heart", Material.HEART_OF_THE_SEA, 35, 1, 4, 60.0, "legendary")
                ))
        );
    }

    public record FishingWaitWindow(int minTicks, int maxTicks) {
    }

    public record FishingPool(String id, int requiredFishingPower) {
        public FishingPool {
            id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
            requiredFishingPower = Math.max(0, requiredFishingPower);
        }
    }

    public record FishingStatContribution(
            double fishingSpeed,
            double seaCreatureChance,
            double treasureChance
    ) {
        public static FishingStatContribution empty() {
            return new FishingStatContribution(0.0, 0.0, 0.0);
        }

        public FishingStatContribution plus(FishingStatContribution other) {
            if (other == null) {
                return this;
            }
            return new FishingStatContribution(
                    fishingSpeed + other.fishingSpeed,
                    seaCreatureChance + other.seaCreatureChance,
                    treasureChance + other.treasureChance
            );
        }
    }

    public record FishingStatSnapshot(
            FishingStatContribution mainHand,
            FishingStatContribution armor,
            FishingStatContribution accessories,
            FishingStatContribution talismanBag,
            FishingStatContribution temporary,
            FishingStatContribution environment
    ) {
        public FishingStatSnapshot {
            mainHand = mainHand == null ? FishingStatContribution.empty() : mainHand;
            armor = armor == null ? FishingStatContribution.empty() : armor;
            accessories = accessories == null ? FishingStatContribution.empty() : accessories;
            talismanBag = talismanBag == null ? FishingStatContribution.empty() : talismanBag;
            temporary = temporary == null ? FishingStatContribution.empty() : temporary;
            environment = environment == null ? FishingStatContribution.empty() : environment;
        }

        public static FishingStatSnapshot empty() {
            FishingStatContribution empty = FishingStatContribution.empty();
            return new FishingStatSnapshot(empty, empty, empty, empty, empty, empty);
        }

        public FishingStatSnapshot(
                FishingStatContribution mainHand,
                FishingStatContribution armor,
                FishingStatContribution accessories,
                FishingStatContribution talismanBag
        ) {
            this(mainHand, armor, accessories, talismanBag, FishingStatContribution.empty(), FishingStatContribution.empty());
        }

        public FishingStatSnapshot(
                FishingStatContribution mainHand,
                FishingStatContribution armor,
                FishingStatContribution accessories,
                FishingStatContribution talismanBag,
                FishingStatContribution temporary
        ) {
            this(mainHand, armor, accessories, talismanBag, temporary, FishingStatContribution.empty());
        }

        public FishingStatContribution total() {
            return mainHand.plus(armor).plus(accessories).plus(talismanBag).plus(temporary).plus(environment);
        }
    }

    private record PendingCatchResult(boolean removeCaughtItem, ItemStack replacement) {
        static PendingCatchResult remove() {
            return new PendingCatchResult(true, null);
        }

        static PendingCatchResult replaceWith(ItemStack replacement) {
            return new PendingCatchResult(false, replacement);
        }
    }

    private record SeaCreatureEntry(
            String id,
            String name,
            EntityType type,
            int minLevel,
            int weight,
            double mod,
            double baseHealth,
            FishingConditions conditions,
            boolean explicitConditions,
            String mythicMob,
            double xp
    ) {
        private SeaCreatureEntry {
            conditions = conditions == null ? FishingConditions.empty() : conditions;
        }

        boolean matchesContext(FishingContext context) {
            return conditions.matches(context);
        }
    }

    private record TreasureTier(String id, int weight, int minLevel, List<TreasureEntry> entries) {
    }

    private record TreasureEntry(String id, Material material, int minLevel, int amount, int weight, double xp, String tier) {
    }
}
