package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Coordinate-triggered unique mob spawns for dungeon and structure set pieces.
 */
public class UniqueMobSpawnManager implements Listener {

    private final ServerCorePlugin plugin;
    private final CustomMobRegistry customMobRegistry;
    private final NamespacedKey uniqueSpawnKey;
    private final File configFile;
    private final File stateFile;
    private final Map<String, SpawnPoint> spawns = new HashMap<>();
    private final Map<String, MarkerDefinition> markers = new HashMap<>();
    private final Map<String, SpawnState> states = new HashMap<>();
    private BukkitTask task;

    public UniqueMobSpawnManager(ServerCorePlugin plugin, CustomMobRegistry customMobRegistry) {
        this.plugin = plugin;
        this.customMobRegistry = customMobRegistry;
        this.uniqueSpawnKey = new NamespacedKey(plugin, "unique_mob_spawn_id");
        this.configFile = new File(plugin.getDataFolder(), "unique_mob_spawns.yml");
        this.stateFile = new File(plugin.getDataFolder(), "unique_mob_spawns_state.yml");

        reload();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSpawns, 40L, 100L);
    }

    public int reload() {
        ensureConfigFile();
        loadConfig();
        loadState();
        return spawns.size();
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        saveState();
    }

    public List<String> getSpawnIds() {
        List<String> ids = new ArrayList<>(spawns.keySet());
        for (String stateId : states.keySet()) {
            if (!ids.contains(stateId)) {
                ids.add(stateId);
            }
        }
        return ids;
    }

    public boolean reset(String spawnId) {
        String normalized = normalize(spawnId);
        SpawnPoint spawn = spawns.get(normalized);
        if (spawn == null && !states.containsKey(normalized)) {
            return false;
        }

        SpawnState previous = states.get(normalized);
        if (previous != null && previous.entityUuid() != null) {
            Entity entity = Bukkit.getEntity(previous.entityUuid());
            if (entity != null && entity.isValid()) {
                entity.remove();
            }
        }

        states.put(normalized, new SpawnState(false, false, null));
        saveState();
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        String spawnId = event.getEntity().getPersistentDataContainer().get(uniqueSpawnKey, PersistentDataType.STRING);
        if (spawnId == null || spawnId.isBlank()) {
            return;
        }

        String normalized = normalize(spawnId);
        SpawnState current = states.getOrDefault(normalized, new SpawnState(true, false, event.getEntity().getUniqueId()));
        states.put(normalized, new SpawnState(current.spawned(), true, null));
        saveState();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (markers.isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> scanChunkForMarkers(event.getChunk()));
    }

    private void tickSpawns() {
        tickCoordinateSpawns();
        scanLoadedChunksForMarkers();
    }

    private void tickCoordinateSpawns() {
        for (SpawnPoint spawn : spawns.values()) {
            if (!spawn.enabled()) {
                continue;
            }

            SpawnState state = states.getOrDefault(spawn.id(), new SpawnState(false, false, null));
            if (spawn.deathLocks() && state.killed()) {
                continue;
            }
            if (state.entityUuid() != null && isAlive(state.entityUuid())) {
                continue;
            }
            if (spawn.spawnOnce() && state.spawned()) {
                continue;
            }

            World world = Bukkit.getWorld(spawn.worldName());
            if (world == null || !hasPlayerNearby(world, spawn)) {
                continue;
            }

            spawnMob(spawn.id(), spawn.mobId(), spawn.location(world), spawn.persistent(), spawn.removeWhenFarAway());
        }
    }

    private void scanLoadedChunksForMarkers() {
        if (markers.isEmpty()) {
            return;
        }

        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                scanChunkForMarkers(chunk);
            }
        }
    }

    private boolean isAlive(UUID entityUuid) {
        Entity entity = Bukkit.getEntity(entityUuid);
        return entity instanceof LivingEntity livingEntity && livingEntity.isValid() && !livingEntity.isDead();
    }

    private boolean hasPlayerNearby(World world, SpawnPoint spawn) {
        double radiusSquared = spawn.triggerRadius() * spawn.triggerRadius();
        Location location = spawn.location(world);
        for (Player player : world.getPlayers()) {
            if (!player.isValid() || player.isDead()) {
                continue;
            }
            if (player.getLocation().distanceSquared(location) <= radiusSquared) {
                return true;
            }
        }
        return false;
    }

    private void scanChunkForMarkers(Chunk chunk) {
        if (markers.isEmpty() || !chunk.isLoaded()) {
            return;
        }

        for (Entity markerEntity : chunk.getEntities()) {
            MarkerDefinition marker = identifyMarker(markerEntity);
            if (marker == null || !marker.enabled()) {
                continue;
            }

            String spawnId = markerSpawnId(marker, markerEntity.getLocation());
            SpawnState state = states.getOrDefault(spawnId, new SpawnState(false, false, null));
            boolean locked = marker.deathLocks() && state.killed();
            boolean alreadySpawned = marker.spawnOnce() && state.spawned();
            boolean active = state.entityUuid() != null && isAlive(state.entityUuid());
            if (locked || alreadySpawned || active) {
                if (marker.removeMarker()) {
                    markerEntity.remove();
                }
                continue;
            }

            Location spawnLocation = markerEntity.getLocation().clone().add(marker.xOffset(), marker.yOffset(), marker.zOffset());
            boolean spawned = spawnMob(spawnId, marker.mobId(), spawnLocation, marker.persistent(), marker.removeWhenFarAway());
            if (spawned && marker.removeMarker()) {
                markerEntity.remove();
            }
        }
    }

    private MarkerDefinition identifyMarker(Entity entity) {
        for (MarkerDefinition marker : markers.values()) {
            if (marker.entityType() != null && !entity.getType().name().equalsIgnoreCase(marker.entityType())) {
                continue;
            }
            if (marker.scoreboardTag() != null && !entity.getScoreboardTags().contains(marker.scoreboardTag())) {
                continue;
            }
            if (marker.name() != null && !entityName(entity).equalsIgnoreCase(marker.name())) {
                continue;
            }
            return marker;
        }
        return null;
    }

    private String entityName(Entity entity) {
        String customName = entity.getCustomName();
        return customName == null || customName.isBlank() ? entity.getName() : customName;
    }

    private boolean spawnMob(String stateId, String mobId, Location location, boolean persistent, boolean removeWhenFarAway) {
        LivingEntity entity = customMobRegistry.spawnConfiguredMob(mobId, location);
        if (entity == null) {
            plugin.getLogger().warning("Could not spawn unique mob '" + stateId + "' with custom mob rule '" + mobId + "'.");
            return false;
        }

        entity.setPersistent(persistent);
        entity.setRemoveWhenFarAway(removeWhenFarAway);
        entity.addScoreboardTag("servercore_unique_spawn");
        entity.getPersistentDataContainer().set(uniqueSpawnKey, PersistentDataType.STRING, stateId);

        MobSpawnManager mobSpawnManager = MobSpawnManager.getInstance();
        if (mobSpawnManager != null) {
            mobSpawnManager.applyCustomMobScaling(entity, mobId);
        }

        states.put(stateId, new SpawnState(true, false, entity.getUniqueId()));
        saveState();
        return true;
    }

    private void loadConfig() {
        spawns.clear();
        markers.clear();

        YamlConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        ConfigurationSection root = config.getConfigurationSection("spawns");
        if (root == null) {
            root = config;
        }

        for (String id : root.getKeys(false)) {
            if (id.equalsIgnoreCase("markers")) {
                continue;
            }
            if (!root.isConfigurationSection(id)) {
                continue;
            }

            ConfigurationSection section = root.getConfigurationSection(id);
            SpawnPoint spawn = parseSpawnPoint(id, section);
            if (spawn != null) {
                spawns.put(spawn.id(), spawn);
            }
        }

        ConfigurationSection markerRoot = config.getConfigurationSection("markers");
        if (markerRoot != null) {
            for (String id : markerRoot.getKeys(false)) {
                if (!markerRoot.isConfigurationSection(id)) {
                    continue;
                }

                MarkerDefinition marker = parseMarkerDefinition(id, markerRoot.getConfigurationSection(id));
                if (marker != null) {
                    markers.put(marker.id(), marker);
                }
            }
        }
    }

    private SpawnPoint parseSpawnPoint(String id, ConfigurationSection section) {
        String mobId = firstPresentString(section, "mob", "mob_id", "custom_mob", "custom_mob_id");
        String world = firstPresentString(section, "world", "world_name");
        if (mobId == null || world == null) {
            plugin.getLogger().warning("Skipped unique mob spawn '" + id + "' because it needs mob and world.");
            return null;
        }

        if (!hasNumber(section, "x") || !hasNumber(section, "y") || !hasNumber(section, "z")) {
            plugin.getLogger().warning("Skipped unique mob spawn '" + id + "' because it needs x, y and z.");
            return null;
        }

        double triggerRadius = Math.max(1.0, section.getDouble("trigger_radius", 32.0));
        return new SpawnPoint(
                normalize(id),
                section.getBoolean("enabled", true),
                mobId,
                world,
                section.getDouble("x"),
                section.getDouble("y"),
                section.getDouble("z"),
                (float) section.getDouble("yaw", 0.0),
                (float) section.getDouble("pitch", 0.0),
                triggerRadius,
                section.getBoolean("spawn_once", true),
                section.getBoolean("death_locks", true),
                section.getBoolean("persistent", true),
                section.getBoolean("remove_when_far_away", false)
        );
    }

    private MarkerDefinition parseMarkerDefinition(String id, ConfigurationSection section) {
        String mobId = firstPresentString(section, "mob", "mob_id", "custom_mob", "custom_mob_id");
        String scoreboardTag = firstPresentString(section, "match_scoreboard_tag", "scoreboard_tag", "tag");
        String name = firstPresentString(section, "match_name", "name");
        String entityType = firstPresentString(section, "match_entity_type", "entity_type", "type");
        if (mobId == null || (scoreboardTag == null && name == null && entityType == null)) {
            plugin.getLogger().warning("Skipped unique mob marker '" + id + "' because it needs mob and at least one marker matcher.");
            return null;
        }

        return new MarkerDefinition(
                normalize(id),
                section.getBoolean("enabled", true),
                mobId,
                scoreboardTag,
                name,
                entityType == null ? null : entityType.toUpperCase(Locale.ROOT),
                section.getDouble("x_offset", 0.0),
                section.getDouble("y_offset", 0.0),
                section.getDouble("z_offset", 0.0),
                section.getBoolean("spawn_once", true),
                section.getBoolean("death_locks", true),
                section.getBoolean("persistent", true),
                section.getBoolean("remove_when_far_away", false),
                section.getBoolean("remove_marker", true)
        );
    }

    private void loadState() {
        states.clear();
        if (!stateFile.exists()) {
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(stateFile);
        ConfigurationSection root = config.getConfigurationSection("spawns");
        if (root == null) {
            return;
        }

        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                continue;
            }

            UUID entityUuid = parseUuid(section.getString("entity_uuid"));
            states.put(normalize(id), new SpawnState(
                    section.getBoolean("spawned", false),
                    section.getBoolean("killed", false),
                    entityUuid
            ));
        }
    }

    private void saveState() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder for unique mob spawn state.");
            return;
        }

        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<String, SpawnState> entry : states.entrySet()) {
            String path = "spawns." + entry.getKey();
            SpawnState state = entry.getValue();
            config.set(path + ".spawned", state.spawned());
            config.set(path + ".killed", state.killed());
            config.set(path + ".entity_uuid", state.entityUuid() == null ? null : state.entityUuid().toString());
        }

        try {
            config.save(stateFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save unique mob spawn state: " + exception.getMessage());
        }
    }

    private void ensureConfigFile() {
        if (configFile.exists()) {
            return;
        }

        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder for unique_mob_spawns.yml.");
            return;
        }

        if (plugin.getResource("unique_mob_spawns.yml") != null) {
            plugin.saveResource("unique_mob_spawns.yml", false);
            return;
        }

        YamlConfiguration config = new YamlConfiguration();
        String path = "spawns.wda_dungeon_elite_001.";
        config.set(path + "enabled", false);
        config.set(path + "mob", "WDA_Dungeon_Guard");
        config.set(path + "world", "world");
        config.set(path + "x", 0.5);
        config.set(path + "y", 64.0);
        config.set(path + "z", 0.5);
        config.set(path + "trigger_radius", 32.0);
        config.set(path + "spawn_once", true);
        config.set(path + "death_locks", true);
        config.set(path + "persistent", true);
        config.set(path + "remove_when_far_away", false);
        config.set("markers.wda_dungeon_elite.enabled", true);
        config.set("markers.wda_dungeon_elite.mob", "WDA_Dungeon_Guard");
        config.set("markers.wda_dungeon_elite.match_scoreboard_tag", "servercore_unique_spawn:wda_dungeon_elite");
        config.set("markers.wda_dungeon_elite.spawn_once", true);
        config.set("markers.wda_dungeon_elite.death_locks", true);
        config.set("markers.wda_dungeon_elite.persistent", true);
        config.set("markers.wda_dungeon_elite.remove_when_far_away", false);
        config.set("markers.wda_dungeon_elite.remove_marker", true);
        try {
            config.save(configFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not create unique_mob_spawns.yml: " + exception.getMessage());
        }
    }

    private String firstPresentString(ConfigurationSection section, String... keys) {
        for (String key : keys) {
            String value = section.getString(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean hasNumber(ConfigurationSection section, String key) {
        return section.contains(key) && (section.isDouble(key) || section.isInt(key) || section.isLong(key));
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String markerSpawnId(MarkerDefinition marker, Location location) {
        return normalize("marker_" + marker.id()
                + "_" + sanitize(location.getWorld() == null ? "world" : location.getWorld().getName())
                + "_" + location.getBlockX()
                + "_" + location.getBlockY()
                + "_" + location.getBlockZ());
    }

    private String sanitize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).trim();
    }

    private record SpawnPoint(
            String id,
            boolean enabled,
            String mobId,
            String worldName,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            double triggerRadius,
            boolean spawnOnce,
            boolean deathLocks,
            boolean persistent,
            boolean removeWhenFarAway
    ) {
        Location location(World world) {
            return new Location(world, x, y, z, yaw, pitch);
        }
    }

    private record MarkerDefinition(
            String id,
            boolean enabled,
            String mobId,
            String scoreboardTag,
            String name,
            String entityType,
            double xOffset,
            double yOffset,
            double zOffset,
            boolean spawnOnce,
            boolean deathLocks,
            boolean persistent,
            boolean removeWhenFarAway,
            boolean removeMarker
    ) {
    }

    private record SpawnState(boolean spawned, boolean killed, UUID entityUuid) {
    }
}
