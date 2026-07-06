# ServerCore 装备体系实现状态

更新时间：2026-06-30

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
| T1-T4/T3/T6 装备 | 已实现物品配置、套装定义和可生效被动 | `custom_items.yml`、`equipment_sets.yml`、`passive_abilities.yml` |

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
| `missing_health_set_stat` | 按缺失生命动态放大指定套装部件提供的面板数值 |
| `bleed_on_hit` | 近战命中时按概率、总时长、总伤害施加通用流血 |
| `bleed_lifesteal` | 把来源玩家实际造成的流血伤害转为受每秒上限约束的吸血恢复 |
| `dominus_sword_aura` | 管理击杀叠层、定时衰减与方向型剑气 |
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

### 7.1 T6 战斗测试装备

2026-06-21 新增以下正式配置内容，当前不接入掉落，通过管理员命令发放：

```text
/sc admin items give elder_crown
/sc admin items give crimson_inferno_helmet
/sc admin items give crimson_inferno_chestplate
/sc admin items give crimson_inferno_leggings
/sc admin items give crimson_inferno_boots
/sc admin items give crimson_conquest_greatsword
```

- `equipment_tier: 6` 会保存为稳定 PDC，并在物品 Lore 显示 `装备阶段: T6`。
- 装备基础最大生命使用独立 `max_health` PDC，不折算为力量；正常装备、饰品和有效护符可提供该数值，印记不会提供。
- 上古之冠正常穿戴时提供面板，放入印记时只启用“鲜血渴望”。
- 绯红炼狱 2 件效果只放大当前正常穿戴的本套装部件所提供的攻击力，不放大全身其他装备或职业攻击。
- Dominus 击杀敌对生物叠至 10 层；最后一次击杀 5 秒后开始每 5 秒衰减 1 层。
- Dominus 剑气使用本次近战实际伤害的 75%，命中前方 8 格、宽 1.5 格内的非玩家生物；以防递归的真实次级伤害落地，避免同一份目标防御被重复计算。
- 猩红征伐巨剑通过 `StatusService#tryApplyBleed` 施加 `30% / 10 秒 / 400 总伤害` 的独立流血池。
- `BleedApplyEvent` 可取消或修改概率、时长、总伤害；`BleedDamageEvent` 暴露经过抗性、DOT 上限与事件修正后的实际流血伤害。
- “鲜血渴望”按实际流血伤害恢复，每个滚动 1 秒窗口最多恢复最大生命 15%；会经过普通治疗倍率，但不会调用血魔的吸血翻倍。

### 7.2 T1-T4/T3 套装内容

2026-06-30 已核对 `custom_items.yml` 中所有新增 `set_id`，并在 `equipment_sets.yml` 与 `passive_abilities.yml` 中补齐对应套装效果。以下套装不再只是物品配置，穿戴有效部件后会进入 `PassiveSnapshotService`，可通过 `/sc debug set` 与 `/sc debug passive` 验证。

| set_id | 名称 | 阈值 | 当前效果 |
| --- | --- | --- | --- |
| `frontier_guard` | 边境守卫 | 2 / 4 | 2 件加少量攻击与韧性，4 件降低受到伤害 |
| `steelwall_guard` | 钢壁卫士 | 2 / 4 | 2 件加生命和护甲，4 件降低受到伤害 |
| `wind_hunter` | 逐风猎手 | 2 / 4 | 2 件加暴击率与破甲，4 件提高造成伤害 |
| `nightwalker` | 夜幕行者 | 2 / 4 | 2 件加暴击率与暴击伤害，4 件提高造成伤害 |
| `astral_scholar` | 星辉学者 | 2 / 4 | 2 件加智慧与意志，4 件提高综合增伤乘区 |
| `cobalt_vanguard` | 苍钢先锋 | 2 / 4 | 2 件加攻击与护甲，4 件提高造成伤害 |
| `bedrock_bulwark` | 磐岩壁垒 | 2 / 4 | 2 件加生命和护甲，4 件降低受到伤害 |
| `dusk_hunter` | 暮影猎手 | 2 / 4 | 2 件加暴击率与暴击伤害，4 件提高造成伤害 |
| `windfeather_ranger` | 风翎游侠 | 2 / 4 | 2 件加暴击率与破甲，4 件提高造成伤害 |

保留的契约：

- 套装只通过 `set_id + set_piece_id` 计数，同一部件 ID 去重。
- 印记仍只启用 `PASSIVE` 与套装身份，不复制基础面板、重铸、宝石、附魔或武器模板属性。
- 护符包仍不参与套装计数。
- 新套装均使用已有通用 handler；复杂流派机制后续仍应新增独立 handler，而不是塞进通用数值项。

## 8. 当前未完成任务

以下内容尚未实现或只保留了扩展位置：

1. 没有可运行的 Paper 自动化测试环境；当前只完成编译与打包验证，必须在测试服执行 GUI、死亡、重登和战斗验证。
2. 周期调度器已经存在，但还没有实现光环、附近目标或环境条件类的具体周期处理器。
3. `cancel_on_deactivate`、`persist_state` 和允许主动开启的 `allow_proc_chain` 尚无具体处理逻辑；当前默认禁止被动伤害递归触发。
4. 当前已有九种内置处理器；新的复杂套装仍应优先增加独立 handler，而不是把专属状态塞进通用数值处理器。
5. 处理器异常的逐能力熔断和日志限频尚未落地。当前内置处理器不执行外部脚本。
6. 被动名称与说明目前只有中文结构，尚未接入多语言文件。
7. 护符被压制原因显示在被动总览和调试输出中，尚未给护符包每个格子增加独立状态图标。
8. 多文件重载分别保持各自旧注册表，但尚未实现跨 `custom_items.yml`、`passive_abilities.yml`、`equipment_sets.yml` 的单事务提交。
9. T1-T4/T3 与 T6 战斗测试套装已完成配置和套装生效闭环；T5、T7、正式护符和更多掉落/制作来源仍未制作。
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
13. 发放六件 T6 物品，确认稀有度、T6 标记、皮革颜色、眼眸/涡流纹饰、自带附魔和三个通用宝石槽正确。
14. 仅穿两件绯红炼狱，分别在满血与半血查看面板；半血时已穿两件提供的攻击力应增加 15%。
15. 穿齐四件并连续击杀敌对生物，确认 Dominus 逐层增加、上限 10，停杀 5 秒后每 5 秒减少一层。
16. 在 1 层与 10 层 Dominus 下近战，确认剑气概率分别约为 10% 与必定触发，且前方 8 格同一直线多个目标均受击。
17. 使用猩红征伐巨剑持续攻击普通目标，确认成功流血后每秒结算一次、10 次合计 400；骷髅与构装免疫。
18. 正常穿戴上古之冠后验证其面板；改放入印记后确认面板消失但“鲜血渴望”仍生效。
19. 同时制造多份流血，确认每秒恢复总量不超过最大生命 15%，切换为血魔后该恢复不翻倍。
20. 用 `/sc debug passive`、`/sc debug set` 与战斗面板交叉核对 T6 能力来源。
21. 逐套发放 T1-T4/T3 四件防具，确认 2 件与 4 件阈值分别出现在 `/sc debug set`，并在 `/sc debug passive` 中看到对应 `set:<set_id>:<threshold>` 来源。
22. 把同一套装的一件防具放入印记、正常穿戴另一个不同 `set_piece_id`，确认套装件数增加但印记不提供该防具基础面板。
