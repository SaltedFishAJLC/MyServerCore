package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import com.servercore.enchant.EnchantStatResolver;
import dev.aurelium.auraskills.api.AuraSkillsBukkit;
import dev.aurelium.auraskills.api.event.skill.XpGainEvent;
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
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
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
 * Mining speed, custom ore, breaking power, spread, and purity engine.
 */
public class MiningManager implements Listener {

    private static final long PENDING_BREAK_TTL_MS = 1_500L;
    private static final int MAX_SPREAD_SCAN_BLOCKS = 768;
    private static final double MINING_SPEED_POINTS_PER_ATTRIBUTE = 100.0;
    private static final BlockFace[] SEARCH_FACES = {
            BlockFace.UP,
            BlockFace.DOWN,
            BlockFace.NORTH,
            BlockFace.SOUTH,
            BlockFace.EAST,
            BlockFace.WEST
    };

    private static MiningManager instance;

    private final ServerCorePlugin plugin;
    private final GlobalStatManager globalStats;
    private final NamespacedKey miningSpeedModifierKey;
    private final NamespacedKey toolFortuneKey;
    private final NamespacedKey miningFortuneKey;
    private final NamespacedKey toolSpreadKey;
    private final NamespacedKey miningSpreadKey;
    private final NamespacedKey breakingPowerKey;
    private final NamespacedKey miningSpeedKey;
    private final NamespacedKey purityKey;
    private final NamespacedKey miningPurityKey;
    private final File gatheringLootFile;
    private final Map<Material, CustomBlock> materialOres = new EnumMap<>(Material.class);
    private final Map<String, CustomBlock> locationOres = new ConcurrentHashMap<>();
    private final Map<UUID, ArrayDeque<PendingOreBreak>> pendingBreaks = new ConcurrentHashMap<>();
    private volatile List<MiningRareEntry> miningRareLoot = List.of();
    private BukkitTask miningSpeedTask;

    public MiningManager(ServerCorePlugin plugin, GlobalStatManager globalStats) {
        this.plugin = plugin;
        this.globalStats = globalStats;
        this.miningSpeedModifierKey = new NamespacedKey(plugin, "mining_speed");
        this.toolFortuneKey = new NamespacedKey(plugin, "tool_fortune");
        this.miningFortuneKey = new NamespacedKey(plugin, "mining_fortune");
        this.toolSpreadKey = new NamespacedKey(plugin, "tool_spread");
        this.miningSpreadKey = new NamespacedKey(plugin, "mining_spread");
        this.breakingPowerKey = new NamespacedKey(plugin, "breaking_power");
        this.miningSpeedKey = new NamespacedKey(plugin, "tool_mining_speed");
        this.purityKey = new NamespacedKey(plugin, "purity");
        this.miningPurityKey = new NamespacedKey(plugin, "mining_purity");
        this.gatheringLootFile = new File(plugin.getDataFolder(), "gathering_loot.yml");
        instance = this;

        registerDefaultOres();
        reloadLootTables();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        this.miningSpeedTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickMiningSpeed, 20L, 40L);
    }

    public static MiningManager getInstance() {
        return instance;
    }

    public void stop() {
        if (miningSpeedTask != null) {
            miningSpeedTask.cancel();
            miningSpeedTask = null;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyMiningSpeedAttribute(player, 0.0);
        }
    }

    public int reloadLootTables() {
        ensureGatheringLootFile();

        YamlConfiguration config = YamlConfiguration.loadConfiguration(gatheringLootFile);
        this.miningRareLoot = readMiningRareEntries(config.getConfigurationSection("mining.entries"));
        if (this.miningRareLoot.isEmpty()) {
            this.miningRareLoot = defaultMiningRareLoot();
        }

        plugin.getLogger().info("Loaded " + this.miningRareLoot.size() + " mining rare loot entr"
                + (this.miningRareLoot.size() == 1 ? "y." : "ies."));
        return this.miningRareLoot.size();
    }

    public enum OreType {
        NORMAL, MAGIC_METAL, GEMSTONE, MAGIC_CRYSTAL
    }

    public interface CustomBlock {
        String id();

        OreType oreType();

        int breakingPower();

        Material displayMaterial();

        default Material dropMaterial() {
            return displayMaterial();
        }
    }

    public void registerCustomOreMaterial(String id, Material material, OreType oreType, int breakingPower, Material dropMaterial) {
        if (material == null || material.isAir() || oreType == null) {
            return;
        }
        materialOres.put(material, new RegisteredCustomBlock(id, oreType, Math.max(0, breakingPower), material,
                dropMaterial == null ? material : dropMaterial));
    }

    public void registerCustomOre(Location location, CustomBlock customBlock) {
        if (location == null || customBlock == null || location.getWorld() == null) {
            return;
        }
        locationOres.put(locationKey(location), customBlock);
    }

    public void unregisterCustomOre(Location location) {
        if (location != null && location.getWorld() != null) {
            locationOres.remove(locationKey(location));
        }
    }

    public void applyMiningSpeedAttribute(Player player, double speedValue) {
        AttributeInstance attribute = player.getAttribute(Attribute.PLAYER_BLOCK_BREAK_SPEED);
        if (attribute == null) {
            return;
        }

        for (AttributeModifier modifier : new ArrayList<>(attribute.getModifiers())) {
            if (modifier.getKey().equals(miningSpeedModifierKey)) {
                attribute.removeModifier(modifier);
            }
        }

        if (Math.abs(speedValue) > 0.0001) {
            attribute.addModifier(new AttributeModifier(miningSpeedModifierKey, speedValue, AttributeModifier.Operation.ADD_NUMBER));
        }
    }

    public boolean checkBreakingPower(ItemStack tool, Block block) {
        CustomBlock customBlock = getCustomBlock(block);
        if (customBlock == null) {
            return true;
        }
        return getBreakingPower(tool) >= customBlock.breakingPower();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onOreBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        CustomBlock customBlock = getCustomBlock(block);
        if (customBlock == null) {
            return;
        }

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!checkBreakingPower(tool, block)) {
            event.setCancelled(true);
            player.sendActionBar(Component.text("Breaking Power too low."));
            player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.35f, 1.8f);
            return;
        }

        PendingOreBreak pending = new PendingOreBreak(
                player.getUniqueId(),
                block.getLocation(),
                block.getState(),
                block.getType(),
                customBlock,
                event,
                System.currentTimeMillis()
        );
        rememberPendingBreak(pending);
        Bukkit.getScheduler().runTask(plugin, () -> processOreBreak(player, pending));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAuraXpGain(XpGainEvent event) {
        if (!event.getSkill().getId().equals(Skills.MINING.getId())) {
            return;
        }
        XpSource source = event.getSource();
        if (!(source instanceof BlockXpSource blockSource)) {
            return;
        }

        ArrayDeque<PendingOreBreak> playerBreaks = pendingBreaks.get(event.getPlayer().getUniqueId());
        if (playerBreaks == null || playerBreaks.isEmpty()) {
            return;
        }

        prunePendingBreaks(playerBreaks);
        long now = System.currentTimeMillis();
        for (Iterator<PendingOreBreak> iterator = playerBreaks.descendingIterator(); iterator.hasNext(); ) {
            PendingOreBreak pending = iterator.next();
            if (now - pending.createdAt() > PENDING_BREAK_TTL_MS) {
                continue;
            }
            if (!matchesSourceBlocks(pending.material(), blockSource.getBlocks())) {
                continue;
            }
            pending.markAuraXpGranted(event.getAmount());
            return;
        }
    }

    private void processOreBreak(Player player, PendingOreBreak pending) {
        try {
            removePendingBreak(pending);
            if (!player.isOnline() || pending.event().isCancelled() || !pending.auraXpGranted()) {
                return;
            }

            ItemStack tool = player.getInventory().getItemInMainHand();
            int copies = calculateMiningCopies(player, tool, pending.customBlock());
            dropExtraCopies(pending.state(), pending.customBlock(), tool, player, pending.location(), Math.max(0, copies - 1));
            rollMiningRare(player, pending.customBlock(), pending.location());

            int spread = getMiningSpread(tool);
            if (spread > 0 && pending.customBlock().oreType() != OreType.MAGIC_CRYSTAL) {
                spreadOres(player, pending, tool, spread);
            }
        } finally {
            ArrayDeque<PendingOreBreak> playerBreaks = pendingBreaks.get(pending.playerId());
            if (playerBreaks != null && playerBreaks.isEmpty()) {
                pendingBreaks.remove(pending.playerId());
            }
        }
    }

    private void spreadOres(Player player, PendingOreBreak origin, ItemStack tool, int limit) {
        Queue<Block> queue = new ArrayDeque<>();
        java.util.Set<String> visited = ConcurrentHashMap.newKeySet();
        Block originBlock = origin.location().getBlock();
        visited.add(blockKey(originBlock));

        for (BlockFace face : SEARCH_FACES) {
            Block neighbor = originBlock.getRelative(face);
            if (isSameOre(origin.customBlock(), neighbor)) {
                queue.add(neighbor);
                visited.add(blockKey(neighbor));
            }
        }

        int broken = 0;
        int scanned = 0;
        while (!queue.isEmpty() && broken < limit && scanned < MAX_SPREAD_SCAN_BLOCKS) {
            scanned++;
            Block current = queue.poll();
            CustomBlock customBlock = getCustomBlock(current);
            if (customBlock == null || !sameOre(origin.customBlock(), customBlock)) {
                continue;
            }
            if (isAuraPlacedBlock(current) || isAuraLocationBlocked(player, current)) {
                continue;
            }
            if (!checkBreakingPower(tool, current)) {
                continue;
            }

            BlockState state = current.getState();
            int copies = calculateMiningCopies(player, tool, customBlock);
            dropCopies(state, customBlock, tool, player, current.getLocation(), copies);
            current.setType(Material.AIR, true);
            rollMiningRare(player, customBlock, current.getLocation());
            addRawMiningXp(player, origin.auraXpAmount());
            broken++;

            for (BlockFace face : SEARCH_FACES) {
                Block neighbor = current.getRelative(face);
                String key = blockKey(neighbor);
                if (visited.add(key) && isSameOre(origin.customBlock(), neighbor)) {
                    queue.add(neighbor);
                }
            }
        }

        if (broken > 0) {
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.45f, 1.4f);
        }
    }

    private int calculateMiningCopies(Player player, ItemStack tool, CustomBlock customBlock) {
        int fortuneCopies = calculateStatCopies(getEquipmentFortune(tool) + (globalStats == null ? 0.0 : globalStats.getGlobalFortune(player)));
        if (customBlock.oreType() != OreType.GEMSTONE && customBlock.oreType() != OreType.MAGIC_CRYSTAL) {
            return fortuneCopies;
        }

        int purityCopies = calculateStatCopies(getPurity(tool));
        return Math.max(1, fortuneCopies * purityCopies);
    }

    private int calculateStatCopies(double value) {
        double stat = Math.max(0.0, value);
        int guaranteed = 1 + (int) Math.floor(stat / 100.0);
        if (ThreadLocalRandom.current().nextDouble() < (stat % 100.0) / 100.0) {
            guaranteed++;
        }
        return Math.max(1, guaranteed);
    }

    private void tickMiningSpeed() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ItemStack tool = player.getInventory().getItemInMainHand();
            double speed = readToolStat(tool, miningSpeedKey) / MINING_SPEED_POINTS_PER_ATTRIBUTE;
            if (speed <= 0.0 && tool != null && tool.getType().name().endsWith("_PICKAXE")) {
                speed = tool.getEnchantmentLevel(Enchantment.EFFICIENCY) * 0.05;
            }
            applyMiningSpeedAttribute(player, speed);
        }
    }

    private double getEquipmentFortune(ItemStack tool) {
        return readToolStat(tool, toolFortuneKey) + readToolStat(tool, miningFortuneKey);
    }

    private int getMiningSpread(ItemStack tool) {
        return Math.max(0, (int) Math.floor(Math.max(readToolStat(tool, toolSpreadKey), readToolStat(tool, miningSpreadKey))));
    }

    private double getPurity(ItemStack tool) {
        return readToolStat(tool, purityKey) + readToolStat(tool, miningPurityKey);
    }

    private int getBreakingPower(ItemStack tool) {
        int vanillaPower = getVanillaToolPower(tool);
        int customPower = (int) Math.floor(readToolStat(tool, breakingPowerKey));
        return Math.max(vanillaPower, customPower);
    }

    private int getVanillaToolPower(ItemStack tool) {
        if (tool == null || tool.getType().isAir()) {
            return 0;
        }

        String type = tool.getType().name();
        if (!(type.endsWith("_PICKAXE") || type.endsWith("_SHOVEL"))) {
            return 0;
        }
        if (type.startsWith("WOODEN_") || type.startsWith("GOLDEN_")) {
            return 1;
        }
        if (type.startsWith("STONE_")) {
            return 2;
        }
        if (type.startsWith("IRON_")) {
            return 3;
        }
        if (type.startsWith("DIAMOND_")) {
            return 4;
        }
        if (type.startsWith("NETHERITE_")) {
            return 5;
        }
        return 0;
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

    private void addRawMiningXp(Player player, double amount) {
        AuraSkillsBridge bridge = AuraSkillsBridge.getInstance();
        if (bridge != null) {
            bridge.addSkillXpRaw(player, Skills.MINING, amount);
        }
    }

    private void rollMiningRare(Player player, CustomBlock customBlock, Location location) {
        AuraSkillsBridge bridge = AuraSkillsBridge.getInstance();
        int level = bridge == null ? 0 : bridge.getSkillLevel(player, Skills.MINING);
        double chance = switch (customBlock.oreType()) {
            case NORMAL -> Math.min(0.025, 0.002 + level * 0.0001);
            case MAGIC_METAL -> Math.min(0.04, 0.004 + level * 0.00015);
            case GEMSTONE -> Math.min(0.05, 0.006 + level * 0.0002);
            case MAGIC_CRYSTAL -> Math.min(0.06, 0.008 + level * 0.00025);
        };
        if (ThreadLocalRandom.current().nextDouble() >= chance) {
            return;
        }

        MiningRareEntry entry = rollMiningRareEntry(level, customBlock);
        ItemStack reward = entry == null ? createDefaultMiningRare(customBlock.oreType()) : createMiningLootItem(entry);
        if (reward == null || reward.getType().isAir()) {
            return;
        }

        location.getWorld().dropItemNaturally(location.clone().add(0.5, 0.35, 0.5), reward);
        if (entry != null && entry.xp() > 0.0) {
            addMiningXp(player, entry.xp());
        }
        player.spawnParticle(Particle.HAPPY_VILLAGER, location.clone().add(0.5, 0.8, 0.5), 8, 0.25, 0.25, 0.25, 0.01);
    }

    private MiningRareEntry rollMiningRareEntry(int level, CustomBlock customBlock) {
        List<MiningRareEntry> eligible = miningRareLoot.stream()
                .filter(entry -> level >= entry.minLevel())
                .filter(entry -> entry.matches(customBlock))
                .toList();
        int totalWeight = eligible.stream().mapToInt(MiningRareEntry::weight).sum();
        if (totalWeight <= 0) {
            return null;
        }

        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
        for (MiningRareEntry entry : eligible) {
            roll -= entry.weight();
            if (roll < 0) {
                return entry;
            }
        }
        return eligible.isEmpty() ? null : eligible.getFirst();
    }

    private ItemStack createMiningLootItem(MiningRareEntry entry) {
        CustomItemRegistry registry = CustomItemRegistry.getInstance();
        ItemStack custom = registry == null || entry.customItemId().isBlank()
                ? null
                : registry.createItem(entry.customItemId(), entry.amount());
        if (custom != null) {
            return custom;
        }
        return new ItemStack(entry.fallbackMaterial(), Math.max(1, Math.min(entry.amount(), entry.fallbackMaterial().getMaxStackSize())));
    }

    private ItemStack createDefaultMiningRare(OreType oreType) {
        Material reward = switch (oreType) {
            case NORMAL -> Material.RAW_IRON;
            case MAGIC_METAL -> Material.RAW_GOLD;
            case GEMSTONE -> Material.AMETHYST_SHARD;
            case MAGIC_CRYSTAL -> Material.ECHO_SHARD;
        };
        return new ItemStack(reward);
    }

    private void addMiningXp(Player player, double amount) {
        AuraSkillsBridge bridge = AuraSkillsBridge.getInstance();
        if (bridge != null) {
            bridge.addSkillXp(player, Skills.MINING, amount);
        }
    }

    private boolean isAuraLocationBlocked(Player player, Block block) {
        try {
            AuraSkillsBukkit bukkitApi = AuraSkillsBukkit.get();
            if (bukkitApi.getLocationManager().isXpGainBlocked(block.getLocation(), player, Skills.MINING)) {
                return true;
            }
            return bukkitApi.getLocationManager().isPluginDisabled(block.getLocation(), player);
        } catch (IllegalStateException | NoClassDefFoundError exception) {
            return true;
        }
    }

    private boolean isAuraPlacedBlock(Block block) {
        try {
            return AuraSkillsBukkit.get().getRegions().isPlacedBlock(block);
        } catch (IllegalStateException | NoClassDefFoundError exception) {
            return true;
        }
    }

    private CustomBlock getCustomBlock(Block block) {
        CustomBlock byLocation = locationOres.get(locationKey(block.getLocation()));
        if (byLocation != null) {
            return byLocation;
        }
        return materialOres.get(block.getType());
    }

    private boolean isSameOre(CustomBlock expected, Block block) {
        CustomBlock actual = getCustomBlock(block);
        return actual != null && sameOre(expected, actual);
    }

    private boolean sameOre(CustomBlock left, CustomBlock right) {
        return left.id().equalsIgnoreCase(right.id());
    }

    private void dropExtraCopies(BlockState state, CustomBlock customBlock, ItemStack tool, Player player, Location location, int copies) {
        if (copies <= 0) {
            return;
        }
        dropCopies(state, customBlock, tool, player, location, copies);
    }

    private void dropCopies(BlockState state, CustomBlock customBlock, ItemStack tool, Player player, Location location, int copies) {
        Collection<ItemStack> drops = getDrops(state, customBlock, tool, player);
        if (drops.isEmpty()) {
            return;
        }

        Location dropLocation = location.clone().add(0.5, 0.35, 0.5);
        for (int i = 0; i < copies; i++) {
            for (ItemStack drop : drops) {
                if (drop == null || drop.getType().isAir()) {
                    continue;
                }
                location.getWorld().dropItemNaturally(dropLocation, drop.clone());
            }
        }
    }

    private Collection<ItemStack> getDrops(BlockState state, CustomBlock customBlock, ItemStack tool, Player player) {
        Material customDrop = customBlock.dropMaterial();
        if (customDrop != null && customDrop != state.getType()) {
            return List.of(new ItemStack(customDrop));
        }
        try {
            return state.getDrops(tool, player);
        } catch (NoSuchMethodError error) {
            return tool == null ? state.getDrops() : state.getDrops(tool);
        }
    }

    private void rememberPendingBreak(PendingOreBreak pending) {
        ArrayDeque<PendingOreBreak> playerBreaks = pendingBreaks.computeIfAbsent(pending.playerId(), ignored -> new ArrayDeque<>());
        prunePendingBreaks(playerBreaks);
        playerBreaks.addLast(pending);
    }

    private void removePendingBreak(PendingOreBreak pending) {
        ArrayDeque<PendingOreBreak> playerBreaks = pendingBreaks.get(pending.playerId());
        if (playerBreaks != null) {
            playerBreaks.remove(pending);
        }
    }

    private void prunePendingBreaks(ArrayDeque<PendingOreBreak> playerBreaks) {
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

    private String blockKey(Block block) {
        return locationKey(block.getLocation());
    }

    private String locationKey(Location location) {
        World world = location.getWorld();
        UUID worldId = world == null ? new UUID(0L, 0L) : world.getUID();
        return worldId + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
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
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Unable to check bundled gathering_loot.yml: " + exception.getMessage());
        }
    }

    private List<MiningRareEntry> readMiningRareEntries(ConfigurationSection section) {
        List<MiningRareEntry> entries = new ArrayList<>();
        if (section == null) {
            return entries;
        }

        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null) {
                continue;
            }

            Material fallback = Material.matchMaterial(entry.getString("fallback_material", entry.getString("material", "RAW_IRON")));
            if (fallback == null || fallback.isAir()) {
                plugin.getLogger().warning("Invalid mining rare material: " + id);
                continue;
            }

            entries.add(new MiningRareEntry(
                    id,
                    entry.getString("custom_item", entry.getString("item", id)),
                    fallback,
                    Math.max(1, entry.getInt("amount", 1)),
                    Math.max(0, entry.getInt("weight", 1)),
                    Math.max(0, entry.getInt("min_mining_level", entry.getInt("min_level", 0))),
                    Math.max(0.0, entry.getDouble("xp", 0.0)),
                    readOreTypes(entry),
                    readStringList(entry, "sources")
            ));
        }
        return entries.stream().filter(entry -> entry.weight() > 0).toList();
    }

    private Set<OreType> readOreTypes(ConfigurationSection section) {
        List<String> rawTypes = readStringList(section, "ore_types");
        if (rawTypes.isEmpty()) {
            rawTypes = readStringList(section, "ore_type");
        }
        if (rawTypes.isEmpty()) {
            return EnumSet.allOf(OreType.class);
        }

        EnumSet<OreType> oreTypes = EnumSet.noneOf(OreType.class);
        for (String rawType : rawTypes) {
            try {
                oreTypes.add(OreType.valueOf(rawType.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Unknown mining ore type in gathering_loot.yml: " + rawType);
            }
        }
        return oreTypes.isEmpty() ? EnumSet.allOf(OreType.class) : oreTypes;
    }

    private List<String> readStringList(ConfigurationSection section, String path) {
        Object raw = section.get(path);
        if (raw instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object value : list) {
                if (value != null && !String.valueOf(value).isBlank()) {
                    result.add(String.valueOf(value).trim());
                }
            }
            return result;
        }
        if (raw == null || String.valueOf(raw).isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : String.valueOf(raw).split(",")) {
            if (!part.isBlank()) {
                result.add(part.trim());
            }
        }
        return result;
    }

    private List<MiningRareEntry> defaultMiningRareLoot() {
        return List.of(
                new MiningRareEntry("iron_nodule", "iron_nodule", Material.RAW_IRON, 1, 100, 0, 6.0, EnumSet.of(OreType.NORMAL, OreType.MAGIC_METAL), List.of()),
                new MiningRareEntry("metal_spark", "metal_spark", Material.RAW_GOLD, 1, 40, 20, 16.0, EnumSet.of(OreType.MAGIC_METAL), List.of()),
                new MiningRareEntry("gem_dust", "gem_dust", Material.AMETHYST_SHARD, 1, 25, 35, 24.0, EnumSet.of(OreType.GEMSTONE, OreType.MAGIC_CRYSTAL), List.of()),
                new MiningRareEntry("crystal_echo", "crystal_echo", Material.ECHO_SHARD, 1, 8, 55, 45.0, EnumSet.of(OreType.MAGIC_CRYSTAL), List.of())
        );
    }

    private void registerDefaultOres() {
        registerCustomOreMaterial("stone", Material.STONE, OreType.NORMAL, 0, Material.STONE);
        registerCustomOreMaterial("deepslate", Material.DEEPSLATE, OreType.NORMAL, 1, Material.DEEPSLATE);
        registerCustomOreMaterial("coal_ore", Material.COAL_ORE, OreType.NORMAL, 1, Material.COAL);
        registerCustomOreMaterial("deepslate_coal_ore", Material.DEEPSLATE_COAL_ORE, OreType.NORMAL, 1, Material.COAL);
        registerCustomOreMaterial("copper_ore", Material.COPPER_ORE, OreType.NORMAL, 2, Material.RAW_COPPER);
        registerCustomOreMaterial("deepslate_copper_ore", Material.DEEPSLATE_COPPER_ORE, OreType.NORMAL, 2, Material.RAW_COPPER);
        registerCustomOreMaterial("iron_ore", Material.IRON_ORE, OreType.MAGIC_METAL, 2, Material.RAW_IRON);
        registerCustomOreMaterial("deepslate_iron_ore", Material.DEEPSLATE_IRON_ORE, OreType.MAGIC_METAL, 2, Material.RAW_IRON);
        registerCustomOreMaterial("gold_ore", Material.GOLD_ORE, OreType.MAGIC_METAL, 3, Material.RAW_GOLD);
        registerCustomOreMaterial("deepslate_gold_ore", Material.DEEPSLATE_GOLD_ORE, OreType.MAGIC_METAL, 3, Material.RAW_GOLD);
        registerCustomOreMaterial("nether_gold_ore", Material.NETHER_GOLD_ORE, OreType.MAGIC_METAL, 3, Material.GOLD_NUGGET);
        registerCustomOreMaterial("nether_quartz_ore", Material.NETHER_QUARTZ_ORE, OreType.MAGIC_METAL, 3, Material.QUARTZ);
        registerCustomOreMaterial("ancient_debris", Material.ANCIENT_DEBRIS, OreType.MAGIC_METAL, 5, Material.ANCIENT_DEBRIS);
        registerCustomOreMaterial("redstone_ore", Material.REDSTONE_ORE, OreType.GEMSTONE, 3, Material.REDSTONE);
        registerCustomOreMaterial("deepslate_redstone_ore", Material.DEEPSLATE_REDSTONE_ORE, OreType.GEMSTONE, 3, Material.REDSTONE);
        registerCustomOreMaterial("lapis_ore", Material.LAPIS_ORE, OreType.GEMSTONE, 3, Material.LAPIS_LAZULI);
        registerCustomOreMaterial("deepslate_lapis_ore", Material.DEEPSLATE_LAPIS_ORE, OreType.GEMSTONE, 3, Material.LAPIS_LAZULI);
        registerCustomOreMaterial("diamond_ore", Material.DIAMOND_ORE, OreType.GEMSTONE, 4, Material.DIAMOND);
        registerCustomOreMaterial("deepslate_diamond_ore", Material.DEEPSLATE_DIAMOND_ORE, OreType.GEMSTONE, 4, Material.DIAMOND);
        registerCustomOreMaterial("emerald_ore", Material.EMERALD_ORE, OreType.GEMSTONE, 4, Material.EMERALD);
        registerCustomOreMaterial("deepslate_emerald_ore", Material.DEEPSLATE_EMERALD_ORE, OreType.GEMSTONE, 4, Material.EMERALD);
        registerCustomOreMaterial("amethyst_block", Material.AMETHYST_BLOCK, OreType.MAGIC_CRYSTAL, 4, Material.AMETHYST_SHARD);
        registerCustomOreMaterial("amethyst_cluster", Material.AMETHYST_CLUSTER, OreType.MAGIC_CRYSTAL, 4, Material.AMETHYST_SHARD);
    }

    private record RegisteredCustomBlock(
            String id,
            OreType oreType,
            int breakingPower,
            Material displayMaterial,
            Material dropMaterial
    ) implements CustomBlock {
    }

    private record MiningRareEntry(
            String id,
            String customItemId,
            Material fallbackMaterial,
            int amount,
            int weight,
            int minLevel,
            double xp,
            Set<OreType> oreTypes,
            List<String> sources
    ) {
        boolean matches(CustomBlock customBlock) {
            if (customBlock == null || !oreTypes.contains(customBlock.oreType())) {
                return false;
            }
            if (sources == null || sources.isEmpty()) {
                return true;
            }

            String idText = normalize(customBlock.id());
            String displayText = normalize(customBlock.displayMaterial().name());
            String dropText = normalize(customBlock.dropMaterial().name());
            for (String source : sources) {
                String normalized = normalize(source);
                if (!normalized.isBlank()
                        && (idText.contains(normalized) || displayText.contains(normalized) || dropText.contains(normalized))) {
                    return true;
                }
            }
            return false;
        }

        private static String normalize(String value) {
            return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
        }
    }

    private static final class PendingOreBreak {
        private final UUID playerId;
        private final Location location;
        private final BlockState state;
        private final Material material;
        private final CustomBlock customBlock;
        private final BlockBreakEvent event;
        private final long createdAt;
        private boolean auraXpGranted;
        private double auraXpAmount;

        private PendingOreBreak(UUID playerId, Location location, BlockState state, Material material,
                                CustomBlock customBlock, BlockBreakEvent event, long createdAt) {
            this.playerId = playerId;
            this.location = location;
            this.state = state;
            this.material = material;
            this.customBlock = customBlock;
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

        CustomBlock customBlock() {
            return customBlock;
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
}
