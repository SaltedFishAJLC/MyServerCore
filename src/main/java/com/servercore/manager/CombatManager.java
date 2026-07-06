package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import com.servercore.combat.damage.DamageCategory;
import com.servercore.combat.damage.DamagePacket;
import com.servercore.combat.damage.DamageResult;
import com.servercore.combat.damage.DamageService;
import com.servercore.combat.damage.DamageSourceKind;
import com.servercore.combat.damage.DamageTag;
import com.servercore.combat.integration.EnchantTargetMatcher;
import com.servercore.enchant.EnchantDamageContext;
import com.servercore.enchant.EnchantEffectService;
import com.servercore.enchant.RangedEmpowermentManager;
import com.servercore.passive.PassiveSnapshotService;
import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.stat.Stats;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.attribute.Attribute;
import org.bukkit.Particle;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CombatManager implements Listener {

    private final AuraSkillsApi auraSkills;
    private final Map<EntityDamageEvent, AttackContext> attackContexts = new IdentityHashMap<>();

    public CombatManager(ServerCorePlugin plugin) {
        this.auraSkills = AuraSkillsApi.get();
        // 注册监听器
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /**
     * 我们选择拦截底层的 EntityDamageByEntityEvent 并设置优先级为 HIGHEST。
     * 【架构师注】：
     * MythicLib 拥有其自身的 DamageMetadata 与 Stat 系统（例如 PVE_DAMAGE 等修饰器）。
     * 但根据蓝图的“严格数值正交化公式”，直接接管底层的 Bukkit 事件是保障公式不被任何第三方（包括 MythicLib）
     * 内部机制污染（即数值膨胀）的最暴力、最有效的手段。
     * 如果后期发现 MythicLib 的特有机制（如技能暴击）需要保留，我们可以再接入 MythicLib 的 `io.lumine.mythic.lib.api.event.PlayerAttackEvent`。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerAttack(EntityDamageByEntityEvent event) {
        if (DamageService.isInternalDamageActive() || EnchantDamageContext.isSecondaryDamage()) {
            return;
        }

        Player player = null;
        boolean isRanged = false;
        
        // 区分近战与远程
        if (event.getDamager() instanceof Player p) {
            player = p;
        } else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            player = p;
            isRanged = true;
        }
        
        if (player == null) {
            return;
        }

        if (!isRanged) {
            WeaponTemplateManager templateManager = WeaponTemplateManager.getInstance();
            if (isRangedWeaponMeleeAttempt(player, templateManager)) {
                event.setCancelled(true);
                event.setDamage(0.0);
                player.sendActionBar(net.kyori.adventure.text.Component.text("远程武器需要通过投射物造成伤害。"));
                return;
            }
            WeaponTemplateManager.HandValidationResult handValidation = templateManager == null
                    ? null
                    : templateManager.validateHands(player);
            if (handValidation != null && !handValidation.canUseMainWeapon()) {
                event.setCancelled(true);
                player.sendActionBar(net.kyori.adventure.text.Component.text(handValidation.reason()));
                return;
            }
        }

        // 获取玩家的动态属性快照
        CombatStats stats = getCombatStats(player);
        ClassManager classManager = ClassManager.getInstance();
        ClassPassiveManager passiveManager = ClassPassiveManager.getInstance();
        ItemStack weaponSnapshot = player.getInventory().getItemInMainHand().clone();
        
        double finalDamage = 0.0;
        boolean isMagic = event.getCause() == DamageCause.MAGIC;
        boolean spellbladeMelee = classManager != null && classManager.usesSpellbladeMeleeDamage(player, isRanged, isMagic);
        boolean usesMagicDamage = isMagic || spellbladeMelee;
        boolean isCrit = false;
        List<Runnable> successfulHitCommits = new ArrayList<>();
        
        // 1. 获取纯净的基础面板伤害
        double baseDamage;
        if (isRanged || isMagic) {
            // 抛射物和魔法伤害已经包含了其蓄力/法术基础强度
            baseDamage = event.getDamage() + stats.baseDamage(); 
        } else {
            // 近战：使用 Paper API 的 getAttackCooldown() 直接获取蓄力比例 (0.0~1.0)
            // 由于原版武器的 GENERIC_ATTACK_DAMAGE 已被 ItemStandardizer 归零，
            // 不能再通过 event.getDamage() / vanillaMax 来反推蓄力比例。
            float cooldownRatio = player.getAttackCooldown();
            
            // 注意：原版暴击已经不影响伤害了，因为 event.getDamage() 基于我们归零后的 1.0 计算
            // 暴击完全由我们的 CombatStats.critChance/critDamage 接管
            
            // 获取锋利附魔带来的真实额外伤害
            double sharpnessDamage = 0.0;
            org.bukkit.inventory.ItemStack weapon = player.getInventory().getItemInMainHand();
            if (weapon != null && weapon.hasItemMeta()) {
                int sharpness = weapon.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SHARPNESS);
                if (sharpness > 0) {
                    sharpnessDamage = 0.5 * sharpness + 0.5;
                }
            }
            
            // 最终基础伤害 = PDC面板伤害 * 蓄力比例 + 锋利附魔独立加成
            baseDamage = (stats.baseDamage() * cooldownRatio) + sharpnessDamage;
        }

        // 2. 流派分支计算
        if (usesMagicDamage) {
            // 【魔法流派】: 面板 * 乘区(含法强)。魔剑士近战也走该乘区。
            // 法强 = (最大法力 - 100) / 400
            double maxMana = 100.0;
            AttributeManager attributeManager = AttributeManager.getInstance();
            if (attributeManager != null) {
                maxMana = attributeManager.getEffectiveMaxMana(player);
            } else if (auraSkills != null && auraSkills.getUser(player.getUniqueId()) != null) {
                maxMana = auraSkills.getUser(player.getUniqueId()).getMaxMana();
            }
            double magicPower = Math.max(0.0, (maxMana - 100.0) / 400.0);
            double classMagicMultiplier = classManager == null ? 0.0 : classManager.getMagicMultiplierBonus(player);
            double skillMultiplier = getSkillMultiplier(player, "sorcery");
            double magicMultiplier = stats.baseMultiplier() + magicPower + classMagicMultiplier;
            
            finalDamage = baseDamage * magicMultiplier * skillMultiplier;

            if (spellbladeMelee) {
                double brutalityMultiplier = rollBrutalityMultiplier(stats.brutality());
                finalDamage *= brutalityMultiplier;
            }
            
        } else {
            // 【物理流派（近战/远程）】: 面板 * 暴击 * 乘区 * (破甲/残暴)
            if (passiveManager == null) {
                isCrit = Math.random() < stats.critChance();
            } else {
                ClassPassiveManager.CriticalRollPlan criticalPlan =
                        passiveManager.previewCritical(player, stats.critChance());
                isCrit = criticalPlan.critical();
                successfulHitCommits.add(criticalPlan.commit());
            }
            double critMult = isCrit ? stats.critDamage() : 1.0;
            double skillMultiplier = getSkillMultiplier(player, isRanged ? "archery" : "fighting");
            double reaperMultiplier = event.getEntity() instanceof LivingEntity target && passiveManager != null
                    ? passiveManager.getReaperDamageMultiplier(player, target)
                    : 1.0;
            double multiplierDamage = baseDamage * stats.baseMultiplier() * skillMultiplier * reaperMultiplier;
            
            if (isRanged) {
                // 远程伤害
                double armorPenMult = 1.0 + clamp(stats.armorPen() / 100.0, 0.0, 1.0);
                double classRangedMultiplier = classManager == null ? 1.0 : classManager.getPhysicalRangedDamageMultiplier(player);
                finalDamage = multiplierDamage * critMult * armorPenMult * classRangedMultiplier;
            } else {
                // 近战伤害
                double brutalityMultiplier = rollBrutalityMultiplier(stats.brutality());
                finalDamage = multiplierDamage * critMult * brutalityMultiplier;
            }
        }

        if (event.getEntity() instanceof LivingEntity target) {
            double targetMultiplier = EnchantTargetMatcher.resolveHighestMultiplier(weaponSnapshot, target);
            finalDamage *= targetMultiplier;

            EnchantEffectService enchantEffects = EnchantEffectService.getInstance();
            if (enchantEffects != null) {
                RangedEmpowermentManager empowermentManager = RangedEmpowermentManager.getInstance();
                if (empowermentManager != null) {
                    RangedEmpowermentManager.ProjectileDamagePlan empowermentPlan =
                            empowermentManager.previewProjectileDamage(
                                    player, event.getDamager(), target, weaponSnapshot, finalDamage);
                    finalDamage = empowermentPlan.damage();
                    successfulHitCommits.add(empowermentPlan.commit());
                }
                EnchantEffectService.OutgoingDamagePlan enchantPlan =
                        enchantEffects.previewOutgoingDamage(
                                player, target, weaponSnapshot, finalDamage, isRanged, usesMagicDamage, isCrit);
                finalDamage = enchantPlan.damage();
                successfulHitCommits.add(enchantPlan.commit());
            }
            PassiveSnapshotService passiveService = PassiveSnapshotService.getInstance();
            if (passiveService != null) {
                finalDamage = passiveService.modifyOutgoingDamage(player, target, finalDamage);
            }

            DamageService damageService = DamageService.getInstance();
            DamageCategory category = usesMagicDamage ? DamageCategory.MAGIC : DamageCategory.PHYSICAL;
            Set<DamageTag> tags = EnumSet.noneOf(DamageTag.class);
            if (isRanged) {
                tags.add(DamageTag.PROJECTILE);
            } else if (!usesMagicDamage || spellbladeMelee) {
                tags.add(DamageTag.MELEE);
            }
            DamageSourceKind sourceKind = isRanged
                    ? DamageSourceKind.VANILLA_PROJECTILE
                    : usesMagicDamage && !spellbladeMelee
                            ? DamageSourceKind.CUSTOM_SKILL
                            : DamageSourceKind.VANILLA_ATTACK;
            double preHitHealth = damageService == null
                    ? target.getHealth()
                    : damageService.getEffectiveHealth(target);
            double lifestealRate = stats.lifesteal();
            if (classManager != null) {
                lifestealRate *= classManager.getLifestealMultiplier(player);
            }
            Runnable commit = combine(successfulHitCommits);
            attackContexts.put(event, new AttackContext(
                    player,
                    target,
                    weaponSnapshot,
                    isRanged,
                    usesMagicDamage,
                    spellbladeMelee,
                    isCrit,
                    Math.max(0.0, lifestealRate),
                    Math.max(0.0, preHitHealth),
                    commit
            ));
            if (damageService != null) {
                damageService.prepareEventDamage(event, new DamagePacket(
                        player,
                        target,
                        finalDamage,
                        category,
                        tags,
                        sourceKind,
                        "player_attack"
                ));
            } else {
                event.setDamage(finalDamage);
            }
            return;
        }

        event.setDamage(finalDamage);
    }

    /**
     * 从极速缓存中提取玩家属性快照并结合主手武器计算总属性
     */
    private CombatStats getCombatStats(Player player) {
        return CombatStats.getFullStats(player);
    }

    private boolean isRangedWeaponMeleeAttempt(Player player, WeaponTemplateManager templateManager) {
        if (player == null || templateManager == null) {
            return false;
        }
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        WeaponTemplateManager.WeaponTemplate template = templateManager.getTemplate(mainHand);
        if (template == null && mainHand != null) {
            template = templateManager.getDefaultTemplate(mainHand.getType());
        }
        return template != null && template.isRanged() && !template.isMelee();
    }

    private double rollBrutalityMultiplier(double brutality) {
        double safeBrutality = Math.max(0.0, brutality);
        int guaranteedExtraHits = (int) (safeBrutality / 100.0);
        double chance = safeBrutality % 100.0;

        int extraHits = guaranteedExtraHits;
        if (Math.random() < (chance / 100.0)) {
            extraHits++;
        }
        return 1.0 + extraHits;
    }

    private double getSkillMultiplier(Player player, String skillType) {
        AuraSkillsBridge bridge = AuraSkillsBridge.getInstance();
        if (bridge == null) {
            return 1.0;
        }

        return Math.max(0.1, 1.0 + bridge.getCombatSkillMultiplier(player, skillType));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDamaged(EntityDamageEvent event) {
        if (DamageService.isInternalDamageActive() || EnchantDamageContext.isSecondaryDamage()) {
            return;
        }

        if (!(event.getEntity() instanceof Player player)) return;
        if (event instanceof EntityDamageByEntityEvent byEntityEvent) {
            if (byEntityEvent.getDamager() instanceof Player) return;
            if (byEntityEvent.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player) return;
        }

        double incomingDamage = event.getDamage();
        PDCManager pdc = PDCManager.getInstance();
        LivingEntity attacker = MobDamageSourceResolver.resolveMobAttacker(event);

        if (pdc != null) {
            if (attacker != null) {
                double mobAttackDamage = attacker.getPersistentDataContainer()
                        .getOrDefault(pdc.KEY_MOB_ATTACK_DAMAGE, PersistentDataType.DOUBLE, 0.0);
                if (mobAttackDamage > 0.0) {
                    incomingDamage = mobAttackDamage;
                }
            }
        }

        AttributeManager attributeManager = AttributeManager.getInstance();
        boolean magic = attributeManager != null && attributeManager.isMagicDamageCause(event.getCause());
        DamageCategory category = magic ? DamageCategory.MAGIC : DamageCategory.PHYSICAL;
        Set<DamageTag> tags = tagsFor(event);
        DamageSourceKind sourceKind = sourceKindFor(event);
        DamageService damageService = DamageService.getInstance();
        if (damageService != null) {
            damageService.prepareEventDamage(event, new DamagePacket(
                    attacker,
                    player,
                    incomingDamage,
                    category,
                    tags,
                    sourceKind,
                    "native_incoming:" + event.getCause().name().toLowerCase(java.util.Locale.ROOT)
            ));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerAttackMonitor(EntityDamageByEntityEvent event) {
        AttackContext context = attackContexts.remove(event);
        if (context == null) {
            return;
        }

        DamageService damageService = DamageService.getInstance();
        DamageResult result = damageService == null
                ? null
                : damageService.finalizeEvent(event);
        if (event.isCancelled()) {
            return;
        }

        double observedDamage = result == null
                ? Math.max(0.0, event.getFinalDamage())
                : Math.max(0.0, result.actualDamage());
        double actualDamage = Math.min(observedDamage, context.preHitEffectiveHealth());
        if (actualDamage <= 0.0) {
            return;
        }

        context.successfulHitCommit().run();
        if (context.critical()) {
            context.target().getWorld().spawnParticle(
                    Particle.CRIT,
                    context.target().getLocation().add(0, 1, 0),
                    15, 0.5, 0.5, 0.5, 0.1
            );
        }
        ClassPassiveManager passiveManager = ClassPassiveManager.getInstance();
        if (passiveManager != null) {
            passiveManager.onPlayerDealtDamage(context.player(), context.target());
            passiveManager.applyLifesteal(
                    context.player(),
                    actualDamage,
                    context.lifestealRate(),
                    context.ranged(),
                    context.magic(),
                    context.spellbladeMelee()
            );
        }
        EnchantEffectService enchantEffects = EnchantEffectService.getInstance();
        if (enchantEffects != null) {
            enchantEffects.afterPlayerAttack(
                    event,
                    context.player(),
                    context.target(),
                    context.weapon(),
                    actualDamage,
                    context.ranged(),
                    context.magic(),
                    context.critical()
            );
        }
        PassiveSnapshotService passiveService = PassiveSnapshotService.getInstance();
        if (passiveService != null) {
            passiveService.afterPlayerAttack(
                    context.player(),
                    context.target(),
                    actualDamage,
                    !context.ranged() && (!context.magic() || context.spellbladeMelee())
            );
        }
    }

    private Set<DamageTag> tagsFor(EntityDamageEvent event) {
        EnumSet<DamageTag> tags = EnumSet.noneOf(DamageTag.class);
        if (event instanceof EntityDamageByEntityEvent byEntityEvent) {
            if (byEntityEvent.getDamager() instanceof Projectile) {
                tags.add(DamageTag.PROJECTILE);
            } else {
                tags.add(DamageTag.MELEE);
            }
        }
        if (event.getCause() == DamageCause.ENTITY_EXPLOSION
                || event.getCause() == DamageCause.BLOCK_EXPLOSION) {
            tags.add(DamageTag.EXPLOSION);
            tags.add(DamageTag.AOE);
        }
        return tags;
    }

    private DamageSourceKind sourceKindFor(EntityDamageEvent event) {
        if (event instanceof EntityDamageByEntityEvent byEntityEvent
                && byEntityEvent.getDamager() instanceof Projectile) {
            return DamageSourceKind.VANILLA_PROJECTILE;
        }
        if (event instanceof EntityDamageByEntityEvent) {
            return DamageSourceKind.VANILLA_ATTACK;
        }
        return DamageSourceKind.VANILLA_ENVIRONMENT;
    }

    private Runnable combine(List<Runnable> actions) {
        List<Runnable> snapshot = List.copyOf(actions);
        return () -> snapshot.forEach(Runnable::run);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record AttackContext(
            Player player,
            LivingEntity target,
            ItemStack weapon,
            boolean ranged,
            boolean magic,
            boolean spellbladeMelee,
            boolean critical,
            double lifestealRate,
            double preHitEffectiveHealth,
            Runnable successfulHitCommit
    ) {
    }
}
