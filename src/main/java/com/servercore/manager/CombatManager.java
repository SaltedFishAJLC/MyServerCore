package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import com.servercore.combat.damage.DamageService;
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

public class CombatManager implements Listener {

    private final AuraSkillsApi auraSkills;

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
        
        double finalDamage = 0.0;
        boolean isMagic = event.getCause() == DamageCause.MAGIC;
        boolean spellbladeMelee = classManager != null && classManager.usesSpellbladeMeleeDamage(player, isRanged, isMagic);
        boolean usesMagicDamage = isMagic || spellbladeMelee;
        double lifestealEffectiveDamage = 0.0;
        boolean isCrit = false;
        
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
            
            // 剥离原版跳劈检测（用于阻止原版暴击，我们自己管理暴击）
            boolean isVanillaCrit = player.getFallDistance() > 0.0F && 
                                    !player.isOnGround() && 
                                    !player.hasPotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS) && 
                                    player.getVehicle() == null && 
                                    !player.isSprinting();
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
                lifestealEffectiveDamage = baseDamage * magicMultiplier * skillMultiplier * brutalityMultiplier;
            }
            
        } else {
            // 【物理流派（近战/远程）】: 面板 * 暴击 * 乘区 * (破甲/残暴)
            isCrit = passiveManager == null
                    ? Math.random() < stats.critChance()
                    : passiveManager.rollCritical(player, stats.critChance());
            double critMult = isCrit ? stats.critDamage() : 1.0;
            double skillMultiplier = getSkillMultiplier(player, isRanged ? "archery" : "fighting");
            double reaperMultiplier = event.getEntity() instanceof LivingEntity target && passiveManager != null
                    ? passiveManager.getReaperDamageMultiplier(player, target)
                    : 1.0;
            double multiplierDamage = baseDamage * stats.baseMultiplier() * skillMultiplier * reaperMultiplier;
            
            if (isCrit && event.getEntity() instanceof LivingEntity target) {
                target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0), 15, 0.5, 0.5, 0.5, 0.1);
            }

            if (isRanged) {
                // 远程伤害
                double armorPenMult = 1.0 + (stats.armorPen() / 100.0); // 暂定基础倍率，未来可根据怪物护甲微调
                double classRangedMultiplier = classManager == null ? 1.0 : classManager.getPhysicalRangedDamageMultiplier(player);
                finalDamage = multiplierDamage * critMult * armorPenMult * classRangedMultiplier;
            } else {
                // 近战伤害
                double brutalityMultiplier = rollBrutalityMultiplier(stats.brutality());
                finalDamage = multiplierDamage * critMult * brutalityMultiplier;
                lifestealEffectiveDamage = multiplierDamage * brutalityMultiplier;
            }
        }

        if (event.getEntity() instanceof LivingEntity target) {
            double targetMultiplier = EnchantTargetMatcher.resolveHighestMultiplier(player.getInventory().getItemInMainHand(), target);
            finalDamage *= targetMultiplier;
            lifestealEffectiveDamage *= targetMultiplier;

            EnchantEffectService enchantEffects = EnchantEffectService.getInstance();
            if (enchantEffects != null) {
                double beforeEffects = finalDamage;
                RangedEmpowermentManager empowermentManager = RangedEmpowermentManager.getInstance();
                if (empowermentManager != null) {
                    finalDamage = empowermentManager.applyProjectileDamage(player, event.getDamager(), target, finalDamage);
                }
                finalDamage = enchantEffects.applyOutgoingDamageModifiers(player, target, finalDamage, isRanged, usesMagicDamage, isCrit);
                if (beforeEffects > 0.0) {
                    lifestealEffectiveDamage *= finalDamage / beforeEffects;
                }
            }
            PassiveSnapshotService passiveService = PassiveSnapshotService.getInstance();
            if (passiveService != null) {
                double beforePassive = finalDamage;
                finalDamage = passiveService.modifyOutgoingDamage(player, target, finalDamage);
                if (beforePassive > 0.0) {
                    lifestealEffectiveDamage *= finalDamage / beforePassive;
                }
            }

            if (target instanceof Player targetPlayer) {
                ShieldManager shieldManager = ShieldManager.getInstance();
                if (shieldManager != null) {
                    finalDamage = shieldManager.applyShieldBeforeReductions(targetPlayer, event, finalDamage);
                }
            }

            PDCManager pdc = PDCManager.getInstance();
            if (pdc != null) {
                double reduction = target.getPersistentDataContainer()
                        .getOrDefault(pdc.KEY_MOB_DAMAGE_REDUCTION, PersistentDataType.DOUBLE, 0.0);
                if (reduction > 0.0) {
                    finalDamage *= Math.max(0.0, 1.0 - Math.min(0.50, reduction));
                }

                if (usesMagicDamage) {
                    double magicResist = target.getPersistentDataContainer()
                            .getOrDefault(pdc.KEY_MOB_MAGIC_RESIST, PersistentDataType.DOUBLE, 0.0);
                    if (magicResist > 0.0) {
                        finalDamage *= Math.max(0.0, 1.0 - Math.min(0.30, magicResist));
                    }
                }
            }
        }

        // 覆盖最终伤害
        event.setDamage(finalDamage);

        if (event.getEntity() instanceof LivingEntity target && finalDamage > 0.0) {
            if (passiveManager != null) {
                passiveManager.onPlayerDealtDamage(player, target);
            }
            double lifestealRate = stats.lifesteal();
            if (classManager != null) {
                lifestealRate *= classManager.getLifestealMultiplier(player);
            }
            if (passiveManager != null) {
                passiveManager.applyLifesteal(player, lifestealEffectiveDamage, lifestealRate, isRanged, usesMagicDamage, spellbladeMelee);
            }
            EnchantEffectService enchantEffects = EnchantEffectService.getInstance();
            if (enchantEffects != null) {
                enchantEffects.afterPlayerAttack(event, player, target, finalDamage, isRanged, usesMagicDamage, isCrit);
            }
            PassiveSnapshotService passiveService = PassiveSnapshotService.getInstance();
            if (passiveService != null) {
                passiveService.afterPlayerAttack(player, target, finalDamage);
            }
        }
    }

    /**
     * 从极速缓存中提取玩家属性快照并结合主手武器计算总属性
     */
    private CombatStats getCombatStats(Player player) {
        return CombatStats.getFullStats(player);
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

        double finalDamage = event.getDamage();
        PDCManager pdc = PDCManager.getInstance();

        if (pdc != null) {
            LivingEntity attacker = MobDamageSourceResolver.resolveMobAttacker(event);
            if (attacker != null) {
                double mobAttackDamage = attacker.getPersistentDataContainer()
                        .getOrDefault(pdc.KEY_MOB_ATTACK_DAMAGE, PersistentDataType.DOUBLE, 0.0);
                if (mobAttackDamage > 0.0) {
                    finalDamage = mobAttackDamage;
                }
            }
        }

        ShieldManager shieldManager = ShieldManager.getInstance();
        if (shieldManager != null) {
            finalDamage = shieldManager.applyShieldBeforeReductions(player, event, finalDamage);
        }

        PowerLevelManager powerLevelManager = PowerLevelManager.getInstance();
        if (powerLevelManager != null) {
            double reduction = powerLevelManager.calculateDamageReduction(player);
            if (reduction > 0.0) {
                finalDamage *= Math.max(0.0, 1.0 - reduction);
            }
        }

        AttributeManager attributeManager = AttributeManager.getInstance();
        if (attributeManager != null && attributeManager.isMagicDamageCause(event.getCause())) {
            double magicReduction = attributeManager.getMagicDamageReduction(player);
            if (magicReduction > 0.0) {
                finalDamage *= Math.max(0.0, 1.0 - magicReduction);
            }
        }

        EnchantEffectService enchantEffects = EnchantEffectService.getInstance();
        if (enchantEffects != null) {
            finalDamage = enchantEffects.applyIncomingDamageModifiers(player, finalDamage);
        }

        PassiveSnapshotService passiveService = PassiveSnapshotService.getInstance();
        if (passiveService != null) {
            finalDamage = passiveService.modifyIncomingDamage(player, finalDamage);
            if (passiveService.tryPreventFatalDamage(player, event, finalDamage)) {
                return;
            }
        }

        if (enchantEffects != null && enchantEffects.tryPreventFatalDamage(player, event, finalDamage)) {
            return;
        }

        event.setDamage(finalDamage);
    }
}
