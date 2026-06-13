package com.servercore.enchant;

import com.servercore.combat.creature.CreatureMainTag;
import com.servercore.combat.creature.CreatureTagService;
import com.servercore.combat.creature.CreatureTraitTag;
import org.bukkit.entity.LivingEntity;

import java.util.Set;

public record EnchantTargetSpec(
        Set<CreatureMainTag> mainTags,
        Set<CreatureTraitTag> traitTags,
        boolean boss
) {

    public static EnchantTargetSpec empty() {
        return new EnchantTargetSpec(Set.of(), Set.of(), false);
    }

    public boolean isEmpty() {
        return mainTags.isEmpty() && traitTags.isEmpty() && !boss;
    }

    public boolean matchesMainTag(LivingEntity target) {
        CreatureTagService tagService = CreatureTagService.getInstance();
        return tagService != null && target != null && mainTags.contains(tagService.getMainTag(target));
    }

    public boolean matchesTraitTag(LivingEntity target) {
        CreatureTagService tagService = CreatureTagService.getInstance();
        if (tagService == null || target == null) {
            return false;
        }
        for (CreatureTraitTag tag : tagService.getTraitTags(target)) {
            if (traitTags.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    public boolean matchesBoss(LivingEntity target) {
        if (!boss || target == null) {
            return false;
        }
        if (target.getScoreboardTags().contains("servercore_boss")) {
            return true;
        }
        CreatureTagService tagService = CreatureTagService.getInstance();
        return tagService != null && tagService.hasTraitTag(target, CreatureTraitTag.BOSS);
    }

    public boolean matchesAny(LivingEntity target) {
        return matchesMainTag(target) || matchesTraitTag(target) || matchesBoss(target);
    }
}
