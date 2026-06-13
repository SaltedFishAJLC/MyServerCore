package com.servercore.enchant;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public sealed interface ValueCurve permits ValueCurve.ConstantCurve, ValueCurve.LinearCurve, ValueCurve.PerLevelCurve {

    double valueAt(int level);

    static ValueCurve constant(double value) {
        return new ConstantCurve(value);
    }

    static ValueCurve fromSection(ConfigurationSection section, String debugPath) {
        if (section == null) {
            return constant(0.0);
        }

        String type = section.getString("type", "CONSTANT").trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "LINEAR" -> new LinearCurve(section.getDouble("base", 0.0), section.getDouble("per_level", 0.0));
            case "PER_LEVEL" -> new PerLevelCurve(section.getDoubleList("values"), debugPath);
            case "CONSTANT" -> new ConstantCurve(section.getDouble("value", 0.0));
            default -> new ConstantCurve(section.getDouble("value", 0.0));
        };
    }

    record ConstantCurve(double value) implements ValueCurve {
        @Override
        public double valueAt(int level) {
            return value;
        }
    }

    record LinearCurve(double base, double perLevel) implements ValueCurve {
        @Override
        public double valueAt(int level) {
            return base + perLevel * Math.max(1, level);
        }
    }

    final class PerLevelCurve implements ValueCurve {
        private final List<Double> values;
        private final String debugPath;

        public PerLevelCurve(List<Double> values, String debugPath) {
            this.values = values == null ? List.of() : List.copyOf(new ArrayList<>(values));
            this.debugPath = debugPath == null ? "" : debugPath;
        }

        @Override
        public double valueAt(int level) {
            if (values.isEmpty()) {
                return 0.0;
            }
            int index = Math.max(0, Math.min(level - 1, values.size() - 1));
            return values.get(index);
        }

        public int configuredLevels() {
            return values.size();
        }

        public String debugPath() {
            return debugPath;
        }
    }
}
