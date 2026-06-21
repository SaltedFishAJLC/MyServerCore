package com.servercore.enchant;

import com.servercore.manager.AttributeManager;
import com.servercore.manager.AuraSkillsBridge;
import dev.aurelium.auraskills.api.user.SkillsUser;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

final class ManaAccess {

    private ManaAccess() {
    }

    static double getMana(Player player) {
        SkillsUser user = user(player);
        if (user == null) {
            return 0.0;
        }
        Double value = invokeNumber(user, "getMana");
        if (value == null) {
            value = invokeNumber(user, "getCurrentMana");
        }
        return value == null ? 0.0 : Math.max(0.0, value);
    }

    static double getMaxMana(Player player) {
        AttributeManager attributeManager = AttributeManager.getInstance();
        if (attributeManager != null) {
            return Math.max(0.0, attributeManager.getEffectiveMaxMana(player));
        }
        SkillsUser user = user(player);
        return user == null ? 0.0 : Math.max(0.0, user.getMaxMana());
    }

    static double consumeMana(Player player, double amount) {
        double requested = Math.max(0.0, amount);
        if (requested <= 0.0) {
            return 0.0;
        }
        double current = getMana(player);
        double consumed = Math.min(current, requested);
        if (consumed <= 0.0) {
            return 0.0;
        }
        setMana(player, current - consumed);
        EquipmentEnchantService equipmentEnchants = EquipmentEnchantService.getInstance();
        if (equipmentEnchants != null) {
            equipmentEnchants.recordManaSpent(player, consumed);
        }
        return consumed;
    }

    static void restoreMana(Player player, double amount) {
        double restored = Math.max(0.0, amount);
        if (restored <= 0.0) {
            return;
        }
        double current = getMana(player);
        setMana(player, Math.min(getMaxMana(player), current + restored));
    }

    private static void setMana(Player player, double value) {
        SkillsUser user = user(player);
        if (user == null) {
            return;
        }
        double safeValue = Math.max(0.0, value);
        if (invokeSetter(user, "setMana", safeValue)) {
            return;
        }
        invokeSetter(user, "setCurrentMana", safeValue);
    }

    private static SkillsUser user(Player player) {
        AuraSkillsBridge bridge = AuraSkillsBridge.getInstance();
        return bridge == null ? null : bridge.getUser(player);
    }

    private static Double invokeNumber(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            Object value = method.invoke(target);
            return value instanceof Number number ? number.doubleValue() : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static boolean invokeSetter(Object target, String name, double value) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> type = method.getParameterTypes()[0];
            try {
                if (type == double.class || type == Double.class) {
                    method.invoke(target, value);
                    return true;
                }
                if (type == float.class || type == Float.class) {
                    method.invoke(target, (float) value);
                    return true;
                }
                if (type == int.class || type == Integer.class) {
                    method.invoke(target, (int) Math.round(value));
                    return true;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return false;
            }
        }
        return false;
    }
}
