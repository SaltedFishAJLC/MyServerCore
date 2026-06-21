package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GUI recipe builder and YAML-backed recipe registry.
 */
public class CustomRecipeManager implements Listener {

    private static final int INVENTORY_SIZE = 45;
    private static final int[] INPUT_SLOTS = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    private static final int RESULT_SLOT = 24;
    private static final int SAVE_SLOT = 40;
    private static final int CANCEL_SLOT = 44;
    private static final int DETAILS_BACK_SLOT = 40;
    private static final String RECIPES_ROOT = "recipes";

    private final Plugin plugin;
    private final File recipesFile;
    private final Map<String, NamespacedKey> registeredRecipeKeys = new LinkedHashMap<>();
    private final Map<String, RecipeDefinition> recipeDefinitions = new LinkedHashMap<>();
    private YamlConfiguration recipesConfig;

    public CustomRecipeManager(ServerCorePlugin plugin) {
        this.plugin = plugin;
        this.recipesFile = new File(plugin.getDataFolder(), "recipes.yml");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        loadAndRegisterRecipes();
    }

    public void openRecipeBuilder(Player player, String recipeId) {
        String normalizedId = normalizeRecipeId(recipeId);
        RecipeBuilderHolder holder = new RecipeBuilderHolder(normalizedId);
        Inventory inventory = Bukkit.createInventory(holder, INVENTORY_SIZE, Component.text("Recipe Builder: " + normalizedId));
        holder.setInventory(inventory);

        ItemStack background = namedItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            inventory.setItem(slot, background);
        }

        for (int inputSlot : INPUT_SLOTS) {
            inventory.setItem(inputSlot, null);
        }
        inventory.setItem(RESULT_SLOT, null);
        inventory.setItem(14, namedItem(Material.ARROW, "Result"));
        inventory.setItem(SAVE_SLOT, namedItem(Material.LIME_DYE, "Save recipe"));
        inventory.setItem(CANCEL_SLOT, namedItem(Material.BARRIER, "Cancel"));

        player.openInventory(inventory);
    }

    public void reloadRecipes() {
        unregisterAll();
        loadAndRegisterRecipes();
    }

    public void unregisterAll() {
        for (NamespacedKey key : registeredRecipeKeys.values()) {
            Bukkit.removeRecipe(key);
        }
        registeredRecipeKeys.clear();
        recipeDefinitions.clear();
    }

    public int getRegisteredRecipeCount() {
        return registeredRecipeKeys.size();
    }

    public void openRecipeUses(Player player, ItemStack queryItem) {
        if (player == null) return;
        if (isEmpty(queryItem)) {
            player.sendMessage(Component.text("主手拿着物品时才能检索后续配方。", NamedTextColor.YELLOW));
            return;
        }

        RecipeQuery query = RecipeQuery.fromItem(queryItem);
        openRecipeUses(player, query);
    }

    public void openRecipeUses(Player player, String rawId) {
        if (player == null) return;
        if (rawId == null || rawId.isBlank()) {
            player.sendMessage(Component.text("请输入要检索的物品 id。", NamedTextColor.YELLOW));
            return;
        }

        RecipeQuery query = RecipeQuery.fromId(rawId);
        openRecipeUses(player, query);
    }

    private void openRecipeUses(Player player, RecipeQuery query) {
        List<RecipeDefinition> matches = findRecipesUsing(query);
        if (matches.isEmpty()) {
            RecipeDefinition directRecipe = recipeDefinitions.get(normalizeRecipeId(query.label()));
            if (directRecipe != null) {
                openRecipeDetails(player, directRecipe, query);
                return;
            }

            player.sendMessage(Component.text("没有找到使用 " + query.label() + " 的自定义配方。", NamedTextColor.YELLOW));
            return;
        }

        RecipeListHolder holder = new RecipeListHolder(query);
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text("后续配方: " + query.label()));
        holder.setInventory(inventory);

        ItemStack filler = namedItem(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 45; slot < 54; slot++) {
            inventory.setItem(slot, filler);
        }

        int slot = 0;
        for (RecipeDefinition recipe : matches) {
            if (slot >= 45) break;
            ItemStack icon = recipe.result().clone();
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                if (!lore.isEmpty()) lore.add(Component.empty());
                lore.add(Component.text("配方 ID: " + recipe.id(), NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
                lore.add(Component.text("点击查看详细配方", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                meta.lore(lore);
                icon.setItemMeta(meta);
            }
            inventory.setItem(slot, icon);
            holder.bind(slot, recipe.id());
            slot++;
        }

        player.openInventory(inventory);
    }

    private void openRecipeDetails(Player player, RecipeDefinition recipe, RecipeQuery returnQuery) {
        RecipeDetailHolder holder = new RecipeDetailHolder(recipe.id(), returnQuery);
        Inventory inventory = Bukkit.createInventory(holder, INVENTORY_SIZE, Component.text("配方: " + recipe.id()));
        holder.setInventory(inventory);

        ItemStack background = namedItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            inventory.setItem(slot, background);
        }

        ItemStack[] items = recipe.items();
        for (int index = 0; index < INPUT_SLOTS.length; index++) {
            ItemStack ingredient = items[index];
            inventory.setItem(INPUT_SLOTS[index], isEmpty(ingredient) ? null : ingredient.clone());
        }
        inventory.setItem(14, namedItem(Material.ARROW, "Result"));
        inventory.setItem(RESULT_SLOT, isEmpty(items[9]) ? null : items[9].clone());
        inventory.setItem(DETAILS_BACK_SLOT, namedItem(Material.ARROW, "返回"));

        player.openInventory(inventory);
    }

    public static boolean isValidRecipeId(String recipeId) {
        return recipeId != null && recipeId.matches("[A-Za-z0-9_-]{1,64}");
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryHolder inventoryHolder = event.getInventory().getHolder();
        if (inventoryHolder instanceof RecipeListHolder holder) {
            handleRecipeListClick(event, holder);
            return;
        }
        if (inventoryHolder instanceof RecipeDetailHolder holder) {
            handleRecipeDetailClick(event, holder);
            return;
        }
        if (!(inventoryHolder instanceof RecipeBuilderHolder holder)) {
            return;
        }

        if (!(event.getWhoClicked() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }

        int rawSlot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();

        if (rawSlot >= topSize) {
            if (event.getClick().isShiftClick()) {
                event.setCancelled(true);
                player.sendMessage(Component.text("Place recipe items into the grid manually."));
            }
            return;
        }

        if (isEditableSlot(rawSlot)) {
            return;
        }

        event.setCancelled(true);
        if (rawSlot == SAVE_SLOT) {
            if (saveRecipe(holder.recipeId(), event.getView().getTopInventory(), player)) {
                player.closeInventory();
            }
            return;
        }

        if (rawSlot == CANCEL_SLOT) {
            player.closeInventory();
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof RecipeListHolder || holder instanceof RecipeDetailHolder) {
            int topSize = event.getView().getTopInventory().getSize();
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot < topSize) {
                    event.setCancelled(true);
                    return;
                }
            }
            return;
        }

        if (!(event.getInventory().getHolder() instanceof RecipeBuilderHolder)) {
            return;
        }

        int topSize = event.getView().getTopInventory().getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize && !isEditableSlot(rawSlot)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        ItemStack[] matrix = event.getInventory().getMatrix();
        RecipeDefinition matched = findMatchingRecipe(matrix);
        if (matched == null) {
            if (event.getRecipe() instanceof Keyed keyed && registeredRecipeKeys.containsValue(keyed.getKey())) {
                event.getInventory().setResult(null);
            }
            return;
        }

        ItemStack result = createCraftResult(matched, matrix);
        event.getInventory().setResult(result);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof RecipeBuilderHolder)) {
            return;
        }

        Player player = (Player) event.getPlayer();
        returnEditorItems(player, event.getInventory());
    }

    private void handleRecipeListClick(InventoryClickEvent event, RecipeListHolder holder) {
        int rawSlot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        if (rawSlot < topSize) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }

            String recipeId = holder.recipeAt(rawSlot);
            RecipeDefinition recipe = recipeId == null ? null : recipeDefinitions.get(recipeId);
            if (recipe != null) {
                openRecipeDetails(player, recipe, holder.query());
            }
        }
    }

    private void handleRecipeDetailClick(InventoryClickEvent event, RecipeDetailHolder holder) {
        int rawSlot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        if (rawSlot < topSize) {
            event.setCancelled(true);
            if (rawSlot == DETAILS_BACK_SLOT && event.getWhoClicked() instanceof Player player) {
                if (holder.returnQuery() != null) {
                    openRecipeUses(player, holder.returnQuery());
                } else {
                    player.closeInventory();
                }
            }
        }
    }

    private void loadAndRegisterRecipes() {
        ensureRecipeFile();
        recipesConfig = YamlConfiguration.loadConfiguration(recipesFile);

        ConfigurationSection root = recipesConfig.getConfigurationSection(RECIPES_ROOT);
        if (root == null) {
            plugin.getLogger().info("Loaded 0 custom recipe(s).");
            return;
        }

        int loaded = 0;
        for (String recipeId : root.getKeys(false)) {
            ConfigurationSection recipeSection = root.getConfigurationSection(recipeId);
            if (recipeSection == null) {
                continue;
            }

            try {
                ItemStack[] items = readRecipeItems(recipeId, recipeSection);
                if (registerRecipe(recipeId, items)) {
                    loaded++;
                }
            } catch (IOException | ClassNotFoundException | IllegalArgumentException exception) {
                plugin.getLogger().warning("Could not load recipe '" + recipeId + "': " + exception.getMessage());
            }
        }

        plugin.getLogger().info("Loaded " + loaded + " custom recipe(s).");
    }

    private boolean saveRecipe(String recipeId, Inventory inventory, Player player) {
        ItemStack[] recipeItems = collectRecipeItems(inventory);
        if (isEmpty(recipeItems[9])) {
            player.sendMessage(Component.text("Set a result item before saving this recipe."));
            return false;
        }

        boolean hasIngredient = false;
        for (int index = 0; index < 9; index++) {
            if (!isEmpty(recipeItems[index])) {
                hasIngredient = true;
                break;
            }
        }

        if (!hasIngredient) {
            player.sendMessage(Component.text("Set at least one ingredient before saving this recipe."));
            return false;
        }

        try {
            recipesConfig.set(RECIPES_ROOT + "." + recipeId + ".items", serializeItems(recipeItems));
            recipesConfig.set(RECIPES_ROOT + "." + recipeId + ".updated_by", player.getUniqueId().toString());
            recipesConfig.set(RECIPES_ROOT + "." + recipeId + ".updated_at", System.currentTimeMillis());
            recipesConfig.save(recipesFile);
        } catch (IOException exception) {
            player.sendMessage(Component.text("Could not save recipe: " + exception.getMessage()));
            return false;
        }

        if (!registerRecipe(recipeId, recipeItems)) {
            player.sendMessage(Component.text("Recipe saved, but Bukkit rejected the shaped recipe."));
            return false;
        }

        player.sendMessage(Component.text("Saved and registered recipe '" + recipeId + "'."));
        return true;
    }

    private boolean registerRecipe(String recipeId, ItemStack[] items) {
        if (items == null || items.length < 10 || isEmpty(items[9])) {
            return false;
        }

        NamespacedKey key = new NamespacedKey(plugin, "custom_recipe_" + normalizeRecipeId(recipeId).toLowerCase(Locale.ROOT));
        String normalizedRecipeId = normalizeRecipeId(recipeId);
        Bukkit.removeRecipe(key);
        registeredRecipeKeys.remove(normalizedRecipeId);
        recipeDefinitions.remove(normalizedRecipeId);

        ShapedRecipe recipe = new ShapedRecipe(key, items[9].clone());
        String[] shape = new String[3];
        Map<Character, RecipeChoice> choices = new LinkedHashMap<>();
        char ingredientKey = 'A';

        for (int row = 0; row < 3; row++) {
            StringBuilder rowShape = new StringBuilder();
            for (int column = 0; column < 3; column++) {
                int index = row * 3 + column;
                ItemStack ingredient = items[index];
                if (isEmpty(ingredient)) {
                    rowShape.append(' ');
                    continue;
                }

                rowShape.append(ingredientKey);
                ItemStack choiceItem = ingredient.clone();
                choiceItem.setAmount(1);
                choices.put(ingredientKey, createRecipeChoice(choiceItem));
                ingredientKey++;
            }
            shape[row] = rowShape.toString();
        }

        recipe.shape(shape);
        for (Map.Entry<Character, RecipeChoice> entry : choices.entrySet()) {
            recipe.setIngredient(entry.getKey(), entry.getValue());
        }
        boolean added = Bukkit.addRecipe(recipe);
        if (added) {
            ItemStack[] storedItems = copyRecipeItems(items);
            registeredRecipeKeys.put(normalizedRecipeId, key);
            recipeDefinitions.put(normalizedRecipeId, new RecipeDefinition(normalizedRecipeId, key, storedItems));
        }
        return added;
    }

    private RecipeChoice createRecipeChoice(ItemStack ingredient) {
        if (getItemId(ingredient) != null) {
            return new RecipeChoice.MaterialChoice(ingredient.getType());
        }
        return new RecipeChoice.ExactChoice(ingredient);
    }

    private RecipeDefinition findMatchingRecipe(ItemStack[] matrix) {
        if (matrix == null) {
            return null;
        }

        for (RecipeDefinition recipe : recipeDefinitions.values()) {
            if (recipeMatches(recipe, matrix)) {
                return recipe;
            }
        }
        return null;
    }

    private boolean recipeMatches(RecipeDefinition recipe, ItemStack[] matrix) {
        ItemStack[] expectedItems = recipe.items();
        for (int index = 0; index < 9; index++) {
            ItemStack expected = expectedItems[index];
            ItemStack actual = index < matrix.length ? matrix[index] : null;
            if (!ingredientMatches(expected, actual)) {
                return false;
            }
        }
        return true;
    }

    private boolean ingredientMatches(ItemStack expected, ItemStack actual) {
        if (isEmpty(expected)) {
            return isEmpty(actual);
        }
        if (isEmpty(actual)) {
            return false;
        }

        String expectedItemId = getItemId(expected);
        if (expectedItemId != null) {
            String actualItemId = getOrInferItemId(actual);
            return normalizeItemId(expectedItemId).equals(normalizeItemId(actualItemId));
        }

        ItemStack expectedCopy = expected.clone();
        ItemStack actualCopy = actual.clone();
        expectedCopy.setAmount(1);
        actualCopy.setAmount(1);
        return expectedCopy.isSimilar(actualCopy);
    }

    private ItemStack createCraftResult(RecipeDefinition recipe, ItemStack[] matrix) {
        ItemStack result = recipe.result().clone();
        ItemStack carrier = findUpgradeCarrier(matrix);
        if (carrier != null && getItemId(result) != null) {
            transferGrowthState(carrier, result);
        }

        ItemFormatManager formatManager = ItemFormatManager.getInstance();
        if (formatManager != null) {
            formatManager.formatItem(result, true);
        }
        return result;
    }

    private ItemStack findUpgradeCarrier(ItemStack[] matrix) {
        ItemStack carrier = null;
        for (ItemStack item : matrix) {
            if (isEmpty(item) || !isUpgradeableEquipment(item.getType()) || getItemId(item) == null) {
                continue;
            }

            if (carrier != null) {
                return null;
            }
            carrier = item;
        }
        return carrier;
    }

    private void transferGrowthState(ItemStack source, ItemStack target) {
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null || source == null || target == null || !source.hasItemMeta()) {
            return;
        }

        copyVanillaEnchantments(source, target);
        ItemMeta sourceMeta = source.getItemMeta();
        ItemMeta targetMeta = target.getItemMeta();
        if (sourceMeta == null || targetMeta == null) {
            return;
        }

        PersistentDataContainer sourcePdc = sourceMeta.getPersistentDataContainer();
        PersistentDataContainer targetPdc = targetMeta.getPersistentDataContainer();

        copyStringPdc(sourcePdc, targetPdc, pdc.KEY_ITEM_CUSTOM_ENCHANTS);
        mergeGemSockets(sourcePdc, targetPdc, pdc);
        target.setItemMeta(targetMeta);

        String reforgeId = sourcePdc.get(pdc.KEY_ITEM_REFORGE_ID, PersistentDataType.STRING);
        if (reforgeId != null && !reforgeId.isBlank()) {
            ReforgeManager reforgeManager = ReforgeManager.getInstance();
            if (reforgeManager != null) {
                reforgeManager.applyReforge(target, reforgeId);
            }
        }
    }

    private void copyVanillaEnchantments(ItemStack source, ItemStack target) {
        for (Map.Entry<org.bukkit.enchantments.Enchantment, Integer> enchantment : source.getEnchantments().entrySet()) {
            target.addUnsafeEnchantment(enchantment.getKey(), enchantment.getValue());
        }
    }

    private void copyStringPdc(PersistentDataContainer source, PersistentDataContainer target, NamespacedKey key) {
        String value = source.get(key, PersistentDataType.STRING);
        if (value == null || value.isBlank()) {
            return;
        }
        target.set(key, PersistentDataType.STRING, value);
    }

    private void mergeGemSockets(PersistentDataContainer source, PersistentDataContainer target, PDCManager pdc) {
        List<String> sourceSocketTypes = splitList(source.get(pdc.KEY_ITEM_SOCKET_TYPES, PersistentDataType.STRING));
        List<String> sourceSocketGems = splitList(source.get(pdc.KEY_ITEM_SOCKET_GEMS, PersistentDataType.STRING));
        if (sourceSocketTypes.isEmpty()) {
            return;
        }

        List<String> targetSocketTypes = splitList(target.get(pdc.KEY_ITEM_SOCKET_TYPES, PersistentDataType.STRING));
        List<String> targetSocketGems = splitList(target.get(pdc.KEY_ITEM_SOCKET_GEMS, PersistentDataType.STRING));
        int mergedSize = Math.max(sourceSocketTypes.size(), targetSocketTypes.size());
        List<String> mergedSocketTypes = new ArrayList<>();
        List<String> mergedSocketGems = new ArrayList<>();
        List<String> inheritedGems = new ArrayList<>();

        for (int index = 0; index < mergedSize; index++) {
            String socketType = index < sourceSocketTypes.size()
                    ? sourceSocketTypes.get(index)
                    : targetSocketTypes.get(index);
            boolean inheritedFromSource = index < sourceSocketGems.size();
            String socketGem = inheritedFromSource
                    ? sourceSocketGems.get(index)
                    : index < targetSocketGems.size() ? targetSocketGems.get(index) : "EMPTY";
            mergedSocketTypes.add(socketType);
            String normalizedGem = socketGem == null || socketGem.isBlank() ? "EMPTY" : socketGem;
            mergedSocketGems.add(normalizedGem);
            if (inheritedFromSource) {
                inheritedGems.add(normalizedGem);
            }
        }

        target.set(pdc.KEY_ITEM_SOCKET_TYPES, PersistentDataType.STRING, String.join(",", mergedSocketTypes));
        target.set(pdc.KEY_ITEM_SOCKET_GEMS, PersistentDataType.STRING, String.join(",", mergedSocketGems));
        applyGemStats(target, pdc, inheritedGems);
    }

    private void applyGemStats(PersistentDataContainer target, PDCManager pdc, List<String> socketGems) {
        GemstoneManager gemstoneManager = GemstoneManager.getInstance();
        if (gemstoneManager == null) {
            return;
        }

        for (String gemId : socketGems) {
            if (gemId == null || gemId.isBlank() || gemId.equalsIgnoreCase("EMPTY")) {
                continue;
            }

            GemstoneManager.GemstoneDefinition gemstone = gemstoneManager.getGemstone(gemId);
            if (gemstone == null) {
                continue;
            }
            for (Map.Entry<String, Double> stat : gemstone.stats().entrySet()) {
                addStat(target, pdc, stat.getKey(), stat.getValue());
            }
        }
    }

    private void addStat(PersistentDataContainer target, PDCManager pdc, String statName, double delta) {
        NamespacedKey key = statKey(pdc, statName);
        if (key == null || Math.abs(delta) < 0.0001) {
            return;
        }
        double current = target.getOrDefault(key, PersistentDataType.DOUBLE, 0.0);
        target.set(key, PersistentDataType.DOUBLE, current + delta);
    }

    private NamespacedKey statKey(PDCManager pdc, String statName) {
        return switch (statName) {
            case "base_damage" -> pdc.KEY_BASE_DAMAGE;
            case "base_multiplier" -> pdc.KEY_BASE_MULTIPLIER;
            case "crit_chance" -> pdc.KEY_CRIT_CHANCE;
            case "crit_damage" -> pdc.KEY_CRIT_DAMAGE;
            case "brutality" -> pdc.KEY_BRUTALITY;
            case "lifesteal" -> pdc.KEY_LIFESTEAL;
            case "armor_pen" -> pdc.KEY_ARMOR_PEN;
            case "base_armor" -> pdc.KEY_BASE_ARMOR;
            case "max_health" -> pdc.KEY_MAX_HEALTH;
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
            case "farming_fortune" -> pdc.KEY_FARMING_FORTUNE;
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
            case "bounty" -> pdc.KEY_BOUNTY;
            case "overbloom" -> pdc.KEY_OVERBLOOM;
            default -> null;
        };
    }

    private List<RecipeDefinition> findRecipesUsing(RecipeQuery query) {
        List<RecipeDefinition> matches = new ArrayList<>();
        for (RecipeDefinition recipe : recipeDefinitions.values()) {
            if (recipeUsesQuery(recipe, query)) {
                matches.add(recipe);
            }
        }
        return matches;
    }

    private boolean recipeUsesQuery(RecipeDefinition recipe, RecipeQuery query) {
        for (int index = 0; index < 9; index++) {
            ItemStack ingredient = recipe.items()[index];
            if (isEmpty(ingredient)) {
                continue;
            }
            if (query.matches(ingredient)) {
                return true;
            }
        }
        return false;
    }

    private ItemStack[] collectRecipeItems(Inventory inventory) {
        ItemStack[] items = new ItemStack[10];
        for (int index = 0; index < INPUT_SLOTS.length; index++) {
            ItemStack item = inventory.getItem(INPUT_SLOTS[index]);
            if (!isEmpty(item)) {
                ItemStack copy = item.clone();
                copy.setAmount(1);
                items[index] = copy;
            }
        }

        ItemStack result = inventory.getItem(RESULT_SLOT);
        if (!isEmpty(result)) {
            items[9] = result.clone();
        }
        return items;
    }

    private void returnEditorItems(Player player, Inventory inventory) {
        for (int inputSlot : INPUT_SLOTS) {
            returnItem(player, inventory.getItem(inputSlot));
            inventory.setItem(inputSlot, null);
        }
        returnItem(player, inventory.getItem(RESULT_SLOT));
        inventory.setItem(RESULT_SLOT, null);
        player.updateInventory();
    }

    private void returnItem(Player player, ItemStack item) {
        if (isEmpty(item)) {
            return;
        }

        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item.clone());
        for (ItemStack leftover : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private void ensureRecipeFile() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder for recipes.yml.");
            return;
        }

        if (recipesFile.exists()) {
            return;
        }

        try (java.io.InputStream stream = plugin.getResource("recipes.yml")) {
            if (stream != null) {
                plugin.saveResource("recipes.yml", false);
                return;
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Unable to check bundled recipes.yml: " + exception.getMessage());
        }

        try {
            YamlConfiguration config = new YamlConfiguration();
            config.createSection(RECIPES_ROOT);
            config.save(recipesFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not create recipes.yml: " + exception.getMessage());
        }
    }

    private ItemStack[] readRecipeItems(String recipeId, ConfigurationSection section) throws IOException, ClassNotFoundException {
        String encoded = section.getString("items");
        if (encoded != null && !encoded.isBlank()) {
            return deserializeItems(encoded);
        }
        return readYamlRecipeItems(recipeId, section);
    }

    private ItemStack[] readYamlRecipeItems(String recipeId, ConfigurationSection section) {
        List<String> shape = section.getStringList("shape");
        if (shape.size() != 3) {
            throw new IllegalArgumentException("YAML recipe requires exactly 3 shape rows.");
        }

        ConfigurationSection ingredients = section.getConfigurationSection("ingredients");
        ConfigurationSection resultSection = section.getConfigurationSection("result");
        if (ingredients == null || resultSection == null) {
            throw new IllegalArgumentException("YAML recipe requires ingredients and result sections.");
        }

        ItemStack[] items = new ItemStack[10];
        for (int row = 0; row < 3; row++) {
            String rowText = shape.get(row);
            if (rowText.length() != 3) {
                throw new IllegalArgumentException("YAML recipe shape rows must be 3 characters long.");
            }
            for (int column = 0; column < 3; column++) {
                char symbol = rowText.charAt(column);
                if (symbol == ' ') {
                    continue;
                }
                ConfigurationSection ingredient = ingredients.getConfigurationSection(String.valueOf(symbol));
                if (ingredient == null) {
                    throw new IllegalArgumentException("Missing ingredient for symbol '" + symbol + "'.");
                }
                items[row * 3 + column] = readYamlRecipeItem(recipeId, ingredient, true);
            }
        }

        items[9] = readYamlRecipeItem(recipeId, resultSection, false);
        if (isEmpty(items[9])) {
            throw new IllegalArgumentException("Recipe result is empty.");
        }
        return items;
    }

    private ItemStack readYamlRecipeItem(String recipeId, ConfigurationSection section, boolean ingredient) {
        String customItemId = firstPresentString(section, "custom_item", "custom_item_id", "item_id", "item");
        int amount = Math.max(1, section.getInt("amount", 1));
        if (customItemId != null && !customItemId.isBlank()) {
            CustomItemRegistry registry = CustomItemRegistry.getInstance();
            ItemStack custom = registry == null ? null : registry.createItem(customItemId, amount);
            if (custom != null) {
                if (ingredient) {
                    custom.setAmount(1);
                }
                return custom;
            }
            plugin.getLogger().warning("Recipe '" + recipeId + "' references unknown custom item '" + customItemId + "'.");
        }

        String materialName = firstPresentString(section, "material", "fallback_material");
        Material material = materialName == null ? null : Material.matchMaterial(materialName);
        if (material == null || material.isAir()) {
            throw new IllegalArgumentException("Invalid recipe material in '" + recipeId + "'.");
        }
        return new ItemStack(material, ingredient ? 1 : Math.min(amount, material.getMaxStackSize()));
    }

    private String firstPresentString(ConfigurationSection section, String... keys) {
        for (String key : keys) {
            String value = section.getString(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String serializeItems(ItemStack[] items) throws IOException {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream output = new BukkitObjectOutputStream(byteStream)) {
            output.writeInt(items.length);
            for (ItemStack item : items) {
                output.writeObject(item);
            }
        }
        return Base64.getEncoder().encodeToString(byteStream.toByteArray());
    }

    private ItemStack[] deserializeItems(String encoded) throws IOException, ClassNotFoundException {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        try (BukkitObjectInputStream input = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
            int length = input.readInt();
            ItemStack[] items = new ItemStack[length];
            for (int index = 0; index < length; index++) {
                Object item = input.readObject();
                items[index] = item instanceof ItemStack stack ? stack : null;
            }
            return items;
        }
    }

    private ItemStack namedItem(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name));
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isEditableSlot(int rawSlot) {
        return rawSlot == RESULT_SLOT || Arrays.stream(INPUT_SLOTS).anyMatch(slot -> slot == rawSlot);
    }

    private boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    private String normalizeRecipeId(String recipeId) {
        return recipeId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    private static String getItemId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return null;
        }
        String itemId = item.getItemMeta().getPersistentDataContainer().get(pdc.KEY_ITEM_ID, PersistentDataType.STRING);
        return itemId == null || itemId.isBlank() ? null : itemId;
    }

    private static String getOrInferItemId(ItemStack item) {
        String itemId = getItemId(item);
        if (itemId != null) {
            return itemId;
        }
        return item == null || item.getType().isAir() ? "" : "vanilla_" + item.getType().name().toLowerCase(Locale.ROOT);
    }

    private static String normalizeItemId(String itemId) {
        return itemId == null ? "" : itemId.trim().toLowerCase(Locale.ROOT);
    }

    private ItemStack[] copyRecipeItems(ItemStack[] items) {
        ItemStack[] copy = new ItemStack[Math.max(10, items.length)];
        for (int index = 0; index < copy.length; index++) {
            ItemStack item = index < items.length ? items[index] : null;
            copy[index] = isEmpty(item) ? null : item.clone();
        }
        return copy;
    }

    private List<String> splitList(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                result.add(part.trim());
            }
        }
        return result;
    }

    private boolean isUpgradeableEquipment(Material material) {
        String name = material.name();
        return name.endsWith("_SWORD")
                || name.endsWith("_AXE")
                || name.endsWith("_PICKAXE")
                || name.endsWith("_SHOVEL")
                || name.endsWith("_HOE")
                || name.endsWith("_HELMET")
                || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS")
                || material == Material.BOW
                || material == Material.CROSSBOW
                || material == Material.TRIDENT
                || material == Material.SHIELD
                || material == Material.MACE
                || material == Material.FISHING_ROD
                || material == Material.SHEARS
                || material == Material.FLINT_AND_STEEL;
    }

    private record RecipeDefinition(String id, NamespacedKey key, ItemStack[] items) {
        private ItemStack result() {
            return items[9];
        }
    }

    private record RecipeQuery(String itemId, Material material, ItemStack exactItem, String label) {
        private static RecipeQuery fromItem(ItemStack item) {
            String itemId = getItemId(item);
            if (itemId != null) {
                return new RecipeQuery(normalizeItemId(itemId), item.getType(), null, itemId);
            }

            ItemStack exact = item.clone();
            exact.setAmount(1);
            return new RecipeQuery(null, item.getType(), exact, item.getType().name());
        }

        private static RecipeQuery fromId(String rawId) {
            String normalized = normalizeItemId(rawId);
            Material material = Material.matchMaterial(rawId);
            return new RecipeQuery(normalized, material, null, rawId.trim());
        }

        private boolean matches(ItemStack ingredient) {
            String ingredientItemId = getItemId(ingredient);
            if (itemId != null && !itemId.isBlank()) {
                if (ingredientItemId != null && normalizeItemId(ingredientItemId).equals(itemId)) {
                    return true;
                }
                if (ingredientItemId == null && normalizeItemId("vanilla_" + ingredient.getType().name().toLowerCase(Locale.ROOT)).equals(itemId)) {
                    return true;
                }
            }

            if (material != null && ingredient.getType() == material) {
                return true;
            }

            if (exactItem != null && ingredientItemId == null) {
                ItemStack ingredientCopy = ingredient.clone();
                ingredientCopy.setAmount(1);
                return exactItem.isSimilar(ingredientCopy);
            }
            return false;
        }
    }

    private static final class RecipeBuilderHolder implements InventoryHolder {
        private final String recipeId;
        private Inventory inventory;

        private RecipeBuilderHolder(String recipeId) {
            this.recipeId = recipeId;
        }

        private String recipeId() {
            return recipeId;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class RecipeListHolder implements InventoryHolder {
        private final RecipeQuery query;
        private final Map<Integer, String> recipesBySlot = new HashMap<>();
        private Inventory inventory;

        private RecipeListHolder(RecipeQuery query) {
            this.query = query;
        }

        private RecipeQuery query() {
            return query;
        }

        private void bind(int slot, String recipeId) {
            recipesBySlot.put(slot, recipeId);
        }

        private String recipeAt(int slot) {
            return recipesBySlot.get(slot);
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class RecipeDetailHolder implements InventoryHolder {
        private final String recipeId;
        private final RecipeQuery returnQuery;
        private Inventory inventory;

        private RecipeDetailHolder(String recipeId, RecipeQuery returnQuery) {
            this.recipeId = recipeId;
            this.returnQuery = returnQuery;
        }

        private RecipeQuery returnQuery() {
            return returnQuery;
        }

        private void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
