package com.servercore.combat.integration;

import com.servercore.combat.creature.CreatureTagService;
import org.bukkit.entity.LivingEntity;

public final class SlayerCategoryResolver {

    private SlayerCategoryResolver() {
    }

    public static SlayerCategory resolveMainCategory(LivingEntity entity) {
        CreatureTagService service = CreatureTagService.getInstance();
        if (service == null) {
            return SlayerCategory.ABERRANT;
        }
        return SlayerCategory.valueOf(service.getMainTag(entity).name());
    }
}
