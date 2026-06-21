# ServerCore `/sc` 指令大全

更新时间：2026-06-21

本文根据当前 `plugin.yml` 与 `ServerCorePlugin` 的实际命令分支整理，不以聊天帮助文本为准。

## 1. 基本约定

主命令：

```text
/servercore
```

可用别名：

```text
/score
/sc
/sce
```

本文统一使用最短别名 `/sc`。

参数记法：

- `<参数>`：必填。
- `[参数]`：可选。
- `A|B`：从多个值中选择一个。
- `[玩家]`：不填写时通常操作自己。

当前限制：

- 所有 `/sc` 指令都只能由游戏内玩家执行，控制台执行会收到“该指令仅限玩家使用”。
- 管理员命令统一检查权限节点 `servercore.admin`。
- `plugin.yml` 当前未单独声明该权限节点；正式服建议通过 LuckPerms 等权限插件显式授予。
- 当前没有专用 Tab 补全器，物品 ID、怪物 ID、附魔 ID 等需要通过列表命令或配置文件查询。

## 2. 玩家常用指令

| 指令 | 作用 |
| --- | --- |
| `/sc` | 显示简短的玩家指令提示；未知参数也会回到该提示。 |
| `/sc stats` | 打开战斗属性面板。 |
| `/sc gathering` | 打开生活/采集属性面板。 |
| `/sc acc` | 打开饰品、印记、护符包与被动总览界面。 |
| `/sc class` | 打开职业选择界面。 |
| `/sc stash` | 打开暂存箱。 |
| `/sc recycle` | 打开装备回收界面。 |
| `/sc recipe [物品ID]` | 查看指定物品参与的配方；不填写 ID 时查询主手物品。 |
| `/sc enchant` | 显示附魔指令帮助。 |
| `/sc enchant books` | 打开轮换附魔书商店。 |
| `/sc enchant special <附魔ID> <等级>` | 对主手物品尝试定向附魔，并支付定向附魔台配置的材料与经验费用。 |

### 2.1 玩家指令别名

| 标准写法 | 其他可用写法 |
| --- | --- |
| `/sc gathering` | `/sc life`、`/sc noncombat`、`/sc toolstats`、`/sc 生活`、`/sc 采集` |
| `/sc recycle` | `/sc salvage` |
| `/sc recipe` | `/sc recipes`、`/sc recipebook` |
| `/sc enchant books` | `/sc enchant bookshop`、`/sc enchant npcbooks` |
| `/sc enchant special` | `/sc enchant table` |

### 2.2 配方查询

```text
/sc recipe
/sc recipe <物品ID>
```

- 无参数：读取主手物品并显示使用它的配方。
- 有物品 ID：直接查询该自定义物品。
- 这是“查询该物品被哪些配方使用”，不是直接合成或发放物品。

### 2.3 附魔书商店与特殊附魔

```text
/sc enchant books
/sc enchant special <附魔ID> <等级>
```

- `books` 打开当前轮换的 NPC 附魔书商店。
- `special` 直接处理主手物品，具体魔尘与经验等级花费由附魔获取配置决定。
- 等级必须是整数；附魔仍需满足槽位、冲突、等级上限和终极附魔数量限制。

## 3. 管理员：配置与内容重载

以下指令均要求 `servercore.admin`。

| 指令 | 重载内容 |
| --- | --- |
| `/sc admin recipe reload` | `custom_recipes.yml` 自定义配方。 |
| `/sc admin mobs reload` | 自定义怪物、标签规则、抗性规则和怪物替换规则。 |
| `/sc admin mobreplacements reload` | 仅重载怪物替换规则。 |
| `/sc admin uniquespawns reload` | 唯一/结构怪物生成规则。 |
| `/sc admin names reload` | 物品名称映射，并重新格式化自己的背包。 |
| `/sc admin recycle reload` | 回收价格配置。 |
| `/sc admin items reload` | 自定义物品，同时刷新被动、套装、原版物品覆盖与自己的物品格式。 |
| `/sc admin passives reload` | 自定义物品、统一被动与套装配置。 |
| `/sc admin vanillaitems reload` | 原版物品覆盖配置，并应用到自己的背包。 |
| `/sc admin gatheringloot reload` | 伐木、农业、采掘、钓鱼和挖矿奖励表。 |
| `/sc reload enchants` | 自定义附魔注册表。注意该指令没有 `admin` 层。 |

### 3.1 重载命令别名

| 标准写法 | 其他可用写法 |
| --- | --- |
| `mobreplacements` | `mob-replacements`、`spawnreplacements` |
| `uniquespawns` | `unique-spawns`、`structurespawns`、`structuremobs` |
| `names` | `itemnames` |
| `recycle` | `salvage` |
| `items` | `customitems` |
| `passives` | `equipment` |
| `vanillaitems` | `vanilla-items`、`itemoverrides` |
| `gatheringloot` | `loot` |

部署提醒：资源目录中的 YAML 不会自动覆盖已经存在的 `plugins/ServerCore/*.yml`。重载前必须先把新字段和条目合并到服务器运行时配置。

## 4. 管理员：配方管理

### 创建配方

```text
/sc admin recipe create <配方ID>
```

- 打开配方构建界面。
- 配方 ID 只能使用英文字母、数字、下划线和连字符。

### 重载配方

```text
/sc admin recipe reload
```

重新加载并注册自定义配方。

## 5. 管理员：怪物与生成管理

### 5.1 自定义怪物

```text
/sc admin mobs list
/sc admin mobs reload
/sc admin mobs summon <怪物规则ID> [数量]
```

- `list`：列出已加载的自定义怪物规则 ID。
- `summon`：在执行者位置生成怪物并应用 ServerCore 数值缩放。
- 数量默认 `1`，限制为 `1-50`。

### 5.2 怪物替换

```text
/sc admin mobreplacements list
/sc admin mobreplacements reload
```

用于查看或重载自然生成怪物替换规则。

### 5.3 唯一与结构怪物

```text
/sc admin uniquespawns list
/sc admin uniquespawns reload
/sc admin uniquespawns reset <生成规则ID>
```

- `list`：列出唯一生成规则。
- `reset`：清除指定生成规则的已生成状态，使其可以重新触发。

## 6. 管理员：自定义物品管理

### 6.1 查看、重载与发放

```text
/sc admin items list
/sc admin items reload
/sc admin items give <物品ID> [数量]
```

- `list`：列出 `custom_items.yml` 中已加载的物品 ID。
- `give`：把模板物品发给自己；背包满时多余物品掉落在脚下。
- 数量不填时使用模板自身的 `amount`，填写时最低为 `1`。

T6 测试装备示例：

```text
/sc admin items give elder_crown
/sc admin items give crimson_inferno_helmet
/sc admin items give crimson_inferno_chestplate
/sc admin items give crimson_inferno_leggings
/sc admin items give crimson_inferno_boots
/sc admin items give crimson_conquest_greatsword
```

### 6.2 设置主手物品 ID

```text
/sc admin items id <物品ID>
```

别名：

```text
/sc admin items setid <物品ID>
/sc admin items set-id <物品ID>
```

该命令只给主手物品写入稳定内部 ID，不会自动把物品保存为模板。

### 6.3 导出主手物品模板

```text
/sc admin items save [物品ID]
```

- 把主手物品导出或覆盖到运行时 `custom_items.yml`。
- 不填写 ID 时读取物品当前的内部 ID。
- 保存时会尝试剥离重铸与宝石已经写入的数值，保留模板基础数值。

### 6.4 修改稀有度

```text
/sc admin rarity <稀有度>
```

可用值：

```text
COMMON
UNCOMMON
RARE
EPIC
LEGENDARY
MYTHIC
```

### 6.5 修改重铸

```text
/sc admin reforge <重铸ID>
/sc admin reforge clear
```

- 对主手物品应用指定重铸。
- 不匹配物品类型的重铸会被拒绝。

### 6.6 添加宝石槽

```text
/sc admin gem socket <槽位类型> <数量>
```

槽位类型：

```text
WEAPON
ARMOR
TOOL
UNIVERSAL
```

### 6.7 安装宝石

```text
/sc admin gem apply <宝石ID>
```

把指定宝石写入主手物品的一个兼容空槽。当前宝石系统没有正式拆除或替换流程。

## 7. 管理员：主手物品底层编辑

通用格式：

```text
/sc item <属性> <值>
```

要求：

- 必须持有非空气物品。
- 要求 `servercore.admin`。
- 数值编辑会直接写入主手物品 PDC，并刷新玩家属性缓存。

### 7.1 战斗与生存属性

| 属性写法 | 含义 | 单位 |
| --- | --- | --- |
| `damage` | 基础攻击力 | 数值 |
| `mult` | 基础增伤乘区增量 | 小数，`0.20 = +20%` |
| `crit` | 暴击率 | 小数，`0.25 = 25%` |
| `critdmg` | 暴击伤害增量 | 小数，`0.45 = +45%` |
| `brutality` | 残暴 | 数值 |
| `lifesteal` | 吸血率 | 小数 |
| `armorpen` | 破甲 | 百分点 |
| `armor` | 护甲 | 数值 |
| `hp` | 最大生命值 | 数值 |
| `attackspeed` | 攻速加成 | 百分点 |
| `shieldthreshold` | 盾牌格挡阈值 | 数值 |
| `effectiveblock` | 盾牌有效格挡比例 | 小数 |
| `shieldcooldown` | 盾牌冷却 | 秒 |

常用别名包括：

- `lifesteal`：`life_steal`、`vampirism`、`vamp`
- `hp`：`health`、`maxhp`、`max_health`
- `attackspeed`：`attack_speed`、`attack_speed_bonus`、`aspeed`
- `shieldthreshold`：`shield_threshold`、`block_threshold`、`shield_block_threshold`
- `effectiveblock`：`effective_block`、`shield_effective_block`
- `shieldcooldown`：`shield_cooldown`、`shield_cooldown_seconds`

### 7.2 五维属性

| 属性写法 | 含义 | 别名 |
| --- | --- | --- |
| `str` | 力量 | `strength`、`tou`、`toughness` |
| `agi` | 敏捷 | `agility` |
| `int` | 智慧 | `intelligence` |
| `wil` | 意志 | `will`、`willpower` |
| `luk` | 幸运 | `luck` |

### 7.3 采集属性

| 标准写法 | 主要别名 |
| --- | --- |
| `toolfortune` | `fortune`、`tf`、`tool_fortune` |
| `collectionfortune` | `gatherfortune`、`cf`、`collection_fortune` |
| `foragingfortune` | `ff`、`foraging_fortune` |
| `bounty` | `foragingbounty`、`foraging_bounty` |
| `farmingfortune` | `farmfortune`、`farming_fortune` |
| `overbloom` | `over_bloom`、`bloom` |
| `excavationfortune` | `digfortune`、`excavation_fortune` |
| `miningfortune` | `minefortune`、`mining_fortune` |
| `toolsweep` | `sweep`、`tool_sweep` |
| `collectionsweep` | `gathersweep`、`collection_sweep` |
| `foragingsweep` | `fsweep`、`foraging_sweep` |
| `toolspread` | `spread`、`tool_spread` |
| `miningspread` | `minespread`、`mining_spread` |
| `miningspeed` | `toolminingspeed`、`tool_mining_speed` |
| `breakingpower` | `bp`、`breaking_power` |
| `purity` | 无 |
| `miningpurity` | `mpurity`、`mining_purity` |
| `fishingspeed` | `fishspeed`、`fishing_speed` |
| `seacreaturechance` | `scc`、`sea_creature_chance` |
| `treasurechance` | `tc`、`treasure_chance` |

注意：当前 `toolspread` 命令分支实际写入的是 `mining_spread`，不是独立 `tool_spread`。这是当前代码行为。

### 7.4 饰品部位

```text
/sc item acctype <部位>
```

常用部位：

```text
necklace
bracelet
ring
belt
talisman
imprint
```

这里只负责写入饰品类型；物品能否进入对应界面还会受到注册物品、印记资格与被动规则校验。

### 7.5 武器模板

```text
/sc item template <模板>
```

主要模板：

```text
ONE_HANDED_SWORD
TWO_HANDED_SWORD
ONE_HANDED_AXE
TWO_HANDED_AXE
HEAVY_HAMMER
TRIDENT
DAGGER
SCYTHE
SHORTBOW
LONGBOW
CROSSBOW
SHIELD
```

命令也接受管理器中的常用简写，例如 `GREATSWORD`、`GREATAXE`、`2H_SWORD`。

模板参数别名：

```text
template
weapontemplate
weapon_template
```

### 7.6 手持规则

```text
/sc item handrule <规则>
```

主要规则：

```text
MAIN_HAND_ONLY
OFF_HAND_ONLY
BOTH_HANDS_ALLOWED
TWO_HANDED
```

参数别名：

```text
handrule
hand_rule
weaponhand
weapon_hand_rule
```

## 8. 管理员：附魔管理

### 8.1 查看附魔列表

```text
/sc enchant list
```

列出附魔 ID、显示名、稀有度以及是否启用。

### 8.2 给主手物品添加附魔

```text
/sc enchant give <附魔ID> <等级>
/sc enchant give <玩家> <附魔ID> <等级>
```

- 不指定玩家时操作自己的主手物品。
- 指定玩家时目标必须在线。
- 会执行槽位、冲突、软/硬上限及终极附魔数量校验。

### 8.3 移除单个附魔

```text
/sc enchant remove <附魔ID>
/sc enchant remove <玩家> <附魔ID>
```

从目标玩家主手物品移除指定自定义附魔。

### 8.4 清空附魔

```text
/sc enchant clear
/sc enchant clear <玩家>
```

清除目标玩家主手物品上的全部自定义附魔。

### 8.5 调试主手附魔

```text
/sc enchant debug
```

输出：

- 主手物品类型；
- 原始附魔 PDC 字符串；
- 当前实际生效的附魔及数值；
- 已禁用、未知或未生效的附魔。

### 8.6 旧式直接编辑入口

```text
/sc admin enchant <附魔ID> <等级>
```

该入口直接修改执行者主手物品，功能与 `/sc enchant give <附魔ID> <等级>` 接近。新管理流程建议优先使用 `/sc enchant give`，因为其玩家参数和帮助信息更完整。

## 9. 管理员：调试命令

```text
/sc debug power
/sc debug weapon
/sc debug shield
/sc debug damage
/sc debug passive [玩家]
/sc debug set [玩家]
/sc debug imprint [玩家]
/sc debug moblevel <怪物规则ID>
```

| 指令 | 输出内容 |
| --- | --- |
| `power` | TargetPower、SpawnPower、近战/远程/魔法 DPS、EHP 和续航。 |
| `weapon` | 主手模板、攻速、距离、冷却、手持规则、可靠性与主副手合法性。 |
| `shield` | 当前盾牌估算的每秒防御价值。 |
| `damage` | 基础攻击、增伤乘区、暴击率、残暴与吸血。 |
| `passive [玩家]` | 有效被动、来源、稳定来源 ID、冷却与失效原因。 |
| `set [玩家]` | 套装有效件数、部件 ID 和已激活阈值。 |
| `imprint [玩家]` | 印记物品 ID 与当前资格状态。 |
| `moblevel <ID>` | 怪物等级模式、基础/最小/最大等级和玩家缩放。 |

`passive`、`set`、`imprint` 不指定玩家时检查自己，指定时目标必须在线。

## 10. 快速测试示例

### 发放并检查 T6 套装

```text
/sc admin items give crimson_inferno_helmet
/sc admin items give crimson_inferno_chestplate
/sc admin items give crimson_inferno_leggings
/sc admin items give crimson_inferno_boots
/sc admin items give crimson_conquest_greatsword
/sc debug damage
/sc debug set
/sc debug passive
```

### 检查上古之冠印记

```text
/sc admin items give elder_crown
/sc acc
/sc debug imprint
/sc debug passive
```

### 制作临时数值测试物品

```text
/sc item damage 400
/sc item crit 0.25
/sc item critdmg 0.45
/sc item hp 250
/sc item template TWO_HANDED_SWORD
/sc item handrule TWO_HANDED
```

## 11. 已知命令层限制

1. 根帮助文本只列出少量玩家指令，不能视为完整指令大全。
2. 所有命令都要求玩家身份，因此无法通过服务器控制台执行重载或批量管理。
3. 没有 Tab 补全；建议先使用 `items list`、`mobs list`、`uniquespawns list`、`enchant list`。
4. 部分旧权限错误消息存在乱码，但实际权限判断仍是 `servercore.admin`。
5. `admin items reload` 会连带刷新多个注册表；只修改被动或套装时优先使用 `admin passives reload`。
6. 命令修改的是运行时文件或当前物品；仓库 `src/main/resources` 模板不会因此自动同步。
