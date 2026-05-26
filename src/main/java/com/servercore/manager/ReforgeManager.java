package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reforge prefix calculation and application engine.
 */
public class ReforgeManager implements Listener {

    private static final int GUI_SIZE = 27;
    private static ReforgeManager instance;

    private final ServerCorePlugin plugin;
    private final Map<String, ReforgeDefinition> reforges = new LinkedHashMap<>();

    public ReforgeManager(ServerCorePlugin plugin) {
        this.plugin = plugin;
        instance = this;
        registerDefaults();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public static ReforgeManager getInstance() {
        return instance;
    }

    public boolean refreshReforge(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null || !item.hasItemMeta()) return false;

        String reforgeId = item.getItemMeta().getPersistentDataContainer()
                .get(pdc.KEY_ITEM_REFORGE_ID, PersistentDataType.STRING);
        if (reforgeId == null || reforgeId.isBlank()) {
            return false;
        }
        return applyReforge(item, reforgeId);
    }

    public boolean applyReforge(ItemStack item, String reforgeId) {
        if (item == null || item.getType().isAir() || reforgeId == null) return false;

        PDCManager pdc = PDCManager.getInstance();
        ItemFormatManager formatManager = ItemFormatManager.getInstance();
        if (pdc == null || formatManager == null) return false;

        ReforgeDefinition definition = reforges.get(normalize(reforgeId));
        if (definition == null || !definition.canApplyTo(item.getType())) return false;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer container = meta.getPersistentDataContainer();

        removePreviousReforge(container, pdc);

        double multiplier = formatManager.getRarityMultiplier(item);
        Map<String, Double> appliedStats = new LinkedHashMap<>();
        for (Map.Entry<String, Double> stat : definition.baseStats().entrySet()) {
            double scaledValue = stat.getValue() * multiplier;
            addStat(container, pdc, stat.getKey(), scaledValue);
            appliedStats.put(stat.getKey(), scaledValue);
        }

        container.set(pdc.KEY_ITEM_REFORGE_ID, PersistentDataType.STRING, definition.id());
        container.set(pdc.KEY_ITEM_REFORGE_PREFIX, PersistentDataType.STRING, definition.prefix());
        container.set(pdc.KEY_ITEM_REFORGE_STATS, PersistentDataType.STRING, encodeStats(appliedStats));
        item.setItemMeta(meta);
        formatManager.formatItem(item);
        return true;
    }

    public void clearReforge(ItemStack item) {
        if (item == null || item.getType().isAir()) return;
        PDCManager pdc = PDCManager.getInstance();
        ItemFormatManager formatManager = ItemFormatManager.getInstance();
        if (pdc == null || formatManager == null) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer container = meta.getPersistentDataContainer();
        removePreviousReforge(container, pdc);
        container.remove(pdc.KEY_ITEM_REFORGE_ID);
        container.remove(pdc.KEY_ITEM_REFORGE_PREFIX);
        container.remove(pdc.KEY_ITEM_REFORGE_STATS);
        item.setItemMeta(meta);
        formatManager.formatItem(item);
    }

    public void openReforgeGUI(Player player) {
        Inventory inventory = Bukkit.createInventory(new ReforgeGuiHolder(), GUI_SIZE, Component.text("Reforge Station"));
        int slot = 10;
        for (ReforgeDefinition definition : reforges.values()) {
            inventory.setItem(slot++, createButton(definition));
            if (slot == 17) slot = 19;
        }
        player.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ReforgeGuiHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || !clicked.hasItemMeta()) return;
        String reforgeId = clicked.getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey(plugin, "reforge_button_id"), PersistentDataType.STRING);
        if (reforgeId == null) return;

        ItemStack target = player.getInventory().getItemInMainHand();
        if (target.getType().isAir()) {
            player.sendMessage(Component.text("Hold the item you want to reforge."));
            return;
        }
        if (applyReforge(target, reforgeId)) {
            player.getInventory().setItemInMainHand(target);
            player.sendMessage(Component.text("Applied reforge: " + reforges.get(reforgeId).prefix()));
        } else {
            player.sendMessage(Component.text("This reforge cannot be applied to the held item."));
        }
    }

    public Map<String, ReforgeDefinition> getReforges() {
        return Map.copyOf(reforges);
    }

    private void registerDefaults() {
        register(new ReforgeDefinition("sharp", "Sharp", Set.of(ItemCategory.WEAPON), stats(
                "base_damage", 8.0,
                "crit_chance", 0.03,
                "crit_damage", 0.15
        )));
        register(new ReforgeDefinition("fierce", "Fierce", Set.of(ItemCategory.WEAPON), stats(
                "base_damage", 5.0,
                "crit_chance", 0.05,
                "brutality", 4.0
        )));
        register(new ReforgeDefinition("divine", "Divine", Set.of(ItemCategory.WEAPON, ItemCategory.TOOL), stats(
                "base_damage", 4.0,
                "base_multiplier", 0.08,
                "crit_damage", 0.20
        )));
        register(new ReforgeDefinition("reinforced", "Reinforced", Set.of(ItemCategory.ARMOR), stats(
                "base_armor", 12.0,
                "attr_toughness", 4.0,
                "attr_willpower", 2.0
        )));
        register(new ReforgeDefinition("wise", "Wise", Set.of(ItemCategory.ARMOR), stats(
                "attr_intelligence", 8.0,
                "crit_chance", 0.02,
                "base_multiplier", 0.04
        )));
        register(new ReforgeDefinition("swift", "Swift", Set.of(ItemCategory.WEAPON, ItemCategory.TOOL), stats(
                "attr_agility", 8.0,
                "armor_pen", 5.0,
                "crit_chance", 0.02
        )));
    }

    public void registerReforge(String id, String prefix, Set<ItemCategory> allowedCategories, Map<String, Double> baseStats) {
        if (id == null || id.isBlank() || prefix == null || prefix.isBlank() || allowedCategories == null || allowedCategories.isEmpty()) {
            return;
        }
        register(new ReforgeDefinition(normalize(id), prefix, Set.copyOf(allowedCategories), new LinkedHashMap<>(baseStats)));
    }

    private void register(ReforgeDefinition definition) {
        reforges.put(definition.id(), definition);
    }

    private ItemStack createButton(ReforgeDefinition definition) {
        ItemStack item = new ItemStack(Material.ANVIL);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(definition.prefix()));
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "reforge_button_id"), PersistentDataType.STRING, definition.id());
            item.setItemMeta(meta);
        }
        return item;
    }

    private void removePreviousReforge(PersistentDataContainer container, PDCManager pdc) {
        Map<String, Double> previous = decodeStats(container.get(pdc.KEY_ITEM_REFORGE_STATS, PersistentDataType.STRING));
        for (Map.Entry<String, Double> stat : previous.entrySet()) {
            addStat(container, pdc, stat.getKey(), -stat.getValue());
        }
    }

    private void addStat(PersistentDataContainer container, PDCManager pdc, String statName, double delta) {
        NamespacedKey key = statKey(pdc, statName);
        if (key == null || Math.abs(delta) < 0.0001) return;
        double current = container.getOrDefault(key, PersistentDataType.DOUBLE, 0.0);
        double next = current + delta;
        if (Math.abs(next) < 0.0001) {
            container.remove(key);
        } else {
            container.set(key, PersistentDataType.DOUBLE, next);
        }
    }

    private NamespacedKey statKey(PDCManager pdc, String statName) {
        return switch (statName) {
            case "base_damage" -> pdc.KEY_BASE_DAMAGE;
            case "base_multiplier" -> pdc.KEY_BASE_MULTIPLIER;
            case "crit_chance" -> pdc.KEY_CRIT_CHANCE;
            case "crit_damage" -> pdc.KEY_CRIT_DAMAGE;
            case "brutality" -> pdc.KEY_BRUTALITY;
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
            default -> null;
        };
    }

    private Map<String, Double> stats(Object... pairs) {
        Map<String, Double> result = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            result.put((String) pairs[index], (Double) pairs[index + 1]);
        }
        return result;
    }

    private String encodeStats(Map<String, Double> stats) {
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, Double> entry : stats.entrySet()) {
            if (!encoded.isEmpty()) encoded.append(';');
            encoded.append(entry.getKey()).append('=').append(String.format(Locale.US, "%.6f", entry.getValue()));
        }
        return encoded.toString();
    }

    private Map<String, Double> decodeStats(String raw) {
        Map<String, Double> stats = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return stats;
        for (String entry : raw.split(";")) {
            String[] parts = entry.split("=", 2);
            if (parts.length != 2) continue;
            try {
                stats.put(parts[0], Double.parseDouble(parts[1]));
            } catch (NumberFormatException ignored) {
                // Skip broken historical entries.
            }
        }
        return stats;
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).trim();
    }

    public enum ItemCategory {
        WEAPON,
        ARMOR,
        TOOL;

        static ItemCategory fromMaterial(Material material) {
            String name = material.name();
            if (name.endsWith("_SWORD")
                    || name.endsWith("_AXE")
                    || material == Material.BOW
                    || material == Material.CROSSBOW
                    || material == Material.TRIDENT
                    || material == Material.MACE) {
                return WEAPON;
            }
            if (name.endsWith("_HELMET")
                    || name.endsWith("_CHESTPLATE")
                    || name.endsWith("_LEGGINGS")
                    || name.endsWith("_BOOTS")
                    || material == Material.SHIELD) {
                return ARMOR;
            }
            if (name.endsWith("_PICKAXE")
                    || name.endsWith("_SHOVEL")
                    || name.endsWith("_HOE")
                    || material == Material.FISHING_ROD
                    || material == Material.SHEARS
                    || material == Material.FLINT_AND_STEEL) {
                return TOOL;
            }
            return null;
        }
    }

    public record ReforgeDefinition(String id, String prefix, Set<ItemCategory> allowedCategories, Map<String, Double> baseStats) {
        boolean canApplyTo(Material material) {
            ItemCategory category = ItemCategory.fromMaterial(material);
            return category != null && allowedCategories.contains(category);
        }
    }

    private static final class ReforgeGuiHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }
}
