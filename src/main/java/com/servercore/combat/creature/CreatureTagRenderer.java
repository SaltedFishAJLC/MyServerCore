package com.servercore.combat.creature;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.ConfigurationSection;

import java.util.EnumMap;
import java.util.Map;

public final class CreatureTagRenderer {

    private final Map<CreatureMainTag, DisplayTag> mainDisplays = new EnumMap<>(CreatureMainTag.class);
    private final Map<CreatureTraitTag, DisplayTag> traitDisplays = new EnumMap<>(CreatureTraitTag.class);

    public CreatureTagRenderer(ConfigurationSection root) {
        loadDefaults();
        loadConfigured(root);
    }

    public Component render(CreatureTagProfile profile) {
        if (profile == null) {
            return Component.empty();
        }

        Component result = renderSingle(mainDisplays.get(profile.mainTag()));
        for (CreatureTraitTag trait : profile.traits().stream().sorted(CreatureTraitTag.DISPLAY_ORDER).toList()) {
            result = result.append(renderSingle(traitDisplays.get(trait)));
        }
        return result;
    }

    private Component renderSingle(DisplayTag display) {
        if (display == null || display.symbol().isBlank()) {
            return Component.empty();
        }
        return Component.text("[", NamedTextColor.DARK_GRAY)
                .append(Component.text(display.symbol(), display.color()))
                .append(Component.text("]", NamedTextColor.DARK_GRAY));
    }

    private void loadConfigured(ConfigurationSection root) {
        if (root == null) {
            return;
        }

        ConfigurationSection main = root.getConfigurationSection("tagDisplay.main");
        if (main != null) {
            for (String key : main.getKeys(false)) {
                try {
                    CreatureMainTag tag = CreatureMainTag.valueOf(key.toUpperCase(java.util.Locale.ROOT));
                    mainDisplays.put(tag, readDisplay(main.getConfigurationSection(key), mainDisplays.get(tag)));
                } catch (IllegalArgumentException ignored) {
                    // Unknown display entries are ignored so newer configs can be shared safely.
                }
            }
        }

        ConfigurationSection trait = root.getConfigurationSection("tagDisplay.trait");
        if (trait != null) {
            for (String key : trait.getKeys(false)) {
                try {
                    CreatureTraitTag tag = CreatureTraitTag.valueOf(key.toUpperCase(java.util.Locale.ROOT));
                    traitDisplays.put(tag, readDisplay(trait.getConfigurationSection(key), traitDisplays.get(tag)));
                } catch (IllegalArgumentException ignored) {
                    // Unknown display entries are ignored so newer configs can be shared safely.
                }
            }
        }
    }

    private DisplayTag readDisplay(ConfigurationSection section, DisplayTag fallback) {
        if (section == null) {
            return fallback;
        }
        String symbol = section.getString("symbol", fallback == null ? "" : fallback.symbol());
        String name = section.getString("name", fallback == null ? "" : fallback.name());
        NamedTextColor color = parseColor(section.getString("color"), fallback == null ? NamedTextColor.WHITE : fallback.color());
        return new DisplayTag(symbol, name, color);
    }

    private NamedTextColor parseColor(String raw, NamedTextColor fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        NamedTextColor color = NamedTextColor.NAMES.value(raw.toLowerCase(java.util.Locale.ROOT));
        return color == null ? fallback : color;
    }

    private void loadDefaults() {
        mainDisplays.put(CreatureMainTag.UNDEAD, new DisplayTag("☠", "亡灵", NamedTextColor.DARK_GREEN));
        mainDisplays.put(CreatureMainTag.SKELETON, new DisplayTag("骨", "骷髅", NamedTextColor.GRAY));
        mainDisplays.put(CreatureMainTag.ANIMAL, new DisplayTag("兽", "动物", NamedTextColor.GOLD));
        mainDisplays.put(CreatureMainTag.ARTHROPOD, new DisplayTag("虫", "节肢", NamedTextColor.DARK_PURPLE));
        mainDisplays.put(CreatureMainTag.HUMANOID, new DisplayTag("人", "人形", NamedTextColor.YELLOW));
        mainDisplays.put(CreatureMainTag.GELATINOUS, new DisplayTag("●", "黏体", NamedTextColor.GREEN));
        mainDisplays.put(CreatureMainTag.CONSTRUCT, new DisplayTag("⚙", "构装", NamedTextColor.AQUA));
        mainDisplays.put(CreatureMainTag.GHOST, new DisplayTag("✦", "幽灵", NamedTextColor.LIGHT_PURPLE));
        mainDisplays.put(CreatureMainTag.GIANT, new DisplayTag("▲", "巨兽", NamedTextColor.DARK_RED));
        mainDisplays.put(CreatureMainTag.ABERRANT, new DisplayTag("◈", "异怪", NamedTextColor.DARK_AQUA));

        traitDisplays.put(CreatureTraitTag.FIRE, new DisplayTag("✹", "熔火", NamedTextColor.RED));
        traitDisplays.put(CreatureTraitTag.FROST, new DisplayTag("❄", "冰霜", NamedTextColor.AQUA));
        traitDisplays.put(CreatureTraitTag.VOID, new DisplayTag("☽", "幽冥", NamedTextColor.DARK_PURPLE));
        traitDisplays.put(CreatureTraitTag.FLYING, new DisplayTag("⬆", "飞行", NamedTextColor.BLUE));
        traitDisplays.put(CreatureTraitTag.BOSS, new DisplayTag("★", "首领", NamedTextColor.GOLD));
    }

    private record DisplayTag(String symbol, String name, NamedTextColor color) {
    }
}
