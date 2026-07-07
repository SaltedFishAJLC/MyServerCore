package com.servercore.fishing;

import com.servercore.manager.FishingManager;

import java.util.List;
import java.util.Set;

public record FishingEnvironmentResult(
        FishingManager.FishingStatContribution bonus,
        Set<String> environmentTags,
        List<String> matchedRuleIds
) {
    public FishingEnvironmentResult {
        bonus = bonus == null ? FishingManager.FishingStatContribution.empty() : bonus;
        environmentTags = environmentTags == null ? Set.of() : Set.copyOf(environmentTags);
        matchedRuleIds = matchedRuleIds == null ? List.of() : List.copyOf(matchedRuleIds);
    }

    public static FishingEnvironmentResult empty(Set<String> tags) {
        return new FishingEnvironmentResult(FishingManager.FishingStatContribution.empty(), tags, List.of());
    }
}
