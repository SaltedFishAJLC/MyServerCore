# Server Core Blueprint (1.21 Paper)
- **目标**: 打造无数值膨胀的 HSB-Like 亲友服核心插件。
- **前置依赖 (Dependencies)**: 
  - 经济: Treasury API (已弃用，现使用 Titan-Economy)
  - 核心/网络: PlaceholderAPI, ProtocolLib
  - 技能与数值: AuraSkills API, MythicLib
  - 界面UI: InventoryFramework (IF)
- **战斗数值体系 (Strict Enforcement)**:
  - 伤害计算必须遵循正交化公式: `Final Damage = (BaseStat + SkillStat) * (1 + GearMultiplier)`。
  - 严禁原版属性直接相加，武器乘区必须独立计算。
- **UI展示规范**:
  - 抛弃旧版 ChatColor，所有文本与 ActionBar 必须使用 Paper 1.21 的 `MiniMessage` Component API 渲染。
# 基础设施与运维层 (Infrastructure & Ops)
- **权限引擎**: LuckPerms API (用于动态物品/副本使用权限校验), CoreProtect (轻量级方块/容器操作记录与回滚)
- **性能监控**: 依赖 Paper 内置 Spark。所有自写高频事件 (如 `PlayerMoveEvent`, 自定义 Tick) 必须经过性能评估，禁止出现 O(N^2) 及以上的耗时操作。
- **世界管理**: 配合 VoidGen，插件需预留动态创建/卸载虚空世界(岛屿)的接口。
- **安全拦截**: 预留与 GrimAC 的兼容性。我们的自定义移动技能(如冲刺、位移)需确保不会触发 GrimAC 的 Fly/Speed 误判，必要时需在代码中临时 bypass 其检测。
# 核心组件变更
- **经济系统**: 自研 Titan-Economy 模块 (不再依赖 Treasury)
- **存储规范**:
  - 数据类型: 使用 long (支持到 9.22e18) 避免浮点精度问题。
  - 架构: 内存快照 + 数据库异步落盘。
  - 兼容性: 必须实现 Vault API 的服务注入，确保 LuckPerms 等插件能识别。
# 游戏机制与核心系统 (Game Mechanics & Systems)
- **战斗属性与饰品引擎**:
  - `CombatStats`: 完全解耦的 O(1) 计算模型 (静态缓存 + 动态主手)。
  - 饰品与护符: `AccessoryManager` 使用二进制序列化直写 Player PDC，杜绝数据库查询阻塞。包含4专属部位（项链、手镯、戒指、腰带）与大型护符包。
- **底层物品引擎 (Item Standardizer)**:
  - 惰性注入(Lazy Injection): 原版装备在被接触时自动隐藏原生属性并注入 PDC `base_damage` 和 `ITEM_ID`。
  - 兼容性: 完美避开村民交易所需材料，且内建对 MythicMobs 的解析映射。
- **待开发系统**: 死亡灵魂容器机制、方尖碑多阶段解锁、箱庭副本。