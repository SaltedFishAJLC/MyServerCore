package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Configured fishing consumables and normal fish runtime state.
 */
public class FishingContentManager implements Listener {

    public static final String ITEM_TYPE_BAIT = "BAIT";
    public static final String ITEM_TYPE_NORMAL_FISH = "NORMAL_FISH";

    private static final Set<String> KNOWN_SCALAR_MODIFIERS = Set.of(
            "normal_fish_weight",
            "rare_fish_weight",
            "treasure_quality",
            "rare_treasure_weight",
            "epic_treasure_weight",
            "legendary_treasure_weight",
            "rare_sea_creature_weight",
            "sea_creature_damage",
            "sea_creature_damage_taken",
            "sea_creature_material_weight",
            "temporary_treasure_chance_bonus"
    );

    private static FishingContentManager instance;

    private final ServerCorePlugin plugin;
    private final File baitsFile;
    private final File fishFile;
    private final Map<UUID, BaitReservation> reservationsByHook = new HashMap<>();
    private final Map<UUID, Map<String, ActiveFishingBuff>> activeBuffs = new HashMap<>();
    private final Map<UUID, Long> fishFoodCooldownUntil = new HashMap<>();
    private BukkitTask cleanupTask;

    private volatile Map<String, BaitDefinition> baits = Map.of();
    private volatile Map<String, NormalFishDefinition> fish = Map.of();
    private volatile List<NormalFishPool> normalFishPools = List.of();
    private volatile Set<String> gatedSeaCreatures = Set.of();
    private volatile Set<String> gatedTreasurePools = Set.of();
    private volatile Set<String> gatedFish = Set.of();
    private volatile double temporaryTreasureChanceBonusCap = 1.0;

    public FishingContentManager(ServerCorePlugin plugin) {
        this.plugin = plugin;
        this.baitsFile = new File(plugin.getDataFolder(), "fishing_baits.yml");
        this.fishFile = new File(plugin.getDataFolder(), "fish_items.yml");
        instance = this;
        reload();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        this.cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::cleanupDanglingReservations, 100L, 100L);
    }

    public static FishingContentManager getInstance() {
        return instance;
    }

    public void stop() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }

    public int reload() {
        ensureResource("fishing_baits.yml", baitsFile);
        ensureResource("fish_items.yml", fishFile);

        YamlConfiguration baitConfig = YamlConfiguration.loadConfiguration(baitsFile);
        YamlConfiguration fishConfig = YamlConfiguration.loadConfiguration(fishFile);

        LinkedHashMap<String, BaitDefinition> loadedBaits = readBaits(baitConfig.getConfigurationSection("baits"));
        LinkedHashMap<String, NormalFishDefinition> loadedFish = readFish(fishConfig.getConfigurationSection("fish"));
        List<NormalFishPool> loadedPools = readNormalFishPools(fishConfig.getConfigurationSection("normal_fish_pools"));

        this.temporaryTreasureChanceBonusCap = Math.max(0.0,
                fishConfig.getDouble("balance.temporary_treasure_chance_bonus_cap", 1.0));
        this.baits = Map.copyOf(loadedBaits);
        this.fish = Map.copyOf(loadedFish);
        this.normalFishPools = List.copyOf(loadedPools);
        rebuildGateIndexes(loadedBaits.values());

        int loaded = loadedBaits.size() + loadedFish.size() + loadedPools.size();
        plugin.getLogger().info("Loaded fishing consumables: baits=" + loadedBaits.size()
                + ", normal_fish=" + loadedFish.size()
                + ", normal_fish_pools=" + loadedPools.size() + ".");
        return loaded;
    }

    public Collection<String> getBaitIds() {
        return baits.keySet();
    }

    public Collection<String> getFishIds() {
        return fish.keySet();
    }

    public double getTemporaryTreasureChanceBonusCap() {
        return temporaryTreasureChanceBonusCap;
    }

    public BaitDefinition getBait(String id) {
        return baits.get(normalize(id));
    }

    public NormalFishDefinition getFish(String id) {
        return fish.get(normalize(id));
    }

    public BaitDefinition getReservedBait(FishHook hook) {
        if (hook == null) {
            return null;
        }
        BaitReservation reservation = reservationsByHook.get(hook.getUniqueId());
        return reservation == null ? null : baits.get(reservation.baitId());
    }

    public BaitDefinition reserveBait(Player player, FishHook hook) {
        if (player == null || hook == null) {
            return null;
        }
        BaitReservation existing = reservationsByHook.get(hook.getUniqueId());
        if (existing != null) {
            return baits.get(existing.baitId());
        }

        PlayerInventory inventory = player.getInventory();
        ItemStack[] storage = inventory.getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            ItemStack item = storage[slot];
            BaitDefinition bait = getBait(item);
            if (bait == null) {
                continue;
            }

            ItemStack reserved = item.clone();
            reserved.setAmount(1);
            ItemStack replacement = item.clone();
            replacement.setAmount(item.getAmount() - 1);
            inventory.setItem(slot, replacement.getAmount() <= 0 ? null : replacement);

            reservationsByHook.put(hook.getUniqueId(), new BaitReservation(
                    player.getUniqueId(),
                    hook.getUniqueId(),
                    bait.id(),
                    reserved,
                    System.currentTimeMillis()
            ));
            return bait;
        }
        return null;
    }

    public void finalizeBait(FishHook hook) {
        if (hook != null) {
            reservationsByHook.remove(hook.getUniqueId());
        }
    }

    public void refundBait(FishHook hook) {
        if (hook == null) {
            return;
        }
        BaitReservation reservation = reservationsByHook.remove(hook.getUniqueId());
        if (reservation != null) {
            refundReservation(reservation);
        }
    }

    public void refundAll(Player player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUniqueId();
        Iterator<BaitReservation> iterator = reservationsByHook.values().iterator();
        List<BaitReservation> refunds = new ArrayList<>();
        while (iterator.hasNext()) {
            BaitReservation reservation = iterator.next();
            if (reservation.playerId().equals(playerId)) {
                iterator.remove();
                refunds.add(reservation);
            }
        }
        refunds.forEach(this::refundReservation);
    }

    public FishingManager.FishingStatContribution getActiveFoodBuffStats(Player player) {
        FishingManager.FishingStatContribution total = FishingManager.FishingStatContribution.empty();
        for (ActiveFishingBuff buff : getActiveFoodBuffs(player)) {
            total = total.plus(buff.stats());
        }
        return total;
    }

    public Collection<ActiveFishingBuff> getActiveFoodBuffs(Player player) {
        if (player == null) {
            return List.of();
        }
        Map<String, ActiveFishingBuff> buffs = activeBuffs.get(player.getUniqueId());
        if (buffs == null || buffs.isEmpty()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        buffs.values().removeIf(buff -> buff.expiresAtMillis() <= now);
        if (buffs.isEmpty()) {
            activeBuffs.remove(player.getUniqueId());
            return List.of();
        }
        return List.copyOf(buffs.values());
    }

    public double getSeaCreatureWeightBonus(Player player, BaitDefinition bait, String seaCreatureId, int minLevel) {
        double bonus = 0.0;
        if (bait != null) {
            bonus += bait.modifiers().seaCreatureWeight(seaCreatureId);
            if (minLevel >= 25) {
                bonus += bait.modifiers().scalar("rare_sea_creature_weight");
            }
        }
        for (ActiveFishingBuff buff : getActiveFoodBuffs(player)) {
            bonus += buff.modifiers().seaCreatureWeight(seaCreatureId);
            if (minLevel >= 25) {
                bonus += buff.modifiers().scalar("rare_sea_creature_weight");
            }
        }
        return Math.max(-0.95, bonus);
    }

    public double getTreasureTierWeightBonus(Player player, BaitDefinition bait, String tierId) {
        String normalizedTier = normalize(tierId);
        double bonus = 0.0;
        if (bait != null) {
            bonus += treasureTierModifier(bait.modifiers(), normalizedTier);
        }
        for (ActiveFishingBuff buff : getActiveFoodBuffs(player)) {
            bonus += treasureTierModifier(buff.modifiers(), normalizedTier);
        }
        return Math.max(-0.95, bonus);
    }

    public double getTreasureEntryWeightBonus(Player player, BaitDefinition bait, String treasureId) {
        double bonus = 0.0;
        if (bait != null) {
            bonus += bait.modifiers().treasureWeight(treasureId);
        }
        for (ActiveFishingBuff buff : getActiveFoodBuffs(player)) {
            bonus += buff.modifiers().treasureWeight(treasureId);
        }
        return Math.max(-0.95, bonus);
    }

    public boolean isSeaCreatureUnlocked(BaitDefinition bait, String seaCreatureId) {
        String id = normalize(seaCreatureId);
        return !gatedSeaCreatures.contains(id)
                || (bait != null && (bait.gates().unlockSeaCreatures().contains(id)
                || bait.modifiers().seaCreatureWeight(id) > 0.0));
    }

    public boolean isTreasureUnlocked(BaitDefinition bait, String treasurePoolOrEntryId) {
        String id = normalize(treasurePoolOrEntryId);
        return !gatedTreasurePools.contains(id)
                || (bait != null && (bait.gates().unlockTreasurePools().contains(id)
                || bait.modifiers().treasureWeight(id) > 0.0));
    }

    public NormalFishDefinition rollNormalFish(Player player, int fishingLevel, BaitDefinition bait, ThreadLocalRandom random) {
        if (fish.isEmpty()) {
            return null;
        }

        NormalFishPool selected = selectNormalFishPool(player, fishingLevel);
        if (selected == null) {
            return null;
        }

        List<WeightedNormalFish> eligible = weightedNormalFish(player, selected, bait);
        if (eligible.isEmpty() && !"river_fish_pool".equals(selected.id())) {
            NormalFishPool fallback = normalFishPools.stream()
                    .filter(pool -> "river_fish_pool".equals(pool.id()))
                    .findFirst()
                    .orElse(null);
            eligible = weightedNormalFish(player, fallback, bait);
        }
        if (eligible.isEmpty()) {
            return null;
        }

        double totalWeight = eligible.stream().mapToDouble(WeightedNormalFish::weight).sum();
        double roll = random.nextDouble(Math.max(0.0001, totalWeight));
        for (WeightedNormalFish entry : eligible) {
            roll -= entry.weight();
            if (roll < 0.0) {
                return entry.fish();
            }
        }
        return eligible.get(0).fish();
    }

    public ItemStack createBaitItem(String id, int amount) {
        BaitDefinition bait = baits.get(normalize(id));
        if (bait == null) {
            return null;
        }
        return createContentItem(
                bait.id(),
                bait.material(),
                bait.displayName(),
                bait.rarity(),
                bait.tier(),
                bait.route(),
                ITEM_TYPE_BAIT,
                baitAbilityLore(bait),
                bait.lore(),
                amount
        );
    }

    public ItemStack createFishItem(String id, int amount) {
        NormalFishDefinition definition = fish.get(normalize(id));
        if (definition == null) {
            return null;
        }
        return createContentItem(
                definition.id(),
                definition.material(),
                definition.displayName(),
                definition.rarity(),
                definition.tier(),
                "",
                ITEM_TYPE_NORMAL_FISH,
                fishAbilityLore(definition),
                definition.lore(),
                amount
        );
    }

    private void cleanupDanglingReservations() {
        List<BaitReservation> refunds = new ArrayList<>();
        Iterator<Map.Entry<UUID, BaitReservation>> iterator = reservationsByHook.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, BaitReservation> entry = iterator.next();
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity instanceof FishHook hook && hook.isValid() && !hook.isDead()) {
                continue;
            }
            iterator.remove();
            refunds.add(entry.getValue());
        }
        refunds.forEach(this::refundReservation);
    }

    private void refundReservation(BaitReservation reservation) {
        Player player = Bukkit.getPlayer(reservation.playerId());
        if (player == null) {
            return;
        }
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(reservation.reservedItem().clone());
        for (ItemStack leftover : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private double treasureTierModifier(BaitModifiers modifiers, String tierId) {
        double bonus = modifiers.scalar(tierId + "_treasure_weight");
        if ("epic".equals(tierId) || "legendary".equals(tierId)) {
            bonus += modifiers.scalar("treasure_quality");
        }
        return bonus;
    }

    private NormalFishPool selectNormalFishPool(Player player, int fishingLevel) {
        NormalFishPool selected = null;
        for (NormalFishPool pool : normalFishPools) {
            if (fishingLevel < pool.minFishingLevel()) {
                continue;
            }
            if (!pool.matchesWeather(player)) {
                continue;
            }
            if (selected == null
                    || pool.minTier() > selected.minTier()
                    || (pool.minTier() == selected.minTier()
                    && pool.minFishingLevel() > selected.minFishingLevel())) {
                selected = pool;
            }
        }
        if (selected != null) {
            return selected;
        }
        return normalFishPools.stream()
                .filter(pool -> "river_fish_pool".equals(pool.id()))
                .findFirst()
                .orElse(normalFishPools.isEmpty() ? null : normalFishPools.get(0));
    }

    private List<WeightedNormalFish> weightedNormalFish(Player player, NormalFishPool pool, BaitDefinition bait) {
        if (pool == null) {
            return List.of();
        }
        List<WeightedNormalFish> result = new ArrayList<>();
        for (NormalFishPoolEntry entry : pool.fish()) {
            NormalFishDefinition definition = fish.get(entry.fishId());
            if (definition == null || !isFishUnlocked(entry, definition, bait)) {
                continue;
            }
            double bonus = 0.0;
            if (bait != null) {
                bonus += bait.modifiers().fishWeight(definition.id());
                bonus += bait.modifiers().scalar("normal_fish_weight");
                if (definition.rarity().ordinal() >= ItemFormatManager.Rarity.RARE.ordinal()) {
                    bonus += bait.modifiers().scalar("rare_fish_weight");
                }
            }
            for (ActiveFishingBuff buff : getActiveFoodBuffs(player)) {
                bonus += buff.modifiers().fishWeight(definition.id());
                bonus += buff.modifiers().scalar("normal_fish_weight");
                if (definition.rarity().ordinal() >= ItemFormatManager.Rarity.RARE.ordinal()) {
                    bonus += buff.modifiers().scalar("rare_fish_weight");
                }
            }
            result.add(new WeightedNormalFish(definition, entry.weight() * Math.max(0.05, 1.0 + bonus)));
        }
        return result;
    }

    private boolean isFishUnlocked(NormalFishPoolEntry entry, NormalFishDefinition definition, BaitDefinition bait) {
        if (!entry.requiredBait().isEmpty() && (bait == null || !entry.requiredBait().contains(bait.id()))) {
            return false;
        }
        return !gatedFish.contains(definition.id())
                || (bait != null && (bait.gates().unlockFish().contains(definition.id())
                || bait.modifiers().fishWeight(definition.id()) > 0.0));
    }

    private BaitDefinition getBait(ItemStack item) {
        String itemId = getItemId(item);
        if (itemId == null) {
            return null;
        }
        BaitDefinition bait = baits.get(normalize(itemId));
        if (bait == null) {
            return null;
        }
        String itemType = getItemType(item);
        return itemType == null || itemType.equalsIgnoreCase(ITEM_TYPE_BAIT) ? bait : null;
    }

    private NormalFishDefinition getNormalFish(ItemStack item) {
        String itemId = getItemId(item);
        if (itemId == null) {
            return null;
        }
        NormalFishDefinition definition = fish.get(normalize(itemId));
        if (definition == null) {
            return null;
        }
        String itemType = getItemType(item);
        return itemType == null || itemType.equalsIgnoreCase(ITEM_TYPE_NORMAL_FISH) ? definition : null;
    }

    private ItemStack createContentItem(String id, Material material, String displayName, ItemFormatManager.Rarity rarity,
                                        int tier, String route, String itemType, List<String> abilityLore,
                                        List<String> storyLore, int amount) {
        ItemStack item = new ItemStack(material, Math.max(1, amount));
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return item;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(pdc.KEY_ITEM_ID, PersistentDataType.STRING, id);
        container.set(pdc.KEY_ITEM_ORIGINAL_NAME, PersistentDataType.STRING, displayName);
        container.set(pdc.KEY_ITEM_RARITY, PersistentDataType.STRING, rarity.name());
        container.set(pdc.KEY_ITEM_TYPE, PersistentDataType.STRING, itemType);
        container.set(pdc.KEY_ITEM_FISHING_TIER, PersistentDataType.INTEGER, Math.max(1, tier));
        container.set(pdc.KEY_ITEM_SCALE_VERSION, PersistentDataType.INTEGER, ItemStandardizer.VANILLA_SCALE_VERSION);
        if (route != null && !route.isBlank()) {
            container.set(pdc.KEY_ITEM_FISHING_ROUTE, PersistentDataType.STRING, route);
        }
        if (!abilityLore.isEmpty()) {
            container.set(pdc.KEY_ITEM_ABILITIES, PersistentDataType.STRING, String.join("||", abilityLore));
        }
        if (!storyLore.isEmpty()) {
            container.set(pdc.KEY_ITEM_STORY_LORE, PersistentDataType.STRING, String.join("||", storyLore));
        }
        item.setItemMeta(meta);

        ItemFormatManager formatManager = ItemFormatManager.getInstance();
        if (formatManager != null) {
            formatManager.formatItem(item, true);
        }
        return item;
    }

    private List<String> baitAbilityLore(BaitDefinition bait) {
        List<String> lines = new ArrayList<>();
        addStatLine(lines, "钓鱼速度", bait.stats().fishingSpeed(), "");
        addStatLine(lines, "海怪召唤率", bait.stats().seaCreatureChance(), "%");
        addStatLine(lines, "宝藏率", bait.stats().treasureChance(), "%");
        lines.add("成功钓起东西时消耗");
        bait.modifiers().appendDisplayLines(lines);
        if (!bait.gates().unlockSeaCreatures().isEmpty()) {
            lines.add("解锁海怪: " + String.join(", ", bait.gates().unlockSeaCreatures()));
        }
        if (!bait.gates().unlockTreasurePools().isEmpty()) {
            lines.add("解锁宝藏池: " + String.join(", ", bait.gates().unlockTreasurePools()));
        }
        if (!bait.gates().unlockFish().isEmpty()) {
            lines.add("解锁鱼类: " + String.join(", ", bait.gates().unlockFish()));
        }
        return lines;
    }

    private List<String> fishAbilityLore(NormalFishDefinition definition) {
        List<String> lines = new ArrayList<>();
        if (!definition.edible()) {
            return lines;
        }
        lines.add("右键食用");
        if (definition.consume().restoreHealth() > 0.0) {
            lines.add("立即恢复 " + formatNumber(definition.consume().restoreHealth()) + " 生命");
        }
        for (FoodBuffDefinition buff : definition.consume().buffs()) {
            lines.add("获得 " + buff.durationSeconds() + " 秒: " + buff.summary());
        }
        if (!definition.consume().buffs().isEmpty()) {
            lines.add("同组食物 Buff 保留更强者");
        }
        return lines;
    }

    private void addStatLine(List<String> lines, String label, double value, String suffix) {
        if (Math.abs(value) < 0.0001) {
            return;
        }
        lines.add(label + " " + (value > 0.0 ? "+" : "") + formatNumber(value) + suffix);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onNormalFishInteract(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick() || event.getHand() == null) {
            return;
        }
        Player player = event.getPlayer();
        if (event.getHand() == EquipmentSlot.OFF_HAND
                && getNormalFish(player.getInventory().getItemInMainHand()) != null) {
            return;
        }
        ItemStack item = event.getHand() == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        NormalFishDefinition definition = getNormalFish(item);
        if (definition == null || !definition.edible()) {
            return;
        }

        event.setCancelled(true);
        useNormalFish(player, event.getHand(), item, definition);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onNormalFishConsume(PlayerItemConsumeEvent event) {
        NormalFishDefinition definition = getNormalFish(event.getItem());
        if (definition == null || !definition.edible()) {
            return;
        }
        event.setCancelled(true);
        useNormalFish(event.getPlayer(), event.getHand(), event.getItem(), definition);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        refundAll(event.getPlayer());
        UUID playerId = event.getPlayer().getUniqueId();
        activeBuffs.remove(playerId);
        fishFoodCooldownUntil.remove(playerId);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        refundAll(event.getPlayer());
    }

    private boolean useNormalFish(Player player, EquipmentSlot hand, ItemStack item, NormalFishDefinition definition) {
        if (player == null || item == null || definition == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        long until = fishFoodCooldownUntil.getOrDefault(player.getUniqueId(), 0L);
        if (until > now) {
            player.sendActionBar(Component.text("鱼类食物冷却: " + formatSeconds(until - now)));
            syncFood(player);
            return true;
        }

        FishConsumeEffect consume = definition.consume();
        PlayerRecoveryManager recoveryManager = PlayerRecoveryManager.getInstance();
        if (recoveryManager != null) {
            recoveryManager.healImmediate(player, consume.restoreHealth());
        } else {
            player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + Math.max(0.0, consume.restoreHealth())));
        }

        applyFoodBuffs(player, consume.buffs());
        long cooldownMillis = Math.round(Math.max(0.1, consume.cooldownSeconds()) * 1000.0);
        fishFoodCooldownUntil.put(player.getUniqueId(), now + cooldownMillis);
        player.setCooldown(item.getType(), Math.max(1, (int) Math.round(consume.cooldownSeconds() * 20.0)));
        if (player.getGameMode() != GameMode.CREATIVE) {
            consumeOne(player, hand, item);
        }
        syncFood(player);
        player.sendActionBar(Component.text("已食用 " + definition.displayName()));
        return true;
    }

    private void applyFoodBuffs(Player player, List<FoodBuffDefinition> buffs) {
        if (buffs.isEmpty()) {
            return;
        }
        Map<String, ActiveFishingBuff> active = activeBuffs.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>());
        long now = System.currentTimeMillis();
        active.values().removeIf(buff -> buff.expiresAtMillis() <= now);

        for (FoodBuffDefinition definition : buffs) {
            int durationTicks = Math.max(1, definition.durationSeconds() * 20);
            for (Map.Entry<String, Integer> effect : definition.effects().entrySet()) {
                PotionEffectType type = PotionEffectType.getByName(effect.getKey().toUpperCase(Locale.ROOT));
                if (type != null) {
                    player.addPotionEffect(new PotionEffect(type, durationTicks, Math.max(0, effect.getValue() - 1), true, false, true));
                }
            }

            ActiveFishingBuff next = new ActiveFishingBuff(
                    definition.group(),
                    now + definition.durationSeconds() * 1000L,
                    definition.stats(),
                    definition.modifiers(),
                    definition.effects(),
                    definition.powerScore()
            );
            ActiveFishingBuff old = active.get(definition.group());
            if (old == null || old.expiresAtMillis() <= now || next.powerScore() >= old.powerScore()) {
                active.put(definition.group(), next);
            }
        }
    }

    private void consumeOne(Player player, EquipmentSlot hand, ItemStack item) {
        ItemStack replacement = item.clone();
        replacement.setAmount(item.getAmount() - 1);
        if (replacement.getAmount() <= 0) {
            replacement = null;
        }
        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(replacement);
        } else {
            player.getInventory().setItemInMainHand(replacement);
        }
    }

    private void syncFood(Player player) {
        PlayerRecoveryManager recoveryManager = PlayerRecoveryManager.getInstance();
        if (recoveryManager != null) {
            recoveryManager.syncFoodState(player);
        }
    }

    private LinkedHashMap<String, BaitDefinition> readBaits(ConfigurationSection section) {
        LinkedHashMap<String, BaitDefinition> result = new LinkedHashMap<>();
        if (section == null) {
            return result;
        }
        for (String rawId : section.getKeys(false)) {
            String id = normalize(rawId);
            ConfigurationSection baitSection = section.getConfigurationSection(rawId);
            if (baitSection == null) {
                continue;
            }
            Material material = parseMaterial(baitSection.getString("material"), Material.WHEAT_SEEDS, "bait " + id);
            if (material == null) {
                continue;
            }
            result.put(id, new BaitDefinition(
                    id,
                    material,
                    baitSection.getString("name", rawId),
                    parseRarity(baitSection.getString("rarity", "COMMON")),
                    Math.max(1, baitSection.getInt("tier", 1)),
                    normalizeRoute(baitSection.getString("route", "MIXED")),
                    baitSection.getString("consume_on", "CATCH_SUCCESS"),
                    readStats(baitSection.getConfigurationSection("stats")),
                    readModifiers(baitSection.getConfigurationSection("modifiers"), "bait " + id),
                    readGates(baitSection.getConfigurationSection("gates")),
                    baitSection.getStringList("lore")
            ));
        }
        return result;
    }

    private LinkedHashMap<String, NormalFishDefinition> readFish(ConfigurationSection section) {
        LinkedHashMap<String, NormalFishDefinition> result = new LinkedHashMap<>();
        if (section == null) {
            return result;
        }
        for (String rawId : section.getKeys(false)) {
            String id = normalize(rawId);
            ConfigurationSection fishSection = section.getConfigurationSection(rawId);
            if (fishSection == null) {
                continue;
            }
            Material material = parseMaterial(fishSection.getString("material"), Material.COD, "fish " + id);
            if (material == null) {
                continue;
            }
            result.put(id, new NormalFishDefinition(
                    id,
                    material,
                    fishSection.getString("name", rawId),
                    parseRarity(fishSection.getString("rarity", "COMMON")),
                    Math.max(1, fishSection.getInt("tier", 1)),
                    fishSection.getBoolean("edible", true),
                    readConsumeEffect(fishSection.getConfigurationSection("consume")),
                    fishSection.getStringList("lore")
            ));
        }
        return result;
    }

    private List<NormalFishPool> readNormalFishPools(ConfigurationSection section) {
        List<NormalFishPool> pools = new ArrayList<>();
        if (section == null) {
            return pools;
        }
        for (String rawId : section.getKeys(false)) {
            String id = normalize(rawId);
            ConfigurationSection pool = section.getConfigurationSection(rawId);
            if (pool == null) {
                continue;
            }
            pools.add(new NormalFishPool(
                    id,
                    Math.max(1, pool.getInt("min_tier", 1)),
                    Math.max(0, pool.getInt("min_fishing_level", 0)),
                    readStringSet(pool.getConfigurationSection("conditions"), "weather"),
                    readNormalFishPoolEntries(pool.getConfigurationSection("fish"))
            ));
        }
        return pools;
    }

    private List<NormalFishPoolEntry> readNormalFishPoolEntries(ConfigurationSection section) {
        List<NormalFishPoolEntry> entries = new ArrayList<>();
        if (section == null) {
            return entries;
        }
        for (String rawFishId : section.getKeys(false)) {
            String fishId = normalize(rawFishId);
            ConfigurationSection entry = section.getConfigurationSection(rawFishId);
            if (entry == null) {
                continue;
            }
            entries.add(new NormalFishPoolEntry(
                    fishId,
                    Math.max(0, entry.getInt("weight", 1)),
                    readStringSet(entry, "required_bait")
            ));
        }
        return entries.stream().filter(entry -> entry.weight() > 0).toList();
    }

    private FishConsumeEffect readConsumeEffect(ConfigurationSection section) {
        if (section == null) {
            return new FishConsumeEffect(0.0, 3.0, List.of());
        }
        return new FishConsumeEffect(
                Math.max(0.0, section.getDouble("restore_health", 0.0)),
                Math.max(0.1, section.getDouble("cooldown_seconds", 3.0)),
                readFoodBuffs(section.getConfigurationSection("buffs"))
        );
    }

    private List<FoodBuffDefinition> readFoodBuffs(ConfigurationSection section) {
        List<FoodBuffDefinition> buffs = new ArrayList<>();
        if (section == null) {
            return buffs;
        }
        for (String rawGroup : section.getKeys(false)) {
            String group = normalize(rawGroup);
            ConfigurationSection buff = section.getConfigurationSection(rawGroup);
            if (buff == null) {
                continue;
            }
            buffs.add(new FoodBuffDefinition(
                    group,
                    Math.max(1, buff.getInt("duration_seconds", 30)),
                    readStats(buff.getConfigurationSection("stats")),
                    readModifiers(buff.getConfigurationSection("modifiers"), "food buff " + group),
                    readEffects(buff.getConfigurationSection("effects"))
            ));
        }
        return buffs;
    }

    private FishingManager.FishingStatContribution readStats(ConfigurationSection section) {
        if (section == null) {
            return FishingManager.FishingStatContribution.empty();
        }
        return new FishingManager.FishingStatContribution(
                section.getDouble("fishing_speed", 0.0),
                section.getDouble("sea_creature_chance", 0.0),
                section.getDouble("treasure_chance", 0.0)
        );
    }

    private BaitModifiers readModifiers(ConfigurationSection section, String source) {
        if (section == null) {
            return BaitModifiers.empty();
        }
        Map<String, Double> seaCreatureWeight = readWeightMap(section.getConfigurationSection("sea_creature_weight"));
        Map<String, Double> treasureWeight = readWeightMap(section.getConfigurationSection("treasure_weight"));
        Map<String, Double> fishWeight = readWeightMap(section.getConfigurationSection("fish_weight"));
        Map<String, Double> scalar = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            String normalized = normalize(key);
            if (normalized.equals("sea_creature_weight")
                    || normalized.equals("treasure_weight")
                    || normalized.equals("fish_weight")) {
                continue;
            }
            if (section.isConfigurationSection(key)) {
                plugin.getLogger().warning("Unknown fishing modifier section ignored in " + source + ": " + key);
                continue;
            }
            if (!KNOWN_SCALAR_MODIFIERS.contains(normalized)) {
                plugin.getLogger().warning("Unknown fishing modifier kept for future use in " + source + ": " + key);
            }
            scalar.put(normalized, section.getDouble(key, 0.0));
        }
        return new BaitModifiers(seaCreatureWeight, treasureWeight, fishWeight, scalar);
    }

    private Map<String, Double> readWeightMap(ConfigurationSection section) {
        Map<String, Double> weights = new LinkedHashMap<>();
        if (section == null) {
            return weights;
        }
        for (String key : section.getKeys(false)) {
            weights.put(normalize(key), section.getDouble(key, 0.0));
        }
        return weights;
    }

    private BaitGates readGates(ConfigurationSection section) {
        if (section == null) {
            return BaitGates.empty();
        }
        return new BaitGates(
                readStringSet(section, "unlock_sea_creatures"),
                readStringSet(section, "unlock_treasure_pools"),
                readStringSet(section, "unlock_fish"),
                readStringSet(section, "unlock_events")
        );
    }

    private Map<String, Integer> readEffects(ConfigurationSection section) {
        Map<String, Integer> effects = new LinkedHashMap<>();
        if (section == null) {
            return effects;
        }
        for (String key : section.getKeys(false)) {
            effects.put(normalize(key), Math.max(1, section.getInt(key, 1)));
        }
        return effects;
    }

    private Set<String> readStringSet(ConfigurationSection section, String key) {
        if (section == null) {
            return Set.of();
        }
        Set<String> values = new HashSet<>();
        if (section.isList(key)) {
            for (String value : section.getStringList(key)) {
                values.add(normalize(value));
            }
        } else {
            String single = section.getString(key, "");
            if (!single.isBlank()) {
                values.add(normalize(single));
            }
        }
        values.remove("");
        return values;
    }

    private void rebuildGateIndexes(Collection<BaitDefinition> definitions) {
        Set<String> sea = new HashSet<>();
        Set<String> treasure = new HashSet<>();
        Set<String> normalFish = new HashSet<>();
        for (BaitDefinition bait : definitions) {
            sea.addAll(bait.gates().unlockSeaCreatures());
            treasure.addAll(bait.gates().unlockTreasurePools());
            normalFish.addAll(bait.gates().unlockFish());
        }
        this.gatedSeaCreatures = Set.copyOf(sea);
        this.gatedTreasurePools = Set.copyOf(treasure);
        this.gatedFish = Set.copyOf(normalFish);
    }

    private Material parseMaterial(String raw, Material fallback, String source) {
        Material material = raw == null ? fallback : Material.matchMaterial(raw);
        if (material == null || material.isAir()) {
            plugin.getLogger().warning("Invalid material for " + source + ": " + raw);
            return null;
        }
        return material;
    }

    private ItemFormatManager.Rarity parseRarity(String raw) {
        try {
            return ItemFormatManager.Rarity.valueOf(raw == null ? "COMMON" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return ItemFormatManager.Rarity.COMMON;
        }
    }

    private String normalizeRoute(String raw) {
        String route = normalize(raw).toUpperCase(Locale.ROOT);
        return route.isBlank() ? "MIXED" : route;
    }

    private String getItemId(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(pdc.KEY_ITEM_ID, PersistentDataType.STRING);
    }

    private String getItemType(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(pdc.KEY_ITEM_TYPE, PersistentDataType.STRING);
    }

    private void ensureResource(String resourceName, File file) {
        if (file.exists()) {
            return;
        }
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder for " + resourceName + ".");
            return;
        }
        if (plugin.getResource(resourceName) != null) {
            plugin.saveResource(resourceName, false);
            return;
        }
        try {
            if (!file.createNewFile()) {
                plugin.getLogger().warning("Could not create " + resourceName + ".");
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Unable to create " + resourceName + ": " + exception.getMessage());
        }
    }

    private String formatSeconds(long millis) {
        return String.valueOf((long) Math.ceil(Math.max(0L, millis) / 1000.0)) + "s";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return String.valueOf((int) Math.rint(value));
        }
        return String.format(Locale.US, "%.1f", value);
    }

    public record BaitDefinition(
            String id,
            Material material,
            String displayName,
            ItemFormatManager.Rarity rarity,
            int tier,
            String route,
            String consumeOn,
            FishingManager.FishingStatContribution stats,
            BaitModifiers modifiers,
            BaitGates gates,
            List<String> lore
    ) {
        public BaitDefinition {
            id = normalize(id);
            route = route == null || route.isBlank() ? "MIXED" : route;
            stats = stats == null ? FishingManager.FishingStatContribution.empty() : stats;
            modifiers = modifiers == null ? BaitModifiers.empty() : modifiers;
            gates = gates == null ? BaitGates.empty() : gates;
            lore = lore == null ? List.of() : List.copyOf(lore);
        }
    }

    public record BaitModifiers(
            Map<String, Double> seaCreatureWeight,
            Map<String, Double> treasureWeight,
            Map<String, Double> fishWeight,
            Map<String, Double> scalar
    ) {
        public BaitModifiers {
            seaCreatureWeight = normalizedMap(seaCreatureWeight);
            treasureWeight = normalizedMap(treasureWeight);
            fishWeight = normalizedMap(fishWeight);
            scalar = normalizedMap(scalar);
        }

        public static BaitModifiers empty() {
            return new BaitModifiers(Map.of(), Map.of(), Map.of(), Map.of());
        }

        public double seaCreatureWeight(String id) {
            return seaCreatureWeight.getOrDefault(normalize(id), 0.0);
        }

        public double treasureWeight(String id) {
            return treasureWeight.getOrDefault(normalize(id), 0.0);
        }

        public double fishWeight(String id) {
            return fishWeight.getOrDefault(normalize(id), 0.0);
        }

        public double scalar(String key) {
            return scalar.getOrDefault(normalize(key), 0.0);
        }

        public double powerScore() {
            return streamMagnitude(seaCreatureWeight)
                    + streamMagnitude(treasureWeight)
                    + streamMagnitude(fishWeight)
                    + streamMagnitude(scalar);
        }

        private void appendDisplayLines(List<String> lines) {
            appendScalarLine(lines, "普通鱼权重", scalar("normal_fish_weight"));
            appendScalarLine(lines, "稀有鱼权重", scalar("rare_fish_weight"));
            appendScalarLine(lines, "宝藏品质", scalar("treasure_quality"));
            appendScalarLine(lines, "稀有宝藏权重", scalar("rare_treasure_weight"));
            appendScalarLine(lines, "史诗宝藏权重", scalar("epic_treasure_weight"));
            appendScalarLine(lines, "传说宝藏权重", scalar("legendary_treasure_weight"));
        }

        private void appendScalarLine(List<String> lines, String label, double value) {
            if (Math.abs(value) >= 0.0001) {
                lines.add(label + " " + (value > 0.0 ? "+" : "") + formatNumber(value * 100.0) + "%");
            }
        }

        private static Map<String, Double> normalizedMap(Map<String, Double> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            Map<String, Double> result = new LinkedHashMap<>();
            source.forEach((key, value) -> result.put(normalize(key), value == null ? 0.0 : value));
            return Map.copyOf(result);
        }

        private static double streamMagnitude(Map<String, Double> values) {
            return values.values().stream().mapToDouble(value -> Math.abs(value) * 100.0).sum();
        }
    }

    public record BaitGates(
            Set<String> unlockSeaCreatures,
            Set<String> unlockTreasurePools,
            Set<String> unlockFish,
            Set<String> unlockEvents
    ) {
        public BaitGates {
            unlockSeaCreatures = normalizedSet(unlockSeaCreatures);
            unlockTreasurePools = normalizedSet(unlockTreasurePools);
            unlockFish = normalizedSet(unlockFish);
            unlockEvents = normalizedSet(unlockEvents);
        }

        public static BaitGates empty() {
            return new BaitGates(Set.of(), Set.of(), Set.of(), Set.of());
        }

        private static Set<String> normalizedSet(Set<String> source) {
            if (source == null || source.isEmpty()) {
                return Set.of();
            }
            Set<String> result = new HashSet<>();
            source.forEach(value -> {
                String normalized = normalize(value);
                if (!normalized.isBlank()) {
                    result.add(normalized);
                }
            });
            return Set.copyOf(result);
        }
    }

    public record NormalFishDefinition(
            String id,
            Material material,
            String displayName,
            ItemFormatManager.Rarity rarity,
            int tier,
            boolean edible,
            FishConsumeEffect consume,
            List<String> lore
    ) {
        public NormalFishDefinition {
            id = normalize(id);
            consume = consume == null ? new FishConsumeEffect(0.0, 3.0, List.of()) : consume;
            lore = lore == null ? List.of() : List.copyOf(lore);
        }
    }

    public record FishConsumeEffect(double restoreHealth, double cooldownSeconds, List<FoodBuffDefinition> buffs) {
        public FishConsumeEffect {
            restoreHealth = Math.max(0.0, restoreHealth);
            cooldownSeconds = Math.max(0.1, cooldownSeconds);
            buffs = buffs == null ? List.of() : List.copyOf(buffs);
        }
    }

    public record FoodBuffDefinition(
            String group,
            int durationSeconds,
            FishingManager.FishingStatContribution stats,
            BaitModifiers modifiers,
            Map<String, Integer> effects
    ) {
        public FoodBuffDefinition {
            group = normalize(group);
            durationSeconds = Math.max(1, durationSeconds);
            stats = stats == null ? FishingManager.FishingStatContribution.empty() : stats;
            modifiers = modifiers == null ? BaitModifiers.empty() : modifiers;
            effects = effects == null ? Map.of() : Map.copyOf(effects);
        }

        public double powerScore() {
            return Math.abs(stats.fishingSpeed())
                    + Math.abs(stats.seaCreatureChance()) * 10.0
                    + Math.abs(stats.treasureChance()) * 10.0
                    + modifiers.powerScore()
                    + effects.values().stream().mapToDouble(value -> value * 10.0).sum();
        }

        public String summary() {
            List<String> parts = new ArrayList<>();
            if (Math.abs(stats.fishingSpeed()) >= 0.0001) {
                parts.add("钓鱼速度 +" + formatNumber(stats.fishingSpeed()));
            }
            if (Math.abs(stats.seaCreatureChance()) >= 0.0001) {
                parts.add("海怪率 +" + formatNumber(stats.seaCreatureChance()) + "%");
            }
            if (Math.abs(stats.treasureChance()) >= 0.0001) {
                parts.add("宝藏率 +" + formatNumber(stats.treasureChance()) + "%");
            }
            if (Math.abs(modifiers.scalar("treasure_quality")) >= 0.0001) {
                parts.add("宝藏品质 +" + formatNumber(modifiers.scalar("treasure_quality") * 100.0) + "%");
            }
            if (parts.isEmpty() && !effects.isEmpty()) {
                parts.add(String.join(", ", effects.keySet()));
            }
            return parts.isEmpty() ? group : String.join(", ", parts);
        }
    }

    public record ActiveFishingBuff(
            String group,
            long expiresAtMillis,
            FishingManager.FishingStatContribution stats,
            BaitModifiers modifiers,
            Map<String, Integer> effects,
            double powerScore
    ) {
    }

    private record BaitReservation(UUID playerId, UUID hookId, String baitId, ItemStack reservedItem, long createdAtMillis) {
    }

    private record NormalFishPool(String id, int minTier, int minFishingLevel, Set<String> weather, List<NormalFishPoolEntry> fish) {
        private boolean matchesWeather(Player player) {
            if (weather.isEmpty()) {
                return true;
            }
            World world = player == null ? null : player.getWorld();
            if (world == null) {
                return false;
            }
            boolean storm = world.hasStorm();
            boolean thunder = world.isThundering();
            return weather.contains("rain") && storm || weather.contains("thunder") && thunder;
        }
    }

    private record NormalFishPoolEntry(String fishId, int weight, Set<String> requiredBait) {
        NormalFishPoolEntry {
            fishId = normalize(fishId);
            requiredBait = requiredBait == null ? Set.of() : Set.copyOf(requiredBait);
        }
    }

    private record WeightedNormalFish(NormalFishDefinition fish, double weight) {
    }
}
