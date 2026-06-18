# ServerCore 装备体系实现状态

更新时间：2026-06-18

本文根据当前仓库代码与“印记、套装、统一被动、护符系列”设计讨论整理。范围包括武器、盔甲、普通饰品、印记、护符包、套装和装备能力。

`src/main/resources/*.yml` 是首次启动时复制的默认模板。已经运行过的服务器必须手动合并到 `plugins/ServerCore/` 下的运行时配置，或清空对应旧文件后重新生成。

## 1. 总览

| 模块 | 当前状态 | 主要入口 |
| --- | --- | --- |
| 武器 | 已实现基础模板、主副手规则、面板属性、主动技能和被动来源 | `WeaponTemplateManager`、`WeaponAbilityManager` |
| 盔甲 | 已实现部位穿戴、属性聚合、准入、附魔和被动/套装来源 | `CombatStats`、`AttributeManager`、`RequirementManager` |
| 普通饰品 | 已实现项链、手镯、戒指、腰带四槽，并改为操作后即时保存 | `AccessoryManager`、`AccessoryListener` |
| 印记 | 已实现独立单槽、死亡保留、即时切换、被动启用和套装计数 | `AccessoryManager#loadImprint`、`PassiveSnapshotService` |
| 护符包 | 已实现 54 格存储、单格一个护符、同物品去重、同系列最高稀有度生效 | `AccessoryManager#resolveTalismans` |
| 套装 | 已实现部件去重、累计/最高阈值和多套装并存 | `EquipmentSetRegistry`、`equipment_sets.yml` |
| 统一被动 | 已接入装备、饰品、护符、印记和套装快照 | `PassiveAbilityRegistry`、`PassiveSnapshotService` |
| 冷却 | 已统一为玩家 PDC 持久冷却，主动与被动可共享 | `AbilityCooldownService` |

## 2. 装备来源与生效规则

### 2.1 正常装备

以下位置属于正常装备来源：

- 主手
- 副手
- 头盔、胸甲、护腿、靴子
- 项链、手镯、戒指、腰带
- 护符包中的有效护符

正常装备继续提供原有基础属性、重铸、宝石和附魔。物品若声明 `trigger: PASSIVE`，还会进入统一被动快照。

主副手必须通过 `WeaponTemplateManager` 的手持规则；防具必须位于正确部位；饰品必须位于匹配的饰品槽。未满足 `req_skill`、`req_slayer` 或 `req_dungeon` 时，被动与套装身份不生效。

### 2.2 印记

印记使用独立玩家 PDC `imprint_data`，不属于原来的四个饰品数组。

允许放入印记的物品必须：

1. 在 `custom_items.yml` 中拥有稳定 `item_id`。
2. 声明 `accessory_type: IMPRINT`，或声明 `imprint_eligible: true`。
3. 至少拥有一个已实现且允许 `IMPRINT` 来源的 `PASSIVE`，或拥有有效的 `set_id + set_piece_id`。
4. 满足玩家准入条件。

印记只启用：

- 明确声明的 `PASSIVE`
- 套装部件身份
- 由被动处理器产生的属性修正

印记不会启用：

- 物品 `stats` 基础面板
- 重铸属性
- 宝石属性
- 原版或自定义附魔
- 武器模板攻击属性
- 主动技能

因此，把伤害或护甲数值极高的武器、盔甲放入印记，不会直接叠加面板。若它属于套装，则仍可补齐对应套装部件。

印记的其他约定：

- 槽内只保存一个物品。
- 战斗中允许自由切换。
- 取下再装备不会重置冷却。
- 玩家死亡时不掉落、不进入灵魂容器，保持原位。
- 配置重载后失去资格的物品仍保留在槽内，但全部停用，可正常取回。

### 2.3 护符包

护符包只允许新放入已注册且声明 `accessory_type: TALISMAN` 的物品。

- 每格最多一个护符。
- 相同 `item_id` 不允许重复放入。
- `talisman_family` 相同的护符只启用稀有度最高版本。
- 稀有度相同时比较 `talisman_priority`。
- 仍相同时按稳定 `item_id` 选择。
- 被压制版本可以保留在包中，但基础数值与被动全部停用。
- 没有 `talisman_family` 的护符按各自 `item_id` 视为独立系列。
- 护符可以提供明确声明的 `PASSIVE`，但不参与套装计数。

当前所有读取护符属性的主要聚合器已改为使用“有效护符视图”，包括战斗属性、五维属性、护甲、攻速、暴击伤害和钓鱼属性。

## 3. 套装系统

物品在 `custom_items.yml` 中声明：

```yaml
set_id: test_warden
set_piece_id: helmet
```

套装效果在 `equipment_sets.yml` 中定义：

```yaml
sets:
  test_warden:
    name: "测试守望者套装"
    threshold_mode: CUMULATIVE
    thresholds:
      2:
        abilities:
          test_guard:
            trigger: PASSIVE
            amount: 0.10
```

规则：

- 每件物品只能声明一个 `set_id` 和一个 `set_piece_id`。
- 同一 `set_piece_id` 只计一次。
- 普通版、强化版等不同 `item_id` 可以共享同一 `set_piece_id`。
- 印记可以提供套装部件。
- 护符包不参与套装。
- 不同套装可以同时激活。
- `CUMULATIVE`：达到高阈值时，低阈值仍保留。
- `HIGHEST_ONLY`：只启用当前满足的最高阈值。
- 每个阈值可以包含多个独立被动能力。

套装扫描位置：

- 主手、副手
- 四件盔甲
- 四个普通饰品
- 一个印记

## 4. 被动能力系统

### 4.1 配置关系

`passive_abilities.yml` 定义能力显示与处理器：

```yaml
abilities:
  test_guard:
    handler: damage_reduction
    name: "测试·守护"
    description:
      - "受到的伤害降低 {amount}。"
    allowed_sources: [ARMOR, ACCESSORY, TALISMAN, IMPRINT, SET_BONUS]
```

物品和套装引用同一个能力 ID：

```yaml
abilities:
  test_guard:
    trigger: PASSIVE
    amount: 0.10
    priority: 20
    stacking: UNIQUE
```

通用字段：

- `priority`：同能力竞争时的优先级，默认 `0`。
- `stacking`：`UNIQUE` 或 `STACK`，默认 `UNIQUE`。
- `cooldown_scope`：`SHARED` 或 `PER_SOURCE`，默认 `SHARED`。
- `cooldown_group`：可让不同能力显式共享冷却组。
- `imprint_enabled`：是否允许从印记来源生效，默认 `true`。
- `allowed_sources`：进一步限制能力实例的来源。
- `options`：也可以把上述能力参数放在嵌套 `options` 节点中。

### 4.2 当前已实现处理器

| handler | 行为 |
| --- | --- |
| `stat_bonus` | 为指定 ServerCore 数值提供被动修正 |
| `damage_reduction` | 乘法降低受到的普通伤害 |
| `outgoing_multiplier` | 乘法提高造成的伤害 |
| `on_hit_damage` | 命中后追加一次防递归真实伤害 |
| `revive` | 致死时恢复生命并写入持久冷却 |

`stat_bonus` 已接入的主要数值：

- 战斗：伤害、增伤乘区、暴击、残暴、吸血、破甲
- 生存：护甲
- 五维：力量、敏捷、智慧、意志、幸运
- 攻速
- 钓鱼速度、海怪概率、宝藏概率
- 伐木、农业、采掘的时运、连锁、Bounty、Overbloom
- 挖矿时运、扩散、速度与纯度

未注册或处理器未实现的能力不会生效，会在启动/重载日志、被动总览和调试命令中显示问题。

### 4.3 去重和来源替换

- `UNIQUE` 默认只启用优先级最高来源。
- 优先级相同则按稳定来源 ID 选择。
- 同优先级但参数不同会记录配置问题。
- `STACK` 会保留多个来源。
- 最高优先级来源失效时，会自动切换到次优来源。
- 相同 `item_instance_id` 的复制品不会重复提供同一能力。

### 4.4 冷却

主动与被动统一使用 `AbilityCooldownService`：

- 冷却保存于玩家 PDC `player_passive_cooldowns`。
- 死亡、换装、切换印记、退出和服务器重启不会刷新冷却。
- 到期记录会自动清理。
- `SHARED` 按玩家和能力/冷却组共享。
- `PER_SOURCE` 使用稳定 `item_instance_id`。
- 套装来源使用 `set:<set_id>:<threshold>` 作为稳定来源。
- 现有凤凰核心致死保护已迁移到持久冷却服务。
- 现有主动物品能力也已迁移，不再因重登清空冷却。

一次致死结算最多触发一个复活效果。系统先按事件优先级和能力优先级寻找可用被动复活，全部不可用时再尝试凤凰核心附魔。

## 5. UI 与玩家操作

`/sc acc` 打开 27 格饰品界面：

- 10：项链
- 12：手镯
- 14：戒指
- 16：腰带
- 21：印记状态
- 22：印记槽
- 23：被动总览
- 26：护符包入口

印记、普通饰品和护符包都在合法操作后立即保存，并在下一游戏刻合并刷新玩家快照。关闭界面仍会执行兜底保存。

印记和护符包使用服务端手动交换，已封堵：

- Shift 点击
- 数字键交换
- 双击收集
- 拖拽跨槽
- 非标准快捷操作

堆叠物品放入空印记/护符槽时只拆出一个；交换已有物品前必须先把光标物品拆成单个。

## 6. 存储与缓存

| 数据 | 存储 |
| --- | --- |
| 四个普通饰品 | 玩家 PDC `accessories_data` |
| 54 格护符包 | 玩家 PDC `talisman_bag_data` |
| 印记 | 玩家 PDC `imprint_data` |
| 物品实例 ID | 物品 PDC `item_instance_id` |
| 主动/被动冷却 | 玩家 PDC `player_passive_cooldowns` |

`PassiveSnapshotService` 为每名在线玩家缓存：

- 当前候选被动
- `UNIQUE` / `STACK` 解析结果
- 套装有效部件与激活阈值
- 配置或来源失效原因

装备、饰品、护符、印记、重生、模式切换和配置重载会刷新快照。同一游戏刻的多次请求会合并。另有每 5 秒一次的低频兜底刷新，用于捕捉 AuraSkills 等外部系统造成的准入条件变化。

## 7. 管理和调试

```text
/sc admin items reload
/sc admin passives reload
/sc admin equipment reload

/sc debug passive [玩家]
/sc debug set [玩家]
/sc debug imprint [玩家]
```

`/sc debug passive` 显示内部能力 ID、来源、稳定来源 ID、冷却和失效原因。普通玩家通过饰品 UI 的“被动总览”查看本地化信息，不显示内部 ID。

默认资源中提供管理员测试内容：

- `test_warden_helmet`
- `test_warden_chestplate`
- `test_warden_sword`
- `test_revival_imprint`
- `test_luck_talisman_rare`
- `test_luck_talisman_epic`
- `test_warden` 套装

这些物品不进入正式掉落，只能通过管理员生成，用于验证印记屏蔽高面板、套装补件、护符系列压制和复活冷却。

## 8. 当前未完成任务

以下内容尚未实现或只保留了扩展位置：

1. 没有可运行的 Paper 自动化测试环境；当前只完成编译与打包验证，必须在测试服执行 GUI、死亡、重登和战斗验证。
2. 周期调度器已经存在，但还没有实现光环、附近目标或环境条件类的具体周期处理器。
3. `cancel_on_deactivate`、`persist_state` 和允许主动开启的 `allow_proc_chain` 尚无具体处理逻辑；当前默认禁止被动伤害递归触发。
4. 当前处理器是五种内置通用处理器；若需要复杂套装专属状态机，应继续增加独立 handler 实现。
5. 处理器异常的逐能力熔断和日志限频尚未落地。当前内置处理器不执行外部脚本。
6. 被动名称与说明目前只有中文结构，尚未接入多语言文件。
7. 护符被压制原因显示在被动总览和调试输出中，尚未给护符包每个格子增加独立状态图标。
8. 多文件重载分别保持各自旧注册表，但尚未实现跨 `custom_items.yml`、`passive_abilities.yml`、`equipment_sets.yml` 的单事务提交。
9. 正式套装与正式被动内容尚未制作；仓库内只有测试样例。
10. `CURRENT_GAMEPLAY_SYSTEMS.md` 中旧的“4 饰品 + 任意护符包”描述尚未同步重写，后续项目总览更新时应引用本文。

## 9. 测试服验收清单

1. 把 `test_warden_sword` 放入印记，确认其 `999` 伤害不进入面板，但回响被动可触发。
2. 身穿两个不同 `test_warden` 部件，确认 2 件阈值激活。
3. 正常穿一件、印记补一个不同 `set_piece_id`，确认套装件数增加。
4. 正常装备和印记使用相同 `set_piece_id`，确认只计一件。
5. 放入 `test_revival_imprint`，触发复活后快速取下重装、死亡、重登和重启，确认 180 秒冷却不刷新。
6. 战斗中直接交换印记，确认下一游戏刻生效，旧印记持续型修正立即消失。
7. 把两个测试幸运护符放入护符包，确认只有 EPIC 版本的幸运数值生效。
8. 尝试放入相同 `item_id` 护符，确认被拒绝。
9. 尝试 Shift、数字键、双击和拖拽绕过印记/护符限制，确认操作被取消且不吞物品。
10. 死亡后确认四个普通饰品仍进入灵魂容器，而印记和护符包保持原位。
11. 用 `/sc debug passive`、`set`、`imprint` 对照实际状态。
12. 制造 YAML 语法错误后重载，确认对应注册表保留上一份有效配置并记录错误。
