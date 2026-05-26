package com.servercore.combat.creature;

import java.util.Set;

public record CreatureTagProfile(CreatureMainTag mainTag, Set<CreatureTraitTag> traits) {
    public CreatureTagProfile {
        mainTag = mainTag == null ? CreatureMainTag.ABERRANT : mainTag;
        traits = traits == null ? Set.of() : Set.copyOf(traits);
    }

    public String toStorageString() {
        if (traits.isEmpty()) {
            return "main:" + mainTag.name();
        }
        return "main:" + mainTag.name() + ",traits:" + String.join("|", traits.stream()
                .sorted(CreatureTraitTag.DISPLAY_ORDER)
                .map(Enum::name)
                .toList());
    }
}
