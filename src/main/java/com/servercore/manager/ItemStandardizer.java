package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ItemStandardizer implements Listener {

    public static final int VANILLA_SCALE_VERSION = 8;
    private static final double VANILLA_WEAPON_DAMAGE_MULTIPLIER = 3.0;
    private static final double VANILLA_ARMOR_MULTIPLIER = 5.0;
    
    private final ServerCorePlugin plugin;
    private final MythicCompatModule mythicCompat;

    public ItemStandardizer(ServerCorePlugin plugin) {
        this.plugin = plugin;
        this.mythicCompat = new MythicCompatModule(plugin);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                standardizePlayerInventory(player);
            }
        });
    }

    @EventHandler
    public void onCraft(PrepareItemCraftEvent event) {
        ItemStack result = event.getInventory().getResult();
        if (result != null && !result.getType().isAir()) {
            standardizeItem(result);
            event.getInventory().setResult(result);
        }
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player) {
            standardizeItem(event.getItem().getItemStack());
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getClickedInventory() == event.getView().getBottomInventory()) {
            ItemStack item = event.getCurrentItem();
            if (item != null) standardizeItem(item);
        }
        ItemStack cursor = event.getCursor();
        if (cursor != null) standardizeItem(cursor);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        standardizePlayerInventory(event.getPlayer());
    }

    private void standardizePlayerInventory(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null) standardizeItem(item);
        }
        ItemStack[] armorContents = player.getInventory().getArmorContents();
        for (ItemStack item : armorContents) {
            if (item != null) standardizeItem(item);
        }
        player.getInventory().setArmorContents(armorContents);
        standardizeItem(player.getInventory().getItemInOffHand());
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            standardizePlayerInventory(player);
        }
    }

    private void standardizeItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return;

        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) return;
        
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer itemPdc = meta.getPersistentDataContainer();
        boolean isEquipment = isEquipment(item.getType());

        // 1. 检查是否已被当前倍率版本接管。旧版本装备会自动迁移。
        boolean alreadyStandardized = itemPdc.has(pdc.KEY_ITEM_ID, PersistentDataType.STRING);
        int scaleVersion = itemPdc.getOrDefault(pdc.KEY_ITEM_SCALE_VERSION, PersistentDataType.INTEGER, 0);
        if (alreadyStandardized && scaleVersion >= VANILLA_SCALE_VERSION) {
            if (isEquipment) {
                finishEquipmentItem(item, meta, mythicCompat.isMythicItem(item), false);
            } else {
                applyStoredWeaponTemplate(item);
                ItemFormatManager formatManager = ItemFormatManager.getInstance();
                if (formatManager != null) {
                    formatManager.formatItem(item);
                }
            }
            return;
        }

        if (isEquipment && hasExistingServerCoreState(itemPdc, pdc)) {
            String itemId = itemPdc.get(pdc.KEY_ITEM_ID, PersistentDataType.STRING);
            boolean isMythic = mythicCompat.isMythicItem(item);
            if (itemId == null || itemId.isBlank()) {
                itemId = isMythic
                        ? "mythic_" + mythicCompat.getMythicName(item).toLowerCase(Locale.ROOT)
                        : "vanilla_" + item.getType().name().toLowerCase(Locale.ROOT);
                itemPdc.set(pdc.KEY_ITEM_ID, PersistentDataType.STRING, itemId);
            }
            itemPdc.set(pdc.KEY_ITEM_SCALE_VERSION, PersistentDataType.INTEGER, VANILLA_SCALE_VERSION);
            finishEquipmentItem(item, meta, isMythic, true);
            return;
        }

        // 2. 非装备物品只做轻量接管：名称、品质、回收价格等系统需要稳定 item_id。
        if (!isEquipment) {
            if (!alreadyStandardized) {
                itemPdc.set(pdc.KEY_ITEM_ID, PersistentDataType.STRING, "vanilla_" + item.getType().name().toLowerCase(Locale.ROOT));
            }
            if (!itemPdc.has(pdc.KEY_ITEM_RARITY, PersistentDataType.STRING)) {
                ItemFormatManager formatManager = ItemFormatManager.getInstance();
                ItemFormatManager.Rarity rarity = formatManager == null
                        ? ItemFormatManager.Rarity.COMMON
                        : formatManager.getRarity(item);
                itemPdc.set(pdc.KEY_ITEM_RARITY, PersistentDataType.STRING, rarity.name());
            }
            itemPdc.set(pdc.KEY_ITEM_SCALE_VERSION, PersistentDataType.INTEGER, VANILLA_SCALE_VERSION);
            item.setItemMeta(meta);
            applyStoredWeaponTemplate(item);

            ItemFormatManager formatManager = ItemFormatManager.getInstance();
            if (formatManager != null) {
                formatManager.formatItem(item);
            }
            return;
        }

        // 3. 装备开始完整接管
        String itemId;
        boolean isMythic = mythicCompat.isMythicItem(item);
        
        if (isMythic) {
            itemId = itemPdc.getOrDefault(pdc.KEY_ITEM_ID, PersistentDataType.STRING, "mythic_" + mythicCompat.getMythicName(item).toLowerCase());
        } else {
            itemId = itemPdc.getOrDefault(pdc.KEY_ITEM_ID, PersistentDataType.STRING, "vanilla_" + item.getType().name().toLowerCase());
            
            // 为原版武器写入默认属性
            double scaledDamage = getDefaultDamage(item.getType()) * VANILLA_WEAPON_DAMAGE_MULTIPLIER;
            if (scaledDamage > 0) {
                itemPdc.set(pdc.KEY_BASE_DAMAGE, PersistentDataType.DOUBLE, scaledDamage);
            }

            double scaledArmor = getDefaultArmor(item.getType()) * VANILLA_ARMOR_MULTIPLIER;
            if (scaledArmor > 0) {
                itemPdc.set(pdc.KEY_BASE_ARMOR, PersistentDataType.DOUBLE, scaledArmor);
            }

            if (item.getType() == Material.SHIELD) {
                itemPdc.set(pdc.KEY_SHIELD_BLOCK_THRESHOLD, PersistentDataType.DOUBLE, 10.0);
                itemPdc.set(pdc.KEY_SHIELD_EFFECTIVE_BLOCK, PersistentDataType.DOUBLE, 0.6);
                itemPdc.set(pdc.KEY_SHIELD_COOLDOWN_SECONDS, PersistentDataType.DOUBLE, 2.0);
            }
        }
        
        // 统一写入 ITEM_ID
        itemPdc.set(pdc.KEY_ITEM_ID, PersistentDataType.STRING, itemId);
        itemPdc.set(pdc.KEY_ITEM_SCALE_VERSION, PersistentDataType.INTEGER, VANILLA_SCALE_VERSION);
        finishEquipmentItem(item, meta, isMythic, true);
    }

    private void finishEquipmentItem(ItemStack item, ItemMeta meta, boolean isMythic, boolean applyDefaultTemplate) {
        // 原版属性只负责动画/手感展示，真实战斗数值统一从 ServerCore 的 PDC 面板读取。
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);

        if (isWeapon(item.getType())) {
            neutralizeVanillaWeaponAttributes(meta, item.getType());
        }

        if (!isMythic && isArmor(item.getType())) {
            neutralizeVanillaArmorAttributes(meta, item.getType());
        }

        item.setItemMeta(meta);
        if (applyDefaultTemplate) {
            applyStoredOrDefaultWeaponTemplate(item);
        } else {
            applyStoredWeaponTemplate(item);
        }

        ItemFormatManager formatManager = ItemFormatManager.getInstance();
        if (formatManager != null) {
            formatManager.formatItem(item);
        }
    }

    private void neutralizeVanillaWeaponAttributes(ItemMeta meta, Material material) {
        meta.removeAttributeModifier(Attribute.GENERIC_ATTACK_DAMAGE);
        meta.removeAttributeModifier(Attribute.GENERIC_ATTACK_SPEED);

        meta.addAttributeModifier(Attribute.GENERIC_ATTACK_DAMAGE,
                new AttributeModifier(
                        new NamespacedKey(plugin, "neutralized_damage"),
                        0.0,
                        AttributeModifier.Operation.ADD_NUMBER,
                        EquipmentSlotGroup.MAINHAND
                ));

        double vanillaSpeed = getDefaultAttackSpeed(material);
        meta.addAttributeModifier(Attribute.GENERIC_ATTACK_SPEED,
                new AttributeModifier(
                        new NamespacedKey(plugin, "preserved_speed"),
                        vanillaSpeed,
                        AttributeModifier.Operation.ADD_NUMBER,
                        EquipmentSlotGroup.MAINHAND
                ));
    }

    private boolean hasExistingServerCoreState(PersistentDataContainer container, PDCManager pdc) {
        return hasAnyStat(container, pdc)
                || container.has(pdc.KEY_ITEM_REFORGE_ID, PersistentDataType.STRING)
                || container.has(pdc.KEY_ITEM_REFORGE_STATS, PersistentDataType.STRING)
                || container.has(pdc.KEY_ITEM_SOCKET_TYPES, PersistentDataType.STRING)
                || container.has(pdc.KEY_ITEM_SOCKET_GEMS, PersistentDataType.STRING)
                || container.has(pdc.KEY_ITEM_CUSTOM_ENCHANTS, PersistentDataType.STRING)
                || container.has(pdc.KEY_ITEM_ABILITIES, PersistentDataType.STRING)
                || container.has(pdc.KEY_ITEM_STORY_LORE, PersistentDataType.STRING)
                || container.has(pdc.KEY_WEAPON_TEMPLATE, PersistentDataType.STRING)
                || container.has(pdc.KEY_WEAPON_HAND_RULE, PersistentDataType.STRING)
                || container.has(pdc.KEY_ITEM_OVERRIDE_ID, PersistentDataType.STRING);
    }

    private boolean hasAnyStat(PersistentDataContainer container, PDCManager pdc) {
        return container.has(pdc.KEY_BASE_DAMAGE, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_BASE_MULTIPLIER, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_CRIT_CHANCE, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_CRIT_DAMAGE, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_BRUTALITY, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_LIFESTEAL, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_ARMOR_PEN, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_BASE_ARMOR, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_ATTACK_SPEED_BONUS, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_SHIELD_BLOCK_THRESHOLD, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_SHIELD_EFFECTIVE_BLOCK, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_SHIELD_COOLDOWN_SECONDS, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_ATTR_TOUGHNESS, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_ATTR_AGILITY, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_ATTR_INTELLIGENCE, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_ATTR_WILLPOWER, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_ATTR_LUCK, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_TOOL_FORTUNE, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_COLLECTION_FORTUNE, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_FORAGING_FORTUNE, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_FARMING_FORTUNE, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_EXCAVATION_FORTUNE, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_MINING_FORTUNE, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_TOOL_SWEEP, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_COLLECTION_SWEEP, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_FORAGING_SWEEP, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_FARMING_SWEEP, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_EXCAVATION_SWEEP, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_TOOL_SPREAD, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_MINING_SPREAD, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_TOOL_MINING_SPEED, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_BREAKING_POWER, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_PURITY, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_MINING_PURITY, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_FISHING_SPEED, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_SEA_CREATURE_CHANCE, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_TREASURE_CHANCE, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_BOUNTY, PersistentDataType.DOUBLE)
                || container.has(pdc.KEY_OVERBLOOM, PersistentDataType.DOUBLE);
    }

    private void applyStoredWeaponTemplate(ItemStack item) {
        WeaponTemplateManager templateManager = WeaponTemplateManager.getInstance();
        if (templateManager == null) {
            return;
        }

        WeaponTemplateManager.WeaponTemplate template = templateManager.getTemplate(item);
        if (template != null) {
            templateManager.applyTemplateToItem(item, template);
        }
    }

    private void applyStoredOrDefaultWeaponTemplate(ItemStack item) {
        WeaponTemplateManager templateManager = WeaponTemplateManager.getInstance();
        if (templateManager == null || item == null || item.getType().isAir()) {
            return;
        }

        WeaponTemplateManager.WeaponTemplate template = templateManager.getTemplate(item);
        if (template == null || isVanillaManagedItem(item)) {
            template = templateManager.getDefaultTemplate(item.getType());
        }
        if (template != null) {
            templateManager.applyTemplateToItem(item, template);
        }
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

    private boolean isEquipment(Material mat) {
        String name = mat.name();
        return name.endsWith("_SWORD") || name.endsWith("_AXE") || name.endsWith("_PICKAXE") 
            || name.endsWith("_SHOVEL") || name.endsWith("_HOE") || name.endsWith("_HELMET") 
            || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")
            || mat == Material.BOW || mat == Material.CROSSBOW || mat == Material.TRIDENT 
            || mat == Material.SHIELD || mat == Material.MACE;
    }

    private double getDefaultDamage(Material mat) {
        return switch (mat) {
            case WOODEN_SWORD, GOLDEN_SWORD -> 4.0;
            case STONE_SWORD -> 5.0;
            case IRON_SWORD -> 6.0;
            case DIAMOND_SWORD -> 7.0;
            case NETHERITE_SWORD -> 8.0;
            case WOODEN_AXE, GOLDEN_AXE -> 7.0;
            case STONE_AXE, IRON_AXE, DIAMOND_AXE -> 9.0;
            case NETHERITE_AXE -> 10.0;
            case MACE -> 6.0;
            case TRIDENT -> 9.0;
            default -> 0.0;
        };
    }

    private double getDefaultArmor(Material mat) {
        return switch (mat) {
            case LEATHER_HELMET, GOLDEN_HELMET, CHAINMAIL_HELMET, IRON_HELMET -> 2.0;
            case TURTLE_HELMET, DIAMOND_HELMET, NETHERITE_HELMET -> 3.0;
            case LEATHER_CHESTPLATE -> 3.0;
            case GOLDEN_CHESTPLATE, CHAINMAIL_CHESTPLATE -> 5.0;
            case IRON_CHESTPLATE -> 6.0;
            case DIAMOND_CHESTPLATE, NETHERITE_CHESTPLATE -> 8.0;
            case LEATHER_LEGGINGS -> 2.0;
            case GOLDEN_LEGGINGS -> 3.0;
            case CHAINMAIL_LEGGINGS -> 4.0;
            case IRON_LEGGINGS -> 5.0;
            case DIAMOND_LEGGINGS, NETHERITE_LEGGINGS -> 6.0;
            case LEATHER_BOOTS, GOLDEN_BOOTS, CHAINMAIL_BOOTS -> 1.0;
            case IRON_BOOTS -> 2.0;
            case DIAMOND_BOOTS, NETHERITE_BOOTS -> 3.0;
            default -> 0.0;
        };
    }

    private double getDefaultArmorToughness(Material mat) {
        return switch (mat) {
            case DIAMOND_HELMET, DIAMOND_CHESTPLATE, DIAMOND_LEGGINGS, DIAMOND_BOOTS -> 2.0;
            case NETHERITE_HELMET, NETHERITE_CHESTPLATE, NETHERITE_LEGGINGS, NETHERITE_BOOTS -> 3.0;
            default -> 0.0;
        };
    }

    /**
     * 判断是否是需要屏蔽原版攻击属性的武器。
     * 防具不需要屏蔽（它们没有攻击伤害修改器）。
     */
    private boolean isWeapon(Material mat) {
        String name = mat.name();
        return name.endsWith("_SWORD") || name.endsWith("_AXE") || name.endsWith("_PICKAXE")
            || name.endsWith("_SHOVEL") || name.endsWith("_HOE")
            || mat == Material.TRIDENT || mat == Material.MACE;
    }

    private boolean isArmor(Material mat) {
        String name = mat.name();
        return name.endsWith("_HELMET")
                || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS");
    }

    private void neutralizeVanillaArmorAttributes(ItemMeta meta, Material mat) {
        meta.removeAttributeModifier(Attribute.GENERIC_ARMOR);
        meta.removeAttributeModifier(Attribute.GENERIC_ARMOR_TOUGHNESS);

        EquipmentSlotGroup slotGroup = getArmorSlotGroup(mat);
        if (slotGroup == null) return;

        String suffix = mat.name().toLowerCase(Locale.ROOT);
        meta.addAttributeModifier(Attribute.GENERIC_ARMOR,
                new AttributeModifier(
                        new NamespacedKey(plugin, "neutralized_armor_" + suffix),
                        0.0,
                        AttributeModifier.Operation.ADD_NUMBER,
                        slotGroup
                ));
        meta.addAttributeModifier(Attribute.GENERIC_ARMOR_TOUGHNESS,
                new AttributeModifier(
                        new NamespacedKey(plugin, "neutralized_toughness_" + suffix),
                        0.0,
                        AttributeModifier.Operation.ADD_NUMBER,
                        slotGroup
                ));
    }

    private EquipmentSlotGroup getArmorSlotGroup(Material mat) {
        String name = mat.name();
        if (name.endsWith("_HELMET")) return EquipmentSlotGroup.HEAD;
        if (name.endsWith("_CHESTPLATE")) return EquipmentSlotGroup.CHEST;
        if (name.endsWith("_LEGGINGS")) return EquipmentSlotGroup.LEGS;
        if (name.endsWith("_BOOTS")) return EquipmentSlotGroup.FEET;
        return null;
    }

    private void refreshVanillaTransitionLore(ItemMeta meta, Material mat) {
        List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        PlainTextComponentSerializer plainText = PlainTextComponentSerializer.plainText();
        lore.removeIf(component -> {
            String text = plainText.serialize(component);
            return text.contains("基础面板伤害") || text.contains("护甲值");
        });

        double scaledDamage = getDefaultDamage(mat) * VANILLA_WEAPON_DAMAGE_MULTIPLIER;
        if (scaledDamage > 0.0) {
            lore.add(ServerCorePlugin.getMiniMessage()
                    .deserialize("<gray>★ 基础面板伤害: <green>+" + formatStat(scaledDamage) + "</green></gray>")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        }

        double scaledArmor = getDefaultArmor(mat) * VANILLA_ARMOR_MULTIPLIER;
        if (scaledArmor > 0.0) {
            lore.add(ServerCorePlugin.getMiniMessage()
                    .deserialize("<gray>★ 护甲值: <green>+" + formatStat(scaledArmor) + "</green></gray>")
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        }

        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
    }

    private String formatStat(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return String.valueOf((int) Math.rint(value));
        }
        return String.format(java.util.Locale.US, "%.1f", value);
    }

    /**
     * 获取原版武器的默认攻击速度值。
     * 原版攻击速度 base = 4.0，武器的 modifier 是负数偏移量。
     * 例如剑的攻击速度为 1.6，则 modifier = 1.6 - 4.0 = -2.4。
     */
    private double getDefaultAttackSpeed(Material mat) {
        return switch (mat) {
            // 剑：1.6 攻速 → -2.4
            case WOODEN_SWORD, STONE_SWORD, IRON_SWORD, DIAMOND_SWORD, NETHERITE_SWORD, GOLDEN_SWORD -> -2.4;
            // 锹：1.0 攻速 → -3.0
            case WOODEN_SHOVEL, STONE_SHOVEL, IRON_SHOVEL, DIAMOND_SHOVEL, NETHERITE_SHOVEL, GOLDEN_SHOVEL -> -3.0;
            // 镐：1.2 攻速 → -2.8
            case WOODEN_PICKAXE, STONE_PICKAXE, IRON_PICKAXE, DIAMOND_PICKAXE, NETHERITE_PICKAXE, GOLDEN_PICKAXE -> -2.8;
            // 斧：不同材质不同攻速
            case WOODEN_AXE, STONE_AXE -> -3.2; // 0.8 攻速
            case IRON_AXE, DIAMOND_AXE, NETHERITE_AXE -> -3.1; // 0.9 攻速 (1.21)
            case GOLDEN_AXE -> -3.0; // 1.0 攻速
            // 锄：不同材质不同攻速
            case WOODEN_HOE, GOLDEN_HOE -> -3.0; // 1.0 攻速
            case STONE_HOE -> -2.0; // 2.0 攻速
            case IRON_HOE -> -1.0; // 3.0 攻速
            case DIAMOND_HOE, NETHERITE_HOE -> 0.0; // 4.0 攻速
            // 三叉戟：1.1 攻速 → -2.9
            case TRIDENT -> -2.9;
            // 锤：1.6 攻速 → -2.4 (1.21 新增)
            case MACE -> -2.4;
            default -> 0.0;
        };
    }
}
