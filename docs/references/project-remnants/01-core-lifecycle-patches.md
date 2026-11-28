# 01 核心运行时、全 21 字节码补丁、生命周期与多玩家夺舍防线

> 覆盖源码：`npcfw/patch/*`（21 个类）、`npcfw/core/*`（22 个类）、`npcfw/data/*`（2 个类）、`npcfw/ui/*`（3 个类）、`npcfw/modules/INPCModule.java`。

---

## 1. 全部 21 个 ASM9 字节码补丁深度规范（`npcfw/patch/*`）

`NPCBootstrap.setupBytecodePatches` 在 `premain` 阶段依次向 JVM `Instrumentation` 注册 21 个实现 `ClassFileTransformer` 的 ASM9 补丁，随后对 **18 个原版类**执行 `retransformIfLoaded`（防止比 Agent 更早加载的类逃逸织入）。ASM `ClassWriter` 在不改控制流时使用 `COMPUTE_MAXS(1)`，改分支时使用 `COMPUTE_FRAMES(2)` 并覆写 `getCommonSuperClass() -> "java/lang/Object"` 以避免转换期触发递归类加载。

### 1.1 输入、渲染、准星与鼠标隔离类（#1–#5, #9–#10）

1. **`IsoPlayerPatch`** → `zombie/characters/IsoPlayer`（5 个方法头/状态机注入）：
   - `getAimVector(Vector2)`: 有 `NPCVirtualAim` 虚拟瞄准返回虚拟向量；否则 `isNpc()` 返回 `forwardDirection`，禁止读取鼠标方向。
   - `setAngleFromAim()`: 由 `NPCVirtualAim.applyAngleFromAim` 接管；否则 `isNpc()` 直接提前返回。
   - `updateCursorVisibility()`: 若 `!NPCPossession.isCursorControlledCharacter(this)`，在方法入口直接 `return`（**防止 NPC 举枪进入 `isAiming()==true` 时因 `playerIndex==0` 调用 `Mouse.setCursorVisible(false)` 偷走真玩家鼠标**）。
   - `isWeaponReady()`: 若 `NPCPossession.isAIControlledOriginal(this)` 直接返回 `true`（放行托管原主角出手）。
   - `updateInternal2()`: 状态机定位第 8 次 `isAiming()` 后 `IFEQ`（跳过瞄准态键鼠 `moveVars.moveX/Y` 覆盖 `playerMoveDir`）与第 9 次 `isAiming()` -> `isJustMoved()` 后 `IFEQ`（跳过非瞄准移动态用 `forwardDirection` 覆盖 `playerMoveDir`），在 `isNpc()` 时短路跳过。
2. **`FBORenderCellPatch`** → `zombie/iso/fboRenderChunk/FBORenderCell.renderMovingObject(IsoMovingObject)`:
   - 入口校验：若 `obj instanceof IsoNPC && obj.isLocalPlayer()` 则直接返回（防止附身 `IsoNPC` 走本地玩家通道后再走世界物体通道产生双重重影）；
   - 后段拦截原版 `if (obj.getClass() != IsoPlayer.class)` 分支，若 `!obj.isLocalPlayer()` 则强行放行进渲染分支（防止被 AI 托管的真 `IsoPlayer` 原主角退居非本地后隐身）。
3. **`FBORenderAreaHighlightsPatch`** → `zombie/iso/fboRenderChunk/FBORenderAreaHighlights.render()`:
   - 方法头注入 `SafehouseAreaRenderer.renderQueued()`，将安全屋/农田/伐木高亮纳入 B42 原生 FBO 深度管线。
4. **`IngameStateActionProgressPatch`** → `zombie/gameStates/IngameState`:
   - `renderFrameInternal(int)`: 方法头注入 `NPCActionProgressBars.update(playerIndex)`，既更新 NPC 悬浮条也用玩家自身动作恢复可能被污染的 `UIManager.getProgressBar(idx)`；
   - `renderFrameTextInternal(int)`: 在 `WorldMarkers.render()` 调用前注入 `NPCActionProgressBars.render(playerIndex)`。
5. **`BallisticsControllerPatch`** → `zombie/core/physics/BallisticsController.update()`:
   - 将方法内全部 `INVOKESTATIC AimingReticle.getX/Y(I)I` 替换为压入 `this.isoGameCharacter` 后调用 `NPCVirtualAim.getReticleX/Y(I, IsoGameCharacter)I`，实现弹道目标从屏幕鼠标坐标向 NPC 虚拟瞄准逆投影坐标的精确分流。
6. **`IsoGameCharacterNamePatch`** → `zombie/characters/IsoGameCharacter`:
   - `renderlast()`: 对符合条件（存活、非当前主控、玩家阵营且配置开启）的 NPC/AI 原主角强行置 `canSeeCurrent=true`、`drawUserName=true` 并更新名字对象；
   - `updateUserName()`: 将 `GETFIELD IsoPlayer.remote` 与前两次 `BooleanServerOption.getValue()` 替换为基于角色权限的静态门控，使单人模式直接使用原版 `TextDrawObject` 头顶名牌样式。
7. **`UIWorldMapPlayerPatch`** → `zombie/worldMap/UIWorldMap.render()`:
   - 紧接首次 `renderRemotePlayers()` 调用 `NPCVanillaPresentation.renderNPCsOnMap(this)`，在 `Players` 图层开启且 `displayZoomF < 20.0F` 时通过反射调 `renderPlayer/renderPlayerName` 在地图上画所有存活 NPC（含离屏与车内坐标）。

### 1.2 战斗、碰撞与生理/成长放行类（#6–#8, #11–#13, #17–#18）

8. **`CombatManagerPvpPatch`** → `zombie/CombatManager.checkPVP`:
   - 方法头短路：仅当双方均为 `IsoPlayer`、至少一方显式绑定 `factionId` 且阵营关系为 `HOSTILE` 时返回 `true`，彻底锁死同阵营与中立友伤。
9. **`IsoGameCharacterStatsPatch`** → `zombie/characters/IsoGameCharacter.updateStats_WakeState/updateThirst`:
   - 将两方法中所有 `IsoPlayer.getInstance()` 替换为 `PartyRestCoordinator.getVanillaStatsGatePlayer(this)`——只要是 AI 原主角、附身态、`COMPANION` 或 `FACTION` NPC，均返回 `this` 使其通过 `this == IsoPlayer.getInstance()` 门控正常演化疲劳与口渴。
10. **`IsoGameCharacterWeaponReadyPatch`** → `zombie/characters/IsoGameCharacter.isWeaponReady`:
    - 对 `NPCPossession.isAIControlledOriginal` 直接返 `true`（与 `IsoPlayerPatch` ④ 形成双保险；`IsoNPC` 自身已覆写返 `true`）。
11. **`SwipeStatePlayerAttackGatePatch`** → `zombie/ai/states/SwipeStatePlayer`:
    - `OnAnimEvent_AttackCollisionCheck`: 就地替换 `IsoPlayer.isLocalPlayer(IsoGameCharacter)` 为 `shouldRunAttackCollision(chr)`（本地玩家优先并清标记 → 已取消攻击或刚结算过 `NPCFWDirectRangedShot` 的远程枪返 `false` → `IsoNPC`/AI 原主角返 `true`）；
    - `isStompingDisabled(chr, req)`: 当 `req==true` 且为未取消攻击的非附身 NPC / AI 原主角时提前返 `false`（允许对倒地目标踩踏）。
12. **`IsoPlayerIsJustMovedPatch`** → `zombie/characters/IsoPlayer.isJustMoved`:
    - 只要 `isNpc()` 且 `playerMoveDir` 非零即返 `true`，修复 AI 原主角与 NPC 无键鼠输入时 `isJustMoved()==false` 导致的动画不播、原地滑步问题。
13. **`IsoMovingObjectBumpPatch`** → `zombie/iso/IsoMovingObject.separate`:
    - 要求双钩同时命中：
      - **钩 A（`shouldStartBump`，常驻）**：角色当前 `ActionContext` 状态名若已是 `"bumped"` 或 `"bumped-bump"`，跳过 `setLastBump`/`setBumpedChr`/上报 `"wasBumped"` 事件（防止连续刷新撞击僵直计时）；
      - **钩 B（`shouldSkipBump`，受沙盒 `DisableNPCBumping` 控制）**：当碰撞双方均为 `IsoPlayer` 且至少一方为 `IsoNPC`（玩家↔NPC、NPC↔NPC，排除丧尸）时走原版 `AttackType.CHARGE` 短路分支，**跳过踉跄绊倒动画，但完整保留后续 `setNextX/Y` 物理推开**。
14. **`XPShortOverloadPatch`**（4 个短重载）与 **`CraftRecipeXPPatch`**:
    - 拦截非 `isLocalPlayer()` 的 `IsoNPC` 与 AI 原主角，绕过本地玩家门控改调 6 参 `AddXP(..., showHalo=false)` 真实加经验，并按规则触发 `NPCFloatingXPText` 悬浮绿色经验字。

### 1.3 死亡接管、存档保护、载具脱困与日志噪音控制（#14–#16, #19–#21）

15. **`IsoPlayerDeathEventPatch`** → `zombie/characters/IsoPlayer.OnDeath`: 重定向 `"OnPlayerDeath"` 触发至 `NPCPossession.onLocalPlayerDeathEvent`，若小队仍有存活原主角或 `ACTIVE_PARTY` 成员，立刻切人接管并吞掉死亡事件阻止 Game Over。
16. **`IsoPlayerAllPlayersDeadPatch`** → `zombie/characters/IsoPlayer.allPlayersDead`: 若原主角或任一 `ACTIVE_PARTY` NPC 存活则返回 `false`。
17. **`SaveQuitPlayerDBPatch`** → `GameWindow` / `IngameState` / `PlayerDB.savePlayerAsync`: 所有 `saveLocalPlayersForce()` 前强制 `NPCPossession.prepareForPlayerDBSave()` 切回存活原主角（若原主角已死则正式转正附身 NPC），异步写库亦强行把 `possessedNpc` 替换回 `originalPlayer`，防止污染 `players.db`。
18. **`DebugTypeWarnPatch`** → `DebugType.warn`: 精确吞掉 `"skinningData is null, matrixPalette may be invalid"` 高频刷屏告警。
19. **`BaseVehicleExitPatch`** → `BaseVehicle.setCharacterPosition` & `updatePhysics`: 在 `physics == null` 时保护 `updatePhysics()` 不崩溃；捕获 `"outside"` 返回点调用 `VehicleAccess.onNativeOutsidePosition` 清理下车后的20余项僵死状态锁。
20. **`DoorToggleObserverPatch`** → `IsoDoor/IsoThumpable.ToggleDoor`: 在所有 `RETURN` 前调 `NavInteract.onDoorToggled`，追踪玩家与 NPC 实际开关门事件。

---

## 2. 启动链与 14 步帧主循环（`NPCFWAgent` → `NPCBootstrap` → `IsoNPC`）

### 2.1 启动与注册流

1. **`NPCFWAgent.premain`** 创建/清空工作目录与 `~/Zomboid/` 下的启动日志，反射进入 `NPCBootstrap.premain`。
2. **`NPCBootstrap.premain`**：安装上述 21 个补丁并执行 18 类 `retransformIfLoaded`；拉起 1s 轮询守护线程 `NPCFW-Bootstrap-Watcher`。
3. 检测到新 `LuaManager.exposer/env` 后延迟 `3000ms` 二次确认：
   - 将 `NPCBootstrap` 全部 `150+` 个 `npcfw*` 静态方法暴露为 Lua 全局函数；
   - 注册 12 个自定义 Lua 事件；
   - 在 `SwipeStatePlayer.instance()` 注册 8 个 `IAnimEventListener`（过滤 `!player.isLocalPlayer()`，补齐近战音效、人物语音、枪机咔哒音、空膛检测与倒地压制预热）。
4. **`npcfwInit()` / `npcfwLoad()`**：新局读取沙盒参数后触发 `OnNPCFWReady`；读档顺序为 `LLM清会话 -> SafehouseManager.load -> NPCManager.load -> LeisureTemporaryItemManager.sweepLoadedState -> PartyRestCoordinator.recoverAfterLoad`。

### 2.2 `npcfwTick()` 严格主循环顺序（由 Lua `OnTick` 每帧驱动）

```text
1. LLMConversationService.cleanupStale()
2. NPCManager.tickDeathCleanup()             // 180 tick 强制 die(), 360 tick 清理躯壳
3. NPCManager.tickVehicleSeatRestores()      // 250ms 间隔、15s 上限重试读档回座
4. NPCPossession.protectActiveControlledPlayer()   // [Guard #1]
5. PhasingSystem.getInstance().update()      // 1000ms 节流, 75m in / 90m out
6. NPCPossession.enforceControlInvariants()  // 清扫违规 players[] / frozen 状态
7. NPCPossession.protectActiveControlledPlayer()   // [Guard #2]
8. PartyRestCoordinator.update()             // 主控入睡检测 + 陪睡同步 + 情绪恢复
9. NPCPossession.tickOriginalPlayerAI()      // 驱动被附身后的 PlayerAIController & 切人修复
10. NPCVehicleDriver.tick() + VehicleDrivingRecorder.tick()
11. 强制压回 IsoPlayer.numPlayers          // 睡眠期=1, 否则仅计真实玩家/附身NPC (下限1)
12. 遍历 phasedInNPCs -> npc.tickAI() + RangedCrosshairDebugRenderer
13. NPCEquipmentLightSystem.update(...)      // LightingJNI 同步手持/穿戴光源
14. CursorReticleGuard.tick()                // 最终校准槽 0 准星模式与鼠标可见性
```

### 2.3 `IsoNPC` 单帧双管线与引擎隔离覆写

- **物理帧 `IsoNPC.update()`（重入保护 `updateInProgress`）**：
  - 递增 `physicalUpdateHeartbeat` 与时间戳 `lastPhysicalUpdateMs`；推进换装延时 `tickEquipDelay()`；非附身搬尸工人开 `setUnlimitedEndurance(true)`。
  - 在 `super.update()` 前后采样 `FATIGUE / HUNGER / THIRST`，仅当增量 `after > before` 时按沙盒倍率 `rate ∈ [0, 2]`（0 强制清零）缩放消耗。
  - 派发第三方特质更新、同步存活小时数、调用 `NavInteract.tickDoorDiscipline(this)`、坐标回写 `NPCData`。
- **决策帧 `IsoNPC.tickAI()`**：
  1. 乘员位置对齐 `VehicleAccess.reconcilePassengerPosition`；附身或休眠则重置看门狗退出。
  2. 看门狗 `recoverFrozenPhysicalBody()`：若 `physicalUpdateHeartbeat` 连 45 tick 且 `>= 1200ms` 未动（在车内或有移动方向），每 2s 调 `ensureWorldReady` 解冻脱世界躯壳。
  3. 高优物理/恢复抢占链：搬尸放尸恢复 → 工休起身恢复 → `PartyRestCoordinator.tickParticipant` → 卡入实心家具 `escapeSolidTile()`（1.5s 未脱出强瞬移至最近邻格心）→ `tickVehicleEntryAction()`。
  4. **前置威胁扫描**：平静期 0.15s（按 id 哈希 4 档错峰），战时/有记忆/CQB 每帧调 `TargetSelector.update`，再经 `GuardCombatCoordinator.afterThreatScan` 过滤据点拴绳。
  5. **严格优先级仲裁链（逐级短路）**：
     `PapaChad` → `NPCDirectOrderController` → `PartyCommandCoordinator` → `ManualSleep` → `SelfCareCoordinator`（运行后仍跑任务队列）→ `AutomaticSleep` → 归还遗留工具 → 清理期工种收尾 → 正常岗位（`Guard -> Food -> GuardDuty -> Hauler -> Farmer -> FarmCare -> FarmProd -> Lumberjack`）→ `Social` → `Leisure` → 兜底 `behaviorTree.evaluate`。
  6. 尾声：驱动 `driveFurnitureTimedActionPath()` → 若非引擎寻路则推进 `taskRunner.tick(this, dt)` → 派发 `INPCModule.onTick` → 采集诊断 → `NavSpeedPolicy.enforce(this)`。
- **关键父类覆写**：
  - `getCharacterInputComponent()`：非附身恒返 `null`；
  - `getMinimumSimulationLevel()`：恒返 `FULL` 防止引擎降帧饿死 NPC；
  - `updateLOS()` / `updateVisionEffects()`：非附身空实现防止污染本地玩家视野迷雾；
  - `getDeferredMovement()`：启用 `clampDeferredMovement` 将单帧动画根位移截断在剩余距离之内，消灭精确停靠点过冲抖动。

---

## 3. 单槽隔离策略、`NPCPossession` 与五大防抢防线

ProjectRemnants 的槽位哲学是：**`IsoPlayer.players[0]` 永远只放唯一主控者，`IsoPlayer.numPlayers` 恒压回 `1`，所有非主控 `IsoNPC` 与 AI 托管原主角从不进入 `players[1..3]`，统一设 `playerIndex = 0` + `setNpc(true)`**。

### 3.1 夺舍切换全流程（`NPCPossession`）

- **`possess(IsoNPC target)`**：
  1. 校验 `!PartyRestCoordinator.isControlSwitchBlocked()`；
  2. 确保目标已进场、从 Meta 车辆实体化并 `ensureWorldReady`；
  3. 原主控 `originalPlayer` 置 `setNpc(true)`、`playerIndex = 0`、清空输入变量，创建 `originalPlayerAI = new PlayerAIController(originalPlayer)` 并移除 `players[]`；
  4. 将所有已连结同伴改为跟随新主控者；
  5. 激活目标 NPC：清理其外部订单与自动驾驶、置 `possessed = true`、`setNpc(false)`、写入 `players[0]` 与 `IsoPlayer.setInstance(target)`，开启 `switchRepairTicks`（同区 45 tick / 跨区远距 600 tick 连续校准模型/相机/网格挂载）。
- **`revert()` & 死亡转正**：切回原主角并按连结分配 `FOLLOW/STAY`；若原主角已死，由 `promotePossessedToPermanentPlayer` 继承原主角 `sqlId`/`saveFileName` 并将其从 NPC 持久化表剥离。

### 3.2 五道主控权保护防线

1. **防抢鼠标与准星**：`IsoPlayerPatch` 在 `updateCursorVisibility` 拦掉所有非光标主控者；帧末 `CursorReticleGuard.tick()` 按当前主控是否手持枪械瞄准强设 `IsoReticle.trySetTargetReticleMode(0, ...)` 并在非隐匿态兜底 `Mouse.setCursorVisible(true)`。
2. **防抢输入与朝向**：`getCharacterInputComponent() = null` + `IsoPlayerPatch` 跳过 `updateInternal2` 键鼠覆写 + `IsoPlayerIsJustMovedPatch`。
3. **防抢相机与隐身**：`protectActiveControlledPlayer()` 每帧两次校准 `IsoCamera` 与 `Alpha=1.0F`；`FBORenderCellPatch` 防止双绘/隐身。
4. **防误判全灭死亡**：`IsoPlayerDeathEventPatch` + `IsoPlayerAllPlayersDeadPatch` 拦截死亡事件并在存在存活原主角或 `ACTIVE_PARTY` 成员时无缝移交控制权。
5. **防存盘损坏 `players.db`**：`SaveQuitPlayerDBPatch` 在落库前强制回归原主角（写入 60s TTL `restoreControlNpcId` 到 `NPCFW_Data.bin` 头），异步写库亦重定向，读档 30 tick 后自动恢复附身控制。

---

## 4. 对象池、离屏分相、二进制存盘与死亡转尸

### 4.1 `NPCPlayerFactory` 与对象池（`NPCManager`）

- 预热 `POOL_INITIAL_SIZE = 20` 个由 `SurvivorFactory.CreateSurvivor` 构造并主动摘离 `currentCell` 的干净坯体。
- **关键防崩溃规则（`retiredAnimationPlayers`）**：B42 原版在 `IsoPlayer` 死亡生成 `IsoDeadBody` 时会将原角色的 `AnimationPlayer` 直接移交给尸体接管。因此 `NPCManager.onNPCDeath` 将逝者的 `AnimationPlayer` 记入弱集合 `retiredAnimationPlayers`——**凡已死亡或 `hasRetiredAnimationPlayer == true` 的躯壳严禁归还对象池复用**，必须等待 `180 tick` 强制 `die()`、`isOnDeathDone()` 或 `360 tick` 硬上限后彻底摘离销毁。
- 非正常态回收至对象池时调用 `IsoNPC.resetForPool()` 完整清洗 18 项模块/黑板/ModData/知识/身体/库存/状态机至 `IdleState`。

### 4.2 离屏分相 `PhasingSystem`（`75m` 进场 / `90m` 退场）

- 每 `1000ms` 按到最近本地玩家的 2D 距离平方扫全表：
  - **`dist <= 75.0F`**：调 `phaseIn` 按二进制快照复活实体（区块未加载则走 `processMetaMovement` 每秒沿直线步进 `1.5F` 格）。
  - **`dist > 90.0F`**：车内/Meta 乘员/附身主控豁免退场；**有活跃工作**（直接指令/8 大据点系统/队列）或**隶属 `ACTIVE_PARTY`** 的 NPC **不回对象池销毁**，而是拍快照后执行“原地软休眠”（`setPhaseSuspended(true)`、`setBlockMovement(true)`、`setDoRender(false)`、`setCollidable(false)`），保留完整上下文与随行切换能力；仅闲置居民与独立 NPC 销毁回池。

### 4.3 `NPCFW_Data.bin` 原生 `ByteBuffer` 全量存盘契约

- 文件头：`SAVE_MAGIC = 0x4E504656` (`"NPFV"`) + `SAVE_VERSION = 24` + `npcCount` + `restoreControlNpcId`。
- 使用自动倍增 `ByteBuffer`（`8KB -> 32MB`）直接委托 B42 原生子系统二进制流持久化，保证 100% 保留 `InventoryItem` 原件实例：
  1. `SurvivorDesc.save` / `HumanVisual.save` / `Stats.save` / `BodyDamage.save` / `XP.save`
  2. `IsoNPC.saveNPCFWKnowledge`（配方、已知媒体台词、已读物及页数进度）
  3. `ModData.save` + `NPCTraitCompatibility` 未知 Mod 特质字符串兜底
  4. `ItemContainer.save`（**按 `save()` 返回的列表下标精确映射 `primaryItemIndex`、`secondaryItemIndex` 与所有穿戴槽 `wornItemIndexes[]`**）
  5. 车辆座位、睡眠续接锚点、LLM 对话历史/感知日志/四维关系状态、以及 `NPCDirectOrderState`（含替换指令队列）。

---

## 5. 小队协同、自理医疗与扩展系统

- **编队链锁 `PartyLinkManager`**：借由 `partyLinkGroupId` 支持 4 槽位编队自由拆离与重新链锁，换人控制时自动将同组伴随者指向新领队。
- **小队指令 `PartyCommandCoordinator`**：仲裁 `break`（`PartyBreakController` 搜索 6 格内带互斥锁 `SeatingManager` 座位或地面，5 态坐休至耐力 `>= 0.995F` 限 `15s..180s`）、`reload`（按 `TimedAction` 存活宽限 `8/3` tick 追踪）与 `loot:<filter>`（`PartyBuildingLootController` 排除玩家自有安全屋后 BFS 遍历未锁房间、触发 `ItemPickerJava.fillContainer`、防撞房+互斥锁搜刮 6 类战利品并收进随身背包）。
- **陪睡同步 `PartyRestCoordinator`**：主控入睡时对同行伴随者调用原版 `SleepingEvent.instance.setPlayerFallAsleep` 并强制压住 `numPlayers = 1` 使单人时间加速正常生效；按游戏小时步长同步扣减全队 `stress / boredom / unhappiness` 情绪；睡眠中禁止切人。
- **`PlayerAIController`**：被附身退居二线的原主角专用轻量 AI，复用 `TargetSelector` / `WeaponSelector` / 近远程战斗 / `FollowLeaf` / `SelfCareCoordinator` 与楼梯防卡死检测，并带 `5.5` 格追队拴绳。
- **`SelfCareCoordinator`（10 阶段医疗与自理）**：支持自动触发与 `directed` 指令（经 Lua 挂原版 `TimedAction`），自动会话优先在安全屋内 15 格选 `Doctor*1000 - distSq` 最高照管人（无则自治）。`prepareMedicalKit` 按 11 种紧缺物资批量借取并设 `RESERVED_MEDICAL_SUPPLIES` 引用锁，支持 3 次失败自动配方降级（如缺缝合针降为 `Needle+Thread`，缺夹板用树枝+碎布）；按部位致命伤痛积分优先级完成「拆脏绷带→拆线→拆夹板→取弹片→取玻璃→洗烧伤→消毒→缝合→上夹板→包扎」后，哪怕患者中途死亡也会把剩余物资批量送还原柜。
- **随身光源、配方与第三方特质**：`NPCEquipmentLightSystem` 为 ID `>= 1000000` 分配自定义光源句柄并调 `LightingJNI.updateTorch`（防碰撞原版玩家手电 ID）；`NPCRecipeKnowledge` 提供 `AutoCloseable` 临时授权合成前后自动撤销；`NPCTraitCompatibility` 反射托管 *More Traits* 与 *Somewhat Traits* 的 Lua 回调与 UpValue，调用期线程隔离 `getPlayer()`。
