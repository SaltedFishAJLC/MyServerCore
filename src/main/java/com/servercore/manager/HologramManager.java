package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import com.servercore.combat.creature.CreatureTagService;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.Locale;
import java.util.UUID;

/**
 * 怪物全息血条引擎。
 * 使用 TextDisplay 作为怪物 passenger，并通过 Transformation 抬高文字位置。
 */
public class HologramManager implements Listener {

    private static final String HOLOGRAM_TAG = "servercore_hologram";

    private final ServerCorePlugin plugin;
    private final BukkitTask cleanupTask;

    public HologramManager(ServerCorePlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        this.cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::cleanupOrphanHolograms, 100L, 100L);
    }

    /**
     * 为生成的实体绑定一个 TextDisplay 作为乘客。
     * 显示格式：[Lv.50] 僵尸 ❤ 20k
     */
    public void attachHologram(LivingEntity entity, int powerLevel) {
        if (!entity.isValid() || entity.isDead()) return;

        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) return;

        removeExistingHologram(entity);

        TextDisplay display = entity.getWorld().spawn(entity.getLocation(), TextDisplay.class, textDisplay -> {
            textDisplay.addScoreboardTag(HOLOGRAM_TAG);
            textDisplay.text(createDisplayText(entity, powerLevel));
            textDisplay.setBillboard(Display.Billboard.CENTER);
            textDisplay.setAlignment(TextDisplay.TextAlignment.CENTER);
            textDisplay.setLineWidth(220);
            textDisplay.setTextOpacity((byte) 255);
            textDisplay.setDefaultBackground(false);
            textDisplay.setBackgroundColor(Color.fromARGB(96, 0, 0, 0));
            textDisplay.setSeeThrough(true);
            textDisplay.setShadowed(true);
            textDisplay.setPersistent(false);
            textDisplay.setGravity(false);
            textDisplay.setInvulnerable(true);
            textDisplay.setSilent(true);
            textDisplay.setTransformation(createHeadOffset(entity));
        });

        if (entity.addPassenger(display)) {
            entity.getPersistentDataContainer().set(pdc.KEY_HOLOGRAM_ID, PersistentDataType.STRING, display.getUniqueId().toString());
        } else {
            display.remove();
        }
    }

    /**
     * 受伤后刷新血量。如果怪物带生态 PDC 但 passenger 丢失，则自动重铸全息。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof TextDisplay) {
            event.setCancelled(true);
            return;
        }

        if (!(event.getEntity() instanceof LivingEntity entity)) return;
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) return;

        Integer powerLevel = entity.getPersistentDataContainer().get(pdc.KEY_MOB_POWER_LEVEL, PersistentDataType.INTEGER);
        if (powerLevel == null || powerLevel <= 0) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            TextDisplay display = getBoundDisplay(entity);
            if (display == null || !display.isValid()) {
                attachHologram(entity, powerLevel);
                return;
            }

            display.setTransformation(createHeadOffset(entity));
            display.text(createDisplayText(entity, powerLevel));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        removeExistingHologram(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemove(EntityRemoveEvent event) {
        if (event.getEntity() instanceof LivingEntity livingEntity) {
            removeExistingHologram(livingEntity);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemoveFromWorld(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof LivingEntity livingEntity) {
            removeExistingHologram(livingEntity);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof LivingEntity livingEntity) {
                removeExistingHologram(livingEntity);
            } else if (entity instanceof TextDisplay textDisplay && isServerCoreHologram(textDisplay)) {
                textDisplay.remove();
            }
        }
    }

    public void updateHologram(LivingEntity entity) {
        if (!entity.isValid() || entity.isDead()) return;

        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) return;

        Integer powerLevel = entity.getPersistentDataContainer().get(pdc.KEY_MOB_POWER_LEVEL, PersistentDataType.INTEGER);
        if (powerLevel == null || powerLevel <= 0) return;

        TextDisplay display = getBoundDisplay(entity);
        if (display == null || !display.isValid()) {
            attachHologram(entity, powerLevel);
            return;
        }

        display.setTransformation(createHeadOffset(entity));
        display.text(createDisplayText(entity, powerLevel));
    }

    private Component createDisplayText(LivingEntity entity, int powerLevel) {
        String name = getDisplayName(entity);
        HealthDisplay healthDisplay = resolveHealthDisplay(entity);
        String health = formatNumber(healthDisplay.health());
        String maxHealth = formatNumber(healthDisplay.maxHealth());
        CreatureTagService tagService = CreatureTagService.getInstance();
        Component tagPrefix = tagService == null ? Component.empty() : tagService.renderTagPrefix(entity);

        return Component.text("[Lv." + powerLevel + "]", NamedTextColor.GRAY)
                .append(Component.space())
                .append(tagPrefix)
                .append(Component.space())
                .append(Component.text(name, NamedTextColor.WHITE))
                .append(Component.space())
                .append(Component.text("❤", NamedTextColor.RED))
                .append(Component.space())
                .append(Component.text(health + "/" + maxHealth, NamedTextColor.WHITE));
    }

    private HealthDisplay resolveHealthDisplay(LivingEntity entity) {
        PDCManager pdc = PDCManager.getInstance();
        if (pdc != null) {
            PersistentDataContainer container = entity.getPersistentDataContainer();
            Double virtualMaxHealth = container.get(pdc.KEY_MOB_VIRTUAL_MAX_HEALTH, PersistentDataType.DOUBLE);
            Double virtualHealth = container.get(pdc.KEY_MOB_VIRTUAL_HEALTH, PersistentDataType.DOUBLE);
            if (virtualMaxHealth != null && virtualMaxHealth > 0.0 && virtualHealth != null) {
                return new HealthDisplay(Math.max(0.0, virtualHealth), virtualMaxHealth);
            }
        }

        double maxHealth = entity.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH) == null
                ? entity.getHealth()
                : entity.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue();
        return new HealthDisplay(Math.max(0.0, entity.getHealth()), maxHealth);
    }

    private String getDisplayName(LivingEntity entity) {
        if (entity.customName() != null) {
            String customName = PlainTextComponentSerializer.plainText().serialize(entity.customName());
            if (!customName.isBlank()) {
                return customName;
            }
        }

        return switch (entity.getType()) {
            case ZOMBIE -> "僵尸";
            case ZOMBIE_VILLAGER -> "僵尸村民";
            case HUSK -> "尸壳";
            case DROWNED -> "溺尸";
            case SKELETON -> "骷髅";
            case STRAY -> "流浪者";
            case WITHER_SKELETON -> "凋灵骷髅";
            case BOGGED -> "沼骸";
            case ENDERMAN -> "末影人";
            case BLAZE -> "烈焰人";
            case MAGMA_CUBE -> "岩浆怪";
            case RAVAGER -> "劫掠兽";
            case WARDEN -> "监守者";
            default -> entity.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    private String formatNumber(double value) {
        if (value >= 1_000_000.0) {
            return String.format(Locale.US, "%.1fm", value / 1_000_000.0);
        }
        if (value >= 1_000.0) {
            return String.format(Locale.US, "%.1fk", value / 1_000.0);
        }
        return String.valueOf(Math.max(0, (int) Math.ceil(value)));
    }

    private TextDisplay getBoundDisplay(LivingEntity entity) {
        for (Entity passenger : entity.getPassengers()) {
            if (passenger instanceof TextDisplay textDisplay && textDisplay.getScoreboardTags().contains(HOLOGRAM_TAG)) {
                return textDisplay;
            }
        }

        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) return null;

        String uuidText = entity.getPersistentDataContainer().get(pdc.KEY_HOLOGRAM_ID, PersistentDataType.STRING);
        if (uuidText == null) return null;

        try {
            Entity display = Bukkit.getEntity(UUID.fromString(uuidText));
            if (display instanceof TextDisplay textDisplay && textDisplay.isValid()) {
                return textDisplay;
            }
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        return null;
    }

    private void removeExistingHologram(LivingEntity entity) {
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) return;

        for (Entity passenger : entity.getPassengers()) {
            if (passenger instanceof TextDisplay && passenger.getScoreboardTags().contains(HOLOGRAM_TAG)) {
                passenger.remove();
            }
        }

        TextDisplay display = getBoundDisplay(entity);
        if (display != null) {
            display.remove();
        }

        PersistentDataContainer container = entity.getPersistentDataContainer();
        container.remove(pdc.KEY_HOLOGRAM_ID);
    }

    private Transformation createHeadOffset(LivingEntity entity) {
        float yOffset = getPassengerHeadOffset(entity);
        return new Transformation(
                new Vector3f(0.0f, yOffset, 0.0f),
                new AxisAngle4f(),
                new Vector3f(1.0f, 1.0f, 1.0f),
                new AxisAngle4f()
        );
    }

    private float getPassengerHeadOffset(LivingEntity entity) {
        return switch (entity.getType()) {
            case SPIDER, CAVE_SPIDER -> 0.05f;
            case SLIME, MAGMA_CUBE, SILVERFISH, ENDERMITE -> 0.08f;
            case CHICKEN, RABBIT, BEE, BAT -> 0.10f;
            case ZOMBIE, ZOMBIE_VILLAGER, HUSK, DROWNED, SKELETON, STRAY, BOGGED, WITHER_SKELETON,
                    PIGLIN, ZOMBIFIED_PIGLIN, PILLAGER, VINDICATOR, WITCH -> 0.18f;
            case CREEPER, BLAZE, PHANTOM -> 0.20f;
            case ENDERMAN -> 0.32f;
            case RAVAGER, IRON_GOLEM, ELDER_GUARDIAN -> 0.55f;
            case WARDEN -> 0.75f;
            case GHAST -> 0.90f;
            default -> (float) Math.max(0.08, Math.min(0.45, entity.getHeight() * 0.12));
        };
    }

    private void cleanupOrphanHolograms() {
        for (World world : Bukkit.getWorlds()) {
            for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
                if (!isServerCoreHologram(display)) continue;
                Entity vehicle = display.getVehicle();
                if (!(vehicle instanceof LivingEntity livingEntity) || !vehicle.isValid() || vehicle.isDead()) {
                    display.remove();
                    continue;
                }

                PDCManager pdc = PDCManager.getInstance();
                if (pdc != null && !livingEntity.getPersistentDataContainer().has(pdc.KEY_MOB_POWER_LEVEL, PersistentDataType.INTEGER)) {
                    display.remove();
                }
            }
        }
    }

    private boolean isServerCoreHologram(TextDisplay display) {
        if (display.getScoreboardTags().contains(HOLOGRAM_TAG)) {
            return true;
        }

        Component text = display.text();
        if (text == null) {
            return false;
        }

        String plain = PlainTextComponentSerializer.plainText().serialize(text);
        return plain.startsWith("[Lv.");
    }

    private record HealthDisplay(double health, double maxHealth) {
    }
}
