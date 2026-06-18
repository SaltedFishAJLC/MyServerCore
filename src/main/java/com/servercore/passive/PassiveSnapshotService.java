package com.servercore.passive;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import com.servercore.ServerCorePlugin;
import com.servercore.combat.damage.DamageCategory;
import com.servercore.combat.damage.DamagePacket;
import com.servercore.combat.damage.DamageService;
import com.servercore.combat.damage.DamageSourceKind;
import com.servercore.manager.AccessoryManager;
import com.servercore.manager.CustomItemRegistry;
import com.servercore.manager.PlayerStatCache;
import com.servercore.manager.RequirementManager;
import com.servercore.manager.WeaponTemplateManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PassiveSnapshotService implements Listener {

    private static final Set<String> IMPLEMENTED_HANDLERS = Set.of(
            "stat_bonus",
            "damage_reduction",
            "outgoing_multiplier",
            "on_hit_damage",
            "revive"
    );

    private static PassiveSnapshotService instance;

    private final ServerCorePlugin plugin;
    private final Map<UUID, PassiveSnapshot> snapshots = new HashMap<>();
    private final Set<UUID> refreshQueued = new HashSet<>();
    private BukkitTask periodicTask;
    private BukkitTask requirementRefreshTask;

    public PassiveSnapshotService(ServerCorePlugin plugin) {
        this.plugin = plugin;
        instance = this;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        validateRegistryConfiguration();
        periodicTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPeriodicPassives, 10L, 10L);
        requirementRefreshTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                () -> Bukkit.getOnlinePlayers().forEach(this::scheduleRefresh),
                100L,
                100L
        );
    }

    public static PassiveSnapshotService getInstance() {
        return instance;
    }

    public static boolean isHandlerImplemented(String handler) {
        return handler != null && IMPLEMENTED_HANDLERS.contains(handler.toLowerCase(Locale.ROOT));
    }

    public void stop() {
        if (periodicTask != null) {
            periodicTask.cancel();
            periodicTask = null;
        }
        if (requirementRefreshTask != null) {
            requirementRefreshTask.cancel();
            requirementRefreshTask = null;
        }
        AbilityCooldownService cooldowns = AbilityCooldownService.getInstance();
        if (cooldowns != null) {
            cooldowns.saveAll();
        }
        snapshots.clear();
        refreshQueued.clear();
    }

    public void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refresh(player);
        }
    }

    public void scheduleRefresh(Player player) {
        if (player == null || !refreshQueued.add(player.getUniqueId())) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            refreshQueued.remove(player.getUniqueId());
            if (player.isOnline()) {
                refresh(player);
            }
        });
    }

    public PassiveSnapshot refresh(Player player) {
        PassiveSnapshot previous = snapshots.get(player.getUniqueId());
        PassiveSnapshot next = buildSnapshot(player);
        snapshots.put(player.getUniqueId(), next);
        notifyStateChanges(player, previous, next);

        PlayerStatCache statCache = PlayerStatCache.getInstance();
        if (statCache != null) {
            statCache.updateCache(player);
        }
        return next;
    }

    public PassiveSnapshot getSnapshot(Player player) {
        if (player == null) {
            return PassiveSnapshot.empty();
        }
        PassiveSnapshot snapshot = snapshots.get(player.getUniqueId());
        if (snapshot == null) {
            snapshot = refresh(player);
        }
        if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
            return PassiveSnapshot.empty();
        }
        return snapshot;
    }

    public double getStatBonus(Player player, String statKey) {
        double result = 0.0;
        for (PassiveAbilityInstance instance : getSnapshot(player).activeAbilities()) {
            if (!instance.definition().handler().equals("stat_bonus")) {
                continue;
            }
            result += readStat(instance.options(), statKey);
        }
        return result;
    }

    public double modifyOutgoingDamage(Player player, LivingEntity target, double damage) {
        double result = damage;
        for (PassiveAbilityInstance instance : getSnapshot(player).activeAbilities()) {
            if (instance.definition().handler().equals("outgoing_multiplier")) {
                result *= Math.max(0.0, 1.0 + doubleOption(instance.options(), "amount", 0.0));
            }
        }
        return result;
    }

    public double modifyIncomingDamage(Player player, double damage) {
        double result = damage;
        for (PassiveAbilityInstance instance : getSnapshot(player).activeAbilities()) {
            if (instance.definition().handler().equals("damage_reduction")) {
                double amount = clamp(doubleOption(instance.options(), "amount", 0.0), 0.0, 0.95);
                result *= 1.0 - amount;
            }
        }
        return result;
    }

    public void afterPlayerAttack(Player player, LivingEntity target, double finalDamage) {
        DamageService damageService = DamageService.getInstance();
        if (damageService == null || player == null || target == null || finalDamage <= 0.0) {
            return;
        }
        for (PassiveAbilityInstance instance : getSnapshot(player).activeAbilities()) {
            if (!instance.definition().handler().equals("on_hit_damage")) {
                continue;
            }
            if (!cooldownReady(player, instance)) {
                continue;
            }
            double amount = doubleOption(instance.options(), "amount", 0.0);
            double ratio = doubleOption(instance.options(), "damage_ratio", 0.0);
            double extraDamage = Math.max(0.0, amount + finalDamage * ratio);
            if (extraDamage <= 0.0) {
                continue;
            }
            damageService.applyDamage(new DamagePacket(
                    player,
                    target,
                    extraDamage,
                    DamageCategory.TRUE,
                    Set.of(),
                    DamageSourceKind.CUSTOM_ITEM,
                    "passive:" + instance.abilityId()
            ));
            startCooldown(player, instance);
        }
    }

    public boolean tryPreventFatalDamage(Player player, org.bukkit.event.entity.EntityDamageEvent event, double finalDamage) {
        if (player == null || player.getHealth() - finalDamage > 0.0) {
            return false;
        }
        List<PassiveAbilityInstance> candidates = getSnapshot(player).activeAbilities().stream()
                .filter(instance -> instance.definition().handler().equals("revive"))
                .sorted(Comparator
                        .comparingInt((PassiveAbilityInstance value) -> value.definition().eventPriority()).reversed()
                        .thenComparing(Comparator.comparingInt(PassiveAbilityInstance::priority).reversed())
                        .thenComparing(PassiveAbilityInstance::abilityId))
                .toList();
        for (PassiveAbilityInstance instance : candidates) {
            if (!cooldownReady(player, instance)) {
                continue;
            }
            if (event != null) {
                event.setCancelled(true);
            }
            double health = Math.max(1.0, doubleOption(instance.options(), "health", 1.0));
            player.setHealth(Math.min(maxHealth(player), health));
            if (booleanOption(instance.options(), "clear_negative_effects", true)) {
                clearNegativeEffects(player);
            }
            player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0.0, 1.0, 0.0),
                    40, 0.6, 0.8, 0.6, 0.03);
            player.playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, 1.0f, 1.0f);
            player.sendActionBar(ServerCorePlugin.getMiniMessage().deserialize(
                    "<gold>" + instance.definition().displayName() + "</gold><gray>阻止了致死伤害。</gray>"));
            startCooldown(player, instance);
            return true;
        }
        return false;
    }

    public List<String> describe(Player player, boolean internal) {
        PassiveSnapshot snapshot = getSnapshot(player);
        List<String> lines = new ArrayList<>();
        for (PassiveAbilityInstance ability : snapshot.activeAbilities()) {
            long remaining = remainingCooldown(player, ability);
            String prefix = internal
                    ? ability.abilityId() + " [" + ability.sourceType() + "/" + ability.sourceId() + "]"
                    : ability.definition().displayName();
            lines.add(prefix + (remaining > 0L ? " - 冷却 " + formatSeconds(remaining) + "s" : " - 生效"));
        }
        for (String issue : snapshot.issues()) {
            lines.add("未生效: " + issue);
        }
        AccessoryManager accessoryManager = AccessoryManager.getInstance();
        if (accessoryManager != null) {
            accessoryManager.resolveTalismans(player).suppressedSlots().forEach((slot, reason) ->
                    lines.add("未生效: 护符包第 " + (slot + 1) + " 格 - " + reason));
        }
        return lines;
    }

    public void validateRegistryConfiguration() {
        CustomItemRegistry items = CustomItemRegistry.getInstance();
        PassiveAbilityRegistry abilities = PassiveAbilityRegistry.getInstance();
        EquipmentSetRegistry sets = EquipmentSetRegistry.getInstance();
        if (items == null || abilities == null || sets == null) {
            return;
        }
        for (CustomItemRegistry.CustomItemDefinition item : items.getDefinitions().values()) {
            boolean imprintDeclared = item.imprintEligible() || item.accessoryType().equalsIgnoreCase("IMPRINT");
            boolean validPassive = false;
            for (CustomItemRegistry.AbilityDefinition ability : item.abilities()) {
                if (!ability.trigger().equals("PASSIVE")) {
                    continue;
                }
                PassiveAbilityRegistry.PassiveDefinition definition = abilities.get(ability.id());
                if (definition == null) {
                    plugin.getLogger().warning("Custom item '" + item.id()
                            + "' references unknown passive ability '" + ability.id() + "'.");
                    continue;
                }
                if (!isHandlerImplemented(definition.handler())) {
                    plugin.getLogger().warning("Passive ability '" + ability.id()
                            + "' uses unimplemented handler '" + definition.handler() + "'.");
                    continue;
                }
                if (imprintDeclared && !definition.allowedSources().contains(PassiveSourceType.IMPRINT)) {
                    plugin.getLogger().warning("Custom item '" + item.id()
                            + "' is imprint-eligible but passive '" + ability.id()
                            + "' does not allow IMPRINT.");
                } else {
                    validPassive = true;
                }
            }
            boolean validSet = !item.setId().isBlank() && !item.setPieceId().isBlank()
                    && sets.get(item.setId()) != null;
            if (imprintDeclared && !validPassive && !validSet) {
                plugin.getLogger().warning("Custom item '" + item.id()
                        + "' declares imprint eligibility but has no implemented imprint passive or valid set piece.");
            }
        }
        for (EquipmentSetRegistry.EquipmentSetDefinition set : sets.all().values()) {
            for (EquipmentSetRegistry.SetThreshold threshold : set.thresholds()) {
                for (CustomItemRegistry.AbilityDefinition ability : threshold.abilities()) {
                    if (abilities.get(ability.id()) == null) {
                        plugin.getLogger().warning("Equipment set '" + set.id() + "' threshold "
                                + threshold.pieces() + " references unknown passive '" + ability.id() + "'.");
                    }
                }
            }
        }
    }

    private PassiveSnapshot buildSnapshot(Player player) {
        CustomItemRegistry itemRegistry = CustomItemRegistry.getInstance();
        PassiveAbilityRegistry abilityRegistry = PassiveAbilityRegistry.getInstance();
        EquipmentSetRegistry setRegistry = EquipmentSetRegistry.getInstance();
        AccessoryManager accessories = AccessoryManager.getInstance();
        if (itemRegistry == null || abilityRegistry == null || setRegistry == null || accessories == null) {
            return PassiveSnapshot.empty();
        }

        List<PassiveAbilityInstance> candidates = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        Map<String, Map<String, String>> setPieces = new LinkedHashMap<>();

        addItemSource(player, player.getInventory().getItemInMainHand(), PassiveSourceType.MAIN_HAND,
                EquipmentSlot.HAND, candidates, setPieces, issues);
        addItemSource(player, player.getInventory().getItemInOffHand(), PassiveSourceType.OFF_HAND,
                EquipmentSlot.OFF_HAND, candidates, setPieces, issues);
        addItemSource(player, player.getInventory().getHelmet(), PassiveSourceType.ARMOR,
                EquipmentSlot.HEAD, candidates, setPieces, issues);
        addItemSource(player, player.getInventory().getChestplate(), PassiveSourceType.ARMOR,
                EquipmentSlot.CHEST, candidates, setPieces, issues);
        addItemSource(player, player.getInventory().getLeggings(), PassiveSourceType.ARMOR,
                EquipmentSlot.LEGS, candidates, setPieces, issues);
        addItemSource(player, player.getInventory().getBoots(), PassiveSourceType.ARMOR,
                EquipmentSlot.FEET, candidates, setPieces, issues);

        ItemStack[] accessoryItems = accessories.loadAccessories(player);
        String[] accessoryTypes = {"necklace", "bracelet", "ring", "belt"};
        for (int index = 0; index < accessoryItems.length; index++) {
            addItemSource(player, accessoryItems[index], PassiveSourceType.ACCESSORY, null,
                    candidates, setPieces, issues, accessoryTypes[index]);
        }
        for (ItemStack talisman : accessories.loadActiveTalismans(player)) {
            addItemSource(player, talisman, PassiveSourceType.TALISMAN, null,
                    candidates, null, issues);
        }
        addItemSource(player, accessories.loadImprint(player), PassiveSourceType.IMPRINT, null,
                candidates, setPieces, issues);

        Map<String, SetState> setStates = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : setPieces.entrySet()) {
            EquipmentSetRegistry.EquipmentSetDefinition set = setRegistry.get(entry.getKey());
            if (set == null) {
                issues.add("未知套装 " + entry.getKey());
                continue;
            }
            int pieces = entry.getValue().size();
            List<Integer> activeThresholds = new ArrayList<>();
            List<EquipmentSetRegistry.SetThreshold> satisfied = set.thresholds().stream()
                    .filter(threshold -> threshold.pieces() <= pieces)
                    .toList();
            if (set.thresholdMode() == EquipmentSetRegistry.ThresholdMode.HIGHEST_ONLY && !satisfied.isEmpty()) {
                satisfied = List.of(satisfied.get(satisfied.size() - 1));
            }
            for (EquipmentSetRegistry.SetThreshold threshold : satisfied) {
                activeThresholds.add(threshold.pieces());
                for (CustomItemRegistry.AbilityDefinition ability : threshold.abilities()) {
                    addAbilityCandidate(
                            ability,
                            PassiveSourceType.SET_BONUS,
                            "set:" + set.id() + ":" + threshold.pieces(),
                            set.id(),
                            candidates,
                            issues
                    );
                }
            }
            setStates.put(set.id(), new SetState(
                    set.displayName(),
                    pieces,
                    Set.copyOf(entry.getValue().keySet()),
                    List.copyOf(activeThresholds)
            ));
        }

        return new PassiveSnapshot(
                resolveStacking(candidates, issues),
                Map.copyOf(setStates),
                List.copyOf(issues)
        );
    }

    private void addItemSource(Player player, ItemStack item, PassiveSourceType sourceType, EquipmentSlot equipmentSlot,
                               List<PassiveAbilityInstance> candidates, Map<String, Map<String, String>> setPieces,
                               List<String> issues) {
        addItemSource(player, item, sourceType, equipmentSlot, candidates, setPieces, issues, null);
    }

    private void addItemSource(Player player, ItemStack item, PassiveSourceType sourceType, EquipmentSlot equipmentSlot,
                               List<PassiveAbilityInstance> candidates, Map<String, Map<String, String>> setPieces,
                               List<String> issues, String expectedAccessoryType) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        CustomItemRegistry registry = CustomItemRegistry.getInstance();
        AccessoryManager accessoryManager = AccessoryManager.getInstance();
        if (registry == null || accessoryManager == null) {
            return;
        }
        CustomItemRegistry.CustomItemDefinition definition = registry.getDefinition(registry.getItemId(item));
        if (definition == null) {
            if (sourceType == PassiveSourceType.IMPRINT || sourceType == PassiveSourceType.TALISMAN) {
                issues.add(sourceType + " 中存在未注册物品");
            }
            return;
        }
        if (!sourceIsValid(player, item, definition, sourceType, equipmentSlot, expectedAccessoryType)) {
            issues.add(definition.displayName() + " 的 " + sourceType + " 来源无效");
            return;
        }

        String sourceId = accessoryManager.ensureItemInstanceId(item);
        for (CustomItemRegistry.AbilityDefinition ability : definition.abilities()) {
            if (ability.trigger().equals("PASSIVE")) {
                addAbilityCandidate(ability, sourceType, sourceId, definition.id(), candidates, issues);
            }
        }

        if (setPieces != null && !definition.setId().isBlank() && !definition.setPieceId().isBlank()) {
            setPieces.computeIfAbsent(definition.setId(), ignored -> new LinkedHashMap<>())
                    .putIfAbsent(definition.setPieceId(), sourceId);
        }
    }

    private boolean sourceIsValid(Player player, ItemStack item, CustomItemRegistry.CustomItemDefinition definition,
                                  PassiveSourceType sourceType, EquipmentSlot equipmentSlot,
                                  String expectedAccessoryType) {
        RequirementManager requirements = RequirementManager.getInstance();
        if (requirements != null && !requirements.meetsRequirement(player, item)) {
            return false;
        }
        if (sourceType == PassiveSourceType.IMPRINT) {
            AccessoryManager manager = AccessoryManager.getInstance();
            return manager != null && manager.isImprintEligible(item);
        }
        if (sourceType == PassiveSourceType.TALISMAN) {
            return definition.accessoryType().equalsIgnoreCase("TALISMAN");
        }
        if (sourceType == PassiveSourceType.ACCESSORY) {
            return expectedAccessoryType != null
                    && definition.accessoryType().equalsIgnoreCase(expectedAccessoryType);
        }
        if (sourceType == PassiveSourceType.MAIN_HAND || sourceType == PassiveSourceType.OFF_HAND) {
            WeaponTemplateManager weapons = WeaponTemplateManager.getInstance();
            return weapons == null || weapons.getEquipmentStatMultiplier(player, item, equipmentSlot) > 0.0;
        }
        if (sourceType == PassiveSourceType.ARMOR) {
            return matchesArmorSlot(item, equipmentSlot);
        }
        return true;
    }

    private boolean matchesArmorSlot(ItemStack item, EquipmentSlot slot) {
        if (slot == null) {
            return false;
        }
        String name = item.getType().name();
        return switch (slot) {
            case HEAD -> name.endsWith("_HELMET");
            case CHEST -> name.endsWith("_CHESTPLATE");
            case LEGS -> name.endsWith("_LEGGINGS");
            case FEET -> name.endsWith("_BOOTS");
            default -> false;
        };
    }

    private void addAbilityCandidate(CustomItemRegistry.AbilityDefinition ability, PassiveSourceType sourceType,
                                     String sourceId, String itemId, List<PassiveAbilityInstance> candidates,
                                     List<String> issues) {
        PassiveAbilityRegistry registry = PassiveAbilityRegistry.getInstance();
        PassiveAbilityRegistry.PassiveDefinition definition = registry == null ? null : registry.get(ability.id());
        if (definition == null) {
            issues.add(itemId + " 引用了未定义能力 " + ability.id());
            return;
        }
        if (!isHandlerImplemented(definition.handler())) {
            issues.add(definition.displayName() + " 尚未实现处理器 " + definition.handler());
            return;
        }
        if (!definition.allowedSources().contains(sourceType)) {
            issues.add(definition.displayName() + " 不支持来源 " + sourceType);
            return;
        }
        if (sourceType == PassiveSourceType.IMPRINT
                && !booleanOption(ability.options(), "imprint_enabled", true)) {
            return;
        }
        if (!allowedByAbilityOption(ability.options(), sourceType)) {
            return;
        }

        Map<String, Object> options = new LinkedHashMap<>(definition.defaultOptions());
        options.putAll(ability.options());
        candidates.add(new PassiveAbilityInstance(
                ability.id(),
                definition,
                Map.copyOf(options),
                sourceType,
                sourceId,
                itemId,
                intOption(options, "priority", 0),
                stringOption(options, "stacking", "UNIQUE").toUpperCase(Locale.ROOT),
                Math.max(0, ability.cooldown()),
                stringOption(options, "cooldown_scope", "SHARED").toUpperCase(Locale.ROOT),
                stringOption(options, "cooldown_group", "")
        ));
    }

    private boolean allowedByAbilityOption(Map<String, Object> options, PassiveSourceType sourceType) {
        Object raw = options.get("allowed_sources");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return true;
        }
        for (Object value : list) {
            if (sourceType.name().equalsIgnoreCase(String.valueOf(value))) {
                return true;
            }
        }
        return false;
    }

    private List<PassiveAbilityInstance> resolveStacking(List<PassiveAbilityInstance> candidates, List<String> issues) {
        Map<String, List<PassiveAbilityInstance>> byAbility = new LinkedHashMap<>();
        Set<String> seenAbilitySources = new HashSet<>();
        for (PassiveAbilityInstance candidate : candidates) {
            String uniqueSource = candidate.abilityId() + "|" + candidate.sourceId();
            if (!seenAbilitySources.add(uniqueSource)) {
                issues.add(candidate.abilityId() + " 检测到重复物品实例来源 " + candidate.sourceId());
                continue;
            }
            byAbility.computeIfAbsent(candidate.abilityId(), ignored -> new ArrayList<>()).add(candidate);
        }
        List<PassiveAbilityInstance> active = new ArrayList<>();
        for (Map.Entry<String, List<PassiveAbilityInstance>> entry : byAbility.entrySet()) {
            List<PassiveAbilityInstance> values = entry.getValue();
            values.sort(Comparator
                    .comparingInt(PassiveAbilityInstance::priority).reversed()
                    .thenComparing(PassiveAbilityInstance::sourceId));
            boolean stack = values.stream().anyMatch(value -> value.stacking().equals("STACK"));
            if (stack) {
                active.addAll(values);
            } else {
                active.add(values.get(0));
                if (values.size() > 1 && values.get(0).priority() == values.get(1).priority()
                        && !values.get(0).options().equals(values.get(1).options())) {
                    issues.add(entry.getKey() + " 存在同优先级、不同参数的 UNIQUE 来源");
                }
            }
        }
        active.sort(Comparator.comparing(PassiveAbilityInstance::abilityId)
                .thenComparing(PassiveAbilityInstance::sourceId));
        return List.copyOf(active);
    }

    private boolean cooldownReady(Player player, PassiveAbilityInstance instance) {
        AbilityCooldownService service = AbilityCooldownService.getInstance();
        return service == null || service.isReady(player, cooldownKey(service, instance));
    }

    private long remainingCooldown(Player player, PassiveAbilityInstance instance) {
        AbilityCooldownService service = AbilityCooldownService.getInstance();
        return service == null ? 0L : service.remainingMillis(player, cooldownKey(service, instance));
    }

    private void startCooldown(Player player, PassiveAbilityInstance instance) {
        if (instance.cooldownSeconds() <= 0) {
            return;
        }
        AbilityCooldownService service = AbilityCooldownService.getInstance();
        if (service != null) {
            service.start(player, cooldownKey(service, instance), instance.cooldownSeconds() * 1000L);
        }
    }

    private String cooldownKey(AbilityCooldownService service, PassiveAbilityInstance instance) {
        return service.key(instance.abilityId(), instance.cooldownGroup(),
                instance.cooldownScope(), instance.sourceId());
    }

    private void notifyStateChanges(Player player, PassiveSnapshot previous, PassiveSnapshot next) {
        if (previous == null) {
            return;
        }
        Set<String> previousKeys = new HashSet<>();
        for (PassiveAbilityInstance ability : previous.activeAbilities()) {
            previousKeys.add(ability.abilityId() + "|" + ability.sourceId() + "|" + ability.options());
        }
        Set<String> nextKeys = new HashSet<>();
        for (PassiveAbilityInstance ability : next.activeAbilities()) {
            nextKeys.add(ability.abilityId() + "|" + ability.sourceId() + "|" + ability.options());
        }
        // Activation/deactivation hooks intentionally remain side-effect free in the first version.
        // The comparison exists so visual handlers can be added without changing snapshot semantics.
        previousKeys.equals(nextKeys);
    }

    private void tickPeriodicPassives() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isDead() || player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            boolean hasPeriodic = getSnapshot(player).activeAbilities().stream()
                    .anyMatch(ability -> ability.definition().periodTicks() > 0
                            && booleanOption(ability.options(), "periodic", false));
            if (!hasPeriodic) {
                continue;
            }
            // Periodic handlers are opt-in. The scheduler only visits players who actually own one.
        }
    }

    private double readStat(Map<String, Object> options, String statKey) {
        Object stats = options.get("stats");
        if (stats instanceof ConfigurationSection section) {
            return section.getDouble(statKey, 0.0);
        }
        if (stats instanceof Map<?, ?> map) {
            return number(map.get(statKey), 0.0);
        }
        if (stringOption(options, "stat", "").equalsIgnoreCase(statKey)) {
            return doubleOption(options, "amount", 0.0);
        }
        return 0.0;
    }

    private double doubleOption(Map<String, Object> options, String key, double fallback) {
        return number(options.get(key), fallback);
    }

    private static int intOption(Map<String, Object> options, String key, int fallback) {
        Object raw = options.get(key);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return raw == null ? fallback : Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static boolean booleanOption(Map<String, Object> options, String key, boolean fallback) {
        Object raw = options.get(key);
        return raw == null ? fallback : Boolean.parseBoolean(String.valueOf(raw));
    }

    private static String stringOption(Map<String, Object> options, String key, String fallback) {
        Object raw = options.get(key);
        return raw == null ? fallback : String.valueOf(raw);
    }

    private static double number(Object raw, double fallback) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return raw == null ? fallback : Double.parseDouble(String.valueOf(raw));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private double maxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        return attribute == null ? 20.0 : Math.max(1.0, attribute.getValue());
    }

    private void clearNegativeEffects(Player player) {
        for (PotionEffectType type : List.of(
                PotionEffectType.POISON,
                PotionEffectType.WITHER,
                PotionEffectType.SLOWNESS,
                PotionEffectType.WEAKNESS,
                PotionEffectType.BLINDNESS,
                PotionEffectType.MINING_FATIGUE,
                PotionEffectType.NAUSEA
        )) {
            player.removePotionEffect(type);
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private long formatSeconds(long millis) {
        return Math.max(1L, (long) Math.ceil(millis / 1000.0));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        scheduleRefresh(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        AbilityCooldownService cooldowns = AbilityCooldownService.getInstance();
        if (cooldowns != null) {
            cooldowns.unload(event.getPlayer());
        }
        snapshots.remove(event.getPlayer().getUniqueId());
        refreshQueued.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        scheduleRefresh(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        scheduleRefresh(event.getPlayer());
    }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent event) {
        scheduleRefresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        scheduleRefresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        scheduleRefresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmor(PlayerArmorChangeEvent event) {
        scheduleRefresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            scheduleRefresh(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            scheduleRefresh(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            scheduleRefresh(player);
        }
    }

    public record PassiveAbilityInstance(
            String abilityId,
            PassiveAbilityRegistry.PassiveDefinition definition,
            Map<String, Object> options,
            PassiveSourceType sourceType,
            String sourceId,
            String itemId,
            int priority,
            String stacking,
            int cooldownSeconds,
            String cooldownScope,
            String cooldownGroup
    ) {
    }

    public record SetState(
            String displayName,
            int pieces,
            Set<String> pieceIds,
            List<Integer> activeThresholds
    ) {
    }

    public record PassiveSnapshot(
            List<PassiveAbilityInstance> activeAbilities,
            Map<String, SetState> sets,
            List<String> issues
    ) {
        private static PassiveSnapshot empty() {
            return new PassiveSnapshot(List.of(), Map.of(), List.of());
        }
    }
}
