package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Custom enchantment registry and PDC writer.
 */
public class EnchantManager {

    private static EnchantManager instance;
    private static final Map<String, CustomEnchant> ENCHANTS = new LinkedHashMap<>();

    public EnchantManager(ServerCorePlugin plugin) {
        instance = this;
        registerDefaults();
    }

    public static EnchantManager getInstance() {
        return instance;
    }

    public void addCustomEnchant(ItemStack item, String enchantId, int level) {
        if (item == null || item.getType().isAir() || enchantId == null) return;
        PDCManager pdc = PDCManager.getInstance();
        ItemFormatManager formatManager = ItemFormatManager.getInstance();
        if (pdc == null) return;

        String normalizedId = normalize(enchantId);
        CustomEnchant definition = ENCHANTS.get(normalizedId);
        int cappedLevel = Math.max(1, Math.min(level, definition == null ? 10 : definition.maxLevel()));

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer container = meta.getPersistentDataContainer();
        Map<String, Integer> enchants = readEnchants(container.get(pdc.KEY_ITEM_CUSTOM_ENCHANTS, PersistentDataType.STRING));
        enchants.put(normalizedId, cappedLevel);
        container.set(pdc.KEY_ITEM_CUSTOM_ENCHANTS, PersistentDataType.STRING, writeEnchants(enchants));
        item.setItemMeta(meta);

        if (formatManager != null) {
            formatManager.formatItem(item);
        }
    }

    public boolean hasCustomEnchant(ItemStack item, String enchantId) {
        return getCustomEnchantLevel(item, enchantId) > 0;
    }

    public int getCustomEnchantLevel(ItemStack item, String enchantId) {
        if (item == null || !item.hasItemMeta() || enchantId == null) return 0;
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) return 0;
        String raw = item.getItemMeta().getPersistentDataContainer().get(pdc.KEY_ITEM_CUSTOM_ENCHANTS, PersistentDataType.STRING);
        return readEnchants(raw).getOrDefault(normalize(enchantId), 0);
    }

    public Map<String, CustomEnchant> getRegisteredEnchants() {
        return Map.copyOf(ENCHANTS);
    }

    public static String describeEnchant(String enchantId) {
        CustomEnchant enchant = ENCHANTS.get(normalize(enchantId));
        return enchant == null ? "" : enchant.description();
    }

    private void registerDefaults() {
        register(new CustomEnchant("vampirism", "Vampirism", 5, "Restores health when you strike enemies."));
        register(new CustomEnchant("critical", "Critical", 5, "Increases critical strike performance."));
        register(new CustomEnchant("cleave", "Cleave", 5, "Splashes part of your melee damage nearby."));
        register(new CustomEnchant("experience_harvest", "Experience Harvest", 3, "Increases combat experience from kills."));
        register(new CustomEnchant("mana_surge", "Mana Surge", 5, "Improves ability-focused equipment scaling."));
        register(new CustomEnchant("undead_slayer", "Undead Slayer", 5, "Increases damage against UNDEAD main-tag creatures."));
        register(new CustomEnchant("skeleton_slayer", "Skeleton Slayer", 5, "Increases damage against SKELETON main-tag creatures."));
        register(new CustomEnchant("animal_slayer", "Animal Slayer", 5, "Increases damage against ANIMAL main-tag creatures."));
        register(new CustomEnchant("arthropod_slayer", "Arthropod Slayer", 5, "Increases damage against ARTHROPOD main-tag creatures."));
        register(new CustomEnchant("humanoid_slayer", "Humanoid Slayer", 5, "Increases damage against HUMANOID main-tag creatures."));
        register(new CustomEnchant("gelatinous_slayer", "Gelatinous Slayer", 5, "Increases damage against GELATINOUS main-tag creatures."));
        register(new CustomEnchant("construct_breaker", "Construct Breaker", 5, "Increases damage against CONSTRUCT main-tag creatures."));
        register(new CustomEnchant("exorcism", "Exorcism", 5, "Increases damage against GHOST main-tag creatures."));
        register(new CustomEnchant("giant_hunter", "Giant Hunter", 5, "Increases damage against GIANT main-tag creatures."));
        register(new CustomEnchant("aberrant_hunter", "Aberrant Hunter", 5, "Increases damage against ABERRANT main-tag creatures."));
    }

    private void register(CustomEnchant enchant) {
        ENCHANTS.put(enchant.id(), enchant);
    }

    private Map<String, Integer> readEnchants(String raw) {
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

    private String writeEnchants(Map<String, Integer> enchants) {
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, Integer> entry : enchants.entrySet()) {
            if (!encoded.isEmpty()) encoded.append(';');
            encoded.append(normalize(entry.getKey())).append(':').append(Math.max(1, entry.getValue()));
        }
        return encoded.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    public record CustomEnchant(String id, String displayName, int maxLevel, String description) {
    }
}
