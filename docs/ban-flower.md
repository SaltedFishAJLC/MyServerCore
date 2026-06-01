# 小红花 (Ban Flower)

## 概述

"小红花"是一个整蛊彩蛋物品。玩家右键使用后会被踢出服务器并随机封禁 30~90 秒，同时全服广播。

每隔 2~4 小时，系统会随机选中一名在线玩家，将一朵小红花悄悄塞入其背包并附上蛊惑性提示。这是一个纯彩蛋功能，不影响任何游戏机制。

## 修改清单

### 新增

| 文件 | 改动 |
|------|------|
| `src/main/resources/custom_items.yml` | 新增 `little_red_flower` 物品定义 |
| `ServerCorePlugin.java` | 新增字段 `banFlowerDeliveryTaskId`、能力处理器注册、定时快递方法 |
| `docs/ban-flower.md` | 本文档 |

### 未修改

所有已有系统（战斗、物品、技能、定时任务）的代码均未改动。唯一侵入点是 `onDisable()` 和 `onEnable()` 中插入了独立的新增逻辑。

## 架构设计

```
┌─────────────────────────┐
│   startBanFlowerDelivery │  ← 插件启动时调用，自循环定时
│   每 2~4h (空服 1h)       │
└───────────┬─────────────┘
            │ Bukkit.runTaskLater
            ▼
┌─────────────────────────┐
│ 随机选在线玩家            │
│ customItemRegistry       │
│   .createItem(           │
│   "little_red_flower")   │
│ player.getInventory()    │
│   .addItem(flower)       │
└───────────┬─────────────┘
            │ 玩家右键
            ▼
┌─────────────────────────┐
│ WeaponAbilityManager     │  ← 已有框架，未修改
│   捕获 RIGHT_CLICK       │
│   匹配 ban_flower 能力   │
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│ ban_flower handler       │  ← 在 onEnable 中注册
│   1. BanList.addBan()   │
│   2. player.kick()      │
│   3. 全服广播            │
└─────────────────────────┘
```

### 为什么复用 WeaponAbilityManager

`WeaponAbilityManager` 是已有的右键技能调度框架。它监听 `PlayerInteractEvent`、解析物品 PDC 中的 `KEY_ITEM_ID`、从 `CustomItemRegistry` 内存映射中查找能力定义、检查触发条件、调用注册的 handler。

小红花直接注册一个 `ban_flower` handler 即可，零额外监听器、零框架修改。

### 为什么武器模板系统不拦截小红花

`WeaponTemplateManager.canUseMainHandWeapon()` 的判断逻辑：

```java
boolean managedWeapon = rule != null || template != null || isWeaponMaterial(item.getType());
if (!managedWeapon) {
    return true;  // 非武器物品放行
}
```

小红花的材质是 `POPPY`，不属于武器类型，`getHandRule` 和 `getTemplate` 均返回 null，直接放行。

### 为什么用 BanList.Type.PROFILE

`BanList.Type.PROFILE` 是 Minecraft 1.20.5+ 引入的基于玩家档案的封禁 API，替代了已弃用的 `NAME` 类型。本项目 `api-version: '1.21'`，保证该 API 可用。

## 测试方式

### 直接获取物品

```
/sc admin items give little_red_flower
```

手持小红花右键即可触发。

### 模拟快递

快递定时器在插件启动时自动开始。如需加速测试，可临时修改 `startBanFlowerDelivery(boolean)` 中的延迟常量（例如改为 60 秒），重新构建后部署。

### 验证点

1. 右键后玩家被踢出
2. 重连时被拒绝（封禁生效）
3. 全服看到 `xxx 因触碰小红花被封印了 X 秒！`
4. 封禁到期后可正常重连
5. 封禁时长在 30~90 秒范围内
6. 控制台日志记录

## 配置

小红花已写入绑定的 `custom_items.yml`，首次启动时自动复制到 `plugins/ServerCore/custom_items.yml`。

如需修改外观或文案，编辑该文件中的 `little_red_flower` 条目后执行 `/sc admin items reload`。

### 调整快递频率

编辑 `ServerCorePlugin.java` 中 `startBanFlowerDelivery(boolean)` 的延迟常量：

```java
// 正常间隔 (目前 2h ~ 4h)
: 7200 + (long)(Math.random() * 7201)

// 空服重试间隔 (目前 1h ~ 1h10m)
: 3600 + (long)(Math.random() * 601)
```
