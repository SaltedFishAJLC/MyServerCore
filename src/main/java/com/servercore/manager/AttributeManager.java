package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import com.servercore.combat.damage.DamageService;
import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.trait.TraitModifier;
import dev.aurelium.auraskills.api.trait.Traits;
import dev.aurelium.auraskills.api.user.SkillsUser;
import dev.aurelium.auraskills.api.util.AuraSkillsModifier.Operation;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 五维属性 (5-Dimension Attributes) 计算引擎。
 * 聚合防具、饰品和护符包上的力量、敏捷、智慧、意志、幸运，并派生到战斗系统。
 * 底层仍兼容旧的 attr_toughness 存储键；AuraSkills 的坚韧继续接入本插件敏捷。
 */
public class AttributeManager implements Listener {

    private static final double TOUGHNESS_HEALTH_PER_POINT = 2.0;
    private static final double AGILITY_ARMOR_PER_POINT = 1.5;
    private static final double INTELLIGENCE_MAGIC_REDUCTION_PER_POINT = 0.002;
    private static final double INTELLIGENCE_MANA_PER_POINT = 2.5;
    private static final double WILLPOWER_REGEN_PER_POINT = 0.1;
    private static final double LUCK_DODGE_PER_POINT = 0.0015;
    private static final double LUCK_CRIT_PER_POINT = 0.0015;
    private static final long COMBAT_WINDOW_MS = 3_000L;
    private static final String MANA_TRAIT_MODIFIER = "servercore_dimension_max_mana";

    private static AttributeManager instance;

    private final ServerCorePlugin plugin;
    private final NamespacedKey healthModifierKey;
    private final NamespacedKey speedModifierKey;
    private final Map<UUID, Long> lastDamageAt = new java.util.HashMap<>();
    private BukkitTask regenTask;

    public AttributeManager(ServerCorePlugin plugin) {
        this.plugin = plugin;
        this.healthModifierKey = new NamespacedKey(plugin, "dimension_health");
        this.speedModifierKey = new NamespacedKey(plugin, "class_speed");
        instance = this;

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        this.regenTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickHealthRegeneration, 20L, 20L);
    }

    public static AttributeManager getInstance() {
        return instance;
    }

    public void stop() {
        if (regenTask != null) {
            regenTask.cancel();
            regenTask = null;
        }
    }

    /**
     * 获取玩家身上的 力量 (Strength) 总值。
     */
    public int getToughness(Player player) {
        return getSnapshot(player).toughness();
    }

    public int getStrength(Player player) {
        return getToughness(player);
    }

    /**
     * 获取玩家身上的 敏捷 (Agility) 总值
     */
    public int getAgility(Player player) {
        return getSnapshot(player).agility();
    }

    /**
     * 获取玩家身上的 智慧 (Intelligence) 总值
     */
    public int getIntelligence(Player player) {
        return getSnapshot(player).intelligence();
    }

    /**
     * 获取玩家身上的 意志 (Willpower) 总值
     */
    public int getWillpower(Player player) {
        return getSnapshot(player).willpower();
    }

    /**
     * 获取玩家身上的 幸运 (Luck) 总值
     */
    public int getLuck(Player player) {
        return getSnapshot(player).luck();
    }

    public AttributeSnapshot getSnapshot(Player player) {
        EnumMap<CoreAttribute, Double> totals = new EnumMap<>(CoreAttribute.class);
        for (CoreAttribute attribute : CoreAttribute.values()) {
            totals.put(attribute, 0.0);
        }

        for (ItemStack armor : player.getInventory().getArmorContents()) {
            addItemAttributes(totals, armor);
        }

        WeaponTemplateManager weaponTemplateManager = WeaponTemplateManager.getInstance();
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        addItemAttributes(totals, mainHand, weaponTemplateManager == null ? 1.0
                : weaponTemplateManager.getEquipmentStatMultiplier(player, mainHand, EquipmentSlot.HAND));
        addItemAttributes(totals, offHand, weaponTemplateManager == null ? 0.0
                : weaponTemplateManager.getEquipmentStatMultiplier(player, offHand, EquipmentSlot.OFF_HAND));

        AccessoryManager accessoryManager = AccessoryManager.getInstance();
        if (accessoryManager != null) {
            for (ItemStack accessory : accessoryManager.loadAccessories(player)) {
                addItemAttributes(totals, accessory);
            }
            for (ItemStack talisman : accessoryManager.loadTalismanBag(player, 54)) {
                addItemAttributes(totals, talisman);
            }
        }

        AuraSkillsBridge auraSkillsBridge = AuraSkillsBridge.getInstance();
        if (auraSkillsBridge != null) {
            totals.merge(CoreAttribute.TOUGHNESS, (double) auraSkillsBridge.getToughnessBonus(player), Double::sum);
            totals.merge(CoreAttribute.AGILITY, (double) auraSkillsBridge.getAgilityBonus(player), Double::sum);
            totals.merge(CoreAttribute.INTELLIGENCE, (double) auraSkillsBridge.getIntelligenceBonus(player), Double::sum);
            totals.merge(CoreAttribute.WILLPOWER, (double) auraSkillsBridge.getWillpowerBonus(player), Double::sum);
            totals.merge(CoreAttribute.LUCK, (double) auraSkillsBridge.getLuckBonus(player), Double::sum);
        }

        return new AttributeSnapshot(
                roundAttribute(totals.get(CoreAttribute.TOUGHNESS)),
                roundAttribute(totals.get(CoreAttribute.AGILITY)),
                roundAttribute(totals.get(CoreAttribute.INTELLIGENCE)),
                roundAttribute(totals.get(CoreAttribute.WILLPOWER)),
                roundAttribute(totals.get(CoreAttribute.LUCK))
        );
    }

    public int getAttributeValue(Player player, CoreAttribute attribute) {
        AttributeSnapshot snapshot = getSnapshot(player);
        return switch (attribute) {
            case TOUGHNESS -> snapshot.toughness();
            case AGILITY -> snapshot.agility();
            case INTELLIGENCE -> snapshot.intelligence();
            case WILLPOWER -> snapshot.willpower();
            case LUCK -> snapshot.luck();
        };
    }

    public double getHealthBonus(Player player) {
        return getToughness(player) * TOUGHNESS_HEALTH_PER_POINT;
    }

    public double getArmorBonus(Player player) {
        return getAgility(player) * AGILITY_ARMOR_PER_POINT;
    }

    public double getMagicDamageReduction(Player player) {
        return clamp(getIntelligence(player) * INTELLIGENCE_MAGIC_REDUCTION_PER_POINT, 0.0, 0.90);
    }

    public double getMaxManaBonus(Player player) {
        AttributeSnapshot snapshot = getSnapshot(player);
        return getMaxManaBonus(player, snapshot);
    }

    public double getMaxManaBonus(Player player, AttributeSnapshot snapshot) {
        ClassManager classManager = ClassManager.getInstance();
        double classMana = classManager == null ? 0.0 : classManager.getBonusMana(player);
        return snapshot.intelligence() * INTELLIGENCE_MANA_PER_POINT + classMana;
    }

    public double getEffectiveMaxMana(Player player) {
        syncManaModifier(player, getMaxManaBonus(player));
        SkillsUser user = getSkillsUser(player);
        return user == null ? 100.0 + getMaxManaBonus(player) : user.getMaxMana();
    }

    public double getHealthRegenPerSecond(Player player) {
        ClassManager classManager = ClassManager.getInstance();
        double classRegen = classManager == null ? 0.0 : classManager.getBonusRegen(player);
        return getWillpower(player) * WILLPOWER_REGEN_PER_POINT + classRegen;
    }

    public double getDodgeChance(Player player) {
        return clamp(getLuck(player) * LUCK_DODGE_PER_POINT, 0.0, 1.0);
    }

    public double getCritChanceBonus(Player player) {
        return getLuck(player) * LUCK_CRIT_PER_POINT;
    }

    public void refreshPlayer(Player player) {
        AttributeSnapshot snapshot = getSnapshot(player);
        ClassManager classManager = ClassManager.getInstance();

        applyHealthModifier(player, snapshot, classManager);
        applyClassSpeedModifiers(player, classManager);
        syncManaModifier(player, getMaxManaBonus(player, snapshot));
    }

    /**
     * 处理战斗受到伤害时的 闪避 (Dodge) 判定。
     * 由 EntityDamageEvent 触发，如果成功闪避，返回 true，并产生特效。
     */
    public boolean processDodge(Player player) {
        double chance = getDodgeChance(player);
        if (chance <= 0.0 || ThreadLocalRandom.current().nextDouble() >= chance) {
            return false;
        }

        player.getWorld().spawnParticle(Particle.CLOUD, player.getLocation().add(0.0, 1.0, 0.0), 16, 0.35, 0.45, 0.35, 0.02);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.45f, 1.65f);
        player.sendActionBar(Component.text("闪避!"));
        ClassPassiveManager classPassiveManager = ClassPassiveManager.getInstance();
        if (classPassiveManager != null) {
            classPassiveManager.onPlayerDodged(player);
        }
        return true;
    }

    /**
     * 定时任务：每秒执行一次，根据玩家的意志(Wil) 和 职业(Class) 计算并恢复生命值。
     * 判定 3 秒脱战规则。
     */
    public void tickHealthRegeneration() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshPlayer(player);
            if (player.isDead() || player.getHealth() <= 0.0) {
                continue;
            }

            double regen = getHealthRegenPerSecond(player);
            if (regen <= 0.0) {
                continue;
            }

            AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            double maxHealth = maxHealthAttribute == null ? 20.0 : maxHealthAttribute.getValue();
            if (player.getHealth() >= maxHealth) {
                continue;
            }

            double multiplier = getRegenMultiplier(player, now);
            double healed = regen * multiplier;
            if (healed > 0.0) {
                player.setHealth(Math.min(maxHealth, player.getHealth() + healed));
            }
        }
    }

    public boolean isMagicDamageCause(EntityDamageEvent.DamageCause cause) {
        return switch (cause) {
            case MAGIC, DRAGON_BREATH, WITHER, POISON -> true;
            default -> false;
        };
    }

    private void addItemAttributes(EnumMap<CoreAttribute, Double> totals, ItemStack item) {
        addItemAttributes(totals, item, 1.0);
    }

    private void addItemAttributes(EnumMap<CoreAttribute, Double> totals, ItemStack item, double multiplier) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return;
        }
        if (Math.abs(multiplier) < 0.0001) {
            return;
        }

        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return;
        }

        for (CoreAttribute attribute : CoreAttribute.values()) {
            NamespacedKey key = attribute.key(pdc);
            double value = item.getItemMeta().getPersistentDataContainer()
                    .getOrDefault(key, PersistentDataType.DOUBLE, 0.0);
            totals.merge(attribute, value * multiplier, Double::sum);
        }
    }

    private void applyHealthModifier(Player player, AttributeSnapshot snapshot, ClassManager classManager) {
        AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHealthAttribute == null) {
            return;
        }

        double baseHealth = maxHealthAttribute.getBaseValue();
        double modifierAmount = snapshot.toughness() * TOUGHNESS_HEALTH_PER_POINT;
        double healthBeforePenalty = baseHealth + modifierAmount;
        double penaltyRate = classManager == null ? 0.0 : classManager.getMaxHealthPenaltyRate(player);
        if (penaltyRate > 0.0) {
            modifierAmount -= healthBeforePenalty * penaltyRate;
        }

        modifierAmount = Math.max(1.0 - baseHealth, modifierAmount);
        replaceAttributeModifier(maxHealthAttribute, healthModifierKey, modifierAmount, AttributeModifier.Operation.ADD_NUMBER);

        double maxHealth = maxHealthAttribute.getValue();
        if (player.getHealth() > maxHealth) {
            player.setHealth(maxHealth);
        }
    }

    private void applyClassSpeedModifiers(Player player, ClassManager classManager) {
        double movementRate = classManager == null ? 0.0 : classManager.getMovementSpeedBonus(player) / 100.0;

        AttributeInstance movementSpeed = player.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        if (movementSpeed != null) {
            replaceAttributeModifier(movementSpeed, speedModifierKey, movementRate, AttributeModifier.Operation.ADD_SCALAR);
        }

        WeaponTemplateManager weaponTemplateManager = WeaponTemplateManager.getInstance();
        if (weaponTemplateManager != null) {
            weaponTemplateManager.applyPlayerAttackSpeedBonus(player, weaponTemplateManager.getPlayerAttackSpeedBonusScore(player));
        }
    }

    private void replaceAttributeModifier(AttributeInstance attribute, NamespacedKey key, double amount, AttributeModifier.Operation operation) {
        for (AttributeModifier modifier : new ArrayList<>(attribute.getModifiers())) {
            if (modifier.getKey().equals(key)) {
                attribute.removeModifier(modifier);
            }
        }

        if (Math.abs(amount) > 0.0001) {
            attribute.addModifier(new AttributeModifier(key, amount, operation));
        }
    }

    private void syncManaModifier(Player player, double bonus) {
        SkillsUser user = getSkillsUser(player);
        if (user == null) {
            return;
        }

        TraitModifier existing = user.getTraitModifier(MANA_TRAIT_MODIFIER);
        if (bonus <= 0.0001) {
            if (existing != null) {
                user.removeTraitModifier(MANA_TRAIT_MODIFIER);
            }
            return;
        }

        if (existing != null && Math.abs(existing.value() - bonus) <= 0.0001) {
            return;
        }

        if (existing != null) {
            user.removeTraitModifier(MANA_TRAIT_MODIFIER);
        }

        TraitModifier modifier = new TraitModifier(MANA_TRAIT_MODIFIER, Traits.MAX_MANA, bonus, Operation.ADD);
        modifier.setNonPersistent();
        user.addTraitModifier(modifier);
    }

    private SkillsUser getSkillsUser(Player player) {
        AuraSkillsApi api = AuraSkillsApi.get();
        return api == null ? null : api.getUser(player.getUniqueId());
    }

    private double getRegenMultiplier(Player player, long now) {
        long lastDamage = lastDamageAt.getOrDefault(player.getUniqueId(), 0L);
        if (now - lastDamage <= COMBAT_WINDOW_MS) {
            ClassManager classManager = ClassManager.getInstance();
            return classManager == null ? 0.35 : classManager.getCombatRegenMultiplier(player);
        }

        return player.getFoodLevel() >= 20 ? 1.0 : 0.5;
    }

    private int roundAttribute(Double value) {
        return (int) Math.round(value == null ? 0.0 : value);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIncomingDamage(EntityDamageEvent event) {
        if (DamageService.isInternalDamageActive()) {
            return;
        }

        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        if (processDodge(player)) {
            event.setCancelled(true);
            event.setDamage(0.0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageMonitor(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && event.getFinalDamage() > 0.0) {
            lastDamageAt.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> refreshPlayer(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldItemChange(PlayerItemHeldEvent event) {
        scheduleCacheRefresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        scheduleCacheRefresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            scheduleCacheRefresh(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            scheduleCacheRefresh(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            scheduleCacheRefresh(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastDamageAt.remove(event.getPlayer().getUniqueId());
        SkillsUser user = getSkillsUser(event.getPlayer());
        if (user != null && user.getTraitModifier(MANA_TRAIT_MODIFIER) != null) {
            user.removeTraitModifier(MANA_TRAIT_MODIFIER);
        }
    }

    private void scheduleCacheRefresh(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            PlayerStatCache cache = PlayerStatCache.getInstance();
            if (cache != null) {
                cache.updateCache(player);
            } else {
                refreshPlayer(player);
            }
        });
    }

    public enum CoreAttribute {
        TOUGHNESS("str", "力量", "Str"),
        AGILITY("agi", "敏捷", "Agi"),
        INTELLIGENCE("int", "智慧", "Int"),
        WILLPOWER("wil", "意志", "Wil"),
        LUCK("luk", "幸运", "Luk");

        private final String storageKey;
        private final String displayName;
        private final String shortName;

        CoreAttribute(String storageKey, String displayName, String shortName) {
            this.storageKey = storageKey;
            this.displayName = displayName;
            this.shortName = shortName;
        }

        public String storageKey() {
            return storageKey;
        }

        public String displayName() {
            return displayName;
        }

        public String shortName() {
            return shortName;
        }

        public String displayLabel() {
            return displayName + " (" + shortName + ")";
        }

        public NamespacedKey key(PDCManager pdc) {
            return switch (this) {
                case TOUGHNESS -> pdc.KEY_ATTR_TOUGHNESS;
                case AGILITY -> pdc.KEY_ATTR_AGILITY;
                case INTELLIGENCE -> pdc.KEY_ATTR_INTELLIGENCE;
                case WILLPOWER -> pdc.KEY_ATTR_WILLPOWER;
                case LUCK -> pdc.KEY_ATTR_LUCK;
            };
        }

        public static CoreAttribute fromInput(String input) {
            if (input == null) {
                return null;
            }

            String normalized = input.toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "str", "strength", "力量", "tou", "toughness", "坚韧" -> TOUGHNESS;
                case "agi", "agility", "敏捷" -> AGILITY;
                case "int", "intelligence", "智慧" -> INTELLIGENCE;
                case "wil", "will", "willpower", "意志" -> WILLPOWER;
                case "luk", "luck", "幸运" -> LUCK;
                default -> null;
            };
        }
    }

    public record AttributeSnapshot(
            int toughness,
            int agility,
            int intelligence,
            int willpower,
            int luck
    ) {
        public int value(CoreAttribute attribute) {
            return switch (attribute) {
                case TOUGHNESS -> toughness;
                case AGILITY -> agility;
                case INTELLIGENCE -> intelligence;
                case WILLPOWER -> willpower;
                case LUCK -> luck;
            };
        }
    }
}
