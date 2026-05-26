package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.event.skill.SkillLevelUpEvent;
import dev.aurelium.auraskills.api.event.user.UserLoadEvent;
import dev.aurelium.auraskills.api.skill.Skill;
import dev.aurelium.auraskills.api.skill.Skills;
import dev.aurelium.auraskills.api.stat.Stats;
import dev.aurelium.auraskills.api.user.SkillsUser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Locale;

public class AuraSkillsBridge implements Listener {

    private static final double COMBAT_MULTIPLIER_PER_LEVEL = 0.005;

    private static AuraSkillsBridge instance;

    private final ServerCorePlugin plugin;

    public AuraSkillsBridge(ServerCorePlugin plugin) {
        this.plugin = plugin;
        instance = this;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public static AuraSkillsBridge getInstance() {
        return instance;
    }

    public int getToughnessBonus(Player player) {
        return getStatPoints(player, Stats.HEALTH);
    }

    public int getAgilityBonus(Player player) {
        return getStatPoints(player, Stats.TOUGHNESS);
    }

    public int getIntelligenceBonus(Player player) {
        return getStatPoints(player, Stats.WISDOM);
    }

    public int getWillpowerBonus(Player player) {
        return getStatPoints(player, Stats.REGENERATION);
    }

    public int getLuckBonus(Player player) {
        return getStatPoints(player, Stats.LUCK);
    }

    public AttributeManager.AttributeSnapshot getAttributeBonuses(Player player) {
        return new AttributeManager.AttributeSnapshot(
                getToughnessBonus(player),
                getAgilityBonus(player),
                getIntelligenceBonus(player),
                getWillpowerBonus(player),
                getLuckBonus(player)
        );
    }

    public double getCombatSkillMultiplier(Player player, String skillType) {
        Skills skill = resolveSkill(skillType);
        if (skill == null) {
            return 0.0;
        }

        return getSkillLevel(player, skill) * COMBAT_MULTIPLIER_PER_LEVEL;
    }

    public int getSkillLevel(Player player, Skills skill) {
        SkillsUser user = getUser(player);
        return user == null ? 0 : Math.max(0, user.getSkillLevel(skill));
    }

    public double getSkillXp(Player player, Skills skill) {
        SkillsUser user = getUser(player);
        return user == null ? 0.0 : Math.max(0.0, user.getSkillXp(skill));
    }

    public void addSkillXp(Player player, Skill skill, double amount) {
        if (amount <= 0.0 || skill == null) {
            return;
        }

        SkillsUser user = getUser(player);
        if (user != null) {
            user.addSkillXp(skill, amount);
        }
    }

    public void addSkillXpRaw(Player player, Skill skill, double amount) {
        if (amount <= 0.0 || skill == null) {
            return;
        }

        SkillsUser user = getUser(player);
        if (user != null) {
            user.addSkillXpRaw(skill, amount);
        }
    }

    public int getXpRequiredForNextLevel(Player player, Skills skill) {
        AuraSkillsApi api = getApi();
        if (api == null) {
            return 0;
        }

        int currentLevel = getSkillLevel(player, skill);
        int maxLevel = getMaxLevel(skill);
        if (maxLevel > 0 && currentLevel >= maxLevel) {
            return 0;
        }

        return Math.max(0, api.getXpRequirements().getXpRequired(skill, currentLevel + 1));
    }

    public double getSkillProgress(Player player, Skills skill) {
        int required = getXpRequiredForNextLevel(player, skill);
        if (required <= 0) {
            return 1.0;
        }

        return clamp(getSkillXp(player, skill) / required, 0.0, 1.0);
    }

    public int getPowerLevel(Player player) {
        SkillsUser user = getUser(player);
        return user == null ? 0 : Math.max(0, user.getPowerLevel());
    }

    public int getMaxLevel(Skills skill) {
        try {
            return Math.max(0, skill.getMaxLevel());
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    public SkillsUser getUser(Player player) {
        if (player == null) {
            return null;
        }

        AuraSkillsApi api = getApi();
        if (api == null) {
            return null;
        }

        SkillsUser user = api.getUser(player.getUniqueId());
        return user != null && user.isLoaded() ? user : null;
    }

    private int getStatPoints(Player player, Stats stat) {
        SkillsUser user = getUser(player);
        if (user == null) {
            return 0;
        }

        return (int) Math.round(Math.max(0.0, user.getBaseStatLevel(stat)));
    }

    private AuraSkillsApi getApi() {
        try {
            return AuraSkillsApi.get();
        } catch (IllegalStateException | NoClassDefFoundError exception) {
            return null;
        }
    }

    private Skills resolveSkill(String skillType) {
        if (skillType == null || skillType.isBlank()) {
            return null;
        }

        String normalized = skillType.trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');

        return switch (normalized) {
            case "MELEE", "SWORD", "SWORDS", "FIGHT", "FIGHTING" -> Skills.FIGHTING;
            case "RANGED", "RANGE", "BOW", "ARCHER", "ARCHERY" -> Skills.ARCHERY;
            case "MAGIC", "MAGE", "SORCERY" -> Skills.SORCERY;
            default -> {
                try {
                    yield Skills.valueOf(normalized);
                } catch (IllegalArgumentException exception) {
                    yield null;
                }
            }
        };
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAuraSkillLevelUp(SkillLevelUpEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> refreshPlayer(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAuraUserLoad(UserLoadEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> refreshPlayer(event.getPlayer()));
    }

    private void refreshPlayer(Player player) {
        PlayerStatCache statCache = PlayerStatCache.getInstance();
        if (statCache != null) {
            statCache.updateCache(player);
            return;
        }

        AttributeManager attributeManager = AttributeManager.getInstance();
        if (attributeManager != null) {
            attributeManager.refreshPlayer(player);
        }
    }
}
