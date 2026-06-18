package com.servercore.enchant;

import com.servercore.ServerCorePlugin;
import com.servercore.combat.damage.DamageCategory;
import com.servercore.combat.damage.DamagePacket;
import com.servercore.combat.damage.DamageService;
import com.servercore.combat.damage.DamageSourceKind;
import com.servercore.combat.damage.DamageTag;
import com.servercore.manager.EnchantManager;
import com.servercore.manager.PDCManager;
import com.servercore.manager.WeaponTemplateManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RangedEmpowermentManager implements Listener {

    private static final double DAMAGE_PER_BLOCK = 0.015;
    private static final double MANA_PER_BLOCK = 1.5;
    private static final long REND_COOLDOWN_MS = 5000L;
    private static final long REND_MARKER_TTL_MS = 30000L;

    private static RangedEmpowermentManager instance;

    private final ServerCorePlugin plugin;
    private final Set<UUID> empoweredPlayers = new java.util.HashSet<>();
    private final Map<UUID, ShotState> shots = new HashMap<>();
    private final Map<UUID, List<RendMarker>> rendMarkers = new HashMap<>();
    private final Map<UUID, Long> rendCooldownUntil = new HashMap<>();

    public RangedEmpowermentManager(ServerCorePlugin plugin) {
        this.plugin = plugin;
        instance = this;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public static RangedEmpowermentManager getInstance() {
        return instance;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToggle(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getPlayer().isSneaking() || !isLeftClick(event.getAction())) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (!isRangedWeapon(weapon)) {
            return;
        }

        event.setCancelled(true);
        EnchantManager enchantManager = EnchantManager.getInstance();
        int rend = enchantManager == null ? 0 : enchantManager.getActiveEnchantLevel(weapon, "rend");
        if (rend > 0) {
            activateRend(player, weapon, rend);
            return;
        }

        UUID playerId = player.getUniqueId();
        if (empoweredPlayers.remove(playerId)) {
            player.sendActionBar(Component.text("赋能射击: 关闭", NamedTextColor.GRAY));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.35f, 0.8f);
        } else {
            empoweredPlayers.add(playerId);
            player.sendActionBar(Component.text("赋能射击: 开启", NamedTextColor.AQUA));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.35f, 1.25f);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof AbstractArrow arrow)
                || !(arrow.getShooter() instanceof Player player)) {
            return;
        }

        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (!isRangedWeapon(weapon)) {
            return;
        }

        EnchantManager enchantManager = EnchantManager.getInstance();
        int rendLevel = enchantManager == null ? 0 : enchantManager.getActiveEnchantLevel(weapon, "rend");
        boolean empowered = empoweredPlayers.contains(player.getUniqueId()) && rendLevel <= 0
                && (enchantManager == null || enchantManager.getActiveEnchantLevel(weapon, "twilight_zone") <= 0);
        if (!empowered && rendLevel <= 0) {
            return;
        }

        UUID projectileId = arrow.getUniqueId();
        shots.put(projectileId, new ShotState(
                player.getUniqueId(),
                arrow.getLocation().clone(),
                weaponSignature(weapon),
                empowered,
                rendLevel
        ));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> shots.remove(projectileId), 20L * 30L);
    }

    @EventHandler
    public void onHeldSlot(PlayerItemHeldEvent event) {
        empoweredPlayers.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        empoweredPlayers.remove(id);
        rendMarkers.remove(id);
        rendCooldownUntil.remove(id);
    }

    public double applyProjectileDamage(Player player, Entity damager, LivingEntity target, double damage) {
        if (!(damager instanceof Projectile projectile) || player == null || target == null || damage <= 0.0) {
            return damage;
        }

        ShotState state = shots.remove(projectile.getUniqueId());
        if (state == null || !state.playerId().equals(player.getUniqueId())) {
            return damage;
        }

        if (state.rendLevel() > 0) {
            rememberRendMarker(player, target, state.weaponSignature(), damage);
        }

        if (!state.empowered()) {
            return damage;
        }

        double distance = Math.max(0.0, state.start().distance(projectile.getLocation()));
        double costMultiplier = manaCostMultiplier(player.getInventory().getItemInMainHand());
        double manaPerBlock = MANA_PER_BLOCK * costMultiplier;
        double fullCost = distance * manaPerBlock;
        double currentMana = ManaAccess.getMana(player);
        double effectiveDistance;
        if (currentMana >= fullCost) {
            ManaAccess.consumeMana(player, fullCost);
            effectiveDistance = distance;
        } else {
            double payableDistance = manaPerBlock <= 0.0 ? distance : Math.ceil(currentMana / manaPerBlock);
            effectiveDistance = Math.min(distance, Math.max(0.0, payableDistance));
            ManaAccess.consumeMana(player, currentMana);
        }

        if (effectiveDistance <= 0.0) {
            player.sendActionBar(Component.text("赋能射击魔力不足。", NamedTextColor.RED));
            return damage;
        }

        double multiplier = 1.0 + effectiveDistance * DAMAGE_PER_BLOCK;
        player.sendActionBar(Component.text("赋能射击 +" + String.format(Locale.US, "%.1f", (multiplier - 1.0) * 100.0) + "%", NamedTextColor.AQUA));
        return damage * multiplier;
    }

    private void activateRend(Player player, ItemStack weapon, int level) {
        long now = System.currentTimeMillis();
        long until = rendCooldownUntil.getOrDefault(player.getUniqueId(), 0L);
        if (until > now) {
            player.sendActionBar(Component.text("撕裂冷却中: " + Math.ceil((until - now) / 1000.0) + "s", NamedTextColor.RED));
            return;
        }

        String signature = weaponSignature(weapon);
        List<RendMarker> markers = rendMarkers.getOrDefault(player.getUniqueId(), List.of());
        if (markers.isEmpty()) {
            player.sendActionBar(Component.text("没有可撕裂的箭矢。", NamedTextColor.GRAY));
            return;
        }

        double ratio = switch (Math.max(1, Math.min(level, 5))) {
            case 1 -> 0.05;
            case 2 -> 0.10;
            case 3 -> 0.15;
            case 4 -> 0.20;
            default -> 0.25;
        };

        Map<UUID, Integer> hitsByTarget = new HashMap<>();
        int triggered = 0;
        Iterator<RendMarker> iterator = markers.iterator();
        while (iterator.hasNext()) {
            RendMarker marker = iterator.next();
            if (now - marker.createdAtMs() > REND_MARKER_TTL_MS || !marker.weaponSignature().equals(signature)) {
                iterator.remove();
                continue;
            }
            LivingEntity target = marker.target();
            if (target == null || target.isDead() || !target.isValid()) {
                iterator.remove();
                continue;
            }
            int used = hitsByTarget.getOrDefault(target.getUniqueId(), 0);
            if (used >= 7) {
                continue;
            }
            hitsByTarget.put(target.getUniqueId(), used + 1);
            applySecondaryDamage(player, target, marker.damage() * ratio, "enchant_rend");
            iterator.remove();
            triggered++;
        }

        if (triggered <= 0) {
            player.sendActionBar(Component.text("没有可撕裂的箭矢。", NamedTextColor.GRAY));
            return;
        }
        rendCooldownUntil.put(player.getUniqueId(), now + REND_COOLDOWN_MS);
        player.playSound(player.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 0.7f, 0.8f);
        player.sendActionBar(Component.text("撕裂触发: " + triggered + " 支箭", NamedTextColor.AQUA));
    }

    private void rememberRendMarker(Player player, LivingEntity target, String weaponSignature, double damage) {
        List<RendMarker> markers = rendMarkers.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayList<>());
        long now = System.currentTimeMillis();
        markers.removeIf(marker -> now - marker.createdAtMs() > REND_MARKER_TTL_MS
                || marker.target() == null
                || marker.target().isDead()
                || !marker.target().isValid());
        markers.add(new RendMarker(target, weaponSignature, damage, now));
    }

    private void applySecondaryDamage(Player player, LivingEntity target, double damage, String reason) {
        DamageService damageService = DamageService.getInstance();
        EnchantDamageContext.runAsSecondaryDamage(() -> {
            if (damageService != null) {
                damageService.applyDamage(new DamagePacket(
                        player,
                        target,
                        damage,
                        DamageCategory.PHYSICAL,
                        EnumSet.of(DamageTag.PROJECTILE),
                        DamageSourceKind.CUSTOM_ITEM,
                        reason
                ));
            } else {
                target.damage(damage, player);
            }
        });
    }

    private boolean isLeftClick(Action action) {
        return action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
    }

    private boolean isRangedWeapon(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        WeaponTemplateManager templateManager = WeaponTemplateManager.getInstance();
        WeaponTemplateManager.WeaponTemplate template = templateManager == null ? null : templateManager.getTemplate(item);
        if (template == null && templateManager != null) {
            template = templateManager.getDefaultTemplate(item.getType());
        }
        return template != null && template.isRanged();
    }

    private double manaCostMultiplier(ItemStack weapon) {
        EnchantManager enchantManager = EnchantManager.getInstance();
        int level = enchantManager == null ? 0 : enchantManager.getActiveEnchantLevel(weapon, "ultimate_wise");
        double reduction = switch (Math.max(0, Math.min(level, 5))) {
            case 1 -> 0.10;
            case 2 -> 0.20;
            case 3 -> 0.30;
            case 4 -> 0.40;
            case 5 -> 0.50;
            default -> 0.0;
        };
        return Math.max(0.0, 1.0 - reduction);
    }

    private String weaponSignature(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(item.getType().name());
        PDCManager pdc = PDCManager.getInstance();
        if (pdc != null && item.hasItemMeta()) {
            String itemId = item.getItemMeta().getPersistentDataContainer().get(pdc.KEY_ITEM_ID, PersistentDataType.STRING);
            String enchants = item.getItemMeta().getPersistentDataContainer().get(pdc.KEY_ITEM_CUSTOM_ENCHANTS, PersistentDataType.STRING);
            builder.append('|').append(itemId == null ? "" : itemId);
            builder.append('|').append(enchants == null ? "" : enchants);
        }
        return builder.toString();
    }

    private record ShotState(UUID playerId, Location start, String weaponSignature, boolean empowered, int rendLevel) {
    }

    private record RendMarker(LivingEntity target, String weaponSignature, double damage, long createdAtMs) {
    }
}
