package com.servercore.manager;

import com.github.stefvanschie.inventoryframework.gui.GuiItem;
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui;
import com.github.stefvanschie.inventoryframework.pane.OutlinePane;
import com.github.stefvanschie.inventoryframework.pane.Pane.Priority;
import com.github.stefvanschie.inventoryframework.pane.StaticPane;
import com.servercore.ServerCorePlugin;
import dev.aurelium.auraskills.api.skill.Skills;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class NonCombatStatsMenu {

    private final Player player;
    private final ChestGui gui;

    public NonCombatStatsMenu(Player player) {
        this.player = player;
        this.gui = new ChestGui(4, "生活技能属性");
        gui.setOnGlobalClick(event -> event.setCancelled(true));
        setupPanes();
    }

    public void open() {
        gui.show(player);
    }

    private void setupPanes() {
        OutlinePane background = new OutlinePane(0, 0, 9, 4, Priority.LOWEST);
        background.addItem(new GuiItem(createNamedItem(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of())));
        background.setRepeat(true);
        gui.addPane(background);

        StaticPane pane = new StaticPane(0, 0, 9, 4, Priority.HIGHEST);
        ToolSnapshot tool = readToolSnapshot();

        pane.addItem(new GuiItem(createGlobalIcon()), 1, 1);
        pane.addItem(new GuiItem(createCollectionIcon(Material.OAK_LOG, "伐木", "green", tool.foragingFortune(), tool.foragingSweep(), true, tool.bounty(), -1.0)), 3, 1);
        pane.addItem(new GuiItem(createCollectionIcon(Material.WHEAT, "种植", "yellow", tool.farmingFortune(), 0.0, false, -1.0, tool.overbloom())), 4, 1);
        pane.addItem(new GuiItem(createCollectionIcon(Material.SUSPICIOUS_SAND, "采掘", "gray", tool.excavationFortune(), 0.0, false, -1.0, -1.0)), 5, 1);
        pane.addItem(new GuiItem(createMiningIcon(tool)), 6, 1);
        pane.addItem(new GuiItem(createFishingIcon(tool)), 7, 1);
        pane.addItem(new GuiItem(createToolIcon(tool)), 4, 2);

        gui.addPane(pane);
    }

    private ItemStack createGlobalIcon() {
        AttributeManager attributeManager = AttributeManager.getInstance();
        AttributeManager.AttributeSnapshot snapshot = attributeManager == null
                ? new AttributeManager.AttributeSnapshot(0, 0, 0, 0, 0)
                : attributeManager.getSnapshot(player);
        GlobalStatManager globalStatManager = GlobalStatManager.getInstance();
        double globalFortune = globalStatManager == null ? snapshot.luck() * 0.6 : globalStatManager.getGlobalFortune(player);
        double magicFind = globalStatManager == null ? snapshot.luck() * 0.5 : globalStatManager.getMagicFind(player);
        int hasteLevel = Math.max(0, snapshot.agility() / 125);

        List<Component> lore = new ArrayList<>();
        lore.add(line("<gray>幸运: <light_purple>" + snapshot.luck() + "</light_purple></gray>"));
        lore.add(line("<gray>全局时运: <gold>" + number(globalFortune) + "</gold></gray>"));
        lore.add(line("<gray>战斗寻宝: <aqua>" + number(magicFind) + "</aqua></gray>"));
        lore.add(line("<gray>敏捷: <green>" + snapshot.agility() + "</green></gray>"));
        lore.add(line("<gray>急迫等级: <green>" + hasteLevel + "</green></gray>"));
        return createNamedItem(Material.NETHER_STAR, mm("<gold><bold>全局生活属性</bold></gold>"), lore);
    }

    private ItemStack createCollectionIcon(Material material, String name, String color, double equipmentFortune, double sweep, boolean supportsSweep, double bounty, double overbloom) {
        double totalFortune = equipmentFortune + globalFortune();
        List<Component> lore = new ArrayList<>();
        lore.add(line("<gray>装备时运: <white>" + number(equipmentFortune) + "</white></gray>"));
        lore.add(line("<gray>全局时运: <gold>" + number(globalFortune()) + "</gold></gray>"));
        lore.add(line("<gray>最终时运: <" + color + ">" + number(totalFortune) + "</" + color + "></gray>"));
        if (supportsSweep) {
            lore.add(line("<gray>伐木扩散: <" + color + ">" + number(sweep) + "</" + color + "></gray>"));
        }
        if (bounty >= 0.0) {
            lore.add(line("<gray>Bounty 赏金: <green>" + number(Math.max(0.0, bounty)) + "%</green></gray>"));
        }
        if (overbloom >= 0.0) {
            lore.add(line("<gray>Overbloom 溢绽: <green>" + number(Math.max(0.0, overbloom)) + "%</green></gray>"));
        }
        lore.add(Component.empty());
        lore.add(line("<dark_gray>产出: " + fortunePreview(totalFortune) + "</dark_gray>"));
        return createNamedItem(material, mm("<" + color + "><bold>" + name + "</bold></" + color + ">"), lore);
    }

    private ItemStack createMiningIcon(ToolSnapshot tool) {
        double totalFortune = tool.miningFortune() + globalFortune();
        List<Component> lore = new ArrayList<>();
        lore.add(line("<gray>装备挖矿时运: <white>" + number(tool.miningFortune()) + "</white></gray>"));
        lore.add(line("<gray>全局时运: <gold>" + number(globalFortune()) + "</gold></gray>"));
        lore.add(line("<gray>最终挖矿时运: <aqua>" + number(totalFortune) + "</aqua></gray>"));
        lore.add(line("<gray>矿物扩散: <aqua>" + number(tool.miningSpread()) + "</aqua></gray>"));
        lore.add(line("<gray>挖掘速度: <white>" + number(tool.miningSpeed()) + "</white> <dark_gray>(+" + number(tool.miningSpeed()) + "%)</dark_gray></gray>"));
        lore.add(line("<gray>破坏力: <red>" + number(tool.breakingPower()) + "</red></gray>"));
        lore.add(line("<gray>晶石纯度: <light_purple>" + number(tool.purity()) + "</light_purple></gray>"));
        lore.add(Component.empty());
        lore.add(line("<dark_gray>时运: " + fortunePreview(totalFortune) + "</dark_gray>"));
        lore.add(line("<dark_gray>纯度: " + fortunePreview(tool.purity()) + "</dark_gray>"));
        return createNamedItem(Material.DIAMOND_PICKAXE, mm("<aqua><bold>挖矿</bold></aqua>"), lore);
    }

    private ItemStack createFishingIcon(ToolSnapshot tool) {
        AuraSkillsBridge bridge = AuraSkillsBridge.getInstance();
        int level = bridge == null ? 0 : bridge.getSkillLevel(player, Skills.FISHING);
        double seaCreatureChance = Math.min(95.0, 5.0 + level * 0.2 + tool.seaCreatureChance());
        double treasureChance = Math.min(95.0, 3.5 + level * 0.15 + tool.treasureChance());
        double levelFishingSpeed = FishingManager.getLevelFishingSpeed(level);
        double effectiveFishingSpeed = FishingManager.getEffectiveFishingSpeed(level, tool.fishingSpeed());
        FishingManager.FishingWaitWindow waitWindow = FishingManager.calculateWaitWindow(effectiveFishingSpeed);

        List<Component> lore = new ArrayList<>();
        lore.add(line("<gray>钓鱼等级: <aqua>" + level + "</aqua></gray>"));
        lore.add(line("<gray>钓鱼速度: <blue>" + number(tool.fishingSpeed()) + "</blue> <dark_gray>+ 等级 " + number(levelFishingSpeed) + "</dark_gray></gray>"));
        lore.add(line("<gray>海怪概率: <aqua>" + number(seaCreatureChance) + "%</aqua></gray>"));
        lore.add(line("<gray>宝藏概率: <gold>" + number(treasureChance) + "%</gold></gray>"));
        lore.add(line("<gray>咬钩窗口: <white>" + waitWindow.minTicks() + "-" + waitWindow.maxTicks() + " ticks</white></gray>"));
        return createNamedItem(Material.FISHING_ROD, mm("<blue><bold>钓鱼</bold></blue>"), lore);
    }

    private ItemStack createToolIcon(ToolSnapshot tool) {
        List<Component> lore = new ArrayList<>();
        lore.add(line("<gray>手持工具: <white>" + safeMiniMessageText(tool.displayName()) + "</white></gray>"));
        lore.add(Component.empty());
        lore.add(line("<gray>通用时运: <gold>" + number(tool.toolFortune()) + "</gold></gray>"));
        lore.add(line("<gray>采集时运: <gold>" + number(tool.collectionFortune()) + "</gold></gray>"));
        lore.add(line("<gray>Bounty 赏金: <green>+" + number(tool.bounty()) + "%</green></gray>"));
        lore.add(line("<gray>Overbloom 溢绽: <green>+" + number(tool.overbloom()) + "%</green></gray>"));
        lore.add(line("<gray>挖矿时运: <aqua>" + number(tool.rawMiningFortune()) + "</aqua></gray>"));
        lore.add(line("<gray>通用连锁: <green>" + number(tool.toolSweep()) + "</green></gray>"));
        lore.add(line("<gray>采集连锁: <green>" + number(tool.collectionSweep()) + "</green></gray>"));
        lore.add(line("<gray>挖矿扩散: <aqua>" + number(tool.miningSpread()) + "</aqua></gray>"));
        lore.add(line("<gray>挖掘速度: <white>" + number(tool.miningSpeed()) + "</white> <dark_gray>(+" + number(tool.miningSpeed()) + "%)</dark_gray></gray>"));
        lore.add(line("<gray>破坏力: <red>" + number(tool.breakingPower()) + "</red></gray>"));
        lore.add(line("<gray>纯度: <light_purple>" + number(tool.purity()) + "</light_purple></gray>"));
        lore.add(line("<gray>钓鱼速度: <blue>" + number(tool.fishingSpeed()) + "</blue></gray>"));
        lore.add(line("<gray>海怪概率: <aqua>+" + number(tool.seaCreatureChance()) + "%</aqua></gray>"));
        lore.add(line("<gray>宝藏概率: <gold>+" + number(tool.treasureChance()) + "%</gold></gray>"));
        return createNamedItem(tool.material(), mm("<white><bold>当前工具</bold></white>"), lore);
    }

    private ToolSnapshot readToolSnapshot() {
        ItemStack item = player.getInventory().getItemInMainHand();
        PDCManager pdc = PDCManager.getInstance();
        if (item == null || item.getType().isAir() || pdc == null) {
            return ToolSnapshot.empty();
        }

        ItemMeta meta = item.getItemMeta();
        String displayName = item.getType().name().toLowerCase(Locale.ROOT);
        if (meta != null && meta.hasDisplayName()) {
            Component componentName = meta.displayName();
            displayName = componentName == null
                    ? stripLegacyCodes(meta.getDisplayName())
                    : PlainTextComponentSerializer.plainText().serialize(componentName);
        }
        double toolFortune = stat(item, pdc.KEY_TOOL_FORTUNE);
        double collectionFortune = stat(item, pdc.KEY_COLLECTION_FORTUNE);
        double foragingFortune = stat(item, pdc.KEY_FORAGING_FORTUNE);
        double farmingFortune = stat(item, pdc.KEY_FARMING_FORTUNE);
        double excavationFortune = stat(item, pdc.KEY_EXCAVATION_FORTUNE);
        double miningFortune = stat(item, pdc.KEY_MINING_FORTUNE);
        double toolSweep = stat(item, pdc.KEY_TOOL_SWEEP);
        double collectionSweep = stat(item, pdc.KEY_COLLECTION_SWEEP);
        double foragingSweep = stat(item, pdc.KEY_FORAGING_SWEEP);
        double farmingSweep = stat(item, pdc.KEY_FARMING_SWEEP);
        double excavationSweep = stat(item, pdc.KEY_EXCAVATION_SWEEP);
        double toolSpread = stat(item, pdc.KEY_TOOL_SPREAD);
        double miningSpread = stat(item, pdc.KEY_MINING_SPREAD);
        double miningSpeed = stat(item, pdc.KEY_TOOL_MINING_SPEED);
        double breakingPower = Math.max(stat(item, pdc.KEY_BREAKING_POWER), vanillaBreakingPower(item));
        double purity = stat(item, pdc.KEY_PURITY) + stat(item, pdc.KEY_MINING_PURITY);
        double fishingSpeed = stat(item, pdc.KEY_FISHING_SPEED);
        double seaCreatureChance = stat(item, pdc.KEY_SEA_CREATURE_CHANCE);
        double treasureChance = stat(item, pdc.KEY_TREASURE_CHANCE);
        double bounty = stat(item, pdc.KEY_BOUNTY);
        double overbloom = stat(item, pdc.KEY_OVERBLOOM);

        return new ToolSnapshot(
                item.getType(),
                displayName,
                toolFortune,
                collectionFortune,
                foragingFortune,
                farmingFortune,
                excavationFortune,
                miningFortune,
                toolSweep,
                collectionSweep,
                foragingSweep,
                farmingSweep,
                excavationSweep,
                toolSpread,
                miningSpread,
                miningSpeed,
                breakingPower,
                purity,
                fishingSpeed,
                seaCreatureChance,
                treasureChance,
                bounty,
                overbloom
        );
    }

    private double stat(ItemStack item, org.bukkit.NamespacedKey key) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return 0.0;
        }
        return item.getItemMeta().getPersistentDataContainer().getOrDefault(key, PersistentDataType.DOUBLE, 0.0);
    }

    private double globalFortune() {
        GlobalStatManager manager = GlobalStatManager.getInstance();
        return manager == null ? 0.0 : manager.getGlobalFortune(player);
    }

    private String fortunePreview(double value) {
        double safe = Math.max(0.0, value);
        int guaranteed = 1 + (int) Math.floor(safe / 100.0);
        double chance = safe % 100.0;
        return guaranteed + "份保底 + " + number(chance) + "% 额外1份";
    }

    private int vanillaBreakingPower(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return 0;
        }
        String type = item.getType().name();
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

    private ItemStack createNamedItem(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }

    private Component mm(String text) {
        return ServerCorePlugin.getMiniMessage().deserialize(text).decoration(TextDecoration.ITALIC, false);
    }

    private Component line(String text) {
        return mm(text);
    }

    private String safeMiniMessageText(String text) {
        return stripLegacyCodes(text)
                .replace("<", "‹")
                .replace(">", "›");
    }

    private String stripLegacyCodes(String text) {
        return text == null ? "" : text.replaceAll("(?i)§[0-9A-FK-ORX]", "");
    }

    private String number(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return String.valueOf((int) Math.rint(value));
        }
        return String.format(Locale.US, "%.1f", value);
    }

    private record ToolSnapshot(
            Material material,
            String displayName,
            double toolFortune,
            double collectionFortune,
            double rawForagingFortune,
            double rawFarmingFortune,
            double rawExcavationFortune,
            double rawMiningFortune,
            double toolSweep,
            double collectionSweep,
            double rawForagingSweep,
            double rawFarmingSweep,
            double rawExcavationSweep,
            double toolSpread,
            double rawMiningSpread,
            double miningSpeed,
            double breakingPower,
            double purity,
            double fishingSpeed,
            double seaCreatureChance,
            double treasureChance,
            double bounty,
            double overbloom
    ) {
        static ToolSnapshot empty() {
            return new ToolSnapshot(Material.BARRIER, "empty hand", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        double foragingFortune() {
            return toolFortune + collectionFortune + rawForagingFortune;
        }

        double farmingFortune() {
            return toolFortune + collectionFortune + rawFarmingFortune;
        }

        double excavationFortune() {
            return toolFortune + collectionFortune + rawExcavationFortune;
        }

        double miningFortune() {
            return toolFortune + rawMiningFortune;
        }

        double foragingSweep() {
            return toolSweep + collectionSweep + rawForagingSweep;
        }

        double farmingSweep() {
            return 0.0;
        }

        double excavationSweep() {
            return 0.0;
        }

        double miningSpread() {
            return Math.max(toolSpread, rawMiningSpread);
        }
    }
}
