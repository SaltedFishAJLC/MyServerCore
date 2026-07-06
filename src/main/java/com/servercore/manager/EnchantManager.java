package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import com.servercore.enchant.EnchantApplyResult;
import com.servercore.enchant.EnchantDefinition;
import com.servercore.enchant.EnchantRarity;
import com.servercore.enchant.EnchantRegistry;
import com.servercore.enchant.EnchantSlotMatcher;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Custom enchant PDC reader/writer. Runtime definitions live in EnchantRegistry.
 */
public class EnchantManager {

    private static EnchantManager instance;

    public EnchantManager(ServerCorePlugin plugin) {
        instance = this;
    }

    public static EnchantManager getInstance() {
        return instance;
    }

    public EnchantApplyResult addCustomEnchantChecked(ItemStack item, String enchantId, int level) {
        EnchantApplyResult result = canApply(item, enchantId, level);
        if (!result.success()) {
            return result;
        }
        addCustomEnchantRaw(item, enchantId, level);
        return result;
    }

    public void addCustomEnchant(ItemStack item, String enchantId, int level) {
        addCustomEnchantChecked(item, enchantId, level);
    }

    public EnchantApplyResult addCustomEnchantAdmin(ItemStack item, String enchantId, int level) {
        EnchantApplyResult result = canApplyAdmin(item, enchantId, level);
        if (!result.success()) {
            return result;
        }

        String normalizedId = normalize(enchantId);
        EnchantRegistry registry = EnchantRegistry.getInstance();
        EnchantDefinition definition = registry == null ? null : registry.get(normalizedId).orElse(null);
        if (definition == null) {
            return EnchantApplyResult.fail("未知附魔: " + normalizedId);
        }

        Map<String, Integer> enchants = getAllCustomEnchants(item);
        enchants.put(definition.id(), Math.max(1, Math.min(level, definition.maxLevel())));
        writeEnchants(item, enchants);
        refreshFormat(item);
        return result;
    }

    public EnchantApplyResult canApplyAdmin(ItemStack item, String enchantId, int level) {
        if (item == null || item.getType().isAir()) {
            return EnchantApplyResult.fail("请将要附魔的物品放入界面。");
        }
        String normalizedId = normalize(enchantId);
        EnchantRegistry registry = EnchantRegistry.getInstance();
        if (registry == null) {
            return EnchantApplyResult.fail("附魔注册表尚未加载。");
        }

        EnchantDefinition definition = registry.get(normalizedId).orElse(null);
        if (definition == null) {
            return EnchantApplyResult.fail("未知附魔: " + normalizedId);
        }
        if (!definition.enabled()) {
            return EnchantApplyResult.fail("该附魔当前已禁用。");
        }
        if (level < 1 || level > definition.maxLevel()) {
            return EnchantApplyResult.fail("等级必须在 1-" + definition.maxLevel() + " 之间。");
        }
        if (!isBook(item) && !EnchantSlotMatcher.matches(item, definition.slots())) {
            return EnchantApplyResult.fail("该附魔不能应用到这件物品。");
        }
        return EnchantApplyResult.ok();
    }

    public EnchantApplyResult canApplyFromEnchantTable(ItemStack item, String enchantId, int rolledLevel) {
        EnchantTableMerge merge = resolveEnchantTableMerge(item, enchantId, rolledLevel);
        return merge.result();
    }

    public EnchantApplyResult addFromEnchantTable(ItemStack item, String enchantId, int rolledLevel) {
        EnchantTableMerge merge = resolveEnchantTableMerge(item, enchantId, rolledLevel);
        if (!merge.result().success()) {
            return merge.result();
        }
        Map<String, Integer> enchants = getAllCustomEnchants(item);
        enchants.put(merge.definition().id(), merge.nextLevel());
        writeEnchants(item, enchants);
        refreshFormat(item);
        return merge.result();
    }

    public void addCustomEnchantRaw(ItemStack item, String enchantId, int level) {
        if (item == null || item.getType().isAir() || enchantId == null) return;
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) return;

        String normalizedId = normalize(enchantId);
        int cappedLevel = Math.max(1, level);
        EnchantRegistry registry = EnchantRegistry.getInstance();
        EnchantDefinition definition = registry == null ? null : registry.get(normalizedId).orElse(null);
        if (definition != null) {
            cappedLevel = Math.min(cappedLevel, definition.maxLevel());
        }

        Map<String, Integer> enchants = getAllCustomEnchants(item);
        if (normalizedId.equals("one_for_all")) {
            enchants.clear();
        }
        enchants.put(normalizedId, cappedLevel);
        writeEnchants(item, enchants);
        refreshFormat(item);
    }

    public EnchantApplyResult canApply(ItemStack item, String enchantId, int level) {
        if (item == null || item.getType().isAir()) {
            return EnchantApplyResult.fail("请将要附魔的物品拿在手上。");
        }
        String normalizedId = normalize(enchantId);
        EnchantRegistry registry = EnchantRegistry.getInstance();
        if (registry == null) {
            return EnchantApplyResult.fail("附魔注册表尚未加载。");
        }

        EnchantDefinition definition = registry.get(normalizedId).orElse(null);
        if (definition == null) {
            return EnchantApplyResult.fail("未知附魔: " + normalizedId);
        }
        if (!definition.enabled()) {
            return EnchantApplyResult.fail("该附魔当前已禁用。");
        }
        if (level < 1 || level > definition.maxLevel()) {
            return EnchantApplyResult.fail("等级必须在 1-" + definition.maxLevel() + " 之间。");
        }
        if (!isBook(item) && !EnchantSlotMatcher.matches(item, definition.slots())) {
            return EnchantApplyResult.fail("该附魔不能应用到这件物品。");
        }

        Map<String, Integer> current = getAllCustomEnchants(item);
        if (!normalizedId.equals("one_for_all") && current.containsKey("one_for_all")) {
            return EnchantApplyResult.fail("一件带有以一镇万的武器不能再追加其他附魔。");
        }
        if (!normalizedId.equals("one_for_all") && conflictsWithExisting(definition, current)) {
            return EnchantApplyResult.fail("该附魔与已有附魔冲突。");
        }
        if (!normalizedId.equals("one_for_all") && definition.isUltimate() && exceedsUltimateLimit(definition, current, registry)) {
            return EnchantApplyResult.fail("一件装备只能拥有 " + registry.settings().ultimateLimitPerItem() + " 个终极附魔。");
        }
        return EnchantApplyResult.ok();
    }

    private EnchantTableMerge resolveEnchantTableMerge(ItemStack item, String enchantId, int rolledLevel) {
        if (item == null || item.getType().isAir()) {
            return EnchantTableMerge.fail("请将要附魔的物品放入附魔台。");
        }
        String normalizedId = normalize(enchantId);
        EnchantRegistry registry = EnchantRegistry.getInstance();
        if (registry == null) {
            return EnchantTableMerge.fail("附魔注册表尚未加载。");
        }

        EnchantDefinition definition = registry.get(normalizedId).orElse(null);
        if (definition == null) {
            return EnchantTableMerge.fail("未知附魔: " + normalizedId);
        }
        if (!definition.enabled()) {
            return EnchantTableMerge.fail("该附魔当前已禁用。");
        }
        if (!definition.obtainableFromEnchantTable()) {
            return EnchantTableMerge.fail("该附魔不能从普通附魔台获得。");
        }
        if (!isBook(item) && !EnchantSlotMatcher.matches(item, definition.slots())) {
            return EnchantTableMerge.fail("该附魔不能应用到这件物品。");
        }

        int rolled = Math.max(1, Math.min(rolledLevel, definition.tableMaxLevel()));
        int existing = getCustomEnchantLevel(item, definition.id());
        int next = existing <= 0
                ? rolled
                : existing == rolled ? existing + 1 : Math.max(existing, rolled);
        next = Math.min(next, definition.tableMaxLevel());

        if (existing >= definition.tableMaxLevel()) {
            return EnchantTableMerge.fail(definition.display() + " 已达到附魔台上限 " + definition.tableMaxLevel() + "。");
        }
        if (next <= existing) {
            return EnchantTableMerge.fail("本次附魔不会提升 " + definition.display() + "。");
        }

        EnchantApplyResult validation = canApply(item, definition.id(), next);
        if (!validation.success()) {
            return EnchantTableMerge.fail(validation.message());
        }
        return new EnchantTableMerge(EnchantApplyResult.ok(), definition, next);
    }

    public void removeCustomEnchant(ItemStack item, String enchantId) {
        if (item == null || item.getType().isAir() || enchantId == null) return;
        Map<String, Integer> enchants = getAllCustomEnchants(item);
        if (enchants.remove(normalize(enchantId)) != null) {
            writeEnchants(item, enchants);
            refreshFormat(item);
        }
    }

    public void clearCustomEnchants(ItemStack item) {
        if (item == null || item.getType().isAir()) return;
        writeEnchants(item, Map.of());
        refreshFormat(item);
    }

    public boolean hasCustomEnchant(ItemStack item, String enchantId) {
        return getCustomEnchantLevel(item, enchantId) > 0;
    }

    public int getCustomEnchantLevel(ItemStack item, String enchantId) {
        if (item == null || !item.hasItemMeta() || enchantId == null) return 0;
        return getAllCustomEnchants(item).getOrDefault(normalize(enchantId), 0);
    }

    public int getActiveEnchantLevel(ItemStack item, String enchantId) {
        int rawLevel = getCustomEnchantLevel(item, enchantId);
        if (rawLevel <= 0) return 0;

        EnchantRegistry registry = EnchantRegistry.getInstance();
        if (registry == null || !registry.isEnabled(enchantId)) {
            return 0;
        }

        EnchantDefinition definition = registry.get(enchantId).orElse(null);
        if (definition == null) return 0;
        return Math.min(rawLevel, definition.maxLevel());
    }

    public Map<String, Integer> getAllCustomEnchants(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return new LinkedHashMap<>();
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) return new LinkedHashMap<>();
        String raw = item.getItemMeta().getPersistentDataContainer().get(pdc.KEY_ITEM_CUSTOM_ENCHANTS, PersistentDataType.STRING);
        return readEnchants(raw);
    }

    public Map<String, Integer> getAllActiveCustomEnchants(ItemStack item) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : getAllCustomEnchants(item).entrySet()) {
            int activeLevel = getActiveEnchantLevel(item, entry.getKey());
            if (activeLevel > 0) {
                result.put(entry.getKey(), activeLevel);
            }
        }
        return result;
    }

    public Map<String, CustomEnchant> getRegisteredEnchants() {
        EnchantRegistry registry = EnchantRegistry.getInstance();
        if (registry == null) {
            return Map.of();
        }
        Map<String, CustomEnchant> result = new LinkedHashMap<>();
        for (EnchantDefinition definition : registry.getAllDefinitions()) {
            result.put(definition.id(), new CustomEnchant(
                    definition.id(),
                    definition.display(),
                    definition.maxLevel(),
                    String.join(" ", definition.description())
            ));
        }
        return Map.copyOf(result);
    }

    public static String describeEnchant(String enchantId) {
        EnchantRegistry registry = EnchantRegistry.getInstance();
        if (registry == null) {
            return "";
        }
        return registry.get(enchantId)
                .map(definition -> String.join(" ", definition.description()))
                .orElse("");
    }

    public Map<String, Integer> readEnchants(String raw) {
        Map<String, Integer> enchants = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return enchants;
        for (String entry : raw.split(";")) {
            String[] parts = entry.split(":", 2);
            if (parts.length != 2 || parts[0].isBlank()) continue;
            try {
                enchants.put(normalize(parts[0]), Math.max(1, Integer.parseInt(parts[1].trim())));
            } catch (NumberFormatException ignored) {
                enchants.put(normalize(parts[0]), 1);
            }
        }
        return enchants;
    }

    public String writeEnchants(Map<String, Integer> enchants) {
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, Integer> entry : enchants.entrySet()) {
            String id = normalize(entry.getKey());
            if (id.isBlank()) continue;
            if (!encoded.isEmpty()) encoded.append(';');
            encoded.append(id).append(':').append(Math.max(1, entry.getValue()));
        }
        return encoded.toString();
    }

    public void writeEnchants(ItemStack item, Map<String, Integer> enchants) {
        if (item == null || item.getType().isAir()) return;
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer container = meta.getPersistentDataContainer();
        String encoded = writeEnchants(enchants);
        if (encoded.isBlank()) {
            container.remove(pdc.KEY_ITEM_CUSTOM_ENCHANTS);
        } else {
            container.set(pdc.KEY_ITEM_CUSTOM_ENCHANTS, PersistentDataType.STRING, encoded);
        }
        item.setItemMeta(meta);
    }

    private boolean conflictsWithExisting(EnchantDefinition definition, Map<String, Integer> current) {
        EnchantRegistry registry = EnchantRegistry.getInstance();
        if (registry == null) {
            return false;
        }
        for (String id : current.keySet()) {
            if (id.equals(definition.id())) {
                continue;
            }
            EnchantDefinition other = registry.get(id).orElse(null);
            if (other == null || !other.enabled()) {
                continue;
            }
            if ((definition.hasConflictGroup() && other.hasConflictGroup()
                    && definition.conflictGroup().equals(other.conflictGroup()))
                    || definition.explicitlyConflictsWith(other.id())
                    || other.explicitlyConflictsWith(definition.id())) {
                return true;
            }
        }
        return false;
    }

    private boolean exceedsUltimateLimit(EnchantDefinition definition, Map<String, Integer> current, EnchantRegistry registry) {
        if (current.containsKey(definition.id())) {
            return false;
        }

        int count = 0;
        for (String id : current.keySet()) {
            EnchantDefinition other = registry.get(id).orElse(null);
            if (other == null || other.rarity() != EnchantRarity.ULTIMATE) {
                continue;
            }
            if (!other.enabled() && !registry.settings().countDisabledUltimateInLimit()) {
                continue;
            }
            count++;
        }
        return count >= registry.settings().ultimateLimitPerItem();
    }

    private boolean isBook(ItemStack item) {
        return item != null && (item.getType() == Material.BOOK || item.getType() == Material.ENCHANTED_BOOK);
    }

    private void refreshFormat(ItemStack item) {
        ItemFormatManager formatManager = ItemFormatManager.getInstance();
        if (formatManager != null) {
            formatManager.formatItem(item, true);
        }
    }

    private static String normalize(String value) {
        String normalized = EnchantRegistry.normalize(value);
        return normalized.equals("protection") ? "fortify" : normalized;
    }

    public record CustomEnchant(String id, String displayName, int maxLevel, String description) {
    }

    private record EnchantTableMerge(EnchantApplyResult result, EnchantDefinition definition, int nextLevel) {
        static EnchantTableMerge fail(String message) {
            return new EnchantTableMerge(EnchantApplyResult.fail(message), null, 0);
        }
    }
}
