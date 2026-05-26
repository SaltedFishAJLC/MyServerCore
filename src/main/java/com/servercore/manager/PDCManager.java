package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public class PDCManager {

    private static PDCManager instance;
    private final Plugin plugin;

    public final NamespacedKey KEY_BASE_DAMAGE;
    public final NamespacedKey KEY_BASE_MULTIPLIER;
    public final NamespacedKey KEY_CRIT_CHANCE;
    public final NamespacedKey KEY_CRIT_DAMAGE;
    public final NamespacedKey KEY_BRUTALITY;
    public final NamespacedKey KEY_ARMOR_PEN;
    public final NamespacedKey KEY_BASE_ARMOR;
    public final NamespacedKey KEY_ATTACK_SPEED_BONUS;
    public final NamespacedKey KEY_SHIELD_BLOCK_THRESHOLD;
    public final NamespacedKey KEY_SHIELD_EFFECTIVE_BLOCK;
    public final NamespacedKey KEY_SHIELD_COOLDOWN_SECONDS;

    public final NamespacedKey KEY_ATTR_TOUGHNESS;
    public final NamespacedKey KEY_ATTR_AGILITY;
    public final NamespacedKey KEY_ATTR_INTELLIGENCE;
    public final NamespacedKey KEY_ATTR_WILLPOWER;
    public final NamespacedKey KEY_ATTR_LUCK;

    public final NamespacedKey KEY_TOOL_FORTUNE;
    public final NamespacedKey KEY_COLLECTION_FORTUNE;
    public final NamespacedKey KEY_FORAGING_FORTUNE;
    public final NamespacedKey KEY_FARMING_FORTUNE;
    public final NamespacedKey KEY_EXCAVATION_FORTUNE;
    public final NamespacedKey KEY_MINING_FORTUNE;
    public final NamespacedKey KEY_TOOL_SWEEP;
    public final NamespacedKey KEY_COLLECTION_SWEEP;
    public final NamespacedKey KEY_FORAGING_SWEEP;
    public final NamespacedKey KEY_FARMING_SWEEP;
    public final NamespacedKey KEY_EXCAVATION_SWEEP;
    public final NamespacedKey KEY_TOOL_SPREAD;
    public final NamespacedKey KEY_MINING_SPREAD;
    public final NamespacedKey KEY_TOOL_MINING_SPEED;
    public final NamespacedKey KEY_BREAKING_POWER;
    public final NamespacedKey KEY_PURITY;
    public final NamespacedKey KEY_MINING_PURITY;
    public final NamespacedKey KEY_FISHING_SPEED;
    public final NamespacedKey KEY_SEA_CREATURE_CHANCE;
    public final NamespacedKey KEY_TREASURE_CHANCE;
    public final NamespacedKey KEY_BOUNTY;
    public final NamespacedKey KEY_OVERBLOOM;

    public final NamespacedKey KEY_ACC_TYPE;
    public final NamespacedKey KEY_ITEM_ID;
    public final NamespacedKey KEY_ITEM_SCALE_VERSION;
    public final NamespacedKey KEY_ITEM_RARITY;
    public final NamespacedKey KEY_ITEM_ORIGINAL_NAME;
    public final NamespacedKey KEY_ITEM_REFORGE_ID;
    public final NamespacedKey KEY_ITEM_REFORGE_PREFIX;
    public final NamespacedKey KEY_ITEM_REFORGE_STATS;
    public final NamespacedKey KEY_ITEM_SOCKET_TYPES;
    public final NamespacedKey KEY_ITEM_SOCKET_GEMS;
    public final NamespacedKey KEY_ITEM_CUSTOM_ENCHANTS;
    public final NamespacedKey KEY_ITEM_ABILITIES;
    public final NamespacedKey KEY_ITEM_STORY_LORE;
    public final NamespacedKey KEY_WEAPON_TEMPLATE;
    public final NamespacedKey KEY_WEAPON_HAND_RULE;
    public final NamespacedKey KEY_ITEM_OVERRIDE_ID;

    public final NamespacedKey KEY_PLAYER_CLASS;
    public final NamespacedKey KEY_PLAYER_CLASS_MAIN;
    public final NamespacedKey KEY_PLAYER_CLASS_SUB;
    public final NamespacedKey KEY_PLAYER_CLASS_SWITCH_AT;

    public final NamespacedKey KEY_SOUL_CONTAINER_ID;
    public final NamespacedKey KEY_SOUL_OWNER_UUID;

    public final NamespacedKey KEY_MOB_POWER_LEVEL;
    public final NamespacedKey KEY_MOB_TAGS;
    public final NamespacedKey KEY_MOB_DAMAGE_REDUCTION;
    public final NamespacedKey KEY_MOB_ATTACK_DAMAGE;
    public final NamespacedKey KEY_MOB_MAGIC_RESIST;
    public final NamespacedKey KEY_MOB_SCALING_MOD;
    public final NamespacedKey KEY_MOB_VIRTUAL_HEALTH;
    public final NamespacedKey KEY_MOB_VIRTUAL_MAX_HEALTH;
    public final NamespacedKey KEY_CUSTOM_MOB_ID;
    public final NamespacedKey KEY_HOLOGRAM_ID;

    public final NamespacedKey KEY_REQ_SKILL;
    public final NamespacedKey KEY_REQ_SLAYER;
    public final NamespacedKey KEY_REQ_DUNGEON;
    public final NamespacedKey KEY_PLAYER_SLAYER_LEVEL;
    public final NamespacedKey KEY_PLAYER_DUNGEON_LEVEL;

    public PDCManager(ServerCorePlugin plugin) {
        instance = this;
        this.plugin = plugin;
        this.KEY_BASE_DAMAGE = key("base_damage");
        this.KEY_BASE_MULTIPLIER = key("base_multiplier");
        this.KEY_CRIT_CHANCE = key("crit_chance");
        this.KEY_CRIT_DAMAGE = key("crit_damage");
        this.KEY_BRUTALITY = key("brutality");
        this.KEY_ARMOR_PEN = key("armor_pen");
        this.KEY_BASE_ARMOR = key("base_armor");
        this.KEY_ATTACK_SPEED_BONUS = key("attack_speed_bonus");
        this.KEY_SHIELD_BLOCK_THRESHOLD = key("shield_block_threshold");
        this.KEY_SHIELD_EFFECTIVE_BLOCK = key("shield_effective_block");
        this.KEY_SHIELD_COOLDOWN_SECONDS = key("shield_cooldown_seconds");
        this.KEY_ATTR_TOUGHNESS = key("attr_toughness");
        this.KEY_ATTR_AGILITY = key("attr_agility");
        this.KEY_ATTR_INTELLIGENCE = key("attr_intelligence");
        this.KEY_ATTR_WILLPOWER = key("attr_willpower");
        this.KEY_ATTR_LUCK = key("attr_luck");
        this.KEY_TOOL_FORTUNE = key("tool_fortune");
        this.KEY_COLLECTION_FORTUNE = key("collection_fortune");
        this.KEY_FORAGING_FORTUNE = key("foraging_fortune");
        this.KEY_FARMING_FORTUNE = key("farming_fortune");
        this.KEY_EXCAVATION_FORTUNE = key("excavation_fortune");
        this.KEY_MINING_FORTUNE = key("mining_fortune");
        this.KEY_TOOL_SWEEP = key("tool_sweep");
        this.KEY_COLLECTION_SWEEP = key("collection_sweep");
        this.KEY_FORAGING_SWEEP = key("foraging_sweep");
        this.KEY_FARMING_SWEEP = key("farming_sweep");
        this.KEY_EXCAVATION_SWEEP = key("excavation_sweep");
        this.KEY_TOOL_SPREAD = key("tool_spread");
        this.KEY_MINING_SPREAD = key("mining_spread");
        this.KEY_TOOL_MINING_SPEED = key("tool_mining_speed");
        this.KEY_BREAKING_POWER = key("breaking_power");
        this.KEY_PURITY = key("purity");
        this.KEY_MINING_PURITY = key("mining_purity");
        this.KEY_FISHING_SPEED = key("fishing_speed");
        this.KEY_SEA_CREATURE_CHANCE = key("sea_creature_chance");
        this.KEY_TREASURE_CHANCE = key("treasure_chance");
        this.KEY_BOUNTY = key("bounty");
        this.KEY_OVERBLOOM = key("overbloom");
        this.KEY_ACC_TYPE = key("acc_type");
        this.KEY_ITEM_ID = key("item_id");
        this.KEY_ITEM_SCALE_VERSION = key("item_scale_version");
        this.KEY_ITEM_RARITY = key("item_rarity");
        this.KEY_ITEM_ORIGINAL_NAME = key("item_original_name");
        this.KEY_ITEM_REFORGE_ID = key("item_reforge_id");
        this.KEY_ITEM_REFORGE_PREFIX = key("item_reforge_prefix");
        this.KEY_ITEM_REFORGE_STATS = key("item_reforge_stats");
        this.KEY_ITEM_SOCKET_TYPES = key("item_socket_types");
        this.KEY_ITEM_SOCKET_GEMS = key("item_socket_gems");
        this.KEY_ITEM_CUSTOM_ENCHANTS = key("item_custom_enchants");
        this.KEY_ITEM_ABILITIES = key("item_abilities");
        this.KEY_ITEM_STORY_LORE = key("item_story_lore");
        this.KEY_WEAPON_TEMPLATE = key("weapon_template");
        this.KEY_WEAPON_HAND_RULE = key("weapon_hand_rule");
        this.KEY_ITEM_OVERRIDE_ID = key("item_override_id");
        this.KEY_PLAYER_CLASS = key("player_class");
        this.KEY_PLAYER_CLASS_MAIN = key("player_class_main");
        this.KEY_PLAYER_CLASS_SUB = key("player_class_sub");
        this.KEY_PLAYER_CLASS_SWITCH_AT = key("player_class_switch_at");
        this.KEY_SOUL_CONTAINER_ID = key("soul_container_id");
        this.KEY_SOUL_OWNER_UUID = key("soul_owner_uuid");
        this.KEY_MOB_POWER_LEVEL = key("mob_power_level");
        this.KEY_MOB_TAGS = key("mob_tags");
        this.KEY_MOB_DAMAGE_REDUCTION = key("mob_damage_reduction");
        this.KEY_MOB_ATTACK_DAMAGE = key("mob_attack_damage");
        this.KEY_MOB_MAGIC_RESIST = key("mob_magic_resist");
        this.KEY_MOB_SCALING_MOD = key("mob_scaling_mod");
        this.KEY_MOB_VIRTUAL_HEALTH = key("mob_virtual_health");
        this.KEY_MOB_VIRTUAL_MAX_HEALTH = key("mob_virtual_max_health");
        this.KEY_CUSTOM_MOB_ID = key("custom_mob_id");
        this.KEY_HOLOGRAM_ID = key("hologram_id");
        this.KEY_REQ_SKILL = key("req_skill");
        this.KEY_REQ_SLAYER = key("req_slayer");
        this.KEY_REQ_DUNGEON = key("req_dungeon");
        this.KEY_PLAYER_SLAYER_LEVEL = key("player_slayer_level");
        this.KEY_PLAYER_DUNGEON_LEVEL = key("player_dungeon_level");
    }

    public static PDCManager getInstance() {
        return instance;
    }

    public void setStat(ItemStack item, NamespacedKey key, double value) {
        if (item == null || item.getType().isAir()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        meta.getPersistentDataContainer().set(key, PersistentDataType.DOUBLE, value);
        item.setItemMeta(meta);
        refreshFormat(item);
    }

    public double getStat(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) return 0.0;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.getOrDefault(key, PersistentDataType.DOUBLE, 0.0);
    }

    public void setString(ItemStack item, NamespacedKey key, String value) {
        if (item == null || item.getType().isAir()) return;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        if (value == null) {
            pdc.remove(key);
        } else {
            pdc.set(key, PersistentDataType.STRING, value);
        }
        item.setItemMeta(meta);
        if (key.equals(KEY_ITEM_RARITY)) {
            ReforgeManager reforgeManager = ReforgeManager.getInstance();
            if (reforgeManager != null && reforgeManager.refreshReforge(item)) {
                return;
            }
        }
        refreshFormat(item);
    }

    public String getString(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.get(key, PersistentDataType.STRING);
    }

    private NamespacedKey key(String key) {
        return new NamespacedKey(plugin, key);
    }

    private void refreshFormat(ItemStack item) {
        ItemFormatManager formatManager = ItemFormatManager.getInstance();
        if (formatManager != null) {
            formatManager.formatItem(item);
        }
    }
}
