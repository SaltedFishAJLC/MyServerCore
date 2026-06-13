package com.servercore;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import com.servercore.combat.creature.CreatureTagService;
import com.servercore.combat.damage.DamageService;
import com.servercore.combat.damage.VanillaDamageAdapter;
import com.servercore.combat.resistance.ResistanceResolver;
import com.servercore.combat.resistance.TagRuleRegistry;
import com.servercore.combat.status.FrostService;
import com.servercore.combat.status.StatusService;
import com.servercore.combat.status.StunController;
import com.servercore.enchant.EnchantAnvilListener;
import com.servercore.enchant.EnchantApplyResult;
import com.servercore.enchant.EnchantDefinition;
import com.servercore.enchant.EnchantEffectService;
import com.servercore.enchant.EnchantGrindstoneListener;
import com.servercore.enchant.EnchantRegistry;
import com.servercore.enchant.EnchantStatResolver;
import com.servercore.enchant.EnchantTableListener;
import com.servercore.manager.DatabaseManager;
import com.servercore.manager.EconomyManager;
import com.servercore.manager.EconomyListener;
import com.servercore.manager.PDCManager;
import com.servercore.manager.VaultImplementer;
import com.servercore.manager.CombatStats;
import com.servercore.manager.CombatManager;
import com.servercore.manager.AccessoryManager;
import com.servercore.manager.PlayerStatCache;
import com.servercore.manager.AccessoryListener;
import com.servercore.manager.ItemFormatManager;
import com.servercore.manager.ItemStandardizer;
import com.servercore.manager.ReforgeManager;
import com.servercore.manager.GemstoneManager;
import com.servercore.manager.EnchantManager;
import com.servercore.manager.RecycleManager;
import com.servercore.manager.SoulContainerManager;
import com.servercore.manager.StashManager;
import com.servercore.manager.DeathListener;
import com.servercore.manager.PowerLevelManager;
import com.servercore.manager.HologramManager;
import com.servercore.manager.MobSpawnManager;
import com.servercore.manager.CustomMobRegistry;
import com.servercore.manager.MobReplacementManager;
import com.servercore.manager.CustomRecipeManager;
import com.servercore.manager.CustomItemRegistry;
import com.servercore.manager.RequirementManager;
import com.servercore.manager.ScoreboardManager;
import com.servercore.manager.AttributeManager;
import com.servercore.manager.AuraSkillsBridge;
import com.servercore.manager.AuraSkillsMenuHijacker;
import com.servercore.manager.CollectionSkillManager;
import com.servercore.manager.ClassPassiveManager;
import com.servercore.manager.ClassManager;
import com.servercore.manager.FishingManager;
import com.servercore.manager.GlobalStatManager;
import com.servercore.manager.MiningManager;
import com.servercore.manager.NonCombatStatsMenu;
import com.servercore.manager.RangedWeaponManager;
import com.servercore.manager.ShieldManager;
import com.servercore.manager.UniqueMobSpawnManager;
import com.servercore.manager.VanillaItemOverrideManager;
import com.servercore.manager.WeaponAbilityManager;
import com.servercore.manager.WeaponTemplateManager;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public final class ServerCorePlugin extends JavaPlugin {

    // 缓存 MiniMessage 实例供全局单例调用，遵循 1.21 Paper 文本展示规范
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    
    private static ServerCorePlugin instance;
    
    private DatabaseManager databaseManager;
    private EconomyManager economyManager;
    private com.servercore.manager.AccessoryListener accListener;
    private PowerLevelManager powerLevelManager;
    private ScoreboardManager scoreboardManager;
    private AttributeManager attributeManager;
    private AuraSkillsBridge auraSkillsBridge;
    private CustomMobRegistry customMobRegistry;
    private CustomRecipeManager customRecipeManager;
    private CustomItemRegistry customItemRegistry;
    private ItemFormatManager itemFormatManager;
    private ReforgeManager reforgeManager;
    private GemstoneManager gemstoneManager;
    private EnchantManager enchantManager;
    private RecycleManager recycleManager;
    private GlobalStatManager globalStatManager;
    private MiningManager miningManager;
    private FishingManager fishingManager;
    private WeaponTemplateManager weaponTemplateManager;
    private MobSpawnManager mobSpawnManager;
    private MobReplacementManager mobReplacementManager;
    private VanillaItemOverrideManager vanillaItemOverrideManager;
    private UniqueMobSpawnManager uniqueMobSpawnManager;
    private ShieldManager shieldManager;
    private ClassPassiveManager classPassiveManager;
    private StatusService statusService;
    private FrostService frostService;
    private StunController stunController;
    private int banFlowerDeliveryTaskId = -1;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        
        long startTime = System.currentTimeMillis();

        // 1. 基础日志输出 (使用 ComponentLogger 与 MiniMessage 渲染)
        getComponentLogger().info(MINI_MESSAGE.deserialize("<gradient:#00ff00:#00aa00>ServerCore 插件正在启动...</gradient>"));

        // 2. 检查前置依赖 (PAPI 挂载检测)
        setupPlaceholderAPI();

        this.auraSkillsBridge = new AuraSkillsBridge(this);
        new AuraSkillsMenuHijacker(this, auraSkillsBridge);

        // 3. 启动 UI 管理系统 (ActionBar 刷新任务)
        new com.servercore.manager.ActionBarManager(this).start();

        // 4. 初始化战斗数值引擎
        new CombatManager(this);
        
        // 5. 初始化数据库与经济模块
        this.databaseManager = new DatabaseManager(this);
        this.economyManager = new EconomyManager(databaseManager);
        new EconomyListener(this, economyManager);
        
        // 5.5 初始化附属系统
        new PDCManager(this);
        new EnchantRegistry(this);
        new EnchantStatResolver();
        CreatureTagService creatureTagService = new CreatureTagService(this);
        TagRuleRegistry tagRuleRegistry = new TagRuleRegistry(this);
        ResistanceResolver resistanceResolver = new ResistanceResolver(creatureTagService, tagRuleRegistry);
        DamageService damageService = new DamageService(this, resistanceResolver);
        this.stunController = new StunController(this, resistanceResolver);
        this.statusService = new StatusService(this, damageService, resistanceResolver);
        this.frostService = new FrostService(this, resistanceResolver, stunController);
        new VanillaDamageAdapter(this, damageService, statusService, frostService, stunController);
        new AccessoryManager(this);
        new PlayerStatCache();
        new ClassManager(this);
        this.weaponTemplateManager = new WeaponTemplateManager(this);
        this.shieldManager = new ShieldManager(this);
        this.attributeManager = new AttributeManager(this);
        this.globalStatManager = new GlobalStatManager(this);
        new CollectionSkillManager(this, globalStatManager);
        this.fishingManager = new FishingManager(this, globalStatManager);
        this.miningManager = new MiningManager(this, globalStatManager);
        new RequirementManager(this);
        this.accListener = new AccessoryListener(this);
        this.itemFormatManager = new ItemFormatManager(this);
        this.reforgeManager = new ReforgeManager(this);
        this.gemstoneManager = new GemstoneManager(this);
        this.enchantManager = new EnchantManager(this);
        new EnchantEffectService(this);
        new EnchantTableListener(this);
        new EnchantAnvilListener(this);
        new EnchantGrindstoneListener(this);
        this.recycleManager = new RecycleManager(this, economyManager);
        this.customItemRegistry = new CustomItemRegistry(this);
        this.vanillaItemOverrideManager = new VanillaItemOverrideManager(this);
        new ItemStandardizer(this);
        new WeaponAbilityManager(this);

        // 注册"小红花"技能处理器
        customItemRegistry.registerAbilityHandler("ban_flower", context -> {
            Player player = context.player();
            int seconds = 30 + (int)(Math.random() * 61); // 30-90s 随机封禁
            String reason = "小红花惩罚：你被封禁了 " + seconds + " 秒";
            java.util.Date expires = java.util.Date.from(java.time.Instant.now().plusSeconds(seconds));
            org.bukkit.BanList<org.bukkit.profile.PlayerProfile> banList = org.bukkit.Bukkit.getBanList(org.bukkit.BanList.Type.PROFILE);
            banList.addBan(player.getPlayerProfile(), reason, expires, "小红花");
            player.kick(MINI_MESSAGE.deserialize("<red>" + reason + "</red>"));

            // 全服广播
            var msg = MINI_MESSAGE.deserialize("<yellow><bold>" + player.getName() + " 因触碰小红花被封印了 " + seconds + " 秒！</bold></yellow>");
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendMessage(msg);
            }
            getComponentLogger().info(MINI_MESSAGE.deserialize("<yellow>" + player.getName() + " 触发小红花封禁 " + seconds + "s</yellow>"));
            return true;
        });

        // 启动小红花随机快递定时器
        startBanFlowerDelivery();

        new RangedWeaponManager(this);
        this.classPassiveManager = new ClassPassiveManager(this);
        this.customMobRegistry = new CustomMobRegistry(this);
        this.customMobRegistry.loadConfig();
        this.customRecipeManager = new CustomRecipeManager(this);
        this.powerLevelManager = new PowerLevelManager(this);
        powerLevelManager.start();
        HologramManager hologramManager = new HologramManager(this);
        this.mobSpawnManager = new MobSpawnManager(this, powerLevelManager, hologramManager, economyManager, customMobRegistry);
        this.mobReplacementManager = new MobReplacementManager(this, customMobRegistry);
        this.uniqueMobSpawnManager = new UniqueMobSpawnManager(this, customMobRegistry);
        this.scoreboardManager = new ScoreboardManager(this, powerLevelManager, economyManager);
        scoreboardManager.start();
        
        SoulContainerManager soulContainerManager = new SoulContainerManager(this, databaseManager);
        new StashManager(this, databaseManager);
        new DeathListener(this, soulContainerManager, economyManager);

        // 6. 注册 Vault 服务
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            try {
                // 绕过 Shadow 插件在 Gradle 9+ 的 ASM org.objectweb.asm.Type LDC 映射 Bug
                @SuppressWarnings("unchecked")
                Class<Economy> ecoClass = (Class<Economy>) Class.forName("net.milkbowl.vault.economy.Economy");
                getServer().getServicesManager().register(ecoClass, new VaultImplementer(economyManager), this, ServicePriority.Highest);
                getComponentLogger().info(MINI_MESSAGE.deserialize("<aqua>✓ 已成功挂载 Vault 经济系统服务！</aqua>"));
            } catch (ClassNotFoundException e) {
                getComponentLogger().error(MINI_MESSAGE.deserialize("<red>⚠ 找不到 Vault API 类，挂载失败！</red>"));
            }
        } else {
            getComponentLogger().warn(MINI_MESSAGE.deserialize("<red>⚠ 未找到 Vault 插件，经济桥接注册失败！</red>"));
        }

        // 7. 注册 PDC 管理器与自定义指令
        getCommand("servercore").setExecutor(new CommandExecutor() {
            @Override
            public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("该指令仅限玩家使用！");
                    return true;
                }

                if (args.length >= 1
                        && (args[0].equalsIgnoreCase("recipe")
                        || args[0].equalsIgnoreCase("recipes")
                        || args[0].equalsIgnoreCase("recipebook"))) {
                    if (args.length >= 2) {
                        customRecipeManager.openRecipeUses(player, args[1]);
                    } else {
                        customRecipeManager.openRecipeUses(player, player.getInventory().getItemInMainHand());
                    }
                    return true;
                }
                
                if (args.length >= 4
                        && args[0].equalsIgnoreCase("admin")
                        && args[1].equalsIgnoreCase("recipe")
                        && args[2].equalsIgnoreCase("create")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>浣犳病鏈夋潈闄愶紒</red>"));
                        return true;
                    }

                    String recipeId = args[3];
                    if (!CustomRecipeManager.isValidRecipeId(recipeId)) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>Recipe ID must use only letters, numbers, _ or -.</red>"));
                        return true;
                    }

                    customRecipeManager.openRecipeBuilder(player, recipeId);
                    return true;
                } else if (args.length == 3
                        && args[0].equalsIgnoreCase("admin")
                        && args[1].equalsIgnoreCase("recipe")
                        && args[2].equalsIgnoreCase("reload")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>浣犳病鏈夋潈闄愶紒</red>"));
                        return true;
                    }

                    customRecipeManager.reloadRecipes();
                    player.sendMessage(MINI_MESSAGE.deserialize("<green>Custom recipes reloaded: " + customRecipeManager.getRegisteredRecipeCount() + "</green>"));
                    return true;
                } else if (args.length == 3
                        && args[0].equalsIgnoreCase("admin")
                        && args[1].equalsIgnoreCase("mobs")
                        && args[2].equalsIgnoreCase("reload")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>浣犳病鏈夋潈闄愶紒</red>"));
                        return true;
                    }

                    customMobRegistry.loadConfig();
                    CreatureTagService tagService = CreatureTagService.getInstance();
                    if (tagService != null) {
                        tagService.reload();
                    }
                    TagRuleRegistry ruleRegistry = TagRuleRegistry.getInstance();
                    if (ruleRegistry != null) {
                        ruleRegistry.reload();
                    }
                    int replacementRules = mobReplacementManager == null ? 0 : mobReplacementManager.reload();
                    player.sendMessage(MINI_MESSAGE.deserialize("<green>Custom mob rules reloaded: " + customMobRegistry.getRuleCount()
                            + "</green> <dark_gray>|</dark_gray> <green>Mob replacements: " + replacementRules + "</green>"));
                    return true;
                } else if (args.length == 3
                        && args[0].equalsIgnoreCase("admin")
                        && args[1].equalsIgnoreCase("mobs")
                        && args[2].equalsIgnoreCase("list")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>你没有权限！</red>"));
                        return true;
                    }

                    String ids = String.join(", ", customMobRegistry.getRuleIds());
                    player.sendMessage(MINI_MESSAGE.deserialize(ids.isBlank()
                            ? "<yellow>No custom mob rules loaded.</yellow>"
                            : "<green>Custom mob rules:</green> <white>" + ids + "</white>"));
                    return true;
                } else if (args.length >= 4
                        && args[0].equalsIgnoreCase("admin")
                        && args[1].equalsIgnoreCase("mobs")
                        && args[2].equalsIgnoreCase("summon")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>你没有权限！</red>"));
                        return true;
                    }

                    int amount = 1;
                    if (args.length >= 5) {
                        try {
                            amount = Math.max(1, Math.min(50, Integer.parseInt(args[4])));
                        } catch (NumberFormatException exception) {
                            player.sendMessage(MINI_MESSAGE.deserialize("<red>Amount must be a number.</red>"));
                            return true;
                        }
                    }

                    int spawned = 0;
                    for (int index = 0; index < amount; index++) {
                        org.bukkit.entity.LivingEntity entity = customMobRegistry.spawnConfiguredMob(args[3], player.getLocation());
                        if (entity == null) {
                            break;
                        }
                        MobSpawnManager mobSpawnManager = MobSpawnManager.getInstance();
                        if (mobSpawnManager != null) {
                            mobSpawnManager.applyCustomMobScaling(entity, args[3]);
                        }
                        spawned++;
                    }

                    player.sendMessage(MINI_MESSAGE.deserialize(spawned == 0
                            ? "<red>Unknown or unspawnable custom mob rule: " + args[3] + "</red>"
                            : "<green>Summoned custom mob:</green> <white>" + args[3] + " x" + spawned + "</white>"));
                    return true;
                } else if (args.length == 3
                        && args[0].equalsIgnoreCase("admin")
                        && (args[1].equalsIgnoreCase("mobreplacements")
                        || args[1].equalsIgnoreCase("mob-replacements")
                        || args[1].equalsIgnoreCase("spawnreplacements"))
                        && args[2].equalsIgnoreCase("reload")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>浣犳病鏈夋潈闄愶紒</red>"));
                        return true;
                    }

                    int loaded = mobReplacementManager == null ? 0 : mobReplacementManager.reload();
                    player.sendMessage(MINI_MESSAGE.deserialize("<green>Mob replacement rules reloaded:</green> <white>" + loaded + "</white>"));
                    return true;
                } else if (args.length == 3
                        && args[0].equalsIgnoreCase("admin")
                        && (args[1].equalsIgnoreCase("mobreplacements")
                        || args[1].equalsIgnoreCase("mob-replacements")
                        || args[1].equalsIgnoreCase("spawnreplacements"))
                        && args[2].equalsIgnoreCase("list")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>浣犳病鏈夋潈闄愶紒</red>"));
                        return true;
                    }

                    String ids = mobReplacementManager == null ? "" : String.join(", ", mobReplacementManager.getRuleIds());
                    player.sendMessage(MINI_MESSAGE.deserialize(ids.isBlank()
                            ? "<yellow>No mob replacement rules loaded.</yellow>"
                            : "<green>Mob replacement rules:</green> <white>" + ids + "</white>"));
                    return true;
                } else if (args.length == 3
                        && args[0].equalsIgnoreCase("admin")
                        && (args[1].equalsIgnoreCase("uniquespawns")
                        || args[1].equalsIgnoreCase("unique-spawns")
                        || args[1].equalsIgnoreCase("structurespawns")
                        || args[1].equalsIgnoreCase("structuremobs"))
                        && args[2].equalsIgnoreCase("reload")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>浣犳病鏈夋潈闄愶紒</red>"));
                        return true;
                    }

                    int loaded = uniqueMobSpawnManager == null ? 0 : uniqueMobSpawnManager.reload();
                    player.sendMessage(MINI_MESSAGE.deserialize("<green>Unique mob spawns reloaded:</green> <white>" + loaded + "</white>"));
                    return true;
                } else if (args.length == 3
                        && args[0].equalsIgnoreCase("admin")
                        && (args[1].equalsIgnoreCase("uniquespawns")
                        || args[1].equalsIgnoreCase("unique-spawns")
                        || args[1].equalsIgnoreCase("structurespawns")
                        || args[1].equalsIgnoreCase("structuremobs"))
                        && args[2].equalsIgnoreCase("list")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>浣犳病鏈夋潈闄愶紒</red>"));
                        return true;
                    }

                    String ids = uniqueMobSpawnManager == null ? "" : String.join(", ", uniqueMobSpawnManager.getSpawnIds());
                    player.sendMessage(MINI_MESSAGE.deserialize(ids.isBlank()
                            ? "<yellow>No unique mob spawns loaded.</yellow>"
                            : "<green>Unique mob spawns:</green> <white>" + ids + "</white>"));
                    return true;
                } else if (args.length >= 4
                        && args[0].equalsIgnoreCase("admin")
                        && (args[1].equalsIgnoreCase("uniquespawns")
                        || args[1].equalsIgnoreCase("unique-spawns")
                        || args[1].equalsIgnoreCase("structurespawns")
                        || args[1].equalsIgnoreCase("structuremobs"))
                        && args[2].equalsIgnoreCase("reset")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>浣犳病鏈夋潈闄愶紒</red>"));
                        return true;
                    }

                    boolean reset = uniqueMobSpawnManager != null && uniqueMobSpawnManager.reset(args[3]);
                    player.sendMessage(MINI_MESSAGE.deserialize(reset
                            ? "<green>Unique mob spawn reset:</green> <white>" + args[3] + "</white>"
                            : "<red>Unknown unique mob spawn:</red> <white>" + args[3] + "</white>"));
                    return true;
                } else if (args.length == 3
                        && args[0].equalsIgnoreCase("admin")
                        && (args[1].equalsIgnoreCase("names") || args[1].equalsIgnoreCase("itemnames"))
                        && args[2].equalsIgnoreCase("reload")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>娴ｇ姵鐥呴張澶嬫綀闂勬劧绱?/red>"));
                        return true;
                    }

                    itemFormatManager.reloadNameMappings();
                    itemFormatManager.formatInventory(player.getInventory());
                    player.sendMessage(MINI_MESSAGE.deserialize("<green>Item name mappings reloaded.</green>"));
                    return true;
                } else if (args.length == 3
                        && args[0].equalsIgnoreCase("admin")
                        && (args[1].equalsIgnoreCase("recycle") || args[1].equalsIgnoreCase("salvage"))
                        && args[2].equalsIgnoreCase("reload")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>娴ｇ姵鐥呴張澶嬫綀闂勬劧绱?/red>"));
                        return true;
                    }

                    recycleManager.reloadConfig();
                    player.sendMessage(MINI_MESSAGE.deserialize("<green>Recycle prices reloaded.</green>"));
                    return true;
                } else if (args.length == 3
                        && args[0].equalsIgnoreCase("admin")
                        && (args[1].equalsIgnoreCase("items") || args[1].equalsIgnoreCase("customitems"))
                        && args[2].equalsIgnoreCase("reload")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>娴ｇ姵鐥呴張澶嬫綀闂勬劧绱?/red>"));
                        return true;
                    }

                    customItemRegistry.reloadItems();
                    if (vanillaItemOverrideManager != null) {
                        vanillaItemOverrideManager.reload();
                        vanillaItemOverrideManager.applyInventory(player);
                    }
                    itemFormatManager.formatInventory(player.getInventory());
                    player.sendMessage(MINI_MESSAGE.deserialize("<green>Custom item templates reloaded: " + customItemRegistry.getItemCount() + "</green>"));
                    return true;
                } else if (args.length == 3
                        && args[0].equalsIgnoreCase("admin")
                        && (args[1].equalsIgnoreCase("vanillaitems")
                        || args[1].equalsIgnoreCase("vanilla-items")
                        || args[1].equalsIgnoreCase("itemoverrides"))
                        && args[2].equalsIgnoreCase("reload")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>濞达絿濮甸惀鍛村嫉婢跺缍€闂傚嫭鍔х槐?/red>"));
                        return true;
                    }

                    int loaded = vanillaItemOverrideManager == null ? 0 : vanillaItemOverrideManager.reload();
                    if (vanillaItemOverrideManager != null) {
                        vanillaItemOverrideManager.applyInventory(player);
                    }
                    itemFormatManager.formatInventory(player.getInventory());
                    player.sendMessage(MINI_MESSAGE.deserialize("<green>Vanilla item overrides reloaded:</green> <white>" + loaded + "</white>"));
                    return true;
                } else if (args.length == 3
                        && args[0].equalsIgnoreCase("admin")
                        && (args[1].equalsIgnoreCase("vanillaitems")
                        || args[1].equalsIgnoreCase("vanilla-items")
                        || args[1].equalsIgnoreCase("itemoverrides"))
                        && args[2].equalsIgnoreCase("list")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>濞达絿濮甸惀鍛村嫉婢跺缍€闂傚嫭鍔х槐?/red>"));
                        return true;
                    }

                    String ids = vanillaItemOverrideManager == null ? "" : String.join(", ", vanillaItemOverrideManager.getOverrideIds());
                    player.sendMessage(MINI_MESSAGE.deserialize(ids.isBlank()
                            ? "<yellow>No vanilla item overrides loaded.</yellow>"
                            : "<green>Vanilla item overrides:</green> <white>" + ids + "</white>"));
                    return true;
                } else if (args.length == 3
                        && args[0].equalsIgnoreCase("admin")
                        && (args[1].equalsIgnoreCase("gatheringloot") || args[1].equalsIgnoreCase("loot"))
                        && args[2].equalsIgnoreCase("reload")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>你没有权限！</red>"));
                        return true;
                    }

                    CollectionSkillManager collectionSkillManager = CollectionSkillManager.getInstance();
                    int loaded = collectionSkillManager == null ? 0 : collectionSkillManager.reloadLootTables();
                    if (fishingManager != null) {
                        loaded += fishingManager.reloadLootTables();
                    }
                    if (miningManager != null) {
                        loaded += miningManager.reloadLootTables();
                    }
                    player.sendMessage(MINI_MESSAGE.deserialize("<green>Gathering loot tables reloaded: " + loaded + "</green>"));
                    return true;
                } else if (args.length == 3
                        && args[0].equalsIgnoreCase("admin")
                        && (args[1].equalsIgnoreCase("items") || args[1].equalsIgnoreCase("customitems"))
                        && args[2].equalsIgnoreCase("list")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>娴ｇ姵鐥呴張澶嬫綀闂勬劧绱?/red>"));
                        return true;
                    }

                    String ids = String.join(", ", customItemRegistry.getItemIds());
                    player.sendMessage(MINI_MESSAGE.deserialize(ids.isBlank()
                            ? "<yellow>No custom item templates loaded.</yellow>"
                            : "<green>Custom items:</green> <white>" + ids + "</white>"));
                    return true;
                } else if (args.length >= 4
                        && args[0].equalsIgnoreCase("admin")
                        && (args[1].equalsIgnoreCase("items") || args[1].equalsIgnoreCase("customitems"))
                        && (args[2].equalsIgnoreCase("id")
                        || args[2].equalsIgnoreCase("setid")
                        || args[2].equalsIgnoreCase("set-id"))) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>你没有权限！</red>"));
                        return true;
                    }

                    ItemStack item = player.getInventory().getItemInMainHand();
                    CustomItemRegistry.SaveResult result = customItemRegistry.setHeldItemId(item, args[3]);
                    if (result.success()) {
                        player.getInventory().setItemInMainHand(item);
                    }
                    player.sendMessage(MINI_MESSAGE.deserialize(result.success()
                            ? "<green>" + result.message() + "</green> <white>" + result.itemId() + "</white>"
                            : "<red>" + result.message() + "</red>"));
                    return true;
                } else if (args.length >= 3
                        && args[0].equalsIgnoreCase("admin")
                        && (args[1].equalsIgnoreCase("items") || args[1].equalsIgnoreCase("customitems"))
                        && args[2].equalsIgnoreCase("save")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>你没有权限！</red>"));
                        return true;
                    }

                    ItemStack item = player.getInventory().getItemInMainHand();
                    String itemId = args.length >= 4 ? args[3] : "";
                    CustomItemRegistry.SaveResult result = customItemRegistry.saveHeldItemTemplate(item, itemId);
                    if (result.success()) {
                        player.getInventory().setItemInMainHand(item);
                    }
                    player.sendMessage(MINI_MESSAGE.deserialize(result.success()
                            ? "<green>" + result.message() + "</green> <white>" + result.itemId() + "</white>"
                            : "<red>" + result.message() + "</red>"));
                    return true;
                } else if (args.length >= 4
                        && args[0].equalsIgnoreCase("admin")
                        && (args[1].equalsIgnoreCase("items") || args[1].equalsIgnoreCase("customitems"))
                        && args[2].equalsIgnoreCase("give")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>娴ｇ姵鐥呴張澶嬫綀闂勬劧绱?/red>"));
                        return true;
                    }

                    int amount = -1;
                    if (args.length >= 5) {
                        try {
                            amount = Math.max(1, Integer.parseInt(args[4]));
                        } catch (NumberFormatException exception) {
                            player.sendMessage(MINI_MESSAGE.deserialize("<red>Amount must be a number.</red>"));
                            return true;
                        }
                    }

                    ItemStack item = customItemRegistry.createItem(args[3], amount);
                    if (item == null) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>Unknown custom item id: " + args[3] + "</red>"));
                        return true;
                    }

                    java.util.Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
                    for (ItemStack leftover : overflow.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), leftover);
                    }
                    player.sendMessage(MINI_MESSAGE.deserialize("<green>Gave custom item:</green> <white>" + args[3] + "</white>"));
                    return true;
                } else if (args.length >= 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("rarity")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>浣犳病鏈夋潈闄愶紒</red>"));
                        return true;
                    }

                    ItemStack item = player.getInventory().getItemInMainHand();
                    if (item.getType().isAir()) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>Hold an item first.</red>"));
                        return true;
                    }

                    try {
                        itemFormatManager.setRarity(item, ItemFormatManager.Rarity.valueOf(args[2].toUpperCase(java.util.Locale.ROOT)));
                        player.getInventory().setItemInMainHand(item);
                        player.sendMessage(MINI_MESSAGE.deserialize("<green>Rarity updated.</green>"));
                    } catch (IllegalArgumentException exception) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>Unknown rarity. Use COMMON, UNCOMMON, RARE, EPIC, LEGENDARY, MYTHIC.</red>"));
                    }
                    return true;
                } else if (args.length >= 3 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("reforge")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>浣犳病鏈夋潈闄愶紒</red>"));
                        return true;
                    }

                    ItemStack item = player.getInventory().getItemInMainHand();
                    if (item.getType().isAir()) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>Hold an item first.</red>"));
                        return true;
                    }

                    if (args[2].equalsIgnoreCase("clear")) {
                        reforgeManager.clearReforge(item);
                        player.sendMessage(MINI_MESSAGE.deserialize("<green>Reforge cleared.</green>"));
                    } else {
                        boolean applied = reforgeManager.applyReforge(item, args[2]);
                        if (!applied) {
                            player.sendMessage(MINI_MESSAGE.deserialize("<red>This reforge cannot be applied to the held item.</red>"));
                            return true;
                        }
                        player.sendMessage(MINI_MESSAGE.deserialize("<green>Reforge updated.</green>"));
                    }
                    player.getInventory().setItemInMainHand(item);
                    return true;
                } else if (args.length >= 5
                        && args[0].equalsIgnoreCase("admin")
                        && args[1].equalsIgnoreCase("gem")
                        && args[2].equalsIgnoreCase("socket")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>浣犳病鏈夋潈闄愶紒</red>"));
                        return true;
                    }

                    ItemStack item = player.getInventory().getItemInMainHand();
                    if (item.getType().isAir()) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>Hold an item first.</red>"));
                        return true;
                    }

                    try {
                        GemstoneManager.SocketType type = GemstoneManager.SocketType.valueOf(args[3].toUpperCase(java.util.Locale.ROOT));
                        int amount = Math.max(1, Integer.parseInt(args[4]));
                        gemstoneManager.addSockets(item, type, amount);
                        player.getInventory().setItemInMainHand(item);
                        player.sendMessage(MINI_MESSAGE.deserialize("<green>Socket added.</green>"));
                    } catch (IllegalArgumentException exception) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>Usage: /sc admin gem socket <WEAPON|ARMOR|TOOL|UNIVERSAL> <amount></red>"));
                    }
                    return true;
                } else if (args.length >= 4
                        && args[0].equalsIgnoreCase("admin")
                        && args[1].equalsIgnoreCase("gem")
                        && args[2].equalsIgnoreCase("apply")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>浣犳病鏈夋潈闄愶紒</red>"));
                        return true;
                    }

                    ItemStack item = player.getInventory().getItemInMainHand();
                    if (item.getType().isAir()) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>Hold an item first.</red>"));
                        return true;
                    }

                    boolean applied = gemstoneManager.applyGemstone(item, args[3]);
                    player.getInventory().setItemInMainHand(item);
                    player.sendMessage(MINI_MESSAGE.deserialize(applied ? "<green>Gemstone applied.</green>" : "<red>No compatible empty socket.</red>"));
                    return true;
                } else if (args.length >= 2
                        && args[0].equalsIgnoreCase("reload")
                        && args[1].equalsIgnoreCase("enchants")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>你没有权限！</red>"));
                        return true;
                    }

                    EnchantRegistry registry = EnchantRegistry.getInstance();
                    if (registry != null) {
                        registry.reload();
                    }
                    player.sendMessage(MINI_MESSAGE.deserialize("<green>Custom enchants reloaded.</green>"));
                    return true;
                } else if (args.length >= 1 && args[0].equalsIgnoreCase("enchant")) {
                    return handleEnchantCommand(player, args);
                } else if (args.length >= 4 && args[0].equalsIgnoreCase("admin") && args[1].equalsIgnoreCase("enchant")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>浣犳病鏈夋潈闄愶紒</red>"));
                        return true;
                    }

                    ItemStack item = player.getInventory().getItemInMainHand();
                    if (item.getType().isAir()) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>Hold an item first.</red>"));
                        return true;
                    }

                    try {
                        EnchantApplyResult result = enchantManager.addCustomEnchantChecked(item, args[2], Integer.parseInt(args[3]));
                        if (result.success()) {
                            player.getInventory().setItemInMainHand(item);
                            player.sendMessage(MINI_MESSAGE.deserialize("<green>Custom enchant updated.</green>"));
                        } else {
                            player.sendMessage(MINI_MESSAGE.deserialize("<red>" + result.message() + "</red>"));
                        }
                    } catch (NumberFormatException exception) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>Level must be a number.</red>"));
                    }
                    return true;
                } else if (args.length >= 2 && args[0].equalsIgnoreCase("debug")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>你没有权限！</red>"));
                        return true;
                    }

                    String debugType = args[1].toLowerCase(java.util.Locale.ROOT);
                    if (debugType.equals("power")) {
                        PowerLevelManager.PowerBreakdown breakdown = powerLevelManager.calculatePowerBreakdown(player);
                        player.sendMessage(MINI_MESSAGE.deserialize("<gold>Power Debug</gold>"));
                        player.sendMessage(MINI_MESSAGE.deserialize("<gray>TargetPower:</gray> <white>" + String.format(java.util.Locale.US, "%.2f", breakdown.targetPower()) + "</white>"));
                        player.sendMessage(MINI_MESSAGE.deserialize("<gray>SpawnPower:</gray> <white>" + String.format(java.util.Locale.US, "%.2f", breakdown.spawnPower()) + "</white>"));
                        player.sendMessage(MINI_MESSAGE.deserialize("<gray>DPS melee/ranged/magic:</gray> <red>" + String.format(java.util.Locale.US, "%.1f", breakdown.meleeDps()) + "</red><dark_gray> / </dark_gray><green>" + String.format(java.util.Locale.US, "%.1f", breakdown.rangedDps()) + "</green><dark_gray> / </dark_gray><light_purple>" + String.format(java.util.Locale.US, "%.1f", breakdown.magicDps()) + "</light_purple>"));
                        player.sendMessage(MINI_MESSAGE.deserialize("<gray>EHP:</gray> <aqua>" + String.format(java.util.Locale.US, "%.1f", breakdown.effectiveHealth()) + "</aqua> <dark_gray>|</dark_gray> <gray>Sustain:</gray> <green>+" + String.format(java.util.Locale.US, "%.0f", breakdown.sustainFactor() * 100.0) + "%</green>"));
                        return true;
                    }

                    if (debugType.equals("weapon")) {
                        WeaponTemplateManager templateManager = WeaponTemplateManager.getInstance();
                        ItemStack mainHand = player.getInventory().getItemInMainHand();
                        WeaponTemplateManager.WeaponTemplate template = templateManager == null ? null : templateManager.getTemplate(mainHand);
                        if (template == null && templateManager != null) {
                            template = templateManager.getDefaultTemplate(mainHand.getType());
                        }
                        WeaponTemplateManager.WeaponProfile profile = templateManager == null ? null : templateManager.getProfile(template);
                        WeaponTemplateManager.HandValidationResult validation = templateManager == null ? null : templateManager.validateHands(player);
                        player.sendMessage(MINI_MESSAGE.deserialize("<gold>Weapon Debug</gold>"));
                        player.sendMessage(MINI_MESSAGE.deserialize("<gray>Main template:</gray> <white>" + (template == null ? "NONE" : template.name()) + "</white>"));
                        if (profile != null) {
                            player.sendMessage(MINI_MESSAGE.deserialize("<gray>speed/range/cooldown:</gray> <white>" + String.format(java.util.Locale.US, "%.2f", profile.attackSpeed()) + "</white><dark_gray> / </dark_gray><white>" + String.format(java.util.Locale.US, "%.1f", profile.attackRange()) + "</white><dark_gray> / </dark_gray><white>" + profile.cooldownTicks() + "t</white>"));
                            player.sendMessage(MINI_MESSAGE.deserialize("<gray>hand rule:</gray> <white>" + profile.defaultHandRule().name() + "</white> <dark_gray>|</dark_gray> <gray>reliability/uptime:</gray> <white>" + String.format(java.util.Locale.US, "%.2f", profile.reliabilityFactor()) + "</white><dark_gray> / </dark_gray><white>" + String.format(java.util.Locale.US, "%.2f", profile.uptimeFactor()) + "</white>"));
                        }
                        if (validation != null) {
                            player.sendMessage(MINI_MESSAGE.deserialize("<gray>usable main/off/shield:</gray> <white>" + validation.canUseMainWeapon() + "</white><dark_gray> / </dark_gray><white>" + validation.canUseOffhandWeapon() + "</white><dark_gray> / </dark_gray><white>" + validation.canUseShield() + "</white>"));
                            if (!validation.reason().isBlank()) {
                                player.sendMessage(MINI_MESSAGE.deserialize("<yellow>" + validation.reason() + "</yellow>"));
                            }
                        }
                        return true;
                    }

                    if (debugType.equals("shield")) {
                        org.bukkit.attribute.AttributeInstance maxHealthAttribute = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
                        double maxHealth = maxHealthAttribute == null ? 20.0 : maxHealthAttribute.getValue();
                        double shieldValue = shieldManager == null ? 0.0 : shieldManager.estimateShieldValuePerSecond(player, maxHealth);
                        player.sendMessage(MINI_MESSAGE.deserialize("<gold>Shield Debug</gold>"));
                        player.sendMessage(MINI_MESSAGE.deserialize("<gray>estimated shield value/s:</gray> <white>" + String.format(java.util.Locale.US, "%.2f", shieldValue) + "</white>"));
                        return true;
                    }

                    if (debugType.equals("damage")) {
                        CombatStats stats = CombatStats.getFullStats(player);
                        player.sendMessage(MINI_MESSAGE.deserialize("<gold>Damage Debug</gold>"));
                        player.sendMessage(MINI_MESSAGE.deserialize("<gray>damage/mult:</gray> <white>" + String.format(java.util.Locale.US, "%.1f", stats.baseDamage()) + "</white><dark_gray> / </dark_gray><white>" + String.format(java.util.Locale.US, "%.2f", stats.baseMultiplier()) + "</white>"));
                        player.sendMessage(MINI_MESSAGE.deserialize("<gray>crit/brutality/lifesteal:</gray> <yellow>" + String.format(java.util.Locale.US, "%.1f", stats.critChance() * 100.0) + "%</yellow><dark_gray> / </dark_gray><dark_red>" + String.format(java.util.Locale.US, "%.1f", stats.brutality()) + "</dark_red><dark_gray> / </dark_gray><red>" + String.format(java.util.Locale.US, "%.1f", stats.lifesteal() * 100.0) + "%</red>"));
                        return true;
                    }

                    if (debugType.equals("moblevel")) {
                        if (args.length < 3) {
                            player.sendMessage(MINI_MESSAGE.deserialize("<red>Usage: /sc debug moblevel <mobRuleId></red>"));
                            return true;
                        }
                        String mobId = args[2];
                        if (!customMobRegistry.hasRule(mobId)) {
                            player.sendMessage(MINI_MESSAGE.deserialize("<red>Unknown custom mob rule: " + mobId + "</red>"));
                            return true;
                        }
                        player.sendMessage(MINI_MESSAGE.deserialize("<gold>Mob Level Debug</gold>"));
                        player.sendMessage(MINI_MESSAGE.deserialize("<gray>mode:</gray> <white>" + customMobRegistry.getLevelMode(mobId).name() + "</white>"));
                        player.sendMessage(MINI_MESSAGE.deserialize("<gray>base/min/max/scale:</gray> <white>" + String.format(java.util.Locale.US, "%.1f", customMobRegistry.getBaseLevel(mobId)) + "</white><dark_gray> / </dark_gray><white>" + String.format(java.util.Locale.US, "%.1f", customMobRegistry.getMinLevel(mobId)) + "</white><dark_gray> / </dark_gray><white>" + String.format(java.util.Locale.US, "%.1f", customMobRegistry.getMaxLevel(mobId)) + "</white><dark_gray> / </dark_gray><white>" + String.format(java.util.Locale.US, "%.2f", customMobRegistry.getPlayerScale(mobId)) + "</white>"));
                        return true;
                    }

                    player.sendMessage(MINI_MESSAGE.deserialize("<yellow>可用调试: /sc debug power, weapon, shield, damage, moblevel <id></yellow>"));
                    return true;
                } else if (args.length >= 3 && args[0].equalsIgnoreCase("item")) {
                    if (!player.hasPermission("servercore.admin")) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>你没有权限！</red>"));
                        return true;
                    }
                    
                    ItemStack item = player.getInventory().getItemInMainHand();
                    if (item.getType().isAir()) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>请将要修改的物品拿在主手中！</red>"));
                        return true;
                    }
                    
                    String statName = args[1].toLowerCase();
                    PDCManager pdc = PDCManager.getInstance();
                    
                    // 特殊处理 acctype
                    if (statName.equals("acctype")) {
                        pdc.setString(item, pdc.KEY_ACC_TYPE, args[2].toLowerCase());
                        player.getInventory().setItemInMainHand(item);
                        player.sendMessage(MINI_MESSAGE.deserialize("<green>已成功将饰品部位设为 " + args[2].toLowerCase() + " !</green>"));
                        return true;
                    }

                    if (statName.equals("template") || statName.equals("weapontemplate") || statName.equals("weapon_template")) {
                        WeaponTemplateManager templateManager = WeaponTemplateManager.getInstance();
                        WeaponTemplateManager.WeaponTemplate template = templateManager == null ? null : templateManager.parseTemplate(args[2]);
                        if (template == null) {
                            player.sendMessage(MINI_MESSAGE.deserialize("<red>Unknown weapon template.</red>"));
                            return true;
                        }

                        templateManager.applyTemplateToItem(item, template);
                        player.getInventory().setItemInMainHand(item);
                        PlayerStatCache statCache = PlayerStatCache.getInstance();
                        if (statCache != null) {
                            statCache.updateCache(player);
                        } else if (attributeManager != null) {
                            attributeManager.refreshPlayer(player);
                        }
                        player.sendMessage(MINI_MESSAGE.deserialize("<green>Weapon template updated: " + template.name() + "</green>"));
                        return true;
                    }

                    if (statName.equals("handrule") || statName.equals("hand_rule") || statName.equals("weaponhand") || statName.equals("weapon_hand_rule")) {
                        WeaponTemplateManager templateManager = WeaponTemplateManager.getInstance();
                        WeaponTemplateManager.HandRule handRule = templateManager == null ? null : templateManager.parseHandRule(args[2]);
                        if (handRule == null) {
                            player.sendMessage(MINI_MESSAGE.deserialize("<red>Unknown hand rule.</red>"));
                            return true;
                        }

                        templateManager.applyHandRuleToItem(item, handRule);
                        player.getInventory().setItemInMainHand(item);
                        PlayerStatCache statCache = PlayerStatCache.getInstance();
                        if (statCache != null) {
                            statCache.updateCache(player);
                        } else if (attributeManager != null) {
                            attributeManager.refreshPlayer(player);
                        }
                        player.sendMessage(MINI_MESSAGE.deserialize("<green>Weapon hand rule updated: " + handRule.name() + "</green>"));
                        return true;
                    }

                    double value;
                    try {
                        value = Double.parseDouble(args[2]);
                    } catch (NumberFormatException e) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>属性值必须是数字！</red>"));
                        return true;
                    }
                    
                    NamespacedKey key = switch (statName) {
                        case "damage" -> pdc.KEY_BASE_DAMAGE;
                        case "mult" -> pdc.KEY_BASE_MULTIPLIER;
                        case "crit" -> pdc.KEY_CRIT_CHANCE;
                        case "critdmg" -> pdc.KEY_CRIT_DAMAGE;
                        case "brutality" -> pdc.KEY_BRUTALITY;
                        case "lifesteal", "life_steal", "vampirism", "vamp" -> pdc.KEY_LIFESTEAL;
                        case "armorpen" -> pdc.KEY_ARMOR_PEN;
                        case "armor" -> pdc.KEY_BASE_ARMOR;
                        case "attackspeed", "attack_speed", "attack_speed_bonus", "aspeed" -> pdc.KEY_ATTACK_SPEED_BONUS;
                        case "shieldthreshold", "shield_threshold", "block_threshold", "shield_block_threshold" -> pdc.KEY_SHIELD_BLOCK_THRESHOLD;
                        case "effectiveblock", "effective_block", "shield_effective_block" -> pdc.KEY_SHIELD_EFFECTIVE_BLOCK;
                        case "shieldcooldown", "shield_cooldown", "shield_cooldown_seconds" -> pdc.KEY_SHIELD_COOLDOWN_SECONDS;
                        case "str", "strength", "tou", "toughness" -> pdc.KEY_ATTR_TOUGHNESS;
                        case "agi", "agility" -> pdc.KEY_ATTR_AGILITY;
                        case "int", "intelligence" -> pdc.KEY_ATTR_INTELLIGENCE;
                        case "wil", "will", "willpower" -> pdc.KEY_ATTR_WILLPOWER;
                        case "luk", "luck" -> pdc.KEY_ATTR_LUCK;
                        case "toolfortune", "fortune", "tf", "tool_fortune" -> pdc.KEY_TOOL_FORTUNE;
                        case "collectionfortune", "gatherfortune", "cf", "collection_fortune" -> pdc.KEY_COLLECTION_FORTUNE;
                        case "foragingfortune", "ff", "foraging_fortune" -> pdc.KEY_FORAGING_FORTUNE;
                        case "bounty", "foragingbounty", "foraging_bounty" -> pdc.KEY_BOUNTY;
                        case "farmingfortune", "farmfortune", "farming_fortune" -> pdc.KEY_FARMING_FORTUNE;
                        case "overbloom", "over_bloom", "bloom" -> pdc.KEY_OVERBLOOM;
                        case "excavationfortune", "digfortune", "excavation_fortune" -> pdc.KEY_EXCAVATION_FORTUNE;
                        case "miningfortune", "minefortune", "mining_fortune" -> pdc.KEY_MINING_FORTUNE;
                        case "toolsweep", "sweep", "tool_sweep" -> pdc.KEY_TOOL_SWEEP;
                        case "collectionsweep", "gathersweep", "collection_sweep" -> pdc.KEY_COLLECTION_SWEEP;
                        case "foragingsweep", "fsweep", "foraging_sweep" -> pdc.KEY_FORAGING_SWEEP;
                        case "toolspread", "spread", "tool_spread" -> pdc.KEY_MINING_SPREAD;
                        case "miningspread", "minespread", "mining_spread" -> pdc.KEY_MINING_SPREAD;
                        case "miningspeed", "toolminingspeed", "tool_mining_speed" -> pdc.KEY_TOOL_MINING_SPEED;
                        case "breakingpower", "bp", "breaking_power" -> pdc.KEY_BREAKING_POWER;
                        case "purity" -> pdc.KEY_PURITY;
                        case "miningpurity", "mpurity", "mining_purity" -> pdc.KEY_MINING_PURITY;
                        case "fishingspeed", "fishspeed", "fishing_speed" -> pdc.KEY_FISHING_SPEED;
                        case "seacreaturechance", "scc", "sea_creature_chance" -> pdc.KEY_SEA_CREATURE_CHANCE;
                        case "treasurechance", "tc", "treasure_chance" -> pdc.KEY_TREASURE_CHANCE;
                        default -> null;
                    };
                    
                    if (key == null) {
                        player.sendMessage(MINI_MESSAGE.deserialize("<red>未知属性！可用: damage, mult, crit, critdmg, brutality, lifesteal, armorpen, armor, str, agi, int, wil, luk, bounty, farmingfortune, overbloom, fishingspeed</red>"));
                        return true;
                    }
                    
                    pdc.setStat(item, key, value);
                    // 必须将修改后的 ItemStack 强制塞回玩家手中，否则 NBT 改变只停留在内存副本！
                    player.getInventory().setItemInMainHand(item);
                    PlayerStatCache statCache = PlayerStatCache.getInstance();
                    if (statCache != null) {
                        statCache.updateCache(player);
                    } else if (attributeManager != null) {
                        attributeManager.refreshPlayer(player);
                    }
                    player.sendMessage(MINI_MESSAGE.deserialize("<green>已成功为手中的物品赋予 " + statName + " = " + value + " !</green>"));
                    return true;
                } else if (args.length == 1 && args[0].equalsIgnoreCase("acc")) {
                    accListener.openAccessoryMenu(player);
                    return true;
                } else if (args.length == 1 && args[0].equalsIgnoreCase("stats")) {
                    new com.servercore.manager.StatsMenu(player).open();
                    return true;
                } else if (args.length == 1
                        && (args[0].equalsIgnoreCase("gathering")
                        || args[0].equalsIgnoreCase("life")
                        || args[0].equalsIgnoreCase("noncombat")
                        || args[0].equalsIgnoreCase("toolstats")
                        || args[0].equalsIgnoreCase("生活")
                        || args[0].equalsIgnoreCase("采集"))) {
                    new NonCombatStatsMenu(player).open();
                    return true;
                } else if (args.length == 1 && args[0].equalsIgnoreCase("class")) {
                    ClassManager classManager = ClassManager.getInstance();
                    if (classManager != null) {
                        classManager.openClassSelectionGUI(player);
                    }
                    return true;
                } else if (args.length == 1 && args[0].equalsIgnoreCase("stash")) {
                    StashManager.getInstance().openStashGUI(player);
                    return true;
                } else if (args.length == 1 && (args[0].equalsIgnoreCase("recycle") || args[0].equalsIgnoreCase("salvage"))) {
                    recycleManager.openRecycleGui(player);
                    return true;
                }
                
                player.sendMessage(MINI_MESSAGE.deserialize("<yellow>可用指令: /sc item <属性> <数值>, /sc recipe [物品id], /sc acc, /sc stats, /sc class, /sc stash, /sc recycle</yellow>"));
                return true;
            }
        });

        // 记录启动耗时
        long timeTaken = System.currentTimeMillis() - startTime;
        getComponentLogger().info(MINI_MESSAGE.deserialize("<green>ServerCore 启动完成! 耗时: <white>" + timeTaken + "ms</white></green>"));
    }

    @Override
    public void onDisable() {
        // 保存所有在线玩家数据
        if (economyManager != null) {
            economyManager.saveAllSync();
        }

        if (powerLevelManager != null) {
            powerLevelManager.stop();
        }

        if (statusService != null) {
            statusService.stop();
        }

        if (frostService != null) {
            frostService.stop();
        }

        if (stunController != null) {
            stunController.stop();
        }

        if (scoreboardManager != null) {
            scoreboardManager.stop();
        }

        if (attributeManager != null) {
            attributeManager.stop();
        }

        if (globalStatManager != null) {
            globalStatManager.stop();
        }

        if (weaponTemplateManager != null) {
            weaponTemplateManager.stop();
        }

        if (classPassiveManager != null) {
            classPassiveManager.stop();
        }

        if (mobSpawnManager != null) {
            mobSpawnManager.stop();
        }

        if (uniqueMobSpawnManager != null) {
            uniqueMobSpawnManager.stop();
        }

        if (miningManager != null) {
            miningManager.stop();
        }

        if (customRecipeManager != null) {
            customRecipeManager.unregisterAll();
        }

        if (banFlowerDeliveryTaskId >= 0) {
            Bukkit.getScheduler().cancelTask(banFlowerDeliveryTaskId);
            banFlowerDeliveryTaskId = -1;
        }

        // 关闭数据库连接池
        if (databaseManager != null) {
            databaseManager.close();
        }

        getComponentLogger().info(MINI_MESSAGE.deserialize("<red>ServerCore 插件已卸载，经济数据已安全保存。</red>"));
    }

    private void setupPlaceholderAPI() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            // PAPI 已安装，可以在这里注册自定义变量扩展 (PlaceholderExpansion)
            getComponentLogger().info(MINI_MESSAGE.deserialize("<aqua>✓ 已检测到 PlaceholderAPI，成功挂载！</aqua>"));
        } else {
            // 警告日志，采用红色醒目提示
            getComponentLogger().warn(MINI_MESSAGE.deserialize("<red>⚠ 未检测到 PlaceholderAPI! 部分依赖 PAPI 的系统可能无法正常工作。</red>"));
        }
    }

    private void startBanFlowerDelivery() {
        startBanFlowerDelivery(false);
    }

    private void startBanFlowerDelivery(boolean shortDelay) {
        long delaySeconds = shortDelay
                ? 3600 + (long)(Math.random() * 601)    // 1h ~ 1h10m, 没人时快速重试
                : 7200 + (long)(Math.random() * 7201);  // 2h ~ 4h, 正常间隔
        banFlowerDeliveryTaskId = Bukkit.getScheduler().runTaskLater(this, () -> {
            var players = new java.util.ArrayList<>(Bukkit.getOnlinePlayers());
            if (players.isEmpty()) {
                scheduleNext(true);
                return;
            }
            Player target = players.get((int)(Math.random() * players.size()));

            ItemStack flower = customItemRegistry.createItem("little_red_flower");
            if (flower == null) {
                scheduleNext(false);
                return;
            }

            java.util.Map<Integer, ItemStack> overflow = target.getInventory().addItem(flower);
            for (ItemStack leftover : overflow.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), leftover);
            }

            target.sendMessage(MINI_MESSAGE.deserialize("<rainbow><bold>♪ 一朵小红花悄悄落入了你的背包... 右键它看看会发生什么？ ♪</bold></rainbow>"));
            getComponentLogger().info(MINI_MESSAGE.deserialize("<light_purple>小红花已派送给 " + target.getName() + "</light_purple>"));

            scheduleNext(false);
        }, delaySeconds * 20).getTaskId();
    }

    private void scheduleNext(boolean shortDelay) {
        banFlowerDeliveryTaskId = -1;
        startBanFlowerDelivery(shortDelay);
    }

    private boolean handleEnchantCommand(Player player, String[] args) {
        if (!player.hasPermission("servercore.admin")) {
            player.sendMessage(MINI_MESSAGE.deserialize("<red>你没有权限！</red>"));
            return true;
        }

        EnchantRegistry registry = EnchantRegistry.getInstance();
        EnchantManager enchantManager = EnchantManager.getInstance();
        if (registry == null || enchantManager == null) {
            player.sendMessage(MINI_MESSAGE.deserialize("<red>附魔系统尚未加载。</red>"));
            return true;
        }

        if (args.length == 1 || args[1].equalsIgnoreCase("help")) {
            player.sendMessage(net.kyori.adventure.text.Component.text("/sc enchant list"));
            player.sendMessage(net.kyori.adventure.text.Component.text("/sc enchant give [player] <enchant_id> <level>"));
            player.sendMessage(net.kyori.adventure.text.Component.text("/sc enchant remove [player] <enchant_id>"));
            player.sendMessage(net.kyori.adventure.text.Component.text("/sc enchant clear [player]"));
            player.sendMessage(net.kyori.adventure.text.Component.text("/sc enchant debug"));
            return true;
        }

        String sub = args[1].toLowerCase(java.util.Locale.ROOT);
        if (sub.equals("list")) {
            player.sendMessage(MINI_MESSAGE.deserialize("<gold>Custom Enchants</gold> <gray>(" + registry.getAllDefinitions().size() + ")</gray>"));
            for (EnchantDefinition definition : registry.getAllDefinitions()) {
                player.sendMessage(net.kyori.adventure.text.Component.text("- ", net.kyori.adventure.text.format.NamedTextColor.GRAY)
                        .append(net.kyori.adventure.text.Component.text(definition.id(), net.kyori.adventure.text.format.NamedTextColor.WHITE))
                        .append(net.kyori.adventure.text.Component.text(" | ", net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY))
                        .append(net.kyori.adventure.text.Component.text(definition.display(), definition.rarity().color()))
                        .append(net.kyori.adventure.text.Component.text(" | ", net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY))
                        .append(net.kyori.adventure.text.Component.text(definition.enabled() ? "enabled" : "disabled",
                                definition.enabled() ? net.kyori.adventure.text.format.NamedTextColor.GREEN : net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY)));
            }
            return true;
        }

        if (sub.equals("give")) {
            if (args.length < 4) {
                player.sendMessage(net.kyori.adventure.text.Component.text("Usage: /sc enchant give [player] <enchant_id> <level>"));
                return true;
            }

            Player target = player;
            String enchantId;
            String levelRaw;
            if (args.length >= 5) {
                target = Bukkit.getPlayerExact(args[2]);
                if (target == null) {
                    player.sendMessage(MINI_MESSAGE.deserialize("<red>目标玩家不在线。</red>"));
                    return true;
                }
                enchantId = args[3];
                levelRaw = args[4];
            } else {
                enchantId = args[2];
                levelRaw = args[3];
            }

            int level;
            try {
                level = Integer.parseInt(levelRaw);
            } catch (NumberFormatException exception) {
                player.sendMessage(MINI_MESSAGE.deserialize("<red>Level must be a number.</red>"));
                return true;
            }

            ItemStack item = target.getInventory().getItemInMainHand();
            if (item.getType().isAir()) {
                player.sendMessage(MINI_MESSAGE.deserialize("<red>目标玩家主手没有物品。</red>"));
                return true;
            }

            EnchantApplyResult result = enchantManager.addCustomEnchantChecked(item, enchantId, level);
            if (!result.success()) {
                player.sendMessage(net.kyori.adventure.text.Component.text(result.message(), net.kyori.adventure.text.format.NamedTextColor.RED));
                return true;
            }
            target.getInventory().setItemInMainHand(item);
            refreshStats(target);
            player.sendMessage(MINI_MESSAGE.deserialize("<green>Custom enchant applied.</green>"));
            return true;
        }

        if (sub.equals("remove")) {
            if (args.length < 3) {
                player.sendMessage(net.kyori.adventure.text.Component.text("Usage: /sc enchant remove [player] <enchant_id>"));
                return true;
            }

            Player target = player;
            String enchantId;
            if (args.length >= 4) {
                target = Bukkit.getPlayerExact(args[2]);
                if (target == null) {
                    player.sendMessage(MINI_MESSAGE.deserialize("<red>目标玩家不在线。</red>"));
                    return true;
                }
                enchantId = args[3];
            } else {
                enchantId = args[2];
            }

            ItemStack item = target.getInventory().getItemInMainHand();
            enchantManager.removeCustomEnchant(item, enchantId);
            target.getInventory().setItemInMainHand(item);
            refreshStats(target);
            player.sendMessage(MINI_MESSAGE.deserialize("<green>Custom enchant removed.</green>"));
            return true;
        }

        if (sub.equals("clear")) {
            Player target = player;
            if (args.length >= 3) {
                target = Bukkit.getPlayerExact(args[2]);
                if (target == null) {
                    player.sendMessage(MINI_MESSAGE.deserialize("<red>目标玩家不在线。</red>"));
                    return true;
                }
            }
            ItemStack item = target.getInventory().getItemInMainHand();
            enchantManager.clearCustomEnchants(item);
            target.getInventory().setItemInMainHand(item);
            refreshStats(target);
            player.sendMessage(MINI_MESSAGE.deserialize("<green>Custom enchants cleared.</green>"));
            return true;
        }

        if (sub.equals("debug")) {
            ItemStack item = player.getInventory().getItemInMainHand();
            java.util.Map<String, Integer> all = enchantManager.getAllCustomEnchants(item);
            java.util.Map<String, Integer> active = enchantManager.getAllActiveCustomEnchants(item);
            player.sendMessage(MINI_MESSAGE.deserialize("<gold>Enchant Debug</gold>"));
            player.sendMessage(net.kyori.adventure.text.Component.text("item: " + (item == null ? "AIR" : item.getType().name())));
            player.sendMessage(net.kyori.adventure.text.Component.text("PDC: " + enchantManager.writeEnchants(all)));
            player.sendMessage(net.kyori.adventure.text.Component.text("active enchants:"));
            for (java.util.Map.Entry<String, Integer> entry : active.entrySet()) {
                EnchantDefinition definition = registry.get(entry.getKey()).orElse(null);
                if (definition == null) continue;
                String numeric = definition.numericBonuses().entrySet().stream()
                        .map(n -> n.getKey() + "=" + String.format(java.util.Locale.US, "%.3f", n.getValue().valueAt(entry.getValue())))
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
                player.sendMessage(net.kyori.adventure.text.Component.text("- " + definition.id() + " " + entry.getValue()
                        + " enabled=true rarity=" + definition.rarity().name()
                        + (numeric.isBlank() ? "" : " numeric[" + numeric + "]")
                        + (definition.effect().hasEffect() ? " effect=" + definition.effect().type().name() : "")));
            }
            player.sendMessage(net.kyori.adventure.text.Component.text("disabled/unknown enchants:"));
            for (java.util.Map.Entry<String, Integer> entry : all.entrySet()) {
                if (active.containsKey(entry.getKey())) continue;
                EnchantDefinition definition = registry.get(entry.getKey()).orElse(null);
                player.sendMessage(net.kyori.adventure.text.Component.text("- " + entry.getKey() + " " + entry.getValue()
                        + " enabled=" + (definition != null && definition.enabled())));
            }
            return true;
        }

        player.sendMessage(net.kyori.adventure.text.Component.text("Usage: /sc enchant <list|give|remove|clear|debug>"));
        return true;
    }

    private void refreshStats(Player player) {
        PlayerStatCache statCache = PlayerStatCache.getInstance();
        if (statCache != null) {
            statCache.updateCache(player);
        } else if (attributeManager != null) {
            attributeManager.refreshPlayer(player);
        }
    }
    
    public static ServerCorePlugin getInstance() {
        return instance;
    }
    
    public static MiniMessage getMiniMessage() {
        return MINI_MESSAGE;
    }
}
