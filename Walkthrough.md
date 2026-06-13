# Walkthrough

## 2026-05-11 服务器生态对抗系统底层实现

### 更新日志

- 完成 `PowerLevelManager`：根据玩家 `CombatStats` 计算目标战斗力，并通过 10% 步长滑动平均维护当前战斗力，降低临时换装对刷怪等级的影响。
- 完成 `MobSpawnManager`：监听怪物生成，读取 64 格内玩家当前战斗力中位数，按世界上限生成动态等级；刷怪笼生成直接拉满当前世界上限。
- 完成 `HologramManager`：使用 1.20+ `TextDisplay` 作为怪物乘客，展示 `[Lv.x] 名称 ❤ 血量` 格式，并在受伤后更新血量。
- 完成 `RequirementManager`：支持装备准入 PDC 标签，拦截主手切换、防具穿戴和饰品穿戴。
- 扩展 `PDCManager`：新增怪物等级、怪物标签、怪物减伤、全息实体 UUID、装备准入需求、玩家通用猎手/副本进度等 key。
- 扩展 `PlayerStatCache`：新增当前战斗力与目标战斗力缓存，玩家退出时同步清理。
- 更新 `CombatManager`：玩家攻击带有生态 PDC 的怪物时，额外读取 `mob_damage_reduction`，使用自定义上限 40% 减伤，不混入原版护甲公式。
- 更新 `ServerCorePlugin`：按依赖顺序初始化准入、战力、全息和刷怪模块，并在卸载时停止战力定时任务。

### 实现细节

- 战斗力公式使用 `CombatStats.getFullStats(player)` 作为输入，将基础伤害、增伤乘区、期望暴击、残暴和破甲合成为 `effectiveDps`，再用平方根缩放到稳定等级区间。
- 当前战斗力每 100 tick 刷新一次，`currentPower += (targetPower - currentPower) * 0.10`。首次计算时直接初始化为目标值，避免新玩家在缓存冷启动时生成 1 级生态。
- 怪物世界限幅：
  - 主世界与其他世界：50
  - 下界：150
  - 末地：250
- 怪物属性按等级赋值：
  - 最大生命：`20 + level * 10`
  - 攻击伤害：`2 + level * 0.35`
  - 原版护甲和护甲韧性清零，自定义减伤写入 PDC，最高 40%。
- 怪物标签通过实体类型写入 `mob_tags`，目前覆盖僵尸、骷髅、狼、幽影、熔岩、巨兽等猎手预留分类。
- 击杀带生态等级的怪物会追加经验掉落，并给击杀者发放 `level * 5` 金币作为基础生态奖励。
- 装备准入标签格式：
  - `req_skill`: `fighting:10` 或 `mining:5,fighting:8`
  - `req_slayer`: `zombie:3` 或 `3`
  - `req_dungeon`: `catacombs:5` 或 `5`
- 猎手/副本系统尚未正式落地时，准入引擎优先读取玩家 PDC 的通用等级 `player_slayer_level` / `player_dungeon_level`，也支持后续按分类扩展为 `slayer_zombie_level`、`dungeon_catacombs_level`。

### 验证记录

- `.\gradle-8.5\bin\gradle.bat --no-daemon compileJava` 未进入 Java 编译阶段，失败原因是现有 Shadow 插件与当前 Gradle API 的构建期兼容错误：`TaskContainer.named(Spec)` `NoSuchMethodError`。
- 已使用本地 Gradle 缓存依赖通过 `javac --release 21` 对 `src/main/java` 进行源码级编译验证，编译通过，仅保留既有弃用 API 提示。

## 2026-05-11 全息显示与小怪血量平衡修复

### 更新日志

- 修复 TextDisplay 全息框只有细线、没有文字的问题：从 passenger 挂载模式改为独立 TextDisplay 跟随模式，每 2 tick 同步到怪物头顶。
- 为 TextDisplay 补齐可见性参数：居中对齐、行宽、文字不透明度、半透明背景、看穿显示和阴影，降低夜晚/模型遮挡导致的不可读概率。
- 重做普通小怪生命值公式：不再使用 `20 + level * 10`，改为按同等级玩家预估伤害反推目标刀数。
- 普通小怪目标约 4.5 刀击杀；末影、烈焰、岩浆等稍厚；劫掠兽、铁傀儡、远古守卫者和 Warden 保持精英/巨兽定位。
- 修复正式 Gradle 构建：Shadow 插件降到兼容 Gradle 8.5 的 `8.3.5`，并取消强制 Java 21 toolchain，保留 `--release 21` 产物目标。

### 实现细节

- `HologramManager` 现在维护怪物 UUID 到 TextDisplay UUID 的映射，怪物死亡、失效或 display 丢失时自动清理。
- 全息位置使用 `entity.getHeight() + 0.55`，避免嵌进僵尸头部导致文字被模型吞掉。
- 小怪血量公式核心为：`sameLevelDamage = max(1, (level / 6)^2)`，再乘以目标刀数和自定义减伤补偿。
- 对你截图中的 9.3 单次伤害场景，普通僵尸会从此前约 110+ 血降到约 40 血上下，预期 4-5 刀死亡。

### 验证记录

- `javac @build\codex-javac.args` 编译通过。
- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 构建通过，已生成新的 Shadow Jar。

## 2026-05-19 职业护甲惩罚回接
### 更新日志

- 保留赌徒职业的护甲减少代价，并将它重新接入实战减伤链路。
- `PowerLevelManager.calculateArmorValue` 继续返回原始自定义护甲值，用于 ActionBar、装备观感和 `/sc stats` 的主护甲显示。
- 新增 `PowerLevelManager.calculateEffectiveArmorValue`，在原始护甲基础上套用 `ClassManager.getArmorPenaltyRate`，用于实际减伤率和 EHP 计算。
- `/sc stats` 生存面板在职业惩罚生效时额外显示“实战护甲”，避免把职业折损误判为装备数值错误。

### 实现细节

- `calculateDamageReduction` 改为读取 `calculateEffectiveArmorValue`，所以怪物打玩家时会受到职业护甲惩罚影响。
- 下界合金套仍显示 100 原始护甲；如果职业产生 30% 护甲惩罚，实战护甲为 70，减伤按 `70 / (70 + 100)` 计算。

### 验证记录

- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 构建通过，已生成新的 Shadow Jar。

## 2026-05-11 修订版生态曲线、Passenger 全息与 Scoreboard

### 更新日志

- 按最新 `implementation_plan.md` 重构 `MobSpawnManager` 数值曲线：
  - 生命值：`20.0 + Math.pow(powerLevel, 1.25) * 0.8 * targetHits`
  - 攻击力：`2.0 + Math.pow(powerLevel, 0.85) * 0.3`
- 重写 `HologramManager`：移除循环传送方案，改回 `entity.addPassenger(display)` 乘客挂载法。
- 为 TextDisplay 添加 `Transformation` Y 轴偏移，让文字固定悬浮在怪物头顶并避开面部遮挡。
- 在 `EntityDamageEvent` 中加入自动重铸逻辑：如果怪物存在生态等级 PDC 但缺失 TextDisplay 乘客，会立即重建血条。
- 新增 `ScoreboardManager`，在右侧常驻显示当前滑动战斗等级、理论目标战斗等级和金币余额。
- 升级 `/sc stats`：新增下界之星战力图标，展示当前滑动战力、缓存目标战力和理论极限战力。

### 实现细节

- 全息 TextDisplay 使用 scoreboard tag `servercore_hologram` 标识，优先从怪物 passenger 列表定位，PDC UUID 仅作为兼容 fallback。
- TextDisplay 参数包含居中、行宽、文字不透明、半透明背景、阴影、无重力、无敌、静音和 `seeThrough`，提高各种视角下的可读性。
- Scoreboard 每 20 ticks 在主线程刷新。虽然计划写了“异步刷新”，但 Bukkit Scoreboard API 必须在主线程操作，所以这里保留轻量主线程刷新，避免异步触碰 Bukkit UI 对象。
- Scoreboard 当前展示 `Lv. 当前 / 理论` 与 `Coins`，金币读取自现有 `EconomyManager` 内存缓存。

### 验证记录

- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 构建通过，已重新生成 Shadow Jar。

## 2026-05-12 最终版数值闭环、自定义怪物接口与全息清理

### 更新日志

- 按最新版 `implementation_plan.md` 彻底重构 `PowerLevelManager`：
  - 玩家战斗等级改为 `lvl = sqrt(EDPH * EHP / 20.0) * 1.5`。
  - 新增 `PowerBreakdown`，拆分近战 EDPH、远程 EDPH、法术 EDPH、EHP 与最终等级。
  - 近战纳入暴击期望与残暴期望；远程按破甲真实伤害价值翻倍；法术按基础法伤与通用乘区估算。
- 按最终蓝图重构 `MobSpawnManager`：
  - 怪物以原版困难模式基础血量/攻击力为底数，按玩家等级与 `mod` 线性修正。
  - 生命公式：`Nhp = round(lvl) * hp * (1.1 / 3.0) * mod + hp`
  - 攻击公式：`Natk = atk * (1 + round(lvl) / hp) * mod + atk * 0.7`
  - 防御公式：`dr = min(0.50, def / (def + 500.0))`
  - 精英及以上怪物写入魔抗，最高 30%。
- 新增自定义怪物接口：
  - `MobSpawnManager.registerCustomMobModifier(String mythicName, double customMod)`
  - `MobSpawnManager.setBaseVanillaStats(EntityType type, double hp, double atk)`
  - 支持通过实体 PDC `custom_mob_id` 或 scoreboard tag `servercore_mob:<id>` / `mythicmob:<id>` 匹配自定义倍率。
- 修复全息面板残留：
  - 监听死亡、实体移除、实体移出世界、实体批量卸载。
  - 每 5 秒低频清理孤儿 TextDisplay，兼容旧版本遗留的 `[Lv.` 血条。
- 修复全息高度：
  - 不再使用 `entity.getHeight() + 0.35` 的过高偏移。
  - 改为按蜘蛛、史莱姆、人形怪、末影人、巨兽等类型分组设置 passenger transformation Y 偏移。
- `/sc stats` 的下界之星战力卡现在显示 EDPH/EHP 细分数据。

### 验证记录

- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 构建通过。

- 构建仅提示 `EntityRemoveEvent` 弃用；当前保留它作为清理路径兜底，同时已接入 Paper 的 `EntityRemoveFromWorldEvent`。

## 2026-05-12 原版装备过渡倍率与 ActionBar 护甲显示

### 更新日志

- 原版武器过渡倍率接入 `ItemStandardizer`：默认面板攻击力提升为原版数值的 3 倍。
- 原版防具过渡倍率接入 `ItemStandardizer`：默认护甲值提升为原版数值的 5 倍。钻石套总护甲从 20 提升到 100，按当前 `armor / (armor + 100)` 公式约为 50% 减伤。
- 新增 `item_scale_version` PDC 标记，旧版本已接管装备会在玩家登录、拾取、点击、打开容器等触发点自动迁移到新倍率。
- `ActionBarManager` 在生命值和法力值之间新增绿色护甲值显示。
- ActionBar 刷新从异步任务改为主线程轻量刷新，避免异步读取 Bukkit 属性。

### 实现细节

- Mythic/自定义装备不套用原版 3x/5x 倍率，避免覆盖自定义数值。
- 原版防具会重写 `GENERIC_ARMOR` AttributeModifier 到对应槽位，钻石头/胸/腿/鞋分别为 15/40/30/15，总计 100。
- 武器仍保留既有“原版攻击属性归零 + PDC 接管伤害”模式，避免原版伤害与自定义公式重复叠加。

### 验证记录

- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 构建通过。

## 2026-05-12 防具护甲叠加修复与生存面板

### 更新日志

- 修复穿多件护甲时只计算最后一件护甲值的问题。
- 原因是所有防具使用了同一个 `scaled_armor` / `scaled_toughness` AttributeModifier key，导致同名修饰器在玩家属性上互相覆盖。
- 将防具 AttributeModifier key 改为按材料唯一，例如 `scaled_armor_diamond_chestplate`。
- 将 `item_scale_version` 从 2 提升到 3，旧防具会在登录、点击、打开容器等标准化触发点自动迁移。
- `/sc stats` 新增盾牌生存槽位，展示当前血量、护甲值、减伤率、EHP。
- `/sc stats` 魔法伤害面板新增最大法力值显示。

### 验证记录

- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 构建通过。

## 2026-05-12 护甲 30 上限绕过

### 更新日志

- 修复护甲值被原版 `GENERIC_ARMOR` 30 点上限卡住的问题。
- 原版防具不再把核心护甲写入 `GENERIC_ARMOR`，改为写入 PDC `base_armor`。
- `PowerLevelManager` 新增自定义护甲求和：扫描玩家防具、饰品和护符包中的 `base_armor`。
- 玩家 EHP、ActionBar 护甲显示、`/sc stats` 生存面板全部改为读取自定义护甲值。
- `CombatManager` 新增玩家受伤减伤分支，按 `armor / (armor + 100)` 对伤害进行自定义减免。
- `/sc item armor <数值>` 已接入，方便给自定义装备写入同一套护甲 PDC。
- `item_scale_version` 提升到 4，旧防具会迁移为 PDC 护甲并移除原版护甲 AttributeModifier。
- `ItemStandardizer` 启动后会扫描在线玩家背包/防具，避免热重载时旧防具没有触发登录迁移。

### 验证记录

- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 构建通过。

## 2026-05-19 怪物动态攻击力接管

### 更新日志

- 修复怪物血量已动态变化但攻击力仍接近原版的问题。
- 怪物生成时新增 PDC `mob_attack_damage`，保存生态公式计算出的最终攻击力。
- `CombatManager` 在玩家被生态怪物攻击时，会优先读取攻击者 PDC 的 `mob_attack_damage` 覆盖原版事件伤害，再套玩家自定义护甲减伤。
- 末影人的基础攻击底数从属性值 `7` 修正为困难模式基础攻击约 `10`。因此 11 级末影人按公式为 `10 * (1 + 11 / 40) + 10 * 0.7 = 19.75`，符合预期约 20 点。
- 投射物伤害也支持读取 shooter 的生态攻击值，为后续骷髅/烈焰人等远程怪物预留一致路径。

### 验证记录

- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 构建通过。

## 2026-05-19 防具原版减伤剥离与护甲折损修复
### 更新日志

- 修复穿戴原版防具时仍然吃到 Minecraft 原版护甲减伤的问题。防具标准化不再只移除 `GENERIC_ARMOR` / `GENERIC_ARMOR_TOUGHNESS`，而是给对应槽位写入 0 值覆盖属性，让 Bukkit 伤害结算阶段看不到原版护甲与护甲韧性。
- 将 `ItemStandardizer.VANILLA_SCALE_VERSION` 提升到 `6`。旧的 5 倍防具会在玩家登录、打开容器、点击物品、拾取物品或插件启动扫描在线玩家背包时自动重新迁移。
- 修复下界合金/钻石防具单件护甲值被折损的问题。`PowerLevelManager.calculateArmorValue` 现在返回装备、饰品、护符包与属性加成提供的原始自定义护甲值，不再提前套用职业护甲惩罚。
- 下界合金套按原版 20 护甲 * 5 的过渡倍率，应在 ActionBar 与 `/sc stats` 生存面板中显示为 100 护甲，并按 `armor / (armor + 100)` 获得 50% 自定义减伤。

### 实现细节

- `ItemStandardizer` 新增 `neutralizeVanillaArmorAttributes`，按头盔、胸甲、护腿、靴子映射到 `HEAD`、`CHEST`、`LEGS`、`FEET`，分别写入唯一的 `neutralized_armor_<material>` 和 `neutralized_toughness_<material>` 修饰器。
- 原版防具的面板数值继续只写入 PDC `base_armor`，实际减伤统一由 `CombatManager` 读取 `PowerLevelManager.calculateDamageReduction` 后接管，避免原版减伤和自定义减伤重复叠加。
- 职业护甲惩罚逻辑暂时保留在 `ClassManager`，但不再影响当前护甲显示与 EHP 计算，避免“物品面板 15，穿上后只剩 10.5”的隐性折损。

### 验证记录

- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 构建通过，已生成新的 Shadow Jar。

## 2026-06-07 非战斗稀有掉落接入 Magic Find
### 更新日志

- 将 `GlobalStatManager.rollRareDrop()` 从战斗掉落扩展到非战斗稀有掉落触发判定。
- 伐木 Bounty、种植 Overbloom、钓鱼 Treasure 与采掘 Treasure 现在都会在触发率低于 5% 时吃 Magic Find，并让赌徒职业获得同一套稀有掉落二次判定。
- 保留原有等级门槛、tier 权重和条目权重；Magic Find 与赌徒只影响“是否触发掉落”，不改变后续表内选择。

### 实现细节

- `CollectionSkillManager` 新增统一的 `rollRareGatheringDrop`，Bounty、Overbloom 与 Excavation Treasure 的基础概率先按原逻辑计算，再交给 `GlobalStatManager.rollRareDrop()`。
- `FishingManager` 显式接收 `GlobalStatManager`，并在 Treasure Chance 判定处使用同一套稀有掉落逻辑；Sea Creature 与采掘召唤物仍保持原来的普通随机召唤逻辑。
- `GlobalStatManager.applyMagicFind` 的注释从“战斗稀有掉落”更新为通用“稀有掉落”，以匹配现在的调用范围。

### 验证记录

- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 构建通过。

## 2026-06-11 战力结构重构与武器规范收敛
### 更新日志

- 将战力语义拆分为即时 `TargetPower` 与自然刷怪使用的滑动 `SpawnPower`，旧的 `getCurrentPower()` 保留为兼容别名但语义改为 SpawnPower。
- `PowerLevelManager` 从旧 EDPH 主导公式改为 DPS 主导公式，按近战、远程、法术三路输出排序合成 `offenseScore`，再与 EHP、吸血、恢复、盾牌价值组合成 `survivalScore`。
- SpawnPower 改为非对称平滑：玩家变强时较快上升，脱装备后较慢下降，避免瞬间换装压低自然生态等级。
- `WeaponTemplateManager` 增加配置驱动 `WeaponProfile`，攻击速度、冷却、攻击范围、默认手持规则、可靠性、uptime、AoE 系数和盾牌默认值都从 `config.yml` 读取。
- 保留并复用已有主副手系统：副手 `BOTH_HANDS_ALLOWED` 属性 50% 生效、盾牌副手 100% 生效、双手武器在副手有物品时不可攻击或释放主动技能。
- `ShieldManager` 增加 `ShieldBlockResult` / `ShieldBlockType`，保留既有“盾牌先于护甲减伤结算”的链路，并向战力公式提供每秒盾牌价值估算。
- 吸血链路保留现有“排除暴击额外伤害”的有效伤害计算，并增加单次吸血上限，默认不超过最大生命 15%。
- `CustomMobRegistry` 支持 `level_mode`：`NATURAL_ADAPTIVE`、`FIXED`、`WORLD_CAP`、`AREA`、`ADAPTIVE_CLAMPED`；旧的 `override_power_level` / `power_level` / `level` 自动兼容为固定内容等级。
- `MobSpawnManager` 自然生成怪物改为读取附近玩家 `SpawnPower` 中位数；WDA、Boss、结构怪等已有 `override_power_level` 的规则继续保持固定内容等级。
- 新增轻量调试入口：`/sc debug power`、`/sc debug weapon`、`/sc debug shield`、`/sc debug damage`、`/sc debug moblevel <id>`。

### 实现细节

- 新增 `src/main/resources/config.yml`，默认包含 `power` 权重、SpawnPower 平滑参数、续航限制和所有主要 `weapon_templates`。
- `PowerBreakdown` 扩展为 DPS、输出评分、生存评分、续航拆解、TargetPower 和 SpawnPower 的完整结构，并保留旧字段别名方法，减少旧 UI/调用点震荡。
- `/sc stats` 的战力槽位现在优先显示即时 `TargetPower`，并把 `SpawnPower` 标记为自然生态采样值。
- 记分牌从“当前 / 理论”改为“理论 / 生态”，避免普通玩家把 SpawnPower 误解为真实面板战力。
- `custom_mobs.yml` 模板注释补充 `level_mode`、`base_level`、`player_scale`、`min_level`、`max_level` 写法，现有 WDA 固定等级配置无需批量迁移。

### 验证记录

- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 构建通过。
- 构建仅提示既有 `HologramManager` 中 `EntityRemoveEvent` 已过时；本次改动未触碰该清理兜底路径。

## 2026-06-11 WDA 怪物表收尾平衡与刷怪笼限制
### 更新日志

- 基于当前生态怪公式重新体检 `custom_mobs.yml` 的 WDA 固定等级表，重点压低低 base_hp 导致的攻击膨胀、远程怪爆发和精英怪血量偏薄问题。
- 收束明显离群项：Bandit Towers 刺客从高爆发纸身改为轻甲精英；Foundry 护卫队长和巨型岩浆怪从高攻怪改为更偏坦度；Shiraz Palace 精英骷髅/尸壳改为更稳的精英坦度曲线。
- Aviary 的高等级远程/飞行怪整体降攻补血，保留末地级结构压迫感，但避免远程怪的单次攻击明显高于同结构近战梯队。
- Coliseum、Illager Corsair、Infested Temple、Mushroom Mines、Undead Pirate Ship 等结构的少数离群精英同步调低攻击、补足血量，使同等级玩家面对同结构怪群时仍有压力但不被单只异常怪秒压。
- 为除修道院村民外的 WDA 规则显式补齐刷怪笼限制字段：`spawner_limit`、`spawner_check_radius`、`spawner_vertical_radius`、`spawner_limit_mode`。

### 实现细节

- 强怪稀疏结构采用更低上限与较大检测半径，例如铸造厂、飞空结构、船长/队长类单位通常限制在 2-6 只附近存活。
- 宫殿、塔楼、矿井、村落这类刷怪点密集或覆盖面积大的结构采用较小检测半径，并在普通同类型杂兵上使用 `spawner_limit_mode: type`，防止多个同类型变体各自刷满。
- `spawner_limit_mode: rule` 继续用于首领、精英和职责差异明显的怪，避免普通怪数量把关键精英刷怪笼完全压死。
- `WDA_Mon_Villager_0` 是非战斗展示生物，未加入怪物刷怪笼限制表；`WDA_Dungeon_Guard` 虽是历史示例规则，也补了 6/24/12 的保守限制。

### 验证记录

- 使用项目 Gradle 缓存中的 SnakeYAML 2.2 读取 `src/main/resources/custom_mobs.yml`，解析通过，顶层规则数为 155。
- 脚本复查 WDA 规则：除修道院村民外，所有 WDA 规则均已具备显式 `spawner_limit` 与 `spawner_limit_mode`。
- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 构建通过。

## 2026-06-12 配置驱动自定义附魔系统
### 更新日志

- 新增 `enchants.yml` 与 `enchant_pools.yml`，自定义附魔定义、enabled 开关、数值曲线、目标标签、机制参数和附魔台池子都改为配置驱动。
- `EnchantManager` 不再硬编码注册附魔，只保留 `id:level;id:level` 的 PDC 读写门面；旧物品 PDC 格式保持兼容。
- 附魔等级拆分为 `soft_max_level` 与硬上限 `max_level`：附魔台最多升到软上限，指令、掉落和稀有战利品等特殊来源可以给到不超过硬上限的等级。
- 新增 active 附魔读取：禁用附魔仍保留在旧物品 PDC 中，但不参与数值、Slayer 增伤或机制触发。
- `ItemFormatManager` 的附魔 lore 改为从注册表读取显示名、稀有度颜色、描述和禁用状态；未知附魔显示 raw id，禁用附魔显示灰色 `[已禁用]`。
- `CombatStats` 通过 `EnchantStatResolver` 动态读取普通/罕见附魔数值，移除了旧的 `vampirism` 固定吸血换算。
- `MiningManager`、`CollectionSkillManager`、`FishingManager` 的工具统计也会叠加 active 附魔 numeric，例如 `tool_mining_speed`、`mining_fortune`、`fishing_speed`、`sea_creature_chance`、`treasure_chance`。
- `EnchantTargetMatcher` 改为读取 YML 中的 `target + damage_to_target`，Slayer 类附魔不再固定每级 5%。
- 新增第一批机制效果：`cleave` 溅射、`vampirism` 稀有吸血、`perfect_guard` 完美格挡，以及 `ultimate_apex_slayer`、`ultimate_berserker_oath`、`ultimate_phoenix_core` 的第一版终极效果。
- 附魔台、铁砧、砂轮加入自定义附魔入口：附魔台从 pool 抽取 enabled 附魔，铁砧合并写入 PDC 并阻止 disabled 升级，砂轮可清空自定义附魔并返还部分经验/粉尘。
- 附魔台现在先验证再清空原版附魔结果；抽到已有附魔时只升级不降级，同等级再抽到会 +1，不可提升、冲突或达到软上限时取消本次附魔并提示玩家。
- 新增测试命令：`/sc reload enchants`、`/sc enchant list`、`/sc enchant give [player] <id> <level>`、`/sc enchant remove [player] <id>`、`/sc enchant clear [player]`、`/sc enchant debug`，并补充 `/sce` 别名。

### 实现细节

- `com.servercore.enchant` 包承载 `EnchantRegistry`、`EnchantDefinition`、`ValueCurve`、`EnchantStatResolver`、`EnchantEffectService` 和附魔台/铁砧/砂轮监听器，避免继续膨胀旧 `manager` 包。
- 数值曲线支持 `CONSTANT`、`LINEAR`、`PER_LEVEL`；`PER_LEVEL` 超出等级时沿用最后一级数值，方便只改 YAML 做平衡。
- 终极附魔默认一件装备最多 1 个，且 disabled 终极默认不计入上限，避免废弃附魔卡死旧装备。
- secondary damage 使用 `EnchantDamageContext` 标记，防止劈砍伤害再次触发劈砍、吸血或主战斗计算。
- `ShieldManager` 继续作为唯一盾牌格挡入口，`perfect_guard` 只在现有阈值/冷却链路上追加配置驱动加成。

### 验证记录

- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 构建通过。
- 构建仅提示 Bukkit 过时 API，其中本次铁砧监听器使用的 repair cost API 已过时；不影响当前附魔功能编译与打包。
