package com.servercore.enchant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class EnchantDescriptionRenderer {

    private EnchantDescriptionRenderer() {
    }

    public static List<String> render(EnchantDefinition definition, int level) {
        if (definition == null || definition.description().isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : definition.description()) {
            String rendered = line;
            for (var entry : definition.numericBonuses().entrySet()) {
                double value = entry.getValue().valueAt(level);
                rendered = rendered.replace("{" + entry.getKey() + "}", formatNumber(value));
                rendered = rendered.replace("{" + entry.getKey() + "_percent}", formatPercent(value));
            }
            if (definition.effect().hasEffect()) {
                for (var entry : definition.effect().numericParams().entrySet()) {
                    double value = entry.getValue().valueAt(level);
                    rendered = rendered.replace("{" + entry.getKey() + "}", formatNumber(value));
                    rendered = rendered.replace("{" + entry.getKey() + "_percent}", formatPercent(value));
                }
            }
            lines.add(rendered);
        }
        return lines;
    }

    private static String formatNumber(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return String.valueOf((int) Math.rint(value));
        }
        return String.format(Locale.US, "%.2f", value);
    }

    private static String formatPercent(double value) {
        return String.format(Locale.US, "%.1f", value * 100.0);
    }
}
