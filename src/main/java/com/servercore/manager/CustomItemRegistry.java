package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Banner;
import org.bukkit.block.BlockState;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * YAML-backed custom item templates. Ability definitions are parsed here, while
 * concrete ability behavior can be attached later through handlers.
 */
public class CustomItemRegistry {

    private static final String ITEMS_ROOT = "items";
    private static CustomItemRegistry instance;

    private final ServerCorePlugin plugin;
    private final File itemsFile;
    private final Map<String, CustomItemDefinition> items = new LinkedHashMap<>();
    private final Map<String, CustomItemAbilityHandler> abilityHandlers = new LinkedHashMap<>();

    public CustomItemRegistry(ServerCorePlugin plugin) {
        this.plugin = plugin;
        this.itemsFile = new File(plugin.getDataFolder(), "custom_items.yml");
        instance = this;
        reloadItems();
    }

    public static CustomItemRegistry getInstance() {
        return instance;
    }

    public void reloadItems() {
        ensureItemsFile();

        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(itemsFile);
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().severe("Could not reload custom_items.yml; keeping the previous registry: "
                    + exception.getMessage());
            return;
        }
        ConfigurationSection root = config.getConfigurationSection(ITEMS_ROOT);
        if (root == null) {
            items.clear();
            plugin.getLogger().info("Loaded 0 custom item template(s).");
            return;
        }

        Map<String, CustomItemDefinition> loadedItems = new LinkedHashMap<>();
        int loaded = 0;
        for (String rawId : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(rawId);
            if (section == null) {
                continue;
            }

            String itemId = normalizeItemId(rawId);
            CustomItemDefinition definition = readDefinition(itemId, section);
            if (definition == null) {
                continue;
            }

            loadedItems.put(itemId, definition);
            loaded++;
        }

        items.clear();
        items.putAll(loadedItems);
        plugin.getLogger().info("Loaded " + loaded + " custom item template(s).");
    }

    public int getItemCount() {
        return items.size();
    }

    public List<String> getItemIds() {
        return List.copyOf(items.keySet());
    }

    public Map<String, CustomItemDefinition> getDefinitions() {
        return Map.copyOf(items);
    }

    public CustomItemDefinition getDefinition(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }

        String normalized = normalizeItemId(itemId);
        CustomItemDefinition definition = items.get(normalized);
        if (definition != null) {
            return definition;
        }

        int namespaceSplit = normalized.indexOf(':');
        if (namespaceSplit >= 0 && namespaceSplit + 1 < normalized.length()) {
            return items.get(normalized.substring(namespaceSplit + 1));
        }
        return null;
    }

    public String getDisplayName(String itemId) {
        CustomItemDefinition definition = getDefinition(itemId);
        return definition == null ? null : definition.displayName();
    }

    public ItemStack createItem(String itemId) {
        return createItem(itemId, -1);
    }

    public ItemStack createItem(String itemId, int amountOverride) {
        CustomItemDefinition definition = getDefinition(itemId);
        if (definition == null) {
            return null;
        }

        int amount = amountOverride > 0 ? amountOverride : definition.amount();
        ItemStack item = new ItemStack(definition.material(), clampAmount(definition.material(), amount));
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return item;
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(pdc.KEY_ITEM_ID, PersistentDataType.STRING, definition.id());
        container.set(pdc.KEY_ITEM_SCALE_VERSION, PersistentDataType.INTEGER, ItemStandardizer.VANILLA_SCALE_VERSION);
        container.set(pdc.KEY_ITEM_ORIGINAL_NAME, PersistentDataType.STRING, definition.displayName());
        container.set(pdc.KEY_ITEM_RARITY, PersistentDataType.STRING, definition.rarity().name());

        if (definition.accessoryType() != null && !definition.accessoryType().isBlank()) {
            container.set(pdc.KEY_ACC_TYPE, PersistentDataType.STRING, definition.accessoryType());
        }
        if (definition.imprintEligible()) {
            container.set(pdc.KEY_ITEM_IMPRINT_ELIGIBLE, PersistentDataType.BYTE, (byte) 1);
        }
        if (!definition.setId().isBlank()) {
            container.set(pdc.KEY_ITEM_SET_ID, PersistentDataType.STRING, definition.setId());
        }
        if (!definition.setPieceId().isBlank()) {
            container.set(pdc.KEY_ITEM_SET_PIECE_ID, PersistentDataType.STRING, definition.setPieceId());
        }
        if (!definition.talismanFamily().isBlank()) {
            container.set(pdc.KEY_ITEM_TALISMAN_FAMILY, PersistentDataType.STRING, definition.talismanFamily());
        }

        if (definition.weaponTemplate() != null) {
            container.set(pdc.KEY_WEAPON_TEMPLATE, PersistentDataType.STRING, definition.weaponTemplate().name());
        }
        if (definition.handRule() != null) {
            container.set(pdc.KEY_WEAPON_HAND_RULE, PersistentDataType.STRING, definition.handRule().name());
        }

        for (Map.Entry<String, Double> stat : definition.stats().entrySet()) {
            NamespacedKey key = statKey(pdc, stat.getKey());
            if (key != null && Math.abs(stat.getValue()) >= 0.0001) {
                container.set(key, PersistentDataType.DOUBLE, stat.getValue());
            }
        }

        if (!definition.sockets().isEmpty()) {
            List<String> socketTypes = new ArrayList<>();
            List<String> socketGems = new ArrayList<>();
            for (GemstoneManager.SocketType socket : definition.sockets()) {
                socketTypes.add(socket.name());
                socketGems.add("EMPTY");
            }
            container.set(pdc.KEY_ITEM_SOCKET_TYPES, PersistentDataType.STRING, String.join(",", socketTypes));
            container.set(pdc.KEY_ITEM_SOCKET_GEMS, PersistentDataType.STRING, String.join(",", socketGems));
        }

        if (!definition.enchants().isEmpty()) {
            container.set(pdc.KEY_ITEM_CUSTOM_ENCHANTS, PersistentDataType.STRING, encodeEnchants(definition.enchants()));
        }

        if (!definition.abilityLore().isEmpty()) {
            container.set(pdc.KEY_ITEM_ABILITIES, PersistentDataType.STRING, joinTextLines(definition.abilityLore()));
        }

        if (!definition.storyLore().isEmpty()) {
            container.set(pdc.KEY_ITEM_STORY_LORE, PersistentDataType.STRING, joinTextLines(definition.storyLore()));
        }

        if (!definition.skillRequirement().isBlank()) {
            container.set(pdc.KEY_REQ_SKILL, PersistentDataType.STRING, definition.skillRequirement());
        }
        if (!definition.slayerRequirement().isBlank()) {
            container.set(pdc.KEY_REQ_SLAYER, PersistentDataType.STRING, definition.slayerRequirement());
        }
        if (!definition.dungeonRequirement().isBlank()) {
            container.set(pdc.KEY_REQ_DUNGEON, PersistentDataType.STRING, definition.dungeonRequirement());
        }

        if (definition.customModelData() != null) {
            meta.setCustomModelData(definition.customModelData());
        }
        meta.setUnbreakable(definition.unbreakable());
        applyArmorAppearance(meta, definition.appearance());
        applyShieldAppearance(meta, definition.shieldAppearance());

        item.setItemMeta(meta);

        if (definition.weaponTemplate() != null) {
            WeaponTemplateManager templateManager = WeaponTemplateManager.getInstance();
            if (templateManager != null) {
                templateManager.applyTemplateToItem(item, definition.weaponTemplate());
                if (definition.handRule() != null) {
                    templateManager.applyHandRuleToItem(item, definition.handRule());
                }
            }
        }

        if (definition.reforgeId() != null && !definition.reforgeId().isBlank()) {
            ReforgeManager reforgeManager = ReforgeManager.getInstance();
            if (reforgeManager != null) {
                reforgeManager.applyReforge(item, definition.reforgeId());
            }
        }

        ItemFormatManager formatManager = ItemFormatManager.getInstance();
        if (formatManager != null) {
            formatManager.formatItem(item, true);
        }
        return item;
    }

    public SaveResult setHeldItemId(ItemStack item, String rawItemId) {
        String itemId = normalizeItemId(rawItemId);
        if (!isValidItemId(itemId)) {
            return new SaveResult(false, itemId, "Item ID must use only letters, numbers, _, -, . or :.");
        }
        if (item == null || item.getType().isAir()) {
            return new SaveResult(false, itemId, "Hold an item first.");
        }

        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return new SaveResult(false, itemId, "PDC manager is not ready.");
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return new SaveResult(false, itemId, "This item cannot be edited.");
        }

        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(pdc.KEY_ITEM_ID, PersistentDataType.STRING, itemId);
        container.set(pdc.KEY_ITEM_SCALE_VERSION, PersistentDataType.INTEGER, ItemStandardizer.VANILLA_SCALE_VERSION);
        if (!container.has(pdc.KEY_ITEM_ORIGINAL_NAME, PersistentDataType.STRING)) {
            container.set(pdc.KEY_ITEM_ORIGINAL_NAME, PersistentDataType.STRING, readDisplayName(item, meta, itemId));
        }
        item.setItemMeta(meta);

        ItemFormatManager formatManager = ItemFormatManager.getInstance();
        if (formatManager != null) {
            formatManager.formatItem(item, true);
        }
        return new SaveResult(true, itemId, "Item ID updated.");
    }

    public SaveResult saveHeldItemTemplate(ItemStack item, String rawItemId) {
        if (item == null || item.getType().isAir()) {
            return new SaveResult(false, "", "Hold an item first.");
        }

        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return new SaveResult(false, "", "PDC manager is not ready.");
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return new SaveResult(false, "", "This item cannot be saved.");
        }

        String itemId = normalizeItemId(rawItemId);
        PersistentDataContainer container = meta.getPersistentDataContainer();
        if (itemId.isBlank()) {
            itemId = normalizeItemId(container.get(pdc.KEY_ITEM_ID, PersistentDataType.STRING));
        }
        if (!isValidItemId(itemId) || itemId.startsWith("vanilla_") || itemId.startsWith("minecraft:")) {
            return new SaveResult(false, itemId, "Use a custom item ID before saving.");
        }

        SaveResult idResult = setHeldItemId(item, itemId);
        if (!idResult.success()) {
            return idResult;
        }
        meta = item.getItemMeta();
        if (meta == null) {
            return new SaveResult(false, itemId, "This item cannot be saved.");
        }
        container = meta.getPersistentDataContainer();

        ensureItemsFile();
        YamlConfiguration config = YamlConfiguration.loadConfiguration(itemsFile);
        ConfigurationSection root = config.getConfigurationSection(ITEMS_ROOT);
        if (root == null) {
            root = config.createSection(ITEMS_ROOT);
        }
        root.set(itemId, null);
        ConfigurationSection section = root.createSection(itemId);
        writeDefinitionFromItem(section, item, meta, container, pdc, itemId);

        try {
            config.save(itemsFile);
        } catch (IOException exception) {
            return new SaveResult(false, itemId, "Could not save custom_items.yml: " + exception.getMessage());
        }

        reloadItems();
        return new SaveResult(true, itemId, "Saved custom item template.");
    }

    public List<AbilityDefinition> getAbilities(ItemStack item) {
        CustomItemDefinition definition = getDefinition(readItemId(item));
        return definition == null ? List.of() : definition.abilities();
    }

    public String getItemId(ItemStack item) {
        return readItemId(item);
    }

    public AbilityDefinition getActiveAbility(ItemStack item, EquipmentSlot hand) {
        CustomItemDefinition definition = getDefinition(readItemId(item));
        if (definition == null) {
            return null;
        }

        AbilityDefinition fallback = null;
        for (AbilityDefinition ability : definition.abilities()) {
            if (!isActiveTrigger(ability.trigger())) {
                continue;
            }
            if (matchesActiveTrigger(ability.trigger(), hand)) {
                return ability;
            }
            if (fallback == null && ability.trigger().equals("RIGHT_CLICK")) {
                fallback = ability;
            }
        }
        return fallback;
    }

    public void registerAbilityHandler(String abilityId, CustomItemAbilityHandler handler) {
        if (abilityId == null || abilityId.isBlank() || handler == null) {
            return;
        }
        abilityHandlers.put(normalizeItemId(abilityId), handler);
    }

    public int triggerAbilities(Player player, ItemStack item, String trigger) {
        if (player == null || item == null || trigger == null) {
            return 0;
        }

        CustomItemDefinition itemDefinition = getDefinition(readItemId(item));
        if (itemDefinition == null) {
            return 0;
        }

        String normalizedTrigger = normalizeTrigger(trigger);
        int executed = 0;
        for (AbilityDefinition ability : itemDefinition.abilities()) {
            if (!ability.trigger().equals("ANY") && !ability.trigger().equals(normalizedTrigger)) {
                continue;
            }

            CustomItemAbilityHandler handler = abilityHandlers.get(ability.id());
            if (handler == null) {
                handler = abilityHandlers.get("*");
            }
            if (handler != null && handler.execute(new AbilityContext(player, item, itemDefinition, ability))) {
                executed++;
            }
        }
        return executed;
    }

    public boolean triggerAbility(Player player, ItemStack item, AbilityDefinition ability) {
        if (player == null || item == null || ability == null) {
            return false;
        }

        CustomItemDefinition itemDefinition = getDefinition(readItemId(item));
        if (itemDefinition == null) {
            return false;
        }

        CustomItemAbilityHandler handler = abilityHandlers.get(ability.id());
        if (handler == null) {
            handler = abilityHandlers.get("*");
        }
        return handler != null && handler.execute(new AbilityContext(player, item, itemDefinition, ability));
    }

    private boolean isActiveTrigger(String trigger) {
        if (trigger == null || trigger.isBlank()) {
            return false;
        }
        return !trigger.equals("PASSIVE");
    }

    private boolean matchesActiveTrigger(String trigger, EquipmentSlot hand) {
        if (trigger == null) {
            return false;
        }
        return switch (trigger) {
            case "ANY", "ACTIVE", "RIGHT_CLICK" -> true;
            case "MAIN_HAND_RIGHT_CLICK", "MAINHAND_RIGHT_CLICK", "MAIN_HAND_ACTIVE" -> hand == EquipmentSlot.HAND;
            case "OFF_HAND_RIGHT_CLICK", "OFFHAND_RIGHT_CLICK", "OFF_HAND_ACTIVE", "SNEAK_RIGHT_CLICK", "SHIFT_RIGHT_CLICK" -> hand == EquipmentSlot.OFF_HAND;
            default -> false;
        };
    }

    private void ensureItemsFile() {
        if (itemsFile.exists()) {
            return;
        }

        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder for custom_items.yml.");
            return;
        }

        try (InputStream stream = plugin.getResource("custom_items.yml")) {
            if (stream != null) {
                plugin.saveResource("custom_items.yml", false);
                return;
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Unable to check bundled custom_items.yml: " + exception.getMessage());
        }

        try {
            YamlConfiguration config = new YamlConfiguration();
            config.createSection(ITEMS_ROOT);
            config.save(itemsFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not create custom_items.yml: " + exception.getMessage());
        }
    }

    private CustomItemDefinition readDefinition(String itemId, ConfigurationSection section) {
        Material material = Material.matchMaterial(section.getString("material", "STONE"));
        if (material == null || material.isAir()) {
            plugin.getLogger().warning("Custom item '" + itemId + "' has invalid material.");
            return null;
        }

        String displayName = section.getString("name", itemId);
        ItemFormatManager.Rarity rarity = parseRarity(section.getString("rarity", "COMMON"));
        Map<String, Double> stats = readStats(section.getConfigurationSection("stats"));
        List<GemstoneManager.SocketType> sockets = readSockets(section);
        Map<String, Integer> enchants = readEnchants(section);
        List<AbilityDefinition> abilities = readAbilities(section);
        WeaponTemplateManager.WeaponTemplate weaponTemplate = readWeaponTemplate(section, material);
        WeaponTemplateManager.HandRule handRule = readHandRule(section);
        ShieldAppearance shieldAppearance = readShieldAppearance(section, material);
        List<String> abilityLore = readStringList(section, "ability_lore");
        for (AbilityDefinition ability : abilities) {
            abilityLore.addAll(ability.lore());
        }

        return new CustomItemDefinition(
                itemId,
                material,
                Math.max(1, section.getInt("amount", 1)),
                displayName,
                rarity,
                stats,
                sockets,
                enchants,
                abilities,
                abilityLore,
                readStringList(section, "story_lore"),
                section.getString("accessory_type", section.getString("acc_type", "")),
                section.getBoolean("imprint_eligible", false),
                normalizeItemId(section.getString("set_id", "")),
                normalizeItemId(section.getString("set_piece_id", "")),
                normalizeItemId(section.getString("talisman_family", "")),
                section.getInt("talisman_priority", 0),
                section.getString("reforge", ""),
                readRequirement(section, "skill"),
                readRequirement(section, "slayer"),
                readRequirement(section, "dungeon"),
                section.contains("custom_model_data") ? section.getInt("custom_model_data") : null,
                section.getBoolean("unbreakable", false),
                weaponTemplate,
                handRule,
                shieldAppearance,
                readArmorAppearance(section)
        );
    }

    private ArmorAppearance readArmorAppearance(ConfigurationSection section) {
        ConfigurationSection appearanceSection = section.getConfigurationSection("appearance");
        String rawLeatherColor = appearanceSection == null
                ? section.getString("leather_color", "")
                : appearanceSection.getString("leather_color", appearanceSection.getString("color", ""));
        Color leatherColor = parseColor(rawLeatherColor);

        ConfigurationSection trimSection = appearanceSection == null
                ? section.getConfigurationSection("trim")
                : appearanceSection.getConfigurationSection("trim");
        if (trimSection == null) {
            trimSection = section.getConfigurationSection("armor_trim");
        }

        ArmorTrim trim = null;
        if (trimSection != null) {
            TrimMaterial trimMaterial = readTrimMaterial(trimSection.getString("material", ""));
            TrimPattern trimPattern = readTrimPattern(trimSection.getString("pattern", ""));
            if (trimMaterial != null && trimPattern != null) {
                trim = new ArmorTrim(trimMaterial, trimPattern);
            }
        }

        return leatherColor == null && trim == null ? null : new ArmorAppearance(leatherColor, trim);
    }

    private WeaponTemplateManager.WeaponTemplate readWeaponTemplate(ConfigurationSection section, Material material) {
        String raw = section.getString("weapon_template", section.getString("template", ""));
        WeaponTemplateManager templateManager = WeaponTemplateManager.getInstance();
        if (raw == null || raw.isBlank()) {
            return templateManager == null ? null : templateManager.getDefaultTemplate(material);
        }

        WeaponTemplateManager.WeaponTemplate template = templateManager == null ? null : templateManager.parseTemplate(raw);
        if (template == null) {
            plugin.getLogger().warning("Custom item '" + section.getName() + "' has unknown weapon_template '" + raw + "'.");
        }
        return template;
    }

    private WeaponTemplateManager.HandRule readHandRule(ConfigurationSection section) {
        String raw = section.getString("hand_rule", section.getString("weapon_hand_rule", section.getString("slot_rule", "")));
        if (raw == null || raw.isBlank()) {
            return null;
        }

        WeaponTemplateManager templateManager = WeaponTemplateManager.getInstance();
        WeaponTemplateManager.HandRule handRule = templateManager == null ? null : templateManager.parseHandRule(raw);
        if (handRule == null) {
            plugin.getLogger().warning("Custom item '" + section.getName() + "' has unknown hand_rule '" + raw + "'.");
        }
        return handRule;
    }

    private ShieldAppearance readShieldAppearance(ConfigurationSection section, Material material) {
        if (material != Material.SHIELD) {
            return null;
        }

        ConfigurationSection shieldSection = section.getConfigurationSection("shield");
        String rawBaseColor = shieldSection == null
                ? section.getString("shield_color", section.getString("base_color", ""))
                : shieldSection.getString("color", shieldSection.getString("base_color", ""));
        DyeColor baseColor = parseDyeColor(rawBaseColor);

        Object rawPatterns = shieldSection == null ? section.get("shield_patterns") : shieldSection.get("patterns");
        List<Pattern> patterns = readShieldPatterns(rawPatterns, section.getName());
        if (baseColor == null && patterns.isEmpty()) {
            return null;
        }
        return new ShieldAppearance(baseColor == null ? DyeColor.WHITE : baseColor, patterns);
    }

    private List<Pattern> readShieldPatterns(Object rawPatterns, String itemId) {
        List<Pattern> patterns = new ArrayList<>();
        if (rawPatterns == null) {
            return patterns;
        }

        if (rawPatterns instanceof List<?> list) {
            for (Object rawPattern : list) {
                Pattern pattern = parseShieldPattern(rawPattern, itemId);
                if (pattern != null) {
                    patterns.add(pattern);
                }
            }
            return patterns;
        }

        Pattern pattern = parseShieldPattern(rawPatterns, itemId);
        if (pattern != null) {
            patterns.add(pattern);
        }
        return patterns;
    }

    private Pattern parseShieldPattern(Object rawPattern, String itemId) {
        if (rawPattern instanceof Map<?, ?> map) {
            DyeColor color = parseDyeColor(stringValue(map.get("color"), ""));
            PatternType type = parsePatternType(stringValue(map.get("pattern"), stringValue(map.get("type"), "")));
            if (color != null && type != null) {
                return new Pattern(color, type);
            }
        } else if (rawPattern != null) {
            String text = String.valueOf(rawPattern).trim();
            String[] parts = text.split(":", 2);
            if (parts.length == 2) {
                DyeColor color = parseDyeColor(parts[0]);
                PatternType type = parsePatternType(parts[1]);
                if (color != null && type != null) {
                    return new Pattern(color, type);
                }
            }
        }

        plugin.getLogger().warning("Custom item '" + itemId + "' has invalid shield pattern '" + rawPattern + "'.");
        return null;
    }

    private DyeColor parseDyeColor(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DyeColor.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    @SuppressWarnings({"deprecation", "removal"})
    private PatternType parsePatternType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        PatternType byIdentifier = PatternType.getByIdentifier(normalized);
        if (byIdentifier != null) {
            return byIdentifier;
        }
        try {
            return (PatternType) PatternType.class.getField(normalized.toUpperCase(Locale.ROOT)).get(null);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private void applyShieldAppearance(ItemMeta meta, ShieldAppearance appearance) {
        if (appearance == null || !(meta instanceof BlockStateMeta blockStateMeta)) {
            return;
        }

        BlockState blockState = blockStateMeta.getBlockState();
        if (!(blockState instanceof Banner banner)) {
            return;
        }

        banner.setBaseColor(appearance.baseColor());
        banner.setPatterns(appearance.patterns());
        blockStateMeta.setBlockState(banner);
    }

    private void applyArmorAppearance(ItemMeta meta, ArmorAppearance appearance) {
        if (appearance == null) {
            return;
        }
        if (appearance.leatherColor() != null && meta instanceof LeatherArmorMeta leatherMeta) {
            leatherMeta.setColor(appearance.leatherColor());
        }
        if (appearance.trim() != null && meta instanceof ArmorMeta armorMeta) {
            armorMeta.setTrim(appearance.trim());
        }
    }

    private void writeDefinitionFromItem(ConfigurationSection section, ItemStack item, ItemMeta meta,
                                         PersistentDataContainer container, PDCManager pdc, String itemId) {
        section.set("material", item.getType().name());
        section.set("amount", Math.max(1, item.getAmount()));
        section.set("name", readDisplayName(item, meta, itemId));

        ItemFormatManager formatManager = ItemFormatManager.getInstance();
        String rarity = container.get(pdc.KEY_ITEM_RARITY, PersistentDataType.STRING);
        if (rarity == null || rarity.isBlank()) {
            rarity = formatManager == null ? ItemFormatManager.Rarity.COMMON.name() : formatManager.getRarity(item).name();
        }
        section.set("rarity", rarity.toUpperCase(Locale.ROOT));

        Map<String, Double> stats = readCurrentStats(container, pdc);
        subtractStats(stats, decodeStatString(container.get(pdc.KEY_ITEM_REFORGE_STATS, PersistentDataType.STRING)));
        subtractGemstoneStats(stats, container.get(pdc.KEY_ITEM_SOCKET_GEMS, PersistentDataType.STRING));
        if (!stats.isEmpty()) {
            ConfigurationSection statsSection = section.createSection("stats");
            for (Map.Entry<String, Double> entry : stats.entrySet()) {
                statsSection.set(entry.getKey(), normalizeYamlNumber(entry.getValue()));
            }
        }

        List<String> sockets = readSocketTypes(container.get(pdc.KEY_ITEM_SOCKET_TYPES, PersistentDataType.STRING));
        if (!sockets.isEmpty()) {
            section.set("sockets", sockets);
        }

        Map<String, Integer> enchants = decodeEnchants(container.get(pdc.KEY_ITEM_CUSTOM_ENCHANTS, PersistentDataType.STRING));
        if (!enchants.isEmpty()) {
            ConfigurationSection enchantsSection = section.createSection("enchants");
            for (Map.Entry<String, Integer> entry : enchants.entrySet()) {
                enchantsSection.set(entry.getKey(), entry.getValue());
            }
        }

        List<String> abilityLore = splitTextBlock(container.get(pdc.KEY_ITEM_ABILITIES, PersistentDataType.STRING));
        if (!abilityLore.isEmpty()) {
            section.set("ability_lore", abilityLore);
        }
        List<String> storyLore = splitTextBlock(container.get(pdc.KEY_ITEM_STORY_LORE, PersistentDataType.STRING));
        if (!storyLore.isEmpty()) {
            section.set("story_lore", storyLore);
        }

        String accessoryType = container.get(pdc.KEY_ACC_TYPE, PersistentDataType.STRING);
        if (accessoryType != null && !accessoryType.isBlank()) {
            section.set("accessory_type", accessoryType);
        }
        if (container.getOrDefault(pdc.KEY_ITEM_IMPRINT_ELIGIBLE, PersistentDataType.BYTE, (byte) 0) != 0) {
            section.set("imprint_eligible", true);
        }
        String setId = container.get(pdc.KEY_ITEM_SET_ID, PersistentDataType.STRING);
        if (setId != null && !setId.isBlank()) {
            section.set("set_id", setId);
        }
        String setPieceId = container.get(pdc.KEY_ITEM_SET_PIECE_ID, PersistentDataType.STRING);
        if (setPieceId != null && !setPieceId.isBlank()) {
            section.set("set_piece_id", setPieceId);
        }
        String talismanFamily = container.get(pdc.KEY_ITEM_TALISMAN_FAMILY, PersistentDataType.STRING);
        if (talismanFamily != null && !talismanFamily.isBlank()) {
            section.set("talisman_family", talismanFamily);
        }

        String reforge = container.get(pdc.KEY_ITEM_REFORGE_ID, PersistentDataType.STRING);
        if (reforge != null && !reforge.isBlank()) {
            section.set("reforge", reforge);
        }

        writeRequirement(section, "req_skill", container.get(pdc.KEY_REQ_SKILL, PersistentDataType.STRING));
        writeRequirement(section, "req_slayer", container.get(pdc.KEY_REQ_SLAYER, PersistentDataType.STRING));
        writeRequirement(section, "req_dungeon", container.get(pdc.KEY_REQ_DUNGEON, PersistentDataType.STRING));

        if (meta.hasCustomModelData()) {
            section.set("custom_model_data", meta.getCustomModelData());
        }
        if (meta.isUnbreakable()) {
            section.set("unbreakable", true);
        }

        String weaponTemplate = container.get(pdc.KEY_WEAPON_TEMPLATE, PersistentDataType.STRING);
        if (weaponTemplate != null && !weaponTemplate.isBlank()) {
            section.set("weapon_template", weaponTemplate);
        }
        String handRule = container.get(pdc.KEY_WEAPON_HAND_RULE, PersistentDataType.STRING);
        if (handRule != null && !handRule.isBlank()) {
            section.set("hand_rule", handRule);
        }

        writeShieldAppearance(section, meta);
        writeArmorAppearance(section, meta);
    }

    private void writeShieldAppearance(ConfigurationSection section, ItemMeta meta) {
        if (!(meta instanceof BlockStateMeta blockStateMeta)) {
            return;
        }
        BlockState blockState = blockStateMeta.getBlockState();
        if (!(blockState instanceof Banner banner)) {
            return;
        }

        ConfigurationSection shield = section.createSection("shield");
        shield.set("color", banner.getBaseColor().name());
        List<String> patterns = new ArrayList<>();
        for (Pattern pattern : banner.getPatterns()) {
            patterns.add(pattern.getColor().name() + ":" + patternTypeName(pattern.getPattern()));
        }
        if (!patterns.isEmpty()) {
            shield.set("patterns", patterns);
        }
    }

    @SuppressWarnings({"deprecation", "removal"})
    private void writeArmorAppearance(ConfigurationSection section, ItemMeta meta) {
        Color leatherColor = meta instanceof LeatherArmorMeta leatherMeta ? leatherMeta.getColor() : null;
        ArmorTrim trim = meta instanceof ArmorMeta armorMeta && armorMeta.hasTrim() ? armorMeta.getTrim() : null;
        if (leatherColor == null && trim == null) {
            return;
        }

        ConfigurationSection appearance = section.createSection("appearance");
        if (leatherColor != null) {
            appearance.set("leather_color", toHexColor(leatherColor));
        }
        if (trim != null) {
            ConfigurationSection trimSection = appearance.createSection("trim");
            trimSection.set("material", trim.getMaterial().getKey().asString());
            trimSection.set("pattern", trim.getPattern().getKey().asString());
        }
    }

    private String readDisplayName(ItemStack item, ItemMeta meta, String fallback) {
        PDCManager pdc = PDCManager.getInstance();
        if (pdc != null) {
            String originalName = meta.getPersistentDataContainer().get(pdc.KEY_ITEM_ORIGINAL_NAME, PersistentDataType.STRING);
            if (originalName != null && !originalName.isBlank()) {
                return originalName;
            }
        }
        if (meta.hasDisplayName() && meta.displayName() != null) {
            String plainName = PlainTextComponentSerializer.plainText().serialize(meta.displayName()).trim();
            if (!plainName.isBlank()) {
                return plainName;
            }
        }
        return fallback == null || fallback.isBlank() ? item.getType().name().toLowerCase(Locale.ROOT) : fallback;
    }

    private Map<String, Double> readCurrentStats(PersistentDataContainer container, PDCManager pdc) {
        Map<String, Double> stats = new LinkedHashMap<>();
        addCurrentStat(stats, container, pdc.KEY_BASE_DAMAGE, "base_damage");
        addCurrentStat(stats, container, pdc.KEY_BASE_MULTIPLIER, "base_multiplier");
        addCurrentStat(stats, container, pdc.KEY_CRIT_CHANCE, "crit_chance");
        addCurrentStat(stats, container, pdc.KEY_CRIT_DAMAGE, "crit_damage");
        addCurrentStat(stats, container, pdc.KEY_BRUTALITY, "brutality");
        addCurrentStat(stats, container, pdc.KEY_LIFESTEAL, "lifesteal");
        addCurrentStat(stats, container, pdc.KEY_ARMOR_PEN, "armor_pen");
        addCurrentStat(stats, container, pdc.KEY_BASE_ARMOR, "base_armor");
        addCurrentStat(stats, container, pdc.KEY_ATTACK_SPEED_BONUS, "attack_speed_bonus");
        addCurrentStat(stats, container, pdc.KEY_SHIELD_BLOCK_THRESHOLD, "shield_block_threshold");
        addCurrentStat(stats, container, pdc.KEY_SHIELD_EFFECTIVE_BLOCK, "shield_effective_block");
        addCurrentStat(stats, container, pdc.KEY_SHIELD_COOLDOWN_SECONDS, "shield_cooldown_seconds");
        addCurrentStat(stats, container, pdc.KEY_ATTR_TOUGHNESS, "attr_toughness");
        addCurrentStat(stats, container, pdc.KEY_ATTR_AGILITY, "attr_agility");
        addCurrentStat(stats, container, pdc.KEY_ATTR_INTELLIGENCE, "attr_intelligence");
        addCurrentStat(stats, container, pdc.KEY_ATTR_WILLPOWER, "attr_willpower");
        addCurrentStat(stats, container, pdc.KEY_ATTR_LUCK, "attr_luck");
        addCurrentStat(stats, container, pdc.KEY_TOOL_FORTUNE, "tool_fortune");
        addCurrentStat(stats, container, pdc.KEY_COLLECTION_FORTUNE, "collection_fortune");
        addCurrentStat(stats, container, pdc.KEY_FORAGING_FORTUNE, "foraging_fortune");
        addCurrentStat(stats, container, pdc.KEY_FARMING_FORTUNE, "farming_fortune");
        addCurrentStat(stats, container, pdc.KEY_EXCAVATION_FORTUNE, "excavation_fortune");
        addCurrentStat(stats, container, pdc.KEY_MINING_FORTUNE, "mining_fortune");
        addCurrentStat(stats, container, pdc.KEY_TOOL_SWEEP, "tool_sweep");
        addCurrentStat(stats, container, pdc.KEY_COLLECTION_SWEEP, "collection_sweep");
        addCurrentStat(stats, container, pdc.KEY_FORAGING_SWEEP, "foraging_sweep");
        addCurrentStat(stats, container, pdc.KEY_FARMING_SWEEP, "farming_sweep");
        addCurrentStat(stats, container, pdc.KEY_EXCAVATION_SWEEP, "excavation_sweep");
        addCurrentStat(stats, container, pdc.KEY_TOOL_SPREAD, "tool_spread");
        addCurrentStat(stats, container, pdc.KEY_MINING_SPREAD, "mining_spread");
        addCurrentStat(stats, container, pdc.KEY_TOOL_MINING_SPEED, "tool_mining_speed");
        addCurrentStat(stats, container, pdc.KEY_BREAKING_POWER, "breaking_power");
        addCurrentStat(stats, container, pdc.KEY_PURITY, "purity");
        addCurrentStat(stats, container, pdc.KEY_MINING_PURITY, "mining_purity");
        addCurrentStat(stats, container, pdc.KEY_FISHING_SPEED, "fishing_speed");
        addCurrentStat(stats, container, pdc.KEY_SEA_CREATURE_CHANCE, "sea_creature_chance");
        addCurrentStat(stats, container, pdc.KEY_TREASURE_CHANCE, "treasure_chance");
        addCurrentStat(stats, container, pdc.KEY_BOUNTY, "bounty");
        addCurrentStat(stats, container, pdc.KEY_OVERBLOOM, "overbloom");
        return stats;
    }

    private void addCurrentStat(Map<String, Double> stats, PersistentDataContainer container, NamespacedKey key, String statName) {
        double value = container.getOrDefault(key, PersistentDataType.DOUBLE, 0.0);
        if (Math.abs(value) >= 0.0001) {
            stats.put(statName, value);
        }
    }

    private void subtractStats(Map<String, Double> stats, Map<String, Double> subtracted) {
        for (Map.Entry<String, Double> entry : subtracted.entrySet()) {
            String statName = normalizeStatName(entry.getKey());
            if (statName.isBlank()) {
                continue;
            }
            double next = stats.getOrDefault(statName, 0.0) - entry.getValue();
            if (Math.abs(next) < 0.0001) {
                stats.remove(statName);
            } else {
                stats.put(statName, next);
            }
        }
    }

    private void subtractGemstoneStats(Map<String, Double> stats, String rawGemSlots) {
        GemstoneManager gemstoneManager = GemstoneManager.getInstance();
        if (gemstoneManager == null || rawGemSlots == null || rawGemSlots.isBlank()) {
            return;
        }

        for (String rawGem : rawGemSlots.split(",")) {
            String gemId = normalizeItemId(rawGem);
            if (gemId.isBlank() || gemId.equals("empty")) {
                continue;
            }
            GemstoneManager.GemstoneDefinition gemstone = gemstoneManager.getGemstone(gemId);
            if (gemstone != null) {
                subtractStats(stats, gemstone.stats());
            }
        }
    }

    private Map<String, Double> decodeStatString(String raw) {
        Map<String, Double> stats = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return stats;
        }
        for (String entry : raw.split(";")) {
            String[] parts = entry.split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            String statName = normalizeStatName(parts[0]);
            if (statName.isBlank()) {
                continue;
            }
            try {
                stats.put(statName, Double.parseDouble(parts[1]));
            } catch (NumberFormatException ignored) {
                // Historical malformed stat entries are ignored during export.
            }
        }
        return stats;
    }

    private Map<String, Integer> decodeEnchants(String raw) {
        Map<String, Integer> enchants = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return enchants;
        }
        for (String entry : raw.split(";")) {
            String[] parts = entry.split(":", 2);
            if (parts.length == 0 || parts[0].isBlank()) {
                continue;
            }
            int level = 1;
            if (parts.length == 2) {
                try {
                    level = Math.max(1, Integer.parseInt(parts[1].trim()));
                } catch (NumberFormatException ignored) {
                    level = 1;
                }
            }
            enchants.put(normalizeItemId(parts[0]), level);
        }
        return enchants;
    }

    private List<String> readSocketTypes(String raw) {
        List<String> sockets = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return sockets;
        }
        for (String socket : raw.split(",")) {
            String normalized = socket.trim().toUpperCase(Locale.ROOT);
            if (!normalized.isBlank() && !normalized.equals("EMPTY")) {
                sockets.add(normalized);
            }
        }
        return sockets;
    }

    private List<String> splitTextBlock(String raw) {
        List<String> lines = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return lines;
        }
        for (String line : raw.split("\\|\\|")) {
            if (!line.isBlank()) {
                lines.add(line.trim());
            }
        }
        return lines;
    }

    private Object normalizeYamlNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return (int) Math.rint(value);
        }
        return value;
    }

    private Color parseColor(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith("#")) {
            text = text.substring(1);
        }
        if (text.length() == 6) {
            try {
                return Color.fromRGB(Integer.parseInt(text.substring(0, 2), 16),
                        Integer.parseInt(text.substring(2, 4), 16),
                        Integer.parseInt(text.substring(4, 6), 16));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        DyeColor dyeColor = parseDyeColor(text);
        return dyeColor == null ? null : dyeColor.getColor();
    }

    private String toHexColor(Color color) {
        return String.format(Locale.ROOT, "#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }

    private TrimMaterial readTrimMaterial(String raw) {
        NamespacedKey key = parseNamespacedKey(raw);
        return key == null ? null : Registry.TRIM_MATERIAL.get(key);
    }

    private TrimPattern readTrimPattern(String raw) {
        NamespacedKey key = parseNamespacedKey(raw);
        return key == null ? null : Registry.TRIM_PATTERN.get(key);
    }

    private NamespacedKey parseNamespacedKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (!text.contains(":")) {
            return NamespacedKey.minecraft(text);
        }
        return NamespacedKey.fromString(text);
    }

    @SuppressWarnings({"deprecation", "removal"})
    private String patternTypeName(PatternType patternType) {
        String identifier = patternType.getIdentifier();
        return identifier == null || identifier.isBlank() ? patternType.name() : identifier.toUpperCase(Locale.ROOT);
    }

    private Map<String, Double> readStats(ConfigurationSection section) {
        Map<String, Double> stats = new LinkedHashMap<>();
        if (section == null) {
            return stats;
        }

        for (String key : section.getKeys(false)) {
            if (!section.isDouble(key) && !section.isInt(key)) {
                continue;
            }
            String normalized = normalizeStatName(key);
            if (!normalized.isBlank()) {
                stats.put(normalized, section.getDouble(key));
            }
        }
        return stats;
    }

    private List<GemstoneManager.SocketType> readSockets(ConfigurationSection section) {
        List<GemstoneManager.SocketType> sockets = new ArrayList<>();
        List<String> rawSockets = readStringList(section, "sockets");
        if (rawSockets.isEmpty()) {
            rawSockets = readStringList(section, "gem_sockets");
        }

        for (String rawSocket : rawSockets) {
            try {
                sockets.add(GemstoneManager.SocketType.valueOf(rawSocket.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Unknown socket type '" + rawSocket + "' in custom item '" + section.getName() + "'.");
            }
        }
        return sockets;
    }

    private String readRequirement(ConfigurationSection section, String type) {
        String direct = section.getString("req_" + type,
                section.getString(type + "_requirement", section.getString("requires_" + type, "")));
        if (direct != null && !direct.isBlank()) {
            return direct.trim();
        }

        ConfigurationSection requirements = section.getConfigurationSection("requirements");
        if (requirements == null) {
            return "";
        }
        String nested = requirements.getString(type, "");
        return nested == null ? "" : nested.trim();
    }

    private void writeRequirement(ConfigurationSection section, String key, String value) {
        if (value != null && !value.isBlank()) {
            section.set(key, value.trim());
        }
    }

    private Map<String, Integer> readEnchants(ConfigurationSection section) {
        Map<String, Integer> enchants = new LinkedHashMap<>();
        ConfigurationSection enchantsSection = section.getConfigurationSection("enchants");
        if (enchantsSection != null) {
            for (String key : enchantsSection.getKeys(false)) {
                enchants.put(normalizeItemId(key), Math.max(1, enchantsSection.getInt(key, 1)));
            }
        }

        if (enchantsSection == null) {
            for (String raw : readStringList(section, "enchants")) {
                String[] parts = raw.split(":", 2);
                if (parts.length == 0 || parts[0].isBlank()) {
                    continue;
                }

                int level = 1;
                if (parts.length == 2) {
                    try {
                        level = Math.max(1, Integer.parseInt(parts[1].trim()));
                    } catch (NumberFormatException ignored) {
                        level = 1;
                    }
                }
                enchants.put(normalizeItemId(parts[0]), level);
            }
        }
        return enchants;
    }

    private List<AbilityDefinition> readAbilities(ConfigurationSection section) {
        List<AbilityDefinition> abilities = new ArrayList<>();
        ConfigurationSection abilitiesSection = section.getConfigurationSection("abilities");
        if (abilitiesSection != null) {
            for (String key : abilitiesSection.getKeys(false)) {
                ConfigurationSection abilitySection = abilitiesSection.getConfigurationSection(key);
                if (abilitySection == null) {
                    continue;
                }
                abilities.add(readAbilityDefinition(key, abilitySection));
            }
            return abilities;
        }

        List<?> rawAbilities = section.getList("abilities");
        if (rawAbilities == null) {
            return abilities;
        }

        int inlineIndex = 0;
        for (Object rawAbility : rawAbilities) {
            inlineIndex++;
            if (rawAbility instanceof String line) {
                abilities.add(new AbilityDefinition("inline_" + inlineIndex, "PASSIVE", 0, List.of(line), Map.of()));
                continue;
            }
            if (rawAbility instanceof Map<?, ?> map) {
                abilities.add(readAbilityDefinition(map, inlineIndex));
            }
        }
        return abilities;
    }

    private AbilityDefinition readAbilityDefinition(String id, ConfigurationSection section) {
        Map<String, Object> options = new LinkedHashMap<>();
        ConfigurationSection nestedOptions = section.getConfigurationSection("options");
        if (nestedOptions != null) {
            for (String key : nestedOptions.getKeys(false)) {
                options.put(key, nestedOptions.get(key));
            }
        }
        for (String key : section.getKeys(false)) {
            if (key.equalsIgnoreCase("trigger") || key.equalsIgnoreCase("cooldown")
                    || key.equalsIgnoreCase("lore") || key.equalsIgnoreCase("options")) {
                continue;
            }
            options.put(key, section.get(key));
        }

        return new AbilityDefinition(
                normalizeItemId(id),
                normalizeTrigger(section.getString("trigger", "PASSIVE")),
                Math.max(0, section.getInt("cooldown", 0)),
                readStringList(section, "lore"),
                options
        );
    }

    private AbilityDefinition readAbilityDefinition(Map<?, ?> map, int inlineIndex) {
        String id = stringValue(map.get("id"), "inline_" + inlineIndex);
        String trigger = stringValue(map.get("trigger"), "PASSIVE");
        int cooldown = intValue(map.get("cooldown"), 0);
        List<String> lore = toStringList(map.get("lore"));
        Map<String, Object> options = new LinkedHashMap<>();
        if (map.get("options") instanceof Map<?, ?> nested) {
            for (Map.Entry<?, ?> entry : nested.entrySet()) {
                options.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.equalsIgnoreCase("id") || key.equalsIgnoreCase("trigger")
                    || key.equalsIgnoreCase("cooldown") || key.equalsIgnoreCase("lore")
                    || key.equalsIgnoreCase("options")) {
                continue;
            }
            options.put(key, entry.getValue());
        }

        return new AbilityDefinition(normalizeItemId(id), normalizeTrigger(trigger), Math.max(0, cooldown), lore, options);
    }

    private List<String> readStringList(ConfigurationSection section, String path) {
        return toStringList(section.get(path));
    }

    private List<String> toStringList(Object raw) {
        List<String> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }

        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                if (entry != null && !String.valueOf(entry).isBlank()) {
                    result.add(String.valueOf(entry).trim());
                }
            }
            return result;
        }

        String value = String.valueOf(raw).trim();
        if (!value.isBlank()) {
            result.add(value);
        }
        return result;
    }

    private String readItemId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(pdc.KEY_ITEM_ID, PersistentDataType.STRING);
    }

    private int clampAmount(Material material, int amount) {
        return Math.max(1, Math.min(Math.max(1, amount), Math.max(1, material.getMaxStackSize())));
    }

    private ItemFormatManager.Rarity parseRarity(String raw) {
        try {
            return ItemFormatManager.Rarity.valueOf(raw.toUpperCase(Locale.ROOT).trim());
        } catch (IllegalArgumentException | NullPointerException exception) {
            return ItemFormatManager.Rarity.COMMON;
        }
    }

    private String encodeEnchants(Map<String, Integer> enchants) {
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, Integer> entry : enchants.entrySet()) {
            if (!encoded.isEmpty()) encoded.append(';');
            encoded.append(normalizeItemId(entry.getKey())).append(':').append(Math.max(1, entry.getValue()));
        }
        return encoded.toString();
    }

    private String joinTextLines(List<String> lines) {
        return String.join("||", lines);
    }

    private NamespacedKey statKey(PDCManager pdc, String statName) {
        return switch (normalizeStatName(statName)) {
            case "base_damage" -> pdc.KEY_BASE_DAMAGE;
            case "base_multiplier" -> pdc.KEY_BASE_MULTIPLIER;
            case "crit_chance" -> pdc.KEY_CRIT_CHANCE;
            case "crit_damage" -> pdc.KEY_CRIT_DAMAGE;
            case "brutality" -> pdc.KEY_BRUTALITY;
            case "lifesteal" -> pdc.KEY_LIFESTEAL;
            case "armor_pen" -> pdc.KEY_ARMOR_PEN;
            case "base_armor" -> pdc.KEY_BASE_ARMOR;
            case "attack_speed_bonus" -> pdc.KEY_ATTACK_SPEED_BONUS;
            case "shield_block_threshold" -> pdc.KEY_SHIELD_BLOCK_THRESHOLD;
            case "shield_effective_block" -> pdc.KEY_SHIELD_EFFECTIVE_BLOCK;
            case "shield_cooldown_seconds" -> pdc.KEY_SHIELD_COOLDOWN_SECONDS;
            case "attr_toughness" -> pdc.KEY_ATTR_TOUGHNESS;
            case "attr_agility" -> pdc.KEY_ATTR_AGILITY;
            case "attr_intelligence" -> pdc.KEY_ATTR_INTELLIGENCE;
            case "attr_willpower" -> pdc.KEY_ATTR_WILLPOWER;
            case "attr_luck" -> pdc.KEY_ATTR_LUCK;
            case "tool_fortune" -> pdc.KEY_TOOL_FORTUNE;
            case "collection_fortune" -> pdc.KEY_COLLECTION_FORTUNE;
            case "foraging_fortune" -> pdc.KEY_FORAGING_FORTUNE;
            case "bounty" -> pdc.KEY_BOUNTY;
            case "farming_fortune" -> pdc.KEY_FARMING_FORTUNE;
            case "overbloom" -> pdc.KEY_OVERBLOOM;
            case "excavation_fortune" -> pdc.KEY_EXCAVATION_FORTUNE;
            case "mining_fortune" -> pdc.KEY_MINING_FORTUNE;
            case "tool_sweep" -> pdc.KEY_TOOL_SWEEP;
            case "collection_sweep" -> pdc.KEY_COLLECTION_SWEEP;
            case "foraging_sweep" -> pdc.KEY_FORAGING_SWEEP;
            case "farming_sweep" -> pdc.KEY_FARMING_SWEEP;
            case "excavation_sweep" -> pdc.KEY_EXCAVATION_SWEEP;
            case "tool_spread" -> pdc.KEY_TOOL_SPREAD;
            case "mining_spread" -> pdc.KEY_MINING_SPREAD;
            case "tool_mining_speed" -> pdc.KEY_TOOL_MINING_SPEED;
            case "breaking_power" -> pdc.KEY_BREAKING_POWER;
            case "purity" -> pdc.KEY_PURITY;
            case "mining_purity" -> pdc.KEY_MINING_PURITY;
            case "fishing_speed" -> pdc.KEY_FISHING_SPEED;
            case "sea_creature_chance" -> pdc.KEY_SEA_CREATURE_CHANCE;
            case "treasure_chance" -> pdc.KEY_TREASURE_CHANCE;
            default -> null;
        };
    }

    private String normalizeStatName(String raw) {
        if (raw == null) {
            return "";
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "damage", "base_damage" -> "base_damage";
            case "mult", "multiplier", "base_multiplier" -> "base_multiplier";
            case "crit", "crit_chance" -> "crit_chance";
            case "critdmg", "crit_damage" -> "crit_damage";
            case "brutality" -> "brutality";
            case "lifesteal", "life_steal", "vampirism", "vamp" -> "lifesteal";
            case "armorpen", "armor_pen" -> "armor_pen";
            case "armor", "base_armor" -> "base_armor";
            case "attackspeed", "attack_speed", "attack_speed_bonus", "aspeed" -> "attack_speed_bonus";
            case "shieldthreshold", "shield_threshold", "block_threshold", "shield_block_threshold" -> "shield_block_threshold";
            case "effectiveblock", "effective_block", "shield_effective_block" -> "shield_effective_block";
            case "shieldcooldown", "shield_cooldown", "shield_cooldown_seconds" -> "shield_cooldown_seconds";
            case "str", "strength", "attr_strength", "tou", "toughness", "attr_toughness" -> "attr_toughness";
            case "agi", "agility", "attr_agility" -> "attr_agility";
            case "int", "intelligence", "attr_intelligence" -> "attr_intelligence";
            case "wil", "will", "willpower", "attr_willpower" -> "attr_willpower";
            case "luk", "luck", "attr_luck" -> "attr_luck";
            case "toolfortune", "fortune", "tool_fortune" -> "tool_fortune";
            case "collectionfortune", "gatherfortune", "collection_fortune" -> "collection_fortune";
            case "foragingfortune", "foraging_fortune" -> "foraging_fortune";
            case "bounty", "foragingbounty", "foraging_bounty" -> "bounty";
            case "farmingfortune", "farming_fortune" -> "farming_fortune";
            case "overbloom", "over_bloom", "bloom" -> "overbloom";
            case "excavationfortune", "excavation_fortune" -> "excavation_fortune";
            case "miningfortune", "mining_fortune" -> "mining_fortune";
            case "toolsweep", "sweep", "tool_sweep" -> "tool_sweep";
            case "collectionsweep", "gathersweep", "collection_sweep" -> "collection_sweep";
            case "foragingsweep", "foraging_sweep" -> "foraging_sweep";
            case "farmingsweep", "farming_sweep" -> "farming_sweep";
            case "excavationsweep", "excavation_sweep" -> "excavation_sweep";
            case "toolspread", "spread", "tool_spread" -> "tool_spread";
            case "miningspread", "mining_spread" -> "mining_spread";
            case "miningspeed", "toolminingspeed", "tool_mining_speed" -> "tool_mining_speed";
            case "breakingpower", "bp", "breaking_power" -> "breaking_power";
            case "purity" -> "purity";
            case "miningpurity", "mining_purity" -> "mining_purity";
            case "fishingspeed", "fishing_speed" -> "fishing_speed";
            case "seacreaturechance", "sea_creature_chance" -> "sea_creature_chance";
            case "treasurechance", "treasure_chance" -> "treasure_chance";
            default -> "";
        };
    }

    private String normalizeItemId(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private boolean isValidItemId(String itemId) {
        return itemId != null && !itemId.isBlank() && itemId.matches("[a-z0-9_:\\-.]+");
    }

    private String normalizeTrigger(String raw) {
        return raw == null ? "PASSIVE" : raw.trim().toUpperCase(Locale.ROOT);
    }

    private String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value).trim();
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    @FunctionalInterface
    public interface CustomItemAbilityHandler {
        boolean execute(AbilityContext context);
    }

    public record AbilityContext(
            Player player,
            ItemStack item,
            CustomItemDefinition itemDefinition,
            AbilityDefinition ability
    ) {
    }

    public record AbilityDefinition(
            String id,
            String trigger,
            int cooldown,
            List<String> lore,
            Map<String, Object> options
    ) {
    }

    public record CustomItemDefinition(
            String id,
            Material material,
            int amount,
            String displayName,
            ItemFormatManager.Rarity rarity,
            Map<String, Double> stats,
            List<GemstoneManager.SocketType> sockets,
            Map<String, Integer> enchants,
            List<AbilityDefinition> abilities,
            List<String> abilityLore,
            List<String> storyLore,
            String accessoryType,
            boolean imprintEligible,
            String setId,
            String setPieceId,
            String talismanFamily,
            int talismanPriority,
            String reforgeId,
            String skillRequirement,
            String slayerRequirement,
            String dungeonRequirement,
            Integer customModelData,
            boolean unbreakable,
            WeaponTemplateManager.WeaponTemplate weaponTemplate,
            WeaponTemplateManager.HandRule handRule,
            ShieldAppearance shieldAppearance,
            ArmorAppearance appearance
    ) {
    }

    public record ShieldAppearance(
            DyeColor baseColor,
            List<Pattern> patterns
    ) {
    }

    public record ArmorAppearance(
            Color leatherColor,
            ArmorTrim trim
    ) {
    }

    public record SaveResult(
            boolean success,
            String itemId,
            String message
    ) {
    }
}
