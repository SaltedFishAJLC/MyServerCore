# 阶段九：异形武器统御与原版攻速/攻击距离引擎

你提出的武器模板方案直击 RPG 战斗的核心痛点。在传统的 RPG 插件中，拿个“绿宝石”当魔法剑往往只能使用空手攻速，并且无法获得左键蓄力指示器。而在 Paper 1.21 强大的原版属性支持下，我将为你设计一套“披着异形外衣，拥有原版灵魂”的完美武器模板架构。

## 1. 武器模板引擎 (Weapon Templates)
我们将彻底接管物品的底层属性注入。在 `PDC` 中，每件武器都会被打上 `KEY_WEAPON_TEMPLATE` 的标签（如 `SINGLE_SWORD`, `TWO_HANDED_AXE` 等）。

当物品生成或被玩家手持时，我们将利用原版的 `AttributeModifier` 将其死死绑定：
*   **攻速覆盖**：强行注入 `Attribute.GENERIC_ATTACK_SPEED` 到物品的 `HAND` 槽位，彻底覆盖该物品（如羽毛、绿宝石）的原生攻速，使客户端完美呈现攻击冷却指示器！
*   **攻击距离**：利用 1.20+ 新增的 `Attribute.PLAYER_ENTITY_INTERACTION_RANGE`。匕首会被削减至 2.7（贴脸），而双手剑和三叉戟会被延长至 4.0（真正的长兵器）。玩家的左键判定会被原版客户端物理级接管！

### 1.1 模板参数标准库
*   **单手剑**：攻速 1.6，范围 3.0
*   **双手剑**：攻速 0.9，范围 4.0
*   **单手斧**：攻速 1.2，范围 2.7
*   **双手斧**：攻速 0.8，范围 3.5
*   **重锤**：攻速 0.6，范围 3.5
*   **三叉戟**：攻速 1.0，范围 4.0
*   **匕首**：攻速 1.8，范围 2.7
*   **镰刀**：攻速 1.1，范围 3.5

## 2. 攻速加成解耦与急迫隔离 (Attack Speed Buffs)
你的公式设计极其严谨：`新冷却 = 原冷却 * 100 / (100 + 加成)`。
而在数学上，这恰好等价于 `新攻速 = 原攻速 * (1 + 加成 / 100)`！

*   **架构实现**：我们将玩家通过职业/饰品获得的额外“攻速加成”转化为 `AttributeModifier.Operation.MULTIPLY_SCALAR_1` 挂载到玩家身上。这样无论玩家手持什么武器，底层客户端都会自动计算出你想要的标准冷却公式！
*   **急迫 (Haste) 隔离**：原版急迫药水会强制增加攻速。**为了阻断这一行为**，我们将在之前的收集技能模块中，不再给玩家发放 `Haste` 药水效果。取而代之的是，利用 1.21 新增的 `Attribute.PLAYER_BLOCK_BREAK_SPEED`（方块破坏速度属性），在只加速挖矿的同时，绝对不污染战斗攻速！

## 3. 远程特化：瞬发短弓 (Shortbows)
短弓的特性是“无需蓄力”。这在原版弓逻辑中无法原生实现。
*   **架构解法**：在 `RangedWeaponManager` 中监听 `PlayerInteractEvent`。
*   当玩家手持短弓 `Right Click` 时，强制取消事件，阻止玩家进入拉弓减速状态。
*   直接利用 `player.launchProjectile(Arrow.class)` 射出箭矢，并计算弹道力度。
*   随后为玩家赋予一个由“武器攻速 + 玩家攻速加成”计算出的 `Cooldown`（利用 Bukkit 的 `player.setCooldown(Material.BOW, ticks)`），在快捷栏上呈现直观的转圈冷却动画！

---

## User Review Required (等待移交执行)

这是让战斗手感发生质变的底层革命。为了规范代码结构，我创建了两个统御武器模板与远程机制的骨架文件：
1. `com.servercore.manager.WeaponTemplateManager.java` (武器模板库与原生属性注入引擎)
2. `com.servercore.manager.RangedWeaponManager.java` (短弓瞬发与投射物拦截控制)

> [!IMPORTANT]
> **图纸已定，请召唤编码 Agent 进行物理重构！**
> 
> 这个阶段涉及到原版硬核 Attribute 的注入，非常考验代码规范。请向编码 Agent 下达指令：
> *“架构师已经规划好了完美的武器模板底层机制。请按照 implementation_plan.md 执行：1. 在 PDCManager 中新增武器模板类型的 Key。2. 实现 `WeaponTemplateManager`，将攻速和攻击距离作为原版 Modifier 写入物品数据中！3. 实现 `RangedWeaponManager`，完成短弓的免蓄力速射与冷却转圈效果！”*
