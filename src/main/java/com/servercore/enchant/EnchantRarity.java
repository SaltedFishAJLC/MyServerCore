package com.servercore.enchant;

import net.kyori.adventure.text.format.NamedTextColor;

public enum EnchantRarity {
    COMMON("普通", NamedTextColor.GRAY),
    UNCOMMON("罕见", NamedTextColor.GREEN),
    RARE("稀有", NamedTextColor.BLUE),
    ULTIMATE("终极", NamedTextColor.GOLD);

    private final String display;
    private final NamedTextColor color;

    EnchantRarity(String display, NamedTextColor color) {
        this.display = display;
        this.color = color;
    }

    public String display() {
        return display;
    }

    public NamedTextColor color() {
        return color;
    }

    public boolean isMechanicAllowed() {
        return this == RARE || this == ULTIMATE;
    }
}
