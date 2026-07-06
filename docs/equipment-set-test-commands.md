# ServerCore 多人装备套装测试指令

更新时间：2026-06-30

本文列出当前默认配置中已有套装、推荐搭配装备和获取指令。`/sc admin items give <物品ID> [数量]` 当前发放给执行者自己，不带目标玩家参数；多人测试时可由管理员生成后分发，或让有权限的测试账号自行执行。

测试前建议执行：

```text
/sc admin items reload
/sc admin passives reload
/sc debug set
/sc debug passive
```

## 调试套装

### test_warden / 测试守望者

```text
/sc admin items give test_warden_helmet
/sc admin items give test_warden_chestplate
/sc admin items give test_warden_sword
```

用途：验证印记屏蔽高面板、套装部件补件和 `set_piece_id` 去重。

## T1-T2

### frontier_guard / 边境守卫

```text
/sc admin items give frontier_helmet
/sc admin items give frontier_chestplate
/sc admin items give frontier_leggings
/sc admin items give frontier_boots
/sc admin items give frontier_sword
```

套装：2 件加少量攻击与韧性，4 件降低受到伤害。

### steelwall_guard / 钢壁卫士

```text
/sc admin items give steelwall_helmet
/sc admin items give steelwall_chestplate
/sc admin items give steelwall_leggings
/sc admin items give steelwall_boots
/sc admin items give steelwall_axe
/sc admin items give steelwall_shield
```

套装：2 件加生命和护甲，4 件降低受到伤害。

## T3

### wind_hunter / 逐风猎手

```text
/sc admin items give wind_hunter_helmet
/sc admin items give wind_hunter_chestplate
/sc admin items give wind_hunter_leggings
/sc admin items give wind_hunter_boots
/sc admin items give wind_hunter_longbow
```

套装：2 件加暴击率与破甲，4 件提高造成伤害。

### cobalt_vanguard / 苍钢先锋

```text
/sc admin items give cobalt_vanguard_helmet
/sc admin items give cobalt_vanguard_chestplate
/sc admin items give cobalt_vanguard_leggings
/sc admin items give cobalt_vanguard_boots
/sc admin items give cobalt_vanguard_sword
```

套装：2 件加攻击与护甲，4 件提高造成伤害。

### bedrock_bulwark / 磐岩壁垒

```text
/sc admin items give bedrock_bulwark_helmet
/sc admin items give bedrock_bulwark_chestplate
/sc admin items give bedrock_bulwark_leggings
/sc admin items give bedrock_bulwark_boots
/sc admin items give bedrock_bulwark_axe
/sc admin items give bedrock_bulwark_shield
```

套装：2 件加生命和护甲，4 件降低受到伤害。

### dusk_hunter / 暮影猎手

```text
/sc admin items give dusk_hunter_helmet
/sc admin items give dusk_hunter_chestplate
/sc admin items give dusk_hunter_leggings
/sc admin items give dusk_hunter_boots
/sc admin items give dusk_hunter_dagger
```

套装：2 件加暴击率与暴击伤害，4 件提高造成伤害。

### windfeather_ranger / 风翎游侠

```text
/sc admin items give windfeather_helmet
/sc admin items give windfeather_chestplate
/sc admin items give windfeather_leggings
/sc admin items give windfeather_boots
/sc admin items give windfeather_shortbow
```

套装：2 件加暴击率与破甲，4 件提高造成伤害。

## T4

### nightwalker / 夜幕行者

```text
/sc admin items give nightwalker_helmet
/sc admin items give nightwalker_chestplate
/sc admin items give nightwalker_leggings
/sc admin items give nightwalker_boots
/sc admin items give nightwalker_dagger
```

套装：2 件加暴击率与暴击伤害，4 件提高造成伤害。

### astral_scholar / 星辉学者

```text
/sc admin items give astral_scholar_helmet
/sc admin items give astral_scholar_chestplate
/sc admin items give astral_scholar_leggings
/sc admin items give astral_scholar_boots
/sc admin items give astral_scythe
```

套装：2 件加智慧与意志，4 件提高综合增伤乘区。

## T6

### crimson_inferno / 绯红炼狱

```text
/sc admin items give elder_crown
/sc admin items give crimson_inferno_helmet
/sc admin items give crimson_inferno_chestplate
/sc admin items give crimson_inferno_leggings
/sc admin items give crimson_inferno_boots
/sc admin items give crimson_conquest_greatsword
```

套装：2 件按缺失生命提高本套装部件攻击力，4 件启用 Dominus 剑气。

## 附魔管理员测试入口

有 `sc.admin` 或 `servercore.admin` 权限时：

```text
潜行右键附魔台
点击 管理员作弊模式
放入装备并点击可用附魔

潜行右键砂轮
点击 管理员作弊拆除
放入装备并点击要删除的附魔
```

作弊附魔保留 enabled、槽位适用和硬上限校验；会跳过普通获取池、稀有度、软上限、材料、经验、冲突组和终极数量限制。作弊砂轮单条拆除不消耗魔尘或终极材料。

## 验收建议

```text
/sc debug set <玩家>
/sc debug passive <玩家>
/sc stats
```

逐套检查 2 件、4 件阈值是否出现，确认来源形如 `set:<set_id>:<threshold>`。远程武器测试时，弓/弩左键近战应被取消，只有箭矢或其他投射物路径能结算弓面板伤害。
