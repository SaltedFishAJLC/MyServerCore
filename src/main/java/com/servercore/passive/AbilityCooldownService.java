package com.servercore.passive;

import com.servercore.ServerCorePlugin;
import com.servercore.manager.PDCManager;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class AbilityCooldownService {

    private static AbilityCooldownService instance;

    private final ServerCorePlugin plugin;
    private final Map<UUID, Map<String, Long>> cache = new HashMap<>();

    public AbilityCooldownService(ServerCorePlugin plugin) {
        this.plugin = plugin;
        instance = this;
    }

    public static AbilityCooldownService getInstance() {
        return instance;
    }

    public long remainingMillis(Player player, String key) {
        long now = System.currentTimeMillis();
        long until = cooldowns(player).getOrDefault(key, 0L);
        if (until <= now) {
            if (until != 0L) {
                cooldowns(player).remove(key);
                save(player);
            }
            return 0L;
        }
        return until - now;
    }

    public boolean isReady(Player player, String key) {
        return remainingMillis(player, key) <= 0L;
    }

    public void start(Player player, String key, long durationMillis) {
        if (player == null || key == null || key.isBlank() || durationMillis <= 0L) {
            return;
        }
        cooldowns(player).put(key, System.currentTimeMillis() + durationMillis);
        save(player);
    }

    public String key(String abilityId, String cooldownGroup, String scope, String sourceId) {
        String group = cooldownGroup == null || cooldownGroup.isBlank() ? abilityId : cooldownGroup;
        if ("PER_SOURCE".equalsIgnoreCase(scope)) {
            return "source:" + group + ":" + sourceId;
        }
        return "shared:" + group;
    }

    public void unload(Player player) {
        if (player == null) {
            return;
        }
        save(player);
        cache.remove(player.getUniqueId());
    }

    public void saveAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            save(player);
        }
    }

    private Map<String, Long> cooldowns(Player player) {
        return cache.computeIfAbsent(player.getUniqueId(), ignored -> load(player));
    }

    private Map<String, Long> load(Player player) {
        Map<String, Long> values = new LinkedHashMap<>();
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return values;
        }
        String raw = player.getPersistentDataContainer()
                .get(pdc.KEY_PLAYER_PASSIVE_COOLDOWNS, PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            return values;
        }
        long now = System.currentTimeMillis();
        for (String line : raw.split("\n")) {
            int split = line.lastIndexOf('=');
            if (split <= 0) {
                continue;
            }
            try {
                long until = Long.parseLong(line.substring(split + 1));
                if (until > now) {
                    values.put(line.substring(0, split), until);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return values;
    }

    private void save(Player player) {
        PDCManager pdc = PDCManager.getInstance();
        if (pdc == null) {
            return;
        }
        Map<String, Long> values = cache.get(player.getUniqueId());
        if (values == null || values.isEmpty()) {
            player.getPersistentDataContainer().remove(pdc.KEY_PLAYER_PASSIVE_COOLDOWNS);
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> iterator = values.entrySet().iterator();
        StringBuilder encoded = new StringBuilder();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (entry.getValue() <= now) {
                iterator.remove();
                continue;
            }
            if (!encoded.isEmpty()) {
                encoded.append('\n');
            }
            encoded.append(entry.getKey()).append('=').append(entry.getValue());
        }
        if (encoded.isEmpty()) {
            player.getPersistentDataContainer().remove(pdc.KEY_PLAYER_PASSIVE_COOLDOWNS);
        } else {
            player.getPersistentDataContainer().set(
                    pdc.KEY_PLAYER_PASSIVE_COOLDOWNS,
                    PersistentDataType.STRING,
                    encoded.toString()
            );
        }
    }
}
