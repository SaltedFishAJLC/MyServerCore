package com.servercore.enchant;

import com.servercore.manager.WeaponTemplateManager;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

public final class EnchantSlotMatcher {

    private EnchantSlotMatcher() {
    }

    public static boolean matches(ItemStack item, Set<EnchantSlot> slots) {
        if (slots == null || slots.isEmpty()) {
            return false;
        }
        if (item == null || item.getType().isAir()) {
            return false;
        }
        for (EnchantSlot slot : slots) {
            if (matches(item, slot)) {
                return true;
            }
        }
        return false;
    }

    public static boolean matches(ItemStack item, EnchantSlot slot) {
        Material material = item == null ? null : item.getType();
        if (material == null || material.isAir() || slot == null) {
            return false;
        }

        WeaponTemplateManager templateManager = WeaponTemplateManager.getInstance();
        WeaponTemplateManager.WeaponTemplate template = templateManager == null ? null : templateManager.getTemplate(item);
        if (template == null && templateManager != null) {
            template = templateManager.getDefaultTemplate(material);
        }

        return switch (slot) {
            case WEAPON -> isWeapon(material, template);
            case MELEE_WEAPON -> template != null ? template.isMelee() : isMeleeMaterial(material);
            case RANGED_WEAPON -> template != null ? template.isRanged() : material == Material.BOW || material == Material.CROSSBOW;
            case MAGIC_WEAPON -> false;
            case ARMOR -> isArmor(material);
            case HELMET -> material.name().endsWith("_HELMET") || material == Material.TURTLE_HELMET;
            case CHESTPLATE -> material.name().endsWith("_CHESTPLATE");
            case LEGGINGS -> material.name().endsWith("_LEGGINGS");
            case BOOTS -> material.name().endsWith("_BOOTS");
            case SHIELD -> material == Material.SHIELD || (template != null && template.isShield());
            case TOOL -> isTool(material);
            case PICKAXE -> material.name().endsWith("_PICKAXE");
            case AXE -> material.name().endsWith("_AXE");
            case SHOVEL -> material.name().endsWith("_SHOVEL");
            case HOE -> material.name().endsWith("_HOE");
            case FISHING_ROD -> material == Material.FISHING_ROD;
            case ACCESSORY -> false;
        };
    }

    private static boolean isWeapon(Material material, WeaponTemplateManager.WeaponTemplate template) {
        return template != null && (template.isMelee() || template.isRanged())
                || isMeleeMaterial(material)
                || material == Material.BOW
                || material == Material.CROSSBOW;
    }

    private static boolean isMeleeMaterial(Material material) {
        String name = material.name();
        return name.endsWith("_SWORD")
                || name.endsWith("_AXE")
                || name.endsWith("_HOE")
                || material == Material.MACE
                || material == Material.TRIDENT;
    }

    private static boolean isArmor(Material material) {
        String name = material.name();
        return name.endsWith("_HELMET")
                || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS")
                || material == Material.TURTLE_HELMET;
    }

    private static boolean isTool(Material material) {
        String name = material.name();
        return name.endsWith("_PICKAXE")
                || name.endsWith("_AXE")
                || name.endsWith("_SHOVEL")
                || name.endsWith("_HOE")
                || material == Material.SHEARS
                || material == Material.FISHING_ROD;
    }
}
