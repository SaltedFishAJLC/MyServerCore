package com.servercore.fishing;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;

import java.util.Set;

public record FishingContext(
        Player player,
        FishHook hook,
        Location hookLocation,
        World world,
        Biome biome,
        boolean openWater,
        boolean raining,
        boolean thundering,
        boolean rainInfluenced,
        boolean skyInfluenced,
        long worldTime,
        long fullTime,
        long gameDay,
        Set<String> biomeTags,
        Set<String> environmentTags
) {
    public FishingContext {
        biomeTags = biomeTags == null ? Set.of() : Set.copyOf(biomeTags);
        environmentTags = environmentTags == null ? Set.of() : Set.copyOf(environmentTags);
    }

    public FishingContext withEnvironmentTags(Set<String> tags) {
        return new FishingContext(
                player,
                hook,
                hookLocation,
                world,
                biome,
                openWater,
                raining,
                thundering,
                rainInfluenced,
                skyInfluenced,
                worldTime,
                fullTime,
                gameDay,
                biomeTags,
                tags
        );
    }
}
