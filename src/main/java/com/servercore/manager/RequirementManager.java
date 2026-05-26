package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.skill.Skills;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 装备与饰品准入判定引擎 骨架
 */
public class RequirementManager implements Listener {

    private static RequirementManager instance;

    private final ServerCorePlugin plugin;

    public RequirementManager(ServerCorePlugin plugin) {
        this.plugin = plugin;
        instance = this;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public static RequirementManager getInstance() {
        return instance;
    }

    /**
     * 判断玩家是否满足该物品的使用条件
     * 要求：
     * 读取 PDC 里的 req_skill, req_slayer, req_dungeon 等标签。
     * 调用外部依赖（如 AuraSkills API）判断玩家的实际等级是否达标。
     */
    public boolean meetsRequirement(Player player, ItemStack item) {
        return getMissingRequirements(player, item).isEmpty();
    }

    /**
     * 发送拒绝使用的提示信息（如果等级不够）
     */
    public void sendRequirementDenyMessage(Player player, ItemStack item) {
        List<String> missing = getMissingRequirements(player, item);
        if (missing.isEmpty()) return;

        player.sendMessage(ServerCorePlugin.getMiniMessage().deserialize("<red>无法使用该物品，未满足准入条件：</red>"));
        for (String line : missing) {
            player.sendMessage(ServerCorePlugin.getMiniMessage().deserialize("<gray>- " + line + "</gray>"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHeldItemChange(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItem(event.getNewSlot());
        if (item == null || item.getType().isAir()) return;

        if (!meetsRequirement(player, item)) {
            event.setCancelled(true);
            sendRequirementDenyMessage(player, item);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        ItemStack candidate = getArmorEquipCandidate(event, player);
        if (candidate == null || candidate.getType().isAir()) return;

        if (!meetsRequirement(player, candidate)) {
            event.setCancelled(true);
            sendRequirementDenyMessage(player, candidate);
        }
    }

    private List<String> getMissingRequirements(Player player, ItemStack item) {
        List<String> missing = new ArrayList<>();
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return missing;

        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) return missing;

        PersistentDataContainer itemPdc = item.getItemMeta().getPersistentDataContainer();

        String skillRequirement = itemPdc.get(pdc.KEY_REQ_SKILL, PersistentDataType.STRING);
        if (skillRequirement != null && !skillRequirement.isBlank()) {
            for (Requirement requirement : parseRequirementList(skillRequirement, "fighting")) {
                int current = getSkillLevel(player, requirement.key());
                if (current < requirement.level()) {
                    missing.add("<yellow>" + requirement.key() + "</yellow> 技能需要 <white>" + requirement.level() + "</white>，当前 <red>" + current + "</red>");
                }
            }
        }

        for (Requirement requirement : readRequirement(itemPdc, pdc.KEY_REQ_SLAYER, "general")) {
            int current = getProgressLevel(player, "slayer", requirement.key(), pdc.KEY_PLAYER_SLAYER_LEVEL);
            if (current < requirement.level()) {
                missing.add("<light_purple>" + requirement.key() + "</light_purple> 猎手需要 <white>" + requirement.level() + "</white>，当前 <red>" + current + "</red>");
            }
        }

        for (Requirement requirement : readRequirement(itemPdc, pdc.KEY_REQ_DUNGEON, "general")) {
            int current = getProgressLevel(player, "dungeon", requirement.key(), pdc.KEY_PLAYER_DUNGEON_LEVEL);
            if (current < requirement.level()) {
                missing.add("<aqua>" + requirement.key() + "</aqua> 副本进度需要 <white>" + requirement.level() + "</white>，当前 <red>" + current + "</red>");
            }
        }

        return missing;
    }

    private List<Requirement> readRequirement(PersistentDataContainer container, NamespacedKey key, String defaultKey) {
        String text = container.get(key, PersistentDataType.STRING);
        if (text != null && !text.isBlank()) {
            return parseRequirementList(text, defaultKey);
        }

        Integer integer = container.get(key, PersistentDataType.INTEGER);
        if (integer != null && integer > 0) {
            return List.of(new Requirement(defaultKey, integer));
        }

        return List.of();
    }

    private List<Requirement> parseRequirementList(String raw, String defaultKey) {
        List<Requirement> requirements = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;

            String key = defaultKey;
            String levelText = trimmed;
            int separator = Math.max(trimmed.indexOf(':'), trimmed.indexOf('='));
            if (separator >= 0) {
                key = trimmed.substring(0, separator).trim().toLowerCase(Locale.ROOT);
                levelText = trimmed.substring(separator + 1).trim();
            }

            try {
                int level = Integer.parseInt(levelText);
                if (level > 0) {
                    requirements.add(new Requirement(key, level));
                }
            } catch (NumberFormatException ignored) {
                plugin.getLogger().warning("Invalid requirement value: " + raw);
            }
        }
        return requirements;
    }

    private int getSkillLevel(Player player, String skillName) {
        AuraSkillsApi api = AuraSkillsApi.get();
        if (api == null || api.getUser(player.getUniqueId()) == null) {
            return 0;
        }

        try {
            Skills skill = Skills.valueOf(skillName.toUpperCase(Locale.ROOT));
            return api.getUser(player.getUniqueId()).getSkillLevel(skill);
        } catch (IllegalArgumentException ignored) {
            plugin.getLogger().warning("Unknown AuraSkills skill in requirement: " + skillName);
            return 0;
        }
    }

    private int getProgressLevel(Player player, String namespace, String keyName, NamespacedKey generalKey) {
        PersistentDataContainer container = player.getPersistentDataContainer();
        Integer general = container.get(generalKey, PersistentDataType.INTEGER);

        if (keyName.equals("general")) {
            return general == null ? 0 : general;
        }

        NamespacedKey scopedKey = new NamespacedKey(plugin, namespace + "_" + keyName.toLowerCase(Locale.ROOT) + "_level");
        Integer scoped = container.get(scopedKey, PersistentDataType.INTEGER);
        return scoped == null ? (general == null ? 0 : general) : scoped;
    }

    private ItemStack getArmorEquipCandidate(InventoryClickEvent event, Player player) {
        if (event.getSlotType() == InventoryType.SlotType.ARMOR) {
            if (event.getClick() == ClickType.NUMBER_KEY) {
                int hotbarButton = event.getHotbarButton();
                if (hotbarButton >= 0) {
                    return player.getInventory().getItem(hotbarButton);
                }
            }
            return event.getCursor();
        }

        ItemStack current = event.getCurrentItem();
        if (event.getClick().isShiftClick() && isArmor(current)) {
            return current;
        }

        return null;
    }

    private boolean isArmor(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;

        Material material = item.getType();
        String name = material.name();
        return name.endsWith("_HELMET")
                || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS");
    }

    private record Requirement(String key, int level) {
    }
}
