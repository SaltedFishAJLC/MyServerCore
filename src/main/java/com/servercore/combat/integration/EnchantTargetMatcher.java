package com.servercore.combat.integration;

import com.servercore.combat.creature.CreatureMainTag;
import com.servercore.combat.creature.CreatureTagService;
import com.servercore.manager.EnchantManager;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

public final class EnchantTargetMatcher {

    private static final double DAMAGE_PER_LEVEL = 0.05;
    private static final Map<CreatureMainTag, String[]> ENCHANT_IDS = new EnumMap<>(CreatureMainTag.class);

    static {
        ENCHANT_IDS.put(CreatureMainTag.UNDEAD, new String[]{"undead_slayer"});
        ENCHANT_IDS.put(CreatureMainTag.SKELETON, new String[]{"skeleton_slayer"});
        ENCHANT_IDS.put(CreatureMainTag.ANIMAL, new String[]{"animal_slayer"});
        ENCHANT_IDS.put(CreatureMainTag.ARTHROPOD, new String[]{"arthropod_slayer"});
        ENCHANT_IDS.put(CreatureMainTag.HUMANOID, new String[]{"humanoid_slayer"});
        ENCHANT_IDS.put(CreatureMainTag.GELATINOUS, new String[]{"gelatinous_slayer", "slime_slayer"});
        ENCHANT_IDS.put(CreatureMainTag.CONSTRUCT, new String[]{"construct_breaker"});
        ENCHANT_IDS.put(CreatureMainTag.GHOST, new String[]{"exorcism", "ghost_slayer"});
        ENCHANT_IDS.put(CreatureMainTag.GIANT, new String[]{"giant_hunter"});
        ENCHANT_IDS.put(CreatureMainTag.ABERRANT, new String[]{"aberrant_hunter"});
    }

    private EnchantTargetMatcher() {
    }

    public static double resolveHighestMultiplier(ItemStack weapon, LivingEntity target) {
        int level = resolveHighestLevel(weapon, target);
        return level <= 0 ? 1.0 : 1.0 + level * DAMAGE_PER_LEVEL;
    }

    public static int resolveHighestLevel(ItemStack weapon, LivingEntity target) {
        EnchantManager enchantManager = EnchantManager.getInstance();
        CreatureTagService creatureTagService = CreatureTagService.getInstance();
        if (enchantManager == null || creatureTagService == null || weapon == null || target == null) {
            return 0;
        }

        CreatureMainTag mainTag = creatureTagService.getMainTag(target);
        int best = 0;
        for (String enchantId : ENCHANT_IDS.getOrDefault(mainTag, new String[0])) {
            best = Math.max(best, enchantManager.getCustomEnchantLevel(weapon, normalize(enchantId)));
        }
        return best;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }
}
