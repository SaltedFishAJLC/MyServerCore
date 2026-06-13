package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import com.servercore.enchant.EnchantStatResolver;
import dev.aurelium.auraskills.api.skill.Skills;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
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
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private static final int MIN_WAIT_FLOOR_TICKS = 10;
    private static final int MAX_WAIT_FLOOR_TICKS = 80;
    private static final int MAX_FISHING_LEVEL = 100;
    private static final double FISHING_SPEED_PER_LEVEL = 3.0;
    private static final double FULL_BUILD_FISHING_SPEED_TARGET = 1400.0;
    private static final double MIN_WAIT_SPEED_SCALE = MIN_WAIT_FLOOR_TICKS * FULL_BUILD_FISHING_SPEED_TARGET / (BASE_MIN_WAIT_TICKS - MIN_WAIT_FLOOR_TICKS);
    private static final double MAX_WAIT_SPEED_SCALE = MAX_WAIT_FLOOR_TICKS * FULL_BUILD_FISHING_SPEED_TARGET / (BASE_MAX_WAIT_TICKS - MAX_WAIT_FLOOR_TICKS);

    private static FishingManager instance;
    private final ServerCorePlugin plugin;
    private final GlobalStatManager globalStats;
    private final File gatheringLootFile;
    private volatile List<SeaCreatureEntry> seaCreatures = List.of();
    private volatile List<TreasureTier> treasureTiers = List.of();

    public FishingManager(ServerCorePlugin plugin) {
        this(plugin, GlobalStatManager.getInstance());
    }

    public FishingManager(ServerCorePlugin plugin, GlobalStatManager globalStats) {
        this.plugin = plugin;
        this.globalStats = globalStats;
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
            applyFishingSpeed(player, hook);
            return;
        }

        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) {
            return;
        }

        if (!isOpenWater(hook.getLocation())) {
            return;
        }

        int fishingLevel = getFishingLevel(player);
        double seaCreatureChance = getSeaCreatureChance(player, fishingLevel);
        ThreadLocalRandom random = ThreadLocalRandom.current();

        if (random.nextDouble() < seaCreatureChance) {
            event.setExpToDrop(0);
            removeCaughtItem(event.getCaught());
            spawnSeaCreature(player, hook.getLocation(), fishingLevel);
            return;
        }

        TreasureEntry treasure = rollTreasure(player, fishingLevel, random);
        if (treasure != null && event.getCaught() instanceof Item item) {
            item.setItemStack(createTreasureItem(treasure));
            event.setExpToDrop(Math.max(event.getExpToDrop(), 3));
            addFishingXp(player, treasure.xp());
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.35f);
            player.sendActionBar(Component.text("Deep sea treasure surfaced: " + treasure.tier() + "."));
        }
    }

    private void applyFishingSpeed(Player player, FishHook hook) {
        int level = getFishingLevel(player);
        FishingWaitWindow waitWindow = calculateWaitWindow(getEffectiveFishingSpeed(level, getFishingSpeed(player)));
        hook.setMinWaitTime(waitWindow.minTicks());
        hook.setMaxWaitTime(waitWindow.maxTicks());
    }

    public static double getLevelFishingSpeed(int fishingLevel) {
        int safeLevel = Math.max(0, Math.min(MAX_FISHING_LEVEL, fishingLevel));
        return safeLevel * FISHING_SPEED_PER_LEVEL;
    }

    public static double getEffectiveFishingSpeed(int fishingLevel, double equipmentFishingSpeed) {
        return getLevelFishingSpeed(fishingLevel) + Math.max(0.0, equipmentFishingSpeed);
    }

    public static FishingWaitWindow calculateWaitWindow(double fishingSpeed) {
        double safeSpeed = Math.max(0.0, fishingSpeed);
        int minWait = Math.max(MIN_WAIT_FLOOR_TICKS, scaleWait(BASE_MIN_WAIT_TICKS, MIN_WAIT_SPEED_SCALE, safeSpeed));
        int maxWait = Math.max(MAX_WAIT_FLOOR_TICKS, scaleWait(BASE_MAX_WAIT_TICKS, MAX_WAIT_SPEED_SCALE, safeSpeed));
        return new FishingWaitWindow(minWait, Math.max(minWait, maxWait));
    }

    private static int scaleWait(int baseTicks, double speedScale, double fishingSpeed) {
        return (int) Math.round(baseTicks * speedScale / (speedScale + fishingSpeed));
    }

    private void spawnSeaCreature(Player player, Location hookLocation, int fishingLevel) {
        SeaCreatureEntry entry = rollSeaCreature(hookLocation, fishingLevel);
        if (entry == null) {
            return;
        }

        Location spawnLocation = hookLocation.clone().add(0.5, 0.25, 0.5);
        LivingEntity entity = entry.mythicMob().isBlank() ? null : spawnMythicMob(entry.mythicMob(), spawnLocation);
        if (entity == null) {
            entity = (LivingEntity) hookLocation.getWorld().spawnEntity(spawnLocation, entry.type());
        }
        entity.customName(Component.text(entry.name()));
        entity.setCustomNameVisible(true);
        entity.getScoreboardTags().add("servercore_sea_creature");

        double levelScale = Math.max(1.0, fishingLevel);
        setAttribute(entity, Attribute.GENERIC_MAX_HEALTH, entry.baseHealth() + levelScale * entry.mod() * 4.0);
        setAttribute(entity, Attribute.GENERIC_ATTACK_DAMAGE, 3.0 + levelScale * entry.mod() * 0.25);
        entity.setHealth(entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue());

        PDCManager pdc = PDCManager.getInstance();
        if (pdc != null) {
            entity.getPersistentDataContainer().set(pdc.KEY_CUSTOM_MOB_ID, PersistentDataType.STRING, "sea_creature_" + entry.id());
            entity.getPersistentDataContainer().set(pdc.KEY_MOB_POWER_LEVEL, PersistentDataType.INTEGER, Math.max(1, fishingLevel + entry.minLevel()));
            entity.getPersistentDataContainer().set(pdc.KEY_MOB_SCALING_MOD, PersistentDataType.DOUBLE, entry.mod());
            entity.getPersistentDataContainer().set(pdc.KEY_MOB_TAGS, PersistentDataType.STRING, "sea,water,fishing");
        }

        addFishingXp(player, entry.xp());
        World world = hookLocation.getWorld();
        world.spawnParticle(Particle.SPLASH, spawnLocation, 45, 0.7, 0.25, 0.7, 0.08);
        world.playSound(spawnLocation, Sound.ENTITY_DROWNED_AMBIENT_WATER, 0.9f, 0.75f);
        player.sendActionBar(Component.text("A sea creature answered the hook."));
    }

    private SeaCreatureEntry rollSeaCreature(Location location, int fishingLevel) {
        Biome biome = location.getBlock().getBiome();
        List<SeaCreatureEntry> eligible = seaCreatures.stream()
                .filter(entry -> fishingLevel >= entry.minLevel())
                .filter(entry -> entry.matchesBiome(biome))
                .toList();
        if (eligible.isEmpty()) {
            eligible = seaCreatures.stream()
                    .filter(entry -> fishingLevel >= entry.minLevel())
                    .toList();
        }
        if (eligible.isEmpty()) {
            return null;
        }

        int totalWeight = eligible.stream().mapToInt(SeaCreatureEntry::weight).sum();
        int roll = ThreadLocalRandom.current().nextInt(Math.max(1, totalWeight));
        for (SeaCreatureEntry entry : eligible) {
            roll -= entry.weight();
            if (roll < 0) {
                return entry;
            }
        }
        return eligible.getFirst();
    }

    private TreasureEntry rollTreasure(Player player, int fishingLevel, ThreadLocalRandom random) {
        double treasureChance = getTreasureChance(player, fishingLevel);
        if (!rollRareFishingDrop(player, treasureChance, random)) {
            return null;
        }

        TreasureTier tier = rollTreasureTier(fishingLevel, random);
        if (tier == null) {
            return null;
        }

        List<TreasureEntry> eligible = tier.entries().stream()
                .filter(entry -> fishingLevel >= entry.minLevel())
                .toList();
        int totalWeight = eligible.stream().mapToInt(TreasureEntry::weight).sum();
        int roll = random.nextInt(Math.max(1, totalWeight));
        for (TreasureEntry entry : eligible) {
            roll -= entry.weight();
            if (roll < 0) {
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

    private TreasureTier rollTreasureTier(int fishingLevel, ThreadLocalRandom random) {
        List<TreasureTier> eligible = treasureTiers.stream()
                .filter(tier -> fishingLevel >= tier.minLevel())
                .filter(tier -> !tier.entries().isEmpty())
                .toList();
        int totalWeight = eligible.stream().mapToInt(TreasureTier::weight).sum();
        if (totalWeight <= 0) {
            return null;
        }

        int roll = random.nextInt(totalWeight);
        for (TreasureTier tier : eligible) {
            roll -= tier.weight();
            if (roll < 0) {
                return tier;
            }
        }
        return eligible.isEmpty() ? null : eligible.getFirst();
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

    private void removeCaughtItem(Entity caught) {
        if (caught != null) {
            caught.remove();
        }
    }

    private int getFishingLevel(Player player) {
        AuraSkillsBridge bridge = AuraSkillsBridge.getInstance();
        return bridge == null ? 0 : bridge.getSkillLevel(player, Skills.FISHING);
    }

    public double getFishingSpeed(Player player) {
        PDCManager pdc = PDCManager.getInstance();
        return pdc == null ? 0.0 : getToolStat(player, pdc.KEY_FISHING_SPEED);
    }

    public double getSeaCreatureChance(Player player, int fishingLevel) {
        PDCManager pdc = PDCManager.getInstance();
        double bonus = pdc == null ? 0.0 : getToolStat(player, pdc.KEY_SEA_CREATURE_CHANCE) / 100.0;
        return Math.min(0.95, SEA_CREATURE_BASE_CHANCE + fishingLevel * SEA_CREATURE_CHANCE_PER_LEVEL + Math.max(0.0, bonus));
    }

    public double getTreasureChance(Player player, int fishingLevel) {
        PDCManager pdc = PDCManager.getInstance();
        double bonus = pdc == null ? 0.0 : getToolStat(player, pdc.KEY_TREASURE_CHANCE) / 100.0;
        return Math.min(0.95, TREASURE_BASE_CHANCE + fishingLevel * TREASURE_CHANCE_PER_LEVEL + Math.max(0.0, bonus));
    }

    private double getToolStat(Player player, org.bukkit.NamespacedKey key) {
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool == null || tool.getType().isAir() || !tool.hasItemMeta()) {
            return 0.0;
        }
        double value = tool.getItemMeta().getPersistentDataContainer().getOrDefault(key, org.bukkit.persistence.PersistentDataType.DOUBLE, 0.0);
        EnchantStatResolver resolver = EnchantStatResolver.getInstance();
        return value + (resolver == null ? 0.0 : resolver.resolveNumeric(tool, key.getKey()));
    }

    private void setAttribute(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(Math.max(0.0, value));
        }
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
                    entry.getString("biome_tags", ""),
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
            return EntityType.valueOf(raw == null ? fallback.name() : raw.trim().toUpperCase(Locale.ROOT));
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
                new SeaCreatureEntry("deep_drowned", "Deep Drowned", EntityType.DROWNED, 1, 60, 1.0, 24.0, "OCEAN,RIVER,SWAMP", "", 8.0),
                new SeaCreatureEntry("reef_guardian", "Reef Guardian", EntityType.GUARDIAN, 10, 95, 1.4, 42.0, "OCEAN,WARM", "", 18.0),
                new SeaCreatureEntry("abyss_guardian", "Abyss Guardian", EntityType.GUARDIAN, 25, 150, 2.0, 80.0, "DEEP_OCEAN,COLD,FROZEN", "", 36.0),
                new SeaCreatureEntry("elder_tidecaller", "Elder Tidecaller", EntityType.ELDER_GUARDIAN, 45, 300, 4.0, 180.0, "DEEP_OCEAN", "", 80.0)
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

    private record SeaCreatureEntry(
            String id,
            String name,
            EntityType type,
            int minLevel,
            int weight,
            double mod,
            double baseHealth,
            String biomeTags,
            String mythicMob,
            double xp
    ) {
        boolean matchesBiome(Biome biome) {
            String biomeName = biome.name().toUpperCase(Locale.ROOT);
            for (String tag : biomeTags.split(",")) {
                String trimmed = tag.trim().toUpperCase(Locale.ROOT);
                if (!trimmed.isBlank() && biomeName.contains(trimmed)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record TreasureTier(String id, int weight, int minLevel, List<TreasureEntry> entries) {
    }

    private record TreasureEntry(String id, Material material, int minLevel, int amount, int weight, double xp, String tier) {
    }
}
