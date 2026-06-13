package com.servercore.manager;

import com.servercore.enchant.EnchantStatBundle;
import com.servercore.enchant.EnchantStatResolver;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * 玩家综合战斗属性快照
 * @param baseDamage 基础面板伤害 (1.0 + 力量 + 装备附加)
 * @param baseMultiplier 综合增伤乘区
 * @param critChance 暴击率 (0.0 ~ 1.0)
 * @param critDamage 暴击倍率 (基础通常为 1.5)
 * @param brutality 残暴值 (近战专属)
 * @param lifesteal 吸血率 (0.025 = 2.5%)
 * @param armorPen 破甲值 (远程专属)
 */
public record CombatStats(
        double baseDamage,
        double baseMultiplier,
        double critChance,
        double critDamage,
        double brutality,
        double lifesteal,
        double armorPen
) {
    /**
     * 计算玩家的静态属性快照（防具、饰品、护符、遗物、技能）
     * 排除主手武器的实时变动属性
     */
    public static CombatStats calculateStatic(Player player) {
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return new CombatStats(0.0, 1.0, 0.0, 1.5, 0.0, 0.0, 0.0);
        }

        double totalBaseDamage = 0.0;
        double totalBaseMultiplier = 1.0; // 基础乘区系数 1.0 (即100%)
        double totalCritChance = 0.0;
        double totalCritDamage = 1.5; // 基础暴击倍率 150%
        double totalBrutality = 0.0;
        double totalLifesteal = 0.0;
        double totalArmorPen = 0.0;

        // 1. 扫描全套防具
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor != null) {
                totalBaseDamage += pdc.getStat(armor, pdc.KEY_BASE_DAMAGE);
                totalBaseMultiplier += pdc.getStat(armor, pdc.KEY_BASE_MULTIPLIER);
                totalCritChance += pdc.getStat(armor, pdc.KEY_CRIT_CHANCE);
                totalCritDamage += pdc.getStat(armor, pdc.KEY_CRIT_DAMAGE);
                totalBrutality += pdc.getStat(armor, pdc.KEY_BRUTALITY);
                totalLifesteal += pdc.getStat(armor, pdc.KEY_LIFESTEAL);
                totalArmorPen += pdc.getStat(armor, pdc.KEY_ARMOR_PEN);
                EnchantStatBundle enchantStats = resolveEnchantStats(armor, player, null);
                totalBaseDamage += enchantStats.baseDamage();
                totalBaseMultiplier += enchantStats.baseMultiplier();
                totalCritChance += enchantStats.critChance();
                totalCritDamage += enchantStats.critDamage();
                totalBrutality += enchantStats.brutality();
                totalLifesteal += enchantStats.lifesteal();
                totalArmorPen += enchantStats.armorPen();
            }
        }
        
        // 3. 扫描饰品与护符包 (如果有)
        AccessoryManager accManager = AccessoryManager.getInstance();
        if (accManager != null) {
            for (ItemStack acc : accManager.loadAccessories(player)) {
                if (acc != null) {
                    totalBaseDamage += pdc.getStat(acc, pdc.KEY_BASE_DAMAGE);
                    totalBaseMultiplier += pdc.getStat(acc, pdc.KEY_BASE_MULTIPLIER);
                    totalCritChance += pdc.getStat(acc, pdc.KEY_CRIT_CHANCE);
                    totalCritDamage += pdc.getStat(acc, pdc.KEY_CRIT_DAMAGE);
                    totalBrutality += pdc.getStat(acc, pdc.KEY_BRUTALITY);
                    totalLifesteal += pdc.getStat(acc, pdc.KEY_LIFESTEAL);
                    totalArmorPen += pdc.getStat(acc, pdc.KEY_ARMOR_PEN);
                    EnchantStatBundle enchantStats = resolveEnchantStats(acc, player, null);
                    totalBaseDamage += enchantStats.baseDamage();
                    totalBaseMultiplier += enchantStats.baseMultiplier();
                    totalCritChance += enchantStats.critChance();
                    totalCritDamage += enchantStats.critDamage();
                    totalBrutality += enchantStats.brutality();
                    totalLifesteal += enchantStats.lifesteal();
                    totalArmorPen += enchantStats.armorPen();
                }
            }
            
            // 默认容量 54 格护符包
            for (ItemStack tal : accManager.loadTalismanBag(player, 54)) {
                if (tal != null) {
                    totalBaseDamage += pdc.getStat(tal, pdc.KEY_BASE_DAMAGE);
                    totalBaseMultiplier += pdc.getStat(tal, pdc.KEY_BASE_MULTIPLIER);
                    totalCritChance += pdc.getStat(tal, pdc.KEY_CRIT_CHANCE);
                    totalCritDamage += pdc.getStat(tal, pdc.KEY_CRIT_DAMAGE);
                    totalBrutality += pdc.getStat(tal, pdc.KEY_BRUTALITY);
                    totalLifesteal += pdc.getStat(tal, pdc.KEY_LIFESTEAL);
                    totalArmorPen += pdc.getStat(tal, pdc.KEY_ARMOR_PEN);
                    EnchantStatBundle enchantStats = resolveEnchantStats(tal, player, null);
                    totalBaseDamage += enchantStats.baseDamage();
                    totalBaseMultiplier += enchantStats.baseMultiplier();
                    totalCritChance += enchantStats.critChance();
                    totalCritDamage += enchantStats.critDamage();
                    totalBrutality += enchantStats.brutality();
                    totalLifesteal += enchantStats.lifesteal();
                    totalArmorPen += enchantStats.armorPen();
                }
            }
        }

        AttributeManager attributeManager = AttributeManager.getInstance();
        if (attributeManager != null) {
            totalCritChance += attributeManager.getCritChanceBonus(player);
        }

        ClassManager classManager = ClassManager.getInstance();
        if (classManager != null) {
            totalBaseDamage += classManager.getClassAttackBonus(player);
            totalBrutality += classManager.getBonusBrutality(player);
            totalCritChance += classManager.getBonusCritChance(player);
            totalCritDamage += classManager.getBonusCritDamage(player);
            totalArmorPen += classManager.getBonusArmorPen(player);
            if (classManager.suppressesCriticalHits(player)) {
                totalCritChance = 0.0;
            }
        }

        return new CombatStats(totalBaseDamage, totalBaseMultiplier, totalCritChance, totalCritDamage, totalBrutality, totalLifesteal, totalArmorPen);
    }

    /**
     * O(1) 极速获取玩家的真实完整战斗属性
     * 直接读取静态缓存，仅加上主手武器的实时属性
     */
    /**
     * 玩家裸手（空手）的基础攻击伤害常量。
     * 原版中 GENERIC_ATTACK_DAMAGE 的 base value 即为 1.0，
     * 武器附加的攻击力已通过 ItemStandardizer 写入 PDC，故不再读取原版属性。
     */
    private static final double PLAYER_BASE_ATTACK = 1.0;

    public static CombatStats getFullStats(Player player) {
        CombatStats cached = PlayerStatCache.getInstance().getCachedStats(player);
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) return cached;
        
        // 基础伤害 = 缓存的静态属性(防具/饰品/技能) + 玩家裸手基础 1.0
        double totalBaseDamage = cached.baseDamage() + PLAYER_BASE_ATTACK;
        double totalBaseMultiplier = cached.baseMultiplier();
        double totalCritChance = cached.critChance();
        double totalCritDamage = cached.critDamage();
        double totalBrutality = cached.brutality();
        double totalLifesteal = cached.lifesteal();
        double totalArmorPen = cached.armorPen();
        
        // 主手武器的所有属性完全从 PDC 读取，不再依赖原版 AttributeModifier
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        WeaponTemplateManager weaponTemplateManager = WeaponTemplateManager.getInstance();
        double mainMultiplier = weaponTemplateManager == null ? 1.0
                : weaponTemplateManager.getEquipmentStatMultiplier(player, mainHand, EquipmentSlot.HAND);
        double offMultiplier = weaponTemplateManager == null ? 0.0
                : weaponTemplateManager.getEquipmentStatMultiplier(player, offHand, EquipmentSlot.OFF_HAND);

        totalBaseDamage += pdc.getStat(mainHand, pdc.KEY_BASE_DAMAGE) * mainMultiplier;
        totalBaseMultiplier += pdc.getStat(mainHand, pdc.KEY_BASE_MULTIPLIER) * mainMultiplier;
        totalCritChance += pdc.getStat(mainHand, pdc.KEY_CRIT_CHANCE) * mainMultiplier;
        totalCritDamage += pdc.getStat(mainHand, pdc.KEY_CRIT_DAMAGE) * mainMultiplier;
        totalBrutality += pdc.getStat(mainHand, pdc.KEY_BRUTALITY) * mainMultiplier;
        totalLifesteal += pdc.getStat(mainHand, pdc.KEY_LIFESTEAL) * mainMultiplier;
        totalArmorPen += pdc.getStat(mainHand, pdc.KEY_ARMOR_PEN) * mainMultiplier;
        EnchantStatBundle mainEnchantStats = resolveEnchantStats(mainHand, player, EquipmentSlot.HAND);
        totalBaseDamage += mainEnchantStats.baseDamage() * mainMultiplier;
        totalBaseMultiplier += mainEnchantStats.baseMultiplier() * mainMultiplier;
        totalCritChance += mainEnchantStats.critChance() * mainMultiplier;
        totalCritDamage += mainEnchantStats.critDamage() * mainMultiplier;
        totalBrutality += mainEnchantStats.brutality() * mainMultiplier;
        totalLifesteal += mainEnchantStats.lifesteal() * mainMultiplier;
        totalArmorPen += mainEnchantStats.armorPen() * mainMultiplier;

        totalBaseDamage += pdc.getStat(offHand, pdc.KEY_BASE_DAMAGE) * offMultiplier;
        totalBaseMultiplier += pdc.getStat(offHand, pdc.KEY_BASE_MULTIPLIER) * offMultiplier;
        totalCritChance += pdc.getStat(offHand, pdc.KEY_CRIT_CHANCE) * offMultiplier;
        totalCritDamage += pdc.getStat(offHand, pdc.KEY_CRIT_DAMAGE) * offMultiplier;
        totalBrutality += pdc.getStat(offHand, pdc.KEY_BRUTALITY) * offMultiplier;
        totalLifesteal += pdc.getStat(offHand, pdc.KEY_LIFESTEAL) * offMultiplier;
        totalArmorPen += pdc.getStat(offHand, pdc.KEY_ARMOR_PEN) * offMultiplier;
        EnchantStatBundle offEnchantStats = resolveEnchantStats(offHand, player, EquipmentSlot.OFF_HAND);
        totalBaseDamage += offEnchantStats.baseDamage() * offMultiplier;
        totalBaseMultiplier += offEnchantStats.baseMultiplier() * offMultiplier;
        totalCritChance += offEnchantStats.critChance() * offMultiplier;
        totalCritDamage += offEnchantStats.critDamage() * offMultiplier;
        totalBrutality += offEnchantStats.brutality() * offMultiplier;
        totalLifesteal += offEnchantStats.lifesteal() * offMultiplier;
        totalArmorPen += offEnchantStats.armorPen() * offMultiplier;

        ClassManager classManager = ClassManager.getInstance();
        if (classManager != null && classManager.suppressesCriticalHits(player)) {
            totalCritChance = 0.0;
        }
        
        return new CombatStats(totalBaseDamage, totalBaseMultiplier, totalCritChance, totalCritDamage, totalBrutality, totalLifesteal, totalArmorPen);
    }

    private static EnchantStatBundle resolveEnchantStats(ItemStack item, Player player, EquipmentSlot slot) {
        EnchantStatResolver resolver = EnchantStatResolver.getInstance();
        return resolver == null ? EnchantStatBundle.empty() : resolver.resolveCombatStats(item, player, slot);
    }
}
