# EmberRoutes（余烬商路 V2）：项目必读

> 仓库定位：正式干净重建版（Mod ID `EmberRoutes`，包名 `emberroutes`）。单模块逐级实机验证闭环后再推进下一模块。

## 1. 开工第一铁律（防脑补与防失忆）

- **动笔前必读架构与路由表**：任何模块设计、新增功能或重构前，**必须先读取 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)（微内核与兼容契约）及 [docs/MODULE_MAP.md](docs/MODULE_MAP.md) 中对应阶段的「原产品设计」「旧库必读参考文件」与「B42 引擎实证红线」**，并在方案中贴出具体代码文件路径与行号。严禁跳过旧库已有交互/领域设计凭通用游戏套路自行脑补概念（如禁止凭空发明“地面存储子区”、禁止两段式右键框选）。
- **原版 B42 全链路源码前置通读（严禁碎片化摸象）**：本机完整可读 Java 反编译源码位于 `D:/Software/GameApp/steam/steamapps/common/ProjectZomboid/zombie反编译源码`，原版动画树位于 `.../media/AnimSets`，原版 Lua 位于 `.../media/lua`（详见 [.agents/rules/vanilla-full-chain-audit.md](.agents/rules/vanilla-full-chain-audit.md)）。凡涉及引擎机制（运动、动画、TimedAction、战斗、容器、载具、UI），**动工前必须直接在 `zombie反编译源码` 中一次性通读从入口到结算的完整端到端调用链与真玩家原生分支**；严禁拿 `javap -c` 切局部片段盲人摸象，严禁未核清原版默认参数与门控条件就塞魔法倍率让用户反复实机试错。
- **异常反馈三步法**：收到报错或交互异常反馈时，严禁直接打补丁改代码；必须先查日志（`C:/Users/MuMu/Zomboid/console.txt`）与调用链定位根因，给出至少 2 个方案权衡，**等用户确认选型后再改代码**。
- **方案闭环三要素**：系统性方案必须明确指出「创建/挂载端」「正常释放/裁剪端」「异常打断/卸载/读档端」的代码位置。

## 2. 核心架构与边界红线

- **专注单人、零兼容代码**：不为联机预留冗余协议；严禁编写任何兼容旧格式、旧接口的转发壳或双轨 fallback。
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
