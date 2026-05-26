package com.servercore.combat.resistance;

import com.servercore.combat.creature.CreatureTagProfile;
import com.servercore.combat.creature.CreatureTagService;
import com.servercore.combat.damage.DamageTag;
import com.servercore.combat.status.StatusType;
import com.servercore.manager.PDCManager;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataType;

import java.util.Set;

public final class ResistanceResolver {

    private final CreatureTagService creatureTagService;
    private final TagRuleRegistry ruleRegistry;

    public ResistanceResolver(CreatureTagService creatureTagService, TagRuleRegistry ruleRegistry) {
        this.creatureTagService = creatureTagService;
        this.ruleRegistry = ruleRegistry;
    }

    public double resolveDamageMultiplier(LivingEntity target, Set<DamageTag> damageTags) {
        if (target == null || damageTags == null || damageTags.isEmpty()) {
            return 1.0;
        }

        double multiplier = 1.0;
        boolean touched = false;
        for (TagRuleRegistry.TagRuleSet ruleSet : collectRules(target)) {
            for (DamageTag tag : damageTags) {
                DamageRule rule = ruleSet.damageRules().get(tag);
                if (rule == null) {
                    continue;
                }
                touched = true;
                if (rule.damageMultiplier() <= 0.0) {
                    return 0.0;
                }
                multiplier *= rule.damageMultiplier();
            }
        }
        return touched ? clamp(multiplier, 0.25, 2.0) : 1.0;
    }

    public double resolveStatusApplyMultiplier(LivingEntity target, StatusType statusType) {
        if (target == null || statusType == null) {
            return 1.0;
        }
        double multiplier = 1.0;
        for (TagRuleRegistry.TagRuleSet ruleSet : collectRules(target)) {
            StatusRule rule = ruleSet.statusRules().get(statusType);
            if (rule == null) {
                continue;
            }
            if (rule.applyMultiplier() <= 0.0) {
                return 0.0;
            }
            multiplier *= rule.applyMultiplier();
        }
        return Math.max(0.0, multiplier);
    }

    public double resolveStatusDamageMultiplier(LivingEntity target, StatusType statusType) {
        if (target == null || statusType == null) {
            return 1.0;
        }
        double multiplier = 1.0;
        boolean touched = false;
        for (TagRuleRegistry.TagRuleSet ruleSet : collectRules(target)) {
            StatusRule rule = ruleSet.statusRules().get(statusType);
            if (rule == null) {
                continue;
            }
            touched = true;
            if (rule.damageMultiplier() <= 0.0) {
                return 0.0;
            }
            multiplier *= rule.damageMultiplier();
        }
        return touched ? clamp(multiplier, 0.25, 2.0) : 1.0;
    }

    public ControlRule resolveControlRule(LivingEntity target, String controlKey) {
        ControlRule result = ControlRule.DEFAULT;
        String normalized = TagRuleRegistry.normalizeControlKey(controlKey);
        for (TagRuleRegistry.TagRuleSet ruleSet : collectRules(target)) {
            result = result.merge(ruleSet.controlRules().get(normalized));
        }
        return result;
    }

    public DotCapRule resolveDotCap(LivingEntity target) {
        DotCapRule result = DotCapRule.NONE;
        for (TagRuleRegistry.TagRuleSet ruleSet : collectRules(target)) {
            result = result.merge(ruleSet.dotCapRule());
        }
        return result;
    }

    public double resolveDotCapDamage(LivingEntity target, double damagePerSecond) {
        DotCapRule rule = resolveDotCap(target);
        if (rule.maxPercentHealthPerSecond() <= 0.0) {
            return damagePerSecond;
        }
        return Math.min(damagePerSecond, getMaxHealth(target) * rule.maxPercentHealthPerSecond());
    }

    private Iterable<TagRuleRegistry.TagRuleSet> collectRules(LivingEntity target) {
        CreatureTagProfile profile = creatureTagService.getProfile(target);
        return ruleRegistry.collectRules(profile.mainTag(), profile.traits());
    }

    private double getMaxHealth(LivingEntity target) {
        PDCManager pdc = PDCManager.getInstance();
        if (pdc != null) {
            Double virtualMax = target.getPersistentDataContainer().get(pdc.KEY_MOB_VIRTUAL_MAX_HEALTH, PersistentDataType.DOUBLE);
            if (virtualMax != null && virtualMax > 0.0) {
                return virtualMax;
            }
        }

        AttributeInstance maxHealth = target.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        return maxHealth == null ? Math.max(1.0, target.getHealth()) : Math.max(1.0, maxHealth.getValue());
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
