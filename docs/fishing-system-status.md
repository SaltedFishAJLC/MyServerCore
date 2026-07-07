# 钓鱼体系实现状态与后续任务

更新时间：2026-07-07

本文以当前仓库代码为准，并结合本项目对话中已经确认的设计要求，记录 ServerCore 钓鱼体系的实现状态、计算约定、配置接口、已知问题和后续任务。

## 1. 当前结论

钓鱼体系的主循环已经可运行：

- AuraSkills 提供钓鱼等级和常规钓鱼经验来源。
- ServerCore 接管 Fishing Speed、Sea Creature Chance、Treasure Chance。
- 基础等待窗口为 `150-300 ticks`。
- 钓鱼等级通过增加 Fishing Speed 间接缩短等待时间。
- 海怪判定优先于宝藏判定，两者互斥。
- 宝藏分为 rare、epic、legendary 三档。
- 海怪和宝藏均由 `gathering_loot.yml` 配置。
- 自定义结果可以额外发放 AuraSkills Fishing XP。
- `/sc gathering` 可以查看当前工具属性、最终概率和预计咬钩窗口。

当前成熟度属于“主流程和 P0 系统整合已实现，但仍存在内容平衡与自动化测试缺口”。尤其需要注意：

- 第一批 T1-T6 钓鱼装备、材料来源、宝藏交叉掉落和海怪击杀掉落已进入默认配置。
- 当前公式满配目标为有效 Fishing Speed `1500` 时达到 `15-60 ticks`。
- Fishing Speed 现在区分原始值与有效值；公式入口会把有效值封顶到 `1500`，超过上限不会继续缩短等待。
- 第一批 T1-T6 钓竿已进入默认配置，并写入 `fishing_rod`、`fishing_route`、`fishing_power`、`growth_line`、`growth_stage` 等 PDC/lore 字段。
- 第一批鱼饵和普通鱼已进入默认配置：鱼饵按背包顺序预约并在成功收杆后消耗，普通鱼作为海怪和宝藏失败后的第三类结果。
- 普通鱼食用由 `FishingContentManager` 拦截，使用 `PlayerRecoveryManager` 的立即治疗/饱食同步入口，不写入通用食物补给表，避免与后续料理系统冲突。
- 环境修正测试版已进入主流程：`FishingEnvironmentManager` 负责天气、群系、时间、开阔水域与环境标签，环境加成作为独立临时来源参与本次钓鱼计算，不写入玩家或物品 PDC。
- 多人事件测试版已进入主流程：`FishingEventManager` 在海怪或宝藏基础 roll 已成功之后尝试启动事件，默认提供 `sea_monster_raid` 与 `secret_treasure_hunt` 两个事件。
- ServerCore 自定义海怪、宝藏与多人事件现在默认要求开阔水域；开阔水域优先使用 Paper/原版 `FishHook#isInOpenWater()`，旧 5x5 水域检查只作为兼容 fallback。
- 当前 T6 装备侧目标按“套装 + 对应钓竿 + 固定套装效果”计算：海怪线 `lord_set + lord_seabond_rod + gatherers_compass` 约为 `1150 Fishing Speed / 78% SCC / 11.5% TC`；宝藏线 `tidevault_set + tidevault_starhook_rod + gatherers_compass` 约为 `1300 Fishing Speed / 31% SCC / 31% TC`。实际等待公式仍会额外叠加 Fishing 等级速度，并在 1500 封顶。
- 钓鱼属性当前聚合主手、护甲、4 个饰品槽、护符袋和临时来源；副手不生效。临时来源包括预约鱼饵和普通鱼食物 Buff。
- 来自鱼饵、普通鱼食物等临时来源的正向 Treasure Chance 默认合计封顶为 `+1.0` 百分点。
- 海怪已经通过 MobSpawnManager 专用入口统一接入减伤、虚拟血池和全息链路。
- 缺少钓鱼系统自动化测试。

## 2. 代码与配置入口

主要实现：

- `src/main/java/com/servercore/manager/FishingManager.java`
- `src/main/java/com/servercore/manager/FishingContentManager.java`
- `src/main/java/com/servercore/manager/FishingEnvironmentManager.java`
- `src/main/java/com/servercore/manager/FishingEventManager.java`
- `src/main/java/com/servercore/fishing/FishingContext.java`
- `src/main/java/com/servercore/fishing/FishingConditions.java`
- `src/main/java/com/servercore/fishing/FishingEnvironmentResult.java`
- `src/main/java/com/servercore/manager/AuraSkillsBridge.java`
- `src/main/java/com/servercore/manager/GlobalStatManager.java`
- `src/main/java/com/servercore/manager/NonCombatStatsMenu.java`
- `src/main/java/com/servercore/manager/ItemFormatManager.java`
- `src/main/java/com/servercore/manager/PDCManager.java`
- `src/main/java/com/servercore/manager/PlayerRecoveryManager.java`

内容配置：

- `src/main/resources/gathering_loot.yml`
- `src/main/resources/fishing_baits.yml`
- `src/main/resources/fish_items.yml`
- `src/main/resources/fishing_environment.yml`
- `src/main/resources/fishing_events.yml`
- `src/main/resources/custom_items.yml`
- `src/main/resources/enchants.yml`

服务器运行时实际读取：

- `plugins/ServerCore/gathering_loot.yml`
- `plugins/ServerCore/fishing_baits.yml`
- `plugins/ServerCore/fish_items.yml`
- `plugins/ServerCore/fishing_environment.yml`
- `plugins/ServerCore/fishing_events.yml`
- `plugins/ServerCore/custom_items.yml`
- `plugins/ServerCore/enchants.yml`

资源目录中的 YAML 只会在运行时文件不存在时复制。更新 jar 不会自动把新条目合并进已有运行时配置。

重载命令：

```text
/sc admin gatheringloot reload
/sc admin loot reload
/sc admin fishing reload
/sc admin fishing debug
/sc admin items reload
/sc admin bait list
/sc admin bait give <id> [amount]
/sc admin fish list
/sc admin fish give <id> [amount]
```

查看面板：

```text
/sc gathering
```

## 3. 一次钓鱼的实际流程

FishingManager 监听 `PlayerFishEvent`，当前流程如下：

1. 状态为 `FISHING`：
   - 扫描 `PlayerInventory#getStorageContents()`，按槽位顺序预约第一个有效鱼饵。
   - 预约会临时扣除 1 个鱼饵并绑定到 `FishHook` UUID；空杆、取消、换世界、退出或钩子消失时返还。
   - 读取 AuraSkills Fishing 等级。
   - 聚合主手、护甲、饰品、护符袋、食物 Buff 和预约鱼饵提供的 Fishing Speed。
   - 浮漂落水后读取 `FishingEnvironmentManager` 上下文；若满足开阔水域和环境规则，额外叠加环境 Fishing Speed / SCC / TC。
   - 计算等待窗口。
   - 写入 `FishHook#setMinWaitTime` 和 `setMaxWaitTime`。

2. 状态为 `CAUGHT_FISH`：
   - 通过 `FishingEnvironmentManager` 构建本次收杆上下文，包含群系、群系标签、环境标签、天气、时间、开阔水域、雨水影响和天空影响。
   - 检查是否满足 ServerCore 的开阔水域要求。
   - 不满足时不执行自定义海怪、宝藏或多人事件逻辑，保留原版结果。
   - 满足时先判定 Sea Creature。
   - 海怪概率命中后先按 `conditions` 选择合格条目，再给多人事件一次启动机会；事件没有启动时才尝试生成普通海怪。
   - 海怪成功：清零原版经验、生成海怪，本次不再判定宝藏。
   - 海怪失败：判定 Treasure Chance。
   - 宝藏成功：按等级筛选档位，再按权重选择档位和条目；随后给宝藏多人事件一次启动机会，事件没有启动时才替换为宝藏物品。
   - 宝藏失败：进入普通鱼池，按 Fishing 等级、天气、鱼饵门槛和权重修正选择普通鱼。
   - AuraSkills 在 `MONITOR` 阶段读取原始渔获并发放常规 Fishing XP。
   - ServerCore 随后在同一 `MONITOR` 阶段移除海怪分支的原渔获，或把宝藏/普通鱼分支的原渔获替换成自定义物品。
   - 成功钓起海怪、宝藏、普通鱼或保留原版渔获时，预约鱼饵正式消耗。

优先级约定：

```text
Sea Creature > Treasure > Normal Fish
```

海怪和宝藏不能在同一次收杆中同时出现。

## 4. Fishing Speed 体系

### 4.1 属性来源

当前原始速度由等级和装备聚合组成：

```text
Raw Fishing Speed
= Level Fishing Speed
+ Main-hand Fishing Speed
+ Armor Fishing Speed
+ Accessory Slot Fishing Speed
+ Talisman Bag Fishing Speed
```

实际进入等待公式前会再应用硬上限：

```text
Effective Fishing Speed = min(Raw Fishing Speed, 1500)
```

面板应保留原始/有效两层信息，避免玩家堆到 1500 以上后误以为等待仍会继续缩短。

每个装备来源都包含物品基础 PDC 与 `EnchantStatResolver` 返回的 active 附魔临时加成。副手不参与钓鱼属性聚合。

等级速度：

```text
Level Fishing Speed = clamp(Fishing Level, 0, 100) × 3
```

因此：

- 0 级提供 0 Fishing Speed。
- 45 级提供 135 Fishing Speed。
- 100 级提供 300 Fishing Speed。

主手物品 PDC key：

```text
fishing_speed
```

附魔通过 `EnchantStatResolver` 临时叠加，不应永久写回基础物品 PDC。

### 4.2 等待时间公式

设计目标：

- 初始等待窗口：`150-300 ticks`。
- 满配有效 Fishing Speed 达到 1500 时：`15-60 ticks`。
- 最低边界固定为 `15-60 ticks`，不会继续缩短。

原始速度先封顶为有效速度 `S = min(Raw Fishing Speed, 1500)`。

最短等待：

```text
MinScale = 15 × 1500 / (150 - 15)
         ≈ 166.6667

MinWait = max(15, round(150 × MinScale / (MinScale + S)))
```

最长等待：

```text
MaxScale = 60 × 1500 / (300 - 60)
         = 375

MaxWait = max(60, round(300 × MaxScale / (MaxScale + S)))
```

实际示例：

| 有效 Fishing Speed | 等待窗口 |
|---:|---:|
| 0 | 150-300 ticks |
| 135 | 83-221 ticks |
| 180 | 72-203 ticks |
| 300 | 54-167 ticks |
| 315 | 52-163 ticks |
| 480 | 39-132 ticks |
| 800 | 26-96 ticks |
| 1100 | 20-76 ticks |
| 1400 | 16-63 ticks |
| 1500 | 15-60 ticks |
| 1600 | 15-60 ticks |

### 4.3 钓竿阶段与终局预算

`tidecaller_rod` 保留为旧版特殊兼容钓竿：

```yaml
tidecaller_rod:
  fishing_speed: 180
  sea_creature_chance: 4.0
  treasure_chance: 5.0
  fishing_route: SPECIAL
  fishing_power: 120
```

100 级玩家使用该钓竿时：

```text
Raw Fishing Speed = 300 + 180 = 480
等待窗口约为 39-132 ticks
```

第一批钓竿阶段：

```text
T1 reed_rod                   MIXED        35 FS / 2.0 SCC / 0.5 TC / Power 10
T2 ink_rod                    MIXED        80 FS / 4.0 SCC / 1.5 TC / Power 25
T3 inkbound_tidebinder_rod    SEA_CREATURE 110 FS / 6.0 SCC / 1.0 TC / Power 55
T4 sharktooth_wavebreaker_rod SEA_CREATURE 200 FS / 9.0 SCC / 1.5 TC / Power 120
T5 riptide_kinghunter_rod     SEA_CREATURE 300 FS / 12.0 SCC / 2.0 TC / Power 300
T6 lord_seabond_rod           SEA_CREATURE 430 FS / 15.0 SCC / 2.5 TC / Power 600
T3 pearl_depthfinder_rod      TREASURE     150 FS / 2.0 SCC / 2.5 TC / Power 55
T4 diver_depthfinder_rod      TREASURE     260 FS / 3.0 SCC / 4.0 TC / Power 120
T5 abyss_treasureseeker_rod   TREASURE     390 FS / 4.0 SCC / 5.0 TC / Power 300
T6 tidevault_starhook_rod     TREASURE     520 FS / 5.0 SCC / 6.0 TC / Power 600
```

终局预算按“装备与套装目标”先不计等级：

```text
Sea route:
lord_set 四件基础 = 720 FS / 55 SCC / 7 TC
lord_seabond_rod = 430 FS / 15 SCC / 2.5 TC
lord_set 2 件 = 8 SCC
合计 = 1150 FS / 78 SCC / 9.5 TC
带 gatherers_compass 后 TC 约 11.5

Treasure route:
tidevault_set 四件基础 = 780 FS / 26 SCC / 20 TC
tidevault_starhook_rod = 520 FS / 5 SCC / 6 TC
tidevault_set 2 件 = 3 TC
gatherers_compass = 2 TC
合计 = 1300 FS / 31 SCC / 31 TC
```

如果把 100 级 Fishing 的 300 速度加入 raw 值，宝藏线会超过 1500，但公式有效值仍封顶到 1500；装备侧的后续增益预算仍应按上表谨慎分配，尤其避免继续大量堆原始 Treasure Chance。

## 5. 概率体系

### 5.1 Sea Creature Chance

公式：

```text
Sea Creature Chance
= 5%
+ Fishing Level × 0.2%
+ Main-hand Sea Creature Chance
+ Main-hand Active Enchant Bonus
```

最终上限：

```text
95%
```

对应 PDC key：

```text
sea_creature_chance
```

示例：

- 0 级、无装备：5%。
- 45 级、装备 +4%：18%。
- 100 级、无装备：25%。

Sea Creature Chance 是普通随机判定，不使用 Magic Find，也不触发赌徒职业的稀有掉落二次判定。

### 5.2 Treasure Chance

公式：

```text
Treasure Chance
= 3.5%
+ Fishing Level × 0.15%
+ Main-hand Treasure Chance
+ Main-hand Active Enchant Bonus
```

最终上限：

```text
95%
```

对应 PDC key：

```text
treasure_chance
```

Treasure Chance 会进入 `GlobalStatManager.rollRareDrop()`。

全局稀有掉落约定：

- 基础概率低于 5% 时，Magic Find 生效。
- 基础概率达到或超过 5% 时，Magic Find 不生效。
- 赌徒职业只在基础概率低于 5% 时获得同概率二次判定。

这意味着钓鱼等级和装备很容易把 Treasure Chance 推到 5% 以上，此后 Magic Find 不再作用于宝藏触发率。这是当前全局稀有掉落规则的直接结果。

## 6. 海怪系统

配置路径：

```yaml
fishing:
  sea_creatures:
    entries:
```

支持字段：

```yaml
entry_id:
  entity_type: DROWNED
  mythic_mob: ""
  name: "Deep Drowned"
  biome_tags: "OCEAN,RIVER,SWAMP"
  min_fishing_level: 1
  weight: 120
  base_health: 24
  mod: 1.0
  xp: 8
```

筛选规则：

1. 玩家等级必须达到 `min_fishing_level`。
2. 优先选择 `biome_tags` 匹配当前群系名称的条目。
3. 如果没有群系匹配条目，则退回全部满足等级要求的条目。
4. 在合格条目中按 `weight` 加权随机。

生成规则：

- `mythic_mob` 非空时优先通过 MythicMobs 生成。
- MythicMobs 生成失败时退回 `entity_type` 原版实体。
- 添加 `servercore_sea_creature` scoreboard tag。
- 写入 `sea,water,fishing` 生物标签。
- 生成过程由 `MobSpawnManager#spawnFishingSeaCreature()` 接管，跳过通用自然怪物缩放，避免先通用缩放再由 FishingManager 二次覆盖。
- ServerCore 负责生命、攻击、减伤、魔抗、战斗等级、虚拟血池和全息；MythicMobs 只负责实体类型、AI 与技能。
- 原版 custom name 不直接显示，名称统一进入 ServerCore 全息。

当前海怪属性公式：

```text
Max Health = base_health + max(1, Fishing Level) × mod × 4
Attack Damage = 3 + Fishing Level × mod × 0.25
Displayed Power Level = max(1, Fishing Level + min_fishing_level)
```

当前默认配置有 6 个海怪条目。

## 7. 宝藏系统

配置路径：

```yaml
fishing:
  treasures:
    rare:
    epic:
    legendary:
```

档位字段：

```yaml
rare:
  weight: 78
  min_fishing_level: 0
  entries:
```

条目字段：

```yaml
deep_sea_cache:
  custom_item: deep_sea_cache
  fallback_material: PRISMARINE_CRYSTALS
  amount: 1
  weight: 90
  min_fishing_level: 1
  xp: 6
```

选择流程：

1. Treasure Chance 判定成功。
2. 过滤玩家等级可用的档位。
3. 按档位 `weight` 选择 rare、epic 或 legendary。
4. 在该档位中过滤玩家等级可用的条目。
5. 按条目 `weight` 选择具体宝藏。
6. 优先从 `custom_items.yml` 创建 `custom_item`。
7. 自定义物品不存在时使用 `fallback_material`。

当前默认档位权重：

```text
Rare: 78
Epic: 19
Legendary: 3
```

当前默认配置有 25 个宝藏条目。

档位概率是对“当前等级已解锁档位”重新归一化后的概率。例如低等级只解锁 Rare 时，宝藏触发后必定进入 Rare，而不是仍有概率抽到未解锁档位后落空。

## 8. 开放水域判定

ServerCore 只在开放水域判定通过后执行自定义海怪、宝藏和多人事件逻辑。

当前优先调用 Paper/原版 `FishHook#isInOpenWater()`。如果运行时 API 不可用，才回退到旧的浮漂所在高度 `5×5` 区域检查：

```text
水或气泡柱数量 >= 16
且水面上方非实心列数量 >= 10
```

中心方块必须是：

```text
WATER 或 BUBBLE_COLUMN
```

该判定用于限制狭小自动钓鱼池触发自定义收益。它不是 AuraSkills 的方块 XP 防刷事件；钓鱼当前使用 `FishingEnvironmentManager` 统一读取开阔水域上下文。

## 9. AuraSkills 接入

当前职责划分：

- AuraSkills 保存 Fishing 等级。
- AuraSkills 的 `sources/fishing.yml` 负责常规渔获经验。
- ServerCore 通过 `AuraSkillsBridge#getSkillLevel()` 读取等级。
- 海怪和宝藏条目的 `xp` 通过 `SkillsUser#addSkillXp()` 发放额外 Fishing XP。
- ServerCore 在 `HIGHEST` 阶段决定自定义结果，但延迟到自己的 `MONITOR` 监听器才替换或移除原始物品。
- AuraSkills 2.3.12 的 `FishingLeveler` 同样在 `MONITOR` 读取渔获；由于 AuraSkills 是 ServerCore 的硬依赖并先注册监听器，因此它先读取原始渔获并只发放一次常规 XP，之后 ServerCore 再完成自定义结果替换。
- Fishing 等级上限在当前 AuraSkills 配置中为 100。

AuraSkills 自带钓鱼能力当前已关闭：

```text
lucky_catch
fisher
treasure_hunter
grappler
epic_catch
```

AuraSkills 自带 fishing loot pool 的基础概率当前设置为 0，避免与 ServerCore 宝藏重复产出。

当前明确约定：

```text
普通鱼 = AuraSkills 常规 source XP
海怪/宝藏 = AuraSkills 原始渔获 source XP + ServerCore 条目 xp
```

源码顺序已经核对；仍需要真实服务器验收记录确认第三方插件没有重排或取消事件。

## 10. 属性展示与内容接入

物品 lore 已支持显示：

- 钓鱼速度
- 海怪概率
- 宝藏概率
- 附魔提供的对应临时加成
- 钓竿路线
- Fishing Power
- 成长线和成长阶段

`/sc gathering` 钓鱼面板显示：

- AuraSkills Fishing 等级
- 装备 Fishing Speed 总计
- 等级 Fishing Speed
- 有效 Fishing Speed 与 1500 上限
- 最终 Sea Creature Chance
- 最终 Treasure Chance
- 预计咬钩窗口
- 主手、护甲、饰品槽、护符袋各自提供的 Fishing Speed、Sea Creature Chance 和 Treasure Chance

默认钓竿字段：

```yaml
reed_rod:
  material: FISHING_ROD
  req_skill: "fishing:1"
  hand_rule: MAIN_HAND_ONLY
  fishing_rod: true
  fishing_route: MIXED
  fishing_power: 10
  growth_item: true
  growth_line: mixed_rod
  growth_stage: 1
  stats:
    fishing_speed: 35
    sea_creature_chance: 2.0
    treasure_chance: 0.5
```

钓竿路线：

```text
通用线：reed_rod T1 -> ink_rod T2
海怪线：inkbound_tidebinder_rod T3 -> sharktooth_wavebreaker_rod T4 -> riptide_kinghunter_rod T5 -> lord_seabond_rod T6
宝藏线：pearl_depthfinder_rod T3 -> diver_depthfinder_rod T4 -> abyss_treasureseeker_rod T5 -> tidevault_starhook_rod T6
```

第一批钓鱼防具路线：

```text
海怪线：荷叶套 T1 -> 乌贼套 T2 -> 墨灵套 T3 -> 鲨鱼套 T4 -> 激流套 T5 -> 领主套 T6
宝藏线：荷叶套 T1 -> 海绵套 T3 -> 潜水员套 T4 -> 深渊套 T5 -> 潮藏套 T6
```

配置入口：

- `custom_items.yml`：钓鱼材料、40 件防具、10 把钓竿，以及阶段/路线/来源/用途元数据。钓竿字段已写入 PDC，并在 lore 身份区显示。
- `equipment_sets.yml` 与 `passive_abilities.yml`：每套 2 件/4 件 `stat_bonus`，第一批只做路线数值或预留权重，不实现复杂套装技能。
- `recipes.yml`：每个防具部位独立配方，钓竿配方使用上一阶段钓竿作为中心基底；升级配方由 `CustomRecipeManager` 迁移成长状态。
- `gathering_loot.yml`：宝藏触发产出；当前仍映射到 rare/epic/legendary 三个既有档位，通过 `min_fishing_level` 表达 T1-T6 解锁。
- `custom_mobs.yml`：`sea_creature_<entry_id>` 同名 PDC 规则负责海怪击杀材料掉落。

当前材料来源原则：

- `tide_heart`、`abyssal_pearl`、`leviathan_lure_core` 均同时存在宝藏来源和海怪来源。
- `stormfish_scale`、`leviathan_royal_scale` 均保留海怪主来源，并提供低效率宝藏副来源。
- T4 以后装备不直接从宝藏或海怪掉整件成品，而是通过材料与上一阶同部位升级。
- 方尖碑等级暂未接入运行逻辑，仅保留为后续配置扩展方向。

当前附魔解析器支持以下 numeric key：

```text
fishing_speed
sea_creature_chance
treasure_chance
```

但默认 `enchants.yml` 和 `enchant_pools.yml` 目前没有实际配置钓鱼专属附魔。

## 11. 设计约定

后续修改应保持以下约定：

1. 钓鱼等级不直接乘算等待时间，只转换为 Fishing Speed。
2. Fishing Speed 使用递减收益曲线，不使用线性减 tick。
3. 基础等待窗口固定为 `150-300 ticks`。
4. 最低等待窗口固定为 `15-60 ticks`。
5. Sea Creature 的优先级高于 Treasure。
6. 同一次收杆不能同时产出海怪和宝藏。
7. Treasure 分为 rare、epic、legendary，档位和条目都使用权重。
8. 自定义结果必须支持 AuraSkills 额外 XP。
9. 自定义物品优先按内部 ID 创建，找不到时使用原版材料兜底。
10. 概率型装备属性使用直观百分比点：`5 = 5%`，不是 `0.05`。
11. 附魔属性通过 `EnchantStatResolver` 临时计算，不永久写入基础物品 PDC。
12. 运行时配置是 `plugins/ServerCore/` 下的文件，资源模板不会自动合并更新。
13. 钓鱼装备属性聚合主手、护甲、4 个饰品槽、护符袋、临时来源和环境修正，副手不生效。
14. 海怪数值统一由 ServerCore 管理；MythicMobs 只负责实体、AI 和技能。
15. 海怪与宝藏保留一次 AuraSkills 原始渔获 XP，并额外发放条目 `xp`。
16. 钓竿阶段通过 `fishing_power` 预留池子门槛；当前 `FishingManager#hasEnoughFishingPower(Player, FishingPool)` 已提供结构，但默认池子尚未强制检查。
17. 鱼饵是背包消耗型道具；当前按 storage 槽位顺序预约，成功收杆消耗，空杆/取消/换世界/退出/钩子清理时返还。
18. 护符袋中相同内部 `item_id` 只生效一次；同 `talisman_family` 仍按既有优先级启用最高版本。

## 12. 后续任务

### P0：正确性与系统整合（2026-06-18 已实现）

1. **无合格海怪条目不再吞掉渔获**

   海怪概率命中后先选择条目，再尝试生成。只有成功生成海怪后才排队移除原渔获；无合格条目或生成失败会继续进入宝藏判定。

2. **海怪统一进入 MobSpawnManager 专用缩放链路**

   同步生成期间跳过通用 `CreatureSpawnEvent` 缩放，再由专用入口一次性写入属性、减伤、魔抗、PDC 和全息。

3. **海怪已接入虚拟血池**

   专用入口复用 `setMaxHealthAttribute()` 与 `syncVirtualHealth()`。超过实体可承载上限的生命会写入 `KEY_MOB_VIRTUAL_MAX_HEALTH` 和 `KEY_MOB_VIRTUAL_HEALTH`，全息读取虚拟血量。

4. **钓鱼装备属性已统一聚合**

   允许来源为主手、护甲、4 个饰品槽和护符袋。副手不生效。默认 `gatherers_compass` 的：

   ```yaml
   treasure_chance: 2.0
   ```

   现在会进入实际 Treasure Chance 和 `/sc gathering` 面板。

5. **AuraSkills XP 顺序已在代码层明确**

   AuraSkills 2.3.12 的监听器源码确认其常规 Fishing XP 在 `MONITOR` 读取当前渔获。ServerCore 延迟到后注册的 `MONITOR` 监听器再替换或移除渔获，因此常规 XP 只计算一次，条目 `xp` 作为额外奖励。

   真实服务器仍应按第 13 节验收清单记录普通鱼、海怪、三个宝藏档位和 fallback 分支。

### P1：配置化与内容完整度

1. **继续扩展 1500 Fishing Speed 前后的成长预算**

   2026-07-06 已补齐第一批 T1-T6 钓鱼防具与钓竿路线。当前 T6 海怪线装备目标约为 1150 FS，T6 宝藏线装备目标约为 1300 FS；含 100 级 Fishing 时 raw 值可超过 1500，但公式有效值硬封顶。后续如果加入鱼饵、重铸、宝石、饰品或钓鱼专属附魔，需要继续区分装备侧目标、raw 展示值和 effective 公式值，避免常规内容过早溢出。

2. **将硬编码常量迁移到配置**

   建议配置化：

   - 基础等待窗口。
   - 最低等待窗口。
   - 每级 Fishing Speed。
   - 满配目标速度。
   - 海怪基础概率与每级成长。
   - 宝藏基础概率与每级成长。
   - 95% 概率上限。

3. **补充钓鱼专属附魔**

   解析器已经支持三个钓鱼 numeric key，但默认附魔池没有对应内容。需要定义附魔等级、数值预算、适用槽位和获取来源。

4. **明确 MythicMob 属性所有权**

   当前 `mythic_mob` 成功生成后，FishingManager 仍覆盖生命、攻击和名称。需要在以下方案中选定一个：

   - MythicMobs 只负责 AI/技能，ServerCore 负责全部数值。
   - MythicMobs 负责数值，ServerCore 只写标签和奖励。
   - 每条海怪配置增加明确的覆盖开关。

5. **继续扩展海怪和宝藏条件**

   2026-07-07 已为海怪加入 `conditions`，支持开阔水域、群系标签、环境标签、天气和时间，并兼容旧 `biome_tags`。后续仍可考虑支持：

   - 精确群系或 namespaced biome。
   - 世界白名单。
   - 天气、时间、维度。
   - 鱼饵或钓竿标签。
   - 最低开放水域等级。
   - 前置任务或地牢进度。

6. **扩展鱼饵体验层**

   当前已实现背包顺序扫描、预约、成功消耗和失败返还。后续如需更强体验，可继续增加鱼饵袋、选择 GUI、debug/simulate 命令、事件鱼饵和更细的 catch type 限制。

### P2：可观测性与体验

1. 为 FishingManager 增加单元测试，至少覆盖等待公式、概率上限、档位权重和空合格表。
2. `/sc admin fishing debug` 已能显示当前浮漂环境、环境加成和最终概率；后续可继续补当前可抽取海怪/宝藏候选列表。
3. 将英文 ActionBar 文本迁移到可配置中文文本。
4. 增加海怪、宝藏与多人事件触发日志的可选 debug 模式。
5. 在 `/sc gathering` 中进一步拆分每件物品的基础 PDC 与附魔贡献；当前已按主手、护甲、饰品槽、护符袋拆分。
6. 当前优先使用 `FishHook#isInOpenWater()`；后续如 AuraSkills 规则有差异，需要决定是否完全对齐 AuraSkills。

## 13. 建议验收清单

每次修改钓鱼体系后至少验证：

- 0、45、100 级的无装备等待窗口。
- 100 级、有效速度 1500 时窗口为 `15-60 ticks`。
- 100 级、raw 速度超过 1500 时窗口仍为 `15-60 ticks`，不会继续缩短。
- `lord_set + lord_seabond_rod + gatherers_compass` 的装备目标接近 `1150 FS / 78 SCC / 11.5 TC`。
- `tidevault_set + tidevault_starhook_rod + gatherers_compass` 的装备目标接近 `1300 FS / 31 SCC / 31 TC`。
- 第一批钓竿物品 lore 显示路线、Fishing Power 和成长阶段。
- 背包中有鱼饵时，抛竿会预约第一个可用鱼饵，并在成功钓起任意结果后消耗 1 个。
- 空杆、取消、换世界、退出或钩子清理时，已预约鱼饵会返还，背包满时掉落在玩家位置。
- 鱼饵和普通鱼食物 Buff 提供的 Fishing Speed 会参与等待窗口计算，但 effective Fishing Speed 仍硬封顶为 1500。
- `starhook_bait` 与 `starfall_koi` 同时存在时，临时 Treasure Chance 仍不超过 `+1.0` 百分点。
- 宝藏失败后会进入普通鱼池，并按等级、天气、鱼饵门槛和权重修正生成自定义普通鱼。
- 普通鱼右键食用会立即回血、消耗 1 个物品并进入鱼类食物短冷却。
- 同组普通鱼食物 Buff 不叠加，保留更强者；不同组可以共存。
- 非开放水域不会触发 ServerCore 海怪或宝藏。
- 海怪成功后不再产出宝藏。
- 海怪失败后宝藏仍可触发。
- 未解锁档位不会被选中。
- 自定义物品缺失时 fallback material 正常。
- 海怪和宝藏配置 XP 正确进入 AuraSkills Fishing。
- Magic Find 只在 Treasure Chance 低于 5% 时生效。
- Sea Creature Chance 不受 Magic Find 影响。
- 主手附魔 numeric 能影响三个钓鱼属性。
- 等级不足时无法切换到带 `req_skill` 的钓竿。
- 生命超过 2000 的海怪仍按配置血量工作。
- 重载 `gathering_loot.yml` 后不需要重启服务器。
- `/sc admin fishing debug` 能在玩家有活动浮漂时输出群系、开阔水域、天气、环境标签、环境 FS/SCC/TC 加成和命中规则。
- 雨天开阔水域命中 `rain_open_water`，雷暴开阔水域额外命中 `thunderstorm`。
- 深海开阔水域命中 `ocean_current` 和 `deep_ocean_depth`，并产生 `OCEAN_CURRENT` / `DEEP_SEA` 标签。
- 非开阔水域不会触发 ServerCore 海怪、宝藏或多人事件。
- 背包有 `ocean_current_core` 时，雨天开阔海洋海怪 roll 成功后有概率启动 `sea_monster_raid`，启动后消耗 1 个催化剂。
- 背包有 `sunken_compass` 时，深海宝藏 roll 成功后有概率启动 `secret_treasure_hunt`，启动后消耗 1 个催化剂。
- 多人事件未通过概率、冷却、环境或活跃数量限制时，不消耗催化剂，并回到普通海怪/宝藏产出。
- 多人事件怪物带有 `fishing_event_id` 与 `fishing_event_instance` PDC，击杀和伤害能计入贡献。
- 玩家在事件范围内继续钓鱼，成功 `CAUGHT_FISH` 会为当前事件增加贡献/进度。
- 插件 disable 时会清理活动钓鱼事件和事件怪。

## 14. 环境修正与多人事件测试版

### 14.1 环境修正

`FishingEnvironmentManager` 读取 `fishing_environment.yml`，负责把本次浮漂位置解析为钓鱼上下文。上下文包括：

- `FishHook#isInOpenWater()` 的原版/Paper 开阔水域判定；API 不可用时 fallback 到旧 5x5 水域检查。
- 当前群系和由配置归并出的群系标签，例如 `OCEAN`、`DEEP_OCEAN`、`WARM`、`COLD`、`SWAMP`。
- 天气、雷暴、时间、是否受雨水影响、是否受天空影响。
- 环境规则累积出的环境标签，例如 `RAIN_BONUS`、`STORM`、`OCEAN_CURRENT`、`DEEP_SEA`、`REEF`。

默认环境规则只提供本次钓鱼计算用的临时加成，不写入玩家 PDC 或物品 PDC。`/sc gathering` 的钓鱼来源现在包含“环境修正”，但这个面板只显示玩家当前位置的常规快照；精确浮漂上下文请用 `/sc admin fishing debug`。

### 14.2 海怪条件

`gathering_loot.yml` 的海怪条目现在支持：

```yaml
conditions:
  require_open_water: true
  biome_tags: [DEEP_OCEAN]
  environment_tags: [DEEP_SEA]
  weather: [RAIN, THUNDER]
  time: [NIGHT]
```

旧字段 `biome_tags: "OCEAN,RIVER"` 仍会被转换为条件，已有海怪表不需要立刻重写。只有显式写了 `conditions` 的条目会严格按新条件过滤；旧式条目保留过去“没有群系命中时按等级 fallback”的兼容行为。

### 14.3 多人事件

`FishingEventManager` 读取 `fishing_events.yml`。事件只会在基础钓鱼 roll 已经成功后尝试启动：

- `SEA_CREATURE_ROLL`：海怪概率命中且选中了一个合格海怪后触发。
- `TREASURE_ROLL`：宝藏概率命中且选中了一个合格宝藏后触发。

启动顺序为：匹配事件条件 -> 检查活跃数量和冷却 -> 检查玩家背包催化剂 -> 事件概率 roll -> 消耗催化剂 -> 创建事件实例。概率未命中或条件不满足时不会消耗催化剂。

默认事件：

- `sea_monster_raid`：雨天/雷暴开阔海洋，需要 `OCEAN_CURRENT` 环境标签与 `ocean_current_core`，持续 8 分钟，分波刷出海怪。
- `secret_treasure_hunt`：深海开阔水域，需要 `DEEP_SEA` 环境标签与 `sunken_compass`，持续 6 分钟，目标进度 1000，通过继续钓鱼、伤害和击杀推进。

事件贡献来源：

- 对事件怪造成伤害。
- 击杀事件怪。
- 在事件范围内成功钓起渔获。

事件怪通过 `MobSpawnManager#spawnFishingSeaCreature()` 创建，继续复用现有海怪数值、虚拟血池和全息面板链路，并额外写入 `fishing_event_id`、`fishing_event_instance`、`fishing_event_contribution_weight`。

### 14.4 测试服注意事项

- 新增默认资源只会在运行时文件不存在时复制；已有测试服需要手动合并 `fishing_environment.yml`、`fishing_events.yml` 和 `custom_items.yml` 中的 `ocean_current_core` / `sunken_compass`。
- `/sc admin fishing reload` 会重载钓鱼 loot、鱼饵/普通鱼、环境规则和事件规则。
- `/sc admin items give ocean_current_core` 与 `/sc admin items give sunken_compass` 可发放催化剂。
- 当前多人事件为测试版：事件状态未持久化，插件重启会清理活动事件；奖励和平衡值应按测试服反馈继续调。
