# EmberRoutes V1 复盘、遗产采集清单与 V2 缺陷根治蓝图

> 日期：2026-11-18｜状态：active｜适用：`EmberRoutes`（V1 冻结参考）向 `EmberRoutesV2` 转移开发重心的工程指南
> 关联导航：[项目红线 (AGENTS.md)](../AGENTS.md) ｜ [系统架构规范 (ARCHITECTURE.md)](ARCHITECTURE.md) ｜ [模块路线图 (MODULE_MAP.md)](MODULE_MAP.md)

---

## 一、V1 终态体检快照：为什么要做 V2 干净重建？

在冻结 V1（版本 `0.29.69`，`EmberRoutes` 仓库）转移重心前，对 V1 全量代码与验证基线实测统计如下：

| 指标维度 | V1 冻结实测数值（`0.29.69`） | 暴露出的核心工程瓶颈 |
| :--- | :--- | :--- |
| **代码规模与目录熵增** | Lua **493 个文件 / 75,120 行**（其中 `UI/` 单目录平铺 **132 个文件**）；Java **71 个文件 / 10,323 行**（`emberroutes/probe/` 单包平铺 **50+ 个类**） | 大域缺乏二级分包，靠超长文件名前缀硬堆，查找与职责隔离成本极高 |
| **跨语言桥面** | `ProbeRuntime` 单类暴露 **119 个公开静态方法** | 缺乏分层收敛，业务细节与底层原语混杂在同一平铺静态门面 |
| **模块耦合度** | 跨模块字面边 **208 条**（同层跨域 **126 条**，含双向环） + **66 处** `Probe.*` 侵入挂载 | `client/Runtime` 反向依赖 10 个业务域，牵一发而动全身（见 `EmberRoutes/docs/reviews/2026-11-14/dependency-bridge-surface.md`） |
| **双语词条量** | CN / EN 各 **2,291 键** | 包含大量暴露内部状态机术语（如 `RECONCILE`、`CUSTODY`）的诊断文案 |
| **测试与实机倒挂** | 测试文件 **421 个**；Kahlua 293 套件实跑 **174 通过 / 119 失败** | 192 个测试靠 `package.loaded` 伪造内部模块桩；重构一次坏百余个假桩测试，而 `0.29.10–0.29.69` 大量核心改动长期处于**实机 UNVERIFIED** |

**根本原因总结**：V1 试图在单人沙盒模组中用 **6 层持久化互锁账本**（`TaskDirectory` $\rightarrow$ `LaborStore` $\rightarrow$ `JobStore` $\rightarrow$ `ClaimsKernel` $\rightarrow$ `ObligationLedger` $\rightarrow$ `TaskController`）去防御一切极端崩溃与并发交错，同时多条业务线并行铺开而缺少逐模块实机闭环门禁，最终导致 **90% 的研发精力被账本自证与假桩维护吞噬，而玩家肉眼可见的寻路、动作起止与搬运流畅度却长期受困**。

---

## 二、软件工程跨版本重写：从 V1 转移到 V2 必须搜集的 7 类核心数据

在软件工程中，从 V1 迁移至重写版 V2 最危险的两个陷阱是 **「丢失用血泪试错换来的隐性边界知识」** 与 **「第二系统综合征（凭空重新脑补产品交互）」**。为此，V1 向 V2 交接只采集以下 7 类高价值资产，其余历史包袱一律封存：

### 1. 已验证的产品与 UX 定案清单（防脑补走样）
- **据点空间单层模型**：只有唯一一层 `settlement.areas`（左键按住拖框增/擦/撤销），绝不设“地面存储子区”（已在 M1 落地）。
- **仓储面板零等待体验**：打开/切回面板第 0 帧直接渲染常驻只读快照（拒绝打开先白屏等重扫）；数值采用真实实数展示，**严禁虚构“够吃 X 天”等伪精确储耗预测**。
- **容器规则配置高效交互（待迁入 M3/M4）**：双栏向导（左定角色/策略、右定分类折叠树 + 搜索）、8 格内相邻容器 `< / >` 静默保存快切、远程检视直达、按住 `[👁]` 窗口透明化（`0.12`）并在地面打光圈定位实体容器、全库 **零 Unicode Emoji**（统一矢量图标与纯文本）。
- **生产维持策略（待迁入 M4）**：工坊自动补货采用**高低双阈值滞回**，不搞每个岗位内部硬嵌递归子制作。
- **小队与人物呈现（待迁入 M4/M5）**：人物头像采用原版 `DeadBodyAtlas` 一次性烘焙缓存静态纹理（取代吃帧率的每帧实时 `ISUI3DModel` 骨骼渲染）；小队 HUD 支持自定义呼号、拖拽显示顺序与三档血量视觉。

### 2. B42 宿主引擎实证雷区台账（防二次踩坑）
除 [MODULE_MAP.md](MODULE_MAP.md) 已登记的 6 条禁令外，以下从 V1 实机与字节码核查中提炼的 **7 条引擎隐蔽事实** 必须在 V2 对应模块直接内置守卫：

| 序号 | B42 原版引擎隐蔽事实（V1 实证出处） | V2 强制应对方式 |
| :--- | :--- | :--- |
| **E1** | **室内门 `door.isLocked()` 假阳性**：原版大量普通未锁室内门 `isLocked()` 默认返回 `true`，曾导致 V1 宏观连通图误判全屋封死（`BACKLOG 2026-11-08`） | 寻路与通行门禁只认 `door.isBarricaded()` 与真实钥匙锁阻断（`isLockedByKey()` 且无对应 `keyId`），绝不盲信裸 `isLocked()` |
| **E2** | **暂停态 `Events.OnTick` 停摆**：单人按 `Space / F2` 暂停查看 UI 或改配置时，`OnTick` 完全停止触发（`BACKLOG 2026-11-08`） | 玩家暂停期间可触发的配置变更（如划区保存、过滤规则调整）必须同步更新快照或挂接 `OnTickEvenPaused`，严禁让 UI 死等下一帧 `OnTick` |
| **E3** | **NPC 健身组件未初始化即自杀**：新建 `IsoPlayer` 子类的 `Fitness.exercises` 未初始化，`setCurrentExercise` 静默写 `nil` 导致锻炼动画第 1 帧即中断（`BACKLOG 2026-10-25`） | M2/M3 调用原版健身前必须先调幂等 `getFitness():init()`，并隔离 AI 朝向微调对 `stopOnRun/Aim` 的误触发 |
| **E4** | **非本地角色 `AddXP` 参数签名陷阱**：原版 Lua 2/3/4 参 `AddXP` 对 `isLocalPlayer() == false` 角色直接提前 `return`（`BACKLOG 2026-11-05`） | M4 NPC 技能成长必须统一调用 **6 参数版 `AddXP`** |
| **E5** | **非本地角色 `HaloTextHelper` 飘字串台**：`IsoPlayer.getIndex()` 为 `final` 且默认返回 `0`，原版升级/采集会把 NPC 头顶飘字刷给玩家 0（`HaloTextGuard.lua`） | M2 通过微内核 `ctx:wrap` 挂载 `HaloTextHelper` 非本地角色过滤守卫 |
| **E6** | **野外采集 `forageIcons` 穿屋刷点**：`forageSystem.createForageIcons` 仅在 `metaZone` 随机撒点而不验地板，且 `ISSearchManager` 强绑玩家屏幕 UI（`BACKLOG 2026-11-15`） | M4 采集必须调用 `forageSystem.isValidSquare(sq, itemDef, catDef)` 过滤室内/不可站格，**严禁为 NPC 实例化 `ISSearchManager` 面板** |
| **E7** | **Kahlua2 `-debug` 模式局部符号表溢出**：普通模式编译通过的大函数/大文件，在玩家开 `Core.debug=true` 时会因调试符号表超限直接编译失败（`BACKLOG 2026-11-01`） | 保持模块短小（如 `tickAI <= 150` 行），并在 V2 [build.ps1](../build.ps1) 中补入 `Core.debug=false / true` **双模式 Kahlua 字节码编译检查** |

### 3. 架构决策取舍台账（`DEC-001 ~ DEC-012` 继承与废弃对照）
详见本文 **第三节**。明确哪些设计思想保留、哪些机制被 V2 两层架构取代，防止在后续开发中不知不觉重走 V1 老路。

### 4. 可直迁「黄金纯净资产」白名单 vs 「严禁迁入」黑名单
- **允许按需清洗迁入 V2 的纯净资产**：
  1. **动画与业务图标资产**：`EmberRoutes/Contents/mods/EmberRoutesProbe/42/media/AnimSets/player/`（坐卧动画配置）及 `EmberRoutes` 专属业务图标（通用 UI 边框、基础图标与主题直接复用前置模组 `EmberUI`）；
  2. **无状态 UI 组件库（已废除自建迁移，统一改用前置 `EmberUI`）**：不再从 V1 迁入 `Theme / CachedList / CardPanel / StepperInput` 等组件代码，所有界面统一基于 `EmberUI` 构建并通过 `PageLifecycle.attachExternalScope` 对接微内核 `ctx:scope`；
  3. **纯领域规则与算法**：`SmartZoning.lua`（容器启发式初始分类）、农作物种子/病害映射知识、`DeadBodyAtlas` 静态头像抓取、以及独立无耦合的 `java/.../llm/LlmBridge.java` + `LlmSettings.java`；
  4. **中英词条语料**：从 V1 `IG_UI.json` 按模块挑取所需玩家可见词条，清洗掉旧内部状态词并统一校验 `%1..%9` 占位符。
- **严禁迁入 V2 的黑名单（Toxic Assets）**：
  - `JobStore`、`LaborStore`、`LaborCommitKernel`、`ClaimsKernel`、`ObligationLedger`、`TaskDirectory`、`ExitSave`、全部 `*JobRecovery` 冷恢复适配器，以及全部基于 `package.loaded` 篡改的旧单元测试桩。

### 5. 关键数值平衡与节拍参数表（防拍脑袋重调）
提取 V1 已校准的关键运行参数作为 V2 初始基线：
- **生理需求工作/空闲双阈值滞回**（`NeedsPolicy`）：空闲态较早进食饮水（如饥饿 `0.25` / 口渴 `0.20`），**一旦进入工作态耐受阈值抬升至 `0.50`**，根除“刚拿上斧头微饿就丢下活跑回厨房”的乒乓震荡；
- **寻路与移动保护参数**：同屏多 NPC 寻路配额仲裁（每 `16ms` 物理帧最多放行 1 次重型路径搜索，其余 FIFO 排队）、失败目标坐标级退避冷却（`500ms~1500ms`，仅封禁同一 `(x, y, z)` 失败点，不连坐封杀相邻候选站位）、实心格卡死脱困阈值 `1.5s`、家具起身硬超时 `3.0s`；
- **仓储与搬运参数**：容器满载预警线 `90%`、内存软预约锁默认 TTL `30s`、借用工具空闲归还触发延迟 `15s~30s`。

### 6. 实机「十大试金石异常场景」（取代脆弱的假桩单测）
将 V1 历史上反复翻车的真实场景固化为 V2 各阶段实机必测清单：
1. **跨层叠箱测试（M1）**：含地下室（`Z=-1`）、一楼、二楼及同格三层叠放木箱的领地增删与重进存档；
2. **暂停态交互测试（M1/M4）**：按 `Space` 暂停游戏时划区、修改过滤规则、查看库存列表是否立即刷新无卡死；
3. **10 人倍速离屏原件守恒（M2）**：10 名携带带 `ModData` 工具与武器的 NPC 开 `3x` 倍速、玩家开车远离卸载区块再返回、存退重进，核验全员与物品 `id` 100% 不变；
4. **俯卧撑与看书抗打断测试（M3）**：NPC 开始锻炼或看书后，外界容器变动或存在缺料订单时，绝不出现“1 秒起身闪烁”或“拿书立刻还书死循环”；
5. **跨楼层找座与床头朝向（M3）**：NPC 在二楼触发阅读/睡眠前往一楼椅子/双人床，验证楼梯转角不卡顿、不瞬移、床头朝向与原版 `Facing` 一致；
6. **多源混装与中途满仓（M3）**：杂物箱同时放入食物、药品、工具，验证 NPC 一趟打包巡回分拣入冰箱/药柜/工具箱；中途塞满目标箱时平滑换箱或放回原箱，不卡死；
7. **干活饥饿抢占与借用工具归还（M3/M4）**：NPC 借斧头砍树途中调高饥饿值触发去厨房吃饭，验证先停动画、吃完继续或空闲后凭工具 `ModData` 来源戳自动把斧头还回原箱；
8. **锁门与钉死门局部绕行（M2/M4）**：必经之门被钉木板或无钥匙上锁时，触发局部绕行或明确报不可达退避，不穿墙、不无限撞门；
9. **睡觉/干活瞬间直接点退出游戏（M2/M3）**：零等待立即存盘退出，重进存档在安全格恢复且无冻结报错；
10. **`-debug` 模式与热门工坊模组混装启动（全域）**：开启 `-debug` 参数并混装工坊车辆/武器/UI 模组，验证 Kahlua 编译 0 溢出、右键菜单开关不互锁。

### 7. V1 冻结基线元数据
- 封存路径：`C:/Users/MuMu/Zomboid/Workshop/EmberRoutes`（最后版本标记 `0.29.69`，只读参考，不再向其合入新功能）。

---

## 三、V1 架构决策复盘与 V2 演进对照表（`DEC-001 ~ DEC-012`）

| V1 决策编号与主题 | V1 当时的背景与初衷 | V1 实际落地暴露的副作用 | V2 的正式裁决与替代设计 |
| :--- | :--- | :--- | :--- |
| **DEC-001**<br>单业务链切分纪律 | 防止按代码目录切任务导致半截子工程 | 方向正确，但后期多会话并行开发打破了单链实机验收节奏 | **强化升级**：固化为 [MODULE_MAP.md](MODULE_MAP.md) 的 M1–M6 串行实机门禁，M4 严格一次只接一个工种 |
| **DEC-002 / 008**<br>版本化不可变快照 | 消灭跨层深拷贝开销与并发读写脏数据 | Lua 层仍残留不少状态包装与 `UPDATING` 中间态假死 | **下沉 Java 极简化**：如 [WarehouseFastIndex.java](../java-src/src/main/java/emberroutes/warehouse/WarehouseFastIndex.java) 直接维护 `revision` + 常驻只读 `front` 快照表 |
| **DEC-003**<br>废除双实现，收敛单一权威 | 早期 Lua/Java 双实现按 `resources>=4` 切换，维护两套不变量极易分叉 | 收敛方向完全正确，但把 `JobStore` 事务写进 Java `LaborCommitKernel` 仍背负了沉重账本 | **保留“单实现零 fallback”，剥离事务账本**：Java 专职空间/容器索引、寻路与 `ByteBuffer` 快照；Lua 专职规则与轻量调度 |
| **DEC-004**<br>`Claims` 与 `Obligation` 双持久账本 | 试图保证任意断电/异常下工具、持货、炉火责任可证明结清 | **V1 最大复杂度源头**：催生 `COLD_ADOPT_FAILED`、`RECONCILE_REQUIRED`、`CommitFence`，异常即冻结据点 | **彻底废弃**：物理原件本就存于世界容器或 `IsoNPC` 真实背包中；改用 **内存 `30s` 软预约 + 工具自身 `ModData` 来源戳空闲归还** |
| **DEC-005**<br>资产唯一结算入口（离屏接缝） | 防止宏观模拟与在场实体双写复制物品 | 原则完全正确，是商队虚实转换的安全底线 | **完整保留核心不变量**：在场期仅实体操作实物；离屏 `phaseOut` 卸载实体入 `VehiclesDB2 / ByteBuffer` 后才移交宏观路线推进 |
| **DEC-006**<br>入口形状校验 + 执行点复验 | 消除中间调用链层层重复全量校验 | 有效降低了中间层冗余守卫 | **保留精髓**：UI/配置提交时验形状，动作真正落地（取物/放物/扣料）瞬间直接向原版容器/逻辑复验实物 |
| **DEC-007**<br>分级工作量预算与降级序 | 防止无依据硬写“50ms”伪预算或丢弃关键结算 | Lua 侧自建复杂预算调度器本身带来了额外的心智与代码负担 | **用架构消除轮询税**：容器靠 `@Patch` 无锁脏队列实现静止 **0 扫描**；寻路靠单帧 1 人仲裁队列削峰；`tickAI` 低频分摊 |
| **DEC-009**<br>唯一领域注册面 | 解决新增工种需到处改硬编码清单与退休表的问题 | 受制于 V1 既有 126 条跨域边，只做到了元数据表面收敛 | **升级为裁剪版 Cordis 微内核**（见 [ARCHITECTURE.md](ARCHITECTURE.md) §3–§4）：工种、分类器、UI 页签与 `Compat` 插件统一走声明式注入与 LIFO 回收 |
| **DEC-010 / 011**<br>安全分档位化、士气慢变量不碰原版数值 | 拒绝给玩家虚假精确数字，禁止无依据篡改原版角色战斗/健康属性 | 避免了数值崩坏与玩家误解 | **完整保留产品原则**：UI 展示实数与清晰阻塞原因（如缺何种材料/工具），不造伪科学百分比 |

---

## 四、V1 核心困扰缺陷统计与 V2 根治方案设计

以下汇总截至 V1 `0.29.69` 最困扰开发与实机体验的 **6 大顽疾**，并明确 V2 的根治机制与闭环落点：

### 顽疾 1：闲暇与工作调度震荡（“俯卧撑 1 秒起身”、“拿书立刻还书”、状态高频闪烁）
- **V1 病灶**：`SelfCare`、`Leisure`、`PublicWork`、`Hauling` 各自维护轮询状态机，发现潜在工单（哪怕后续因缺工具/缺料或 `FORAGE_REFRESH` 根本无法执行）就先发信号打断当前闲暇，认领失败后又退回闲暇，形成高频抢占死循环（见 `EmberRoutes/build/diagnostics-20261116-7fa129d5/findings.json`）。
- **V2 根治设计（M3 单一 `tickAI` + 前置实证预检 + 驻留锁）**：
  1. **消灭多状态机横跳**：每个 `IsoNPC` 仅由单一、顺序求值的 `tickAI`（控制在 150 行内）驱动：`逃离威胁 -> 紧急医疗/重度饥渴 -> 睡眠 -> 可立即执行的当班工种 -> 闲暇`；
  2. **未拿齐凭证绝不打断身体**：工种候选必须在只读预检阶段确认「源物资存在 + 工具可用 + 目标工位未满」并拿到内存 `30s` 软预约后，才允许接管身体；
  3. **最小驻留防抖 + 闲暇 `TEMP_PROP`**：非紧急生存危机（如遇袭、重伤、极度饥渴）不抢占未满最短驻留窗口（如 `10s~15s`）的闲暇姿态；闲暇阅读/吸烟等优先使用纯表现层 `TEMP_PROP` 临时道具（打断即销毁，零借书还书跑腿死循环）。

### 顽疾 2：室内寻路穿墙、楼梯卡角、跨层选座失败与起身瞬移
- **V1 病灶**：自研 `EmberNavGrid` 曾误判墙体标志与斜角碰撞；对骨骼根运动乘 `speedScale` 导致滑步；跨楼层家具（`actor Z=1, chair Z=0`）直接调原版家具寻路 0 位移失败；到达或退出家具时调全局 `stopProbe` / 硬写坐标引发瞬移。
- **V2 根治设计（`NavSandbox` 实机调优后合入 M2 `NpcLocomotionPort`）**：
  1. **单一寻路权威**：远近统一调用原版 C++ `PathfindNative`，主线程用 `PolygonalMap2.instance.lineClearCollide(..., 7)` 做实时贪心视线拉直与亚格落点还原，仅对锁门/钉死门/禁翻高墙做 `20` 格局部绕行缝合；
  2. **跨层家具两段式接近**：目标床/椅在不同楼层或远距离时，先查 `SeatingManager.getInstance()` 取得目标家具合法邻接站立格，用主干寻路跨楼层走到该站立格旁，到位后再触发入座动作与内存座位锁；
  3. **1:1 原生根运动与 `3.0s` 硬复位**：绝不缩放动画根位移向量；打断起身走原生退出动画，超过 `3.0s` 未退完由硬超时保底复位，杜绝永久卡死在椅子/床上。

### 顽疾 3：搬运遇瞬时阻挡即放弃身体、单件折返跑与满仓锁死
- **V1 病灶**：`HaulAgent` 遇 `0.5s` 寻路受阻即转 `PAUSED` 释放身体，被其他低优行为趁虚而入；满仓或中断触发复杂持久 `CARRIED_ITEM Obligation`。
- **V2 根治设计（M3 容器级 `SmartZoning` + 内存批次搬运器）**：
  1. **一趟多源集货与多目标巡回**：按 NPC 背包剩余负重与目标容器 `SmartZoning` 规则 + `30s` 内存容量软预约，一趟装满、按距离顺路依次投递；
  2. **动作器内局部退避**：门前拥挤或瞬时受阻在当前移动端口内做短时等待/换邻格重试，不轻易放弃当前搬运批次；
  3. **零烂账降级**：投递瞬间若目标箱被玩家塞满，当场重选同规则次优箱；若全满则放回原处并进入该分类短时冷却，不写任何持久锁。

### 顽疾 4：异常中断引发 `RECONCILE_REQUIRED` 冻结整个据点或工坊
- **V1 病灶**：将内存态未知结果持久化为阻塞门禁（`CRAFT_SCOPE_RECONCILE_REQUIRED`、`SAVE_RECONCILE_REQUIRED`），把开发期的防御断言转嫁成了玩家的死档体验。
- **V2 根治设计（两层分离 + 实物自愈）**：
  - 彻底删除 `Reconcile` 概念；一切以世界容器与 NPC 背包中的**真实物品实例**为准：中断留在背包里的物资会在下一轮空闲分拣自然入仓，借出的工具凭 `item:getModData()` 来源戳自动归还。

### 顽疾 5：生产工种缺料隐蔽失败与嵌套合成复杂度失控
- **V1 病灶**：早期清洁液空瓶、缺喷雾/种子时静默失败或反复无效认领；曾纠结是否在砍树/农务内部嵌套自动手搓锯板/配药。
- **V2 根治设计（M4 原版 `CraftLogic` 诊断 + 库存双阈值解耦）**：
  1. **缺料透明可见**：工坊与生产直接复用原版 `HandcraftLogic / CraftLogic.debugCanStart` + `CraftRecipeMonitor`，在 UI 上直接标出精确缺口（缺何种原料、工具或工作台）；
  2. **工种间通过真仓库解耦**：伐木工只管把原木交回仓库，木工台通过维持库存双阈值规则（如木板低于下限自动开工锯板至上限）消费原木，职责正交零嵌套。

### 顽疾 6：目录无序平铺、全局钩子泄漏与第三方模组互踩
- **V1 & V2 M1 早期病灶**：
  1. **目录缺乏适度归类**：V1 的 `emberroutes/probe/` 平铺 50+ 个 Java 文件、`UI/` 单目录堆积 132 个 Lua 文件；而 V2 M1 初版也把所有 Lua 脚本与 Java 类全部直接平铺在根目录；
  2. **全局副作用裸奔**：散装 `Events.*.Add` 无卸载、裸写 `ISWorldObjectContextMenu.disableWorldMenu = false`、`prerender` 内无意调 `SettlementStore.list()` 新建临时表、[WarehouseFastIndex.java](../java-src/src/main/java/emberroutes/warehouse/WarehouseFastIndex.java) 硬编码英文物品子串分类。
- **V2 根治设计（[ARCHITECTURE.md](ARCHITECTURE.md) §3 & §7 微内核 + 按需分层目录）**：
  - **实用主义领域分包**：不设死板的文件数上限或单文件套壳，但拒绝把所有业务堆在根目录——核心入口（`Main.java` / `EmberRoutesBridge.java`、`Kernel.lua` / `Boot.lua`）留根，业务代码按 `warehouse / npc / patches` 与 `Settlement / Warehouse / Npc / Work / UI / Compat` 自然归类，后续随模块增长与维护需要再渐进拆分子目录；
  - **微内核生命周期托管**：统一经 `Kernel.lua` 托管会话级 `ctx:on / ctx:effect / ctx:wrap` 与面板级 `ctx:scope` + `acquireGlobalFlag` 引用计数守卫；分类升级为“插件规则链 + B42 原版 `ItemTag` 优先”，第三方模组适配全部隔离在 `Compat/<ModKey>.lua` 内按需挂载、退档逆序清零。

---

## 五、重心切换执行路线建议（Immediate Transition Checklist）

切入 V2 日常开发时，建议按以下顺序稳步推进：

1. **封存 V1 仓库**：保持 `EmberRoutes` 目录只读，仅在开发对应模块时按 [MODULE_MAP.md](MODULE_MAP.md) 与本文白名单查阅算法/UI 组件源码。
2. **加固 V2 目录归类、微内核与构建地基（M1 收口期）**：
   - **适度归拢 M1 现存平铺文件**：按 [ARCHITECTURE.md](ARCHITECTURE.md) §7.2 将仓储索引类归入 `emberroutes/warehouse/`，将平铺 Lua 业务脚本归入 `Settlement/`、`Warehouse/`、`UI/`，保留清晰的根入口；
   - **补齐核心构建检查**：在 [build.ps1](../build.ps1) 中加入核心分层方向检查（防 `shared -> client` 或业务反向引 `UI/Compat`）以及 **Kahlua 普通 + Debug 双模式编译检查**（防 `-debug` 符号溢出）；
   - **接入 `Kernel.lua`**：收敛当前 M1 的 `SettlementStore` 可解绑订阅、`WarehouseWatcher` 会话事件、`ZoneEditorPanel` 的 `disableWorldMenu` 引用计数守卫、`DebugWarehousePanel` 的严格零 GC 脏检查顺序，并开放 `WarehouseFastIndex` 分类规则注入孔；
   - **M1 实机通关验收**：覆盖地下室、多层建筑、同格叠箱、暂停态交互与重进存档。
3. **合入 `NavSandbox` 寻路成果开启 M2**：在 M1 实机验收闭环后，将 `NavSandbox` 验证成熟的移动方案接入 M2 `IsoNPC` 与 `NpcLocomotionPort`，逐级通关 M2 $\rightarrow$ M6。
