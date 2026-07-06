package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import com.servercore.combat.damage.DamagePacket;
import com.servercore.combat.damage.DamageResult;
import com.servercore.enchant.EquipmentEnchantService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class PlayerRecoveryManager implements Listener {

    private static final String PATH = "survival";
    private static PlayerRecoveryManager instance;

    private final ServerCorePlugin plugin;
    private final Map<UUID, Long> lastCombatAt = new HashMap<>();
    private final Map<UUID, Map<String, Long>> foodCooldownUntil = new HashMap<>();
    private final Map<String, FoodEntry> foodsById = new LinkedHashMap<>();
    private final Map<Material, FoodEntry> foodsByMaterial = new LinkedHashMap<>();
    private BukkitTask recoveryTask;

    public PlayerRecoveryManager(ServerCorePlugin plugin) {
        this.plugin = plugin;
        instance = this;
        loadFoodEntries();
        applyNaturalRegenerationRule();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        this.recoveryTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::tickRecovery,
                getTickInterval(),
                getTickInterval()
        );
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                syncFood(player);
            }
        });
    }

    public static PlayerRecoveryManager getInstance() {
        return instance;
    }

    public void stop() {
        if (recoveryTask != null) {
            recoveryTask.cancel();
            recoveryTask = null;
        }
    }

    public void reload() {
        loadFoodEntries();
        applyNaturalRegenerationRule();
    }

    public void markCombat(Player player) {
        if (player != null) {
            lastCombatAt.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    public void markCombat(DamagePacket packet, DamageResult result) {
        if (packet == null || result == null || !result.applied() || result.actualDamage() <= 0.0) {
            return;
        }
        if (packet.source() instanceof Player player) {
            markCombat(player);
        }
        if (packet.target() instanceof Player player) {
            markCombat(player);
        }
    }

    public RecoveryStatus getRecoveryStatus(Player player) {
        if (player == null || player.isDead()) {
            return RecoveryStatus.DISABLED;
        }
        if (!outOfCombatRegenEnabled()) {
            return RecoveryStatus.DISABLED;
        }
        long remaining = getMillisUntilRecovery(player);
        if (remaining > 0L) {
            return RecoveryStatus.COMBAT;
        }
        if (isRecoveryBlocked(player)) {
            return RecoveryStatus.BLOCKED;
        }
        return isInjured(player) ? RecoveryStatus.RECOVERING : RecoveryStatus.READY;
    }

    public String formatStatus(Player player) {
        RecoveryStatus status = getRecoveryStatus(player);
        return switch (status) {
            case COMBAT -> "战斗中 " + formatSeconds(getMillisUntilRecovery(player));
            case BLOCKED -> "附近威胁";
            case RECOVERING -> "恢复中";
            case READY -> "安全";
            case DISABLED -> "补给锁定";
        };
    }

    public double getExitSeconds(Player player) {
        double base = plugin.getConfig().getDouble(PATH + ".out_of_combat_regen.base_exit_seconds", 12.0);
        double min = plugin.getConfig().getDouble(PATH + ".out_of_combat_regen.min_exit_seconds", 4.0);
        double reduction = plugin.getConfig().getDouble(
                PATH + ".out_of_combat_regen.willpower_seconds_reduction_per_point", 0.035);
        AttributeManager attributes = AttributeManager.getInstance();
        int willpower = attributes == null || player == null ? 0 : attributes.getWillpower(player);
        return Math.max(min, base - willpower * reduction);
    }

    public long getMillisUntilRecovery(Player player) {
        long last = lastCombatAt.getOrDefault(player.getUniqueId(), 0L);
        if (last <= 0L) {
            return 0L;
        }
        long wait = Math.round(getExitSeconds(player) * 1000.0);
        return Math.max(0L, wait - (System.currentTimeMillis() - last));
    }

    public double estimateOutOfCombatRegenPerSecond(Player player) {
        if (player == null || !outOfCombatRegenEnabled()) {
            return 0.0;
        }

        double maxHealth = getMaxHealth(player);
        double percent = plugin.getConfig().getDouble(PATH + ".out_of_combat_regen.heal_percent_per_second", 0.08);
        return baseFlatRegen(player) + maxHealth * Math.max(0.0, percent);
    }

    private void tickRecovery() {
        applyNaturalRegenerationRule();
        int tickInterval = getTickInterval();
        double seconds = tickInterval / 20.0;

        for (Player player : Bukkit.getOnlinePlayers()) {
            syncFood(player);
            AttributeManager attributes = AttributeManager.getInstance();
            if (attributes != null) {
                attributes.refreshPlayer(player);
            }
            if (player.isDead() || player.getHealth() <= 0.0) {
                continue;
            }

            double maxHealth = getMaxHealth(player);
            if (player.getHealth() >= maxHealth) {
                continue;
            }

            if (getMillisUntilRecovery(player) > 0L) {
                tickGuardianCombatRegen(player, seconds);
                continue;
            }
            if (!outOfCombatRegenEnabled() || isRecoveryBlocked(player)) {
                continue;
            }

            double heal = estimateOutOfCombatRegenPerSecond(player) * seconds;
            heal(player, heal);
        }
    }

    private void tickGuardianCombatRegen(Player player, double seconds) {
        ClassManager classManager = ClassManager.getInstance();
        if (classManager == null || classManager.getPlayerClass(player) != ClassManager.PlayerClass.GUARDIAN) {
            return;
        }
        double heal = classManager.getBonusRegen(player) * classManager.getCombatRegenMultiplier(player) * seconds;
        heal(player, heal);
    }

    private void heal(Player player, double amount) {
        if (amount <= 0.0) {
            return;
        }
        EquipmentEnchantService equipmentEnchants = EquipmentEnchantService.getInstance();
        if (equipmentEnchants != null) {
            equipmentEnchants.heal(player, amount);
            return;
        }

        player.setHealth(Math.min(getMaxHealth(player), player.getHealth() + amount));
    }

    private double baseFlatRegen(Player player) {
        ClassManager classManager = ClassManager.getInstance();
        double classRegen = classManager == null ? 0.0 : classManager.getBonusRegen(player);
        EquipmentEnchantService equipmentEnchants = EquipmentEnchantService.getInstance();
        return equipmentEnchants == null ? classRegen : equipmentEnchants.modifyNaturalRegen(player, classRegen);
    }

    private void applyNaturalRegenerationRule() {
        if (plugin.getConfig().getBoolean(PATH + ".natural_regeneration.enabled", false)) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            world.setGameRule(GameRule.NATURAL_REGENERATION, false);
        }
    }

    private void syncFood(Player player) {
        if (player == null || plugin.getConfig().getBoolean(PATH + ".hunger.enabled", false)) {
            return;
        }
        player.setFoodLevel(plugin.getConfig().getInt(PATH + ".hunger.lock_food_level", 20));
        player.setSaturation((float) plugin.getConfig().getDouble(PATH + ".hunger.lock_saturation", 20.0));
        player.setExhaustion(0.0f);
    }

    private int getTickInterval() {
        return Math.max(1, plugin.getConfig().getInt(PATH + ".out_of_combat_regen.tick_interval", 20));
    }

    private boolean outOfCombatRegenEnabled() {
        return plugin.getConfig().getBoolean(PATH + ".out_of_combat_regen.enabled", true);
    }

    private boolean isInjured(Player player) {
        return player.getHealth() + 0.0001 < getMaxHealth(player);
    }

    private double getMaxHealth(Player player) {
        AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        return maxHealthAttribute == null ? 20.0 : Math.max(1.0, maxHealthAttribute.getValue());
    }

    private boolean isRecoveryBlocked(Player player) {
        double radius = plugin.getConfig().getDouble(PATH + ".out_of_combat_regen.block_near_hostile_radius", 12.0);
        if (radius <= 0.0) {
            return false;
        }

        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof Enemy && entity instanceof LivingEntity living
                    && living.isValid()
                    && !living.isDead()) {
                return true;
            }
        }
        return false;
    }

    private void loadFoodEntries() {
        foodsById.clear();
        foodsByMaterial.clear();

        ConfigurationSection section = plugin.getConfig().getConfigurationSection(PATH + ".foods");
        if (section == null) {
            registerDefaultFoods();
            return;
        }

        for (String id : section.getKeys(false)) {
            ConfigurationSection foodSection = section.getConfigurationSection(id);
            if (foodSection == null) {
                continue;
            }
            FoodEntry entry = FoodEntry.fromConfig(id, foodSection);
            registerFood(entry);
        }
        if (foodsByMaterial.isEmpty() && foodsById.isEmpty()) {
            registerDefaultFoods();
        }
    }

    private void registerDefaultFoods() {
        registerFood(new FoodEntry("apple", null, Material.APPLE, 0.10, 0.0, 8.0, "BASIC_FOOD"));
        registerFood(new FoodEntry("bread", null, Material.BREAD, 0.14, 0.0, 8.0, "BASIC_FOOD"));
        registerFood(new FoodEntry("cooked_beef", null, Material.COOKED_BEEF, 0.18, 0.0, 10.0, "BASIC_FOOD"));
        registerFood(new FoodEntry("cooked_porkchop", null, Material.COOKED_PORKCHOP, 0.18, 0.0, 10.0, "BASIC_FOOD"));
        registerFood(new FoodEntry("cooked_chicken", null, Material.COOKED_CHICKEN, 0.16, 0.0, 10.0, "BASIC_FOOD"));
        registerFood(new FoodEntry("cooked_cod", null, Material.COOKED_COD, 0.16, 0.0, 10.0, "SEAFOOD"));
        registerFood(new FoodEntry("cooked_salmon", null, Material.COOKED_SALMON, 0.18, 0.0, 10.0, "SEAFOOD"));
        registerFood(new FoodEntry("golden_apple", null, Material.GOLDEN_APPLE, 0.25, 40.0, 30.0, "SPECIAL_FOOD"));
    }

    private void registerFood(FoodEntry entry) {
        if (entry == null) {
            return;
        }
        if (entry.itemId() != null && !entry.itemId().isBlank()) {
            foodsById.put(normalize(entry.itemId()), entry);
        }
        if (entry.material() != null) {
            foodsByMaterial.put(entry.material(), entry);
        }
    }

    private FoodEntry findFood(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }

        String itemId = getItemId(item);
        if (itemId != null) {
            FoodEntry byId = foodsById.get(normalize(itemId));
            if (byId != null) {
                return byId;
            }
            if (!itemId.startsWith("vanilla_")) {
                return null;
            }
        }
        return foodsByMaterial.get(item.getType());
    }

    private boolean isServerCoreCustomItem(ItemStack item) {
        String itemId = getItemId(item);
        return itemId != null && !itemId.startsWith("vanilla_");
    }

    private String getItemId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(pdc.KEY_ITEM_ID, PersistentDataType.STRING);
    }

    private boolean useFood(Player player, EquipmentSlot hand, ItemStack item, FoodEntry food) {
        if (player == null || item == null || food == null) {
            return false;
        }

        long now = System.currentTimeMillis();
        Map<String, Long> cooldowns = foodCooldownUntil.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>());
        long until = cooldowns.getOrDefault(food.category(), 0L);
        if (until > now) {
            player.sendActionBar(Component.text("补给冷却: " + formatSeconds(until - now)));
            return true;
        }

        double maxHealth = getMaxHealth(player);
        heal(player, maxHealth * Math.max(0.0, food.instantHealPercent()));
        if (food.absorption() > 0.0) {
            player.setAbsorptionAmount(Math.max(player.getAbsorptionAmount(), food.absorption()));
        }
        cooldowns.put(food.category(), now + Math.round(food.cooldownSeconds() * 1000.0));
        player.setCooldown(item.getType(), Math.max(1, (int) Math.round(food.cooldownSeconds() * 20.0)));
        if (player.getGameMode() != GameMode.CREATIVE) {
            consumeOne(player, hand, item);
        }
        syncFood(player);
        player.sendActionBar(Component.text("已使用补给: " + food.displayName()));
        return true;
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

    private String formatSeconds(long millis) {
        return String.valueOf((long) Math.ceil(Math.max(0L, millis) / 1000.0)) + "s";
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    @EventHandler(ignoreCancelled = true)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player) {
            event.setCancelled(true);
            syncFood(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFoodInteract(PlayerInteractEvent event) {
        if (!event.getAction().isRightClick() || event.getHand() == null) {
            return;
        }
        Player player = event.getPlayer();
        if (event.getHand() == EquipmentSlot.OFF_HAND
                && findFood(player.getInventory().getItemInMainHand()) != null) {
            return;
        }
        ItemStack item = event.getHand() == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        FoodEntry food = findFood(item);
        if (food == null) {
            if (item != null && item.getType().isEdible() && isServerCoreCustomItem(item)) {
                event.setCancelled(true);
                syncFood(player);
            }
            return;
        }

        event.setCancelled(true);
        useFood(player, event.getHand(), item, food);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemConsume(PlayerItemConsumeEvent event) {
        ItemStack item = event.getItem();
        FoodEntry food = findFood(item);
        if (food == null) {
            if (item.getType().isEdible() || isServerCoreCustomItem(item)) {
                event.setCancelled(true);
                syncFood(event.getPlayer());
            }
            return;
        }

        event.setCancelled(true);
        useFood(event.getPlayer(), event.getHand(), item, food);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> syncFood(event.getPlayer()));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> syncFood(event.getPlayer()));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        syncFood(event.getPlayer());
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        if (!plugin.getConfig().getBoolean(PATH + ".natural_regeneration.enabled", false)) {
            event.getWorld().setGameRule(GameRule.NATURAL_REGENERATION, false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastCombatAt.remove(id);
        foodCooldownUntil.remove(id);
    }

    public enum RecoveryStatus {
        DISABLED,
        COMBAT,
        BLOCKED,
        RECOVERING,
        READY
    }

    private record FoodEntry(
            String id,
            String itemId,
            Material material,
            double instantHealPercent,
            double absorption,
            double cooldownSeconds,
            String category
    ) {
        static FoodEntry fromConfig(String id, ConfigurationSection section) {
            String itemId = section.getString("item_id", null);
            Material material = null;
            String materialName = section.getString("material", null);
            if (materialName == null && section.isList("materials") && !section.getStringList("materials").isEmpty()) {
                materialName = section.getStringList("materials").get(0);
            }
            if (materialName != null && !materialName.isBlank()) {
                material = Material.matchMaterial(materialName);
            }
            return new FoodEntry(
                    id,
                    itemId,
                    material,
                    section.getDouble("instant_heal_percent", 0.0),
                    section.getDouble("absorption", 0.0),
                    section.getDouble("cooldown_seconds", 8.0),
                    section.getString("category", "BASIC_FOOD")
            );
        }

        String displayName() {
            return id;
        }
    }
}
