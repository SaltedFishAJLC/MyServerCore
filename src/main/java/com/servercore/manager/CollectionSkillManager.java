package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import com.servercore.enchant.EnchantStatResolver;
import dev.aurelium.auraskills.api.AuraSkillsBukkit;
import dev.aurelium.auraskills.api.event.skill.XpGainEvent;
import dev.aurelium.auraskills.api.skill.Skill;
import dev.aurelium.auraskills.api.skill.Skills;
import dev.aurelium.auraskills.api.source.XpSource;
import dev.aurelium.auraskills.api.source.type.BlockXpSource;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Rabbit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Handles foraging, farming, and excavation gathering mechanics.
 */
public class CollectionSkillManager implements Listener {

    private static final long PENDING_BREAK_TTL_MS = 1_500L;
    private static final int MAX_SWEEP_SCAN_BLOCKS = 768;
    private static final BlockFace[] SEARCH_FACES = {
            BlockFace.UP,
            BlockFace.DOWN,
            BlockFace.NORTH,
            BlockFace.SOUTH,
            BlockFace.EAST,
            BlockFace.WEST
    };

    private static CollectionSkillManager instance;

    private final ServerCorePlugin plugin;
    private final GlobalStatManager globalStats;
    private final Map<UUID, ArrayDeque<PendingBreak>> pendingBreaks = new ConcurrentHashMap<>();
    private final NamespacedKey toolFortuneKey;
    private final NamespacedKey collectionFortuneKey;
    private final NamespacedKey foragingFortuneKey;
    private final NamespacedKey farmingFortuneKey;
    private final NamespacedKey excavationFortuneKey;
    private final NamespacedKey toolSweepKey;
    private final NamespacedKey collectionSweepKey;
    private final NamespacedKey foragingSweepKey;
    private final NamespacedKey bountyKey;
    private final NamespacedKey overbloomKey;
    private final File gatheringLootFile;
    private volatile List<LootEntry> bountyLoot = List.of();
    private volatile List<LootEntry> overbloomLoot = List.of();
    private volatile List<ExcavationCreatureEntry> excavationCreatures = List.of();
    private volatile List<TreasureTier> excavationTreasureTiers = List.of();

    public CollectionSkillManager(ServerCorePlugin plugin, GlobalStatManager globalStats) {
        this.plugin = plugin;
        this.globalStats = globalStats;
        this.gatheringLootFile = new File(plugin.getDataFolder(), "gathering_loot.yml");
        this.toolFortuneKey = new NamespacedKey(plugin, "tool_fortune");
        this.collectionFortuneKey = new NamespacedKey(plugin, "collection_fortune");
        this.foragingFortuneKey = new NamespacedKey(plugin, "foraging_fortune");
        this.farmingFortuneKey = new NamespacedKey(plugin, "farming_fortune");
        this.excavationFortuneKey = new NamespacedKey(plugin, "excavation_fortune");
        this.toolSweepKey = new NamespacedKey(plugin, "tool_sweep");
        this.collectionSweepKey = new NamespacedKey(plugin, "collection_sweep");
        this.foragingSweepKey = new NamespacedKey(plugin, "foraging_sweep");
        this.bountyKey = new NamespacedKey(plugin, "bounty");
        this.overbloomKey = new NamespacedKey(plugin, "overbloom");
        instance = this;

        reloadLootTables();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public static CollectionSkillManager getInstance() {
        return instance;
    }

    public int reloadLootTables() {
        ensureGatheringLootFile();

        YamlConfiguration config = YamlConfiguration.loadConfiguration(gatheringLootFile);
        this.bountyLoot = readLootEntries(config.getConfigurationSection("bounty.entries"), "bounty", "min_foraging_level");
        this.overbloomLoot = readLootEntries(config.getConfigurationSection("overbloom.entries"), "overbloom", "min_farming_level");
        this.excavationCreatures = readExcavationCreatures(config.getConfigurationSection("excavation.creatures.entries"));
        this.excavationTreasureTiers = readTreasureTiers(config.getConfigurationSection("excavation.treasures"), "min_excavation_level");
        if (this.bountyLoot.isEmpty()) {
            this.bountyLoot = List.of(new LootEntry("heart_of_the_forest", "heart_of_the_forest", Material.GOLDEN_APPLE, 1, 1, 0, 0.0, "", List.of()));
        }
        if (this.overbloomLoot.isEmpty()) {
            this.overbloomLoot = List.of(new LootEntry("overgrown_essence", "overgrown_essence", Material.GLOW_BERRIES, 1, 1, 0, 0.0, "", List.of()));
        }
        if (this.excavationCreatures.isEmpty()) {
            this.excavationCreatures = defaultExcavationCreatures();
        }
        if (this.excavationTreasureTiers.isEmpty()) {
            this.excavationTreasureTiers = defaultExcavationTreasureTiers();
        }
        int loaded = this.bountyLoot.size() + this.overbloomLoot.size() + this.excavationCreatures.size()
                + this.excavationTreasureTiers.stream().mapToInt(tier -> tier.entries().size()).sum();
        plugin.getLogger().info("Loaded " + loaded + " gathering loot entr" + (loaded == 1 ? "y." : "ies.")
                + " (bounty=" + this.bountyLoot.size()
                + ", overbloom=" + this.overbloomLoot.size()
                + ", excavation_creatures=" + this.excavationCreatures.size()
                + ", excavation_tiers=" + this.excavationTreasureTiers.size() + ").");
        return loaded;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        CollectionCategory category = CollectionCategory.fromBlock(block);
        if (category == null) {
            return;
        }

        PendingBreak pending = new PendingBreak(
                player.getUniqueId(),
                block.getLocation(),
                block.getState(),
                block.getType(),
                category,
                isMatureCrop(block),
                event,
                System.currentTimeMillis()
        );
        rememberPendingBreak(pending);

        Bukkit.getScheduler().runTask(plugin, () -> processBreak(player, pending));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAuraXpGain(XpGainEvent event) {
        XpSource source = event.getSource();
        if (!(source instanceof BlockXpSource blockSource)) {
            return;
        }

        CollectionCategory category = CollectionCategory.fromSkill(event.getSkill());
        if (category == null) {
            return;
        }

        ArrayDeque<PendingBreak> playerBreaks = pendingBreaks.get(event.getPlayer().getUniqueId());
        if (playerBreaks == null || playerBreaks.isEmpty()) {
            return;
        }

        prunePendingBreaks(playerBreaks);
        String[] sourceBlocks = blockSource.getBlocks();
        long now = System.currentTimeMillis();
        for (Iterator<PendingBreak> iterator = playerBreaks.descendingIterator(); iterator.hasNext(); ) {
            PendingBreak pending = iterator.next();
            if (pending.category() != category || now - pending.createdAt() > PENDING_BREAK_TTL_MS) {
                continue;
            }
            if (!matchesSourceBlocks(pending.material(), sourceBlocks)) {
                continue;
            }
            pending.markAuraXpGranted(event.getAmount());
            return;
        }
    }

    public void processExcavationTracking(Player player, Block block) {
        AuraSkillsBridge bridge = AuraSkillsBridge.getInstance();
        int level = bridge == null ? 0 : bridge.getSkillLevel(player, Skills.EXCAVATION);
        double chance = Math.min(0.08, 0.01 + level * 0.0006);
        if (ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }

        Location spawnLocation = block.getLocation().add(0.5, 1.0, 0.5);
        Rabbit rabbit = (Rabbit) block.getWorld().spawnEntity(spawnLocation, EntityType.RABBIT);
        rabbit.customName(Component.text("Tracked Burrowling"));
        rabbit.setCustomNameVisible(true);
        rabbit.setRemoveWhenFarAway(false);
        rabbit.getScoreboardTags().add("servercore_excavation_tracking");
        rabbit.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, rabbit.getLocation().add(0.0, 0.4, 0.0), 16, 0.35, 0.35, 0.35, 0.01);
        rabbit.getWorld().playSound(rabbit.getLocation(), Sound.ENTITY_RABBIT_AMBIENT, 0.65f, 1.35f);

        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!rabbit.isValid() || !player.isOnline() || player.getWorld() != rabbit.getWorld()
                    || player.getLocation().distanceSquared(rabbit.getLocation()) > 32.0 * 32.0) {
                rabbit.remove();
                task.cancel();
            }
        }, 80L, 80L);
    }

    /**
     * x = equipment fortune + global fortune.
     * Drops = guaranteed 1 + floor(x / 100), plus (x % 100)% for one extra.
     */
    public int calculateFortuneDrops(Player player, double equipmentFortune) {
        double fortune = Math.max(0.0, equipmentFortune + (globalStats == null ? 0.0 : globalStats.getGlobalFortune(player)));
        int guaranteed = 1 + (int) Math.floor(fortune / 100.0);
        double remainderChance = (fortune % 100.0) / 100.0;
        if (ThreadLocalRandom.current().nextDouble() < remainderChance) {
            guaranteed++;
        }
        return Math.max(1, guaranteed);
    }

    private void processBreak(Player player, PendingBreak pending) {
        try {
            removePendingBreak(pending);
            if (!player.isOnline() || pending.event().isCancelled()) {
                return;
            }

            boolean legalGather = pending.matureCrop() || pending.auraXpGranted();
            if (!legalGather) {
                return;
            }

            ItemStack tool = player.getInventory().getItemInMainHand();
            int copies = calculateFortuneDrops(player, getEquipmentFortune(tool, pending.category()));
            dropExtraCopies(pending.state(), tool, player, pending.location(), Math.max(0, copies - 1));
            if (pending.category() == CollectionCategory.FARMING) {
                if (pending.matureCrop()) {
                    rollOverbloom(player, tool, pending.location());
                }
            } else if (pending.category() == CollectionCategory.FORAGING) {
                rollBounty(player, tool, pending.location());
            }

            int sweep = pending.category() == CollectionCategory.FORAGING ? getSweep(tool) : 0;
            if (sweep > 0) {
                sweepBlocks(player, pending, tool, sweep);
            }

            if (pending.category() == CollectionCategory.EXCAVATION) {
                if (!rollExcavationCreature(player, tool, pending)) {
                    rollExcavationTreasure(player, pending);
                }
            }
        } finally {
            ArrayDeque<PendingBreak> playerBreaks = pendingBreaks.get(pending.playerId());
            if (playerBreaks != null && playerBreaks.isEmpty()) {
                pendingBreaks.remove(pending.playerId());
            }
        }
    }

    private void sweepBlocks(Player player, PendingBreak origin, ItemStack tool, int limit) {
        Queue<Block> queue = new ArrayDeque<>();
        Set<String> visited = ConcurrentHashMap.newKeySet();
        Block originBlock = origin.location().getBlock();
        visited.add(blockKey(originBlock));

        for (BlockFace face : SEARCH_FACES) {
            Block neighbor = originBlock.getRelative(face);
            if (neighbor.getType() == origin.material()) {
                queue.add(neighbor);
                visited.add(blockKey(neighbor));
            }
        }

        int broken = 0;
        int scanned = 0;
        while (!queue.isEmpty() && broken < limit && scanned < MAX_SWEEP_SCAN_BLOCKS) {
            scanned++;
            Block current = queue.poll();
            if (current.getType() != origin.material()) {
                continue;
            }
            if (!isSweepTargetLegal(player, current, origin.category())) {
                continue;
            }

            BlockState state = current.getState();
            int copies = calculateFortuneDrops(player, getEquipmentFortune(tool, origin.category()));
            dropCopies(state, tool, player, current.getLocation(), copies);
            current.setType(Material.AIR, true);
            rollBounty(player, tool, current.getLocation());
            addRawGatheringXp(player, origin.category().skill(), origin.auraXpAmount());
            broken++;

            for (BlockFace face : SEARCH_FACES) {
                Block neighbor = current.getRelative(face);
                String key = blockKey(neighbor);
                if (visited.add(key) && neighbor.getType() == origin.material()) {
                    queue.add(neighbor);
                }
            }
        }

        if (broken > 0) {
            player.playSound(player.getLocation(), Sound.BLOCK_GRASS_BREAK, 0.45f, 1.2f);
        }
    }

    private boolean isSweepTargetLegal(Player player, Block block, CollectionCategory category) {
        return category == CollectionCategory.FORAGING
                && !isAuraPlacedBlock(block)
                && !isAuraLocationBlocked(player, block, category.skill());
    }

    private boolean isAuraLocationBlocked(Player player, Block block, Skills skill) {
        try {
            AuraSkillsBukkit bukkitApi = AuraSkillsBukkit.get();
            if (bukkitApi.getLocationManager().isXpGainBlocked(block.getLocation(), player, skill)) {
                return true;
            }
            return bukkitApi.getLocationManager().isPluginDisabled(block.getLocation(), player);
        } catch (IllegalStateException | NoClassDefFoundError exception) {
            return true;
        }
    }

    private boolean isAuraPlacedBlock(Block block) {
        try {
            AuraSkillsBukkit bukkitApi = AuraSkillsBukkit.get();
            return bukkitApi.getRegions().isPlacedBlock(block);
        } catch (IllegalStateException | NoClassDefFoundError exception) {
            return true;
        }
    }

    private void rollBounty(Player player, ItemStack tool, Location location) {
        double chance = Math.min(1.0, Math.max(0.0, readToolStat(tool, bountyKey) / 100.0));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (!rollRareGatheringDrop(player, chance, random)) {
            return;
        }

        AuraSkillsBridge bridge = AuraSkillsBridge.getInstance();
        int level = bridge == null ? 0 : bridge.getSkillLevel(player, Skills.FORAGING);
        LootEntry entry = rollLootEntry(bountyLoot, level);
        if (entry == null) {
            return;
        }

        ItemStack reward = createLootItem(entry);
        if (reward == null || reward.getType().isAir()) {
            return;
        }

        location.getWorld().dropItemNaturally(location.clone().add(0.5, 0.35, 0.5), reward);
        if (entry.xp() > 0.0) {
            addGatheringXp(player, Skills.FORAGING, entry.xp());
        }
        location.getWorld().spawnParticle(Particle.COMPOSTER, location.clone().add(0.5, 0.75, 0.5), 10, 0.35, 0.35, 0.35, 0.02);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.55f, 1.45f);
    }

    private void rollOverbloom(Player player, ItemStack tool, Location location) {
        double chance = Math.min(1.0, Math.max(0.0, readToolStat(tool, overbloomKey) / 100.0));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (!rollRareGatheringDrop(player, chance, random)) {
            return;
        }

        AuraSkillsBridge bridge = AuraSkillsBridge.getInstance();
        int level = bridge == null ? 0 : bridge.getSkillLevel(player, Skills.FARMING);
        LootEntry entry = rollLootEntry(overbloomLoot, level);
        if (entry == null) {
            return;
        }

        ItemStack reward = createLootItem(entry);
        if (reward == null || reward.getType().isAir()) {
            return;
        }

        location.getWorld().dropItemNaturally(location.clone().add(0.5, 0.35, 0.5), reward);
        if (entry.xp() > 0.0) {
            addGatheringXp(player, Skills.FARMING, entry.xp());
        }
        location.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, location.clone().add(0.5, 0.8, 0.5), 12, 0.35, 0.35, 0.35, 0.02);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.55f, 1.55f);
    }

    private boolean rollExcavationCreature(Player player, ItemStack tool, PendingBreak pending) {
        AuraSkillsBridge bridge = AuraSkillsBridge.getInstance();
        int level = bridge == null ? 0 : bridge.getSkillLevel(player, Skills.EXCAVATION);
        double chance = Math.min(0.08, 0.01 + level * 0.0006);
        if (ThreadLocalRandom.current().nextDouble() >= chance) {
            return false;
        }

        ExcavationCreatureEntry entry = rollExcavationCreatureEntry(level, pending.material());
        if (entry == null) {
            return false;
        }

        Location spawnLocation = pending.location().clone().add(0.5, 1.0, 0.5);
        LivingEntity entity = entry.mythicMob().isBlank() ? null : spawnMythicMob(entry.mythicMob(), spawnLocation);
        if (entity == null) {
            entity = (LivingEntity) spawnLocation.getWorld().spawnEntity(spawnLocation, entry.type());
        }

        entity.customName(Component.text(entry.name()));
        entity.setCustomNameVisible(true);
        entity.getScoreboardTags().add("servercore_excavation_creature");
        setAttribute(entity, Attribute.GENERIC_MAX_HEALTH, entry.baseHealth() + Math.max(1, level) * entry.mod() * 2.0);
        setAttribute(entity, Attribute.GENERIC_ATTACK_DAMAGE, 2.0 + Math.max(1, level) * entry.mod() * 0.12);
        AttributeInstance maxHealth = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHealth != null) {
            entity.setHealth(maxHealth.getValue());
        }

        PDCManager pdc = PDCManager.getInstance();
        if (pdc != null) {
            entity.getPersistentDataContainer().set(pdc.KEY_CUSTOM_MOB_ID, PersistentDataType.STRING, "excavation_creature_" + entry.id());
            entity.getPersistentDataContainer().set(pdc.KEY_MOB_POWER_LEVEL, PersistentDataType.INTEGER, Math.max(1, level + entry.minLevel()));
            entity.getPersistentDataContainer().set(pdc.KEY_MOB_SCALING_MOD, PersistentDataType.DOUBLE, entry.mod());
            entity.getPersistentDataContainer().set(pdc.KEY_MOB_TAGS, PersistentDataType.STRING, "earth,excavation");
        }

        addGatheringXp(player, Skills.EXCAVATION, entry.xp());
        spawnLocation.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, spawnLocation.clone().add(0.0, 0.4, 0.0), 16, 0.35, 0.35, 0.35, 0.01);
        spawnLocation.getWorld().playSound(spawnLocation, Sound.ENTITY_RABBIT_AMBIENT, 0.65f, 1.35f);
        return true;
    }

    private ExcavationCreatureEntry rollExcavationCreatureEntry(int level, Material sourceMaterial) {
        List<ExcavationCreatureEntry> eligible = excavationCreatures.stream()
                .filter(entry -> level >= entry.minLevel())
                .filter(entry -> entry.matchesSource(sourceMaterial))
                .toList();
        int totalWeight = eligible.stream().mapToInt(ExcavationCreatureEntry::weight).sum();
        if (totalWeight <= 0) {
            return null;
        }

        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
        for (ExcavationCreatureEntry entry : eligible) {
            roll -= entry.weight();
            if (roll < 0) {
                return entry;
            }
        }
        return eligible.isEmpty() ? null : eligible.getFirst();
    }

    private LootEntry rollLootEntry(List<LootEntry> entries, int level) {
        int totalWeight = entries.stream()
                .filter(entry -> level >= entry.minLevel())
                .mapToInt(LootEntry::weight)
                .sum();
        if (totalWeight <= 0) {
            return null;
        }

        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
        for (LootEntry entry : entries) {
            if (level < entry.minLevel()) {
                continue;
            }
            roll -= entry.weight();
            if (roll < 0) {
                return entry;
            }
        }
        return null;
    }

    private ItemStack createLootItem(LootEntry entry) {
        CustomItemRegistry registry = CustomItemRegistry.getInstance();
        ItemStack custom = registry == null || entry.customItemId().isBlank()
                ? null
                : registry.createItem(entry.customItemId(), entry.amount());
        if (custom != null) {
            return custom;
        }
        return new ItemStack(entry.fallbackMaterial(), Math.max(1, Math.min(entry.amount(), entry.fallbackMaterial().getMaxStackSize())));
    }

    private void rollExcavationTreasure(Player player, PendingBreak pending) {
        AuraSkillsBridge bridge = AuraSkillsBridge.getInstance();
        int level = bridge == null ? 0 : bridge.getSkillLevel(player, Skills.EXCAVATION);
        double chance = Math.min(0.05, 0.006 + level * 0.00025);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (!rollRareGatheringDrop(player, chance, random)) {
            return;
        }

        TreasureTier tier = rollTreasureTier(excavationTreasureTiers, level);
        if (tier == null) {
            return;
        }

        List<LootEntry> eligible = tier.entries().stream()
                .filter(entry -> level >= entry.minLevel())
                .filter(entry -> entry.matchesSource(pending.material()))
                .toList();
        LootEntry entry = rollLootEntry(eligible, level);
        if (entry == null) {
            return;
        }

        ItemStack reward = createLootItem(entry);
        pending.location().getWorld().dropItemNaturally(pending.location().clone().add(0.5, 0.35, 0.5), reward);
        addGatheringXp(player, Skills.EXCAVATION, entry.xp());
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.55f, 1.45f);
        player.sendActionBar(Component.text("Excavation treasure unearthed: " + tier.id() + "."));
    }

    private boolean rollRareGatheringDrop(Player player, double baseChance, ThreadLocalRandom random) {
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

    private ItemStack createCustomOrMaterial(String itemId, Material fallback, int amount) {
        CustomItemRegistry registry = CustomItemRegistry.getInstance();
        ItemStack custom = registry == null ? null : registry.createItem(itemId, amount);
        return custom == null ? new ItemStack(fallback, amount) : custom;
    }

    private TreasureTier rollTreasureTier(List<TreasureTier> tiers, int level) {
        List<TreasureTier> eligible = tiers.stream()
                .filter(tier -> level >= tier.minLevel())
                .filter(tier -> !tier.entries().isEmpty())
                .toList();
        int totalWeight = eligible.stream().mapToInt(TreasureTier::weight).sum();
        if (totalWeight <= 0) {
            return null;
        }

        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
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

            Object abstractEntity = activeMob.getClass().getMethod("getEntity").invoke(activeMob);
            Method getBukkitEntity = abstractEntity.getClass().getMethod("getBukkitEntity");
            Object bukkitEntity = getBukkitEntity.invoke(abstractEntity);
            return bukkitEntity instanceof LivingEntity livingEntity ? livingEntity : null;
        } catch (ReflectiveOperationException | LinkageError exception) {
            plugin.getLogger().fine("Could not spawn MythicMob '" + mythicMob + "': " + exception.getMessage());
            return null;
        }
    }

    private void setAttribute(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(Math.max(0.0, value));
        }
    }

    private void addGatheringXp(Player player, Skills skill, double amount) {
        AuraSkillsBridge bridge = AuraSkillsBridge.getInstance();
        if (bridge != null) {
            bridge.addSkillXp(player, skill, amount);
        }
    }

    private void addRawGatheringXp(Player player, Skills skill, double amount) {
        AuraSkillsBridge bridge = AuraSkillsBridge.getInstance();
        if (bridge != null) {
            bridge.addSkillXpRaw(player, skill, amount);
        }
    }

    private List<LootEntry> readLootEntries(ConfigurationSection section, String tableName, String minLevelKey) {
        List<LootEntry> entries = new ArrayList<>();
        if (section == null) {
            return entries;
        }

        for (String id : section.getKeys(false)) {
            ConfigurationSection entrySection = section.getConfigurationSection(id);
            if (entrySection == null) {
                continue;
            }

            String customItemId = entrySection.getString("custom_item", entrySection.getString("item", id));
            String fallbackRaw = entrySection.getString("fallback_material", entrySection.getString("material", "EMERALD"));
            Material fallback = Material.matchMaterial(fallbackRaw == null ? "" : fallbackRaw);
            int weight = Math.max(0, entrySection.getInt("weight", 1));
            if (fallback == null || fallback.isAir() || weight <= 0) {
                plugin.getLogger().warning("Invalid " + tableName + " loot entry: " + id);
                continue;
            }

            int amount = Math.max(1, entrySection.getInt("amount", 1));
            int minLevel = Math.max(0, entrySection.getInt(minLevelKey, entrySection.getInt("min_level", 0)));
            double xp = Math.max(0.0, entrySection.getDouble("xp", 0.0));
            entries.add(new LootEntry(id, customItemId == null ? "" : customItemId.trim(), fallback, amount, weight,
                    minLevel, xp, tableName, entrySection.getStringList("sources")));
        }
        return List.copyOf(entries);
    }

    private List<TreasureTier> readTreasureTiers(ConfigurationSection section, String minLevelKey) {
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
                    Math.max(0, tier.getInt(minLevelKey, tier.getInt("min_level", 0))),
                    readLootEntries(tier.getConfigurationSection("entries"), tierName, minLevelKey)
            ));
        }
        return tiers.stream().filter(tier -> tier.weight() > 0 && !tier.entries().isEmpty()).toList();
    }

    private List<ExcavationCreatureEntry> readExcavationCreatures(ConfigurationSection section) {
        List<ExcavationCreatureEntry> entries = new ArrayList<>();
        if (section == null) {
            return entries;
        }

        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) {
                continue;
            }

            EntityType type = parseEntityType(entry.getString("entity_type", "RABBIT"), EntityType.RABBIT);
            entries.add(new ExcavationCreatureEntry(
                    id,
                    entry.getString("name", id),
                    type,
                    entry.getString("mythic_mob", ""),
                    Math.max(0, entry.getInt("min_excavation_level", entry.getInt("min_level", 0))),
                    Math.max(0, entry.getInt("weight", 1)),
                    Math.max(0.1, entry.getDouble("mod", 1.0)),
                    Math.max(1.0, entry.getDouble("base_health", 16.0)),
                    Math.max(0.0, entry.getDouble("xp", 0.0)),
                    entry.getStringList("sources")
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

        try {
            YamlConfiguration config = new YamlConfiguration();
            ConfigurationSection bountyEntries = config.createSection("bounty.entries");
            ConfigurationSection bountyDefault = bountyEntries.createSection("heart_of_the_forest");
            bountyDefault.set("custom_item", "heart_of_the_forest");
            bountyDefault.set("fallback_material", "GOLDEN_APPLE");
            bountyDefault.set("amount", 1);
            bountyDefault.set("weight", 1);
            bountyDefault.set("min_foraging_level", 0);

            ConfigurationSection entries = config.createSection("overbloom.entries");
            ConfigurationSection defaultEntry = entries.createSection("overgrown_essence");
            defaultEntry.set("custom_item", "overgrown_essence");
            defaultEntry.set("fallback_material", "GLOW_BERRIES");
            defaultEntry.set("amount", 1);
            defaultEntry.set("weight", 1);
            defaultEntry.set("min_farming_level", 0);

            ConfigurationSection excavationCreatures = config.createSection("excavation.creatures.entries");
            ConfigurationSection burrowling = excavationCreatures.createSection("burrowling");
            burrowling.set("entity_type", "RABBIT");
            burrowling.set("name", "Tracked Burrowling");
            burrowling.set("amount", 1);
            burrowling.set("weight", 100);
            burrowling.set("min_excavation_level", 0);
            burrowling.set("base_health", 18);
            burrowling.set("mod", 1.0);
            burrowling.set("xp", 8);

            ConfigurationSection excavationTreasures = config.createSection("excavation.treasures");
            ConfigurationSection rare = excavationTreasures.createSection("rare");
            rare.set("weight", 80);
            rare.set("min_excavation_level", 0);
            ConfigurationSection rareEntries = rare.createSection("entries");
            ConfigurationSection buriedRelic = rareEntries.createSection("buried_relic");
            buriedRelic.set("custom_item", "buried_relic");
            buriedRelic.set("fallback_material", "AMETHYST_SHARD");
            buriedRelic.set("amount", 1);
            buriedRelic.set("weight", 100);
            buriedRelic.set("xp", 10);

            ConfigurationSection epic = excavationTreasures.createSection("epic");
            epic.set("weight", 18);
            epic.set("min_excavation_level", 20);
            ConfigurationSection epicEntries = epic.createSection("entries");
            ConfigurationSection ancientCoin = epicEntries.createSection("ancient_coin");
            ancientCoin.set("custom_item", "ancient_coin");
            ancientCoin.set("fallback_material", "GOLD_INGOT");
            ancientCoin.set("amount", 1);
            ancientCoin.set("weight", 100);
            ancientCoin.set("xp", 25);

            ConfigurationSection legendary = excavationTreasures.createSection("legendary");
            legendary.set("weight", 2);
            legendary.set("min_excavation_level", 50);
            ConfigurationSection legendaryEntries = legendary.createSection("entries");
            ConfigurationSection buriedCrown = legendaryEntries.createSection("buried_crown");
            buriedCrown.set("custom_item", "buried_crown");
            buriedCrown.set("fallback_material", "HEART_OF_THE_SEA");
            buriedCrown.set("amount", 1);
            buriedCrown.set("weight", 100);
            buriedCrown.set("xp", 80);
            config.save(gatheringLootFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not create gathering_loot.yml: " + exception.getMessage());
        }
    }

    private List<ExcavationCreatureEntry> defaultExcavationCreatures() {
        return List.of(
                new ExcavationCreatureEntry("burrowling", "Tracked Burrowling", EntityType.RABBIT, "", 0, 100, 1.0, 18.0, 8.0, List.of()),
                new ExcavationCreatureEntry("sand_wraith", "Sand Wraith", EntityType.HUSK, "", 20, 30, 1.6, 42.0, 24.0, List.of("sand", "red_sand")),
                new ExcavationCreatureEntry("relic_guardian", "Relic Guardian", EntityType.SKELETON, "", 45, 8, 2.8, 90.0, 70.0, List.of())
        );
    }

    private List<TreasureTier> defaultExcavationTreasureTiers() {
        return List.of(
                new TreasureTier("rare", 80, 0, List.of(
                        new LootEntry("buried_relic", "buried_relic", Material.AMETHYST_SHARD, 1, 100, 0, 10.0, "rare", List.of())
                )),
                new TreasureTier("epic", 18, 20, List.of(
                        new LootEntry("ancient_coin", "ancient_coin", Material.GOLD_INGOT, 1, 100, 20, 25.0, "epic", List.of())
                )),
                new TreasureTier("legendary", 2, 50, List.of(
                        new LootEntry("buried_crown", "buried_crown", Material.HEART_OF_THE_SEA, 1, 100, 50, 80.0, "legendary", List.of())
                ))
        );
    }

    private double getEquipmentFortune(ItemStack tool, CollectionCategory category) {
        return readToolStat(tool, toolFortuneKey)
                + readToolStat(tool, collectionFortuneKey)
                + switch (category) {
                    case FORAGING -> readToolStat(tool, foragingFortuneKey);
                    case FARMING -> readToolStat(tool, farmingFortuneKey);
                    case EXCAVATION -> readToolStat(tool, excavationFortuneKey);
                };
    }

    private int getSweep(ItemStack tool) {
        double value = readToolStat(tool, toolSweepKey)
                + readToolStat(tool, collectionSweepKey)
                + readToolStat(tool, foragingSweepKey);
        if (value <= 0.0 && tool != null && tool.getType().name().endsWith("_AXE")) {
            value = tool.getEnchantmentLevel(Enchantment.EFFICIENCY);
        }
        return Math.max(0, (int) Math.floor(value));
    }

    private double readToolStat(ItemStack item, NamespacedKey key) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return 0.0;
        }
        PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();
        double value = container.getOrDefault(key, PersistentDataType.DOUBLE, 0.0);
        EnchantStatResolver resolver = EnchantStatResolver.getInstance();
        return value + (resolver == null ? 0.0 : resolver.resolveNumeric(item, key.getKey()));
    }

    private void dropExtraCopies(BlockState state, ItemStack tool, Player player, Location location, int copies) {
        if (copies <= 0) {
            return;
        }
        dropCopies(state, tool, player, location, copies);
    }

    private void dropCopies(BlockState state, ItemStack tool, Player player, Location location, int copies) {
        Collection<ItemStack> drops = getDrops(state, tool, player);
        if (drops.isEmpty()) {
            return;
        }

        Location dropLocation = location.clone().add(0.5, 0.35, 0.5);
        for (int i = 0; i < copies; i++) {
            for (ItemStack drop : drops) {
                if (drop == null || drop.getType().isAir()) {
                    continue;
                }
                state.getWorld().dropItemNaturally(dropLocation, drop.clone());
            }
        }
    }

    private Collection<ItemStack> getDrops(BlockState state, ItemStack tool, Player player) {
        try {
            return state.getDrops(tool, player);
        } catch (NoSuchMethodError error) {
            return tool == null ? state.getDrops() : state.getDrops(tool);
        }
    }

    private void rememberPendingBreak(PendingBreak pending) {
        ArrayDeque<PendingBreak> playerBreaks = pendingBreaks.computeIfAbsent(pending.playerId(), ignored -> new ArrayDeque<>());
        prunePendingBreaks(playerBreaks);
        playerBreaks.addLast(pending);
    }

    private void removePendingBreak(PendingBreak pending) {
        ArrayDeque<PendingBreak> playerBreaks = pendingBreaks.get(pending.playerId());
        if (playerBreaks != null) {
            playerBreaks.remove(pending);
        }
    }

    private void prunePendingBreaks(ArrayDeque<PendingBreak> playerBreaks) {
        long now = System.currentTimeMillis();
        while (!playerBreaks.isEmpty() && now - playerBreaks.peekFirst().createdAt() > PENDING_BREAK_TTL_MS) {
            playerBreaks.removeFirst();
        }
    }

    private boolean matchesSourceBlocks(Material material, String[] sourceBlocks) {
        String materialName = material.name().toLowerCase(Locale.ROOT);
        for (String sourceBlock : sourceBlocks) {
            if (materialName.equals(normalizeMaterialName(sourceBlock))) {
                return true;
            }
        }
        return false;
    }

    private String normalizeMaterialName(String raw) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        int namespaceSplit = normalized.indexOf(':');
        return namespaceSplit >= 0 ? normalized.substring(namespaceSplit + 1) : normalized;
    }

    private boolean isMatureCrop(Block block) {
        if (block.getBlockData() instanceof Ageable ageable) {
            return ageable.getAge() >= ageable.getMaximumAge();
        }
        return switch (block.getType()) {
            case PUMPKIN, MELON, CACTUS, SUGAR_CANE, BAMBOO, BROWN_MUSHROOM, RED_MUSHROOM, KELP, KELP_PLANT -> true;
            default -> false;
        };
    }

    private String blockKey(Block block) {
        Location location = block.getLocation();
        return location.getWorld().getUID() + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    private record LootEntry(
            String id,
            String customItemId,
            Material fallbackMaterial,
            int amount,
            int weight,
            int minLevel,
            double xp,
            String tier,
            List<String> sources
    ) {
        boolean matchesSource(Material material) {
            if (sources == null || sources.isEmpty()) {
                return true;
            }
            String materialName = material.name().toLowerCase(Locale.ROOT);
            for (String source : sources) {
                if (sourceMatches(materialName, source)) {
                    return true;
                }
            }
            return false;
        }
    }

    private record TreasureTier(String id, int weight, int minLevel, List<LootEntry> entries) {
    }

    private record ExcavationCreatureEntry(
            String id,
            String name,
            EntityType type,
            String mythicMob,
            int minLevel,
            int weight,
            double mod,
            double baseHealth,
            double xp,
            List<String> sources
    ) {
        boolean matchesSource(Material material) {
            if (sources == null || sources.isEmpty()) {
                return true;
            }
            String materialName = material.name().toLowerCase(Locale.ROOT);
            for (String source : sources) {
                if (sourceMatches(materialName, source)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static boolean sourceMatches(String materialName, String rawSource) {
        if (rawSource == null || rawSource.isBlank()) {
            return false;
        }
        String source = rawSource.trim().toLowerCase(Locale.ROOT);
        if (materialName.equals(source) || materialName.contains(source)) {
            return true;
        }
        return switch (source) {
            case "dirts", "dirt" -> materialName.contains("dirt") || materialName.equals("grass_block")
                    || materialName.equals("podzol") || materialName.equals("mycelium") || materialName.equals("mud");
            case "sand" -> materialName.equals("sand");
            case "red_sand" -> materialName.equals("red_sand");
            case "sands" -> materialName.endsWith("sand");
            case "gravel" -> materialName.equals("gravel");
            case "clay" -> materialName.equals("clay");
            case "mud" -> materialName.contains("mud");
            case "soul_sand", "soul_soil" -> materialName.equals(source);
            case "soul" -> materialName.equals("soul_sand") || materialName.equals("soul_soil");
            default -> false;
        };
    }

    private static final class PendingBreak {
        private final UUID playerId;
        private final Location location;
        private final BlockState state;
        private final Material material;
        private final CollectionCategory category;
        private final boolean matureCrop;
        private final BlockBreakEvent event;
        private final long createdAt;
        private boolean auraXpGranted;
        private double auraXpAmount;

        private PendingBreak(UUID playerId, Location location, BlockState state, Material material,
                             CollectionCategory category, boolean matureCrop, BlockBreakEvent event, long createdAt) {
            this.playerId = playerId;
            this.location = location;
            this.state = state;
            this.material = material;
            this.category = category;
            this.matureCrop = matureCrop;
            this.event = event;
            this.createdAt = createdAt;
        }

        UUID playerId() {
            return playerId;
        }

        Location location() {
            return location;
        }

        BlockState state() {
            return state;
        }

        Material material() {
            return material;
        }

        CollectionCategory category() {
            return category;
        }

        boolean matureCrop() {
            return matureCrop;
        }

        BlockBreakEvent event() {
            return event;
        }

        long createdAt() {
            return createdAt;
        }

        boolean auraXpGranted() {
            return auraXpGranted;
        }

        double auraXpAmount() {
            return auraXpAmount;
        }

        void markAuraXpGranted(double amount) {
            auraXpGranted = true;
            auraXpAmount = Math.max(0.0, amount);
        }
    }

    private enum CollectionCategory {
        FORAGING(Skills.FORAGING),
        FARMING(Skills.FARMING),
        EXCAVATION(Skills.EXCAVATION);

        private static final Set<Material> EXCAVATION_BLOCKS = EnumSet.of(
                Material.DIRT,
                Material.COARSE_DIRT,
                Material.ROOTED_DIRT,
                Material.GRASS_BLOCK,
                Material.PODZOL,
                Material.MYCELIUM,
                Material.SAND,
                Material.RED_SAND,
                Material.GRAVEL,
                Material.CLAY,
                Material.MUD,
                Material.MUDDY_MANGROVE_ROOTS,
                Material.SOUL_SAND,
                Material.SOUL_SOIL
        );

        private final Skills skill;

        CollectionCategory(Skills skill) {
            this.skill = skill;
        }

        Skills skill() {
            return skill;
        }

        static CollectionCategory fromSkill(Skill skill) {
            if (skill == null) {
                return null;
            }
            if (skill.getId().equals(Skills.FORAGING.getId())) {
                return FORAGING;
            }
            if (skill.getId().equals(Skills.FARMING.getId())) {
                return FARMING;
            }
            if (skill.getId().equals(Skills.EXCAVATION.getId())) {
                return EXCAVATION;
            }
            return null;
        }

        static CollectionCategory fromBlock(Block block) {
            Material material = block.getType();
            String name = material.name();
            if (isForagingMaterial(name)) {
                return FORAGING;
            }
            if (isFarmingMaterial(material)) {
                return FARMING;
            }
            if (EXCAVATION_BLOCKS.contains(material)) {
                return EXCAVATION;
            }
            return null;
        }

        private static boolean isForagingMaterial(String name) {
            return name.endsWith("_LOG")
                    || name.endsWith("_WOOD")
                    || name.endsWith("_STEM")
                    || name.endsWith("_HYPHAE")
                    || name.endsWith("_LEAVES")
                    || name.endsWith("_MUSHROOM_BLOCK")
                    || name.endsWith("_WART_BLOCK")
                    || name.equals("MUSHROOM_STEM")
                    || name.equals("MANGROVE_ROOTS")
                    || name.equals("MOSS_BLOCK")
                    || name.equals("PALE_MOSS_BLOCK")
                    || name.equals("AZALEA")
                    || name.equals("FLOWERING_AZALEA");
        }

        private static boolean isFarmingMaterial(Material material) {
            return switch (material) {
                case WHEAT,
                     POTATOES,
                     CARROTS,
                     BEETROOTS,
                     NETHER_WART,
                     PUMPKIN,
                     MELON,
                     SUGAR_CANE,
                     BAMBOO,
                     COCOA,
                     CACTUS,
                     BROWN_MUSHROOM,
                     RED_MUSHROOM,
                     KELP,
                     KELP_PLANT,
                     SEA_PICKLE,
                     SWEET_BERRY_BUSH,
                     CAVE_VINES,
                     CAVE_VINES_PLANT,
                     TORCHFLOWER,
                     PITCHER_CROP -> true;
                default -> false;
            };
        }
    }
}
