# ServerCore 战力结构重构与武器规范实现指南

> 目标读者：负责修改 `ServerCore` 的 Agent / 开发者。  
> 目标版本：Paper 1.21，Java 21。  
> 本文专注战力结构、武器模板、主副手规则、盾牌、吸血与自然刷怪等级逻辑。法术武器只预留接口，暂缓完整实现。

---

## 1. 重构目标

当前战力系统的主要问题是：

1. `PowerLevel` 同时承担了“玩家真实战力”和“自然刷怪防换装采样值”两个职责。
2. 现有公式偏向 EDPH / 单次伤害，导致高伤害低攻速武器被高估，低伤害高攻速武器被低估。
3. 特殊怪、结构怪、Boss、Slayer 怪不应该默认跟随玩家滑动战力，而应该以固定等级或区域等级为主。
4. 武器模板、主副手规则、盾牌、吸血已经有明确规范，需要进入战力估算和实战链路。

本次重构需要把战力拆成三个概念：

| 名称 | 含义 | 是否滑动 | 用途 |
|---|---|---:|---|
| `TargetPower` | 玩家即时理论战力 | 否 | `/sc stats`、准入判断、调试、装备评分 |
| `SpawnPower` | 自然生态刷怪采样战力 | 是 | 自然刷怪、自然替换怪等级采样 |
| `ContentLevel` | 内容配置等级 | 否，除非配置自适应 | WDA 结构怪、Boss、Slayer、副本怪、固定精英怪 |

核心原则：

```text
真实战力即时计算；自然刷怪使用滑动采样；特殊内容默认配置驱动。
```

---

## 2. 武器模板规范

### 2.1 模板列表

近战模板：

- `ONE_HANDED_SWORD`：单手剑
- `TWO_HANDED_SWORD`：双手剑
- `ONE_HANDED_AXE`：单手斧
- `TWO_HANDED_AXE`：双手斧
- `HEAVY_HAMMER`：重锤，双手
- `TRIDENT`：三叉戟，双手
- `DAGGER`：匕首，单手
- `SCYTHE`：镰刀，双手，原型为锄头
- `PICKAXE`：镐子，可预留
- `SHOVEL`：铲子，可预留

远程模板：

- `SHORTBOW`：短弓，双手，无需蓄力
- `LONGBOW`：长弓，双手，需要蓄力，默认同原版弓
- `CROSSBOW`：弩，单手，默认同原版弩

法术模板暂缓，只预留：

- `SPELL_SWORD`
- `WAND`
- `STAFF`
- `CATALYST`
- 其他异形法术引导物

盾牌模板：

- `SHIELD`：单手，只能副手

### 2.2 近战与短弓数值表

攻击速度 `x` 表示 1 秒，即 20 ticks 内可以完成 `x` 次攻击。攻击冷却为：

```java
cooldownTicks = Math.max(1, Math.round(20.0 / attackSpeed));
```

| 模板 | 攻击速度 | 冷却 ticks | 攻击范围 | 默认手持规则 |
|---|---:|---:|---:|---|
| 单手剑 | 1.6 | 13 | 3.0 | 主手限定，后续可扩展双手可放 |
| 双手剑 | 0.9 | 22 | 4.0 | 双手武器 |
| 单手斧 | 1.2 | 17 | 2.7 | 主手限定，后续可扩展双手可放 |
| 双手斧 | 0.8 | 25 | 3.5 | 双手武器 |
| 重锤 | 0.6 | 33 | 3.5 | 双手武器 |
| 三叉戟 | 1.0 | 20 | 4.0 | 双手武器 |
| 匕首 | 1.8 | 11 | 2.7 | 单手，可扩展双手可放 |
| 镰刀 | 1.1 | 18 | 3.5 | 双手武器 |
| 短弓 | 2.0 | 10 | 原版弓射程 | 双手武器 |

长弓、弩先保持原版蓄力 / 装填节奏，不强行套用近战冷却。之后如果需要纳入 `PowerLevel`，可以通过“理论射速 + 命中可靠性 + 蓄力系数”估算。

### 2.3 WeaponProfile

建议新增或扩展一个不可变数据结构：

```java
public record WeaponProfile(
    WeaponTemplate template,
    double attackSpeed,
    int cooldownTicks,
    double attackRange,
    HandRule defaultHandRule,
    boolean twoHanded,
    boolean melee,
    boolean ranged,
    double reliabilityFactor,
    double uptimeFactor,
    double aoeFactor
) {}
```

默认可靠性建议：

| 模板 | `reliabilityFactor` | `uptimeFactor` | 说明 |
|---|---:|---:|---|
| 单手剑 | 1.00 | 0.90 | 标准近战 |
| 双手剑 | 0.95 | 0.82 | 长距离但慢 |
| 单手斧 | 0.95 | 0.88 | 短距离，爆发略强 |
| 双手斧 | 0.90 | 0.80 | 慢，范围中等 |
| 重锤 | 0.85 | 0.75 | 极慢，高 DPH，不应因单次伤害抬高战力 |
| 三叉戟 | 0.95 | 0.85 | 长距离，中速 |
| 匕首 | 1.00 | 0.95 | 高频但短距离 |
| 镰刀 | 0.95 | 0.82 | 较长距离，可预留 AoE |
| 短弓 | 0.90 | 0.90 | 高频远程，略受命中影响 |
| 长弓 | 0.80 | 0.75 | 需要蓄力，命中与走位成本更高 |
| 弩 | 0.85 | 0.80 | 单手远程，但装填影响输出 |

这些值必须写入配置文件，不能硬编码死在公式里。

---

## 3. 主副手武器规则

### 3.1 HandRule

建议使用以下枚举：

```java
public enum HandRule {
    MAIN_HAND_ONLY,       // 只能主手
    OFF_HAND_ONLY,        // 只能副手
    BOTH_HANDS_ALLOWED,   // 主副手都可放，但攻击只使用主手
    TWO_HANDED            // 双手武器，只能主手，副手有物品则不可使用
}
```

### 3.2 默认规则

1. 原版所有剑：`ONE_HANDED_SWORD`，`MAIN_HAND_ONLY`。
2. 原版所有斧头：`TWO_HANDED_AXE`，`TWO_HANDED`。
3. 原版重锤：`HEAVY_HAMMER`，`TWO_HANDED`。
4. 原版三叉戟：`TRIDENT`，`TWO_HANDED`。
5. 原版弓：`LONGBOW`，`TWO_HANDED`。
6. 原版弩：`CROSSBOW`，`MAIN_HAND_ONLY` 或 `BOTH_HANDS_ALLOWED`，当前建议先用 `MAIN_HAND_ONLY`，以后再开放单手弩副手玩法。
7. 原版盾牌：`SHIELD`，`OFF_HAND_ONLY`。

### 3.3 属性加成规则

扫描装备属性时：

1. 主手武器属性：100% 生效。
2. 副手武器如果 `BOTH_HANDS_ALLOWED`：属性按 50% 生效。
3. 副手武器如果 `OFF_HAND_ONLY`：属性按 100% 生效。
4. 双手武器只能位于主手；若副手存在非空气物品，则该双手武器不可使用，主手武器属性和攻击能力不应正常生效。
5. 盾牌只作为副手装备，盾牌属性不参与普通武器攻击属性，但参与防御、格挡和生存评分。

建议新增：

```java
public record HandValidationResult(
    boolean canUseMainWeapon,
    boolean canUseOffhandWeapon,
    boolean canUseShield,
    String reason
) {}
```

用于攻击、右键技能、属性刷新、UI 提示统一判断。

---

## 4. 武器技能触发规则

### 4.1 主动技能数量

除法术武器外，每件武器可以有多个被动能力，但至多有一个主动技能。

### 4.2 触发方式

1. 主手武器主动技能：右键释放。
2. 副手武器主动技能：下蹲 + 右键释放。
3. 下蹲 + 右键时，如果副手武器有可释放技能，优先释放副手技能；否则释放主手技能。
4. 技能消耗不固定为法力，需要预留通用 cost 结构。

### 4.3 优先级伪代码

```java
if (player.isSneaking()) {
    if (offhandWeapon.hasActiveAbility() && canUseOffhandWeapon) {
        cast(offhandWeapon.activeAbility());
        return;
    }
}

if (mainHandWeapon.hasActiveAbility() && canUseMainWeapon) {
    cast(mainHandWeapon.activeAbility());
}
```

### 4.4 AbilityCost

建议预留：

```java
public record AbilityCost(
    CostType type,
    double amount,
    Map<String, Object> options
) {}

public enum CostType {
    MANA,
    HEALTH,
    COOLDOWN_ONLY,
    ITEM,
    AMMO,
    CUSTOM
}
```

---

## 5. 盾牌重做规范

### 5.1 盾牌专属属性

盾牌模板 `SHIELD` 有三个专属属性：

| PDC / stat key | 含义 |
|---|---|
| `shield_block_threshold` | 格挡阈值 |
| `shield_effective_block` | 有效格挡，`0 <= value <= 1` |
| `shield_cooldown_seconds` | 盾牌冷却，单位秒 |

原版盾牌默认：

```yaml
vanilla_shield:
  shield_block_threshold: 10.0
  shield_effective_block: 0.6
  shield_cooldown_seconds: 2.0
```

### 5.2 格挡结算顺序

盾牌结算必须发生在普通护甲、减伤、魔法抗性等结算之前。

```text
原始伤害 -> 盾牌抵消 -> 护甲/减伤 -> 最终伤害
```

例如：受到 20 点伤害，盾牌抵消 8 点，减伤率 50%，则最终伤害为：

```text
(20 - 8) * (1 - 0.5) = 6
```

### 5.3 格挡结果

设：

```java
damage = incomingDamage;
threshold = shieldBlockThreshold;
effective = shieldEffectiveBlock;
baseCooldown = shieldCooldownSeconds;
```

规则：

1. `damage <= threshold`：完全抵消伤害，不进入强制冷却。
2. `threshold < damage <= threshold * 2.5`：破格挡。
   - 抵消 `threshold * effective` 点伤害。
   - 进入短冷却：`baseCooldown` 秒。
   - 双方都被击退。
3. `damage > threshold * 2.5`：崩盾。
   - 抵消 `threshold * effective` 点伤害。
   - 进入长冷却：`baseCooldown * 2.5` 秒。
   - 双方都被击退，击退强度可大于破格挡。

建议结果结构：

```java
public record ShieldBlockResult(
    ShieldBlockType type,
    double blockedDamage,
    double remainingDamage,
    double cooldownSeconds,
    boolean knockbackBothSides
) {}

public enum ShieldBlockType {
    NONE,
    FULL_BLOCK,
    GUARD_BREAK,
    SHIELD_BREAK
}
```

### 5.4 盾牌与战力估算

盾牌不应该显著抬高自然刷怪等级，否则坦克玩家会让周围怪物过强。建议盾牌只少量进入 `survivalScore`：

```java
shieldValuePerSecond = min(
    shieldBlockThreshold * shieldEffectiveBlock / max(1.0, shieldCooldownSeconds),
    maxHealth * 0.10
);
```

该值只用于 `TargetPower` 的生存评分，且对 `SpawnPower` 可再乘一个较低权重，例如 `0.5`。

---

## 6. 吸血规范

### 6.1 吸血来源

吸血可以通过附魔、武器自带属性、职业被动或特殊装备获得。

默认规则：

1. 只有近战武器可以吸血。
2. 只有物理伤害可以吸血。
3. 远程武器、法术伤害默认不能吸血。
4. 后续可以通过少部分装备或被动显式允许远程 / 法术吸血。
5. 血魔默认自带 2.5% 近战吸血。

### 6.2 有效吸血伤害

将伤害拆成四部分：

```text
最终伤害 = 面板 * 乘区 * 暴击 * 残暴追击
```

吸血有效伤害为：

```text
吸血有效伤害 = 面板 * 乘区 * 残暴追击
```

也就是说，暴击造成的额外伤害不触发吸血。

### 6.3 实现建议

在伤害结算中保留 `DamageBreakdown`：

```java
public record DamageBreakdown(
    double panelDamage,
    double multiplierPart,
    double critMultiplier,
    double brutalityMultiplier,
    double finalDamageBeforeDefense,
    double finalDamageAfterDefense,
    DamageCategory category,
    WeaponTemplate sourceTemplate
) {
    public double lifestealEffectiveDamage() {
        return panelDamage * multiplierPart * brutalityMultiplier;
    }
}
```

实际治疗量：

```java
heal = lifestealEffectiveDamage * lifestealRate;
```

注意：

1. 吸血应基于实际成功命中的伤害事件。
2. 不要对已死亡目标重复吸血。
3. 建议每次命中吸血量不超过玩家最大生命的一定比例，例如 10% ~ 15%，避免单次重锤暴击前置伤害导致瞬间满血。
4. 血魔“吸血效果翻倍”应作为最终 `lifestealRate` 倍率，而不是修改物品 PDC。

---

## 7. 新战力公式

### 7.1 输出评分从 DPH 改为 DPS

不要再使用单次伤害 `DPH` 作为主要战力指标。应使用模板攻速后的期望 DPS。

单系 DPS：

```text
expectedHit = nonCritHit * expectedCritFactor
rawDps = expectedHit * attacksPerSecond
adjustedDps = rawDps * reliabilityFactor * uptimeFactor
```

其中：

```text
nonCritHit = panelDamage * multiplierPart * expectedBrutalityFactor
expectedCritFactor = 1 + critChance * (critDamageMultiplier - 1)
```

如果现有代码中的 `crit_damage` 表示“额外暴击伤害”而不是“暴击倍率”，需要先转换成统一倍率。

### 7.2 多输出类型合成

近战、远程、法术分别计算：

```java
meleeDps
rangedDps
magicDps
```

战力估算先取排序：

```java
d1 >= d2 >= d3
```

输出评分：

```java
offenseScore = d1 + 0.25 * d2 + 0.10 * d3;
```

这样主输出决定战力，副输出有少量贡献，但不会让多修玩家的自然刷怪等级膨胀太多。

### 7.3 生存评分

基础 EHP：

```java
damageReduction = armor / (armor + 100.0);
ehp = maxHealth / Math.max(0.05, 1.0 - damageReduction);
```

续航评分：

```java
lifestealPerSecond = lifestealEffectiveDps * lifestealRate;
regenPerSecond = estimatedRegenPerSecond;
shieldValuePerSecond = estimatedShieldValuePerSecond;

sustainPerSecond = lifestealPerSecond + regenPerSecond + shieldValuePerSecond;
sustainFactor = clamp(sustainPerSecond / maxHealth, 0.0, 0.50);
```

生存评分：

```java
survivalScore = ehp * (1.0 + sustainFactor);
```

注意：生存不应线性强烈推高自然怪等级，否则守护者、血魔、盾牌玩家会把附近怪物等级抬得过高。

### 7.4 TargetPower 公式

推荐公式：

```java
targetPower = Math.sqrt(offenseScore * Math.sqrt(survivalScore) / 4.0) * globalScale;
```

配置项：

```yaml
power:
  global_scale: 1.0
  offense_secondary_weight: 0.25
  offense_third_weight: 0.10
  survival_sqrt: true
  denominator: 4.0
  min_power: 1.0
  max_power: 10000.0
```

直觉：

```text
输出翻 4 倍，战力约翻 2 倍。
生存翻 4 倍，战力约翻 1.414 倍。
```

这比 `sqrt(DPH * EHP)` 更不容易惩罚坦克职业和盾牌职业。

---

## 8. SpawnPower 滑动采样

### 8.1 不要让 `/sc stats` 主要显示 SpawnPower

`/sc stats` 应优先显示 `TargetPower`。`SpawnPower` 是自然刷怪内部值，可以在 debug 模式显示。

### 8.2 非对称滑动

如果继续使用指数滑动，建议：

```java
if (targetPower > spawnPower) {
    spawnPower += (targetPower - spawnPower) * riseAlpha; // 例如 0.25
} else {
    spawnPower += (targetPower - spawnPower) * fallAlpha; // 例如 0.05
}
```

含义：

- 玩家变强后，自然怪可以较快增强。
- 玩家脱装备后，自然怪不应立刻变弱，避免换装压低刷怪等级。

### 8.3 滚动分位数方案

更推荐维护最近 3 ~ 5 分钟 `TargetPower` 样本，取 70 分位数作为 `SpawnPower`：

```yaml
power:
  spawn_power:
    mode: ROLLING_PERCENTILE
    sample_interval_ticks: 20
    window_seconds: 240
    percentile: 0.70
    min_samples: 10
```

如果实现成本较高，可以先用非对称滑动，后续再替换成滚动分位数。

### 8.4 区域采样

自然生成怪物时，`MobSpawnManager` 应读取附近玩家的 `SpawnPower`，而不是 `TargetPower`。建议仍取附近 64 格玩家中位数。

```java
spawnLevel = median(nearbyPlayers.map(powerManager::getSpawnPower));
```

如果附近没有玩家，则使用世界默认等级或取消自适应。

---

## 9. ContentLevel 与怪物等级模式

特殊怪、WDA 结构怪、Boss、Slayer、副本怪不应默认受滑动战力影响。给 `custom_mobs.yml` 增加等级模式。

### 9.1 LevelMode

```java
public enum MobLevelMode {
    NATURAL_ADAPTIVE,   // 自然怪，读取附近 SpawnPower
    FIXED,              // 固定等级
    WORLD_CAP,          // 使用世界上限或世界 tier
    AREA,               // 使用区域 / 结构 tier
    ADAPTIVE_CLAMPED    // 在固定内容等级附近小幅自适应
}
```

### 9.2 YAML 示例

自然替换怪：

```yaml
Rogue_Enderman:
  level_mode: NATURAL_ADAPTIVE
  min_level: 10
  max_level: 80
  mod: 1.1
```

WDA 结构精英：

```yaml
WDA_Dungeon_Guard:
  level_mode: FIXED
  level: 60
  mod: 1.2
```

Boss：

```yaml
Mythic_Flame_Boss:
  level_mode: FIXED
  level: 150
  bypass_world_level_cap: true
  mod: 2.0
```

野外精英小幅自适应：

```yaml
Wild_Elite:
  level_mode: ADAPTIVE_CLAMPED
  base_level: 50
  player_scale: 0.25
  min_level: 45
  max_level: 70
```

### 9.3 等级选择逻辑

```java
switch (rule.levelMode()) {
    case NATURAL_ADAPTIVE -> level = clamp(medianNearbySpawnPower, rule.minLevel(), rule.maxLevel());
    case FIXED -> level = rule.level();
    case WORLD_CAP -> level = worldCap(world);
    case AREA -> level = areaTierLevel(location);
    case ADAPTIVE_CLAMPED -> level = clamp(
        rule.baseLevel() + medianNearbySpawnPower * rule.playerScale(),
        rule.minLevel(),
        rule.maxLevel()
    );
}
```

---

## 10. PowerBreakdown 数据结构

建议新增：

```java
public record PowerBreakdown(
    double meleeDps,
    double rangedDps,
    double magicDps,
    double offenseScore,
    double maxHealth,
    double armor,
    double damageReduction,
    double effectiveHealth,
    double lifestealPerSecond,
    double regenPerSecond,
    double shieldValuePerSecond,
    double sustainFactor,
    double survivalScore,
    double targetPower,
    double spawnPower
) {}
```

`/sc stats` 可以显示简化版：

```text
理论战力: 73.5
主输出 DPS: 近战 128.4
有效生命: 420.0
续航评分: +18%
自然刷怪采样: 65.2  [debug only]
```

---

## 11. 实现步骤建议

### Step 1：整理武器模板配置

1. 确认 `WeaponTemplateManager` 中模板枚举完整。
2. 将攻击速度、冷却、范围、默认手持规则写入配置。
3. 所有冷却统一用 `round(20 / attackSpeed)`。
4. 对长弓、弩保留原版逻辑，不强行套用短弓。

### Step 2：统一主副手校验

1. 新增 `HandValidationResult`。
2. 攻击事件、右键技能、属性刷新、短弓发射、盾牌格挡都必须调用同一套校验。
3. 双手武器主手持有且副手非空气时，攻击和主动技能都不可用。

### Step 3：实现盾牌结果结构

1. 新增 `ShieldBlockResult` 和 `ShieldBlockType`。
2. 盾牌先于护甲减伤结算。
3. 完整实现 FULL_BLOCK / GUARD_BREAK / SHIELD_BREAK。
4. 超过阈值时双方击退。
5. 原版盾牌写入默认数值。

### Step 4：重写吸血链路

1. 在伤害链路中保留 `DamageBreakdown`。
2. 吸血有效伤害排除暴击额外伤害。
3. 默认只允许近战物理吸血。
4. 血魔提供 2.5% 近战吸血，且吸血效果翻倍。
5. 为远程 / 法术吸血预留显式开关。

### Step 5：重构 PowerLevelManager

建议接口：

```java
public final class PowerLevelManager {
    public PowerBreakdown calculateTargetPower(Player player);
    public double getTargetPower(Player player);
    public double getSpawnPower(Player player);
    public void tickSpawnPower();
    public double getNearbyMedianSpawnPower(Location location, double radius);
}
```

注意：

- `calculateTargetPower` 即时计算。
- `getSpawnPower` 返回滑动 / 分位数采样结果。
- `MobSpawnManager` 自然怪只能使用 `SpawnPower`。
- `/sc stats` 主要显示 `TargetPower`。

### Step 6：扩展 custom_mobs.yml

1. 增加 `level_mode`。
2. 支持 `level`、`base_level`、`min_level`、`max_level`、`player_scale`。
3. 兼容旧字段：如果旧配置写了固定等级，则默认 `FIXED`；如果没有固定等级，则自然生成走 `NATURAL_ADAPTIVE`。

### Step 7：调试命令

新增或扩展：

```text
/sc debug power
/sc debug weapon
/sc debug shield
/sc debug damage
/sc debug moblevel <mobRuleId>
```

`/sc debug power` 应输出完整 `PowerBreakdown`。

---

## 12. 验收标准

### 12.1 武器战力验收

1. 两把武器理论 DPS 相同，但一把高 DPH 低攻速，一把低 DPH 高攻速，`TargetPower` 差距不应超过 10% ~ 15%。
2. 重锤不应因为单次伤害高而让战力远高于同 DPS 匕首。
3. 攻击速度加成应正确影响 DPS 和战力。
4. 副手 `BOTH_HANDS_ALLOWED` 武器只提供 50% 属性。
5. 双手武器在副手有物品时不能攻击，不能释放主动技能。

### 12.2 盾牌验收

1. 伤害不超过阈值时完全格挡。
2. 超过阈值但不超过 2.5 倍阈值时触发破格挡，短冷却，双方击退。
3. 超过 2.5 倍阈值时触发崩盾，长冷却，双方击退。
4. 盾牌抵消发生在护甲减伤之前。
5. 原版盾牌默认阈值 10、有效格挡 0.6、冷却 2 秒。

### 12.3 吸血验收

1. 默认只有近战物理伤害触发吸血。
2. 暴击额外伤害不增加吸血。
3. 残暴追击部分可以增加吸血。
4. 血魔默认有 2.5% 近战吸血。
5. 血魔吸血效果翻倍不应永久写入物品属性。

### 12.4 战力与刷怪验收

1. `/sc stats` 显示即时 `TargetPower`。
2. 自然怪生成使用附近玩家 `SpawnPower` 中位数。
3. 玩家脱装备后，`SpawnPower` 不应立刻下降。
4. 固定等级 Boss 不受玩家 `SpawnPower` 影响。
5. WDA 结构怪可以通过 `level_mode: FIXED` 或 `AREA` 固定内容等级。
6. 旧配置能够兼容，不应导致现有怪物无法生成。

---

## 13. 推荐默认配置片段

```yaml
power:
  global_scale: 1.0
  denominator: 4.0
  min_power: 1.0
  max_power: 10000.0

  offense:
    secondary_weight: 0.25
    third_weight: 0.10

  sustain:
    max_sustain_factor: 0.50
    max_lifesteal_per_hit_ratio: 0.15
    shield_spawn_weight: 0.50

  spawn_power:
    mode: ASYMMETRIC_SMOOTHING
    rise_alpha: 0.25
    fall_alpha: 0.05
    update_interval_ticks: 20
    nearby_radius: 64.0

weapon_templates:
  ONE_HANDED_SWORD:
    attack_speed: 1.6
    attack_range: 3.0
    default_hand_rule: MAIN_HAND_ONLY
    reliability_factor: 1.00
    uptime_factor: 0.90

  TWO_HANDED_SWORD:
    attack_speed: 0.9
    attack_range: 4.0
    default_hand_rule: TWO_HANDED
    reliability_factor: 0.95
    uptime_factor: 0.82

  ONE_HANDED_AXE:
    attack_speed: 1.2
    attack_range: 2.7
    default_hand_rule: MAIN_HAND_ONLY
    reliability_factor: 0.95
    uptime_factor: 0.88

  TWO_HANDED_AXE:
    attack_speed: 0.8
    attack_range: 3.5
    default_hand_rule: TWO_HANDED
    reliability_factor: 0.90
    uptime_factor: 0.80

  HEAVY_HAMMER:
    attack_speed: 0.6
    attack_range: 3.5
    default_hand_rule: TWO_HANDED
    reliability_factor: 0.85
    uptime_factor: 0.75

  TRIDENT:
    attack_speed: 1.0
    attack_range: 4.0
    default_hand_rule: TWO_HANDED
    reliability_factor: 0.95
    uptime_factor: 0.85

  DAGGER:
    attack_speed: 1.8
    attack_range: 2.7
    default_hand_rule: BOTH_HANDS_ALLOWED
    reliability_factor: 1.00
    uptime_factor: 0.95

  SCYTHE:
    attack_speed: 1.1
    attack_range: 3.5
    default_hand_rule: TWO_HANDED
    reliability_factor: 0.95
    uptime_factor: 0.82

  SHORTBOW:
    attack_speed: 2.0
    attack_range: -1
    default_hand_rule: TWO_HANDED
    reliability_factor: 0.90
    uptime_factor: 0.90

  SHIELD:
    default_hand_rule: OFF_HAND_ONLY
    shield_block_threshold: 10.0
    shield_effective_block: 0.6
    shield_cooldown_seconds: 2.0
```

---

## 14. 注意事项

1. 不要把 `SpawnPower` 当作玩家面板战力显示给普通玩家，否则会造成困惑。
2. 不要让盾牌、生存、吸血过强地推高自然刷怪等级。
3. 不要用 DPH 估算武器战力，必须换成 DPS。
4. 不要让特殊怪默认自适应玩家战力，除非配置明确要求。
5. 不要把职业被动永久写入物品 PDC；职业加成应在属性汇总阶段动态加入。
6. 所有模板参数、战力权重、滑动参数都应进入配置文件，方便后续调参。
7. 法术武器暂缓实现，但 `PowerBreakdown`、`DamageBreakdown`、`AbilityCost` 要预留法术扩展空间。
