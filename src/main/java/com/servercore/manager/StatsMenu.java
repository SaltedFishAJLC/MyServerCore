package com.servercore.manager;

import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import com.github.stefvanschie.inventoryframework.pane.Pane.Priority;
import com.servercore.ServerCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

public class StatsMenu {

    private final Player player;
    private final ChestGui gui;

    public StatsMenu(Player player) {
        this.player = player;
        // IF 0.12.0 默认支持字符串作为标题，部分高级版本支持 Component
        this.gui = new ChestGui(6, "⚔ 个人战斗面板 ⚔");
        gui.setOnGlobalClick(event -> event.setCancelled(true)); // 禁止拿走物品

        setupPanes();
    }

    private void setupPanes() {
        // 1. 背景玻璃板 (优先级设为最低，防止覆盖上层图标)
        OutlinePane background = new OutlinePane(0, 0, 9, 6, Priority.LOWEST);
        ItemStack glass = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta glassMeta = glass.getItemMeta();
        glassMeta.displayName(Component.empty());
        glass.setItemMeta(glassMeta);
        background.addItem(new GuiItem(glass));
        background.setRepeat(true);
        gui.addPane(background);

        // 2. 核心数据区 (优先级设为最高，确保展示在玻璃板上方)
        StaticPane statsPane = new StaticPane(0, 0, 9, 6, Priority.HIGHEST);

        AttributeManager attributeManager = AttributeManager.getInstance();
        if (attributeManager != null) {
            attributeManager.refreshPlayer(player);
        }
        AttributeManager.AttributeSnapshot attributeSnapshot = attributeManager == null
                ? new AttributeManager.AttributeSnapshot(0, 0, 0, 0, 0)
                : attributeManager.getSnapshot(player);

        // 抓取当前实时属性 (通过极速缓存)
        CombatStats stats = CombatStats.getFullStats(player);

        // -- 五维属性总量 (Top Row) --
        statsPane.addItem(new GuiItem(getAttributeIcon(AttributeManager.CoreAttribute.TOUGHNESS, attributeSnapshot)), 3,
                2);
        statsPane.addItem(new GuiItem(getAttributeIcon(AttributeManager.CoreAttribute.AGILITY, attributeSnapshot)), 5,
                2);
        statsPane.addItem(new GuiItem(getAttributeIcon(AttributeManager.CoreAttribute.INTELLIGENCE, attributeSnapshot)),
                7, 2);
        statsPane.addItem(new GuiItem(getAttributeIcon(AttributeManager.CoreAttribute.WILLPOWER, attributeSnapshot)), 4,
                3);
        statsPane.addItem(new GuiItem(getAttributeIcon(AttributeManager.CoreAttribute.LUCK, attributeSnapshot)), 6, 3);

        // -- 玩家头颅 (Slot 11 / X:1 Y:1) --
        statsPane.addItem(new GuiItem(getPlayerHead(stats)), 1, 1);

        // -- 近战属性 (Slot 13 / X:1 Y:2) --
        statsPane.addItem(new GuiItem(getMeleeIcon(stats)), 1, 2);

        // -- 远程属性 (Slot 15 / X:1 Y:3) --
        statsPane.addItem(new GuiItem(getRangedIcon(stats)), 1, 3);

        // -- 生存属性 (Slot 29 / X:0 Y:1) --
        statsPane.addItem(new GuiItem(getSurvivalIcon()), 0, 1);

        // -- 理论战力 (Slot 22 / X:2 Y:1) --
        statsPane.addItem(new GuiItem(getPowerIcon()), 2, 1);

        // -- 魔法属性 (Slot 31 / X:1 Y:4) --
        statsPane.addItem(new GuiItem(getMagicIcon()), 1, 4);

        gui.addPane(statsPane);
    }

    public void open() {
        gui.show(player);
    }

    // ========== 图标生成逻辑 ==========

    private ItemStack getAttributeIcon(AttributeManager.CoreAttribute attribute,
            AttributeManager.AttributeSnapshot snapshot) {
        Material material = switch (attribute) {
            case TOUGHNESS -> Material.RED_STAINED_GLASS_PANE;
            case AGILITY -> Material.LIME_STAINED_GLASS_PANE;
            case INTELLIGENCE -> Material.LIGHT_BLUE_STAINED_GLASS_PANE;
            case WILLPOWER -> Material.YELLOW_STAINED_GLASS_PANE;
            case LUCK -> Material.PURPLE_STAINED_GLASS_PANE;
        };

        String color = switch (attribute) {
            case TOUGHNESS -> "red";
            case AGILITY -> "green";
            case INTELLIGENCE -> "aqua";
            case WILLPOWER -> "yellow";
            case LUCK -> "light_purple";
        };

        int value = snapshot.value(attribute);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ServerCorePlugin.getMiniMessage()
                .deserialize(
                        "<" + color + "><bold>" + attribute.displayLabel() + ": " + value + "</bold></" + color + ">")
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        switch (attribute) {
            case TOUGHNESS -> lore.add(ServerCorePlugin.getMiniMessage()
                    .deserialize("<gray>最大生命值: <red>+" + formatStat(value * 2.0) + "</red></gray>")
                    .decoration(TextDecoration.ITALIC, false));
            case AGILITY -> lore.add(ServerCorePlugin.getMiniMessage()
                    .deserialize("<gray>护甲值: <green>+" + formatStat(value * 1.5) + "</green></gray>")
                    .decoration(TextDecoration.ITALIC, false));
            case INTELLIGENCE -> {
                lore.add(ServerCorePlugin.getMiniMessage()
                        .deserialize("<gray>魔法减伤: <aqua>+" + String.format(java.util.Locale.US, "%.1f", value * 0.2)
                                + "%</aqua></gray>")
                        .decoration(TextDecoration.ITALIC, false));
                lore.add(ServerCorePlugin.getMiniMessage()
                        .deserialize("<gray>最大法力值: <aqua>+" + formatStat(value * 2.5) + "</aqua></gray>")
                        .decoration(TextDecoration.ITALIC, false));
            }
            case WILLPOWER -> lore.add(ServerCorePlugin.getMiniMessage()
                    .deserialize("<gray>面板生命恢复: <yellow>+" + formatStat(value * 0.1) + " HP/s</yellow></gray>")
                    .decoration(TextDecoration.ITALIC, false));
            case LUCK -> {
                lore.add(ServerCorePlugin.getMiniMessage()
                        .deserialize("<gray>闪避几率: <light_purple>+"
                                + String.format(java.util.Locale.US, "%.2f", value * 0.15) + "%</light_purple></gray>")
                        .decoration(TextDecoration.ITALIC, false));
                lore.add(ServerCorePlugin.getMiniMessage()
                        .deserialize("<gray>暴击几率: <light_purple>+"
                                + String.format(java.util.Locale.US, "%.2f", value * 0.15) + "%</light_purple></gray>")
                        .decoration(TextDecoration.ITALIC, false));
            }
        }

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack getPlayerHead(CombatStats stats) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(player);
        meta.displayName(ServerCorePlugin.getMiniMessage()
                .deserialize("<gold><bold>" + player.getName() + " 的综合战力</bold></gold>")
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(ServerCorePlugin.getMiniMessage()
                .deserialize("<gray>当前综合增伤乘区: <white><b>" + (stats.baseMultiplier() * 100) + "%</b></white></gray>")
                .decoration(TextDecoration.ITALIC, false));

        lore.add(ServerCorePlugin.getMiniMessage()
                .deserialize("<gray>最大法力池: <aqua>" + formatStat(getEffectiveMaxMana()) + "</aqua></gray>")
                .decoration(TextDecoration.ITALIC, false));

        ClassManager classManager = ClassManager.getInstance();
        if (classManager != null && classManager.getPlayerClass(player).isPlayable()) {
            lore.add(ServerCorePlugin.getMiniMessage()
                    .deserialize("<gray>当前职业: <gold>" + classManager.getPlayerClass(player).displayName()
                            + "</gold> <dark_gray>(Power " + formatStat(classManager.getClassPower(player))
                            + ")</dark_gray></gray>")
                    .decoration(TextDecoration.ITALIC, false));
        }

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack getMeleeIcon(CombatStats stats) {
        ItemStack item = new ItemStack(Material.IRON_SWORD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ServerCorePlugin.getMiniMessage().deserialize("<red><bold>⚔ 近战流派面板</bold></red>")
                .decoration(TextDecoration.ITALIC, false));
        ClassManager classManager = ClassManager.getInstance();
        double lifesteal = classManager == null
                ? stats.lifesteal()
                : (stats.lifesteal() + classManager.getBaseLifesteal(player)) * classManager.getLifestealMultiplier(player);

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(ServerCorePlugin.getMiniMessage()
                .deserialize("<gray>基础面板伤害: <white>" + stats.baseDamage() + "</white></gray>")
                .decoration(TextDecoration.ITALIC, false));
        lore.add(ServerCorePlugin.getMiniMessage()
                .deserialize("<gray>暴击几率: <yellow>" + (stats.critChance() * 100) + "%</yellow></gray>")
                .decoration(TextDecoration.ITALIC, false));
        lore.add(ServerCorePlugin.getMiniMessage()
                .deserialize("<gray>暴击倍率: <yellow>" + (stats.critDamage() * 100) + "%</yellow></gray>")
                .decoration(TextDecoration.ITALIC, false));
        lore.add(ServerCorePlugin.getMiniMessage()
                .deserialize("<gray>残暴等级: <dark_red>" + stats.brutality() + "</dark_red></gray>")
                .decoration(TextDecoration.ITALIC, false));
        lore.add(ServerCorePlugin.getMiniMessage()
                .deserialize("<gray>吸血: <dark_red>" + String.format(java.util.Locale.US, "%.1f", lifesteal * 100.0) + "%</dark_red></gray>")
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(ServerCorePlugin.getMiniMessage().deserialize("<dark_gray><i>满蓄力平A必定打出残暴追击！</i></dark_gray>")
                .decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack getRangedIcon(CombatStats stats) {
        ItemStack item = new ItemStack(Material.BOW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ServerCorePlugin.getMiniMessage().deserialize("<green><bold>🏹 远程流派面板</bold></green>")
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(ServerCorePlugin.getMiniMessage()
                .deserialize("<gray>破甲强度: <green>" + stats.armorPen() + "</green></gray>")
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(ServerCorePlugin.getMiniMessage().deserialize("<dark_gray><i>射击高护甲生物时，将造成巨额贯穿伤害！</i></dark_gray>")
                .decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack getMagicIcon() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(
                ServerCorePlugin.getMiniMessage().deserialize("<light_purple><bold>✨ 魔法流派面板</bold></light_purple>")
                        .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());

        double maxMana = getEffectiveMaxMana();
        double magicPower = Math.max(0.0, (maxMana - 100.0) / 400.0);
        ClassManager classManager = ClassManager.getInstance();
        double classMagicMultiplier = classManager == null ? 0.0 : classManager.getMagicMultiplierBonus(player);

        lore.add(ServerCorePlugin.getMiniMessage()
                .deserialize("<gray>最大法力值: <aqua>" + String.format("%.0f", maxMana) + "</aqua></gray>")
                .decoration(TextDecoration.ITALIC, false));
        lore.add(
                ServerCorePlugin
                        .getMiniMessage().deserialize("<gray>法强转换收益: <light_purple>+"
                                + String.format("%.2f", magicPower) + " 倍</light_purple></gray>")
                        .decoration(TextDecoration.ITALIC, false));
        if (classMagicMultiplier > 0.0) {
            lore.add(ServerCorePlugin
                    .getMiniMessage().deserialize("<gray>职业法术乘区: <light_purple>+"
                            + String.format("%.2f", classMagicMultiplier) + " 倍</light_purple></gray>")
                    .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(ServerCorePlugin.getMiniMessage().deserialize("<dark_gray><i>魔法无视暴击，但巨额法强将撕裂一切！</i></dark_gray>")
                .decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack getSurvivalIcon() {
        ItemStack item = new ItemStack(Material.SHIELD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ServerCorePlugin.getMiniMessage().deserialize("<green><bold>⛨ 生存面板</bold></green>")
                .decoration(TextDecoration.ITALIC, false));

        AttributeInstance maxHealthAttribute = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double maxHealth = maxHealthAttribute == null ? 20.0 : maxHealthAttribute.getValue();
        double health = player.getHealth();
        PowerLevelManager powerLevelManager = PowerLevelManager.getInstance();
        double armor = powerLevelManager == null ? 0.0 : powerLevelManager.calculateArmorValue(player);
        double effectiveArmor = powerLevelManager == null ? 0.0 : powerLevelManager.calculateEffectiveArmorValue(player);
        double reduction = powerLevelManager == null ? 0.0 : powerLevelManager.calculateDamageReduction(player);
        double ehp = maxHealth / Math.max(0.01, 1.0 - reduction);

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(ServerCorePlugin.getMiniMessage()
                .deserialize("<gray>当前血量: <red>" + formatStat(health) + "/" + formatStat(maxHealth) + "</red></gray>")
                .decoration(TextDecoration.ITALIC, false));
        lore.add(ServerCorePlugin.getMiniMessage()
                .deserialize("<gray>护甲值: <green>" + formatStat(armor) + "</green></gray>")
                .decoration(TextDecoration.ITALIC, false));
        if (Math.abs(effectiveArmor - armor) > 0.01) {
            lore.add(ServerCorePlugin.getMiniMessage()
                    .deserialize("<gray>实战护甲: <green>" + formatStat(effectiveArmor) + "</green></gray>")
                    .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(ServerCorePlugin.getMiniMessage()
                .deserialize("<gray>减伤率: <green>" + String.format("%.1f", reduction * 100.0) + "%</green></gray>")
                .decoration(TextDecoration.ITALIC, false));
        lore.add(ServerCorePlugin.getMiniMessage()
                .deserialize("<gray>有效生命 EHP: <aqua>" + formatStat(ehp) + "</aqua></gray>")
                .decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack getPowerIcon() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(ServerCorePlugin.getMiniMessage().deserialize("<gold><bold>✦ 理论战力</bold></gold>")
                .decoration(TextDecoration.ITALIC, false));

        PowerLevelManager powerLevelManager = PowerLevelManager.getInstance();
        PlayerStatCache cache = PlayerStatCache.getInstance();
        double spawnPower = powerLevelManager == null ? 1.0 : powerLevelManager.getSpawnPower(player);
        double targetPower = powerLevelManager == null ? spawnPower : powerLevelManager.calculateTargetPower(player);
        double cachedTarget = cache == null ? targetPower : cache.getTargetPower(player);
        PowerLevelManager.PowerBreakdown breakdown = powerLevelManager == null ? null
                : powerLevelManager.calculatePowerBreakdown(player);

        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        lore.add(ServerCorePlugin.getMiniMessage()
                .deserialize("<gray>理论 TargetPower: <gold>" + String.format("%.1f", targetPower) + "</gold></gray>")
                .decoration(TextDecoration.ITALIC, false));
        lore.add(ServerCorePlugin.getMiniMessage()
                .deserialize("<gray>缓存目标战力: <yellow>" + String.format("%.1f", cachedTarget) + "</yellow></gray>")
                .decoration(TextDecoration.ITALIC, false));
        lore.add(ServerCorePlugin.getMiniMessage()
                .deserialize("<gray>自然 SpawnPower: <white>" + String.format("%.1f", spawnPower) + "</white></gray>")
                .decoration(TextDecoration.ITALIC, false));
        if (breakdown != null) {
            lore.add(Component.empty());
            lore.add(ServerCorePlugin.getMiniMessage()
                    .deserialize(
                            "<gray>近战 DPS: <red>" + String.format("%.1f", breakdown.meleeDps()) + "</red></gray>")
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(ServerCorePlugin
                    .getMiniMessage().deserialize("<gray>远程 DPS: <green>"
                            + String.format("%.1f", breakdown.rangedDps()) + "</green></gray>")
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(ServerCorePlugin
                    .getMiniMessage().deserialize("<gray>法术 DPS: <light_purple>"
                            + String.format("%.1f", breakdown.magicDps()) + "</light_purple></gray>")
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(ServerCorePlugin
                    .getMiniMessage().deserialize("<gray>输出评分: <gold>"
                            + String.format("%.1f", breakdown.offenseScore()) + "</gold></gray>")
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(ServerCorePlugin
                    .getMiniMessage().deserialize("<gray>有效生命 EHP: <aqua>"
                            + String.format("%.1f", breakdown.effectiveHealth()) + "</aqua></gray>")
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(ServerCorePlugin
                    .getMiniMessage().deserialize("<gray>续航评分: <green>+"
                            + String.format("%.0f", breakdown.sustainFactor() * 100.0) + "%</green></gray>")
                    .decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.add(ServerCorePlugin.getMiniMessage().deserialize("<dark_gray><i>普通面板显示即时理论战力；自然刷怪读取滑动采样。</i></dark_gray>")
                .decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private String formatStat(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return String.valueOf((int) Math.rint(value));
        }
        return String.format("%.1f", value);
    }

    private double getEffectiveMaxMana() {
        AttributeManager attributeManager = AttributeManager.getInstance();
        return attributeManager == null ? 100.0 : attributeManager.getEffectiveMaxMana(player);
    }
}
