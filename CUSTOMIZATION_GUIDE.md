# ServerCore 自定义内容流程

这份文档汇总当前插件内置的四类自定义内容：自定义物品、自定义配方、自定义生物、自定义战利品表。所有运行时配置都会位于 `plugins/ServerCore/` 下；`src/main/resources/` 里的同名 YAML 只是首次启动时复制出来的默认模板。

## 1. 自定义物品

运行时文件：`plugins/ServerCore/custom_items.yml`

重载与管理：

```text
/sc admin items reload
/sc admin items list
/sc admin items give <物品ID> [数量]
```

基本结构：

```yaml
items:
  verdant_hoe:
    material: DIAMOND_HOE
    amount: 1
    name: "溢绽农锄"
    rarity: EPIC
    custom_model_data: 12001
    unbreakable: true
    stats:
      farming_fortune: 120
      overbloom: 6
    sockets:
      - TOOL
      - UNIVERSAL
    enchants:
      harvest_focus: 2
    abilities:
      bloom_call:
        trigger: RIGHT_CLICK
        cooldown: 12
        lore:
          - "右键唤醒沉睡的花圃。"
    story_lore:
      - "锄刃上缠着还未凋谢的微光。"
```

常用字段：

- `material`：Bukkit 材质名，必填。
- `amount`：生成数量，默认 1。
- `name`：显示名。
- `rarity`：`COMMON`、`UNCOMMON`、`RARE`、`EPIC`、`LEGENDARY`、`MYTHIC`。
- `custom_model_data`：资源包模型编号。
- `unbreakable`：是否不可破坏。
- `stats`：写入 PDC 的数值属性。
- `sockets` / `gem_sockets`：宝石孔，支持 `WEAPON`、`ARMOR`、`TOOL`、`UNIVERSAL`。
- `enchants`：自定义附魔 ID 到等级。
- `abilities`：能力描述会写入 lore；触发逻辑需要代码中注册 handler。
- `story_lore`：故事描述。
- `accessory_type` / `acc_type`：饰品部位。
- `reforge`：默认词缀 ID。

常用 `stats` 键：

```yaml
stats:
  damage: 100
  crit_chance: 0.15
  crit_damage: 0.50
  attr_luck: 25
  tool_fortune: 50
  collection_fortune: 40
  foraging_fortune: 80
  bounty: 4
  farming_fortune: 100
  overbloom: 5
  mining_fortune: 120
  mining_spread: 5
  tool_mining_speed: 30
  breaking_power: 4
  fishing_speed: 120
  sea_creature_chance: 5
  treasure_chance: 3
```

百分比属性分两类：战斗暴击类用小数，`0.15 = 15%`；生活概率点用直观百分比点，`overbloom: 5 = 5%`。

### 原版物品默认覆盖

运行时文件：`plugins/ServerCore/vanilla_item_overrides.yml`

```text
/sc admin vanillaitems reload
/sc admin vanillaitems list
```

用于“玩家获取某个原版物品时，自动加 ServerCore 属性或替换成自定义物品”。例如让所有下界合金镐自带 `1 MiningSpread`：

```yaml
items:
  netherite_pickaxe:
    enabled: true
    material: NETHERITE_PICKAXE
    chance: 100%
    stats:
      mining_spread: 1
```

如果要完整替换成 `custom_items.yml` 里的模板：

```yaml
items:
  diamond_sword:
    enabled: true
    material: DIAMOND_SWORD
    chance: 5%
    custom_item: foundry_flame_sword
```

覆盖会在拾取、合成结果、打开背包、点击背包、登录时检查；已经是 ServerCore 自定义物品的物品不会再被原版覆盖规则处理。

## 2. 自定义配方

运行时文件：`plugins/ServerCore/recipes.yml`

配方通过 GUI 创建，不建议手写 `recipes.yml`，因为配方会保存完整 `ItemStack` 数据，包含自定义物品的 PDC、lore、模型等。

创建流程：

```text
/sc admin recipe create <配方ID>
```

在 GUI 里：

- 左侧 3x3 空格放材料。
- 右侧结果格放成品。
- 点击绿色染料保存。
- 关闭界面时，编辑器内物品会返还给玩家。

重载：

```text
/sc admin recipe reload
```

注意点：

- 配方 ID 只能用字母、数字、`_`、`-`。
- 材料使用 `RecipeChoice.ExactChoice`，所以 NBT/PDC 必须完全匹配。
- 输入材料保存时数量会归一为 1，结果保留自身数量。

## 3. 自定义生物

运行时文件：`plugins/ServerCore/custom_mobs.yml`

重载：

```text
/sc admin mobs reload
/sc admin mobs list
/sc admin mobs summon <规则ID> [数量]
```

规则按文件顺序匹配；一个规则内写了多个 matcher 时，必须全部满足。

示例：

```yaml
Verdant_Titan:
  match_type: IRON_GOLEM
  match_name_regex: "^Verdant Titan$"
  display_name: "Verdant Titan"
  override_power_level: 80
  bypass_world_level_cap: true
  mod: 3.5
  override_base_hp: 500
  # fixed_max_health: 5000
  override_base_atk: 24
  clear_vanilla_drops: true
  drops:
    - item_id: verdant_core
      chance: 2%
      amount: 1
    - material: EMERALD
      chance: 0.35
      amount: 2-6

Warden_500:
  match_type: WARDEN
  display_name: "Ancient Warden"
  override_power_level: 500
  bypass_world_level_cap: true
  fixed_max_health: 500
  clear_vanilla_drops: false
```

可用 matcher：

- `match_type`：原版实体类型，如 `ZOMBIE`。
- `match_name` / `match_name_contains`：显示名包含匹配，不区分大小写。
- `match_name_regex`：显示名正则。
- `match_mythic_id`：MythicMobs 内部 ID。
- `match_pdc_id`：ServerCore 写入的自定义生物 ID。
- `match_scoreboard_tag` / `match_scoreboard_tags`：计分板标签。
- `match_equipment`：装备匹配，支持 `helmet`、`chestplate`、`leggings`、`boots`、`main_hand`、`off_hand`。

可用覆盖项：

- `display_name`：ServerCore 展示名。
- `override_power_level` / `power_level` / `level`：固定战力等级。
- `bypass_world_level_cap`：忽略世界等级上限。
- `mod`：缩放倍率。
- `override_base_hp`：基础生命覆盖，仍会进入战斗等级血量公式。
- `fixed_max_health` / `fixed_health` / `max_health`：固定最终最大生命。设置后生命值不再受战斗等级控制；如果数值超过 Paper/原版实体属性上限，ServerCore 会自动启用虚拟血池，全息显示与死亡判定仍按配置血量走。
- `override_base_atk`：基础攻击覆盖。
- `always_hostile` / `hostile_to_players`：让该规则生成的 Mob 周期性锁定附近玩家为目标。
- `clear_vanilla_drops` / `replace_drops`：是否清除原版掉落。
- `drops`：自定义掉落列表。

掉落字段：

- `item_id`：优先生成 `custom_items.yml` 中的自定义物品。
- `material`：原版材料兜底或直接掉落。
- `chance`：支持 `0.02` 或 `2%`。
- `amount`：固定数量或区间，如 `1`、`2-5`。
- `min_amount` / `max_amount` 也可用。

ID 使用规则：

- `custom_mobs.yml` 顶层键就是 ServerCore 的规则 ID，例如 `Verdant_Titan`。
- `/sc admin mobs list` 会列出这些规则 ID。
- `/sc admin mobs summon Verdant_Titan 1` 会按规则 ID 召唤。
- 如果规则里写了 `match_mythic_id: "FlameDemon"`，ServerCore 召唤时会优先让 MythicMobs 生成 `FlameDemon`，再套用 ServerCore 的等级、血量、掉落逻辑。
- 如果没有 `match_mythic_id`，会使用 `match_type` 直接生成原版实体。
- `match_mythic_id` 是 MythicMobs 内部 ID；规则 ID 是 ServerCore 管理、指令和掉落配置引用时使用的 ID。

### 自然刷怪替换

运行时文件：`plugins/ServerCore/mob_replacements.yml`

```text
/sc admin mobreplacements reload
/sc admin mobreplacements list
```

例如让自然生成的末影人有 10% 替换为 `custom_mobs.yml` 里的 `Rogue_Enderman`：

```yaml
replacements:
  rogue_enderman:
    enabled: true
    source_type: ENDERMAN
    spawn_reasons:
      - NATURAL
    chance: 10%
    replacement_mob: Rogue_Enderman
```

对应的自定义生物规则：

```yaml
Rogue_Enderman:
  match_type: ENDERMAN
  display_name: "恶徒"
  override_power_level: 45
  mod: 1.8
  always_hostile: true
  clear_vanilla_drops: false
```

可选过滤：`worlds: [world]`、`biomes: [warped_forest]`。`replacement_mob` 使用 ServerCore 规则 ID；如果这个规则里写了 `match_mythic_id`，替换时会生成对应 MythicMob。

## 4. 结构唯一精英怪

运行时文件：`plugins/ServerCore/unique_mob_spawns.yml`

状态文件：`plugins/ServerCore/unique_mob_spawns_state.yml`

重载与调试：

```text
/sc admin uniquespawns reload
/sc admin uniquespawns list
/sc admin uniquespawns reset <刷新点ID>
```

`mob` 填 `custom_mobs.yml` 的 ServerCore 规则 ID。WDA 地牢这种结构本身先由 WDA/世界生成器负责生成；ServerCore 负责把结构里的刷新点转换成唯一精英怪，并把“已生成/已死亡”写入状态文件。

推荐用 marker 模式处理“一个世界里有很多个同类结构”的情况：在 WDA 结构模板里放一个不可见 ArmorStand 或 marker entity，并给它计分板标签 `servercore_unique_spawn:wda_dungeon_elite`。每个结构实例都会生成自己的 marker，ServerCore 会按 marker 的世界和坐标生成独立状态 ID，所以每个地牢都会有一只自己的精英怪。

marker 模式示例：

```yaml
markers:
  wda_dungeon_elite:
    enabled: true
    mob: WDA_Dungeon_Guard
    match_scoreboard_tag: "servercore_unique_spawn:wda_dungeon_elite"
    # 可选缩小匹配范围：
    # match_entity_type: ARMOR_STAND
    # match_name: "servercore_unique_spawn:wda_dungeon_elite"
    x_offset: 0.0
    y_offset: 0.0
    z_offset: 0.0
    spawn_once: true
    death_locks: true
    persistent: true
    remove_when_far_away: false
    remove_marker: true
```

marker entity 被发现后会被移除并替换成精英怪；死亡后对应的 marker 坐标状态会被锁定，不再刷新。

如果无法修改结构模板，也可以用坐标模式手动列出每个地牢实例：

坐标模式示例：

```yaml
spawns:
  wda_dungeon_elite_001:
    enabled: true
    mob: WDA_Dungeon_Guard
    world: world
    x: 120.5
    y: 42.0
    z: -310.5
    yaw: 180.0
    pitch: 0.0
    trigger_radius: 32.0
    spawn_once: true
    death_locks: true
    persistent: true
    remove_when_far_away: false
```

字段说明：

- `spawn_once: true`：这个刷新点成功生成过一次后，不会再生成第二只。
- `death_locks: true`：该实体死亡后永久锁定刷新点。
- `persistent: true` 和 `remove_when_far_away: false`：防止自然刷怪清理逻辑把它当普通怪物移除。
- `reset` 会清空指定刷新点状态，并移除当前记录的实体，适合测试或重置某个地牢实例。

如果同一种 WDA 地牢会在世界里生成很多份，就给每一份地牢各写一个刷新点 ID，例如 `wda_dungeon_elite_001`、`wda_dungeon_elite_002`。

## 5. 自定义战利品表

运行时文件：`plugins/ServerCore/gathering_loot.yml`

重载：

```text
/sc admin gatheringloot reload
/sc admin loot reload
```

当前生活技能表：

- `bounty.entries`：只给伐木使用。触发概率来自主手工具 `bounty`，例如 `bounty: 5` 是 5%。
- `overbloom.entries`：只给成熟农作物使用。触发概率来自主手工具 `overbloom`，例如 `overbloom: 5` 是 5%。
- `fishing.sea_creatures.entries`：钓鱼海怪表。先于钓鱼宝藏判定。
- `fishing.treasures.rare|epic|legendary.entries`：钓鱼分级宝藏表。
- `excavation.creatures.entries`：采掘召唤物表。先于采掘宝藏判定。
- `excavation.treasures.rare|epic|legendary.entries`：采掘分级宝藏表。

示例：

```yaml
bounty:
  entries:
    heart_of_the_forest:
      custom_item: heart_of_the_forest
      fallback_material: GOLDEN_APPLE
      amount: 1
      weight: 100
      min_foraging_level: 0

overbloom:
  entries:
    verdant_core:
      custom_item: verdant_core
      fallback_material: HEART_OF_THE_SEA
      amount: 1
      weight: 3
      min_farming_level: 60
```

字段说明：

- `custom_item` / `item`：优先从 `custom_items.yml` 生成。
- `fallback_material` / `material`：找不到自定义物品时掉落的原版材料。
- `amount`：掉落数量。
- `weight`：权重，只影响触发后抽中哪个条目。
- `min_foraging_level`：Bounty 条目的伐木等级门槛。
- `min_farming_level`：Overbloom 条目的种植等级门槛。
- `min_fishing_level`：钓鱼条目的钓鱼等级门槛。
- `min_excavation_level`：采掘条目的采掘等级门槛。
- `min_level`：通用等级门槛别名。
- `xp`：该自定义采集结果额外给予的 AuraSkills XP。ServerCore 会通过 AuraSkills API 主动发放。
- `sources`：可选，限制采掘来源方块，例如 `[sand, red_sand]`、`[dirts]`、`[gravel]`。

触发逻辑：

- 伐木：合法方块必须能通过 AuraSkills 防刷判定；Sweep 连锁破坏的木系方块也会单独掷 Bounty。
- 种植：只在成熟农作物上触发 Overbloom；种植没有 Sweep / Spread，也不会读取 Bounty。
- `Farming Fortune` 只增加普通农作物产出份数，不影响 Overbloom 概率。
- 钓鱼：先掷 Sea Creature；如果成功召唤怪物，本次不会再掷 Treasure。若失败，再掷 Treasure Chance，并按 `rare -> epic -> legendary` 的 tier 权重选择宝藏表。
- 采掘：先掷 `excavation.creatures`；如果成功召唤怪物，本次不会再掷 Treasure。若失败，再掷采掘宝藏，并按 `rare -> epic -> legendary` 的 tier 权重选择宝藏表。
- 召唤物可以写 `entity_type` 直接生成原版生物，也可以写 `mythic_mob` 交给 MythicMobs 生成复杂 AI。

钓鱼宝藏示例：

```yaml
fishing:
  treasures:
    legendary:
      weight: 2
      min_fishing_level: 35
      entries:
        tide_heart:
          custom_item: tide_heart
          fallback_material: HEART_OF_THE_SEA
          amount: 1
          weight: 4
          min_fishing_level: 35
          xp: 60
```

采掘召唤物示例：

```yaml
excavation:
  creatures:
    entries:
      sand_wraith:
        entity_type: HUSK
        mythic_mob: ""
        name: "Sand Wraith"
        sources: [sand, red_sand]
        min_excavation_level: 20
        weight: 30
        base_health: 42
        mod: 1.6
        xp: 24
```

AuraSkills 接管建议：

- 保留 `sources/fishing.yml` 和 `sources/excavation.yml`，它们负责正常经验来源和防刷判定。
- 关闭 AuraSkills 自带的 fishing/excavation loot 概率，避免和 ServerCore 重复产出。
- `abilities.yml` 里建议保持这些能力关闭：`treasure_hunter`、`epic_catch`、`metal_detector`、`lucky_spades`。
- 对 ServerCore 召唤/宝藏产生的额外经验，用 `gathering_loot.yml` 里的 `xp` 字段配置。
- 对 Sweep / Mining Spread 额外破坏的方块，ServerCore 会按实际额外破坏数量补发 AuraSkills raw XP，主方块 XP 仍由 AuraSkills 自己发放。

## 6. 快速测试指令

```text
/sc item bounty 10
/sc item farmingfortune 100
/sc item overbloom 10
/sc item miningspread 5
/sc item fishingspeed 120
/sc gathering
```

测试完 YAML 后按需重载：

```text
/sc admin items reload
/sc admin recipe reload
/sc admin mobs reload
/sc admin uniquespawns reload
/sc admin gatheringloot reload
```
