package com.servercore.manager;

import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.Pane.Priority;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import com.servercore.ServerCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 职业流派引擎 (Class System)
 * 负责职业切换、冷却判定、以及根据主副属性进行乘区与额外效果转化。
 */
public class ClassManager {

    private static final long SWITCH_COOLDOWN_MS = 60_000L;
    private static ClassManager instance;

    private final ServerCorePlugin plugin;

    public ClassManager(ServerCorePlugin plugin) {
        this.plugin = plugin;
        instance = this;
    }

    public static ClassManager getInstance() {
        return instance;
    }

    public enum PlayerClass {
        NONE("无职业", Material.BARRIER, null, null, List.of("未选择职业流派")),
        BLOOD_MAGE("血魔", Material.REDSTONE, AttributeManager.CoreAttribute.TOUGHNESS, AttributeManager.CoreAttribute.AGILITY,
                List.of("额外残暴值 = Power * 0.6", "基础吸血 +2.5%", "总吸血效果翻倍")),
        GUARDIAN("守护者", Material.SHIELD, AttributeManager.CoreAttribute.TOUGHNESS, AttributeManager.CoreAttribute.WILLPOWER,
                List.of("额外生命恢复 = Power * 0.08", "战斗中生命恢复保留至 70%", "承受附近队友 25% 实际伤害")),
        MARKSMAN("神射手", Material.BOW, AttributeManager.CoreAttribute.AGILITY, AttributeManager.CoreAttribute.INTELLIGENCE,
                List.of("额外暴击率 = Power * 0.2%", "额外破甲率 = Power * 0.2%", "箭矢获得轻微吸附")),
        RANGER("游侠", Material.FEATHER, AttributeManager.CoreAttribute.AGILITY, AttributeManager.CoreAttribute.WILLPOWER,
                List.of("攻击速度 = Power * 0.2", "移动速度 = Power * 0.6", "允许二段跳", "命中叠加移动速度")),
        PROPHET("先知", Material.ENCHANTED_BOOK, AttributeManager.CoreAttribute.INTELLIGENCE, AttributeManager.CoreAttribute.WILLPOWER,
                List.of("额外法力上限 = Power * 3.0", "消耗法力积累扭曲治疗光环")),
        CALAMITY_FAMILIAR("灾厄使魔", Material.MAGMA_CREAM, AttributeManager.CoreAttribute.INTELLIGENCE, AttributeManager.CoreAttribute.LUCK,
                List.of("额外法力上限 = Power * 2.0", "法术伤害通用乘区 = Power * 0.01", "代价: 扣除最大生命值 max(25%, Power * 0.12%)", "消耗法力转化血池")),
        GAMBLER("赌徒", Material.AMETHYST_SHARD, AttributeManager.CoreAttribute.WILLPOWER, AttributeManager.CoreAttribute.LUCK,
                List.of("额外暴击伤害 = Power * 0.25%", "MF = Power * 0.2", "代价: 扣除护甲值 max(30%, Power * 0.2%)", "攻击与稀有掉落进行两次判定")),
        ASSASSIN("刺客", Material.NETHERITE_SWORD, AttributeManager.CoreAttribute.AGILITY, AttributeManager.CoreAttribute.LUCK,
                List.of("移动速度 = Power * 0.8", "额外暴击伤害 = Power * 0.35%", "闪避后隐身并脱离非首领仇恨")),
        SPELLBLADE("魔剑士", Material.DIAMOND_SWORD, AttributeManager.CoreAttribute.TOUGHNESS, AttributeManager.CoreAttribute.INTELLIGENCE,
                List.of("额外法力上限 = Power * 1.5", "额外残暴值 = Power * 0.3", "无法暴击", "远程物理伤害降低至 20%", "近战攻击造成法术伤害")),
        REAPER("收割者", Material.NETHERITE_HOE, AttributeManager.CoreAttribute.TOUGHNESS, AttributeManager.CoreAttribute.LUCK,
                List.of("额外暴击率 = Power * 0.25%", "额外残暴值 = Power * 0.2", "目标每损失 1% 生命，伤害乘区 +1.5%"));

        private final String displayName;
        private final Material icon;
        private final AttributeManager.CoreAttribute firstAttribute;
        private final AttributeManager.CoreAttribute secondAttribute;
        private final List<String> bonusLore;

        PlayerClass(String displayName, Material icon, AttributeManager.CoreAttribute firstAttribute,
                    AttributeManager.CoreAttribute secondAttribute, List<String> bonusLore) {
            this.displayName = displayName;
            this.icon = icon;
            this.firstAttribute = firstAttribute;
            this.secondAttribute = secondAttribute;
            this.bonusLore = bonusLore;
        }

        public String displayName() {
            return displayName;
        }

        public Material icon() {
            return icon;
        }

        public AttributeManager.CoreAttribute firstAttribute() {
            return firstAttribute;
        }

        public AttributeManager.CoreAttribute secondAttribute() {
            return secondAttribute;
        }

        public List<String> bonusLore() {
            return bonusLore;
        }

        public boolean isPlayable() {
            return this != NONE;
        }

        public boolean allows(AttributeManager.CoreAttribute attribute) {
            return attribute != null && (attribute == firstAttribute || attribute == secondAttribute);
        }

        public static PlayerClass fromInput(String input) {
            if (input == null) {
                return NONE;
            }

            try {
                return PlayerClass.valueOf(input.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return NONE;
            }
        }
    }

    /**
     * 打开 /sc class 的职业选择面板
     */
    public void openClassSelectionGUI(Player player) {
        ChestGui gui = new ChestGui(4, "✦ 职业大厅 ✦");
        gui.setOnGlobalClick(event -> event.setCancelled(true));

        OutlinePane background = new OutlinePane(0, 0, 9, 4, Priority.LOWEST);
        background.addItem(new GuiItem(createGlass(Material.BLACK_STAINED_GLASS_PANE)));
        background.setRepeat(true);
        gui.addPane(background);

        StaticPane pane = new StaticPane(0, 0, 9, 4, Priority.HIGHEST);
        int slot = 0;
        for (PlayerClass playerClass : PlayerClass.values()) {
            if (!playerClass.isPlayable()) {
                continue;
            }

            pane.addItem(new GuiItem(createClassIcon(player, playerClass), event -> {
                event.setCancelled(true);
                Bukkit.getScheduler().runTask(plugin, () -> openAttributeChoiceGUI(player, playerClass));
            }), slot % 9, 1 + slot / 9);
            slot++;
        }

        pane.addItem(new GuiItem(createCurrentClassIcon(player)), 8, 3);
        gui.addPane(pane);
        gui.show(player);
    }

    private void openAttributeChoiceGUI(Player player, PlayerClass targetClass) {
        if (!targetClass.isPlayable()) {
            return;
        }

        ChestGui gui = new ChestGui(3, "✦ 选择主副属性 ✦");
        gui.setOnGlobalClick(event -> event.setCancelled(true));

        OutlinePane background = new OutlinePane(0, 0, 9, 3, Priority.LOWEST);
        background.addItem(new GuiItem(createGlass(Material.GRAY_STAINED_GLASS_PANE)));
        background.setRepeat(true);
        gui.addPane(background);

        StaticPane pane = new StaticPane(0, 0, 9, 3, Priority.HIGHEST);
        AttributeManager.CoreAttribute first = targetClass.firstAttribute();
        AttributeManager.CoreAttribute second = targetClass.secondAttribute();

        pane.addItem(new GuiItem(createChoiceIcon(targetClass, first, second), event -> {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> {
                selectClass(player, targetClass, first.storageKey(), second.storageKey());
                player.closeInventory();
            });
        }), 3, 1);

        pane.addItem(new GuiItem(createChoiceIcon(targetClass, second, first), event -> {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> {
                selectClass(player, targetClass, second.storageKey(), first.storageKey());
                player.closeInventory();
            });
        }), 5, 1);

        pane.addItem(new GuiItem(createBackIcon(), event -> {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> openClassSelectionGUI(player));
        }), 4, 2);

        gui.addPane(pane);
        gui.show(player);
    }

    /**
     * 玩家尝试切换职业，判定 60 秒冷却，并要求选择 Main / Sub 属性。
     */
    public void selectClass(Player player, PlayerClass targetClass, String mainAttribute, String subAttribute) {
        if (targetClass == null || !targetClass.isPlayable()) {
            player.sendMessage(ServerCorePlugin.getMiniMessage().deserialize("<red>未知职业流派。</red>"));
            return;
        }

        long remaining = getRemainingCooldownMillis(player);
        if (remaining > 0L) {
            player.sendMessage(ServerCorePlugin.getMiniMessage().deserialize("<red>职业切换冷却中，还需 " + formatSeconds(remaining) + " 秒。</red>"));
            return;
        }

        AttributeManager.CoreAttribute main = AttributeManager.CoreAttribute.fromInput(mainAttribute);
        AttributeManager.CoreAttribute sub = AttributeManager.CoreAttribute.fromInput(subAttribute);
        if (main == null || sub == null || main == sub || !targetClass.allows(main) || !targetClass.allows(sub)) {
            player.sendMessage(ServerCorePlugin.getMiniMessage().deserialize("<red>该职业只能在规定的两个维度之间选择主副属性。</red>"));
            return;
        }

        PDCManager pdcManager = PDCManager.getInstance();
        if (pdcManager == null) {
            player.sendMessage(ServerCorePlugin.getMiniMessage().deserialize("<red>PDC 管理器尚未初始化。</red>"));
            return;
        }

        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.set(pdcManager.KEY_PLAYER_CLASS, PersistentDataType.STRING, targetClass.name());
        pdc.set(pdcManager.KEY_PLAYER_CLASS_MAIN, PersistentDataType.STRING, main.storageKey());
        pdc.set(pdcManager.KEY_PLAYER_CLASS_SUB, PersistentDataType.STRING, sub.storageKey());
        pdc.set(pdcManager.KEY_PLAYER_CLASS_SWITCH_AT, PersistentDataType.LONG, System.currentTimeMillis());

        PlayerStatCache cache = PlayerStatCache.getInstance();
        if (cache != null) {
            cache.updateCache(player);
        }
        AttributeManager attributeManager = AttributeManager.getInstance();
        if (attributeManager != null) {
            attributeManager.refreshPlayer(player);
        }

        player.sendMessage(ServerCorePlugin.getMiniMessage().deserialize("<green>已切换为 <gold>" + targetClass.displayName() + "</gold>，主属性 <yellow>"
                + main.displayLabel() + "</yellow>，副属性 <aqua>" + sub.displayLabel() + "</aqua>。</green>"));
    }

    /**
     * 获取玩家当前的职业
     */
    public PlayerClass getPlayerClass(Player player) {
        PDCManager pdcManager = PDCManager.getInstance();
        if (pdcManager == null) {
            return PlayerClass.NONE;
        }

        String stored = player.getPersistentDataContainer().get(pdcManager.KEY_PLAYER_CLASS, PersistentDataType.STRING);
        return PlayerClass.fromInput(stored);
    }

    public AttributeManager.CoreAttribute getMainAttribute(Player player) {
        return getStoredAttribute(player, true);
    }

    public AttributeManager.CoreAttribute getSubAttribute(Player player) {
        return getStoredAttribute(player, false);
    }

    /**
     * 核心计算：根据玩家的主副属性，计算得出该职业体系下的 Power
     * Power = Main + 0.5 * Sub
     */
    public double getClassPower(Player player) {
        AttributePair pair = getValidAttributePair(player);
        if (pair == null) {
            return 0.0;
        }

        AttributeManager attributeManager = AttributeManager.getInstance();
        if (attributeManager == null) {
            return 0.0;
        }

        double main = attributeManager.getAttributeValue(player, pair.main());
        double sub = attributeManager.getAttributeValue(player, pair.sub());
        return main + 0.5 * sub;
    }

    /**
     * 核心计算：获取该职业提供给面板的额外攻击力
     * Atk = Main * 1.05 + Sub * 0.75
     */
    public double getClassAttackBonus(Player player) {
        AttributePair pair = getValidAttributePair(player);
        if (pair == null) {
            return 0.0;
        }

        AttributeManager attributeManager = AttributeManager.getInstance();
        if (attributeManager == null) {
            return 0.0;
        }

        double main = attributeManager.getAttributeValue(player, pair.main());
        double sub = attributeManager.getAttributeValue(player, pair.sub());
        return main * 1.05 + sub * 0.75;
    }

    public double getBonusBrutality(Player player) {
        return switch (getPlayerClass(player)) {
            case BLOOD_MAGE -> getClassPower(player) * 0.6;
            case SPELLBLADE -> getClassPower(player) * 0.3 + getSpellbladeConvertedBrutality(player);
            case REAPER -> getClassPower(player) * 0.2;
            default -> 0.0;
        };
    }

    public double getBonusRegen(Player player) {
        return getPlayerClass(player) == PlayerClass.GUARDIAN ? getClassPower(player) * 0.08 : 0.0;
    }

    public double getCombatRegenMultiplier(Player player) {
        return getPlayerClass(player) == PlayerClass.GUARDIAN ? 0.70 : 0.35;
    }

    public double getBonusCritChance(Player player) {
        return switch (getPlayerClass(player)) {
            case MARKSMAN -> getClassPower(player) * 0.002;
            case REAPER -> getClassPower(player) * 0.0025;
            default -> 0.0;
        };
    }

    public double getBonusCritDamage(Player player) {
        return switch (getPlayerClass(player)) {
            case GAMBLER -> getClassPower(player) * 0.0025;
            case ASSASSIN -> getClassPower(player) * 0.0035;
            default -> 0.0;
        };
    }

    public double getBonusArmorPen(Player player) {
        return getPlayerClass(player) == PlayerClass.MARKSMAN ? getClassPower(player) * 0.2 : 0.0;
    }

    public double getAttackSpeedBonus(Player player) {
        return getPlayerClass(player) == PlayerClass.RANGER ? getClassPower(player) * 0.2 : 0.0;
    }

    public double getMovementSpeedBonus(Player player) {
        double base = switch (getPlayerClass(player)) {
            case RANGER -> getClassPower(player) * 0.6;
            case ASSASSIN -> getClassPower(player) * 0.8;
            default -> 0.0;
        };
        ClassPassiveManager passiveManager = ClassPassiveManager.getInstance();
        return base + (passiveManager == null ? 0.0 : passiveManager.getRangerSpeedBonus(player));
    }

    public double getBonusMana(Player player) {
        return switch (getPlayerClass(player)) {
            case PROPHET -> getClassPower(player) * 3.0;
            case CALAMITY_FAMILIAR -> getClassPower(player) * 2.0;
            case SPELLBLADE -> getClassPower(player) * 1.5 + getSpellbladeConvertedMana(player);
            default -> 0.0;
        };
    }

    public double getMagicMultiplierBonus(Player player) {
        double classBonus = getPlayerClass(player) == PlayerClass.CALAMITY_FAMILIAR ? getClassPower(player) * 0.01 : 0.0;
        ClassPassiveManager passiveManager = ClassPassiveManager.getInstance();
        return classBonus + (passiveManager == null ? 0.0 : passiveManager.getCalamityBloodPoolMagicMultiplier(player));
    }

    public double getMaxHealthPenaltyRate(Player player) {
        return getPlayerClass(player) == PlayerClass.CALAMITY_FAMILIAR
                ? clamp(Math.max(0.25, getClassPower(player) * 0.0012), 0.0, 0.95)
                : 0.0;
    }

    public double getArmorPenaltyRate(Player player) {
        return getPlayerClass(player) == PlayerClass.GAMBLER
                ? clamp(Math.max(0.30, getClassPower(player) * 0.002), 0.0, 0.95)
                : 0.0;
    }

    public int getLootLevel(Player player) {
        return 0;
    }

    public double getMagicFindBonus(Player player) {
        return getPlayerClass(player) == PlayerClass.GAMBLER ? getClassPower(player) * 0.2 : 0.0;
    }

    public boolean suppressesCriticalHits(Player player) {
        return getPlayerClass(player) == PlayerClass.SPELLBLADE;
    }

    public double getPhysicalRangedDamageMultiplier(Player player) {
        return getPlayerClass(player) == PlayerClass.SPELLBLADE ? 0.2 : 1.0;
    }

    public boolean usesSpellbladeMeleeDamage(Player player, boolean isRanged, boolean isMagicCause) {
        return getPlayerClass(player) == PlayerClass.SPELLBLADE && !isRanged && !isMagicCause;
    }

    public double getBaseLifesteal(Player player) {
        return getPlayerClass(player) == PlayerClass.BLOOD_MAGE ? 0.025 : 0.0;
    }

    public double getLifestealMultiplier(Player player) {
        return getPlayerClass(player) == PlayerClass.BLOOD_MAGE ? 2.0 : 1.0;
    }

    public double getSpellbladeConvertedMana(Player player) {
        return getPlayerClass(player) == PlayerClass.SPELLBLADE ? getSpellbladeCritDamagePoints(player) : 0.0;
    }

    public double getSpellbladeConvertedBrutality(Player player) {
        return getPlayerClass(player) == PlayerClass.SPELLBLADE ? getSpellbladeCritDamagePoints(player) / 5.0 : 0.0;
    }

    private double getSpellbladeCritDamagePoints(Player player) {
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null || player == null) {
            return 0.0;
        }

        double critDamage = 0.0;
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            critDamage += pdc.getStat(armor, pdc.KEY_CRIT_DAMAGE);
        }

        WeaponTemplateManager weaponTemplateManager = WeaponTemplateManager.getInstance();
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        critDamage += pdc.getStat(mainHand, pdc.KEY_CRIT_DAMAGE) * (weaponTemplateManager == null ? 1.0
                : weaponTemplateManager.getEquipmentStatMultiplier(player, mainHand, EquipmentSlot.HAND));
        critDamage += pdc.getStat(offHand, pdc.KEY_CRIT_DAMAGE) * (weaponTemplateManager == null ? 0.0
                : weaponTemplateManager.getEquipmentStatMultiplier(player, offHand, EquipmentSlot.OFF_HAND));

        AccessoryManager accessoryManager = AccessoryManager.getInstance();
        if (accessoryManager != null) {
            for (ItemStack accessory : accessoryManager.loadAccessories(player)) {
                critDamage += pdc.getStat(accessory, pdc.KEY_CRIT_DAMAGE);
            }
            for (ItemStack talisman : accessoryManager.loadActiveTalismans(player)) {
                critDamage += pdc.getStat(talisman, pdc.KEY_CRIT_DAMAGE);
            }
        }

        return Math.max(0.0, critDamage * 100.0);
    }

    public long getRemainingCooldownMillis(Player player) {
        PDCManager pdcManager = PDCManager.getInstance();
        if (pdcManager == null) {
            return 0L;
        }

        Long lastSwitch = player.getPersistentDataContainer().get(pdcManager.KEY_PLAYER_CLASS_SWITCH_AT, PersistentDataType.LONG);
        if (lastSwitch == null || lastSwitch <= 0L) {
            return 0L;
        }

        return Math.max(0L, SWITCH_COOLDOWN_MS - (System.currentTimeMillis() - lastSwitch));
    }

    private AttributeManager.CoreAttribute getStoredAttribute(Player player, boolean main) {
        PlayerClass playerClass = getPlayerClass(player);
        if (!playerClass.isPlayable()) {
            return null;
        }

        PDCManager pdcManager = PDCManager.getInstance();
        if (pdcManager == null) {
            return main ? playerClass.firstAttribute() : playerClass.secondAttribute();
        }

        String stored = player.getPersistentDataContainer().get(
                main ? pdcManager.KEY_PLAYER_CLASS_MAIN : pdcManager.KEY_PLAYER_CLASS_SUB,
                PersistentDataType.STRING
        );
        AttributeManager.CoreAttribute attribute = AttributeManager.CoreAttribute.fromInput(stored);
        if (attribute == null || !playerClass.allows(attribute)) {
            return main ? playerClass.firstAttribute() : playerClass.secondAttribute();
        }
        return attribute;
    }

    private AttributePair getValidAttributePair(Player player) {
        PlayerClass playerClass = getPlayerClass(player);
        AttributeManager.CoreAttribute main = getMainAttribute(player);
        AttributeManager.CoreAttribute sub = getSubAttribute(player);
        if (!playerClass.isPlayable() || main == null || sub == null || main == sub || !playerClass.allows(main) || !playerClass.allows(sub)) {
            return null;
        }
        return new AttributePair(main, sub);
    }

    private ItemStack createClassIcon(Player player, PlayerClass playerClass) {
        ItemStack item = new ItemStack(playerClass.icon());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ServerCorePlugin.getMiniMessage()
                .deserialize("<gold><bold>" + playerClass.displayName() + "</bold></gold>")
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(ServerCorePlugin.getMiniMessage().deserialize("<gray>影响维度: <yellow>"
                + playerClass.firstAttribute().displayLabel() + "</yellow> / <aqua>"
                + playerClass.secondAttribute().displayLabel() + "</aqua></gray>").decoration(TextDecoration.ITALIC, false));
        lore.add(ServerCorePlugin.getMiniMessage().deserialize("<gray>Power = Main + 0.5 * Sub</gray>").decoration(TextDecoration.ITALIC, false));
        lore.add(ServerCorePlugin.getMiniMessage().deserialize("<gray>Atk = Main * 1.05 + Sub * 0.75</gray>").decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        for (String line : playerClass.bonusLore()) {
            lore.add(ServerCorePlugin.getMiniMessage().deserialize("<dark_gray>• " + line + "</dark_gray>").decoration(TextDecoration.ITALIC, false));
        }

        if (getPlayerClass(player) == playerClass) {
            lore.add(Component.empty());
            lore.add(ServerCorePlugin.getMiniMessage().deserialize("<green>当前流派 Power: " + formatStat(getClassPower(player)) + "</green>").decoration(TextDecoration.ITALIC, false));
        }

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createCurrentClassIcon(Player player) {
        PlayerClass playerClass = getPlayerClass(player);
        ItemStack item = new ItemStack(playerClass.isPlayable() ? Material.NETHER_STAR : Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ServerCorePlugin.getMiniMessage().deserialize("<yellow><bold>当前职业</bold></yellow>").decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        if (!playerClass.isPlayable()) {
            lore.add(ServerCorePlugin.getMiniMessage().deserialize("<gray>尚未选择职业。</gray>").decoration(TextDecoration.ITALIC, false));
        } else {
            AttributeManager.CoreAttribute main = getMainAttribute(player);
            AttributeManager.CoreAttribute sub = getSubAttribute(player);
            lore.add(ServerCorePlugin.getMiniMessage().deserialize("<gray>流派: <gold>" + playerClass.displayName() + "</gold></gray>").decoration(TextDecoration.ITALIC, false));
            lore.add(ServerCorePlugin.getMiniMessage().deserialize("<gray>Main: <yellow>" + (main == null ? "-" : main.displayLabel()) + "</yellow></gray>").decoration(TextDecoration.ITALIC, false));
            lore.add(ServerCorePlugin.getMiniMessage().deserialize("<gray>Sub: <aqua>" + (sub == null ? "-" : sub.displayLabel()) + "</aqua></gray>").decoration(TextDecoration.ITALIC, false));
            lore.add(ServerCorePlugin.getMiniMessage().deserialize("<gray>Power: <white>" + formatStat(getClassPower(player)) + "</white></gray>").decoration(TextDecoration.ITALIC, false));
            lore.add(ServerCorePlugin.getMiniMessage().deserialize("<gray>Atk 加成: <red>+" + formatStat(getClassAttackBonus(player)) + "</red></gray>").decoration(TextDecoration.ITALIC, false));
        }

        long remaining = getRemainingCooldownMillis(player);
        if (remaining > 0L) {
            lore.add(Component.empty());
            lore.add(ServerCorePlugin.getMiniMessage().deserialize("<red>切换冷却: " + formatSeconds(remaining) + " 秒</red>").decoration(TextDecoration.ITALIC, false));
        }

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createChoiceIcon(PlayerClass targetClass, AttributeManager.CoreAttribute main, AttributeManager.CoreAttribute sub) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ServerCorePlugin.getMiniMessage().deserialize("<green><bold>" + main.displayLabel() + " 主修</bold></green>").decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(ServerCorePlugin.getMiniMessage().deserialize("<gray>职业: <gold>" + targetClass.displayName() + "</gold></gray>").decoration(TextDecoration.ITALIC, false));
        lore.add(ServerCorePlugin.getMiniMessage().deserialize("<gray>Main: <yellow>" + main.displayLabel() + "</yellow></gray>").decoration(TextDecoration.ITALIC, false));
        lore.add(ServerCorePlugin.getMiniMessage().deserialize("<gray>Sub: <aqua>" + sub.displayLabel() + "</aqua></gray>").decoration(TextDecoration.ITALIC, false));
        lore.add(ServerCorePlugin.getMiniMessage().deserialize("<gray>Power = " + main.shortName() + " + 0.5 * " + sub.shortName() + "</gray>").decoration(TextDecoration.ITALIC, false));
        lore.add(ServerCorePlugin.getMiniMessage().deserialize("<gray>Atk = " + main.shortName() + " * 1.05 + " + sub.shortName() + " * 0.75</gray>").decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createBackIcon() {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ServerCorePlugin.getMiniMessage().deserialize("<gray>返回职业大厅</gray>").decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createGlass(Material material) {
        ItemStack glass = new ItemStack(material);
        ItemMeta meta = glass.getItemMeta();
        meta.displayName(Component.empty());
        glass.setItemMeta(meta);
        return glass;
    }

    private String formatSeconds(long millis) {
        return String.valueOf((long) Math.ceil(millis / 1000.0));
    }

    private String formatStat(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return String.valueOf((int) Math.rint(value));
        }
        return String.format(Locale.US, "%.1f", value);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record AttributePair(AttributeManager.CoreAttribute main, AttributeManager.CoreAttribute sub) {
    }
}
