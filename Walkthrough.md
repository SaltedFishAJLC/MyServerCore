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

## 2026-06-16 附魔获取入口收尾
### 更新日志

- 审查现有商店/NPC/战利品代码后，确认仓库内没有独立 NPC/商店模块；本次复用 `/sc` 命令、Bukkit 背包 GUI、`CustomMobRegistry` 掉落和既有铁砧合并链路落地新入口。
- 新增玩家可用入口 `/sc enchant special <enchant_id> <level>`：按 `special_enchant_table` 校验启用状态、允许稀有度、硬等级上限、槽位、冲突和终极附魔数量，然后消耗粉尘与经验等级，对主手物品进行定向附魔。
- 新增玩家可用入口 `/sc enchant books`：打开轮换附魔书 GUI，读取 `npc_books.pools` 生成当前轮换 offer；购买后得到带自定义附魔 PDC 的 `ENCHANTED_BOOK`，可直接作为铁砧右侧材料合并到装备。
- 新增 `EnchantBookFactory` 统一生成自定义附魔书，避免轮换商店和掉落各自手写 PDC/lore。
- `EnchantPoolRegistry` 现在实际解析 `special_enchant_table` 与 `npc_books`，支持轮换数量、刷新小时、等级范围、粉尘费用和经验等级费用；RARE 默认每日轮换，ULTIMATE 默认三日轮换。
- `CustomMobRegistry` 掉落表支持 `enchant_book` / `enchant_id`，并支持 `level: 1-3` 这类等级范围；命中后生成可铁砧使用的自定义附魔书。
- `custom_mobs.yml` 已加入附魔书掉落示例：Foundry 护卫队长和幽灵船长掉落 RARE 书，Flame Boss 与 Ancient Warden 掉落 ULTIMATE 书，RARE / ULTIMATE 不再只能依赖调试命令。
- `enchants.yml` 为所有既有附魔补充 `# 效果：...` 注释，并在 `enchants:` 下写明后续新增附魔也必须补充效果注释。

### 实现细节

- 定向附魔消耗的“粉尘”兼容 `magic_dust`、现有配置中的 `gem_dust`，以及无自定义模板时的 `GLOWSTONE_DUST`，避免材料命名不一致导致入口不可用。
- 普通附魔台仍只受软上限 `soft_max_level` 限制；定向附魔、轮换附魔书和怪物掉落书按硬上限 `max_level` 处理。
- 轮换书按 pool id 与当前刷新窗口生成稳定随机结果，服务器重启不会在同一窗口内刷新出另一套商品。
- 自定义怪物掉落仍复用 `GlobalStatManager.rollRareDrop()`，稀有掉落概率继续吃 Magic Find 逻辑。

### 验证记录

- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 构建通过。
- `git diff --check` 未发现空白错误，仅提示当前工作区文件行尾会在 Git 触碰时由 LF 替换为 CRLF。

## 2026-06-16 附魔数值面板与文案修正
### 更新日志

- 物品面板的属性行现在会读取 `EnchantStatResolver.resolveNumeric()` 的动态附魔加成，并在基础属性后追加蓝色括号显示，例如 `伤害: +24 (+10)`。
- 如果某个属性完全来自附魔而基础物品没有该属性，面板会显示该属性名和蓝色括号加成，例如 `暴击伤害: (+20.0%)`，避免伪造一个 `+0` 基础值。
- 附魔说明中的线性数值附魔从“每级 +当前总值”改为“当前总计 +数值”，修正高等级附魔被误读为再次按等级累加的问题。
- 缺少具体数值说明的目标杀手、蓄势、采矿专精和终极附魔已补充当前数值/参数说明，方便玩家直接从 lore 判断实际效果。

### 实现细节

- 面板显示复用现有附魔注册表、启用状态过滤和数值曲线解析；禁用附魔、未知附魔不会额外显示为有效属性。
- 顶部属性面板只展示可归入现有物品属性键的数值加成；对特定目标增伤、满蓄力伤害等情境加成仍保留在对应附魔 lore 中说明。

### 验证记录

- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 构建通过。
- 构建仅提示既有铁砧 repair cost API 过时警告；本次面板显示和 YAML 文案调整编译通过。
- 构建仅提示既有 `EntityRemoveEvent`、铁砧 repair cost API 过时警告；本次新增入口编译与打包通过。

## 2026-06-16 砂轮单条拆除收尾
### 更新日志

- `EnchantGrindstoneListener` 保留原版砂轮单槽结果的 clear-all 行为：放入带自定义附魔的物品后，取出结果会清空全部自定义附魔，并继续按 `grindstone.clear_all` 比例返还经验与魔尘。
- 新增“按附魔单独拆除”流程：玩家潜行右键砂轮打开 `附魔拆除` GUI，左侧放入装备，中间列出该装备上的每个自定义附魔；点击某个条目会只移除该附魔。
- 单条拆除现在真正读取 `grindstone.remove_single.<RARITY>.dust_per_level`，按附魔等级计算魔尘消耗；普通、罕见、稀有附魔只消耗魔尘。
- 终极附魔拆除现在真正读取 `require_special_material`，默认额外需要 `ultimate_enchant_catalyst`；材料不足时不会移除附魔，也不会扣除魔尘。
- `EnchantPoolRegistry` 增加单条拆除费用与特殊材料查询接口，clear-all 返还仍沿用原有 `dustRefund` / `expRefund` 路径，避免破坏旧砂轮逻辑。
- `custom_items.yml` 新增 `magic_dust` 与 `ultimate_enchant_catalyst`，`recipes.yml` 增加默认制作配方，`recycle.yml` 增加回收价格。

### 实现细节

- 单条拆除 GUI 关闭时会把左侧槽位中的装备返还到玩家背包，背包满时掉落在玩家位置，避免吞物品。
- 魔尘兼容 `magic_dust`、历史 `gem_dust` 和无自定义物品时的原版 `GLOWSTONE_DUST`；clear-all 返还现在会按堆拆分，避免返还数量超过 64 时丢失。
- 终极附魔特殊材料通过 `special_material_item_id` / `special_material_amount` 配置，后续可以直接换成更稀有的掉落材料。

### 验证记录

- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 构建通过。
- 构建仅提示既有铁砧 repair cost API 过时警告；本次砂轮 GUI 与配置解析编译通过。

## 2026-06-16 魔法粉尘循环总收口
### 更新日志

- 审查 `enchant_pools.yml`、`EnchantPoolRegistry`、`EnchantGrindstoneListener`、`EnchantTableListener`、`EnchantManager` 与 `custom_items.yml` 后，确认此前未闭合点集中在粉尘物品定义、单条拆除费用、终极拆除材料、定向附魔消耗与轮换附魔书购买链路。
- `magic_dust` 已作为正式自定义物品落入 `custom_items.yml`，并补充默认配方与回收价格；砂轮返还、定向附魔和轮换书购买都优先走该自定义物品。
- `grindstone.remove_single` 已由 `EnchantPoolRegistry` 解析为单条拆除规则：按稀有度读取 `dust_per_level`，并支持 `require_special_material`、`special_material_item_id` 与 `special_material_amount`。
- 单条拆除通过潜行右键砂轮打开 `附魔拆除` GUI；左槽放装备，点击附魔条目后只移除该附魔，并按等级扣除魔尘。终极附魔默认额外消耗 `ultimate_enchant_catalyst`。
- clear-all 砂轮路径保持兼容：普通砂轮取结果仍会清空全部自定义附魔并返还经验/魔尘；取出阶段现在和 prepare 阶段使用同一套“寻找带自定义附魔源物品”逻辑，避免双槽场景返还错位。
- `special_enchant_table` 已接入 `/sc enchant special <enchant_id> <level>`，完整走启用状态、稀有度、硬上限、槽位、冲突校验、魔尘/经验消耗与 lore 刷新。
- `npc_books` 已接入 `/sc enchant books` 轮换书 GUI，按 pool 刷新窗口生成 RARE / ULTIMATE 附魔书 offer，购买时消耗魔尘与经验等级并产出可铁砧合并的自定义附魔书。

### 第一版限制

- 定向附魔暂时是命令入口，不是实体方块 GUI；轮换附魔书暂时是命令打开的商店 GUI，不是正式 NPC 对话/交易系统。
- 魔尘识别仍兼容历史 `gem_dust` 与无模板时的 `GLOWSTONE_DUST`，用于迁移旧物品和旧配置；新产出优先使用正式 `magic_dust`。
- `recipes.yml`、`custom_items.yml` 等默认资源只会在新数据文件生成时自动复制；已经部署过的服务器需要手动合并或执行对应 reload/重新生成流程。

### 验证记录

- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 构建通过。
- `git diff --check` 未发现空白错误，仅提示当前工作区文件行尾会在 Git 触碰时由 LF 替换为 CRLF。

## 2026-06-17 铁砧软上限合并修正
### 更新日志

- 铁砧合并自定义附魔时，现在把 `soft_max_level` 作为普通合并升级的最高等级；两件同级物品或两本同级附魔书不能再通过铁砧把等级推到软上限以上。
- 超过软上限但不超过 `max_level` 的附魔，仍可通过特殊来源的高等级附魔书敲到装备上，保留“特殊掉落/商店书 -> 装备”的高阶入口。
- 自定义装备模板自带的高等级附魔仍按硬上限生效；左侧装备已有的高等级附魔也会保留，但不能靠同级合并继续升级。

### 实现细节

- `EnchantAnvilListener` 现在区分左右物品是否为附魔书：只有“左侧为装备、右侧为附魔书，并且右侧书本身提供目标高等级”时，铁砧才允许产出软上限以上的等级。
- `III + III -> IV` 这类会越过软上限的合并会被拒绝；`右侧 IV 书 -> 左侧装备` 这类特殊书应用会被允许，前提是仍通过槽位、冲突、终极附魔数量和硬上限校验。

### 验证记录

- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 构建通过。
- 构建仅提示既有铁砧 repair cost API 过时警告；本次铁砧软上限规则调整编译通过。

## 2026-06-17 吸血统计收束
### 更新日志

- `vampirism` / `嗜血` 附魔从独立 `VAMPIRISM` 治疗效果改为 `lifesteal` 数值加成，进入物品面板、`CombatStats`、战力计算和统一吸血上限。
- 血魔职业的基础 2.5% 吸血现在并入 `CombatStats.lifesteal`；血魔“总吸血效果翻倍”保留为最终倍率，放大装备、宝石、附魔和职业基础等所有吸血来源。
- 旧配置中仍写着 `effect: VAMPIRISM` / `heal_ratio` 的嗜血类附魔会被 `EnchantStatResolver` 兼容折算为 `lifesteal`，避免迁移时完全失效。

### 实现细节

- `EnchantEffectService` 不再在命中后单独触发嗜血回血，避免嗜血同时走独立治疗和统计吸血造成双算。
- 统一吸血仍只在现有近战允许路径生效，并继续受 `power.sustain.max_lifesteal_per_hit_ratio` 单次吸血上限控制。
- `enchants.yml` 的嗜血说明改为“当前总计 +X% 吸血”；硬上限等级超出已配置曲线时仍沿用 `PER_LEVEL` 的最后一档数值。

### 验证记录

- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 构建通过。
- 构建仅提示既有过时 API；本次吸血统计收束编译通过。

## 2026-06-18 特殊附魔台、赋能射击与武器附魔扩展
### 更新日志

- 特殊附魔台从命令入口扩展为可交互 GUI：玩家潜行右键原版附魔台打开 `定向附魔台`，左侧放入装备后，界面会列出该物品可继续提升的 COMMON / UNCOMMON 附魔。
- 定向附魔台复用 `special_enchant_table` 的稀有度白名单与费用配置，每次点击把目标附魔提升 1 级，最高不超过 `soft_max_level`，并完整走槽位、冲突、终极限制、魔尘/经验消耗和物品 lore 刷新。
- 新增远程武器主动状态 `赋能射击`：手持长弓、短弓或弩时 Shift+左键切换；开启后射出的箭命中时按飞行距离计算 +1.5%/格伤害，并消耗 1.5 魔力/格，魔力不足时按可支付距离向上取整计算增伤。
- `Ultimate Wise / 究极之智` 已接入赋能射击耗魔折扣，当前等级分别降低 10%/20%/30%/40%/50% 魔力消耗。
- `Rend / 撕裂` 作为第一版主动技能接入：带 Rend 的远程武器 Shift+左键不再切换赋能射击，而是清除这把武器命中过目标留下的箭矢标记并造成 secondary damage，单目标最多 7 次，冷却 5 秒。
- 附魔模型新增显式 `conflicts` 列表，补足“Chain Lightning 与 Cleave/ThunderBolt 互斥，但 ThunderBolt 可和 Cleave 共存”这类无法只靠单个 `conflict_group` 表达的关系。
- `EnchantSlot` 增加 `SHORTBOW`、`LONGBOW`、`CROSSBOW`、`TWO_HANDED_MELEE`，新附魔可以精确限制到长弓、弩或双手近战武器。
- `One For All / 以一镇万` 已接入：应用时清空武器上除自身外的所有自定义附魔，且带有该附魔的武器不能再追加其他附魔；面板伤害显示蓝色括号的 +150% 基础伤害加成。
- `Mana Steal / 魔力汲取`、`Drain / 饮血`、`Fire Aspect / 火舌`、`Anatomy / 解剖`、`Scavenger / 野蛮`、`First Strike / 先发制人`、`Triple Strike / 三连击`、`Chain Lightning / 连锁闪电`、`ThunderBolt / 雷击`、`Knockback / 击退`、`Ruthless / 冷酷`、`Antigravity / 反重力`、`Infinite Quiver / 无尽`、`Overload / 超载`、`Power / 力量`、`Punch / 冲击`、`Combo / 以战养战`、`Soul Eater / 灵魂收割`、`Swarm / 困兽之斗`、`Execute / 血腥屠戮` 已接入当前战斗或经济链路。
- 吸血和吸蓝都加上 0.75 秒触发冷却：职业基础吸血、Vampirism 面板吸血等统一走 `ClassPassiveManager.applyLifesteal` 冷却；Mana Steal 走独立吸蓝冷却；Drain 击杀治疗也受 0.75 秒门槛限制。
- `Scavenger` 现在会放大生态击杀金币奖励；`Infinite Quiver` 会在远程消耗箭矢判定中按等级提供不消耗概率；`Overload` 会把 100% 以上暴击率按等级转为暴击伤害。
- `enchant_pools.yml` 的 RARE / ULTIMATE 轮换书等级上限提升到 5，并继续由每个附魔自己的 `max_level` 截断，让高于软上限的特殊书有实际产出入口。
- `enchants.yml` 新增本批武器、长弓、弩、终极附魔定义，并为每个新增附魔补充 `# 效果：...` 注释和玩家可读说明。

### 第一版限制

- 特殊附魔台第一版是“顺序提升 1 级”的指定附魔 GUI，还不是同一附魔直接选择任意目标等级的多按钮版本。
- `Fire Aspect` 第一版用火焰视觉和一次性 magic secondary damage 表达点燃总伤害，尚未拆成逐秒 DoT 状态。
- 长弓/弩中需要复杂蓄力、移动检测、视野标记、追踪弹道、未命中惩罚或额外箭矢 AI 的附魔已完成配置、槽位和互斥入口；其中 `Full Draw`、`Cloudpiercer` 已有简化伤害分支，`Rend` 已有主动第一版，其余需要后续专门补弹道状态机。
- `Mana Steal` 通过 AuraSkills 用户对象反射读写当前魔力；如果 AuraSkills API 在运行端没有 `getMana/setMana` 或 `getCurrentMana/setCurrentMana`，该效果会安全失败为不恢复魔力，需要按实际服务端 API 名称补适配。

### 验证记录

- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 构建通过。
- 构建仅提示既有 `EntityRemoveEvent`、铁砧 repair cost API 过时警告；本次特殊附魔台、赋能射击和附魔扩展编译与打包通过。

## 2026-06-18 钓鱼系统 P0 整合修复
### 更新日志

- 修复海怪概率命中但当前等级没有合格条目时吞掉原渔获的问题：现在先选择条目并确认海怪成功生成，失败时继续进入宝藏判定。
- 海怪生成改为统一走 `MobSpawnManager#spawnFishingSeaCreature()`；同步生成期间跳过通用自然怪物缩放，之后一次性写入生命、攻击、减伤、魔抗、战斗等级、标签和全息。
- 海怪生命复用现有虚拟血池链路；理论生命超过实体 Attribute 可承载上限时，实际总血量写入虚拟血量 PDC，并由全息显示。
- 明确 MythicMobs 所有权：MythicMobs 负责实体类型、AI 和技能，ServerCore 负责全部战斗数值与显示。
- 钓鱼属性从“仅主手”扩展为主手、护甲、4 个饰品槽和护符袋统一聚合；副手不生效。每件物品的 active 附魔数值继续通过 `EnchantStatResolver` 临时计算。
- `/sc gathering` 钓鱼面板现在按主手、护甲、饰品槽、护符袋拆分 Fishing Speed、Sea Creature Chance 和 Treasure Chance，默认 `gatherers_compass` 的宝藏概率正式进入实际计算。
- 自定义海怪/宝藏结果改为两阶段收尾：AuraSkills 2.3.12 先在 `MONITOR` 读取原始渔获并发放一次常规 Fishing XP，ServerCore 后注册的 `MONITOR` 监听器再移除或替换物品；条目 `xp` 明确作为额外奖励。

### 验证记录

- 对照 AuraSkills 2.3.12 官方源码确认 `FishingLeveler` 使用 `PlayerFishEvent` 的 `MONITOR` 优先级，并读取当时的 ItemStack。
- `.\gradle-8.5\bin\gradle.bat --no-daemon compileJava` 编译通过。
- 仓库当前没有可构造 Paper 钓鱼事件、AuraSkills 和 MythicMobs 联动的自动化测试缝；真实服务器仍需验证普通鱼、海怪、三个宝藏档位和 fallback 分支的 XP 记录。

## 2026-06-18 印记、套装与统一装备被动系统

### 更新日志

- `/sc acc` 新增独立“印记”槽、印记状态和被动总览入口；印记单独保存到玩家 PDC，不扩充原有四饰品数组。
- 印记只启用明确声明的 `PASSIVE` 和套装部件身份，物品基础数值、重铸、宝石、附魔、武器模板属性与主动技能全部被屏蔽。
- 印记支持 `accessory_type: IMPRINT` 或 `imprint_eligible: true`；没有已实现被动且没有有效套装身份的物品不能放入。
- 印记死亡后保留原位，战斗中允许即时切换；换装、死亡、重登和服务器重启均不会刷新能力冷却。
- 新增 `equipment_sets.yml`：支持 `set_id`、`set_piece_id`、部件去重、`CUMULATIVE` / `HIGHEST_ONLY` 阈值和每阈值多个被动。
- 新增 `passive_abilities.yml`、`PassiveAbilityRegistry`、`PassiveSnapshotService` 和 `AbilityCooldownService`，把武器、盔甲、普通饰品、护符、印记与套装统一到同一被动快照。
- 首版实现 `stat_bonus`、`damage_reduction`、`outgoing_multiplier`、`on_hit_damage`、`revive` 五种处理器；被动追加伤害默认通过内部伤害链防止递归触发。
- 主动物品能力和凤凰核心附魔迁移到玩家 PDC 持久冷却；致死时最多触发一个可用复活效果。
- 护符包现在只接受已注册 `TALISMAN`，每格一个，相同 `item_id` 不可重复；同 `talisman_family` 只启用稀有度最高、优先级最高的版本。
- 战斗属性、五维属性、护甲、攻速、暴击伤害和钓鱼属性均改为只扫描有效护符；低稀有度同系列护符的数值和被动一起停用。
- 饰品和护符界面改为专用 `InventoryHolder` 与即时保存，封堵 Shift、数字键、双击和拖拽旁路。
- 新增 `/sc debug passive [玩家]`、`/sc debug set [玩家]`、`/sc debug imprint [玩家]`，并提供管理员测试印记、护符和三件测试套装。
- 新增 `docs/equipment-system-status.md`，记录当前实现、设计约定、配置格式、限制与测试服验收清单。

### 第一版限制

- 周期调度、状态持久化、取下时取消限时效果等扩展位已经明确，但首版尚无对应复杂处理器。
- 当前只有五种通用被动处理器；正式套装的专属状态机和正式内容仍需逐个实现。
- 仓库没有 Paper 事件自动化测试环境，GUI 原子交换、死亡保留、重启后冷却和多人战斗切换仍需测试服验证。

### 验证记录

- `.\gradle-8.5\bin\gradle.bat --no-daemon compileJava` 编译通过。
- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 完整打包通过。
- 既有 `EntityRemoveEvent` 与铁砧 repair cost API 过时警告不属于本次回归。

## 2026-06-19 武器与防具附魔扩展

### 更新日志

- `enchants.yml` 新增 39 个武器/防具附魔定义，附魔总数由 65 增加到 104；保留用户已有的 Protection 每级 10 护甲、Efficiency 显示名和两个禁用长弓终极附魔改动。
- `Fortify / 坚固` 的玩家显示名更名为 `Protection / 保护`，并与 Projectile Protection 互斥；底层继续规范化为旧 id `fortify`，历史物品和命令别名 `protection` 均可读取。
- 附魔定义新增 `table_max_level` 与 `table_obtainable`：Feather Falling 的软/硬上限保持 10，但普通附魔台最高 5；Frost Walker、Soul Speed、Walk Thru Fire 不会从普通附魔台抽取。
- 新增 `EquipmentEnchantService`，统一处理 Chimera 印记属性继承、过去 10 秒耗魔窗口、临时护甲、延迟治疗、受击返伤、阈值回蓝、客户端轮廓、移动状态和环境效果。
- Protection 的 `base_armor` 已正式进入 `PowerLevelManager` 护甲与减伤公式；Projectile Protection、Nimble Evasion 只在弹射物伤害结算时加入额外护甲。
- Growth、Big Brain、Smarty Pants、Reflection、Rejuvenate、Respite、Sugar Rush、Lightweight 和 Insights 已进入最大生命、最大魔力、五维、自然回复与 1.21 玩家属性链路。
- Meditation、Refrigerate、Ferocious Mana、Mana Rebound 与 Arcane Buffer 统一读取 AuraSkills 能力耗魔和 ServerCore 主动耗魔；所有实际数值从 `enchants.yml` 的曲线读取。
- Thorns 与 Reflection 使用实际结算伤害生成 secondary damage，避免返伤递归；Counter Strike、Emergency Reserve、Adaptive Plating、Metallicize、Mind Fortress、Last Stand、No Pain No Gain 和 Shade Step 已接入受伤前后状态。
- Hunter's Sense 使用玩家定向的客户端发光效果，只让受击玩家看到攻击者轮廓，对隐身目标同样发送。
- Frost Walker 与 Soul Speed 会同步对应原版附魔并隐藏原版 tooltip；Depth Strider、Aqua Affinity、Swift Sneak、Feather Falling、Lightweight 等使用 Paper 1.21 原生 Attribute。
- Bank 会按多件防具叠加降低死亡金币损失，并配合 `PlayerDeathEvent#setKeepInventory(true)` 只保留带 Bank 的防具，其余物品仍进入原有灵魂容器。
- Legion 统计 30 格内玩家（含自己）并动态放大单件防具的战斗面板，不放大五维；Coolheaded、Refrigerate、Metallicize 和 Counter Strike 的临时护甲进入同一护甲公式。
- Chimera 动态读取印记物品的 PDC 面板和 numeric 附魔数值；印记的主动技能、统一被动和套装身份不会被复制。
- `docs/enchant-system-status.md` 已更新到 2026-06-19，记录本批实现、兼容约定、获取限制和仍需测试服验证的边界。

### 验证记录

- `.\gradle-8.5\bin\gradle.bat --no-daemon compileJava` 编译通过。
- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 完整打包通过，最终复验为 `BUILD SUCCESSFUL in 27s`。
- 使用 Gradle 缓存中的 SnakeYAML 2.2 实际解析 `src/main/resources/enchants.yml`，确认共有 104 个定义并包含 `shade_step` 等本批末尾条目。
- 本仓库检出不存在 `plugins/ServerCore/enchants.yml`，因此没有运行时文件覆盖本批资源配置；已有服务器部署时仍需手动合并配置。
- 仓库没有 Paper 玩家移动、AuraSkills 魔力、死亡容器和多人战斗的自动化测试夹具；完整打包后仍需在测试服验证水下属性、客户端轮廓、Bank 保留、Legion 层数与各类受击顺序。

## 2026-06-21 数值体系 P0 统一

### 更新日志

- `CombatManager` 收束为攻击者侧计算器；护盾、护甲、魔抗、标签抗性、DOT 上限、承伤附魔、统一被动、灾厄血池、守护者代偿和致死保护统一交给 `DamageService`。
- 原生攻击继续修改同一个 Bukkit 事件，状态、环境和 secondary damage 继续使用主动扣血入口；两种入口共用同一目标侧解析器。
- 新增 `DamagePlan` 两阶段提交：伤害计算阶段只生成触发计划，`MONITOR` 确认事件未取消后才消耗护盾冷却、Perfect Guard、Arcane Buffer 魔力、Shade Step、灾厄血池和进攻附魔状态。
- 攻击上下文会锁定武器、附魔、暴击、吸血率和目标受击前有效生命；First Strike、Triple Strike、Thunderbolt、Apex Slayer、刺客伏击和赋能射击不再因后续取消事件而提前消耗。
- 吸血、Cleave、连锁闪电、Mana Steal、Rend 标记和统一被动追击移至成功命中的后置阶段，并以最终实际伤害为基数；过量伤害不计入，伤害吸收生命计入。
- 实战与战力魔法公式统一为 `基础增伤 + (最大魔力 - 100) / 400 + 职业魔法加成`。
- 实战与战力远程破甲统一封顶 100 点，最高形成 2 倍乘区；玩家护甲减伤统一封顶 95%。
- 护盾仅拦截近战、弹射物、爆炸和直接法术，不处理真实、DOT、状态、环境或系统伤害。
- 真实伤害跳过普通防御和承伤修正，但带 DOT 标签时仍受 DOT 上限，并允许被动复活或凤凰核心救下。
- 闪避仅适用于近战、弹射物和直接法术，不再闪避爆炸、AOE、DOT、环境或系统伤害。
- 灾厄血池改为在普通减伤后吸收剩余物理/魔法伤害；守护者随后转移队友 25% 最终实际伤害，转移伤害不会连锁，但守护者自身仍可复活。
- 战力护盾价值正式乘以 `power.sustain.shield_weight`，默认 0.50，并兼容旧运行时键 `shield_spawn_weight`。
- 核实神射手箭矢吸附已由 `RangedWeaponManager` 实现，并同步修正 `docs/numerical-systems-status.md` 的状态与 P0 清单。

### 验证记录

- `.\gradle-8.5\bin\gradle.bat --no-daemon compileJava` 编译通过。
- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 完整构建通过，最终复验为 `BUILD SUCCESSFUL in 19s`。

## 2026-06-21 T6 装备、绯红炼狱套装与通用流血

### 更新日志

- `custom_items.yml` 新增六件 T6 战斗测试装备：上古之冠、绯红炼狱头盔/胸甲/护腿/战靴和猩红征伐巨剑；完整写入用户指定的 HP、ATK、DEF、暴击、残暴、五维、自带附魔、宝石槽与双手剑模板。
- 自定义装备新增 `equipment_tier: 1-7` 元数据，保存到 `item_equipment_tier` PDC，并在物品 Lore 显示装备阶段。
- 新增装备基础 `max_health` PDC；正常装备、合法主副手、普通饰品、有效护符和 `stat_bonus` 可提供直接最大生命，印记仍严格屏蔽基础面板。
- 上古之冠使用下界合金头盔、金色 Flow 纹饰并允许放入印记；“鲜血渴望”在正常穿戴或印记来源下均可启用。
- 绯红炼狱四件使用橙红皮革、红石 Eye 纹饰和无限耐久；头盔自带 Hunter's Sense III，战靴自带 Walk Thru Fire I 与 Soul Speed III。
- 绯红炼狱 2 件效果通过 `missing_health_set_stat` 动态计算，只放大当前正常穿戴的本套装部件攻击力，每损失 1% 生命提高 0.3%。
- 绯红炼狱 4 件“肃杀之气”通过 `dominus_sword_aura` 管理 Dominus：敌对生物击杀叠至 10 层，停杀 5 秒后每 5 秒衰减；近战命中按每层 10% 概率释放 8 格剑气，以防递归真实次级伤害造成该次实际伤害的 75%。
- 猩红征伐巨剑使用神话双手剑模板、400 ATK、25% 暴击、45% 暴伤、25 残暴、三个通用宝石槽，并自带行刑者 X 与饮血 V。
- `StatusService` 新增统一 `tryApplyBleed(source, target, chance, totalDamage, durationTicks, reason)`；每次流血独立保存来源和伤害池，并发布可修改/取消的 `BleedApplyEvent` 与实际伤害 `BleedDamageEvent`。
- 巨剑被动以 `30% / 10 秒 / 400 总伤害` 调用通用流血；鲜血渴望按实际流血伤害恢复，每秒最多最大生命 15%，经过普通治疗倍率但不调用血魔吸血翻倍。
- `docs/equipment-system-status.md` 与 `docs/numerical-systems-status.md` 已同步 T6 内容、公式、调试命令和测试服验收项。

### 验证记录

- 三轮中间 `compileJava` 均通过，用于分别验证基础 HP/装备阶段、通用流血事件和复杂被动处理器接线。
- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 完整构建通过，产物为 `build/libs/ServerCore-1.0.0-SNAPSHOT.jar`。
- 使用 Gradle 缓存中的 SnakeYAML 2.2 实际解析 `custom_items.yml`、`passive_abilities.yml` 和 `equipment_sets.yml`，三份配置均成功。
- 配置断言确认六件装备均为 T6，四件套基础总计为 `725 HP / 175 ATK / 870 DEF`，巨剑为 400 ATK 双手剑。
- 仓库没有可运行的 Paper 战斗自动化夹具；Dominus 概率/范围、流血实际节拍、印记鲜血渴望与纹饰外观仍需测试服按文档清单验证。

## 2026-06-21 `/sc` 指令大全文档

### 更新日志

- 新增 `docs/servercore-command-reference.md`，从 `plugin.yml` 与 `ServerCorePlugin` 实际命令分支整理完整 `/sc` 命令面。
- 文档覆盖根命令别名、玩家 GUI、配方查询、附魔书商店、管理员重载、怪物生成、唯一生成重置、自定义物品、稀有度、重铸、宝石、底层物品属性编辑、附魔管理和调试命令。
- 单独记录 `/sc item` 支持的战斗、五维、盾牌、采集和钓鱼属性别名及数值单位。
- 标明全部命令仅限玩家执行、管理员权限节点为 `servercore.admin`、当前没有 Tab 补全，以及运行时 YAML 不会自动同步资源模板。
- 补充 T6 装备发放、套装调试、印记检查和临时测试物品的可复制命令示例。

### 验证记录

- 逐段核对唯一命令执行器中的参数长度、别名、权限判断和目标玩家处理逻辑。
- 本次仅新增和更新 Markdown 文档，没有修改 Java 或 YAML 运行逻辑，因此未重复执行 Gradle 构建。

## 2026-06-21 WDA 结构怪物区块加载补偿

### 更新日志

- 确认指令召唤的 `WDA_IC_1_Normal_0` 属性正常，而结构随区块生成的同标签实体没有属性；根因是现代 Paper 不再为区块生成实体触发 `CreatureSpawnEvent` 的 `CHUNK_GEN` 路径。
- `MobSpawnManager` 新增 `EntitiesLoadEvent` 与 `ChunkLoadEvent.isNewChunk()` 双入口：磁盘实体加载和新区块结构实体均在延迟一 tick 后扫描敌对生物，并复用现有 `custom_mobs.yml` 标签识别、属性计算、PDC 写入和全息生成链路。
- 新增管理器启动时的已加载实体扫描，确保插件重载或热替换后，当前已加载区块中的结构怪也能补齐属性。
- 补偿路径以 `KEY_MOB_POWER_LEVEL` 为幂等标记；已经完成 ServerCore 初始化的怪物不会重复缩放或重复生成全息。

### 验证记录

- `.\gradle-8.5\bin\gradle.bat --no-daemon compileJava` 编译通过。
- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 完整构建通过，产物为 `build/libs/ServerCore-1.0.0-SNAPSHOT.jar`。
- 仓库没有可直接启动的 Paper 集成测试夹具；最终需在测试服卸载并重新加载含 WDA 结构怪的区块，确认名称、等级、血量、攻击和全息均已应用。

## 2026-06-30 远程武器近战、DOT 无敌帧、套装与管理员附魔收口

### 更新日志

- 修复拿弓/弩左键近战会结算弓面板伤害的问题；根因是 `CombatManager` 的玩家近战分支只区分 damager 是否为 Projectile，没有排除主手远程武器模板。现在非投射物事件中若主手模板为纯远程，会取消本次近战事件并提示必须通过投射物造成伤害。
- 收口 `DamageService` 的内部扣血无敌帧规则：内部伤害应用前仍会临时清零 `noDamageTicks`，避免持续伤害被原版无敌帧吞掉；但只有 `CUSTOM_STATUS`、`VANILLA_STATUS`、`DOT` 或 `STATUS` 标签会在结算后恢复旧 tick。
- 行为边界：lava/fire/hot floor/campfire/lightning/explosion 这类环境或爆炸内部伤害会保留原版受击冷却；fire tick/poison/wither 经 `StatusService` 转为状态 tick，bleed 走 `CUSTOM_STATUS + DOT + STATUS`，结算后恢复旧 tick；直接近战和玩家投射物仍走原 Bukkit 事件路径，不被内部 DOT 恢复逻辑改节奏。
- 核对 `custom_items.yml` 所有 `set_id`：`test_warden`、`crimson_inferno` 与新增的 `frontier_guard`、`steelwall_guard`、`wind_hunter`、`nightwalker`、`astral_scholar`、`cobalt_vanguard`、`bedrock_bulwark`、`dusk_hunter`、`windfeather_ranger` 均已在 `equipment_sets.yml` 定义。
- 为新增 T1-T4/T3 套装补齐 2 件/4 件阈值，并在 `passive_abilities.yml` 注册对应 `stat_bonus`、`damage_reduction`、`outgoing_multiplier` 被动；保留印记只启用 PASSIVE 与套装身份、不复制基础面板/附魔/宝石/武器模板属性的契约。
- 附魔台定向 GUI 增加管理员作弊模式按钮，检测 `sc.admin` 或 `servercore.admin`；管理员可无消耗选择所有 enabled 且适用于该物品槽位的附魔，允许超过软上限但不超过硬上限，并旁路普通池、稀有度、冲突组和终极数量限制。
- 砂轮单独拆除 GUI 增加管理员作弊拆除按钮；管理员模式下删除单条自定义附魔不消耗魔尘或终极材料。
- 更新 `docs/equipment-system-status.md`，新增 `docs/equipment-set-test-commands.md`，列出当前套装、推荐搭配装备、发放指令、管理员附魔/砂轮入口和多人测试验收建议。

### 验证记录

- `custom_items.yml` 中所有唯一 `set_id` 与 `equipment_sets.yml` 顶层套装 id 对照，缺失列表为空。
- 使用 SnakeYAML 2.2 实际解析 `custom_items.yml`、`passive_abilities.yml`、`equipment_sets.yml`，三份配置均成功。
- `.\gradle-8.5\bin\gradle.bat --no-daemon compileJava` 编译通过。
- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 完整构建通过，最终结果为 `BUILD SUCCESSFUL in 15s`。
- 仓库没有可直接启动的 Paper 交互测试夹具；附魔台按钮、砂轮按钮、弓左键取消、lava/fire tick/poison/wither/bleed/近战节奏仍需在测试服按新文档实测。

## 2026-07-06 钓鱼等待公式、钓鱼套装与海怪材料路线

### 更新日志

- `FishingManager` 等待时间公式保持递减收益曲线，但将满配硬下限从 `10-80 ticks @ 1400 Fishing Speed` 调整为 `15-60 ticks @ 1500 Fishing Speed`；`/sc gathering` 继续通过同一 `calculateWaitWindow()` 显示新窗口。
- `docs/fishing-system-status.md` 同步新公式、示例表、验收目标和第一批钓鱼装备路线：海怪线为荷叶 -> 乌贼 -> 墨灵 -> 鲨鱼 -> 激流 -> 领主，宝藏线为荷叶 -> 海绵 -> 潜水员 -> 深渊 -> 潮藏。
- `custom_items.yml` 补齐钓鱼材料元数据与新材料：墨灵残膜、暗潮墨核、鲨齿、潮唤者鳃片、激流鳞核、失落罗盘、利维坦王鳞、海王密钥、利维坦诱饵碎片；新增 10 套共 40 件钓鱼防具，材料和装备均记录阶段、路线、主/副来源和用途。
- `equipment_sets.yml` 与 `passive_abilities.yml` 为 10 套钓鱼防具注册 2 件/4 件 `stat_bonus`，只做路线数值强化，不接入复杂套装技能或方尖碑逻辑。
- `gathering_loot.yml` 扩展钓鱼宝藏表，在现有 rare/epic/legendary 三档内用 `min_fishing_level` 表达 T1-T6 解锁；高阶核心材料均保留双来源，海怪材料也有低效率宝藏副来源。
- `custom_mobs.yml` 新增 `sea_creature_<entry_id>` PDC 匹配掉落规则，使钓出的 Deep Drowned、Reef Guardian、Abyss Guardian、Storm Eel、Elder Tidecaller、Leviathan Echo 击杀后能产出对应材料。
- `recipes.yml` 新增利维坦诱饵碎片合成核心配方，并为每个钓鱼防具部位提供独立配方；升级配方使用上一阶同部位作为中心基底，保留现有配方系统的成长状态迁移能力。

### 验证记录

- 使用 SnakeYAML 2.2 解析 `custom_items.yml`、`gathering_loot.yml`、`custom_mobs.yml`、`equipment_sets.yml`、`passive_abilities.yml`、`recipes.yml`，六份配置均成功。
- 配置引用检查确认新增配方、宝藏、海怪掉落、套装和被动均能找到目标 ID；唯一缺失引用仍是既有 `Mythic_Flame_Boss -> foundry_flame_sword`，本次未改动。
- `.\gradle-8.5\bin\gradle.bat --no-daemon compileJava` 编译通过。
- `.\gradle-8.5\bin\gradle.bat --no-daemon build` 完整构建通过，最终结果为 `BUILD SUCCESSFUL in 11s`。
- 仓库没有可直接启动的 Paper 集成测试夹具；实际钓鱼触发、海怪击杀掉落、合成界面和 T6 潮藏套触达 `15-60 ticks` 仍需测试服验收。
