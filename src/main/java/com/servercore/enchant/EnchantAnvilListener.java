package com.servercore.enchant;

import com.servercore.ServerCorePlugin;
import com.servercore.manager.EnchantManager;
import com.servercore.manager.ItemFormatManager;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

public final class EnchantAnvilListener implements Listener {

    public EnchantAnvilListener(ServerCorePlugin plugin) {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack result = buildResult(event.getInventory().getItem(0), event.getInventory().getItem(1));
        if (result == null) {
            return;
        }
        event.setResult(result);
        event.getInventory().setRepairCost(Math.max(1, event.getInventory().getRepairCost()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTakeResult(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getType() != InventoryType.ANVIL || event.getSlotType() != InventoryType.SlotType.RESULT) {
            return;
        }
        if (!(event.getView().getTopInventory() instanceof AnvilInventory inventory)) {
            return;
        }
        ItemStack result = buildResult(inventory.getItem(0), inventory.getItem(1));
        if (result != null) {
            event.setCurrentItem(result);
        }
    }

    private ItemStack buildResult(ItemStack left, ItemStack right) {
        EnchantManager enchantManager = EnchantManager.getInstance();
        if (enchantManager == null || left == null || left.getType().isAir() || right == null || right.getType().isAir()) {
            return null;
        }

        Map<String, Integer> rightEnchants = enchantManager.getAllCustomEnchants(right);
        if (rightEnchants.isEmpty()) {
            return null;
        }

        ItemStack result = left.clone();
        Map<String, Integer> merged = new LinkedHashMap<>(enchantManager.getAllCustomEnchants(left));
        boolean leftIsBook = isBook(left);
        boolean rightIsBook = isBook(right);
        boolean changed = false;
        for (Map.Entry<String, Integer> entry : rightEnchants.entrySet()) {
            MergeOutcome outcome = mergeOne(result, merged, entry.getKey(), entry.getValue(), leftIsBook, rightIsBook);
            if (outcome.changed()) {
                changed = true;
                merged.put(entry.getKey(), outcome.level());
                enchantManager.writeEnchants(result, merged);
            }
        }

        if (!changed) {
            return null;
        }
        enchantManager.writeEnchants(result, merged);
        ItemFormatManager formatManager = ItemFormatManager.getInstance();
        if (formatManager != null) {
            formatManager.formatItem(result, true);
        }
        return result;
    }

    private MergeOutcome mergeOne(ItemStack result, Map<String, Integer> current, String id, int incomingLevel,
                                  boolean leftIsBook, boolean rightIsBook) {
        EnchantRegistry registry = EnchantRegistry.getInstance();
        EnchantManager enchantManager = EnchantManager.getInstance();
        if (registry == null || enchantManager == null) {
            return MergeOutcome.unchanged();
        }

        EnchantDefinition definition = registry.get(id).orElse(null);
        int existing = current.getOrDefault(id, 0);
        if (definition == null) {
            return existing > 0 ? MergeOutcome.changed(existing) : MergeOutcome.unchanged();
        }

        if (!definition.enabled()) {
            return existing > 0 ? MergeOutcome.changed(existing) : MergeOutcome.unchanged();
        }

        int incoming = Math.max(1, Math.min(incomingLevel, definition.maxLevel()));
        int next = existing <= 0 ? incoming : existing == incoming ? existing + 1 : Math.max(existing, incoming);
        next = Math.min(next, definition.maxLevel());
        if (next <= existing) {
            return MergeOutcome.unchanged();
        }

        if (next > definition.softMaxLevel() && !canAcceptAboveSoftFromAnvil(leftIsBook, rightIsBook, incoming, next)) {
            return MergeOutcome.unchanged();
        }

        if (!leftIsBook && !EnchantSlotMatcher.matches(result, definition.slots())) {
            return MergeOutcome.unchanged();
        }

        EnchantApplyResult validation = enchantManager.canApply(result, id, next);
        if (!validation.success()) {
            return MergeOutcome.unchanged();
        }
        if ("one_for_all".equals(id)) {
            current.clear();
        }
        current.put(id, next);
        enchantManager.writeEnchants(result, current);
        return MergeOutcome.changed(next);
    }

    private boolean canAcceptAboveSoftFromAnvil(boolean leftIsBook, boolean rightIsBook, int incomingLevel, int nextLevel) {
        if (leftIsBook || !rightIsBook) {
            return false;
        }
        return incomingLevel == nextLevel;
    }

    private boolean isBook(ItemStack item) {
        return item != null && (item.getType() == Material.BOOK || item.getType() == Material.ENCHANTED_BOOK);
    }

    private record MergeOutcome(boolean changed, int level) {
        static MergeOutcome unchanged() {
            return new MergeOutcome(false, 0);
        }

        static MergeOutcome changed(int level) {
            return new MergeOutcome(true, level);
        }
    }
}
