# EmberRoutes（余烬商路 V2）：项目必读

> 仓库定位：正式干净重建版（Mod ID `EmberRoutes`，包名 `emberroutes`）。单模块逐级实机验证闭环后再推进下一模块。

## 1. 开工第一铁律（防脑补与防失忆）

- **动笔前必读架构与路由表**：任何模块设计、新增功能或重构前，**必须先读取 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)（分阶段仿真内核与兼容契约）及 [docs/MODULE_MAP.md](docs/MODULE_MAP.md) 中对应阶段的「原产品设计」「旧库必读参考文件」与「B42 引擎实证红线」**，并在方案中贴出具体代码文件路径与行号。严禁跳过旧库已有交互/领域设计凭通用游戏套路自行脑补概念（如禁止凭空发明“地面存储子区”、禁止两段式右键框选）。
- **原版 B42 全链路源码前置通读（严禁碎片化摸象）**：本机完整可读 Java 反编译源码位于 `D:/Software/GameApp/steam/steamapps/common/ProjectZomboid/zombie反编译源码`，原版动画树位于 `.../media/AnimSets`，原版 Lua 位于 `.../media/lua`（详见 [.agents/rules/vanilla-full-chain-audit.md](.agents/rules/vanilla-full-chain-audit.md)）。凡涉及引擎机制（运动、动画、TimedAction、战斗、容器、载具、UI），**动工前必须直接在 `zombie反编译源码` 中一次性通读从入口到结算的完整端到端调用链与真玩家原生分支**；严禁拿 `javap -c` 切局部片段盲人摸象，严禁未核清原版默认参数与门控条件就塞魔法倍率让用户反复实机试错。
- **异常反馈三步法（严禁凑数无价值方案）**：收到报错或交互异常反馈时，严禁直接打补丁改代码；必须先查日志（`C:/Users/MuMu/Zomboid/console.txt`）与调用链定位根因并给出根治方案，**等用户确认后再改代码**。**严禁为了凑方案数量提出治标不治本、留有已知隐患或明显劣质的无价值陪跑方案**：若根因明确且仅有唯一彻底的根治方案，只给出该唯一正选方案；仅当确实存在多个各有真实工程/产品取舍的有效路线时，才列出多方案权衡。
- **方案闭环三要素**：系统性方案必须明确指出「创建/挂载端」「正常释放/裁剪端」「异常打断/卸载/读档端」的代码位置。
- **进度同步单一权威（Linear）**：项目需求、里程碑（M1–M6）、任务状态与验收进度**统一通过 Linear（团队 `EmberRoutesV2` / Key `EMB`，项目 `EmberRoutes V2`）同步维护**；**严禁在本地新建进度单、状态台账或开发流水文档**。
- **协作入口**：涉及开发、PR 或 Linear 任务时，先读取并执行 [Linear–GitHub 工作流](.agents/rules/linear-github-workflow.md)；纯只读排查和仅入库依照其中的旁路处理。

## 2. 核心架构与边界红线

- **专注单人、零兼容代码**：不为联机预留冗余协议；严禁编写任何兼容旧格式、旧接口的转发壳或双轨 fallback。
- **Java 重核（85%）+ Lua 薄壳（15%）一步到位铁律**：凡 B42 `projectzomboid.jar` 已有 Java 类支撑的仿真、AI 大脑（`BrainRouter` / `EmberStateTree` / `StateScope`）、空间感知（`SmartObjectRegistry`）、组合拍卖（`SettlementTaskAuctioneer`）、动作器（`BaseAction`）、战斗导演（`EmberSquadDirector`）、工坊合成（`HandcraftLogic / CraftLogic`）与商队路网（`WorldMapStreets / VehiclesDB2`），**100% 直接在 Java 层一步到位实现**；Lua 层仅保留 `EmberUI` 界面、右键/环形菜单、声明式预设表、原版纯 Lua 系统（如 `SFarmingSystem.lua` / `forageSystem.lua`）的 15 行薄胶水以及 `Compat` 槽，**严禁“先在 Lua 写第一版核心逻辑、后期再往 Java 迁移”**。
- **B42 生命周期与跨语言硬防线**：
  - 内部核心业务 100% 静态 `require` 并遵守域级 DAG（禁字符串 DI、禁业务域私调总 `EmberRoutesJava.reset()`）；
  - `onModDataReady` 跑在后台加载线程，严禁触碰世界/实体/UI；二进制存盘必须强验 `loadedForCurrentSave` 门闩防版本升级空写覆写；
  - 高频帧逻辑走单入口 5 阶段双时钟管线（`frameTick` 管 CPU 分帧，`simTime` 管倍速业务超时），高频区块加载/卸载直连 Java `Patch_IsoChunk`；严禁跨帧缓存 `IsoPlayer` 引用（需兼容同档死亡重生 `OnCreatePlayer`）。
- **拒绝过度防御、性能优先**：
  - **严禁复辟旧版 6 层持久互锁账本**（无 `JobStore / ObligationLedger / ClaimsKernel` 持久锁，无向玩家暴露的 `Reconcile` 冻结态）。
  - **持久与瞬态两层分离**：宏观配置/规则/合同与离屏实体快照存盘；微观执行态（走位、读条进度、30s 软预约锁、闲暇临时道具）纯内存运行，借用工具通过物品自身 `ModData` 来源戳在空闲时自动归还。
  - **允许闪退有限回退**：不在后台区块高频热存（Hotsave）时全量序列化在场活跃实体，仅在正常退出/存盘（`GameWindow.save`）与离屏卸载（`phaseOut`）时生成快照，换取日常与高速驾驶 **0 主线程序列化税**。
- **100% 真实原件守恒与单点身体控制**：
  - 真 `IsoPlayer` 子类躯壳（严禁占用 `IsoPlayer.players[]` 真实玩家槽位，严禁伪装 `IsoZombie`）；
  - 物资、容器、车辆全链路保留真实实例与原 `InventoryItem.id`（严禁凭空刷物或删物/删车按类型重造）；
  - 每个 NPC 的移动与动作只经唯一身体控制器下发。

## 3. UI、国际化与验证门禁

- **按领域归类、按维护需求渐进拆分**：遵循 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) §7 目录约定——拒绝把所有源码无序平铺在根目录，按自然业务领域（如 `Settlement/`、`Warehouse/`、`Npc/`、`Work/`、`UI/`、`Compat/`）归类；不搞死板的文件数量上限或单文件硬套子目录，随模块体量与实际维护需要自然演进二级拆分。
- **零 GC 渲染**：`prerender / render` 阶段仅读预投影缓存，严禁每帧建表或扫描世界实体；关闭窗口必须对称释放监听与全局开关（如 `ISWorldObjectContextMenu.disableWorldMenu = false`）。
- **双语词条门禁**：`Translate/CN` 与 `Translate/EN` 100% 成对同步；**统一使用 `%1..%9` 位置占位符（字面百分号写 `%%`），严禁在 JSON 译文中使用 `%d / %f / %s`**。每次构建必跑 `./build.ps1`。
- **脱机原生 OpenGL 自检**：新增或修改 UI 面板布局后，必须运行 `./tools/preview-ui.ps1` 并回读导出的 CN/EN 帧缓冲截图核验无压字、无穿模、无溢出后方可交付实机测试。
