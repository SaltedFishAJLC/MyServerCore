# 钓鱼体系实现状态与后续任务

更新时间：2026-06-18

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

- 默认内容无法达到设计目标中的满配 `10-80 ticks`。
- 钓鱼属性当前聚合主手、护甲、4 个饰品槽和护符袋；副手不生效。
- 海怪已经通过 MobSpawnManager 专用入口统一接入减伤、虚拟血池和全息链路。
- 缺少钓鱼系统自动化测试。

## 2. 代码与配置入口

主要实现：

- `src/main/java/com/servercore/manager/FishingManager.java`
- `src/main/java/com/servercore/manager/AuraSkillsBridge.java`
- `src/main/java/com/servercore/manager/GlobalStatManager.java`
- `src/main/java/com/servercore/manager/NonCombatStatsMenu.java`
- `src/main/java/com/servercore/manager/ItemFormatManager.java`
- `src/main/java/com/servercore/manager/PDCManager.java`

内容配置：

- `src/main/resources/gathering_loot.yml`
- `src/main/resources/custom_items.yml`
- `src/main/resources/enchants.yml`

服务器运行时实际读取：

- `plugins/ServerCore/gathering_loot.yml`
- `plugins/ServerCore/custom_items.yml`
- `plugins/ServerCore/enchants.yml`

资源目录中的 YAML 只会在运行时文件不存在时复制。更新 jar 不会自动把新条目合并进已有运行时配置。

重载命令：

```text
/sc admin gatheringloot reload
/sc admin loot reload
/sc admin items reload
```

查看面板：

```text
/sc gathering
```

## 3. 一次钓鱼的实际流程

FishingManager 监听 `PlayerFishEvent`，当前流程如下：

1. 状态为 `FISHING`：
   - 读取 AuraSkills Fishing 等级。
   - 读取主手物品及其 active 附魔提供的 Fishing Speed。
   - 计算等待窗口。
   - 写入 `FishHook#setMinWaitTime` 和 `setMaxWaitTime`。

2. 状态为 `CAUGHT_FISH`：
   - 检查是否满足 ServerCore 的开放水域要求。
   - 不满足时不执行自定义海怪或宝藏逻辑，保留原版结果。
   - 满足时先判定 Sea Creature。
   - 海怪概率命中后先选择合格条目并尝试生成；没有合格条目或生成失败时继续判定宝藏。
   - 海怪成功：清零原版经验、生成海怪，本次不再判定宝藏。
   - 海怪失败：判定 Treasure Chance。
   - 宝藏成功：按等级筛选档位，再按权重选择档位和条目。
   - AuraSkills 在 `MONITOR` 阶段读取原始渔获并发放常规 Fishing XP。
   - ServerCore 随后在同一 `MONITOR` 阶段移除海怪分支的原渔获，或把宝藏分支的原渔获替换成自定义宝藏。
   - 两者都失败：保留原版渔获。

优先级约定：

```text
Sea Creature > Treasure > Vanilla Catch
```

海怪和宝藏不能在同一次收杆中同时出现。

## 4. Fishing Speed 体系

### 4.1 属性来源

当前有效速度由等级和装备聚合组成：

```text
Effective Fishing Speed
= Level Fishing Speed
+ Main-hand Fishing Speed
+ Armor Fishing Speed
+ Accessory Slot Fishing Speed
+ Talisman Bag Fishing Speed
```

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
- 满配有效 Fishing Speed 达到 1400 时：`10-80 ticks`。
- 最低边界固定为 `10-80 ticks`，不会继续缩短。

有效速度记作 `S`。

最短等待：

```text
MinWait = max(10, round(150 × 100 / (100 + S)))
```

最长等待：

```text
MaxScale = 80 × 1400 / (300 - 80)
         ≈ 509.0909

MaxWait = max(80, round(300 × MaxScale / (MaxScale + S)))
```

实际示例：

| 有效 Fishing Speed | 等待窗口 |
|---:|---:|
| 0 | 150-300 ticks |
| 135 | 64-237 ticks |
| 180 | 54-222 ticks |
| 300 | 38-189 ticks |
| 315 | 36-185 ticks |
| 480 | 26-154 ticks |
| 800 | 17-117 ticks |
| 1100 | 12-95 ticks |
| 1400 | 10-80 ticks |

### 4.3 当前平衡缺口

默认终局钓竿 `tidecaller_rod` 提供：

```yaml
fishing_speed: 180
```

100 级玩家使用该钓竿时：

```text
Effective Fishing Speed = 300 + 180 = 480
等待窗口约为 26-154 ticks
```

要达到 `10-80 ticks`，100 级玩家仍需要约 1100 点装备/附魔 Fishing Speed。当前默认物品和附魔内容没有提供足够数值，因此“满配 10-80 ticks”目前只是公式目标，不是默认内容可达目标。

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

当前默认配置有 10 个宝藏条目。

档位概率是对“当前等级已解锁档位”重新归一化后的概率。例如低等级只解锁 Rare 时，宝藏触发后必定进入 Rare，而不是仍有概率抽到未解锁档位后落空。

## 8. 开放水域判定

ServerCore 只在自定义开放水域判定通过后执行海怪和宝藏逻辑。

当前检查浮漂所在高度的 `5×5` 区域：

```text
水或气泡柱数量 >= 16
且水面上方非实心列数量 >= 10
```

中心方块必须是：

```text
WATER 或 BUBBLE_COLUMN
```

该判定用于限制狭小自动钓鱼池触发自定义收益。它不是 AuraSkills 的方块 XP 防刷事件；钓鱼当前使用独立开放水域检查。

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

`/sc gathering` 钓鱼面板显示：

- AuraSkills Fishing 等级
- 装备 Fishing Speed 总计
- 等级 Fishing Speed
- 最终 Sea Creature Chance
- 最终 Treasure Chance
- 预计咬钩窗口
- 主手、护甲、饰品槽、护符袋各自提供的 Fishing Speed、Sea Creature Chance 和 Treasure Chance

默认终局钓竿：

```yaml
tidecaller_rod:
  material: FISHING_ROD
  req_skill: "fishing:45"
  hand_rule: MAIN_HAND_ONLY
  stats:
    fishing_speed: 180
    sea_creature_chance: 4.0
    treasure_chance: 5.0
    attr_luck: 8
```

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
4. 最低等待窗口固定为 `10-80 ticks`。
5. Sea Creature 的优先级高于 Treasure。
6. 同一次收杆不能同时产出海怪和宝藏。
7. Treasure 分为 rare、epic、legendary，档位和条目都使用权重。
8. 自定义结果必须支持 AuraSkills 额外 XP。
9. 自定义物品优先按内部 ID 创建，找不到时使用原版材料兜底。
10. 概率型装备属性使用直观百分比点：`5 = 5%`，不是 `0.05`。
11. 附魔属性通过 `EnchantStatResolver` 临时计算，不永久写入基础物品 PDC。
12. 运行时配置是 `plugins/ServerCore/` 下的文件，资源模板不会自动合并更新。
13. 钓鱼装备属性聚合主手、护甲、4 个饰品槽和护符袋，副手不生效。
14. 海怪数值统一由 ServerCore 管理；MythicMobs 只负责实体、AI 和技能。
15. 海怪与宝藏保留一次 AuraSkills 原始渔获 XP，并额外发放条目 `xp`。

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

1. **补齐达到 1400 Fishing Speed 的装备成长线**

   当前默认内容只能提供少量 Fishing Speed。需要增加护甲、饰品、重铸、宝石或附魔来源，并确定满配预算。

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

5. **扩展海怪和宝藏条件**

   可考虑支持：

   - 精确群系或 namespaced biome。
   - 世界白名单。
   - 天气、时间、维度。
   - 鱼饵或钓竿标签。
   - 最低开放水域等级。
   - 前置任务或地牢进度。

### P2：可观测性与体验

1. 为 FishingManager 增加单元测试，至少覆盖等待公式、概率上限、档位权重和空合格表。
2. 增加管理员调试命令，显示玩家最终速度、概率、属性来源和当前可抽取条目。
3. 将英文 ActionBar 文本迁移到可配置中文文本。
4. 增加海怪与宝藏触发日志的可选 debug 模式。
5. 在 `/sc gathering` 中进一步拆分每件物品的基础 PDC 与附魔贡献；当前已按主手、护甲、饰品槽、护符袋拆分。
6. 明确开放水域判定是否需要完全对齐原版或 AuraSkills 规则。

## 13. 建议验收清单

每次修改钓鱼体系后至少验证：

- 0、45、100 级的无装备等待窗口。
- 100 级、有效速度 1400 时窗口为 `10-80 ticks`。
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
