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
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AuraSkillsMenuHijacker implements Listener {

    private static final int SOURCE_ITEMS_PER_PAGE = 28;

    private final ServerCorePlugin plugin;
    private final AuraSkillsBridge bridge;

    public AuraSkillsMenuHijacker(ServerCorePlugin plugin, AuraSkillsBridge bridge) {
        this.plugin = plugin;
        this.bridge = bridge;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String[] parts = event.getMessage().trim().split("\\s+");
        if (parts.length == 0 || parts[0].length() <= 1) {
            return;
        }

        String command = parts[0].substring(1).toLowerCase(Locale.ROOT);
        String firstArg = parts.length >= 2 ? parts[1].toLowerCase(Locale.ROOT) : "";
        if (!shouldHijack(command, firstArg)) {
            return;
        }

        event.setCancelled(true);
        openSkillsMenu(event.getPlayer());
    }

    public void openSkillsMenu(Player player) {
        List<SkillDisplay> skills = loadEnabledSkills();
        ChestGui gui = new ChestGui(5, "ServerCore 技能同化面板", plugin);
        gui.setOnGlobalClick(event -> event.setCancelled(true));
        addBackground(gui, 5);

        StaticPane pane = new StaticPane(0, 0, 9, 5, Priority.HIGHEST);
        pane.addItem(new GuiItem(createSummaryIcon(player, skills), plugin), 0, 0);
        addDimensionIcons(pane, player);

        int index = 0;
        for (int y = 1; y <= 3; y++) {
            for (int x = 1; x <= 7 && index < skills.size(); x++) {
                SkillDisplay skill = skills.get(index++);
                pane.addItem(new GuiItem(createSkillIcon(player, skill), event -> openSkillProgressMenu(player, skill), plugin), x, y);
            }
        }

        pane.addItem(new GuiItem(createNamedItem(Material.COMPARATOR,
                mm("<yellow><bold>同化规则</bold></yellow>"),
                List.of(
                        text("AuraSkills 负责等级、经验曲线与存档。"),
                        text("ServerCore 接管属性映射、战斗乘区和展示口径。"),
                        text("这里只显示 skills.yml 中启用的原有技能。")
                )), plugin), 8, 4);
        gui.addPane(pane);
        gui.show(player);
    }

    private void openSkillProgressMenu(Player player, SkillDisplay skill) {
        int currentLevel = bridge.getSkillLevel(player, skill.skill());
        int nextLevel = Math.min(skill.maxLevel(), currentLevel + 1);
        double progress = currentLevel >= skill.maxLevel() ? 1.0 : bridge.getSkillProgress(player, skill.skill());

        ChestGui gui = new ChestGui(6, skill.name() + " 等级进度", plugin);
        gui.setOnGlobalClick(event -> event.setCancelled(true));
        addBackground(gui, 6);

        StaticPane pane = new StaticPane(0, 0, 9, 6, Priority.HIGHEST);
        pane.addItem(new GuiItem(createBackIcon("返回技能总览"), event -> openSkillsMenu(player), plugin), 0, 5);
        pane.addItem(new GuiItem(createCloseIcon(), event -> player.closeInventory(), plugin), 8, 5);
        pane.addItem(new GuiItem(createSkillDetailIcon(player, skill, currentLevel, nextLevel, progress), plugin), 0, 0);
        pane.addItem(new GuiItem(createSourcesEntry(skill), event -> openSourcesMenu(player, skill, 0), plugin), 8, 0);

        addProgressBar(pane, skill, currentLevel, nextLevel, progress);
        addLevelTrack(pane, skill, currentLevel);

        gui.addPane(pane);
        gui.show(player);
    }

    private void openSourcesMenu(Player player, SkillDisplay skill, int page) {
        List<SourceEntry> sources = loadSources(skill);
        int totalPages = Math.max(1, (int) Math.ceil(sources.size() / (double) SOURCE_ITEMS_PER_PAGE));
        int safePage = Math.max(0, Math.min(page, totalPages - 1));

        ChestGui gui = new ChestGui(6, skill.name() + " 经验来源 " + (safePage + 1) + "/" + totalPages, plugin);
        gui.setOnGlobalClick(event -> event.setCancelled(true));
        addBackground(gui, 6);

        StaticPane pane = new StaticPane(0, 0, 9, 6, Priority.HIGHEST);
        pane.addItem(new GuiItem(createBackIcon("返回等级进度"), event -> openSkillProgressMenu(player, skill), plugin), 0, 5);
        pane.addItem(new GuiItem(createCloseIcon(), event -> player.closeInventory(), plugin), 8, 5);

        int start = safePage * SOURCE_ITEMS_PER_PAGE;
        int end = Math.min(start + SOURCE_ITEMS_PER_PAGE, sources.size());
        int index = start;
        for (int y = 1; y <= 4; y++) {
            for (int x = 1; x <= 7 && index < end; x++) {
                pane.addItem(new GuiItem(createSourceIcon(sources.get(index++)), plugin), x, y);
            }
        }

        if (safePage > 0) {
            pane.addItem(new GuiItem(createNamedItem(Material.ARROW, mm("<gold><bold>上一页</bold></gold>"),
                    List.of(text("查看前一批经验来源。"))), event -> openSourcesMenu(player, skill, safePage - 1), plugin), 3, 5);
        }
        if (safePage + 1 < totalPages) {
            pane.addItem(new GuiItem(createNamedItem(Material.ARROW, mm("<gold><bold>下一页</bold></gold>"),
                    List.of(text("查看后一批经验来源。"))), event -> openSourcesMenu(player, skill, safePage + 1), plugin), 5, 5);
        }

        if (sources.isEmpty()) {
            pane.addItem(new GuiItem(createNamedItem(Material.BARRIER, mm("<red><bold>没有配置经验来源</bold></red>"),
                    List.of(text("未找到 sources/" + skill.key() + ".yml。"))), plugin), 4, 2);
        }

        gui.addPane(pane);
        gui.show(player);
    }

    private boolean shouldHijack(String command, String firstArg) {
        return switch (command) {
            case "skills", "skill", "sk" -> true;
            case "auraskills", "aureliumskills" -> firstArg.isBlank()
                    || firstArg.equals("skills")
                    || firstArg.equals("skill")
                    || firstArg.equals("menu")
                    || firstArg.equals("gui");
            default -> false;
        };
    }

    private void addBackground(ChestGui gui, int rows) {
        OutlinePane background = new OutlinePane(0, 0, 9, rows, Priority.LOWEST);
        background.addItem(new GuiItem(createNamedItem(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of()), plugin));
        background.setRepeat(true);
        gui.addPane(background);
    }

    private void addDimensionIcons(StaticPane pane, Player player) {
        AttributeManager.AttributeSnapshot bonuses = bridge.getAttributeBonuses(player);
        pane.addItem(new GuiItem(createDimensionIcon(Material.RED_DYE, "坚韧", "red", bonuses.toughness(), "Aura Health"), plugin), 2, 0);
        pane.addItem(new GuiItem(createDimensionIcon(Material.LIME_DYE, "敏捷", "green", bonuses.agility(), "Aura Toughness"), plugin), 3, 0);
        pane.addItem(new GuiItem(createDimensionIcon(Material.LIGHT_BLUE_DYE, "智慧", "aqua", bonuses.intelligence(), "Aura Wisdom"), plugin), 4, 0);
        pane.addItem(new GuiItem(createDimensionIcon(Material.YELLOW_DYE, "意志", "yellow", bonuses.willpower(), "Aura Regeneration"), plugin), 5, 0);
        pane.addItem(new GuiItem(createDimensionIcon(Material.PURPLE_DYE, "幸运", "light_purple", bonuses.luck(), "Aura Luck"), plugin), 6, 0);
    }

    private void addProgressBar(StaticPane pane, SkillDisplay skill, int currentLevel, int nextLevel, double progress) {
        int filledSlots = (int) Math.floor(progress * 9.0);
        boolean maxed = currentLevel >= skill.maxLevel();
        for (int x = 0; x < 9; x++) {
            Material material;
            if (maxed || x < filledSlots) {
                material = Material.LIME_STAINED_GLASS_PANE;
            } else if (x == filledSlots) {
                material = Material.YELLOW_STAINED_GLASS_PANE;
            } else {
                material = Material.GRAY_STAINED_GLASS_PANE;
            }

            List<Component> lore = new ArrayList<>();
            lore.add(text("当前等级: " + currentLevel + "/" + skill.maxLevel()));
            if (maxed) {
                lore.add(text("已经达到最高等级。"));
            } else {
                lore.add(text("目标等级: " + nextLevel));
                lore.add(text("进度: " + String.format(Locale.US, "%.1f", progress * 100.0) + "%"));
            }
            pane.addItem(new GuiItem(createNamedItem(material,
                    mm("<" + skill.color() + "><bold>" + skill.name() + " 经验进度</bold></" + skill.color() + ">"),
                    lore), plugin), x, 1);
        }
    }

    private void addLevelTrack(StaticPane pane, SkillDisplay skill, int currentLevel) {
        int startLevel = Math.max(1, currentLevel - 4);
        if (currentLevel < 5) {
            startLevel = 1;
        }
        if (startLevel + 17 > skill.maxLevel()) {
            startLevel = Math.max(1, skill.maxLevel() - 17);
        }

        int level = startLevel;
        for (int y = 3; y <= 4; y++) {
            for (int x = 0; x < 9 && level <= skill.maxLevel(); x++) {
                pane.addItem(new GuiItem(createLevelIcon(skill, level, currentLevel), plugin), x, y);
                level++;
            }
        }
    }

    private ItemStack createSummaryIcon(Player player, List<SkillDisplay> skills) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(player);
        meta.displayName(mm("<gold><bold>" + player.getName() + "</bold></gold>").decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(text("Aura 总技能等级: " + bridge.getPowerLevel(player)));
        lore.add(text("当前显示技能数: " + skills.size()));
        lore.add(text("白名单来源: AuraSkills skills.yml"));
        meta.lore(clean(lore));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createSkillIcon(Player player, SkillDisplay skill) {
        int level = bridge.getSkillLevel(player, skill.skill());
        int required = bridge.getXpRequiredForNextLevel(player, skill.skill());
        double xp = bridge.getSkillXp(player, skill.skill());
        double progress = bridge.getSkillProgress(player, skill.skill()) * 100.0;

        List<Component> lore = new ArrayList<>();
        lore.add(text(skill.description()));
        lore.add(Component.empty());
        lore.add(text("等级: " + level + "/" + skill.maxLevel()));
        if (level >= skill.maxLevel()) {
            lore.add(text("经验进度: 已满级"));
        } else {
            lore.add(text("经验进度: " + formatNumber(xp) + "/" + required + " (" + String.format(Locale.US, "%.1f", progress) + "%)"));
        }

        double multiplier = bridge.getCombatSkillMultiplier(player, skill.skill().name());
        if (multiplier > 0.0) {
            lore.add(text("ServerCore 技能乘区: +" + String.format(Locale.US, "%.1f", multiplier * 100.0) + "%"));
        }

        lore.add(Component.empty());
        lore.add(text("点击查看等级奖励与经验来源。"));

        return createNamedItem(skill.material(),
                mm("<" + skill.color() + "><bold>" + skill.name() + " Lv." + level + "</bold></" + skill.color() + ">"),
                lore);
    }

    private ItemStack createSkillDetailIcon(Player player, SkillDisplay skill, int currentLevel, int nextLevel, double progress) {
        List<Component> lore = new ArrayList<>();
        lore.add(text(skill.description()));
        lore.add(Component.empty());
        lore.add(text("当前等级: " + currentLevel + "/" + skill.maxLevel()));
        if (currentLevel >= skill.maxLevel()) {
            lore.add(text("进度: 已满级"));
        } else {
            lore.add(text("下一级: Lv." + nextLevel));
            lore.add(text("进度: " + String.format(Locale.US, "%.1f", progress * 100.0) + "%"));
            lore.add(Component.empty());
            lore.add(text("提升到 Lv." + nextLevel + " 可获得:"));
            for (String reward : getRewardsForLevel(skill, nextLevel)) {
                lore.add(text("  " + reward));
            }
        }

        return createNamedItem(skill.material(),
                mm("<" + skill.color() + "><bold>" + skill.name() + " 等级进度</bold></" + skill.color() + ">"),
                lore);
    }

    private ItemStack createLevelIcon(SkillDisplay skill, int level, int currentLevel) {
        Material material = level <= currentLevel
                ? Material.LIME_STAINED_GLASS_PANE
                : level == currentLevel + 1 ? Material.YELLOW_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;

        String color = level <= currentLevel ? "green" : level == currentLevel + 1 ? "yellow" : "red";
        List<Component> lore = new ArrayList<>();
        lore.add(text("提升到 Lv." + level + " 可获得:"));
        List<String> rewards = getRewardsForLevel(skill, level);
        if (rewards.isEmpty()) {
            lore.add(text("  无新增奖励"));
        } else {
            for (String reward : rewards) {
                lore.add(text("  " + reward));
            }
        }
        lore.add(Component.empty());
        lore.add(text(level <= currentLevel ? "状态: 已解锁" : level == currentLevel + 1 ? "状态: 进行中" : "状态: 未解锁"));

        return createNamedItem(material, mm("<" + color + "><bold>Lv." + level + "</bold></" + color + ">"), lore);
    }

    private ItemStack createSourceIcon(SourceEntry source) {
        List<Component> lore = new ArrayList<>();
        lore.add(text("类型: " + source.type()));
        lore.add(text("经验: " + formatNumber(source.xp()) + source.unitSuffix()));
        if (!source.extra().isEmpty()) {
            lore.add(Component.empty());
            source.extra().forEach(line -> lore.add(text(line)));
        }
        return createNamedItem(source.material(), mm("<white><bold>" + source.name() + "</bold></white>"), lore);
    }

    private ItemStack createSourcesEntry(SkillDisplay skill) {
        return createNamedItem(Material.EXPERIENCE_BOTTLE,
                mm("<green><bold>经验来源</bold></green>"),
                List.of(
                        text("查看 " + skill.name() + " 的所有经验获取方式。"),
                        text("内容来自 sources/" + skill.key() + ".yml。")
                ));
    }

    private ItemStack createDimensionIcon(Material material, String name, String color, int value, String source) {
        return createNamedItem(material,
                mm("<" + color + "><bold>" + name + " +" + value + "</bold></" + color + ">"),
                List.of(
                        text("来源: " + source),
                        text("注入五维点数: +" + value)
                ));
    }

    private ItemStack createBackIcon(String label) {
        return createNamedItem(Material.ARROW, mm("<green><bold>" + label + "</bold></green>"), List.of(text("点击返回。")));
    }

    private ItemStack createCloseIcon() {
        return createNamedItem(Material.BARRIER, mm("<red><bold>关闭</bold></red>"), List.of(text("点击关闭面板。")));
    }

    private List<SkillDisplay> loadEnabledSkills() {
        ConfigBundle config = loadConfigBundle();
        ConfigurationSection skillsSection = config.skills().getConfigurationSection("skills");
        if (skillsSection == null) {
            return List.of();
        }

        List<SkillDisplay> result = new ArrayList<>();
        for (String namespacedKey : skillsSection.getKeys(false)) {
            String key = simpleKey(namespacedKey);
            Skills skill = resolveSkill(key);
            if (skill == null || !config.skills().getBoolean("skills." + namespacedKey + ".options.enabled", true)) {
                continue;
            }

            int maxLevel = config.skills().getInt("skills." + namespacedKey + ".options.max_level", bridge.getMaxLevel(skill));
            Material material = materialFromMenuConfig(config.skillsMenu(), key, defaultMaterial(key));
            int order = menuOrder(config.skillsMenu(), key);
            String name = message(config.messages(), "skills." + key + ".name", titleCase(key));
            String desc = message(config.messages(), "skills." + key + ".desc", "").replace("{xp_unit}", "经验");
            List<String> abilityIds = config.skills().getStringList("skills." + namespacedKey + ".abilities");
            String manaAbilityId = config.skills().getString("skills." + namespacedKey + ".mana_ability", "");
            result.add(new SkillDisplay(skill, key, name, desc, material, skillColor(key), maxLevel, order, abilityIds, manaAbilityId));
        }

        result.sort(Comparator.comparingInt(SkillDisplay::order).thenComparing(SkillDisplay::key));
        return result;
    }

    private List<String> getRewardsForLevel(SkillDisplay skill, int level) {
        ConfigBundle config = loadConfigBundle();
        List<String> rewards = new ArrayList<>();
        File rewardsFile = new File(config.root(), "rewards/" + skill.key() + ".yml");
        YamlConfiguration rewardsConfig = YamlConfiguration.loadConfiguration(rewardsFile);

        for (Object rawPattern : rewardsConfig.getList("patterns", List.of())) {
            if (!(rawPattern instanceof Map<?, ?> pattern)) {
                continue;
            }

            if (!"stat".equalsIgnoreCase(String.valueOf(pattern.containsKey("type") ? pattern.get("type") : ""))) {
                continue;
            }

            int interval = nestedInt(pattern, "pattern", "interval", 1);
            if (interval <= 0 || level % interval != 0) {
                continue;
            }

            String stat = String.valueOf(pattern.containsKey("stat") ? pattern.get("stat") : "");
            double value = asDouble(pattern.get("value"), 0.0);
            String statName = message(config.messages(), "stats." + stat + ".name", titleCase(stat));
            rewards.add("+" + formatNumber(value) + " " + statName);
        }

        rewards.addAll(getAbilityRewards(config, skill, level));
        addCombatMultiplierReward(skill, level, rewards);
        return rewards;
    }

    private List<String> getAbilityRewards(ConfigBundle config, SkillDisplay skill, int level) {
        List<String> rewards = new ArrayList<>();
        int startLevel = config.main().getInt("start_level", 0);

        for (String abilityId : skill.abilityIds()) {
            String key = simpleKey(abilityId);
            String path = "abilities." + abilityId;
            if (!config.abilities().getBoolean(path + ".enabled", true)) {
                continue;
            }

            int unlock = parseLevelExpression(config.abilities().getString(path + ".unlock", "0"), startLevel);
            int levelUp = config.abilities().getInt(path + ".level_up", 0);
            int abilityLevel = abilityLevelAt(level, unlock, levelUp);
            if (abilityLevel <= 0) {
                continue;
            }

            int max = config.abilities().getInt(path + ".max_level", 0);
            if (max > 0 && abilityLevel > max) {
                continue;
            }

            String name = message(config.messages(), "abilities." + key + ".name", titleCase(key));
            String info = message(config.messages(), "abilities." + key + ".info",
                    message(config.messages(), "abilities." + key + ".desc", ""));
            rewards.add((level == unlock ? "解锁 " : "升级 ") + name + " Lv." + abilityLevel
                    + formatInfoSuffix(info, config.abilities().getConfigurationSection(path), abilityLevel));
        }

        String manaAbilityId = skill.manaAbilityId();
        if (manaAbilityId != null && !manaAbilityId.isBlank()) {
            String key = simpleKey(manaAbilityId);
            String path = "mana_abilities." + manaAbilityId;
            if (config.manaAbilities().getBoolean(path + ".enabled", true)) {
                int unlock = parseLevelExpression(config.manaAbilities().getString(path + ".unlock", "0"), startLevel);
                int levelUp = config.manaAbilities().getInt(path + ".level_up", 0);
                int manaLevel = abilityLevelAt(level, unlock, levelUp);
                int max = config.manaAbilities().getInt(path + ".max_level", 0);
                if (manaLevel > 0 && (max <= 0 || manaLevel <= max)) {
                    String name = message(config.messages(), "mana_abilities." + key + ".name", titleCase(key));
                    String desc = message(config.messages(), "mana_abilities." + key + ".desc", "");
                    rewards.add((level == unlock ? "解锁魔法技能 " : "升级魔法技能 ") + name + " Lv." + manaLevel
                            + formatInfoSuffix(desc, config.manaAbilities().getConfigurationSection(path), manaLevel));
                }
            }
        }

        return rewards;
    }

    private void addCombatMultiplierReward(SkillDisplay skill, int level, List<String> rewards) {
        if (!skill.key().equals("fighting") && !skill.key().equals("archery") && !skill.key().equals("sorcery")) {
            return;
        }

        rewards.add("ServerCore 技能乘区 +0.5%（累计 +" + String.format(Locale.US, "%.1f", level * 0.5) + "%）");
    }

    private int abilityLevelAt(int level, int unlock, int levelUp) {
        if (unlock <= 0 || level < unlock) {
            return 0;
        }
        if (level == unlock) {
            return 1;
        }
        if (levelUp <= 0 || (level - unlock) % levelUp != 0) {
            return 0;
        }
        return 1 + (level - unlock) / levelUp;
    }

    private List<SourceEntry> loadSources(SkillDisplay skill) {
        ConfigBundle config = loadConfigBundle();
        File sourcesFile = new File(config.root(), "sources/" + skill.key() + ".yml");
        YamlConfiguration sourceConfig = YamlConfiguration.loadConfiguration(sourcesFile);
        ConfigurationSection sourceSection = sourceConfig.getConfigurationSection("sources");
        if (sourceSection == null) {
            return List.of();
        }

        List<SourceEntry> sources = new ArrayList<>();
        for (String key : sourceSection.getKeys(false)) {
            ConfigurationSection section = sourceSection.getConfigurationSection(key);
            if (section == null) {
                continue;
            }

            String type = section.getString("type", sourceConfig.getString("default.type", skill.key()));
            String name = sourceName(config.messages(), skill.key(), type, key);
            double xp = section.getDouble("xp", 0.0);
            String unit = resolveMessageToken(config.messages(), section.getString("unit", ""));
            Material material = sourceMaterial(sourceConfig, section, key);
            List<String> extra = sourceExtra(section);
            sources.add(new SourceEntry(name, type, xp, unit.isBlank() ? "" : "/" + unit, material, extra));
        }
        return sources;
    }

    private List<String> sourceExtra(ConfigurationSection section) {
        List<String> extra = new ArrayList<>();
        if (section.contains("multiplier")) {
            extra.add("倍率: " + section.getString("multiplier"));
        }
        if (section.contains("minimum_increase")) {
            extra.add("最小增量: " + section.getString("minimum_increase"));
        }
        if (section.contains("trigger")) {
            extra.add("触发: " + section.getString("trigger"));
        }
        if (section.contains("triggers")) {
            extra.add("触发: " + String.join(", ", section.getStringList("triggers")));
        }
        return extra;
    }

    private Material sourceMaterial(YamlConfiguration sourceConfig, ConfigurationSection source, String key) {
        String material = source.getString("menu_item.material", sourceConfig.getString("default.menu_item.material", "experience_bottle"));
        String block = source.getString("block", key);
        List<String> blocks = source.getStringList("blocks");
        if (!blocks.isEmpty()) {
            block = blocks.get(0);
        }
        String itemMaterial = source.getString("item.material", source.getString("item", key));

        material = material
                .replace("{key}", key)
                .replace("{block}", block)
                .replace("{item.material}", itemMaterial);

        Material matched = Material.matchMaterial(material.toUpperCase(Locale.ROOT));
        return matched == null ? Material.EXPERIENCE_BOTTLE : matched;
    }

    private ConfigBundle loadConfigBundle() {
        File root = getAuraSkillsFolder();
        String language = YamlConfiguration.loadConfiguration(new File(root, "config.yml")).getString("default_language", "zh-CN");
        File messagesFile = new File(root, "messages/messages_" + language + ".yml");
        if (!messagesFile.isFile()) {
            messagesFile = new File(root, "messages/messages_zh-CN.yml");
        }

        return new ConfigBundle(
                root,
                YamlConfiguration.loadConfiguration(new File(root, "config.yml")),
                YamlConfiguration.loadConfiguration(new File(root, "skills.yml")),
                YamlConfiguration.loadConfiguration(new File(root, "menus/skills.yml")),
                YamlConfiguration.loadConfiguration(messagesFile),
                YamlConfiguration.loadConfiguration(new File(root, "abilities.yml")),
                YamlConfiguration.loadConfiguration(new File(root, "mana_abilities.yml"))
        );
    }

    private File getAuraSkillsFolder() {
        Plugin auraSkills = Bukkit.getPluginManager().getPlugin("AuraSkills");
        if (auraSkills != null && auraSkills.getDataFolder().isDirectory()) {
            return auraSkills.getDataFolder();
        }

        File sibling = new File(plugin.getDataFolder().getParentFile(), "AuraSkills");
        if (sibling.isDirectory()) {
            return sibling;
        }

        File localReference = new File("aura-reference");
        if (localReference.isDirectory()) {
            return localReference;
        }

        return plugin.getDataFolder();
    }

    private String sourceName(YamlConfiguration messages, String skillKey, String type, String sourceKey) {
        String fromSkill = messages.getString("sources." + skillKey + "." + sourceKey);
        if (fromSkill != null) {
            return fromSkill;
        }

        String fromType = messages.getString("sources." + type + "." + sourceKey);
        return fromType == null ? titleCase(sourceKey) : fromType;
    }

    private String resolveMessageToken(YamlConfiguration messages, String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        if (raw.startsWith("{") && raw.endsWith("}")) {
            String path = raw.substring(1, raw.length() - 1);
            return messages.getString(path, raw);
        }

        return raw;
    }

    private Material materialFromMenuConfig(YamlConfiguration menu, String key, Material fallback) {
        String raw = menu.getString("templates.skill.contexts." + key + ".material", fallback.name());
        Material material = Material.matchMaterial(raw.toUpperCase(Locale.ROOT));
        return material == null ? fallback : material;
    }

    private int menuOrder(YamlConfiguration menu, String key) {
        String group = menu.getString("templates.skill.contexts." + key + ".group", "zz");
        int row = switch (group) {
            case "first_row" -> 0;
            case "second_row" -> 1;
            case "third_row" -> 2;
            default -> 9;
        };
        return row * 100 + menu.getInt("templates.skill.contexts." + key + ".order", 99);
    }

    private Skills resolveSkill(String key) {
        try {
            return Skills.valueOf(key.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String message(YamlConfiguration config, String path, String fallback) {
        return stripFormatting(config.getString(path, fallback));
    }

    private String formatInfoSuffix(String template, ConfigurationSection section, int level) {
        if (template == null || template.isBlank() || section == null) {
            return "";
        }

        String formatted = applyAbilityPlaceholders(template, section, level);
        formatted = stripFormatting(formatted);
        if (formatted.isBlank()) {
            return "";
        }
        return ": " + formatted;
    }

    private String applyAbilityPlaceholders(String template, ConfigurationSection section, int level) {
        int index = Math.max(0, level - 1);
        Map<String, String> values = new HashMap<>();
        values.put("value", formatNumber(section.getDouble("base_value", 0.0) + section.getDouble("value_per_level", 0.0) * index));
        values.put("secondary_value", formatNumber(section.getDouble("secondary_base_value", 0.0) + section.getDouble("secondary_value_per_level", 0.0) * index));
        values.put("duration", formatNumber(section.getDouble("base_duration", 0.0) + section.getDouble("duration_per_level", 0.0) * index));
        values.put("mana", formatNumber(section.getDouble("base_mana_cost", 0.0) + section.getDouble("mana_cost_per_level", 0.0) * index));
        values.put("cooldown", formatNumber(section.getDouble("base_cooldown", 0.0) + section.getDouble("cooldown_per_level", 0.0) * index));
        values.put("radius", formatNumber(section.getDouble("radius", section.getDouble("max_blocks", 0.0))));
        values.put("haste_level", formatNumber(section.getDouble("haste_level", 0.0)));
        values.put("mana_unit", "魔法值");

        String result = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private int parseLevelExpression(String expression, int startLevel) {
        if (expression == null || expression.isBlank()) {
            return 0;
        }

        String normalized = expression.replace("{start}", String.valueOf(startLevel)).replace(" ", "");
        if (normalized.contains("+")) {
            int total = 0;
            for (String part : normalized.split("\\+")) {
                total += parseInt(part, 0);
            }
            return total;
        }
        if (normalized.contains("-")) {
            String[] parts = normalized.split("-");
            int total = parseInt(parts[0], 0);
            for (int i = 1; i < parts.length; i++) {
                total -= parseInt(parts[i], 0);
            }
            return total;
        }
        return parseInt(normalized, 0);
    }

    private int nestedInt(Map<?, ?> map, String sectionKey, String valueKey, int fallback) {
        Object nested = map.get(sectionKey);
        if (nested instanceof Map<?, ?> nestedMap) {
            return parseInt(String.valueOf(nestedMap.containsKey(valueKey) ? nestedMap.get(valueKey) : fallback), fallback);
        }
        return fallback;
    }

    private double asDouble(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String simpleKey(String namespacedKey) {
        int slash = namespacedKey.indexOf('/');
        return slash >= 0 ? namespacedKey.substring(slash + 1) : namespacedKey;
    }

    private String titleCase(String key) {
        StringBuilder builder = new StringBuilder();
        for (String part : key.split("_")) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return builder.toString();
    }

    private String stripFormatting(String input) {
        if (input == null) {
            return "";
        }
        return input.replaceAll("<[^>]+>", "")
                .replace("{{", "")
                .replace("}}", "")
                .replace("鈻?", "-")
                .trim();
    }

    private Material defaultMaterial(String key) {
        return switch (key) {
            case "farming" -> Material.IRON_HOE;
            case "foraging" -> Material.IRON_AXE;
            case "mining" -> Material.IRON_PICKAXE;
            case "fishing" -> Material.FISHING_ROD;
            case "excavation" -> Material.IRON_SHOVEL;
            case "archery" -> Material.BOW;
            case "defense" -> Material.CHAINMAIL_CHESTPLATE;
            case "fighting" -> Material.IRON_SWORD;
            case "agility" -> Material.FEATHER;
            case "alchemy" -> Material.POTION;
            case "enchanting" -> Material.ENCHANTING_TABLE;
            default -> Material.BOOK;
        };
    }

    private String skillColor(String key) {
        return switch (key) {
            case "farming", "foraging" -> "green";
            case "mining", "enchanting" -> "dark_purple";
            case "fishing" -> "aqua";
            case "excavation" -> "yellow";
            case "archery" -> "gold";
            case "defense" -> "blue";
            case "fighting" -> "red";
            case "agility" -> "white";
            case "alchemy" -> "light_purple";
            default -> "white";
        };
    }

    private ItemStack createNamedItem(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(clean(lore));
        item.setItemMeta(meta);
        return item;
    }

    private List<Component> clean(List<Component> lore) {
        return lore.stream()
                .map(line -> line.decoration(TextDecoration.ITALIC, false))
                .toList();
    }

    private Component text(String input) {
        return Component.text(input).decoration(TextDecoration.ITALIC, false);
    }

    private Component mm(String input) {
        return ServerCorePlugin.getMiniMessage().deserialize(input).decoration(TextDecoration.ITALIC, false);
    }

    private String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return String.valueOf((int) Math.rint(value));
        }
        return String.format(Locale.US, "%.1f", value);
    }

    private record ConfigBundle(
            File root,
            YamlConfiguration main,
            YamlConfiguration skills,
            YamlConfiguration skillsMenu,
            YamlConfiguration messages,
            YamlConfiguration abilities,
            YamlConfiguration manaAbilities
    ) {
    }

    private record SkillDisplay(
            Skills skill,
            String key,
            String name,
            String description,
            Material material,
            String color,
            int maxLevel,
            int order,
            List<String> abilityIds,
            String manaAbilityId
    ) {
    }

    private record SourceEntry(
            String name,
            String type,
            double xp,
            String unitSuffix,
            Material material,
            List<String> extra
    ) {
    }
}
