package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Owns weapon template metadata, hand rules, and vanilla attribute injection.
 */
public class WeaponTemplateManager implements Listener {

    private static final double PLAYER_BASE_ATTACK_SPEED = 4.0;
    private static final double PLAYER_BASE_ENTITY_RANGE = 3.0;
    private static final double MAX_ATTACK_SPEED_BONUS = 100.0;

    private static WeaponTemplateManager instance;

    private final ServerCorePlugin plugin;
    private final NamespacedKey weaponDamageKey;
    private final NamespacedKey weaponAttackSpeedKey;
    private final NamespacedKey weaponAttackRangeKey;
    private final NamespacedKey playerAttackSpeedBonusKey;
    private final NamespacedKey legacyClassAttackSpeedKey;
    private final NamespacedKey hasteAttackSpeedKey;
    private BukkitTask hasteIsolationTask;

    public WeaponTemplateManager(ServerCorePlugin plugin) {
        this.plugin = plugin;
        this.weaponDamageKey = new NamespacedKey(plugin, "weapon_template_neutralized_damage");
        this.weaponAttackSpeedKey = new NamespacedKey(plugin, "weapon_template_attack_speed");
        this.weaponAttackRangeKey = new NamespacedKey(plugin, "weapon_template_attack_range");
        this.playerAttackSpeedBonusKey = new NamespacedKey(plugin, "global_attack_speed_bonus");
        this.legacyClassAttackSpeedKey = new NamespacedKey(plugin, "class_attack_speed");
        this.hasteAttackSpeedKey = NamespacedKey.minecraft("effect.haste");
        instance = this;

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        this.hasteIsolationTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickHasteIsolation, 1L, 1L);
    }

    public static WeaponTemplateManager getInstance() {
        return instance;
    }

    public WeaponProfile getProfile(WeaponTemplate template) {
        if (template == null) {
            return null;
        }

        String path = "weapon_templates." + template.name() + ".";
        double attackSpeed = plugin.getConfig().getDouble(path + "attack_speed", template.attackSpeed);
        double attackRange = plugin.getConfig().getDouble(path + "attack_range", template.attackRange);
        HandRule configuredRule = parseHandRule(plugin.getConfig().getString(path + "default_hand_rule"));
        HandRule defaultRule = configuredRule == null ? template.defaultHandRule() : configuredRule;
        double reliability = plugin.getConfig().getDouble(path + "reliability_factor", template.reliabilityFactor);
        double uptime = plugin.getConfig().getDouble(path + "uptime_factor", template.uptimeFactor);
        double aoe = plugin.getConfig().getDouble(path + "aoe_factor", template.aoeFactor);
        int cooldownTicks = plugin.getConfig().contains(path + "cooldown_ticks")
                ? Math.max(0, plugin.getConfig().getInt(path + "cooldown_ticks"))
                : defaultCooldownTicks(attackSpeed);

        return new WeaponProfile(
                template,
                attackSpeed,
                cooldownTicks,
                attackRange,
                defaultRule,
                defaultRule == HandRule.TWO_HANDED,
                template.isMelee(),
                template.isRanged(),
                clamp(reliability, 0.0, 5.0),
                clamp(uptime, 0.0, 5.0),
                clamp(aoe, 0.0, 5.0),
                plugin.getConfig().getDouble(path + "shield_block_threshold", 10.0),
                clamp(plugin.getConfig().getDouble(path + "shield_effective_block", 0.6), 0.0, 1.0),
                plugin.getConfig().getDouble(path + "shield_cooldown_seconds", 2.0)
        );
    }

    public void stop() {
        if (hasteIsolationTask != null) {
            hasteIsolationTask.cancel();
            hasteIsolationTask = null;
        }
    }

    public enum HandRule {
        MAIN_HAND_ONLY,
        OFF_HAND_ONLY,
        BOTH_HANDS_ALLOWED,
        BOTH_HANDS,
        TWO_HANDED;

        public boolean allowsMainHand() {
            return this == MAIN_HAND_ONLY || this == BOTH_HANDS_ALLOWED || this == BOTH_HANDS || this == TWO_HANDED;
        }

        public boolean allowsOffHand() {
            return this == OFF_HAND_ONLY || this == BOTH_HANDS_ALLOWED || this == BOTH_HANDS;
        }

        public boolean isBothHandsAllowed() {
            return this == BOTH_HANDS_ALLOWED || this == BOTH_HANDS;
        }
    }

    public enum WeaponTemplate {
        ONE_HANDED_SWORD(1.6, 3.0, true, HandRule.MAIN_HAND_ONLY, true, false, 1.00, 0.90, 1.0),
        TWO_HANDED_SWORD(0.9, 4.0, true, HandRule.TWO_HANDED, true, false, 0.95, 0.82, 1.0),
        ONE_HANDED_AXE(1.2, 2.7, true, HandRule.MAIN_HAND_ONLY, true, false, 0.95, 0.88, 1.0),
        TWO_HANDED_AXE(0.8, 3.5, true, HandRule.TWO_HANDED, true, false, 0.90, 0.80, 1.0),
        HEAVY_HAMMER(0.6, 3.5, true, HandRule.TWO_HANDED, true, false, 0.85, 0.75, 1.0),
        TRIDENT(1.0, 4.0, true, HandRule.TWO_HANDED, true, false, 0.95, 0.85, 1.0),
        DAGGER(1.8, 2.7, true, HandRule.BOTH_HANDS_ALLOWED, true, false, 1.00, 0.95, 1.0),
        SCYTHE(1.1, 3.5, true, HandRule.TWO_HANDED, true, false, 0.95, 0.82, 1.0),
        SHORTBOW(2.0, -1.0, false, HandRule.TWO_HANDED, false, true, 0.90, 0.90, 1.0),
        LONGBOW(0.75, -1.0, false, HandRule.TWO_HANDED, false, true, 0.80, 0.75, 1.0),
        CROSSBOW(0.8, -1.0, false, HandRule.MAIN_HAND_ONLY, false, true, 0.85, 0.80, 1.0),
        SHIELD(0.0, 0.0, false, HandRule.OFF_HAND_ONLY, false, false, 1.0, 1.0, 1.0);

        public final double attackSpeed;
        public final double attackRange;
        private final boolean injectVanillaAttributes;
        private final HandRule defaultHandRule;
        private final boolean melee;
        private final boolean ranged;
        private final double reliabilityFactor;
        private final double uptimeFactor;
        private final double aoeFactor;

        WeaponTemplate(double attackSpeed, double attackRange, boolean injectVanillaAttributes, HandRule defaultHandRule,
                       boolean melee, boolean ranged, double reliabilityFactor, double uptimeFactor, double aoeFactor) {
            this.attackSpeed = attackSpeed;
            this.attackRange = attackRange;
            this.injectVanillaAttributes = injectVanillaAttributes;
            this.defaultHandRule = defaultHandRule;
            this.melee = melee;
            this.ranged = ranged;
            this.reliabilityFactor = reliabilityFactor;
            this.uptimeFactor = uptimeFactor;
            this.aoeFactor = aoeFactor;
        }

        public boolean injectVanillaAttributes() {
            return injectVanillaAttributes;
        }

        public HandRule defaultHandRule() {
            return defaultHandRule;
        }

        public boolean isMelee() {
            return melee;
        }

        public boolean isRanged() {
            return ranged;
        }

        public boolean isShield() {
            return this == SHIELD;
        }
    }

    public record WeaponProfile(
            WeaponTemplate template,
            double attackSpeed,
            int cooldownTicks,
            double attackRange,
            HandRule defaultHandRule,
            boolean twoHanded,
            boolean melee,
            boolean ranged,
            double reliabilityFactor,
            double uptimeFactor,
            double aoeFactor,
            double shieldBlockThreshold,
            double shieldEffectiveBlock,
            double shieldCooldownSeconds
    ) {
    }

    public record HandValidationResult(
            boolean canUseMainWeapon,
            boolean canUseOffhandWeapon,
            boolean canUseShield,
            String reason
    ) {
    }

    public void applyTemplateToItem(ItemStack item, WeaponTemplate template) {
        if (item == null || item.getType().isAir() || template == null) {
            return;
        }

        PDCManager pdc = PDCManager.getInstance();
        ItemMeta meta = item.getItemMeta();
        if (pdc == null || meta == null) {
            return;
        }

        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(pdc.KEY_WEAPON_TEMPLATE, PersistentDataType.STRING, template.name());

        if (isVanillaManagedItem(item) || !container.has(pdc.KEY_WEAPON_HAND_RULE, PersistentDataType.STRING)) {
            container.set(pdc.KEY_WEAPON_HAND_RULE, PersistentDataType.STRING, getDefaultHandRule(item, template).name());
        }

        removeManagedTemplateModifiers(meta);

        WeaponProfile profile = getProfile(template);
        if (template.injectVanillaAttributes() && profile != null) {
            meta.removeAttributeModifier(Attribute.GENERIC_ATTACK_DAMAGE);
            meta.addAttributeModifier(Attribute.GENERIC_ATTACK_DAMAGE,
                    new AttributeModifier(
                            weaponDamageKey,
                            0.0,
                            AttributeModifier.Operation.ADD_NUMBER,
                            EquipmentSlotGroup.MAINHAND
                    ));

            meta.addAttributeModifier(Attribute.GENERIC_ATTACK_SPEED,
                    new AttributeModifier(
                            weaponAttackSpeedKey,
                            profile.attackSpeed() - PLAYER_BASE_ATTACK_SPEED,
                            AttributeModifier.Operation.ADD_NUMBER,
                            EquipmentSlotGroup.MAINHAND
                    ));

            if (profile.attackRange() > 0.0) {
                meta.addAttributeModifier(Attribute.PLAYER_ENTITY_INTERACTION_RANGE,
                        new AttributeModifier(
                                weaponAttackRangeKey,
                                profile.attackRange() - PLAYER_BASE_ENTITY_RANGE,
                                AttributeModifier.Operation.ADD_NUMBER,
                                EquipmentSlotGroup.MAINHAND
                        ));
            }
        }

        item.setItemMeta(meta);
    }

    public void applyTemplateToItem(ItemStack item, String templateName) {
        WeaponTemplate template = parseTemplate(templateName);
        if (template != null) {
            applyTemplateToItem(item, template);
        }
    }

    public void applyHandRuleToItem(ItemStack item, HandRule handRule) {
        if (item == null || item.getType().isAir() || handRule == null) {
            return;
        }

        PDCManager pdc = PDCManager.getInstance();
        ItemMeta meta = item.getItemMeta();
        if (pdc == null || meta == null) {
            return;
        }

        meta.getPersistentDataContainer().set(pdc.KEY_WEAPON_HAND_RULE, PersistentDataType.STRING, handRule.name());
        item.setItemMeta(meta);
    }

    public WeaponTemplate getTemplate(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }

        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return null;
        }

        String raw = item.getItemMeta().getPersistentDataContainer().get(pdc.KEY_WEAPON_TEMPLATE, PersistentDataType.STRING);
        return parseTemplate(raw);
    }

    public WeaponTemplate parseTemplate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String normalized = normalizeEnumInput(raw);
        return switch (normalized) {
            case "SINGLE_SWORD", "ONE_HAND_SWORD", "ONE_HANDED_SWORD", "1H_SWORD", "SWORD" -> WeaponTemplate.ONE_HANDED_SWORD;
            case "TWO_HAND_SWORD", "TWO_HANDED_SWORD", "2H_SWORD", "GREATSWORD", "GREAT_SWORD" -> WeaponTemplate.TWO_HANDED_SWORD;
            case "SINGLE_AXE", "ONE_HAND_AXE", "ONE_HANDED_AXE", "1H_AXE" -> WeaponTemplate.ONE_HANDED_AXE;
            case "AXE", "TWO_HAND_AXE", "TWO_HANDED_AXE", "2H_AXE", "GREATAXE", "GREAT_AXE" -> WeaponTemplate.TWO_HANDED_AXE;
            case "HAMMER", "HEAVY_HAMMER", "MACE" -> WeaponTemplate.HEAVY_HAMMER;
            case "TRIDENT", "SPEAR" -> WeaponTemplate.TRIDENT;
            case "DAGGER", "KNIFE" -> WeaponTemplate.DAGGER;
            case "SCYTHE", "SICKLE" -> WeaponTemplate.SCYTHE;
            case "SHORT_BOW", "SHORTBOW" -> WeaponTemplate.SHORTBOW;
            case "LONG_BOW", "LONGBOW", "BOW" -> WeaponTemplate.LONGBOW;
            case "CROSS_BOW", "CROSSBOW" -> WeaponTemplate.CROSSBOW;
            case "SHIELD", "BUCKLER" -> WeaponTemplate.SHIELD;
            default -> {
                try {
                    yield WeaponTemplate.valueOf(normalized);
                } catch (IllegalArgumentException ignored) {
                    yield null;
                }
            }
        };
    }

    public HandRule parseHandRule(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String normalized = normalizeEnumInput(raw);
        return switch (normalized) {
            case "MAIN", "MAIN_HAND", "MAINHAND", "MAIN_HAND_ONLY", "MAIN_ONLY", "PRIMARY" -> HandRule.MAIN_HAND_ONLY;
            case "OFF", "OFF_HAND", "OFFHAND", "OFF_HAND_ONLY", "OFF_ONLY", "SECONDARY" -> HandRule.OFF_HAND_ONLY;
            case "BOTH", "BOTH_HANDS", "BOTH_HANDS_ALLOWED", "EITHER", "EITHER_HAND", "DUAL", "ONE_HANDED" -> HandRule.BOTH_HANDS_ALLOWED;
            case "TWO_HAND", "TWO_HANDED", "2H", "TWO_HANDED_ONLY" -> HandRule.TWO_HANDED;
            default -> {
                try {
                    yield HandRule.valueOf(normalized);
                } catch (IllegalArgumentException ignored) {
                    yield null;
                }
            }
        };
    }

    public WeaponTemplate getDefaultTemplate(Material material) {
        if (material == null || material.isAir()) {
            return null;
        }

        String name = material.name();
        if (name.endsWith("_SWORD")) {
            return WeaponTemplate.ONE_HANDED_SWORD;
        }
        if (name.endsWith("_AXE")) {
            return WeaponTemplate.TWO_HANDED_AXE;
        }
        if (name.endsWith("_HOE")) {
            return WeaponTemplate.SCYTHE;
        }
        if (material == Material.MACE) {
            return WeaponTemplate.HEAVY_HAMMER;
        }
        if (material == Material.TRIDENT) {
            return WeaponTemplate.TRIDENT;
        }
        if (material == Material.BOW) {
            return WeaponTemplate.LONGBOW;
        }
        if (material == Material.CROSSBOW) {
            return WeaponTemplate.CROSSBOW;
        }
        if (material == Material.SHIELD) {
            return WeaponTemplate.SHIELD;
        }
        return null;
    }

    public HandRule getHandRule(ItemStack item) {
        WeaponTemplate template = getTemplate(item);
        return getHandRule(item, template);
    }

    public HandRule getHandRule(ItemStack item, WeaponTemplate template) {
        if (item == null || item.getType().isAir()) {
            return null;
        }

        PDCManager pdc = PDCManager.getInstance();
        if (pdc != null && item.hasItemMeta()) {
            PersistentDataContainer container = item.getItemMeta().getPersistentDataContainer();
            HandRule stored = parseHandRule(container.get(pdc.KEY_WEAPON_HAND_RULE, PersistentDataType.STRING));
            if (stored != null) {
                return stored;
            }
        }

        if (template == null) {
            template = getDefaultTemplate(item.getType());
        }
        return template == null ? null : getDefaultHandRule(item, template);
    }

    public HandRule getDefaultHandRule(ItemStack item, WeaponTemplate template) {
        if (template == null) {
            return null;
        }
        WeaponProfile profile = getProfile(template);
        HandRule configuredDefault = profile == null ? template.defaultHandRule() : profile.defaultHandRule();
        if (template == WeaponTemplate.SHIELD) {
            return HandRule.OFF_HAND_ONLY;
        }
        if (isVanillaManagedItem(item) && template != WeaponTemplate.SHIELD) {
            return configuredDefault == HandRule.TWO_HANDED ? HandRule.TWO_HANDED : HandRule.MAIN_HAND_ONLY;
        }
        return configuredDefault;
    }

    public double getEquipmentStatMultiplier(Player player, ItemStack item, EquipmentSlot slot) {
        if (item == null || item.getType().isAir() || slot == null) {
            return 0.0;
        }

        HandRule rule = getHandRule(item);
        WeaponTemplate template = getTemplate(item);
        boolean managedWeapon = rule != null || template != null || isWeaponMaterial(item.getType());
        if (!managedWeapon) {
            return slot == EquipmentSlot.HAND ? 1.0 : 0.0;
        }

        if (slot == EquipmentSlot.HAND) {
            if (rule == null || !rule.allowsMainHand()) {
                return 0.0;
            }
            if (rule == HandRule.TWO_HANDED && hasBlockingOffhandItem(player)) {
                return 0.0;
            }
            return 1.0;
        }

        if (slot == EquipmentSlot.OFF_HAND) {
            if (rule == HandRule.OFF_HAND_ONLY) {
                return 1.0;
            }
            if (rule != null && rule.isBothHandsAllowed()) {
                return 0.5;
            }
        }

        return 0.0;
    }

    public boolean canUseMainHandWeapon(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return true;
        }

        HandRule rule = getHandRule(item);
        WeaponTemplate template = getTemplate(item);
        boolean managedWeapon = rule != null || template != null || isWeaponMaterial(item.getType());
        if (!managedWeapon) {
            return true;
        }
        return getEquipmentStatMultiplier(player, item, EquipmentSlot.HAND) > 0.0;
    }

    public boolean canUseOffhandEquipment(Player player, ItemStack item) {
        return getEquipmentStatMultiplier(player, item, EquipmentSlot.OFF_HAND) > 0.0;
    }

    public boolean isTwoHandBlocked(Player player, ItemStack item) {
        return getHandRule(item) == HandRule.TWO_HANDED && hasBlockingOffhandItem(player);
    }

    public HandValidationResult validateHands(Player player) {
        if (player == null) {
            return new HandValidationResult(false, false, false, "玩家不存在。");
        }

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        boolean canUseMain = canUseMainHandWeapon(player, mainHand);
        boolean canUseOffhand = canUseOffhandEquipment(player, offHand);
        WeaponTemplate offhandTemplate = getTemplate(offHand);
        boolean canUseShield = offhandTemplate == WeaponTemplate.SHIELD || (offHand != null && offHand.getType() == Material.SHIELD);
        canUseShield = canUseShield && canUseOffhand;

        String reason = "";
        if (!canUseMain && isTwoHandBlocked(player, mainHand)) {
            reason = "双手武器需要空出副手。";
        } else if (!canUseMain) {
            reason = "这件武器不能在主手使用。";
        } else if (offHand != null && !offHand.getType().isAir() && !canUseOffhand) {
            reason = "这件装备不能在副手使用。";
        }

        return new HandValidationResult(canUseMain, canUseOffhand, canUseShield, reason);
    }

    public void applyPlayerAttackSpeedBonus(Player player, double bonusScore) {
        if (player == null) {
            return;
        }

        AttributeInstance attackSpeed = player.getAttribute(Attribute.GENERIC_ATTACK_SPEED);
        if (attackSpeed == null) {
            return;
        }

        removeModifier(attackSpeed, playerAttackSpeedBonusKey);
        removeModifier(attackSpeed, legacyClassAttackSpeedKey);
        stripHasteAttackSpeedModifiers(player);

        double clamped = clamp(bonusScore, 0.0, MAX_ATTACK_SPEED_BONUS);
        if (clamped > 0.0001) {
            attackSpeed.addModifier(new AttributeModifier(
                    playerAttackSpeedBonusKey,
                    clamped / 100.0,
                    AttributeModifier.Operation.MULTIPLY_SCALAR_1
            ));
        }
    }

    public double getPlayerAttackSpeedBonusScore(Player player) {
        double bonus = 0.0;

        ClassManager classManager = ClassManager.getInstance();
        if (classManager != null) {
            bonus += classManager.getAttackSpeedBonus(player);
        }

        PDCManager pdc = PDCManager.getInstance();
        if (pdc != null && player != null) {
            PlayerInventory inventory = player.getInventory();
            for (ItemStack armor : inventory.getArmorContents()) {
                bonus += pdc.getStat(armor, pdc.KEY_ATTACK_SPEED_BONUS);
            }

            bonus += pdc.getStat(inventory.getItemInMainHand(), pdc.KEY_ATTACK_SPEED_BONUS)
                    * getEquipmentStatMultiplier(player, inventory.getItemInMainHand(), EquipmentSlot.HAND);
            bonus += pdc.getStat(inventory.getItemInOffHand(), pdc.KEY_ATTACK_SPEED_BONUS)
                    * getEquipmentStatMultiplier(player, inventory.getItemInOffHand(), EquipmentSlot.OFF_HAND);

            AccessoryManager accessoryManager = AccessoryManager.getInstance();
            if (accessoryManager != null) {
                for (ItemStack accessory : accessoryManager.loadAccessories(player)) {
                    bonus += pdc.getStat(accessory, pdc.KEY_ATTACK_SPEED_BONUS);
                }
                for (ItemStack talisman : accessoryManager.loadTalismanBag(player, 54)) {
                    bonus += pdc.getStat(talisman, pdc.KEY_ATTACK_SPEED_BONUS);
                }
            }
        }

        return clamp(bonus, 0.0, MAX_ATTACK_SPEED_BONUS);
    }

    public int getCooldownTicks(Player player, WeaponTemplate template) {
        WeaponProfile profile = getProfile(template);
        if (profile == null || profile.attackSpeed() <= 0.0) {
            return 0;
        }

        double bonus = getPlayerAttackSpeedBonusScore(player);
        double cooldown = profile.cooldownTicks() * 100.0 / (100.0 + bonus);
        return Math.max(1, (int) Math.round(cooldown));
    }

    public boolean canShootWithoutConsumingArrow(Player player, ItemStack weapon) {
        if (player == null) {
            return false;
        }
        return player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR
                || (weapon != null && weapon.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.INFINITY) > 0);
    }

    public void stripHasteAttackSpeedModifiers(Player player) {
        if (player == null) {
            return;
        }

        AttributeInstance attackSpeed = player.getAttribute(Attribute.GENERIC_ATTACK_SPEED);
        if (attackSpeed == null) {
            return;
        }

        for (AttributeModifier modifier : new ArrayList<>(attackSpeed.getModifiers())) {
            NamespacedKey key = modifier.getKey();
            if (key.equals(hasteAttackSpeedKey)
                    || (key.getNamespace().equals(NamespacedKey.MINECRAFT) && key.getKey().toLowerCase(Locale.ROOT).contains("haste"))) {
                attackSpeed.removeModifier(modifier);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        ItemStack newOffhand = event.getOffHandItem();
        HandRule offhandRule = getHandRule(newOffhand);
        if (offhandRule == HandRule.MAIN_HAND_ONLY || offhandRule == HandRule.TWO_HANDED) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text("这件装备不能放在副手。"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            Bukkit.getScheduler().runTask(plugin, () -> validateOffhand(player));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPotionEffect(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        PotionEffect newEffect = event.getNewEffect();
        PotionEffect oldEffect = event.getOldEffect();
        boolean touchesHaste = (newEffect != null && newEffect.getType().equals(PotionEffectType.HASTE))
                || (oldEffect != null && oldEffect.getType().equals(PotionEffectType.HASTE));
        if (touchesHaste) {
            Bukkit.getScheduler().runTask(plugin, () -> stripHasteAttackSpeedModifiers(player));
        }
    }

    private void validateOffhand(Player player) {
        ItemStack offhand = player.getInventory().getItemInOffHand();
        HandRule rule = getHandRule(offhand);
        if (rule != HandRule.MAIN_HAND_ONLY && rule != HandRule.TWO_HANDED) {
            return;
        }

        player.getInventory().setItemInOffHand(null);
        for (ItemStack leftover : player.getInventory().addItem(offhand).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
        player.sendActionBar(Component.text("这件装备不能放在副手。"));
    }

    private void tickHasteIsolation() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            stripHasteAttackSpeedModifiers(player);
        }
    }

    private boolean hasBlockingOffhandItem(Player player) {
        if (player == null) {
            return false;
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        return offhand != null && !offhand.getType().isAir();
    }

    private boolean isVanillaManagedItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }

        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return false;
        }

        String itemId = item.getItemMeta().getPersistentDataContainer().get(pdc.KEY_ITEM_ID, PersistentDataType.STRING);
        return itemId != null && (itemId.startsWith("vanilla_") || itemId.startsWith("minecraft:"));
    }

    private boolean isWeaponMaterial(Material material) {
        if (material == null || material.isAir()) {
            return false;
        }
        String name = material.name();
        return name.endsWith("_SWORD")
                || name.endsWith("_AXE")
                || name.endsWith("_HOE")
                || material == Material.BOW
                || material == Material.CROSSBOW
                || material == Material.TRIDENT
                || material == Material.MACE
                || material == Material.SHIELD;
    }

    private void removeManagedTemplateModifiers(ItemMeta meta) {
        meta.removeAttributeModifier(Attribute.GENERIC_ATTACK_SPEED);
        meta.removeAttributeModifier(Attribute.PLAYER_ENTITY_INTERACTION_RANGE);
    }

    private void removeModifier(AttributeInstance attribute, NamespacedKey key) {
        for (AttributeModifier modifier : new ArrayList<>(attribute.getModifiers())) {
            if (modifier.getKey().equals(key)) {
                attribute.removeModifier(modifier);
            }
        }
    }

    private int defaultCooldownTicks(double attackSpeed) {
        if (attackSpeed <= 0.0) {
            return 0;
        }
        return Math.max(1, (int) Math.round(20.0 / attackSpeed));
    }

    private String normalizeEnumInput(String raw) {
        return raw.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
