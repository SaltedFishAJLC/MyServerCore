package com.servercore.combat.creature;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Set;

public final class CreatureTagService {

    private static CreatureTagService instance;

    private final CreatureTagRegistry registry;

    public CreatureTagService(Plugin plugin) {
        instance = this;
        this.registry = new CreatureTagRegistry(plugin);
    }

    public static CreatureTagService getInstance() {
        return instance;
    }

    public void reload() {
        registry.reload();
    }

    public CreatureTagProfile getProfile(LivingEntity entity) {
        if (entity instanceof Player) {
            return new CreatureTagProfile(CreatureMainTag.HUMANOID, Set.of());
        }
        return entity == null ? new CreatureTagProfile(CreatureMainTag.ABERRANT, Set.of()) : registry.getProfile(entity.getType());
    }

    public CreatureMainTag getMainTag(LivingEntity entity) {
        return getProfile(entity).mainTag();
    }

    public Set<CreatureTraitTag> getTraitTags(LivingEntity entity) {
        return getProfile(entity).traits();
    }

    public boolean hasMainTag(LivingEntity entity, CreatureMainTag tag) {
        return getMainTag(entity) == tag;
    }

    public boolean hasTraitTag(LivingEntity entity, CreatureTraitTag tag) {
        return getTraitTags(entity).contains(tag);
    }

    public Component renderTagPrefix(LivingEntity entity) {
        return registry.renderer().render(getProfile(entity));
    }
}
