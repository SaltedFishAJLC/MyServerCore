package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gemstone socket and insertion system.
 */
public class GemstoneManager {

    private static GemstoneManager instance;

    private final Map<String, GemstoneDefinition> gemstones = new LinkedHashMap<>();

    public GemstoneManager(ServerCorePlugin plugin) {
        instance = this;
        registerDefaults();
    }

    public static GemstoneManager getInstance() {
        return instance;
    }

    public enum SocketType {
        WEAPON, ARMOR, TOOL, UNIVERSAL
    }

    public void addSockets(ItemStack item, SocketType type, int amount) {
        if (item == null || item.getType().isAir() || type == null || amount <= 0) return;
        PDCManager pdc = PDCManager.getInstance();
        ItemFormatManager formatManager = ItemFormatManager.getInstance();
        if (pdc == null) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        PersistentDataContainer container = meta.getPersistentDataContainer();

        List<String> socketTypes = split(container.get(pdc.KEY_ITEM_SOCKET_TYPES, PersistentDataType.STRING));
        List<String> socketGems = split(container.get(pdc.KEY_ITEM_SOCKET_GEMS, PersistentDataType.STRING));
        for (int index = 0; index < amount; index++) {
            socketTypes.add(type.name());
            socketGems.add("EMPTY");
        }

        container.set(pdc.KEY_ITEM_SOCKET_TYPES, PersistentDataType.STRING, join(socketTypes));
        container.set(pdc.KEY_ITEM_SOCKET_GEMS, PersistentDataType.STRING, join(socketGems));
        item.setItemMeta(meta);
        if (formatManager != null) {
            formatManager.formatItem(item);
        }
    }

    public boolean applyGemstone(ItemStack item, String gemstoneId) {
        if (item == null || item.getType().isAir() || gemstoneId == null) return false;
        PDCManager pdc = PDCManager.getInstance();
        ItemFormatManager formatManager = ItemFormatManager.getInstance();
        if (pdc == null) return false;

        GemstoneDefinition gemstone = gemstones.get(normalize(gemstoneId));
        if (gemstone == null) return false;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer container = meta.getPersistentDataContainer();

        List<String> socketTypes = split(container.get(pdc.KEY_ITEM_SOCKET_TYPES, PersistentDataType.STRING));
        List<String> socketGems = split(container.get(pdc.KEY_ITEM_SOCKET_GEMS, PersistentDataType.STRING));
        while (socketGems.size() < socketTypes.size()) {
            socketGems.add("EMPTY");
        }

        int targetSlot = -1;
        for (int index = 0; index < socketTypes.size(); index++) {
            if (!socketGems.get(index).equalsIgnoreCase("EMPTY")) continue;
            SocketType socketType = parseSocket(socketTypes.get(index));
            if (socketType == SocketType.UNIVERSAL || socketType == gemstone.socketType()) {
                targetSlot = index;
                break;
            }
        }

        if (targetSlot < 0) {
            return false;
        }

        socketGems.set(targetSlot, gemstone.id());
        for (Map.Entry<String, Double> stat : gemstone.stats().entrySet()) {
            addStat(container, pdc, stat.getKey(), stat.getValue());
        }

        container.set(pdc.KEY_ITEM_SOCKET_GEMS, PersistentDataType.STRING, join(socketGems));
        item.setItemMeta(meta);
        if (formatManager != null) {
            formatManager.formatItem(item);
        }
        return true;
    }

    public void openGemstoneGUI(Player player) {
        player.sendMessage(Component.text("Gemstone GUI is available through addSockets/applyGemstone hooks."));
    }

    public Map<String, GemstoneDefinition> getGemstones() {
        return Map.copyOf(gemstones);
    }

    public GemstoneDefinition getGemstone(String gemstoneId) {
        if (gemstoneId == null) return null;
        return gemstones.get(normalize(gemstoneId));
    }

    private void registerDefaults() {
        register(new GemstoneDefinition("ruby", "红宝石", SocketType.WEAPON, ItemFormatManager.Rarity.UNCOMMON, NamedTextColor.RED, stats("base_damage", 6.0, "crit_damage", 0.05)));
        register(new GemstoneDefinition("sapphire", "蓝宝石", SocketType.TOOL, ItemFormatManager.Rarity.RARE, NamedTextColor.AQUA, stats("attr_intelligence", 8.0)));
        register(new GemstoneDefinition("jade", "翡翠", SocketType.ARMOR, ItemFormatManager.Rarity.RARE, NamedTextColor.GREEN, stats("base_armor", 10.0, "attr_toughness", 3.0)));
        register(new GemstoneDefinition("amber", "琥珀", SocketType.UNIVERSAL, ItemFormatManager.Rarity.COMMON, NamedTextColor.GOLD, stats("attr_agility", 4.0, "crit_chance", 0.01)));
        register(new GemstoneDefinition("amethyst", "紫水晶", SocketType.UNIVERSAL, ItemFormatManager.Rarity.LEGENDARY, NamedTextColor.LIGHT_PURPLE, stats("attr_willpower", 4.0, "attr_luck", 2.0)));
    }

    private void register(GemstoneDefinition definition) {
        gemstones.put(definition.id(), definition);
    }

    public void registerGemstone(String id, String displayName, SocketType socketType, ItemFormatManager.Rarity rarity,
                                 NamedTextColor color, Map<String, Double> stats) {
        if (id == null || id.isBlank() || socketType == null || rarity == null || color == null) return;
        register(new GemstoneDefinition(normalize(id), displayName, socketType, clampGemstoneRarity(rarity), color, new LinkedHashMap<>(stats)));
    }

    private ItemFormatManager.Rarity clampGemstoneRarity(ItemFormatManager.Rarity rarity) {
        if (rarity.ordinal() > ItemFormatManager.Rarity.LEGENDARY.ordinal()) {
            return ItemFormatManager.Rarity.LEGENDARY;
        }
        return rarity;
    }

    private void addStat(PersistentDataContainer container, PDCManager pdc, String statName, double delta) {
        NamespacedKey key = statKey(pdc, statName);
        if (key == null || Math.abs(delta) < 0.0001) return;
        double current = container.getOrDefault(key, PersistentDataType.DOUBLE, 0.0);
        container.set(key, PersistentDataType.DOUBLE, current + delta);
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

    private SocketType parseSocket(String raw) {
        try {
            return SocketType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return SocketType.UNIVERSAL;
        }
    }

    private List<String> split(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) return result;
        for (String part : raw.split(",")) {
            if (!part.isBlank()) result.add(part.trim());
        }
        return result;
    }

    private String join(List<String> values) {
        return String.join(",", values);
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).trim();
    }

    public record GemstoneDefinition(
            String id,
            String displayName,
            SocketType socketType,
            ItemFormatManager.Rarity rarity,
            NamedTextColor color,
            Map<String, Double> stats
    ) {
        public GemstoneDefinition {
            if (rarity.ordinal() > ItemFormatManager.Rarity.LEGENDARY.ordinal()) {
                rarity = ItemFormatManager.Rarity.LEGENDARY;
            }
        }
    }
}
