# 玩家后勤与状态系统实现状态

本文记录当前 ServerCore 玩家后勤系统的实现口径。后续药水系统、料理 buff、Boss 房间锁战等内容应优先读取本文，再扩展对应管理器。

## 1. 当前目标

- 原版装备耐久不再作为服务器后勤压力来源。
- 原版饱食度、饱和度和自然回血不再决定玩家生存节奏。
- 玩家恢复改为 RPG 化后勤：脱战回血、食物补给、未来药水/料理 buff。
- 保留现有战斗、职业、附魔、吸血、血池、护盾、死亡容器、暂存箱和自定义物品 PDC 合约。

## 2. 主要入口

| 领域 | 当前入口 | 说明 |
| --- | --- | --- |
| 耐久迁移 | `ItemDurabilityManager` | 兜底取消 `PlayerItemDamageEvent`，登录/容器/点击时迁移背包、装备、副手、末影箱。 |
| 物品生成 | `CustomItemRegistry`、`ItemStandardizer` | 自定义装备和被标准化的原版装备都会强制 `unbreakable=true` 并清除 damage。 |
| 饱食锁定 | `PlayerRecoveryManager` | 取消饱食变化，登录/重生/切世界/周期 tick 同步饱食 20、饱和 20、疲劳 0。 |
| 自然回血 | `PlayerRecoveryManager` | 启动和世界加载时设置 `NATURAL_REGENERATION=false`。 |
| 战斗状态 | `DamageService`、`ShieldManager` | 有效伤害结算后刷新玩家战斗状态；盾牌成功格挡也刷新状态。 |
| 脱战回血 | `PlayerRecoveryManager` | 倒计时结束且附近无敌对单位时，按最大生命百分比平滑回血。 |
| 食物补给 | `PlayerRecoveryManager` | 配置过的食物右键立即补给，手动扣物品、回血/吸收并进入分类冷却。 |

## 3. 当前规则

### 耐久

- 所有可损耗装备、工具、武器、防具、钓竿、弓、弩、三叉戟、盾牌、鞘翅、剪刀、打火石、刷子都会强制不可破坏。
- `custom_items.yml` 的 `unbreakable` 字段继续兼容；但只要物品材料属于耐久装备，运行时总规则会覆盖为不可破坏。
- `PlayerItemDamageEvent` 在 `HIGHEST` 优先级取消，并把 damage 归零。
- `CustomRecipeManager` 当前对带 ServerCore item id 的材料按 item id 匹配，对普通 ExactChoice 会把 amount 归一后比较；耐久归零不会破坏已接管升级配方。

### 饱食和自然回血

- `survival.hunger.enabled=false` 时，玩家饱食度固定为 `lock_food_level`，饱和度固定为 `lock_saturation`，疲劳固定为 0。
- `survival.natural_regeneration.enabled=false` 时，所有世界关闭原版自然回血。
- `AttributeManager` 不再执行旧的每秒自然回血任务；它只保留属性聚合、生命/法力属性修饰和战力估算查询。

### 脱战回血

配置入口：

```yaml
survival:
  out_of_combat_regen:
    enabled: true
    base_exit_seconds: 12.0
    min_exit_seconds: 4.0
    willpower_seconds_reduction_per_point: 0.035
    heal_percent_per_second: 0.08
    tick_interval: 20
    block_near_hostile_radius: 12.0
```

公式：

```text
exitSeconds = max(min_exit_seconds,
                  base_exit_seconds - WIL * willpower_seconds_reduction_per_point)

healPerSecond = maxHealth * heal_percent_per_second + flatRecovery
```

- WIL 只缩短脱战等待时间，不直接增加回血速度。
- `flatRecovery` 当前包含守护者职业回复和 Respite 等装备附魔的平铺恢复。
- 玩家最近造成或受到有效伤害后进入战斗状态。
- 玩家附近 `block_near_hostile_radius` 内存在敌对生物时，不进入恢复中状态。
- 守护者保留战斗中低速恢复；血魔吸血、先知治疗光环、灾厄使魔血池仍走各自系统。

## 4. 食物补给

当前 `config.yml` 内置了苹果、面包、熟肉、熟鱼和金苹果等基础补给。每个补给项可配置：

- `material`：原版材质。
- `item_id`：可选，未来自定义料理应优先用 ServerCore item id。
- `instant_heal_percent`：按最大生命百分比立即治疗。
- `absorption`：可选，设置至少达到的吸收值。
- `cooldown_seconds`：分类冷却秒数。
- `category`：`BASIC_FOOD`、`SPECIAL_FOOD`、`SEAFOOD`、`MAGIC_MEAL` 等。

食用规则：

- 配置过的食物可以在满饱食时右键使用。
- 原版饱食/药水效果会被取消，治疗和吸收由 ServerCore 手动执行。
- ServerCore 自定义材料即使使用可食用材质，只要没有配置为补给品，也不会被误吃。
- 食物 buff 目前只实现立即回血和吸收；短时料理 buff 应继续接入该管理器或后续统一 buff 层。

## 5. 药水联动预留

后续药水系统建议新增独立 ServerCore buff 层，再由 `PlayerRecoveryManager` 暴露查询或注册入口：

- 食物：短时、弱数值、可战斗中使用，默认不计入或低权重计入生态战力。
- 药水：长效、强数值、同分类互斥，战斗药水应考虑计入生态战力。
- 原版药水效果可以保留图标/粒子，但最终数值不应直接依赖原版效果。
- 建议分类：`OFFENSE_POTION`、`DEFENSE_POTION`、`UTILITY_POTION`、`MAGIC_POTION`、`GATHERING_POTION`。

## 6. 已确认调整

- 游侠主/副属性池改为敏捷/意志，避免与刺客敏捷/幸运重复。
- `docs/numerical-systems-status.md` 已更新意志、脱战回血和游侠属性口径。
- `化石心核` lore 已从“耐久回声”改为“采掘回声与工具共鸣”。
- 当前附魔资源中未发现 `minecraft:unbreaking` 或 `minecraft:mending` 正式产出入口。

## 7. 回归测试清单

1. 自定义武器、防具、工具、钓竿、盾牌使用后不掉耐久。
2. 原版钻石/下界合金装备被标准化后不掉耐久。
3. 玩家奔跑、挖矿、受伤后饱食度始终保持锁定值。
4. 世界游戏规则 `naturalRegeneration` 为 false。
5. 玩家受到怪物、投射物、DOT、环境转换伤害后刷新脱战倒计时。
6. 玩家攻击怪物后刷新脱战倒计时。
7. 盾牌完整格挡或格挡被突破时刷新脱战倒计时。
8. 倒计时结束且附近没有敌对单位时按最大生命百分比回血。
9. 提高 WIL 后脱战等待缩短，但回血速度不随 WIL 增长。
10. 配置食物在满饱食时可右键使用，正确扣物品、回血并进入分类冷却。
11. 未配置的 ServerCore 可食用材质材料不会被误吃。
12. 血魔吸血、守护者战斗恢复、先知治疗光环、灾厄血池仍可独立触发。
