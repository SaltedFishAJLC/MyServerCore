# 附魔体系实现状态、设计约定与后续任务

更新时间：2026-06-18

本文以当前仓库代码为准，并结合本对话中已经确认的玩法目标，记录 ServerCore 自定义附魔体系的实际实现状态、稳定设计约定、已知偏差和后续任务。

文中的“已配置”只表示 `enchants.yml` 已存在定义、等级、槽位、说明和互斥，不等于对应战斗机制已经接入。

## 1. 当前结论

附魔体系的基础框架和经济循环已经形成：

- 自定义附魔通过 PDC 保存，配置由 `enchants.yml` 和 `enchant_pools.yml` 驱动。
- 当前共有 65 个附魔定义，其中 64 个启用，`ultimate_apex_slayer` 当前禁用。
- 普通附魔台、定向附魔台、轮换附魔书、怪物掉落书、自定义装备预置附魔、铁砧和砂轮均已有代码入口。
- 软上限与硬上限已经分离，特殊来源可以产出高于软上限但不超过硬上限的附魔。
- 物品面板可以用蓝色括号显示可归入基础属性的附魔加成。
- Magic Dust 已有正式自定义物品、配方、回收价格和消耗路径。
- 吸血已收束为统一数值；Vampirism、职业吸血和其他装备来源会进入同一统计。
- 赋能射击和 Rend 已形成第一版远程主动技能链路。

当前成熟度应定义为：

> 获取、存储、显示、基础合并和拆除流程可用；常规数值附魔与一部分战斗附魔可用；复杂长弓、弩和终极附魔仍有较大机制缺口。

不能把 `enchants.yml` 中所有启用条目理解为已经完整生效。当前最需要优先处理的差异是：

- `MAGIC_WEAPON` 和 `ACCESSORY` 槽位匹配尚未实现。
- 命令版定向附魔可以指定到硬上限，和“特殊附魔台不得超过软上限”的最终约定不一致。
- 多个新附魔在 Java 中重复硬编码数值，没有真正读取 YAML 曲线。
- 一批复杂远程附魔只有配置、槽位和互斥，没有弹道或状态机。
- 没有 `src/test`，附魔体系目前完全依赖构建和人工服内验证。

## 2. 主要代码与配置入口

核心模型与注册：

- `src/main/java/com/servercore/enchant/EnchantRegistry.java`
- `src/main/java/com/servercore/enchant/EnchantDefinition.java`
- `src/main/java/com/servercore/enchant/EnchantSettings.java`
- `src/main/java/com/servercore/enchant/ValueCurve.java`
- `src/main/java/com/servercore/enchant/EnchantSlot.java`
- `src/main/java/com/servercore/enchant/EnchantSlotMatcher.java`
- `src/main/java/com/servercore/manager/EnchantManager.java`

数值、说明和物品面板：

- `src/main/java/com/servercore/enchant/EnchantStatResolver.java`
- `src/main/java/com/servercore/enchant/EnchantDescriptionRenderer.java`
- `src/main/java/com/servercore/manager/ItemFormatManager.java`
- `src/main/java/com/servercore/manager/CombatStats.java`

获取和改造入口：

- `src/main/java/com/servercore/enchant/EnchantTableListener.java`
- `src/main/java/com/servercore/enchant/EnchantAcquisitionManager.java`
- `src/main/java/com/servercore/enchant/EnchantBookFactory.java`
- `src/main/java/com/servercore/enchant/EnchantAnvilListener.java`
- `src/main/java/com/servercore/enchant/EnchantGrindstoneListener.java`
- `src/main/java/com/servercore/enchant/EnchantPoolRegistry.java`

战斗和主动技能：

- `src/main/java/com/servercore/enchant/EnchantEffectService.java`
- `src/main/java/com/servercore/enchant/RangedEmpowermentManager.java`
- `src/main/java/com/servercore/enchant/ManaAccess.java`
- `src/main/java/com/servercore/combat/integration/EnchantTargetMatcher.java`
- `src/main/java/com/servercore/manager/CombatManager.java`
- `src/main/java/com/servercore/manager/ClassPassiveManager.java`
- `src/main/java/com/servercore/manager/WeaponTemplateManager.java`

内容配置：

- `src/main/resources/enchants.yml`
- `src/main/resources/enchant_pools.yml`
- `src/main/resources/custom_items.yml`
- `src/main/resources/custom_mobs.yml`
- `src/main/resources/recipes.yml`
- `src/main/resources/recycle.yml`

运行时实际读取：

- `plugins/ServerCore/enchants.yml`
- `plugins/ServerCore/enchant_pools.yml`
- `plugins/ServerCore/custom_items.yml`
- `plugins/ServerCore/custom_mobs.yml`

资源目录中的默认 YAML 只会在运行时文件不存在时复制。更新 jar 不会自动合并新增附魔、物品、配方或掉落条目。

## 3. 稳定设计约定

### 3.1 PDC 存储契约

自定义附魔继续保存在：

```text
KEY_ITEM_CUSTOM_ENCHANTS
```

编码格式保持为：

```text
id:level;id:level
```

例如：

```text
critical:4;vampirism:2
```

约定如下：

- 不迁移现有 PDC 格式。
- 配置注册表、效果服务和获取入口都建立在该格式之上。
- 未知附魔保留原始 PDC，不主动删除。
- 禁用附魔保留在物品上，但不参与 active 数值和机制。
- active 等级最多按定义的 `max_level` 生效。

### 3.2 软上限与硬上限

每个附魔有两个等级上限：

- `soft_max_level`：常规成长上限。
- `max_level`：任何来源都不能超过的硬上限。

当前约定是：

| 来源 | 允许等级 |
| --- | --- |
| 普通附魔台 | 不超过软上限 |
| 定向附魔台 GUI | 每次提升 1 级，不超过软上限 |
| 两件同级装备合并 | 可以升级，但不能越过软上限 |
| 两本同级附魔书合并 | 可以升级，但不能越过软上限 |
| 高等级特殊附魔书敲到装备 | 可以高于软上限，不超过硬上限 |
| NPC 轮换书 | 可以高于软上限，不超过硬上限 |
| 怪物掉落书、稀有战利品 | 可以高于软上限，不超过硬上限 |
| 自定义装备预置附魔 | 可以高于软上限，不超过硬上限生效 |
| 管理员调试命令 | 可以高于软上限，不超过硬上限 |

铁砧的关键约束：

- `III + III -> IV` 若 IV 超过软上限，则拒绝。
- 左侧装备加右侧 IV 特殊书，可以直接得到 IV。
- 左侧附魔书加右侧附魔书不能借此绕过软上限。
- 已经高于软上限的装备会保留该等级，但不能靠同级合并继续升级。

当前偏差：

- `/sc enchant special <id> <level>` 只检查硬上限，仍可把 COMMON/UNCOMMON 附魔定向到软上限以上。
- 实体定向附魔台 GUI 已正确限制软上限。
- 后续应让命令入口复用 GUI 的软上限规则，或把它明确改为管理员专用特殊来源。

### 3.3 稀有度与获取边界

当前稀有度：

- `COMMON`
- `UNCOMMON`
- `RARE`
- `ULTIMATE`

默认获取约定：

- 普通附魔台权重为 COMMON 65、UNCOMMON 25、RARE 2。
- 普通附魔台屏蔽 ULTIMATE。
- 定向附魔台只允许 COMMON、UNCOMMON。
- NPC 轮换商店提供 3 个 RARE 日轮换和 1 个 ULTIMATE 三日轮换。
- RARE / ULTIMATE 可通过轮换书和怪物掉落书进入铁砧链路。

NPC 轮换目前不是正式 NPC 系统，而是：

```text
/sc enchant books
```

打开的 Bukkit GUI。

### 3.4 槽位匹配

已经定义的槽位包括：

- 通用：`WEAPON`、`MELEE_WEAPON`、`RANGED_WEAPON`
- 武器细分：`TWO_HANDED_MELEE`、`SHORTBOW`、`LONGBOW`、`CROSSBOW`
- 其他：`MAGIC_WEAPON`、护甲部位、盾牌、工具部位、`ACCESSORY`

长弓、短弓、弩和双手近战会复用 `WeaponTemplateManager`。

当前未完成：

- `MAGIC_WEAPON` 在 `EnchantSlotMatcher` 中固定返回 `false`。
- `ACCESSORY` 在 `EnchantSlotMatcher` 中固定返回 `false`。
- 因此 Mana Steal、Antigravity、Ultimate Wise 的法术武器适用面目前无法通过正常附魔入口获得。
- `WEAPON` 当前只覆盖近战和远程模板，不覆盖尚未建模的法术武器。

### 3.5 冲突与终极数量

冲突有两种表达：

- `conflict_group`：同组互斥。
- `conflicts`：显式列出附魔 id，按双向关系检查。

已经使用的主要互斥：

- `damage_generic`：Keen、Might、Ruthless。
- `sustain_conversion`：Vampirism、Mana Steal、Drain。
- `opening_strike`：First Strike、Triple Strike。
- Chain Lightning、Thunderbolt、Cleave 的非对称关系通过 `conflicts` 表达。
- 弩附魔使用显式冲突限制 Explosive Bolt、Armor Piercer、Skewer、Multishot、Burst Magazine 等组合。

终极附魔默认一件装备最多 1 个：

```yaml
ultimate_limit_per_item: 1
```

注意：注册表还会给未声明冲突组的 ULTIMATE 自动使用 `ultimate` 组。因此即使未来把数量上限调高，多终极组合仍可能被冲突组拦截；若要开放多终极附魔，需要同时调整该默认行为。

### 3.6 数值、说明与面板

数值曲线支持：

- `CONSTANT`
- `LINEAR`
- `PER_LEVEL`

`PER_LEVEL` 超出配置数组长度后沿用最后一档。因此 Vampirism 当前虽然硬上限为 5，但只配置了 3 档数值，IV、V 会沿用 III 的 2%。

玩家说明约定：

- YAML 中描述的是当前等级的总效果，不写成容易误解的“每级 +当前总值”。
- 每个附魔必须在定义前写 `# 效果：...` 注释。
- 当前 65 个定义均有对应效果注释。
- 能归入现有属性键的附魔加成，在物品面板基础值后用蓝色括号显示。
- 情境增伤、触发效果、范围伤害等不进入基础属性面板，只在附魔 lore 中说明。

示例：

```text
伤害: +24 (+10)
暴击伤害: (+20.0%)
```

当前架构差异：

- 旧一批通用 numeric、Slayer 和 `effect.params` 会直接读取 YAML 曲线。
- 多个新附魔的实际战斗数值在 `EnchantEffectService` 中再次硬编码。
- 修改这些附魔的 YAML 可能只改变 lore，而不改变真实效果。
- 后续新增附魔应优先通过注册表读取曲线，避免继续增加按 id 写死的数组。

### 3.7 Secondary Damage

范围、连锁、爆炸和 Rend 等追加伤害通过 `EnchantDamageContext` 标记为 secondary damage。

设计目标：

- 不再次触发主伤害计算。
- 不再次触发附魔。
- 不触发吸血、暴击、Magic Find 或额外掉落。
- 防止 Cleave、Chain Lightning、Explosive Bolt 等递归触发。

## 4. 当前玩法循环

### 4.1 普通附魔台

流程：

1. 玩家使用原版附魔台。
2. `EnchantPoolRegistry` 按权重抽取可用于该物品的 enabled 自定义附魔。
3. 等级按经验消耗和软上限抽取。
4. 先检查槽位、冲突、终极数量和是否能提升。
5. 清除本次原版附魔结果。
6. 把自定义附魔写入原物品并刷新 lore。

同一个物品可以多次使用附魔台。再次抽到同级附魔时会尝试 `+1`，但不会超过软上限。

### 4.2 定向附魔台

交互：

```text
潜行 + 右键原版附魔台
```

GUI 行为：

- 放入一件装备。
- 列出适用于该装备的 COMMON / UNCOMMON 附魔。
- 不显示已经达到软上限的条目。
- 每次点击提升 1 级。
- 消耗 Magic Dust 和经验等级。
- 关闭界面时返还槽位中的装备，背包满时掉落在玩家位置。

费用公式：

```text
COMMON Dust = 4 * 目标等级^2
COMMON EXP  = 5 * 目标等级

UNCOMMON Dust = 12 * 目标等级^2
UNCOMMON EXP  = 8 * 目标等级
```

第一版限制：

- `allow_choose_level` 没有在 GUI 中提供任意目标等级选择。
- GUI 当前固定为逐级提升。
- 命令版入口和 GUI 的软上限规则尚未统一。

### 4.3 轮换附魔书和怪物掉落

轮换书：

- `/sc enchant books` 打开商店。
- offer 按 pool id 和刷新时间窗稳定随机。
- 购买消耗 Magic Dust 和经验等级。
- 产出带自定义附魔 PDC 的 `ENCHANTED_BOOK`。
- 书可以作为铁砧右侧材料敲到装备上。

当前轮换：

- RARE：3 个条目，24 小时刷新，等级 1-5。
- ULTIMATE：1 个条目，72 小时刷新，等级 1-5。
- 最终等级仍会被每个附魔的硬上限截断。

怪物掉落：

- `custom_mobs.yml` 支持 `enchant_book` / `enchant_id`。
- `level` 支持固定等级或范围。
- 生成时会检查附魔存在、enabled 和硬上限。
- 稀有掉落继续复用 Magic Find 判定。

当前示例包括 Cleave、Vampirism、Phoenix Core 等附魔书。

注意：`ultimate_apex_slayer` 当前 disabled，因此对应 Flame Boss 掉落条目不会实际产出书。

### 4.4 铁砧

铁砧支持：

- 装备加附魔书。
- 装备加装备。
- 附魔书加附魔书。
- 目标槽位校验。
- disabled 附魔不升级。
- 冲突和终极数量校验。
- One For All 清除其他自定义附魔。

铁砧当前主要依赖 Bukkit 的过时 repair-cost API，仍可编译运行，但后续升级服务端 API 时需要复查。

### 4.5 砂轮

普通砂轮保留 clear-all：

- 清空全部自定义附魔。
- 按配置返还部分经验和 Magic Dust。
- clear-all 不要求终极剥离催化剂。

单条拆除：

```text
潜行 + 右键砂轮
```

- GUI 列出物品上的每条自定义附魔。
- 点击只移除选中的附魔。
- COMMON：每级 8 Magic Dust。
- UNCOMMON：每级 24 Magic Dust。
- RARE：每级 80 Magic Dust。
- ULTIMATE：每级 500 Magic Dust，另需 1 个 `ultimate_enchant_catalyst`。

兼容约定：

- 新产出优先使用正式 `magic_dust`。
- 仍识别历史 `gem_dust`。
- 仍把普通 `GLOWSTONE_DUST` 视作兼容粉尘，便于旧服迁移。

### 4.6 自定义装备预置附魔

`custom_items.yml` 已支持：

```yaml
items:
  example_weapon:
    material: DIAMOND_SWORD
    enchants:
      anatomy: 4
      critical: 6
```

该路径直接把附魔写入装备 PDC，因此可以制作自带高于软上限的特定装备。

当前规则：

- 配置读取时不会检查槽位、冲突或软上限。
- active 读取会把实际生效等级截断到硬上限。
- 等级高于硬上限时，PDC 原值可能仍被保存，但 lore 和效果最多按硬上限生效。
- 内容制作时仍应人工保证槽位、互斥和终极数量合法。

后续建议在 `CustomItemRegistry` 加载时输出校验警告，但不要自动删除配置内容。

### 4.7 赋能射击

适用武器：

- 长弓
- 短弓
- 弩

切换：

```text
Shift + 左键
```

命中时：

```text
伤害倍率 = 1 + 飞行距离 * 1.5%
魔力消耗 = 飞行距离 * 1.5
```

魔力不足时，用当前魔力可支付的最大距离向上取整，再以该距离计算增伤。

当前行为：

- 魔力在箭命中时扣除。
- 切换手持槽位会关闭赋能状态。
- Ultimate Wise 会降低赋能射击耗魔。
- Rend 会替换该武器的赋能射击主动操作。
- Twilight Zone 存在时会阻止赋能箭记录，但 Twilight Zone 自身尚未实现。

`ManaAccess` 通过反射兼容 AuraSkills 的 `getMana/setMana` 或 `getCurrentMana/setCurrentMana`。如果运行端 API 不提供这些方法，读取会得到 0，恢复和消耗不会正常工作。

## 5. 附魔实现状态

状态定义：

- `可用`：主要效果已接入实际属性、战斗、经济或工具流程。
- `简化`：有实际效果，但与目标设计仍有明显差异。
- `仅配置`：定义、lore、槽位或互斥存在，核心机制未接线。
- `禁用`：代码可能存在，但配置不允许正常生效。

### 5.1 通用数值、工具和目标附魔

| 附魔 | 状态 | 当前实现 |
| --- | --- | --- |
| Keen / 锋芒 | 可用 | 增加 `base_damage`，进入面板与 CombatStats。 |
| Might / 强攻 | 可用 | 增加 `base_multiplier`。 |
| Critical / 精准 | 可用 | 增加暴击率。 |
| Crit Damage / 毁伤 | 可用 | 增加暴击伤害。 |
| Fortify / 坚固 | 简化 | 面板会显示 `base_armor`，但当前未发现实际护甲结算读取 `EnchantStatBundle.baseArmor()`。 |
| Efficiency Core / 效率核心 | 可用 | 增加工具挖掘速度。 |
| Fortune Core / 时运核心 | 可用 | 增加工具时运。 |
| Mining Focus / 采矿专精 | 可用 | 增加挖矿时运。 |
| Undead Slayer / 亡灵克星 | 可用 | 对对应主标签目标增伤。 |
| Skeleton Slayer / 碎骨 | 可用 | 对对应主标签目标增伤。 |
| Humanoid Slayer / 行刑者 | 可用 | 对对应主标签目标增伤。 |
| Construct Breaker / 构装破坏 | 可用 | 对对应主标签目标增伤。 |
| Giant Hunter / 巨兽猎手 | 可用 | 对对应主标签目标增伤。 |
| Draw Power / 蓄势 | 仅配置 | `full_charge_damage` 尚未接入蓄力检测。 |
| Ruthless / 冷酷 | 可用 | 增加残暴，和 Keen、Might 互斥。 |
| Antigravity / 反重力 | 简化 | 对 FLYING 特质目标增伤可用；法术武器槽位不可用。 |

目标增伤的叠加约定：

- 同一主标签只取最高值。
- 同一特质标签只取最高值。
- 主标签、特质标签和 Boss 三类之间可以相加。

### 5.2 已接入的战斗与经济附魔

| 附魔 | 状态 | 当前实现与边界 |
| --- | --- | --- |
| Cleave / 劈砍 | 可用 | 近战命中造成物理 secondary damage，范围和目标数读取 effect 参数。 |
| Vampirism / 嗜血 | 可用 | 作为 `lifesteal` 数值进入统一吸血统计。 |
| Perfect Guard / 完美格挡 | 可用 | 接入 ShieldManager 的格挡阈值和冷却链路。 |
| Berserker Oath / 狂战誓约 | 可用 | 无盾时增伤并提高承伤。 |
| Phoenix Core / 不灭余烬 | 可用 | 胸甲致死保护、清负面效果和冷却。 |
| Knockback / 击退 | 简化 | 通过自定义速度向量模拟原版式击退。 |
| Power / 力量 | 可用 | 远程伤害乘区。 |
| Punch / 冲击 | 简化 | 通过自定义速度向量模拟原版式远程击退。 |
| Fire Aspect / 火舌 | 简化 | 设置燃烧时间，并立即结算一次 magic secondary damage；不是逐秒 DoT。 |
| Scavenger / 野蛮 | 简化 | 放大生态击杀奖励；当前基础奖励仍是 `怪物等级 * 5`，再乘赏金系数。 |
| Infinite Quiver / 无尽 | 可用 | 每级 5% 概率不消耗箭矢。 |
| Mana Steal / 魔力汲取 | 简化 | 非远程命中按伤害恢复魔力，0.75 秒冷却；法术武器正常附魔入口未实现，且依赖 ManaAccess 反射。 |
| Drain / 饮血 | 简化 | 击杀后按缺失生命恢复，血魔倍率生效，0.75 秒冷却；当前未严格限制为“敌对生物”。 |
| Anatomy / 解剖 | 可用 | 按目标当前生命百分比增加伤害。 |
| First Strike / 先发制人 | 可用 | 玩家和目标独立记录，20 秒未攻击该目标后刷新。 |
| Triple Strike / 三连击 | 可用 | 连续攻击同一目标时每第三击增伤。 |
| Chain Lightning / 连锁闪电 | 可用 | 近战暴击触发，2.5 格跳跃，secondary damage，最多 10 个追加目标。 |
| ThunderBolt / 雷击 | 可用 | 近战暴击增伤、眩晕 0.5 秒，玩家级 4 秒冷却。 |
| Armor Piercer / 破甲弩矢 | 简化 | 当前按伤害乘区处理，不是真正进入护甲公式的穿透；CONSTRUCT/HEAVY 提高 50%。 |
| Explosive Bolt / 爆裂弩矢 | 可用 | 弩命中后对周围目标造成 secondary damage。 |
| Full Draw / 满弦 | 简化 | 当前所有远程命中都获得增伤，没有检查满蓄力。 |
| Cloudpiercer / 穿云 | 简化 | FLYING 目标分支可用；距离超过 16 格分支和满蓄力检查未实现。 |
| One For All / 以一镇万 | 可用 | 应用时清除其他附魔、阻止再追加附魔、面板基础伤害 +150%；尚不覆盖未建模的法术武器。 |
| Overload / 超载 | 可用 | 增加暴击率/暴击伤害，并把超过 100% 的暴击率转换为暴击伤害。 |
| Combo / 以战养战 | 可用 | 击杀叠层，最多 6 层，按等级窗口过期。 |
| Rend / 撕裂 | 简化 | Shift+左键触发，按同武器签名消费命中标记，单目标最多 7 次，5 秒冷却。当前是内存标记，不会真的移除世界中的箭实体。 |
| Soul Eater / 灵魂收割 | 可用 | 记录 30 秒内最高怪物等级，动态增加武器面板伤害，上限 500。 |
| Swarm / 困兽之斗 | 可用 | 统计 10 格内 Enemy，最多 10 层。 |
| Execute / 血腥屠戮 | 简化 | 双手近战可处决低生命非 Boss 并眩晕周围敌人；当前未额外验证目标一定是敌对生物。 |
| Ultimate Wise / 究极之智 | 简化 | 只接入赋能射击耗魔折扣，尚未统一接入所有武器能力。 |

统一吸血：

- 装备、宝石、Vampirism 和职业基础吸血进入同一个 `lifesteal` 数值。
- 血魔最终倍率会放大总吸血。
- 统一吸血受单次最大生命比例上限约束。
- 统一吸血触发冷却为 0.75 秒。
- Mana Steal 使用独立 0.75 秒吸蓝冷却。
- Drain 使用独立 0.75 秒击杀治疗冷却。

### 5.3 已定义但核心机制未接线

| 附魔 | 缺失机制 |
| --- | --- |
| Spring Mechanism / 机簧改造 | 弩装填时间公式。 |
| Multishot / 多重射击 | 额外箭矢生成和弹道。 |
| Skewer / 贯穿射击 | 贯穿目标计数、后续伤害保留和最多额外 3 目标。 |
| Hunter's Mark / 猎手印记 | 目标标记、4 秒持续、5 层和换目标清空。 |
| Interrupting Shot / 截击 | 施法/蓄力识别、动作打断、Boss 特例和冷却。 |
| Focus / 射手专注 | 满蓄力保持 0.75/1.5 秒的状态检测。 |
| Zeroing / 精准校准 | 同目标连续命中叠层、未命中和换目标清空。 |
| Weakpoint Shot / 弱点射击 | 满蓄力暴击检测和全玩家共享易伤。 |
| Wind Shear / 风切 | 满蓄力箭速度修改。 |
| Horizon Sniper / 天穹狙击 | 静止/蹲伏计时、移动/跳跃/受击/未命中状态机。 |
| Duplex / 复式打击 | 锁定目标的追踪追加箭。 |
| Fatal Tempo / 致命节奏 | 临时残暴/攻速叠层、未命中扣层和 4 秒清除。 |
| Burst Magazine / 连弩机匣 | 连续命中计数和弱追踪弩矢。 |
| Siege Crossbow / 重弩架设 | 蹲伏静止架设与移动/切换解除。 |
| Sunpiercer / 贯日 | 满蓄力、20 格距离、5 秒冷却、击杀返还和移速。 |
| Judgement String / 审判之弦 | 满蓄力、高生命阈值、未命中 8 秒冷却。 |
| Starfall Volley / 星坠箭雨 | 延迟落箭生成、追踪区域和 8 秒冷却。 |
| Twilight Zone / 暮光视域 | 禁止移动/浮空、禁回复、视野扫描、暮光层数、射击耗魔、强制退出和标记清理。 |

### 5.4 禁用附魔

`Ultimate Apex Slayer / 猎王`：

- 配置为 `enabled: false`。
- 连续攻击 Boss 的叠层代码仍存在。
- disabled 时不会进入 active 附魔，也不会正常从掉落书产出。
- 若未来启用，应先重新验证 Boss 标签、叠层刷新和与其他终极附魔的关系。

## 6. 命令与运维

玩家入口：

```text
/sc enchant books
/sc enchant special <enchant_id> <level>
```

管理员入口：

```text
/sc reload enchants
/sc enchant list
/sc enchant give [player] <enchant_id> <level>
/sc enchant remove [player] <enchant_id>
/sc enchant clear [player]
/sc enchant debug
```

命令别名：

```text
/servercore
/score
/sc
/sce
```

相关资源修改后的重载：

```text
/sc reload enchants
/sc admin items reload
/sc admin recipe reload
```

部署时仍需人工把新的默认 YAML 条目合并到服务器已有的 `plugins/ServerCore` 文件。

## 7. 未完成任务

### P0：规则一致性和现有功能修正

1. 修正 `/sc enchant special`，不得绕过 `soft_max_level`；或者改为管理员权限并明确其特殊来源身份。
2. 实现 `MAGIC_WEAPON` 的可靠判定，至少覆盖带主动耗魔能力的自定义武器。
3. 让 Ultimate Wise 接入统一能力耗魔接口，而不只影响赋能射击。
4. 把 Fortify 的 `base_armor` 真正接入护甲统计和减伤结算。
5. 将新附魔 Java 硬编码数组改为读取 `EnchantDefinition.numericBonuses()` 或 `effect.params`。
6. 为 CustomItemRegistry 的预置附魔增加存在、槽位、冲突、终极数量和硬上限警告。
7. 明确 clear-all 是否应继续允许无催化剂清除终极附魔；当前行为兼容旧设计，但会绕过单拆的特殊材料门槛。

### P1：补完已有简化实现

1. Fire Aspect 改为真正的魔法减益 DoT，由状态系统分段结算。
2. Armor Piercer 接入护甲公式，而不是直接伤害乘区。
3. Full Draw、Cloudpiercer 接入蓄力和飞行距离快照。
4. Drain、Combo、Execute 等击杀/目标效果严格限制目标类型。
5. Scavenger 统一“赏金系数 × 怪物等级”的基础公式，避免和现有 `等级 * 5` 口径冲突。
6. Rend 绑定唯一武器实例、保存真实命中伤害快照，并处理箭实体或更明确地更名为标记机制。
7. 赋能射击在发射时快照 Ultimate Wise 和武器身份，避免箭飞行中切换装备影响命中结算。
8. 给 First Strike、Triple Strike、Combo、Soul Eater 等内存状态增加退出清理和过期清理。

### P1：远程弹道状态机

建议先建立统一 `RangedShotContext`，记录：

- 发射玩家
- 武器唯一签名
- 武器模板
- 附魔快照
- 发射位置和命中距离
- 蓄力比例和满蓄力时长
- 是否暴击
- 已命中目标
- 是否 secondary projectile
- 是否允许触发附魔、吸血和掉落

在此基础上按依赖顺序实现：

1. Full Draw、Focus、Wind Shear、Cloudpiercer。
2. Skewer、Hunter's Mark、Zeroing、Weakpoint Shot。
3. Multishot、Duplex、Burst Magazine。
4. Horizon Sniper、Sunpiercer、Judgement String、Starfall Volley。
5. Twilight Zone。

### P2：内容和交互

1. 把轮换书 GUI 接到正式 NPC 或商店交互，而不只依赖命令。
2. 给特殊附魔台增加同一附魔的目标等级选择。
3. 为 Magic Dust、催化剂和高等级书补充稳定的战利品投放与经济平衡。
4. 清理或替换 disabled 的 `ultimate_apex_slayer` 怪物掉落条目。
5. 补充 ACCESSORY 附魔槽位的识别和用途。

### P2：测试与可观测性

当前没有自动化测试。至少需要补：

- ValueCurve 等级边界测试。
- 软上限/硬上限和铁砧组合矩阵测试。
- 冲突组、显式冲突和终极数量测试。
- One For All 清除与锁定测试。
- 自定义物品高等级预置附魔测试。
- 砂轮单拆费用和终极材料测试。
- 普通附魔台、特殊附魔台和 NPC 书等级边界测试。
- secondary damage 不递归触发测试。
- 赋能射击距离、魔力不足和 Ultimate Wise 测试。
- 吸血、Mana Steal、Drain 的 0.75 秒冷却测试。

建议增加管理员调试输出：

- 当前武器匹配到的 EnchantSlot。
- 每个 active 附魔的最终等级和来源。
- 本次伤害实际应用的附魔乘区。
- 远程射击快照、飞行距离和耗魔。
- 附魔书来源、软硬上限和铁砧拒绝原因。

## 8. 新增附魔的实现清单

以后新增附魔时，至少完成以下项目：

1. 在 `enchants.yml` 增加定义。
2. 在定义前增加 `# 效果：...` 注释。
3. 写清显示名、稀有度、软上限、硬上限和槽位。
4. 写清冲突组或显式冲突。
5. 使用 `CONSTANT`、`LINEAR` 或 `PER_LEVEL` 表达数值。
6. lore 描述显示当前等级总值。
7. 明确是基础属性、目标增伤、触发效果还是主动技能。
8. 把机制接入已有服务，不在监听器中复制整套战斗结算。
9. secondary damage 必须显式标记。
10. 明确普通附魔台、特殊附魔台、轮换书和掉落书的可获得性。
11. 验证面板蓝色括号或情境 lore 是否正确。
12. 补测试或至少记录人工验证步骤。
13. 更新本文和 `Walkthrough.md`。

## 9. 验证基线

本次状态整理基于 2026-06-18 当前干净工作树，HEAD 为：

```text
358a3fd Add config-driven enchant effect services
```

本次文档整理完成后执行：

```text
.\gradle-8.5\bin\gradle.bat --no-daemon build
```

结果：

```text
BUILD SUCCESSFUL in 20s
```

当前没有测试源码，Gradle 输出 `compileTestJava NO-SOURCE` 和 `test NO-SOURCE`。
