
# MyServerCore 自定义附魔系统实现指南

## 0. 当前仓库现状

当前项目已有自定义附魔雏形：

1. `EnchantManager` 已经使用 `KEY_ITEM_CUSTOM_ENCHANTS` 把附魔以 `id:level;id:level` 字符串形式写入物品 PDC，并且目前通过 `registerDefaults()` 硬编码注册了一批附魔。([GitHub][1])
2. `PDCManager` 已经定义了大量装备属性 key，包括伤害、暴击、残暴、吸血、破甲、防御、盾牌阈值、采集、钓鱼，以及 `KEY_ITEM_CUSTOM_ENCHANTS`。([GitHub][2])
3. `CombatStats` 当前会动态读取装备 PDC 属性，并且已经硬编码把 `vampirism` 附魔等级转成吸血率。([GitHub][3])
4. `CombatManager` 当前已经在玩家攻击时计算自定义伤害，并调用 `EnchantTargetMatcher.resolveHighestMultiplier()` 处理 Slayer 类附魔增伤。([GitHub][4])
5. `EnchantTargetMatcher` 当前把生物主标签映射到硬编码的 Slayer 附魔 ID，并固定每级 `+5%` 伤害。([GitHub][5])
6. `ShieldManager` 已经有格挡阈值、有效格挡、盾牌冷却等自定义盾牌逻辑。([GitHub][6])

因此本次实现的目标不是从零写附魔，而是把现有硬编码逻辑改造成**配置驱动 + 数值解析 + 事件效果处理**。

---

# 1. 总目标

实现一个完整的自定义附魔系统，满足以下要求：

```text
1. 所有附魔从 enchants.yml 加载，不再硬编码注册。
2. 每个附魔可以在 YML 中设置 enabled: true/false。
3. 禁用的附魔：
   - 不会从附魔台、NPC、战利品、测试命令中新增；
   - 不参与属性计算；
   - 不触发机制效果；
   - 已经存在于旧装备上的禁用附魔默认保留 PDC，但 lore 显示为灰色“已禁用”。
4. 普通、罕见附魔只做数值加成。
5. 稀有、终极附魔允许触发机制效果。
6. 终极附魔一件装备最多只能存在一个。
7. 附魔的等级上限、数值倍率、触发概率、冷却、范围、目标数量等全部可在 YML 调整。
8. 保留铁砧合并附魔书机制，但要接管结果物品的 PDC，不能依赖原版附魔系统。
9. 砂轮支持：
   - 消耗魔法粉尘移除指定附魔；
   - 清空全部附魔并返还部分粉尘与经验。
```

---

# 2. 文件结构

新增或重构以下文件：

```text
src/main/resources/enchants.yml
src/main/resources/enchant_pools.yml

src/main/java/com/servercore/enchant/EnchantDefinition.java
src/main/java/com/servercore/enchant/EnchantRarity.java
src/main/java/com/servercore/enchant/EnchantSlot.java
src/main/java/com/servercore/enchant/EnchantNumericBonus.java
src/main/java/com/servercore/enchant/EnchantEffectType.java
src/main/java/com/servercore/enchant/EnchantRegistry.java
src/main/java/com/servercore/enchant/EnchantStatResolver.java
src/main/java/com/servercore/enchant/EnchantEffectService.java
src/main/java/com/servercore/enchant/EnchantAnvilListener.java
src/main/java/com/servercore/enchant/EnchantGrindstoneListener.java
src/main/java/com/servercore/enchant/EnchantTableListener.java
```

也可以暂时继续放在 `com.servercore.manager` 包下，但推荐新建 `com.servercore.enchant`，避免 `manager` 包继续膨胀。

---

# 3. enchants.yml 格式

## 3.1 顶层结构

```yaml
version: 1

settings:
  disabled_lore_mode: SHOW_GRAY # SHOW_GRAY / HIDE
  unknown_enchant_lore_mode: SHOW_RAW # SHOW_RAW / HIDE
  ultimate_limit_per_item: 1
  rare_effects_require_enabled: true
  numeric_bonus_require_enabled: true

enchants:
  keen:
    enabled: true
    display: "锋芒"
    rarity: COMMON
    max_level: 5
    slots: [MELEE_WEAPON]
    conflict_group: damage_generic
    description:
      - "增加基础伤害。"
      - "每级 +{base_damage} 基础伤害。"
    numeric:
      base_damage:
        type: LINEAR
        per_level: 2.0

  critical:
    enabled: true
    display: "精准"
    rarity: COMMON
    max_level: 5
    slots: [WEAPON, RANGED_WEAPON]
    conflict_group: crit
    description:
      - "提高暴击率。"
      - "每级 +{crit_chance_percent}% 暴击率。"
    numeric:
      crit_chance:
        type: LINEAR
        per_level: 0.012

  undead_slayer:
    enabled: true
    display: "亡灵杀手"
    rarity: UNCOMMON
    max_level: 5
    slots: [WEAPON]
    conflict_group: slayer_main_tag
    description:
      - "对亡灵主标签目标造成更高伤害。"
      - "每级 +{damage_to_main_tag_percent}% 伤害。"
    target:
      main_tags: [UNDEAD]
    numeric:
      damage_to_target:
        type: LINEAR
        per_level: 0.05

  cleave:
    enabled: true
    display: "劈砍"
    rarity: RARE
    max_level: 3
    slots: [MELEE_WEAPON]
    conflict_group: melee_mechanic
    description:
      - "近战命中时，对周围敌人造成溅射伤害。"
      - "溅射伤害不触发暴击、吸血、额外掉落。"
    effect:
      type: CLEAVE
      trigger: ON_MELEE_HIT
      params:
        radius:
          type: PER_LEVEL
          values: [3.0, 3.25, 3.5]
        max_targets:
          type: PER_LEVEL
          values: [2, 3, 3]
        damage_ratio:
          type: PER_LEVEL
          values: [0.10, 0.15, 0.20]
        secondary_damage: true

  perfect_guard:
    enabled: true
    display: "完美格挡"
    rarity: RARE
    max_level: 3
    slots: [SHIELD]
    conflict_group: shield_mechanic
    description:
      - "举盾瞬间受到攻击时，短时间提高格挡能力。"
    effect:
      type: PERFECT_GUARD
      trigger: ON_SHIELD_BLOCK
      params:
        timing_window_seconds:
          type: CONSTANT
          value: 0.35
        threshold_bonus:
          type: PER_LEVEL
          values: [15.0, 25.0, 40.0]
        cooldown_seconds:
          type: PER_LEVEL
          values: [8.0, 6.0, 4.0]

  ultimate_apex_slayer:
    enabled: true
    display: "猎王"
    rarity: ULTIMATE
    max_level: 1
    slots: [WEAPON]
    conflict_group: ultimate
    description:
      - "连续攻击同一个首领或 Slayer 目标时，伤害逐渐提高。"
    effect:
      type: APEX_SLAYER
      trigger: ON_DAMAGE_CALCULATE
      params:
        damage_bonus_per_stack:
          type: CONSTANT
          value: 0.02
        max_stacks:
          type: CONSTANT
          value: 10
        expire_seconds:
          type: CONSTANT
          value: 5.0
        require_boss_or_slayer: true
```

---

# 4. enabled 的行为

`enabled: false` 是测试阶段最重要的开关，必须严格实现。

## 4.1 禁用后的规则

```text
enabled: false 时：

1. EnchantRegistry 仍然加载这个附魔定义。
   原因：旧装备上可能已经有这个附魔，需要能显示它的名字和说明。

2. EnchantManager#getCustomEnchantLevel(item, id)
   默认仍然返回 PDC 中的原始等级。
   但新增一个方法 getActiveEnchantLevel(item, id)，只有 enabled=true 才返回等级。

3. 所有战斗、采集、钓鱼、盾牌、机制触发都必须使用 getActiveEnchantLevel 或 EnchantStatResolver。
   不允许直接用 getCustomEnchantLevel 参与效果。

4. 附魔台、特殊附魔台、NPC、战利品、测试命令：
   禁止生成 enabled=false 的附魔。

5. 铁砧合并：
   如果材料或目标里已有 disabled 附魔，结果物品可以保留；
   但不能通过合并提升 disabled 附魔等级。

6. 砂轮：
   disabled 附魔允许被移除。
```

## 4.2 lore 显示

默认显示：

```text
§8[已禁用] 锋芒 V
§8此附魔当前不会生效。
```

配置项：

```yaml
settings:
  disabled_lore_mode: SHOW_GRAY # SHOW_GRAY / HIDE
```

`SHOW_GRAY` 更适合测试阶段，方便发现旧装备残留附魔。
`HIDE` 适合正式服隐藏废弃内容。

---

# 5. 参数曲线格式

不要使用任意表达式求值器，例如直接 eval `"2 * level"`。
原因是没有必要，而且会引入安全和调试问题。

实现一个简单的 `ValueCurve` 即可：

```yaml
type: CONSTANT
value: 0.05
```

```yaml
type: LINEAR
base: 0.0
per_level: 0.05
```

```yaml
type: PER_LEVEL
values: [0.10, 0.15, 0.20]
```

Java 接口：

```java
public interface ValueCurve {
    double valueAt(int level);
}
```

实现：

```java
public final class ConstantCurve implements ValueCurve {
    private final double value;

    public double valueAt(int level) {
        return value;
    }
}

public final class LinearCurve implements ValueCurve {
    private final double base;
    private final double perLevel;

    public double valueAt(int level) {
        return base + perLevel * level;
    }
}

public final class PerLevelCurve implements ValueCurve {
    private final List<Double> values;

    public double valueAt(int level) {
        if (values.isEmpty()) return 0.0;
        int index = Math.max(0, Math.min(level - 1, values.size() - 1));
        return values.get(index);
    }
}
```

这样你就能在 YML 中随时调：

```yaml
damage_ratio:
  type: PER_LEVEL
  values: [0.08, 0.12, 0.16]
```

不需要改代码。

---

# 6. 核心数据类

## 6.1 EnchantRarity

```java
public enum EnchantRarity {
    COMMON("普通"),
    UNCOMMON("罕见"),
    RARE("稀有"),
    ULTIMATE("终极");

    private final String display;

    EnchantRarity(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }

    public boolean isMechanicAllowed() {
        return this == RARE || this == ULTIMATE;
    }
}
```

## 6.2 EnchantSlot

```java
public enum EnchantSlot {
    WEAPON,
    MELEE_WEAPON,
    RANGED_WEAPON,
    MAGIC_WEAPON,
    ARMOR,
    HELMET,
    CHESTPLATE,
    LEGGINGS,
    BOOTS,
    SHIELD,
    TOOL,
    PICKAXE,
    AXE,
    SHOVEL,
    HOE,
    FISHING_ROD,
    ACCESSORY
}
```

装备位判断优先接入现有 `WeaponTemplateManager`。不要只靠 `Material` 判断，否则你之后的异形武器、魔法武器、双手武器会很难扩展。

## 6.3 EnchantDefinition

```java
public final class EnchantDefinition {
    private final String id;
    private final boolean enabled;
    private final String display;
    private final EnchantRarity rarity;
    private final int maxLevel;
    private final Set<EnchantSlot> slots;
    private final String conflictGroup;
    private final List<String> description;

    private final Map<String, ValueCurve> numericBonuses;
    private final EnchantEffectSpec effect;
    private final EnchantTargetSpec target;

    // getters...
}
```

## 6.4 EnchantEffectSpec

```java
public final class EnchantEffectSpec {
    private final EnchantEffectType type;
    private final String trigger;
    private final Map<String, ValueCurve> numericParams;
    private final Map<String, Object> rawParams;
}
```

## 6.5 EnchantEffectType

```java
public enum EnchantEffectType {
    NONE,

    CLEAVE,
    VAMPIRISM,
    FRACTURE,
    EXECUTIONER,

    HUNTER_MARK,
    ECHO_SHOT,
    PINNING_SHOT,

    MANA_SURGE,
    SPELL_ECHO,

    PERFECT_GUARD,
    ABSORB_STRIKE,

    VEIN_RESONANCE,
    HARVEST_ECHO,
    RELIC_SENSE,
    TIDE_CALL,

    APEX_SLAYER,
    BERSERKER_OATH,
    HORIZON_SNIPER,
    PHOENIX_CORE,
    VEIN_LORD
}
```

---

# 7. EnchantRegistry

`EnchantRegistry` 负责读取 `enchants.yml`，替代当前 `EnchantManager#registerDefaults()`。

## 7.1 职责

```text
1. 插件启动时加载 enchants.yml。
2. 如果插件数据目录没有 enchants.yml，则从 resources 复制默认文件。
3. 校验每个附魔：
   - id 非空；
   - rarity 合法；
   - max_level >= 1；
   - slots 非空；
   - COMMON/UNCOMMON 不允许配置 effect；
   - ULTIMATE 自动放入 ultimate 冲突组；
   - PER_LEVEL 长度不足时允许使用最后一级数值，但打印 warning。
4. 提供 get(id)、isEnabled(id)、getAll()、getEnabled()。
5. 支持 reload。
```

## 7.2 关键方法

```java
public final class EnchantRegistry {
    private static EnchantRegistry instance;

    private final ServerCorePlugin plugin;
    private final Map<String, EnchantDefinition> definitions = new LinkedHashMap<>();
    private EnchantSettings settings;

    public void reload() {
        definitions.clear();
        // load enchants.yml
        // validate
    }

    public Optional<EnchantDefinition> get(String id) {
        return Optional.ofNullable(definitions.get(normalize(id)));
    }

    public boolean isEnabled(String id) {
        return get(id).map(EnchantDefinition::isEnabled).orElse(false);
    }

    public Collection<EnchantDefinition> getEnabledDefinitions() {
        return definitions.values().stream()
                .filter(EnchantDefinition::isEnabled)
                .toList();
    }
}
```

---

# 8. 重构 EnchantManager

当前 `EnchantManager` 可以继续负责 PDC 读写，但不要再负责硬编码定义。

## 8.1 保留职责

```text
1. readEnchants(raw)
2. writeEnchants(map)
3. addCustomEnchant(item, id, level)
4. removeCustomEnchant(item, id)
5. clearCustomEnchants(item)
6. getCustomEnchantLevel(item, id)
7. getActiveEnchantLevel(item, id)
8. getAllCustomEnchants(item)
9. getAllActiveCustomEnchants(item)
```

## 8.2 新增方法

```java
public int getActiveEnchantLevel(ItemStack item, String enchantId) {
    int rawLevel = getCustomEnchantLevel(item, enchantId);
    if (rawLevel <= 0) return 0;

    EnchantRegistry registry = EnchantRegistry.getInstance();
    if (registry == null || !registry.isEnabled(enchantId)) {
        return 0;
    }

    EnchantDefinition def = registry.get(enchantId).orElse(null);
    if (def == null) return 0;

    return Math.min(rawLevel, def.maxLevel());
}
```

```java
public Map<String, Integer> getAllActiveCustomEnchants(ItemStack item) {
    Map<String, Integer> result = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> entry : getAllCustomEnchants(item).entrySet()) {
        int activeLevel = getActiveEnchantLevel(item, entry.getKey());
        if (activeLevel > 0) {
            result.put(entry.getKey(), activeLevel);
        }
    }
    return result;
}
```

## 8.3 添加附魔时的校验

`addCustomEnchant` 必须检查：

```text
1. 附魔是否存在；
2. 附魔是否 enabled；
3. 等级是否在 [1, maxLevel]；
4. 物品是否符合 slots；
5. 冲突组是否冲突；
6. 终极附魔数量是否超过限制。
```

建议新增：

```java
public EnchantApplyResult canApply(ItemStack item, String enchantId, int level);
public EnchantApplyResult addCustomEnchantChecked(ItemStack item, String enchantId, int level);
```

测试命令可以走 checked 版本。内部迁移旧物品时可以走 raw 版本。

---

# 9. EnchantStatResolver

普通、罕见附魔都应该通过 `EnchantStatResolver` 转成临时属性加成。不要把附魔数值永久写入物品 PDC，否则移除附魔时容易出现属性残留。

## 9.1 支持的 numeric key

先实现这些：

```text
base_damage
base_multiplier
crit_chance
crit_damage
brutality
lifesteal
armor_pen

base_armor
attack_speed_bonus
shield_block_threshold
shield_effective_block
shield_cooldown_seconds

attr_toughness
attr_agility
attr_intelligence
attr_willpower
attr_luck

tool_mining_speed
breaking_power
purity
mining_fortune
foraging_fortune
farming_fortune
excavation_fortune
fishing_speed
sea_creature_chance
treasure_chance

damage_to_target
damage_to_main_tag
damage_to_trait_tag
```

## 9.2 战斗属性接入

在 `CombatStats.calculateStatic()` 和 `CombatStats.getFullStats()` 中，读取装备 PDC 后，额外加上附魔数值贡献。

不要直接在 `CombatStats` 里写大量附魔判断。改成：

```java
EnchantStatResolver resolver = EnchantStatResolver.getInstance();

EnchantStatBundle enchantStats = resolver.resolveCombatStats(item, player, slot);

totalBaseDamage += enchantStats.baseDamage();
totalBaseMultiplier += enchantStats.baseMultiplier();
totalCritChance += enchantStats.critChance();
totalCritDamage += enchantStats.critDamage();
totalBrutality += enchantStats.brutality();
totalLifesteal += enchantStats.lifesteal();
totalArmorPen += enchantStats.armorPen();
```

然后删除当前 `CombatStats#getVampirismLifesteal()` 的硬编码逻辑，把 `vampirism` 也改成 YML 数值或稀有效果。

---

# 10. 重构 Slayer 附魔

当前 `EnchantTargetMatcher` 硬编码 `CreatureMainTag -> enchant ids`，并固定每级 `0.05`。应改成从 `enchants.yml` 读取。

## 10.1 YML 示例

```yaml
undead_slayer:
  enabled: true
  display: "亡灵杀手"
  rarity: UNCOMMON
  max_level: 5
  slots: [WEAPON]
  target:
    main_tags: [UNDEAD]
  numeric:
    damage_to_target:
      type: LINEAR
      per_level: 0.05

molten_bane:
  enabled: true
  display: "熔火克制"
  rarity: UNCOMMON
  max_level: 5
  slots: [WEAPON]
  target:
    trait_tags: [MOLTEN]
  numeric:
    damage_to_target:
      type: LINEAR
      per_level: 0.04
```

## 10.2 新逻辑

```java
public double resolveTargetDamageMultiplier(ItemStack weapon, LivingEntity target) {
    double bonus = 0.0;

    for (active enchant on weapon) {
        EnchantDefinition def = registry.get(id);
        if (!def.enabled()) continue;
        if (!targetSpecMatches(def.target(), target)) continue;

        ValueCurve curve = def.numericBonuses().get("damage_to_target");
        if (curve != null) {
            bonus = Math.max(bonus, curve.valueAt(level));
        }
    }

    return 1.0 + bonus;
}
```

注意：同类 Slayer 建议取最高，不要叠乘。
例如目标同时是 `SKELETON + NETHER_TRAIT`，可以允许“主标签 Slayer”和“特质 Bane”相加，但同一类主标签只取最高。

推荐规则：

```text
main_tag_bonus: 取最高
trait_tag_bonus: 取最高
boss_bonus: 单独取最高
最终 = 1 + main_tag_bonus + trait_tag_bonus + boss_bonus
```

---

# 11. EnchantEffectService

稀有和终极附魔统一在 `EnchantEffectService` 中处理。

## 11.1 事件入口

先接这些：

```text
EntityDamageByEntityEvent
EntityDamageEvent
EntityDeathEvent
BlockBreakEvent
PlayerFishEvent
PrepareAnvilEvent
InventoryClickEvent
PrepareGrindstoneEvent / InventoryClickEvent for Grindstone
EnchantItemEvent
```

## 11.2 关键原则

```text
1. 所有机制类附魔必须检查 enabled。
2. 所有机制类附魔必须检查 rarity >= RARE。
3. secondary damage 不允许再次触发附魔。
4. 额外伤害不允许触发吸血、暴击、Magic Find、额外掉落。
5. Boss 目标必须有衰减参数或独立参数。
```

建议新增内部伤害标记：

```java
public final class EnchantDamageContext {
    private static final ThreadLocal<Boolean> SECONDARY_DAMAGE = ThreadLocal.withInitial(() -> false);

    public static boolean isSecondaryDamage() {
        return SECONDARY_DAMAGE.get();
    }

    public static void runAsSecondaryDamage(Runnable runnable) {
        boolean old = SECONDARY_DAMAGE.get();
        SECONDARY_DAMAGE.set(true);
        try {
            runnable.run();
        } finally {
            SECONDARY_DAMAGE.set(old);
        }
    }
}
```

然后在 `CombatManager` 开头加：

```java
if (EnchantDamageContext.isSecondaryDamage()) {
    return;
}
```

或者在计算暴击、吸血、掉落时跳过。

---

# 12. 稀有效果第一批实现

第一阶段只做这几个，足够测试系统架构。

## 12.1 CLEAVE 劈砍

```text
触发：玩家近战命中 LivingEntity
条件：
- 主手有 enabled 的 cleave
- 不是 secondary damage
- 不是远程
效果：
- 搜索目标周围 radius 内 LivingEntity
- 排除原目标、玩家、友方
- 最多 max_targets 个
- 造成 finalDamage * damage_ratio 的 secondary damage
- 不触发暴击、吸血、Slayer、Magic Find、额外掉落
```

## 12.2 VAMPIRISM 血饮

建议把普通吸血 `leech` 做成数值，`vampirism` 做稀有版：

```text
触发：玩家近战造成实际伤害后
条件：
- 主手有 enabled 的 vampirism
- 不是远程
- 不是魔法
- 不是 secondary damage
效果：
- 恢复 actualDamage * ratio
- 对 Boss 使用 boss_multiplier
- 每个玩家有 cooldown
```

YML：

```yaml
vampirism:
  enabled: true
  display: "血饮"
  rarity: RARE
  max_level: 3
  slots: [MELEE_WEAPON]
  effect:
    type: VAMPIRISM
    trigger: ON_DAMAGE_DEALT
    params:
      heal_ratio:
        type: PER_LEVEL
        values: [0.01, 0.015, 0.02]
      boss_multiplier:
        type: CONSTANT
        value: 0.5
      cooldown_seconds:
        type: CONSTANT
        value: 1.0
```

## 12.3 HUNTER_MARK 猎印

```text
触发：弓弩命中
效果：
- 对目标记录 mark owner = player uuid
- 后续同一玩家远程命中该目标时叠层
- 每层增加远程伤害
- 换目标或过期清空
```

状态可以用内存 Map，不要写 PDC：

```java
Map<UUID, HunterMarkState> marksByPlayer;
```

## 12.4 PERFECT_GUARD 完美格挡

`ShieldManager` 目前已经集中处理盾牌格挡，应在这里接入，而不是另写一套盾牌逻辑。

改造方案：

```text
1. ShieldManager 记录玩家开始举盾时间。
2. resolveShieldBlock() 中检查盾上 perfect_guard 等级。
3. 如果当前时间 - 举盾开始时间 <= timing_window_seconds：
   - threshold += threshold_bonus
   - 格挡成功后触发冷却
   - 可播放音效/粒子
```

## 12.5 VEIN_RESONANCE 矿脉共鸣

```text
触发：Mining Spread 成功后
效果：
- 按 chance 额外扩散一次
- 二次扩散标记 secondary_spread
- secondary_spread 不触发稀有掉落、不触发再次共鸣
```

---

# 13. 终极附魔实现规则

终极附魔不要先做太多。第一批只做 3 个：

```text
ultimate_apex_slayer
ultimate_berserker_oath
ultimate_phoenix_core
```

## 13.1 终极限制

`EnchantManager#canApply()` 必须检查：

```java
if (definition.rarity() == EnchantRarity.ULTIMATE) {
    int currentUltimateCount = countUltimateEnchants(item);
    boolean alreadyHasThis = getCustomEnchantLevel(item, definition.id()) > 0;

    if (!alreadyHasThis && currentUltimateCount >= settings.ultimateLimitPerItem()) {
        return EnchantApplyResult.fail("一件装备只能拥有一个终极附魔");
    }
}
```

## 13.2 ultimate_apex_slayer 猎王

```text
触发：伤害计算
条件：
- 目标是 Boss 或 Slayer 任务目标
- 玩家连续攻击同一个目标
效果：
- 每次命中叠 1 层
- 每层 +damage_bonus_per_stack
- 最高 max_stacks
- expire_seconds 未命中清空
```

## 13.3 ultimate_berserker_oath 狂战誓约

```text
触发：伤害计算、受伤计算
条件：
- 主手近战武器有该附魔
- 副手为空，或副手不是盾
效果：
- 近战伤害增加
- 受到伤害增加
```

YML：

```yaml
ultimate_berserker_oath:
  enabled: true
  display: "狂战誓约"
  rarity: ULTIMATE
  max_level: 1
  slots: [MELEE_WEAPON]
  conflict_group: ultimate
  effect:
    type: BERSERKER_OATH
    trigger: PASSIVE
    params:
      damage_bonus:
        type: CONSTANT
        value: 0.25
      incoming_damage_penalty:
        type: CONSTANT
        value: 0.12
      require_no_shield: true
```

## 13.4 ultimate_phoenix_core 不灭余烬

```text
触发：玩家受到致死伤害
条件：
- 胸甲有该附魔
- 冷却结束
效果：
- 取消致死
- 玩家保留 1 点生命
- 清除部分负面状态
- 播放粒子/音效
- 进入长冷却
```

冷却必须存玩家 UUID 的内存 Map，也可以持久化到数据库，第一版用内存即可。

---

# 14. enchant_pools.yml

附魔台、NPC、战利品不要直接遍历所有 enabled 附魔，应该使用 pool。

```yaml
version: 1

vanilla_enchant_table:
  enabled: true
  cost:
    exp_level_base: 5
    exp_level_per_power: 3
  rewards:
    common:
      weight: 65
      rarities: [COMMON]
    uncommon:
      weight: 25
      rarities: [UNCOMMON]
    dust:
      weight: 8
      item_id: magic_dust
      amount_min: 1
      amount_max: 4
    rare:
      weight: 2
      rarities: [RARE]
  blocked_rarities: [ULTIMATE]

special_enchant_table:
  enabled: true
  allowed_rarities: [COMMON, UNCOMMON]
  allow_choose_level: true
  cost:
    COMMON:
      dust_per_level_square: 4
      exp_per_level: 5
    UNCOMMON:
      dust_per_level_square: 12
      exp_per_level: 8

npc_books:
  enabled: true
  pools:
    rare_rotation:
      rarities: [RARE]
      refresh_hours: 24
    ultimate_rotation:
      rarities: [ULTIMATE]
      refresh_hours: 72

grindstone:
  remove_single:
    COMMON:
      dust_per_level: 8
    UNCOMMON:
      dust_per_level: 24
    RARE:
      dust_per_level: 80
    ULTIMATE:
      dust_per_level: 500
      require_special_material: true
  clear_all:
    refund_dust_ratio: 0.35
    refund_exp_ratio: 0.25
```

所有抽取逻辑必须过滤：

```java
definition.enabled()
definition.rarity() in allowedRarities
definition.slots() matches target item
```

---

# 15. 铁砧合并

新增 `EnchantAnvilListener`。

## 15.1 合并规则

```text
书 + 书：
- 同 ID 同等级 => 等级 +1，不超过 max_level
- 同 ID 不同等级 => 保留较高等级
- 不同 ID => 合并，检查冲突组

装备 + 书：
- 把书上的附魔合并到装备上
- 检查 slots
- 检查冲突组
- 检查终极数量

装备 + 装备：
- 暂时可以不支持，或只合并右侧装备的附魔
```

## 15.2 disabled 附魔规则

```text
1. disabled 附魔可以被保留。
2. disabled 附魔不能升级。
3. disabled 附魔不能新增到没有该附魔的物品。
```

## 15.3 结果槽保护

必须监听 `PrepareAnvilEvent` 生成结果，并在 `InventoryClickEvent` 玩家取出结果时再次校验一次。
原因是有些插件或客户端交互会导致 prepare 阶段和取出阶段结果不一致。

---

# 16. 砂轮

第一版不要强行复用原版砂轮 UI 做“指定移除”，因为指定移除需要选择某个附魔。建议：

```text
1. 原版砂轮交互：
   - 放入物品后，结果为“清空全部自定义附魔”
   - 返还粉尘和经验

2. 指定移除：
   - 做一个自定义 GUI
   - 左边放装备
   - 中间列出装备上的附魔
   - 点击某个附魔，消耗粉尘移除
```

这样交互最清晰，也方便之后扩展“保护符”“重铸锁定”等机制。

---

# 17. lore 渲染

`ItemFormatManager` 当前已经是统一 lore 渲染入口，应继续保持这个原则。([GitHub][7])

自定义附魔 lore 建议顺序：

```text
1. 物品名
2. 基础属性
3. 宝石/插槽
4. 重铸
5. 自定义附魔
6. 能力
7. 故事 lore
8. 稀有度
```

附魔显示格式：

```text
§7锋芒 V
§7亡灵杀手 III
§9劈砍 II
§6猎王 I
```

禁用附魔：

```text
§8[已禁用] 劈砍 II
```

未知附魔：

```text
§8[未知附魔] old_enchant_id:3
```

描述中的 `{base_damage}`、`{crit_chance_percent}`、`{damage_ratio_percent}` 可以由 `EnchantDescriptionRenderer` 动态替换。

---

# 18. 第一批默认附魔配置

先实现这些，避免系统一次性太大。

```yaml
enchants:
  keen:
    enabled: true
    display: "锋芒"
    rarity: COMMON
    max_level: 5
    slots: [MELEE_WEAPON]
    conflict_group: damage_generic
    numeric:
      base_damage:
        type: LINEAR
        per_level: 2.0

  might:
    enabled: true
    display: "强攻"
    rarity: COMMON
    max_level: 5
    slots: [WEAPON]
    conflict_group: damage_generic
    numeric:
      base_multiplier:
        type: LINEAR
        per_level: 0.015

  critical:
    enabled: true
    display: "精准"
    rarity: COMMON
    max_level: 5
    slots: [WEAPON, RANGED_WEAPON]
    numeric:
      crit_chance:
        type: LINEAR
        per_level: 0.012

  crit_damage:
    enabled: true
    display: "毁伤"
    rarity: COMMON
    max_level: 5
    slots: [WEAPON, RANGED_WEAPON]
    numeric:
      crit_damage:
        type: LINEAR
        per_level: 0.04

  fortify:
    enabled: true
    display: "坚固"
    rarity: COMMON
    max_level: 5
    slots: [ARMOR, SHIELD]
    numeric:
      base_armor:
        type: LINEAR
        per_level: 8.0

  efficiency_core:
    enabled: true
    display: "效率核心"
    rarity: COMMON
    max_level: 5
    slots: [TOOL]
    numeric:
      tool_mining_speed:
        type: LINEAR
        per_level: 40.0

  fortune_core:
    enabled: true
    display: "时运核心"
    rarity: COMMON
    max_level: 5
    slots: [TOOL]
    numeric:
      tool_fortune:
        type: LINEAR
        per_level: 8.0

  undead_slayer:
    enabled: true
    display: "亡灵杀手"
    rarity: UNCOMMON
    max_level: 5
    slots: [WEAPON]
    target:
      main_tags: [UNDEAD]
    numeric:
      damage_to_target:
        type: LINEAR
        per_level: 0.05

  skeleton_slayer:
    enabled: true
    display: "骷髅杀手"
    rarity: UNCOMMON
    max_level: 5
    slots: [WEAPON]
    target:
      main_tags: [SKELETON]
    numeric:
      damage_to_target:
        type: LINEAR
        per_level: 0.05

  humanoid_slayer:
    enabled: true
    display: "人形杀手"
    rarity: UNCOMMON
    max_level: 5
    slots: [WEAPON]
    target:
      main_tags: [HUMANOID]
    numeric:
      damage_to_target:
        type: LINEAR
        per_level: 0.05

  construct_breaker:
    enabled: true
    display: "构装破坏"
    rarity: UNCOMMON
    max_level: 5
    slots: [WEAPON]
    target:
      main_tags: [CONSTRUCT]
    numeric:
      damage_to_target:
        type: LINEAR
        per_level: 0.05

  giant_hunter:
    enabled: true
    display: "巨兽猎手"
    rarity: UNCOMMON
    max_level: 5
    slots: [WEAPON]
    target:
      main_tags: [GIANT]
    numeric:
      damage_to_target:
        type: LINEAR
        per_level: 0.05

  draw_power:
    enabled: true
    display: "蓄势"
    rarity: UNCOMMON
    max_level: 5
    slots: [RANGED_WEAPON]
    numeric:
      full_charge_damage:
        type: LINEAR
        per_level: 0.03

  mining_focus:
    enabled: true
    display: "采矿专精"
    rarity: UNCOMMON
    max_level: 5
    slots: [PICKAXE]
    numeric:
      mining_fortune:
        type: LINEAR
        per_level: 15.0

  cleave:
    enabled: true
    display: "劈砍"
    rarity: RARE
    max_level: 3
    slots: [MELEE_WEAPON]
    conflict_group: melee_mechanic
    effect:
      type: CLEAVE
      trigger: ON_MELEE_HIT
      params:
        radius:
          type: PER_LEVEL
          values: [3.0, 3.25, 3.5]
        max_targets:
          type: PER_LEVEL
          values: [2, 3, 3]
        damage_ratio:
          type: PER_LEVEL
          values: [0.10, 0.15, 0.20]
        secondary_damage: true

  vampirism:
    enabled: true
    display: "血饮"
    rarity: RARE
    max_level: 3
    slots: [MELEE_WEAPON]
    effect:
      type: VAMPIRISM
      trigger: ON_DAMAGE_DEALT
      params:
        heal_ratio:
          type: PER_LEVEL
          values: [0.01, 0.015, 0.02]
        boss_multiplier:
          type: CONSTANT
          value: 0.5
        cooldown_seconds:
          type: CONSTANT
          value: 1.0

  perfect_guard:
    enabled: true
    display: "完美格挡"
    rarity: RARE
    max_level: 3
    slots: [SHIELD]
    effect:
      type: PERFECT_GUARD
      trigger: ON_SHIELD_BLOCK
      params:
        timing_window_seconds:
          type: CONSTANT
          value: 0.35
        threshold_bonus:
          type: PER_LEVEL
          values: [15.0, 25.0, 40.0]
        cooldown_seconds:
          type: PER_LEVEL
          values: [8.0, 6.0, 4.0]

  ultimate_apex_slayer:
    enabled: true
    display: "猎王"
    rarity: ULTIMATE
    max_level: 1
    slots: [WEAPON]
    conflict_group: ultimate
    effect:
      type: APEX_SLAYER
      trigger: ON_DAMAGE_CALCULATE
      params:
        damage_bonus_per_stack:
          type: CONSTANT
          value: 0.02
        max_stacks:
          type: CONSTANT
          value: 10
        expire_seconds:
          type: CONSTANT
          value: 5.0

  ultimate_berserker_oath:
    enabled: true
    display: "狂战誓约"
    rarity: ULTIMATE
    max_level: 1
    slots: [MELEE_WEAPON]
    conflict_group: ultimate
    effect:
      type: BERSERKER_OATH
      trigger: PASSIVE
      params:
        damage_bonus:
          type: CONSTANT
          value: 0.25
        incoming_damage_penalty:
          type: CONSTANT
          value: 0.12
        require_no_shield: true

  ultimate_phoenix_core:
    enabled: true
    display: "不灭余烬"
    rarity: ULTIMATE
    max_level: 1
    slots: [CHESTPLATE]
    conflict_group: ultimate
    effect:
      type: PHOENIX_CORE
      trigger: ON_FATAL_DAMAGE
      params:
        cooldown_seconds:
          type: CONSTANT
          value: 180.0
        remain_health:
          type: CONSTANT
          value: 1.0
        clear_negative_effects: true
```

---

# 19. 测试命令

新增调试命令，方便服主测试。

```text
/sce reload enchants
/sce enchant list
/sce enchant give <player> <enchant_id> <level>
/sce enchant remove <player> <enchant_id>
/sce enchant clear <player>
/sce enchant debug
```

`debug` 输出手持物品：

```text
物品：xxx
附魔 PDC：keen:5;cleave:2
active enchants:
- keen V enabled=true numeric base_damage=10
- cleave II enabled=true effect=CLEAVE radius=3.25 damage_ratio=0.15
disabled enchants:
- old_test I enabled=false
```

---

# 20. 实现顺序

按这个顺序做，风险最低：

```text
第一步：
实现 EnchantRegistry + enchants.yml 加载 + enabled 开关。

第二步：
重构 EnchantManager，保留 PDC 格式，新增 active 读取方法。

第三步：
改 ItemFormatManager 的附魔 lore，让它从 EnchantRegistry 取名字、稀有度、说明、禁用状态。

第四步：
实现 EnchantStatResolver，把普通/罕见 numeric 接入 CombatStats。

第五步：
重构 EnchantTargetMatcher，从 YML 的 target + damage_to_target 读取，不再硬编码每级 5%。

第六步：
实现 EnchantEffectService，只做 cleave / vampirism / perfect_guard 三个稀有效果。

第七步：
实现终极限制和 1~2 个终极附魔。

第八步：
实现 enchant_pools.yml、附魔台、特殊附魔台、砂轮、NPC 交易。

第九步：
实现铁砧合并规则和冲突组校验。

第十步：
补测试命令和 debug 输出。
```

---

# 21. 验收标准

完成后至少要满足：

```text
1. 把 keen.enabled 改成 false，重载后：
   - 已有 keen 物品 lore 显示已禁用；
   - keen 不再增加基础伤害；
   - 附魔台不再产出 keen；
   - 铁砧不能把 keen 升级。

2. 把 undead_slayer.numeric.damage_to_target.per_level 从 0.05 改成 0.10，重载后：
   - 不改代码即可变成每级 +10%。

3. 把 cleave.effect.params.damage_ratio 改成 [0.05, 0.08, 0.12]，重载后：
   - 劈砍溅射伤害立刻变化。

4. 一件武器已经有 ultimate_apex_slayer 时：
   - 不能再打 ultimate_berserker_oath。

5. disabled 的终极附魔：
   - 不触发；
   - 不计入终极上限，或者计入上限需要由 settings 控制。
   推荐默认不计入上限，避免废弃附魔卡死旧装备。

6. secondary damage：
   - 不触发 cleave；
   - 不触发 vampirism；
   - 不触发 Magic Find；
   - 不触发额外掉落。
```

---

最核心的实现原则是：**PDC 只存“这个物品拥有哪些附魔和等级”，所有效果都从 YML 动态解析**。这样你测试时只要改 `enabled`、`per_level`、`values`、`cooldown_seconds`，就能快速平衡，不需要反复改 Java 代码。

[1]: https://raw.githubusercontent.com/SaltedFishAJLC/MyServerCore/main/src/main/java/com/servercore/manager/EnchantManager.java "raw.githubusercontent.com"
[2]: https://raw.githubusercontent.com/SaltedFishAJLC/MyServerCore/main/src/main/java/com/servercore/manager/PDCManager.java "raw.githubusercontent.com"
[3]: https://raw.githubusercontent.com/SaltedFishAJLC/MyServerCore/main/src/main/java/com/servercore/manager/CombatStats.java "raw.githubusercontent.com"
[4]: https://raw.githubusercontent.com/SaltedFishAJLC/MyServerCore/main/src/main/java/com/servercore/manager/CombatManager.java "raw.githubusercontent.com"
[5]: https://raw.githubusercontent.com/SaltedFishAJLC/MyServerCore/main/src/main/java/com/servercore/combat/integration/EnchantTargetMatcher.java "raw.githubusercontent.com"
[6]: https://raw.githubusercontent.com/SaltedFishAJLC/MyServerCore/main/src/main/java/com/servercore/manager/ShieldManager.java "raw.githubusercontent.com"
[7]: https://raw.githubusercontent.com/SaltedFishAJLC/MyServerCore/main/src/main/java/com/servercore/manager/ItemFormatManager.java "raw.githubusercontent.com"
