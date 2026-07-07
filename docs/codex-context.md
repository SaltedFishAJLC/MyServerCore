# Codex 项目交接摘要

更新时间：2026-07-07

本文给新的 Agent 快速接上 `E:\ServerPlugin` 使用。长期规则、代码风格和验证命令见仓库根目录 `AGENTS.md`。

## 1. 当前插件架构

`ServerCore` 是 Paper 1.21 / Java 21 插件，入口是 `com.servercore.ServerCorePlugin`。主命令是 `/servercore`，别名 `/sc`、`/score`、`/sce`。硬依赖为 Vault、AuraSkills、MythicLib、ProtocolLib，软依赖为 PlaceholderAPI、LuckPerms、CoreProtect、GrimAC。

启动时 `ServerCorePlugin#onEnable()` 负责组装各系统：

- 基础层：`DatabaseManager`、`EconomyManager`、Vault 服务注册、SQLite 经济。
- 属性与展示：`PDCManager`、`AttributeManager`、`ActionBarManager`、`ScoreboardManager`、`PlayerStatCache`。
- 战斗层：`CombatManager`、`DamageService`、`VanillaDamageAdapter`、`StatusService`、`FrostService`、`StunController`、`ResistanceResolver`、`CreatureTagService`。
- 装备层：`CustomItemRegistry`、`ItemFormatManager`、`ItemStandardizer`、`WeaponTemplateManager`、`RangedWeaponManager`、`ShieldManager`、`ReforgeManager`、`GemstoneManager`。
- 附魔层：`EnchantRegistry`、`EnchantManager`、`EnchantStatResolver`、`EnchantEffectService`、`EquipmentEnchantService`、`EnchantTableListener`、`EnchantAnvilListener`、`EnchantGrindstoneListener`、`RangedEmpowermentManager`。
- 被动与套装：`PassiveAbilityRegistry`、`EquipmentSetRegistry`、`AbilityCooldownService`、`PassiveSnapshotService`。
- 职业：`ClassManager`、`ClassPassiveManager`。
- 怪物生态：`PowerLevelManager`、`CustomMobRegistry`、`MobSpawnManager`、`MobReplacementManager`、`UniqueMobSpawnManager`、`HologramManager`。
- 生活技能：`CollectionSkillManager`、`MiningManager`、`GlobalStatManager`。
- 钓鱼拆分层：`FishingManager` 主流程，`FishingContentManager` 鱼饵/普通鱼，`FishingEnvironmentManager` 环境修正，`FishingEventManager` 多人事件，`com.servercore.fishing.*` 为上下文 record。
- 玩家后勤：`ItemDurabilityManager`、`PlayerRecoveryManager`。
- 经济与保护：`RecycleManager`、`SoulContainerManager`、`StashManager`、`DeathListener`。
- 内容工具：`CustomRecipeManager`、`VanillaItemOverrideManager`、`AuraSkillsMenuHijacker`、`NonCombatStatsMenu`。

运行时配置以 `plugins/ServerCore/*.yml` 为准；`src/main/resources/*.yml` 只是默认模板。新增默认资源不会自动合并进已有测试服配置。

## 2. 已完成内容

主要已完成系统：

- 战斗数值主链路：攻击者侧由 `CombatManager` 计算，目标侧由 `DamageService` 统一结算；原版护甲已迁移到 ServerCore 护甲公式。
- DamagePlan 两阶段提交：护盾、Perfect Guard、Arcane Buffer、Shade Step、灾厄血池、进攻附魔状态等副作用在事件未取消后提交。
- 生态战力与怪物缩放：`PowerLevelManager` 计算玩家生态强度，`MobSpawnManager` 根据附近玩家和配置写入怪物等级、生命、攻击、减伤、虚拟血池与全息。
- 自定义物品、武器模板、手持规则、宝石、词缀、配方、回收和管理员物品导出流程已接入。
- 装备套装、印记、护符包和统一被动系统已落地，T1-T4/T3 套装与 T6 战斗测试套装已能进入 `PassiveSnapshotService`。
- 自定义附魔框架已配置驱动：普通附魔台、定向附魔台、轮换附魔书、铁砧、砂轮单拆、管理员附魔/拆除入口已接入；大量复杂附魔仍是简化或仅配置状态。
- 钓鱼主流程已拆分并扩展：等待公式、T1-T6 钓竿/防具路线、鱼饵、普通鱼、普通鱼食用 Buff、环境修正、开阔水域限制、海怪条件和多人事件测试版已进入默认模板。
- 玩家后勤系统已改为 RPG 化：耐久强制不可损耗、饱食锁定、关闭原版自然回血、脱战回血、基础食物补给、普通鱼食用。
- 生活采集已有 Bounty、Overbloom、Mining Fortune、Mining Spread、Mining Speed、Excavation、Fishing treasure/sea creature 等主流程。
- `/sc` 命令文档、附魔状态、装备状态、钓鱼状态、玩家后勤状态、数值状态等文档已经存在。

最近一轮状态：

- `Walkthrough.md` 最新条目是 `2026-07-07 钓鱼环境修正与多人事件测试版`。
- 当时 `.\gradle-8.5\bin\gradle.bat build` 成功，仅有既有 `EntityRemoveEvent` 与铁砧 repair cost API 过时警告。
- 当前工作树已有未提交改动，包含新的钓鱼管理器、`com.servercore.fishing` 包、`fish_items.yml`、`fishing_baits.yml`、`fishing_environment.yml`、`fishing_events.yml` 以及相关文档更新。后续 Agent 不要把这些当成本轮新改动随意回滚。

## 3. 关键设计决策

- 运行时配置优先：检查真实服务器问题时要看 `plugins/ServerCore/*.yml`，不能只看 `src/main/resources`。
- 文档默认中文，功能性变更要更新对应状态文档，并在 `Walkthrough.md` 末尾追加记录。
- PDC 是兼容契约。新增字段集中走 `PDCManager`，旧 key 不做无计划迁移。
- `ItemFormatManager` 是展示统一出口；新增属性、身份、钓鱼字段、附魔数值应同步 lore 渲染。
- 战斗不要绕开 `DamageService`。环境伤害、DOT、状态伤害和自定义伤害都要明确 `DamageCategory`、`DamageTag` 与无敌帧行为。
- 附魔的存储和定义分离：`EnchantManager` 管物品 PDC 写入，`EnchantRegistry` 管配置定义，`EnchantStatResolver` 管数值临时汇总。
- 管理员 UI 需求要接入对应 GUI 或 `/sc admin` 表面，不要只藏在内部 helper。
- 印记规则保持克制：只启用 PASSIVE 和套装身份，不复制高面板。
- 钓鱼自定义收益要求开阔水域；海怪、宝藏、普通鱼互斥顺序固定；多人事件只在基础 roll 成功后尝试启动。
- 鱼饵预约失败要返还，成功收杆才消耗；普通鱼食用不写入 `survival.foods`。
- WIL 的后勤含义是缩短脱战等待，不是直接提高回血速度。
- Magic Find / 赌徒只影响低于 5% 的稀有触发概率；钓鱼 Treasure Chance 到 5% 以上后不再吃 Magic Find。

## 4. 常用命令

仓库验证：

```powershell
git status --short
.\gradle-8.5\bin\gradle.bat --no-daemon compileJava
.\gradle-8.5\bin\gradle.bat --no-daemon build
```

玩家常用：

```text
/sc stats
/sc gathering
/sc acc
/sc class
/sc stash
/sc recycle
/sc recipe [物品ID或手持物品]
```

管理员常用：

```text
/sc admin items reload
/sc admin items list
/sc admin items give <物品ID> [数量]
/sc admin mobs reload
/sc admin mobs summon <规则ID> [数量]
/sc admin recipe create <配方ID>
/sc admin recipe reload
/sc admin gatheringloot reload
/sc admin fishing reload
/sc admin fishing debug
/sc admin bait list
/sc admin bait give <id> [amount]
/sc admin fish list
/sc admin fish give <id> [amount]
/sc enchant list
/sc enchant give [player] <enchant_id> <level>
/sc enchant remove [player] <enchant_id>
/sc enchant clear [player]
/sc enchant debug
```

内容调试：

```text
/sc item <属性> <数值>
/sc item acctype <necklace|bracelet|ring|belt>
/sc item template <武器模板>
/sc item handrule <手持规则>
/sc debug set
/sc debug passive
```

完整命令面以 `docs/servercore-command-reference.md` 和实际 `ServerCorePlugin` 分支为准；根 help 文本不完整。

## 5. 待办事项

全局：

- 当前没有 `src/test` 自动化测试。核心公式、附魔边界、钓鱼概率、配置引用和事件顺序都主要靠 build 与测试服验证。
- `CURRENT_GAMEPLAY_SYSTEMS.md` 有些描述落后于最新状态文档，尤其装备、钓鱼和后勤部分。
- 运行时配置与默认模板可能漂移；涉及内容问题时要核对 `plugins/ServerCore/*.yml`。

钓鱼：

- 测试服验证开阔水域 API、雨天/深海环境命中、催化剂消耗、多人事件进度、贡献奖励和插件 disable 清理。
- 将基础等待窗口、最低窗口、每级 Fishing Speed、1500 上限、基础 SCC/TC、概率上限等硬编码常量配置化。
- 钓鱼专属附魔尚未进入默认附魔池。
- `FishingPower` 池子门槛结构已预留，默认池子尚未强制检查。
- MythicMobs 与 ServerCore 对海怪数值的所有权需要最终确认。

附魔：

- 命令入口和 GUI 软上限规则尚未完全统一。
- `MAGIC_WEAPON`、`ACCESSORY` 槽位匹配未完整落地。
- 远程弹道状态机仍缺：Skewer、Zeroing、Horizon Sniper、Fatal Tempo、Judgement String 等多为配置或简化。
- 复杂附魔的多人顺序、死亡容器、移动手感和客户端轮廓仍需测试服验证。

装备与内容：

- GUI 原子交换、死亡保留、重登冷却、多人战斗切换还缺自动化，只能测试服验证。
- T5、T7、正式护符和更多掉落/制作来源仍未制作完整。
- 多文件重载未实现跨 `custom_items.yml`、`passive_abilities.yml`、`equipment_sets.yml` 的单事务提交。

怪物与生态：

- `custom_mobs.yml` 中历史 WDA 表仍可能有 `NEED_FIX_`、空数值或待校准条目。
- 结构唯一精英和 marker 模式需要在真实 WDA 结构中继续验证。

## 6. 重要文件路径

入口与构建：

- `build.gradle.kts`
- `settings.gradle.kts`
- `src/main/resources/plugin.yml`
- `src/main/java/com/servercore/ServerCorePlugin.java`

核心源码：

- `src/main/java/com/servercore/combat/damage/DamageService.java`
- `src/main/java/com/servercore/combat/damage/VanillaDamageAdapter.java`
- `src/main/java/com/servercore/manager/CombatManager.java`
- `src/main/java/com/servercore/manager/CombatStats.java`
- `src/main/java/com/servercore/manager/PowerLevelManager.java`
- `src/main/java/com/servercore/manager/MobSpawnManager.java`
- `src/main/java/com/servercore/manager/PDCManager.java`
- `src/main/java/com/servercore/manager/CustomItemRegistry.java`
- `src/main/java/com/servercore/manager/ItemFormatManager.java`
- `src/main/java/com/servercore/manager/FishingManager.java`
- `src/main/java/com/servercore/manager/FishingContentManager.java`
- `src/main/java/com/servercore/manager/FishingEnvironmentManager.java`
- `src/main/java/com/servercore/manager/FishingEventManager.java`
- `src/main/java/com/servercore/manager/PlayerRecoveryManager.java`
- `src/main/java/com/servercore/enchant/EnchantRegistry.java`
- `src/main/java/com/servercore/manager/EnchantManager.java`
- `src/main/java/com/servercore/passive/PassiveSnapshotService.java`

默认配置：

- `src/main/resources/custom_items.yml`
- `src/main/resources/custom_mobs.yml`
- `src/main/resources/gathering_loot.yml`
- `src/main/resources/fishing_baits.yml`
- `src/main/resources/fish_items.yml`
- `src/main/resources/fishing_environment.yml`
- `src/main/resources/fishing_events.yml`
- `src/main/resources/equipment_sets.yml`
- `src/main/resources/passive_abilities.yml`
- `src/main/resources/enchants.yml`
- `src/main/resources/enchant_pools.yml`
- `src/main/resources/recipes.yml`
- `src/main/resources/config.yml`

状态与交接文档：

- `AGENTS.md`
- `docs/codex-context.md`
- `Walkthrough.md`
- `CURRENT_GAMEPLAY_SYSTEMS.md`
- `CUSTOMIZATION_GUIDE.md`
- `docs/numerical-systems-status.md`
- `docs/enchant-system-status.md`
- `docs/equipment-system-status.md`
- `docs/fishing-system-status.md`
- `docs/player-logistics-system-status.md`
- `docs/servercore-command-reference.md`
- `docs/equipment-set-test-commands.md`

## 7. 建议后续 Agent 使用的技能

- `diagnose`：用户报告 bug、回归、构建失败、事件顺序异常时使用。
- `tdd`：如果要补公式、配置解析、附魔边界或钓鱼概率测试，优先用。
- `grill-me`：设计印记、被动、套装、复杂钓鱼事件或附魔状态机前使用。
- `improve-codebase-architecture`：当用户要求系统性重构或找架构机会时使用，先读本文件和相关状态文档。
- `handoff`：继续沉淀跨会话交接时使用，但本仓库内的长期交接建议仍写入 `AGENTS.md` 与 `docs/codex-context.md`。

