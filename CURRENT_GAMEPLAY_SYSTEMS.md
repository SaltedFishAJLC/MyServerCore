# ServerCore 现有玩法与系统梳理

生成日期：2026-06-07

本文基于当前仓库源码与默认资源文件梳理。需要特别注意：运行服真实配置以 `plugins/ServerCore/*.yml` 为准，`src/main/resources/*.yml` 只是首次启动时复制到插件数据目录的默认模板。也就是说，本文能准确描述当前插件“具备哪些系统能力”，但具体线上已有多少条运行时物品、怪物、配方，仍要以服务端数据目录为最终来源。

## 1. 总体定位

`ServerCore` 目前已经是一个 HSB-like 的 Paper 1.21 服务器核心插件雏形。它不只是修改伤害，而是把下面几条玩法线接在一起：

- 玩家属性、职业、AuraSkills 等级、装备和饰品共同形成战斗数值。
- 战斗数值反推玩家生态战力，动态影响自然怪物和结构怪物的等级、血量、攻击力与奖励。
- 自定义物品、词缀、宝石、附魔、武器模板、盾牌、护甲 PDC 共同构成装备成长。
- 伐木、种植、挖矿、钓鱼、采掘等生活技能有独立掉落表、工具属性和额外经验。
- 自定义怪物、WDA 结构怪、自然刷怪替换、结构唯一精英怪形成探索与战斗内容。
- SQLite 经济、回收站、死亡灵魂容器、暂存箱提供基础运维与玩家物品保护。

可以把它理解为一个“核心 RPG 层”：AuraSkills 负责等级与经验底座，ServerCore 负责装备、职业、掉落、怪物缩放、经济、UI 和内容管理。

## 2. 主要依赖与运行环境

入口插件：`com.servercore.ServerCorePlugin`

插件名与指令：

- 主插件名：`ServerCore`
- 主命令：`/servercore`
- 别名：`/sc`、`/score`

硬依赖：

- Vault
- AuraSkills
- MythicLib
- ProtocolLib

软依赖：

- PlaceholderAPI
- LuckPerms
- CoreProtect
- GrimAC

构建与内嵌库：

- Paper API 1.21
- Java 21
- InventoryFramework 被 shade 进插件
- HikariCP 和 SQLite JDBC 被 shade 进插件
- MythicMobs 通过 compileOnly 兼容模块接入

## 3. 玩家可见的主玩法循环

### 3.1 战斗与生态刷怪循环

玩家装备、饰品、职业、AuraSkills 等级先汇总为 `CombatStats`。`PowerLevelManager` 再把近战、远程、法术三种 EDPH 与 EHP 合成生态战斗等级：

```text
level = sqrt(max(1, max(meleeEdph, rangedEdph, magicEdph)) * max(1, EHP) / 20) * 1.5
```

这个等级不会瞬间跳变，而是由 `PlayerStatCache` 保存当前战力和目标战力。当前战力按滑动方式追随目标战力，避免玩家靠瞬间换装操控附近刷怪等级。

怪物生成时，`MobSpawnManager` 会读取附近 64 格玩家当前战力的中位数，结合世界上限与自定义怪规则决定怪物等级。默认世界上限：

- 主世界和其他世界：50
- 下界：150
- 末地：250

刷怪笼生成会直接使用世界上限。自定义怪可以通过 `bypass_world_level_cap` 忽略世界上限。

怪物属性缩放公式当前大致为：

```text
maxHealth = round(level) * baseHp * (1.1 / 3.0) * mod + baseHp
attackDamage = baseAtk * (1 + round(level) / baseHp) * mod + baseAtk * 0.7
damageReduction = min(0.50, level * mod / (level * mod + 500))
magicResist = eliteOrAbove ? min(0.30, level * mod / 2000) : 0
```

怪物生成后会写入 PDC：

- `mob_power_level`
- `mob_damage_reduction`
- `mob_attack_damage`
- `mob_magic_resist`
- `mob_scaling_mod`
- `custom_mob_id`
- 虚拟血池相关字段

击杀带生态等级的怪物会：

- 追加经验掉落：约 `powerLevel / 2`
- 给击杀者金币：`powerLevel * 5`
- 触发自定义怪物掉落表
- 用 ActionBar 提示金币与生态击杀等级

### 3.2 装备成长循环

装备系统由 PDC 驱动。物品可以带战斗属性、核心属性、生活属性、准入需求、武器模板、手持规则、宝石孔、附魔、词缀、能力描述和外观数据。

当前支持的关键战斗属性：

- `base_damage`
- `base_multiplier`
- `crit_chance`
- `crit_damage`
- `brutality`
- `lifesteal`
- `armor_pen`
- `base_armor`
- `attack_speed_bonus`

当前支持的核心属性：

- 力量：`attr_toughness`
- 敏捷：`attr_agility`
- 智慧：`attr_intelligence`
- 意志：`attr_willpower`
- 幸运：`attr_luck`

当前支持的生活属性：

- `tool_fortune`
- `collection_fortune`
- `foraging_fortune`
- `farming_fortune`
- `excavation_fortune`
- `mining_fortune`
- `tool_sweep`
- `collection_sweep`
- `foraging_sweep`
- `mining_spread`
- `tool_mining_speed`
- `breaking_power`
- `purity`
- `mining_purity`
- `fishing_speed`
- `sea_creature_chance`
- `treasure_chance`
- `bounty`
- `overbloom`

默认 `custom_items.yml` 里当前粗略有 43 个物品模板。主要分为：

- 伐木材料：森林之心、树脂琥珀、活性树皮、低语叶片、林冠核心、古树髓种
- 种植材料：溢生精华、丰沃孢子、月照花粉、丰收晶尘、苍翠核心、创生荚果
- 钓鱼材料：深海匣、沉水绿宝石、珍珠簇、溺亡航图残页、远古贝壳、礁光玻璃、雷鱼鳞膜、潮汐之心、深渊珍珠、利维坦诱饵核
- 采掘材料：埋藏遗物、残缺陶偶、泥板残片、古代金币、化石心核、空响圣甲虫、埋藏王冠、蚀时罗盘、建筑师石板
- 挖矿材料：铁脉结核、金属火花、晶尘、晶洞回声、晶洞心脏、星铸采集核心
- 高阶采集工具：林冠收割斧、苍翠培育锄、唤潮钓竿、遗迹寻踪铲、晶洞破界镐
- 终局护符：采集者罗盘

这些物品不只是显示名与 lore，很多已经带有实际 PDC 属性和 AuraSkills 等级准入。

### 3.3 生活采集循环

生活技能主要由 `CollectionSkillManager`、`MiningManager`、`FishingManager` 和 `gathering_loot.yml` 驱动。

已经接入的生活玩法：

- 伐木 Bounty：砍树时按主手工具 `bounty` 概率触发额外掉落。
- 伐木 Sweep：工具带 `tool_sweep`、`collection_sweep`、`foraging_sweep` 时能连锁处理附近木系方块。
- 种植 Overbloom：收获成熟作物时按 `overbloom` 概率触发额外掉落。
- Farming Fortune：提高普通农作物复制份数，不影响 Overbloom 概率。
- 挖矿 Mining Fortune：提高矿物产出份数。
- Mining Spread：矿物扩散，能额外破坏同类矿物。
- Mining Speed：定时同步到玩家方块破坏速度属性。
- Breaking Power：限制工具是否能破坏特定矿物层级。
- Purity / Mining Purity：服务挖矿复制与稀有掉落路线。
- 钓鱼 Fishing Speed：缩短浮漂等待窗口。
- Sea Creature Chance：钓鱼时先判定海怪。
- Treasure Chance：海怪失败后再判定宝藏。
- 采掘 Excavation：挖沙、泥、土、黏土、灵魂土等时可以召唤生物或发现宝藏。

`gathering_loot.yml` 当前默认内容包括：

- Bounty 表：6 条伐木额外掉落
- Overbloom 表：6 条种植额外掉落
- Mining 表：5 条挖矿稀有掉落
- Fishing sea creature 表：6 条海怪
- Fishing treasure 表：rare、epic、legendary 三档，共 10 条宝藏
- Excavation creature 表：5 条采掘召唤物
- Excavation treasure 表：rare、epic、legendary 三档，共 9 条宝藏

这些掉落都可以写 `custom_item`，优先从 `custom_items.yml` 生成；找不到时用 `fallback_material` 兜底。每条还可以写 AuraSkills 额外 XP。

### 3.4 职业与被动循环

`/sc class` 已经有职业选择 GUI。当前职业枚举：

- 无职业
- 血魔
- 守护者
- 神射手
- 游侠
- 先知
- 灾厄使魔
- 赌徒
- 刺客
- 魔剑士
- 收割者

职业不是单纯标签，而是与两项核心属性绑定。玩家选择职业时，还要选择主属性和副属性顺序，职业 Power 由职业允许的属性组合决定。

已接入的职业效果示例：

- 血魔：额外残暴值，自带近战吸血，吸血效果翻倍。
- 守护者：额外生命恢复，战斗中仍保留较高恢复，替附近队友承受部分实际伤害。
- 神射手：额外暴击率、破甲，箭矢轻微吸附。
- 游侠：攻击速度、移动速度，允许二段跳，命中后叠加移速。
- 先知：额外法力上限，消耗法力积累扭曲治疗光环。
- 灾厄使魔：额外法力和法术乘区，代价是最大生命折损，消耗法力转化血池。
- 赌徒：额外暴击伤害和魔法发现，代价是护甲折损，暴击和稀有掉落走二次判定。
- 刺客：移动速度、暴击伤害，闪避后短暂隐身并清除非首领仇恨。
- 魔剑士：额外法力与残暴，无法暴击，远程物理降低，近战转为法术伤害。
- 收割者：额外暴击率和残暴，目标生命越低伤害乘区越高。

职业惩罚已经接入实战链路。例如赌徒的护甲惩罚会影响实际减伤，但 `/sc stats` 仍区分原始护甲与实战护甲。

### 3.5 探索与怪物内容循环

当前 `custom_mobs.yml` 默认粗略有 155 条顶层怪物规则。它支持：

- 按实体类型匹配
- 按显示名匹配
- 按显示名正则匹配
- 按 MythicMobs 内部 ID 匹配
- 按 ServerCore PDC ID 匹配
- 按 scoreboard tag 匹配
- 按装备匹配

规则可以覆盖：

- 展示名
- 固定生态等级
- 是否绕过世界等级上限
- 缩放倍率 `mod`
- 基础生命
- 固定最终最大生命
- 基础攻击
- 是否常驻仇恨玩家
- 是否清除原版掉落
- 自定义掉落表

目前内容覆盖了：

- 示例结构怪：`WDA_Dungeon_Guard`
- MythicMobs 示例 Boss：`Mythic_Flame_Boss`
- 固定 500 级 Warden：`Warden_500`
- 自然替换怪：`Rogue_Enderman`
- 大量 WDA 结构怪，如 abandoned temple、aviary、bandit towers、foundry、heavenly challenger、illager camps、mushroom mines、plague asylum 等

需要注意：`custom_mobs.yml` 里部分条目以 `NEED_FIX_` 开头，或存在空数值字段。这说明 WDA 内容表已经铺开，但部分结构怪仍处在待补数值或待核对状态。

自然刷怪替换当前默认有：

- 自然生成的末影人有 10% 替换为 `Rogue_Enderman`

结构唯一精英怪当前默认有：

- 一个关闭的坐标示例刷新点
- 一个启用的 marker 模式刷新点：`servercore_unique_spawn:wda_dungeon_elite`

marker 模式适合 WDA 结构：每个结构模板中放一个 marker entity，ServerCore 发现后替换为唯一精英怪，并把该点的已生成/已死亡状态写入状态文件。

## 4. 战斗系统细节

### 4.1 玩家对怪物造成伤害

`CombatManager` 拦截玩家攻击，统一接管伤害公式。它区分：

- 近战
- 远程投射物
- 法术伤害
- 魔剑士近战转法术

主要影响项：

- 基础伤害
- 蓄力比例
- Sharpness 类原版附魔补偿
- AuraSkills combat skill multiplier
- 职业攻击加成
- 暴击几率与暴击倍率
- 残暴倍率
- 破甲
- 目标自定义减伤
- 目标魔抗
- 目标主标签/特质标签带来的抗性规则
- 吸血

暴击由 ServerCore 自己接管，原版暴击不再直接决定最终伤害。残暴偏向满蓄力平 A 的追击收益。

### 4.2 玩家承受伤害

玩家受伤时，插件会处理：

- 怪物 `mob_attack_damage` 覆盖原版攻击伤害
- 盾牌格挡优先于普通减伤
- 自定义护甲减伤公式：`armor / (armor + 100)`
- 职业护甲惩罚
- 核心属性带来的魔法减伤
- 敏捷带来的闪避

原版护甲减伤已经被剥离。原版防具会通过 `ItemStandardizer` 把面板护甲迁移到 PDC `base_armor`，并写入 0 值属性覆盖，避免 Minecraft 原版护甲和 ServerCore 护甲重复减伤。

### 4.3 虚拟血池

当怪物配置的最终血量超过 Paper 或原版实体属性可接受范围时，`MobSpawnManager` 会启用虚拟血池：

- PDC 保存真实虚拟血量与最大虚拟血量。
- 实体实际血量按比例映射到可承受的物理血量。
- 全息血条显示虚拟血量。
- 死亡判定按虚拟血量走。

这为高等级 Boss 或固定超高血量怪物预留了空间。

### 4.4 伤害标签、状态与抗性

`combat` 包下面已经有更细的伤害服务层：

- `DamageService`
- `DamagePacket`
- `DamageCategory`
- `DamageTag`
- `VanillaDamageAdapter`
- `StatusService`
- `FrostService`
- `StunController`
- `ResistanceResolver`
- `TagRuleRegistry`
- `CreatureTagService`

状态类型包括：

- 燃烧
- 中毒
- 凋零
- 流血
- 冻伤
- 眩晕/冻结相关控制

`creature-tags.yml` 给实体分主标签和特质标签。主标签包括：

- 亡灵
- 骷髅
- 动物
- 节肢
- 人形
- 黏体
- 构装
- 幽灵
- 巨兽
- 异怪

特质标签包括：

- 熔火
- 冰霜
- 幽冥
- 飞行
- 首领

`tag-rules.yml` 会让不同标签拥有不同抗性。例如：

- 亡灵免疫中毒，流血收益减半。
- 骷髅继承亡灵，并完全免疫流血。
- 构装免疫中毒和流血。
- 火焰特质免疫火焰，怕冰霜。
- 冰霜特质免疫冰霜，怕火焰。
- 巨兽和首领对控制时间、DOT 上限有额外限制。

这套系统目前是插件未来做技能、附魔、Boss 机制的底层。

## 5. 装备系统细节

### 5.1 自定义物品模板

`CustomItemRegistry` 负责读取 `custom_items.yml`。模板可以包含：

- `material`
- `amount`
- `name`
- `rarity`
- `custom_model_data`
- `unbreakable`
- `stats`
- `sockets` / `gem_sockets`
- `enchants`
- `abilities`
- `ability_lore`
- `story_lore`
- `accessory_type` / `acc_type`
- `reforge`
- `weapon_template`
- `hand_rule`
- `appearance`
- `shield`
- `req_skill`
- `req_slayer`
- `req_dungeon`

管理指令：

```text
/sc admin items reload
/sc admin items list
/sc admin items give <物品ID> [数量]
/sc admin items id <物品ID>
/sc admin items save [物品ID]
```

`id` 会给手持物品打 ServerCore 内部 ID。`save` 会把手持物品导出到运行时 `plugins/ServerCore/custom_items.yml`，并尽量保存外观、PDC 属性、孔位、附魔、故事 lore 等。之前实现中还特别处理了宝石/词缀类加成，导出时保存更接近“模板初始状态”的数据。

### 5.2 物品格式化

`ItemFormatManager` 会在拾取、合成、铁砧、附魔、背包点击、打开/关闭容器等事件后刷新物品展示。它负责：

- 中文物品名映射
- 稀有度显示
- 属性 lore
- 宝石孔显示
- 自定义附魔显示
- 能力 lore
- 故事 lore
- 准入需求显示

这意味着物品的显示基本是最终面板渲染器，而不是每个系统各写自己的 lore。

### 5.3 原版物品标准化

`ItemStandardizer` 与 `vanilla_item_overrides.yml` 共同处理原版物品。

当前默认覆盖：

- 下界合金镐获得 `mining_spread: 1`

原版武器会被迁移到自定义伤害 PDC，避免原版伤害与插件伤害重复叠加。原版防具会把原版护甲按过渡倍率写入 `base_armor`，再剥离原版护甲结算。

当前已知设计：

- 原版武器基础面板攻击按约 3 倍过渡。
- 原版防具护甲按约 5 倍过渡。
- 下界合金/钻石套的目标是显示约 100 自定义护甲，对应约 50% 自定义减伤。

### 5.4 武器模板与手持规则

`WeaponTemplateManager` 支持的模板：

- `ONE_HANDED_SWORD`
- `TWO_HANDED_SWORD`
- `ONE_HANDED_AXE`
- `TWO_HANDED_AXE`
- `HEAVY_HAMMER`
- `TRIDENT`
- `DAGGER`
- `SCYTHE`
- `SHORTBOW`
- `LONGBOW`
- `CROSSBOW`
- `SHIELD`

模板会影响：

- 攻速
- 交互/攻击距离
- 是否注入 vanilla attribute
- 默认手持规则
- 短弓冷却
- 主副手属性倍率

手持规则：

- 主手限定
- 副手限定
- 双手皆可
- 双手武器

双手武器如果副手有阻挡物，会被判定不可正常使用。主手、副手切换和背包点击后都会刷新与校验。

### 5.5 短弓、远程与箭矢辅助

`RangedWeaponManager` 已处理短弓右键快速射击。它会：

- 校验主手武器规则
- 消耗箭或判定能否免费射击
- 施加冷却
- 应用弓附魔

神射手职业还会让箭矢获得轻微目标吸附，方便远程职业形成手感差异。

### 5.6 盾牌

`ShieldManager` 支持自定义盾牌格挡：

- `shield_block_threshold`
- `shield_effective_block`
- `shield_cooldown_seconds`

盾牌格挡在普通护甲减伤前处理。伤害超过阈值会造成突破或崩裂表现，并施加冷却、击退和音效。

### 5.7 词缀、宝石与附魔

词缀默认包括：

- `sharp`
- `fierce`
- `divine`
- `reinforced`
- `wise`
- `swift`

宝石默认包括：

- 红宝石：武器孔，伤害和暴击伤害
- 蓝宝石：工具孔，智慧
- 翡翠：防具孔，护甲和力量
- 琥珀：通用孔，敏捷和暴击率
- 紫水晶：通用孔，意志和幸运

自定义附魔默认包括：

- `vampirism`
- `critical`
- `cleave`
- `experience_harvest`
- `mana_surge`
- `undead_slayer`
- `skeleton_slayer`
- `animal_slayer`
- `arthropod_slayer`
- `humanoid_slayer`
- `gelatinous_slayer`
- `construct_breaker`
- `exorcism`
- `giant_hunter`
- `aberrant_hunter`

其中 slayer 类附魔已经和生物主标签系统天然适配。需要注意的是，附魔注册和 PDC 写入已经存在，但每个附魔的完整战斗效果要看后续是否在伤害链路中读取。

### 5.8 物品能力

`custom_items.yml` 中可以写 `abilities`，`CustomItemRegistry` 会解析：

- ability id
- trigger
- cooldown
- lore
- options

`WeaponAbilityManager` 已经能监听右键、判断主副手触发、检查冷却并派发到 ability handler。

当前状态：能力框架已接入，具体技能效果需要通过 `registerAbilityHandler` 注册。也就是说，YAML 里的能力描述不是自动变成实际效果，必须有对应 handler。

## 6. 饰品、护符包与属性缓存

`/sc acc` 打开独立饰品槽。当前独立槽位：

- 项链：`necklace`
- 手镯：`bracelet`
- 戒指：`ring`
- 腰带：`belt`

另有 54 格护符包。饰品栏和护符包都由 `AccessoryManager` 序列化到玩家 PDC，不依赖数据库。

`CombatStats.calculateStatic` 会扫描：

- 防具
- 独立饰品
- 护符包
- 职业加成

`CombatStats.getFullStats` 再额外扫描：

- 主手
- 副手

这样主副手切换只需要刷新动态部分，而防具/饰品/护符包能作为相对稳定的静态缓存来源。

## 7. UI 与信息展示

玩家常用 UI：

- `/sc stats`：战斗、生存、职业、生态战力总面板
- `/sc gathering`、`/sc life`、`/sc noncombat`、`/sc toolstats`：生活/工具属性面板
- `/sc acc`：饰品槽与护符包
- `/sc class`：职业选择
- `/sc stash`：暂存箱
- `/sc recycle`、`/sc salvage`：回收站
- `/sc recipe [物品ID或手持物品]`：查询后续配方用途

常驻展示：

- ActionBar：生命、护甲、法力等核心状态
- Scoreboard：当前生态战力、理论战力、金币余额
- 怪物全息：`[Lv.x] 名称 ❤ 血量`

`AuraSkillsMenuHijacker` 还会劫持 AuraSkills 的技能菜单命令，把技能列表、等级进度、来源和奖励用 ServerCore 风格 GUI 展示，同时仍然读取 AuraSkills 本身的等级、经验、配置与消息文件。

## 8. 自定义配方系统

`CustomRecipeManager` 提供 GUI 配方编辑器：

```text
/sc admin recipe create <配方ID>
/sc admin recipe reload
/sc recipe [物品ID]
```

特点：

- 左侧 3x3 放材料，右侧放结果。
- 保存完整 `ItemStack`，包括 PDC、lore、模型、宝石等。
- Bukkit 配方用 `RecipeChoice.ExactChoice`，因此材料需要 NBT/PDC 完全匹配。
- 支持按手持物品或物品 ID 查询“这个物品可用于哪些后续配方”。
- 支持配方详情 GUI。
- 合成结果时会尝试转移升级载体的成长状态、宝石孔、原版附魔等。

这套系统已经可以支撑“拿旧装备升级成新装备”的制作链。

## 9. 经济、回收、死亡与暂存

### 9.1 经济

经济系统自研，数据保存在 SQLite：

- 数据文件：`plugins/ServerCore/economy.db`
- 表：`players_economy`
- 金币类型：`long`
- 启动时通过 Vault 注册经济服务

玩家上线异步加载余额，下线异步保存。插件卸载时同步保存全部余额。

### 9.2 回收站

`RecycleManager` 读取 `recycle.yml`。价格优先级：

```text
item_id 价格 > material 价格 > rarity 价格
```

当前默认价格覆盖了大部分采集材料、高阶工具和采集者罗盘。GUI 会计算回收报价，并对受保护或高价值物品做确认。

### 9.3 死亡灵魂容器

玩家死亡时，`DeathListener` 会：

- 保存背包、装备和饰品。
- 清空已保存物品，避免原版掉落重复。
- 在智能位置生成一个灵魂容器物品。
- 灵魂容器 ID 与物品数据写入 SQLite `soul_containers` 表。

玩家拾取灵魂容器时恢复物品。背包放不下的物品会进入暂存箱。

### 9.4 暂存箱

`StashManager` 使用 SQLite `player_stash` 表保存溢出物品。玩家使用：

```text
/sc stash
```

即可打开暂存箱 GUI 取回物品。它主要服务死亡恢复、奖励溢出和背包满时的保护。

## 10. 后台内容管理指令

常用玩家指令：

```text
/sc stats
/sc gathering
/sc acc
/sc class
/sc stash
/sc recycle
/sc recipe [物品ID]
```

物品管理：

```text
/sc admin items reload
/sc admin items list
/sc admin items give <物品ID> [数量]
/sc admin items id <物品ID>
/sc admin items save [物品ID]
/sc admin rarity <COMMON|UNCOMMON|RARE|EPIC|LEGENDARY|MYTHIC>
/sc admin reforge <词缀ID|clear>
/sc admin gem socket <WEAPON|ARMOR|TOOL|UNIVERSAL> <数量>
/sc admin gem apply <宝石ID>
/sc admin enchant <附魔ID> <等级>
```

快速写 PDC 属性：

```text
/sc item <属性> <数值>
/sc item acctype <necklace|bracelet|ring|belt>
/sc item template <武器模板>
/sc item handrule <手持规则>
```

配方管理：

```text
/sc admin recipe create <配方ID>
/sc admin recipe reload
```

怪物管理：

```text
/sc admin mobs reload
/sc admin mobs list
/sc admin mobs summon <规则ID> [数量]
/sc admin mobreplacements reload
/sc admin mobreplacements list
/sc admin uniquespawns reload
/sc admin uniquespawns list
/sc admin uniquespawns reset <刷新点ID>
```

其他内容：

```text
/sc admin vanillaitems reload
/sc admin vanillaitems list
/sc admin gatheringloot reload
/sc admin loot reload
/sc admin recycle reload
/sc admin names reload
```

## 11. 当前已配置内容统计

以下是对 `src/main/resources` 默认模板的粗略统计：

- 自定义物品模板：约 43 个
- 自定义怪物规则：约 155 个
- 自然刷怪替换规则：1 个
- 唯一结构精英刷新配置：1 个坐标示例、1 个 marker 示例
- 伐木 Bounty：6 条
- 种植 Overbloom：6 条
- 挖矿稀有掉落：5 条
- 钓鱼海怪：6 条
- 钓鱼宝藏：10 条
- 采掘召唤物：5 条
- 采掘宝藏：9 条

这些数字只代表默认模板。运行时文件可能更多，也可能被删改。

## 12. 已经比较成型的模块

当前成熟度较高的模块：

- 战斗公式与玩家属性缓存
- 生态战力与怪物动态缩放
- 怪物全息血条
- 自定义护甲与原版护甲剥离
- 原版装备标准化
- 自定义物品模板读取与导出
- 饰品槽与护符包
- 职业 GUI 与职业加成
- 生活采集掉落表
- 钓鱼海怪/宝藏
- 挖矿速度、扩散、稀有掉落
- 自定义配方 GUI
- SQLite 经济、回收、死亡容器、暂存箱
- AuraSkills 菜单接管与属性桥接

## 13. 半成品或需要继续推进的部分

这些不是坏事，只是当前状态需要标注清楚：

- `custom_mobs.yml` 中部分 WDA 条目有 `NEED_FIX_` 或空数值，说明内容表还没全部校准。
- 自定义物品 `abilities` 已经有解析、冷却和派发框架，但具体技能效果需要注册 handler。
- 自定义附魔已经有 PDC 与描述注册，但不是每个附魔都确认已在伤害链路里完整产生效果。
- 猎手/副本等级目前主要是准入 PDC 字段，完整 Slayer/Dungeon 玩法还没有正式落地。
- LuckPerms、CoreProtect、GrimAC 是软依赖方向，当前主要是预留与兼容思路，不等于完整玩法已经完成。
- 配方默认文件不一定已经有完整制作链，主要能力是 GUI 创建和运行时注册。
- MythicMobs 兼容存在，但具体 MythicMob 内容仍依赖外部 MythicMobs 配置。

## 14. 后续推进建议

如果重新开始推进项目，建议按下面顺序恢复手感：

1. 先确认运行服 `plugins/ServerCore/*.yml` 与源码默认模板是否一致，尤其是 `custom_items.yml`、`custom_mobs.yml`、`gathering_loot.yml`。
2. 跑一次 Gradle build，确认当前仓库仍能构建 Shadow Jar。
3. 在测试服用 `/sc stats`、`/sc gathering`、`/sc class`、`/sc acc` 检查基础 UI 是否正常。
4. 用一套原版装备测试标准化：护甲显示、实战护甲、减伤、ActionBar 是否符合预期。
5. 用 `/sc admin items give geodebreaker_pick`、`/sc admin items give gatherers_compass` 等检查高阶采集物品。
6. 分别测试伐木 Bounty、种植 Overbloom、挖矿 Spread、钓鱼海怪、采掘宝藏。
7. 用 `/sc admin mobs summon Rogue_Enderman 1` 和 `/sc admin mobs summon Warden_500 1` 测试自定义怪物缩放、全息、掉落和金币。
8. 再处理 WDA 怪物表里的 `NEED_FIX_` 和空数值条目。
9. 最后再推进自定义技能 handler、附魔效果和 Slayer/Dungeon 进度系统。

## 15. 关键源码与配置索引

入口与命令：

- `src/main/java/com/servercore/ServerCorePlugin.java`
- `src/main/resources/plugin.yml`

战斗与生态：

- `src/main/java/com/servercore/manager/CombatManager.java`
- `src/main/java/com/servercore/manager/CombatStats.java`
- `src/main/java/com/servercore/manager/PowerLevelManager.java`
- `src/main/java/com/servercore/manager/MobSpawnManager.java`
- `src/main/java/com/servercore/manager/HologramManager.java`
- `src/main/java/com/servercore/combat/`

装备与物品：

- `src/main/java/com/servercore/manager/PDCManager.java`
- `src/main/java/com/servercore/manager/CustomItemRegistry.java`
- `src/main/java/com/servercore/manager/ItemFormatManager.java`
- `src/main/java/com/servercore/manager/ItemStandardizer.java`
- `src/main/java/com/servercore/manager/WeaponTemplateManager.java`
- `src/main/java/com/servercore/manager/RangedWeaponManager.java`
- `src/main/java/com/servercore/manager/ShieldManager.java`
- `src/main/java/com/servercore/manager/ReforgeManager.java`
- `src/main/java/com/servercore/manager/GemstoneManager.java`
- `src/main/java/com/servercore/manager/EnchantManager.java`

职业与属性：

- `src/main/java/com/servercore/manager/AttributeManager.java`
- `src/main/java/com/servercore/manager/ClassManager.java`
- `src/main/java/com/servercore/manager/ClassPassiveManager.java`
- `src/main/java/com/servercore/manager/AuraSkillsBridge.java`
- `src/main/java/com/servercore/manager/AuraSkillsMenuHijacker.java`

生活技能：

- `src/main/java/com/servercore/manager/CollectionSkillManager.java`
- `src/main/java/com/servercore/manager/MiningManager.java`
- `src/main/java/com/servercore/manager/FishingManager.java`
- `src/main/java/com/servercore/manager/GlobalStatManager.java`

经济与保护：

- `src/main/java/com/servercore/manager/DatabaseManager.java`
- `src/main/java/com/servercore/manager/EconomyManager.java`
- `src/main/java/com/servercore/manager/VaultImplementer.java`
- `src/main/java/com/servercore/manager/RecycleManager.java`
- `src/main/java/com/servercore/manager/DeathListener.java`
- `src/main/java/com/servercore/manager/SoulContainerManager.java`
- `src/main/java/com/servercore/manager/StashManager.java`

默认配置：

- `src/main/resources/custom_items.yml`
- `src/main/resources/custom_mobs.yml`
- `src/main/resources/mob_replacements.yml`
- `src/main/resources/unique_mob_spawns.yml`
- `src/main/resources/gathering_loot.yml`
- `src/main/resources/recipes.yml`
- `src/main/resources/recycle.yml`
- `src/main/resources/vanilla_item_overrides.yml`
- `src/main/resources/creature-tags.yml`
- `src/main/resources/tag-rules.yml`

## 16. 一句话总结

当前 ServerCore 已经具备一个 RPG 服务器核心插件的主骨架：玩家通过职业、属性、装备和 AuraSkills 成长；成长影响战斗力；战斗力影响生态怪物；怪物和采集内容产出材料；材料进入自定义物品、回收、配方和后续装备线。接下来最值得做的是补齐运行时配置状态、校准 WDA 怪物表、落实技能 handler 和附魔效果，让现有骨架从“系统已接好”进一步变成“内容链稳定可玩”。
