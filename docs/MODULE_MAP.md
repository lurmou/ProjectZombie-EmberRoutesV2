# EmberRoutes V2 模块路线图、旧库遗产对标与 B42 原版引擎基线

> 本文件是防失忆单一路由入口。**开启任一模块前，必须先结合 [系统架构规范 (ARCHITECTURE.md)](ARCHITECTURE.md)、[V1 复盘与缺陷根治参考 (V1_RETROSPECTIVE_AND_MIGRATION.md)](V1_RETROSPECTIVE_AND_MIGRATION.md) 并读取本表对应行指定的旧库参考文件核对原产品意图，严禁凭空脑补概念。**
> 完整前序调研只读参考：
> - 旧仓库（只读）：`C:/Users/MuMu/Zomboid/Workshop/EmberRoutes`
> - 寻路实验沙盒：`C:/Users/MuMu/Zomboid/Workshop/NavSandbox`
> - 真 `IsoPlayer` 参考（只读）：`D:/Project/ProjectRemnants`

---

## 一、B42 原版引擎字节码实证基线（该用谁、禁碰谁）

### 1. 必用的原版现成能力（拒绝重复造轮子）
- **全局寻路与平滑（待 `NavSandbox` 调优合入）**：远近统一只用 C++ `PathfindNative` 单一全局权威（不搞 35 格距离切换防交界震荡）+ 主线程 `PolygonalMap2.instance.lineClearCollide(..., 7)` 贪心拉直与亚格落点还原（绕开 `isNpc() == true` 的原版三重阉割）+ 仅对锁门/钉死门/禁翻高墙做 `20` 格局部小窗绕行缝合 + `1.5s` 陷入实心格邻格脱困。
- **室内与自建屋顶判定**：直接调 `IsoRegions.getIsoWorldRegion(x, y, z)`（转为 `IsoWorldRegion` 查 `isEnclosed()` / `isPlayerRoom()` / `isFullyRoofed()`，原生支持玩家自建墙与屋顶）。
- **容器变动零轮询感知**：原版 `Events.OnContainerUpdate` 全 JAR **0 次触发**，且 `DoRemoveItem / clear` 直接写字段不调 `setDirty`；统一由 [Patch_ItemContainer.java](../java-src/src/main/java/emberroutes/Patch_ItemContainer.java) 钩住底层 `items` 增删与方块摆拆入口写无锁脏队列。
- **多容器合成、自动机器与阻塞诊断**：随身/桌面制作调 `HandcraftLogic`；熔炉/晾干架/酿造桶/工坊台调 `CraftLogic / FurnaceLogic / DryingLogic`（离屏自动由原版 `MetaEntitySystem` 计时）；缺料/缺工具诊断直接调 `CraftLogic.debugCanStart` + `CraftRecipeMonitor`。
- **入座、畜牧与农耕**：坐姿几何调 `SeatingManager.getInstance()`（寻路前加内存座位锁补原版入座途中未占座漏洞）；畜牧直接读玩家原版 `DesignationZoneAnimal`（`getTroughsConnected / getHutchsConnected`）；农耕直接调服务端 `SFarmingSystem.lua`。
- **商队公路网、真车离屏、外部聚落与电台**：
  - 公路拓扑直接读原版 `WorldMapStreets`（`media/maps/Muldraugh, KY/streets.xml`，`getClosestPointOn` + `getIntersections()` + `getPointOn`）；
  - 真车虚实转换调 `BaseVehicle.save(ByteBuffer, false)` / `load(...)` + `VehiclesDB2.instance.addVehicle / removeVehicle`（100% 保留后备箱原件 `id` 与车钥匙 `keyId`）；
  - 外部 AI 聚落从 `IsoMetaGrid` 未加载 `BuildingDef / RoomDef` 按真实房间类型推导产销特长；
  - 广播走 `ZomboidRadio`（`DynamicRadioChannel` + `SendTransmission`），大地图图标走 `WorldMapMarkers.addGridSquareMarker`。

### 2. 已证实的原版深坑与绝对禁令
- **`ZombieBuddy` `@Patch` 根包铁律**：`PatchEngine.collectPatches(pkg, cl)` 在传入非空 `ClassLoader` 时会跳过 `Loader.g_known_jars` 导致 Java 17 Agent 动态挂载 JAR 扫出 `0 classes`，且包名用 `equals("emberroutes")` 精确匹配；**所有 `Patch_*` 类必须一律直接放在根包 `emberroutes` 下（禁止新建 `emberroutes.patches` 子包）**，由 `Loader` 原生单次扫描织入。
- **禁污染 `IsoMetaGrid.Zone` 与 `DesignationZone`**：往 `IsoMetaGrid.registerZone` 写自定义类型会导致翻箱子时 `ItemPickInfo` 持续刷 `cannot get ID for zone` 并占 `int[32]` 槽位；`DesignationZone.load` 硬编码仅认 `"AnimalZone"` 读档必丢。据点空间归属统一在 Java `WarehouseFastIndex` 内自管 2D 矩形查表。
- **禁碰 `item:getAttributes()` 与 `item:ModDataMatches`**：`AttributeContainer` 封闭枚举无单属性长度头，异常即永久删玩家物品；`ModDataMatches` 原版字节码写成 `other` 跟自己比恒返 `true` 且强分配空表。轻量归属戳统一用 `item:hasModData()` 判空 + `item:getModData()`（用完置 `nil` 保 RLE 压缩）。
- **禁持久化 `ItemContainer.id` 或 `IsoObject.getEntityNetID()`**：前者不存盘，后者高 24 位拼同格对象下标（扫掉血迹即变 ID）；世界容器统一用 `x:y:z#sprite:kind:contIdx#stackOrd` 定位（兼容同格三层叠放木箱）。
- **网格框选禁信 `worldobjects[1]:getSquare()`**：室内右键射线先命中前景半透明切面墙/屋顶导致跳墙角；空间选点必须统一用 `math.floor(screenToIsoX / screenToIsoY)` + 左键拖拽（`onMouseDownOutside / Move / Up`）。
- **B42 `Translator.getText` 格式坑**：JSON 译文严禁写 `%d / %f / %s`（无参调 `getText` 必抛 `MissingFormatArgumentException`，传 Lua `Double` 必抛类型不匹配），统一用 `%1..%9` 传字符串参数。

---

## 二、M1–M6 模块规划与开工必读遗产对照表

| 模块 | 核心产品边界（严禁偏离或瞎造概念） | 动工前必读的旧库 / 参考源码（只取精华，禁抄旧账本） | 实机通关门禁 |
| :--- | :--- | :--- | :--- |
| **M1：据点划区与真仓储底座**<br>*(当前阶段)* | **空间上只有唯一的一层 `settlement.areas`（无任何“地面存储子区”！）**；左键按住拖框（支持增/擦/撤销，实时预览不规则融合外轮廓 + 编辑态 3D 地面深度半透明铺底，0 内部分割缝），保存瞬间自动扫描领地内全楼层（含地下室 `minLevel..maxLevel`）全部世界容器，由 `@Patch` 零轮询增量更新。 | 旧库：`SettlementGeometry.lua`、`SettlementSelector.lua`（`#L240-L298` 左键拖框）、`WarehouseFastIndex.java`、`settlement-storage-architecture.md` | 左键任意增删 L/U/回字形不规则领地无内缝，保存立显全屋/地下室/二楼库存；放拿物品、拆建木箱毫秒跳变，静止 `0` 脏事件；重进存档原样恢复。 |
| **M2：`IsoNPC` 持久躯壳与运动解耦契约** | 非玩家槽 `IsoNPC extends IsoPlayer`（带相机/全局主角指针 `try/finally` 保护与 `AIComponent` 同步）；`ByteBuffer` 外观/状态/背包/穿戴/手持 100% 原件存读档 + 死亡防摸尸双份复制；预留每实例 `NpcLocomotionPort` 接口待 `NavSandbox` 合入。 | `NavSandbox`：`SandboxNpc.java`、`NavNavigator.java`<br>`ProjectRemnants`：`NPCManager.java`（`#L1553-L1895` `ByteBuffer` 快照，**不采纳**其 `registerInPlayersSlot`） | 同屏刷 10 名 NPC 发不同装备/带 `ModData` 物品，开 3x 倍速、离屏卸载再返回、存退重进，角色与背包原件 `getID()` 100% 不变。 |
| **M3：原子动作器、单一 `tickAI` 自理与容器级分拣搬运** | `BaseAction` 视觉壳 + 确定性计时结算（打断直接停动画零烂账，`3.0s` 起身超时硬复位）；150 行内 `tickAI`（逃离→吃喝包扎→睡眠→`TEMP_PROP` 闲暇）；**迁入 `SmartZoning` 在容器自身自动初始化分类过滤规则** + 内存 `30s` 软预约 + 借用工具 `ModData` 来源戳自动归还。 | 旧库：`SmartZoning.lua`、`BatchStoragePainting.lua`、`ContainerConfiguration.lua`<br>`ProjectRemnants`：`NPCCraftRecipeExecutor.java`、`LeisureTemporaryItemManager.java`、`SafehouseSleepSystem` | 5 名 NPC 挂机 2 游戏日自动吃真粮睡真床；往杂物箱丢物资自动按容器 `SmartZoning` 分拣入对应冰箱/药柜/木箱；中途打断借用的工具自动归还。 |
| **M4：生产工种串行通关 + `EmberUI` 正式界面落地** | **严格一次只接一个工种**：① 伐木锯板 → ② 农耕畜牧 → ③ 烹饪看火 → ④ 工坊配方订单（基础生存配方当班临时授权，维持库存双阈值防震荡）；每通一个工种基于前置框架 `EmberUI` 落地对应页面（含 `mod.info` 声明 `\EmberUI` 依赖）。 | `EmberUI`：`EmberUI.lua`、`Shell/UI.lua`、`Shell/SlotRegistry.lua`、`Widgets/*`<br>旧库：`2026-10-24-settlement-ui-ux-master-guide.md`<br>`ProjectRemnants`：`NPCRecipeKnowledge.java` | 每个工种单独实机跑满“缺料清晰提示 → 补料开工 → 饿了吃饭 → 回来交货入仓”全链路。 |
| **M5：随从编队、守卫防务与对话** | 迁入 `SquadHUD` 与跟随编队；守卫巡逻、近远战切换、弹药补给与公平防黑枪缓冲；按需接入 `llm/` 对话桥。 | 旧库：`UI/SquadHUD.lua`、`SquadService.lua`、`UI/DialogueWindow.lua`、`java/.../llm/` | 带 3 名随从外出搜刮遭遇尸潮，验证 ROE 交战、撤退、包扎与回据点交接。 |
| **M6：多社区经济、真车商队与活世界** | 多据点盈缺与实物易物合同；商队装真车沿 `WorldMapStreets` 推进，抵近按 `60m/100m` 迟滞环 + `120°` 视野锥由 `VehiclesDB2` 真车显化；联动 `ZomboidRadio` 电台与 `WorldMapMarkers` 地图图标。 | 旧库/文档：`GDD/TDD v5.1 §2.5 & §7` | 签跨镇易物合同，目送商队装真货开车离场、收音机播报路况、带回换购真货，全程原件与原车钥匙 100% 守恒。 |
