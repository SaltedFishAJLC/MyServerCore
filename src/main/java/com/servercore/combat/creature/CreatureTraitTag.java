package com.servercore.combat.creature;

import java.util.Comparator;

public enum CreatureTraitTag {
    FIRE,
    FROST,
    VOID,
    FLYING,
    BOSS;

    public static final Comparator<CreatureTraitTag> DISPLAY_ORDER = Comparator.comparingInt(CreatureTraitTag::displayPriority);

    public int displayPriority() {
        return switch (this) {
            case FIRE -> 0;
            case FROST -> 1;
            case VOID -> 2;
            case FLYING -> 3;
            case BOSS -> 4;
        };
    }
}
