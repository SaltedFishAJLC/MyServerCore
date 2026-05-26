package com.servercore.manager;

import com.servercore.ServerCorePlugin;
import com.servercore.combat.damage.DamageService;
import com.servercore.combat.integration.EnchantTargetMatcher;
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
        if (DamageService.isInternalDamageActive()) {
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
            if (templateManager != null && !templateManager.canUseMainHandWeapon(player, player.getInventory().getItemInMainHand())) {
                event.setCancelled(true);
                if (templateManager.isTwoHandBlocked(player, player.getInventory().getItemInMainHand())) {
                    player.sendActionBar(net.kyori.adventure.text.Component.text("双手武器需要空出副手。"));
                } else {
                    player.sendActionBar(net.kyori.adventure.text.Component.text("这件武器不能在主手使用。"));
                }
                return;
            }
        }

        // 获取玩家的动态属性快照
        CombatStats stats = getCombatStats(player);
        
        double finalDamage = 0.0;
        boolean isMagic = event.getCause() == DamageCause.MAGIC;
        
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
        if (isMagic) {
            // 【魔法流派】: 面板 * 乘区(含法强)
            // 法强 = (最大法力 - 100) / 400
            double maxMana = 100.0;
            AttributeManager attributeManager = AttributeManager.getInstance();
            if (attributeManager != null) {
                maxMana = attributeManager.getEffectiveMaxMana(player);
            } else if (auraSkills != null && auraSkills.getUser(player.getUniqueId()) != null) {
                maxMana = auraSkills.getUser(player.getUniqueId()).getMaxMana();
            }
            double magicPower = Math.max(0.0, (maxMana - 100.0) / 400.0);
            ClassManager classManager = ClassManager.getInstance();
            double classMagicMultiplier = classManager == null ? 0.0 : classManager.getMagicMultiplierBonus(player);
            double skillMultiplier = getSkillMultiplier(player, "sorcery");
            
            finalDamage = baseDamage * (stats.baseMultiplier() + magicPower + classMagicMultiplier) * skillMultiplier;
            
        } else {
            // 【物理流派（近战/远程）】: 面板 * 暴击 * 乘区 * (破甲/残暴)
            boolean isCrit = Math.random() < stats.critChance();
            double critMult = isCrit ? stats.critDamage() : 1.0;
            double skillMultiplier = getSkillMultiplier(player, isRanged ? "archery" : "fighting");
            
            if (isCrit && event.getEntity() instanceof LivingEntity target) {
                target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0), 15, 0.5, 0.5, 0.5, 0.1);
            }

            if (isRanged) {
                // 远程伤害
                double armorPenMult = 1.0 + (stats.armorPen() / 100.0); // 暂定基础倍率，未来可根据怪物护甲微调
                finalDamage = baseDamage * critMult * stats.baseMultiplier() * skillMultiplier * armorPenMult;
            } else {
                // 近战伤害
                finalDamage = baseDamage * critMult * stats.baseMultiplier() * skillMultiplier;
                
                // 残暴计算
                double brutality = stats.brutality();
                int p = (int) (brutality / 100);
                double q = brutality % 100;
                
                int extraHits = p;
                if (Math.random() < (q / 100.0)) {
                    extraHits++;
                }
                
                if (extraHits > 0) {
                    // 用户需求：不模拟真实连击导致无敌帧，直接将伤害合并计算
                    // TODO: 后续接入全息伤害显示插件时，可在此处额外生成 extraHits 条浮空文本以满足视觉需求
                    finalDamage *= (1 + extraHits);
                }
            }
        }

        if (event.getEntity() instanceof LivingEntity target) {
            finalDamage *= EnchantTargetMatcher.resolveHighestMultiplier(player.getInventory().getItemInMainHand(), target);

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

                if (isMagic) {
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
    }

    /**
     * 从极速缓存中提取玩家属性快照并结合主手武器计算总属性
     */
    private CombatStats getCombatStats(Player player) {
        return CombatStats.getFullStats(player);
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
        if (DamageService.isInternalDamageActive()) {
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

        event.setDamage(finalDamage);
    }
}
