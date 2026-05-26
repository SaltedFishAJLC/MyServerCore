package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.user.SkillsUser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

public class ActionBarManager {

    private final ServerCorePlugin plugin;
    private final AuraSkillsApi auraSkills;

    // 缓存一些静态不变的文本 Component，避免每次循环重复创建
    private static final Component SPACING = Component.text("    ");
    private static final Component HEALTH_ICON = Component.text("❤ ");
    private static final Component ARMOR_ICON = Component.text("⛨ ");
    private static final Component MANA_ICON = Component.text("✦ ");
    private static final Component MANA_SUFFIX = Component.text("✎");

    public ActionBarManager(ServerCorePlugin plugin) {
        this.plugin = plugin;
        
        // 安全获取 AuraSkills 实例，考虑到之前在 plugin.yml 已经 depend，这里一定不为 null
        this.auraSkills = AuraSkillsApi.get();
    }

    public void start() {
        // 每 10 ticks (0.5秒) 运行一次。Bukkit 属性读取保持在主线程。
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    updateActionBar(player);
                }
            }
        }.runTaskTimer(plugin, 20L, 10L); // 延迟 20 ticks 后启动
    }

    private void updateActionBar(Player player) {
        // 1. 获取生命值
        double health = player.getHealth();
        AttributeInstance maxHealthAttr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0;

        // 2. 获取护甲值
        PowerLevelManager powerLevelManager = PowerLevelManager.getInstance();
        double armor = powerLevelManager == null ? 0.0 : powerLevelManager.calculateArmorValue(player);

        // 3. 获取法力值 (通过 AuraSkills API)
        SkillsUser user = auraSkills.getUser(player.getUniqueId());
        double mana = user != null ? user.getMana() : 0.0;

        // 4. 高性能渲染 UI
        // 【架构师注】
        // 虽然 MiniMessage 非常方便，但在高频循环 (如每秒 2 次 * 全服玩家) 中，
        // MiniMessage.deserialize(String) 每次都会触发底层的字符串解析和 AST 构建。
        // 为了追求极致的 O(1) 性能，对于这种高频刷新的动态数字，
        // 使用底层的 Component Builder API 直接拼接预缓存的 Component 是最快的方案。
        Component actionBar = Component.text()
                .append(HEALTH_ICON.color(NamedTextColor.RED))
                .append(Component.text((int) health + "/" + (int) maxHealth, NamedTextColor.RED))
                .append(SPACING)
                .append(ARMOR_ICON.color(NamedTextColor.GREEN))
                .append(Component.text(formatArmor(armor), NamedTextColor.GREEN))
                .append(SPACING)
                .append(MANA_ICON.color(NamedTextColor.BLUE))
                .append(Component.text((int) mana, NamedTextColor.BLUE))
                .append(MANA_SUFFIX.color(NamedTextColor.BLUE))
                .build();

        player.sendActionBar(actionBar);
    }

    private String formatArmor(double armor) {
        if (Math.abs(armor - Math.rint(armor)) < 0.0001) {
            return String.valueOf((int) Math.rint(armor));
        }
        return String.format(java.util.Locale.US, "%.1f", armor);
    }
}
