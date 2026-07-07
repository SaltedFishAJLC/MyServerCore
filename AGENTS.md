# ServerCore Agent Guide

本文件给后续 Agent 使用，记录本仓库的长期协作规则、代码风格、验证命令和项目约定。更偏历史背景、近期状态和 TODO 的交接摘要在 `docs/codex-context.md`。

## 项目定位

`ServerCore` 是 Paper 1.21 / Java 21 的 RPG 核心插件。它把 AuraSkills 等级、职业、装备、饰品、附魔、采集、钓鱼、怪物缩放、经济、死亡保护和 UI 串成一套 HSB-like 服务器玩法层。

入口与构建：

- 主类：`src/main/java/com/servercore/ServerCorePlugin.java`
- 插件描述：`src/main/resources/plugin.yml`
- 构建脚本：`build.gradle.kts`
- Gradle 运行时：`gradle-8.5/bin/gradle.bat`
- Java：21
- Paper API：1.21
- 硬依赖：Vault、AuraSkills、MythicLib、ProtocolLib
- 软依赖：PlaceholderAPI、LuckPerms、CoreProtect、GrimAC

运行时配置约定：

- `src/main/resources/*.yml` 是默认模板，只会在运行时文件不存在时复制。
- 真实服务器配置以 `plugins/ServerCore/*.yml` 为准。
- 更新默认模板后，交接或测试说明里要提醒手动合并已有运行时配置。

## 工作方式

- 默认用中文写项目文档、状态文档和交接内容。
- 如果用户指向 `implementation_plan.md`、`docs/*-system-status.md` 或 `Walkthrough.md`，先读这些文件再改代码。
- 工作树可能长期有未提交改动。不要回滚、整理或覆盖与当前任务无关的改动。
- 功能改动通常要同步更新对应 `docs/*-system-status.md`，并在 `Walkthrough.md` 末尾追加日期记录。
- `Walkthrough.md` 是追加式历史，不要重排旧内容。
- PowerShell 读取中文文件时优先显式使用 UTF-8，例如 `Get-Content -Encoding UTF8`。

## 架构边界

启动装配集中在 `ServerCorePlugin#onEnable()`。现有模式是“入口只组装，领域逻辑放 manager/service/registry”。新增功能应优先复用既有 manager，而不是并行造一套系统。

关键边界：

- 战斗攻击者侧：`CombatManager`
- 战斗目标侧统一结算：`combat/damage/DamageService`
- 原版伤害转内部伤害：`combat/damage/VanillaDamageAdapter`
- 状态与控制：`combat/status/StatusService`、`FrostService`、`StunController`
- 生物标签和抗性：`combat/creature/*`、`combat/resistance/*`
- 玩家属性与职业：`AttributeManager`、`ClassManager`、`ClassPassiveManager`
- 战力与刷怪：`PowerLevelManager`、`MobSpawnManager`、`CustomMobRegistry`
- 物品 PDC：`PDCManager`
- 自定义物品：`CustomItemRegistry`
- 物品展示：`ItemFormatManager`
- 原版物品迁移：`ItemStandardizer`、`VanillaItemOverrideManager`
- 武器模板和手持规则：`WeaponTemplateManager`
- 自定义附魔存储：`EnchantManager`
- 附魔配置与定义：`enchant/EnchantRegistry`
- 附魔数值解析：`enchant/EnchantStatResolver`
- 附魔战斗效果：`enchant/EnchantEffectService`、`EquipmentEnchantService`
- 套装、印记、统一被动：`passive/*`
- 生活采集：`CollectionSkillManager`、`MiningManager`、`GlobalStatManager`
- 钓鱼主流程：`FishingManager`
- 鱼饵、普通鱼、钓鱼食物 Buff：`FishingContentManager`
- 钓鱼环境修正：`FishingEnvironmentManager`
- 钓鱼多人事件：`FishingEventManager`
- 玩家后勤：`PlayerRecoveryManager`、`ItemDurabilityManager`
- 经济、死亡保护和暂存：`DatabaseManager`、`EconomyManager`、`DeathListener`、`SoulContainerManager`、`StashManager`

## 稳定设计约定

- 不要随意重命名或重写 PDC key。已有物品依赖这些 key 做跨版本兼容。
- `PDCManager` 是 key 的集中入口；新增长期数据先在这里定义。
- `ItemFormatManager` 是 lore 和面板展示中心。不要让各系统各自拼完整 lore。
- `CombatManager` 负责攻击者侧公式，`DamageService` 负责目标侧结算。不要绕过 `DamageService` 另写一条受击链。
- 有副作用的战斗状态使用计划/提交模型，尽量在 `MONITOR` 确认事件未取消后再消耗冷却、魔力、护盾、血池或进攻状态。
- 原版护甲和武器面板会被迁移到 ServerCore PDC，避免原版结算和插件结算重复叠加。
- 自定义护甲减伤公式当前是 `min(95%, armor / (armor + 100))`。
- 自定义附魔 PDC 存储由 `EnchantManager` 维护，格式是 `id:level;id:level`。`EnchantRegistry` 负责配置定义，不负责直接改物品。
- `protection` 可见名历史上兼容 legacy stored id `fortify`，不要无计划迁移旧物品。
- 印记只携带 PASSIVE 与套装身份，不复制基础面板、附魔、宝石、武器模板等正常装备属性。
- Fishing Speed 使用 raw/effective 两层语义，公式入口 effective 封顶 `1500`，最低窗口 `15-60 ticks`。
- 钓鱼一次收杆优先级是 `Sea Creature > Treasure > Normal Fish`，海怪和宝藏互斥。
- 钓鱼自定义海怪、宝藏和多人事件默认要求开阔水域。
- 钓鱼海怪数值由 `MobSpawnManager#spawnFishingSeaCreature()` 统一写入，避免 `FishingManager` 和通用自然缩放双重覆盖。
- AuraSkills 仍负责常规 Fishing XP；ServerCore 在后续 `MONITOR` 替换/移除渔获，并额外发放条目 XP。
- 玩家后勤中 WIL 只缩短脱战等待，不直接提高回血速度。

## 代码风格

- Java 21 可使用 record、switch expression 和局部不可变变量；保持现有包内风格。
- 新增 YAML 配置解析时要兼容常见别名字段，失败时给 logger warning，不要让单条内容拖垮全插件。
- 新增命令分支时同步更新 `docs/servercore-command-reference.md`。
- 管理员功能优先接到已有 `/sc admin ...` 或相关 GUI，不要只留下隐藏命令。
- 新增内容配置要补齐引用链：物品、套装、被动、配方、掉落、怪物规则之间不要留下悬空 ID。
- 复杂机制先落到 manager/service，`ServerCorePlugin` 只做依赖注入、命令分发和生命周期 stop。
- 注释要解释设计原因或边界，避免重复代码表面含义。

## 常用验证命令

编译：

```powershell
.\gradle-8.5\bin\gradle.bat --no-daemon compileJava
```

完整构建：

```powershell
.\gradle-8.5\bin\gradle.bat --no-daemon build
```

查看工作树：

```powershell
git status --short
```

快速找入口：

```powershell
rg -n "new .*Manager|admin fishing|gatheringloot|enchant give" src/main/java/com/servercore/ServerCorePlugin.java
```

查命令文档：

```powershell
Get-Content -Encoding UTF8 docs/servercore-command-reference.md
```

YAML 变更较大时，需要额外做实际解析或引用检查。过去常用 SnakeYAML 2.2 校验 `custom_items.yml`、`gathering_loot.yml`、`custom_mobs.yml`、`equipment_sets.yml`、`passive_abilities.yml`、`recipes.yml` 等文件。

## 测试现实

- 当前仓库没有 `src/test` 自动化测试。
- Gradle build 是最便宜的回归信号，但不能替代 Paper 测试服验证。
- 涉及 Bukkit/Paper 事件顺序、GUI、多人战斗、钓鱼、AuraSkills、MythicMobs、Vault 或死亡恢复的改动，必须在最终说明里列出测试服验收点。
- `git diff --check` 在本仓库可能遇到既有 LF/CRLF 提示或旧文档空白噪音；不要把它当唯一准入信号。

## 重要文档

- `docs/codex-context.md`：给后续 Agent 的当前交接摘要。
- `CURRENT_GAMEPLAY_SYSTEMS.md`：全项目玩法地图，部分描述可能落后于最新状态文档。
- `CUSTOMIZATION_GUIDE.md`：自定义内容流程。
- `docs/numerical-systems-status.md`：数值体系、公式和边界。
- `docs/enchant-system-status.md`：附魔状态、契约和 TODO。
- `docs/equipment-system-status.md`：装备、印记、套装和被动状态。
- `docs/fishing-system-status.md`：钓鱼公式、内容路线、环境与多人事件状态。
- `docs/player-logistics-system-status.md`：耐久、饱食、脱战恢复、食物和普通鱼食用。
- `docs/servercore-command-reference.md`：`/sc` 命令大全。
- `docs/equipment-set-test-commands.md`：多人装备套装测试指令。
- `Walkthrough.md`：按日期追加的实施历史和验证记录。

