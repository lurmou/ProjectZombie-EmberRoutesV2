# 06 Lua 薄壳胶水、全套 UI 体系、对话/LLM 指令链路与资源清单

> 覆盖源码：`npcfw/conversation/*`（5 类）、`GibberishBridge.java`、40 个 `42/media/lua/` 文件、`NPCFW_ModOptions.lua`、`42/media/sandbox-options.txt` 与全部非脚本资源。

---

## 1. Java ↔ Lua 三通道桥接与精确动作同步范式

### 1.1 通信三通道（`NPCFW_Main.lua` & `NPCBootstrap`）
| 通道 | 方向 | 规模与承载机制 |
|---|---|---|
| **A：导出函数** | Lua → Java | ~180 个全局 `npcfw*` Kahlua 函数，涵盖生命周期、夺舍切人、编组指令、远程音/弹道、据点划区/岗位/仓储/点菜、驾驶指令、LLM/记忆与第三方特质桥。 |
| **B：直接回调** | Java → Lua | ~80 个全局 `NPCFW_*` 函数，经 `LuaManager.caller.protectedCall` 同步入队原版动作、查询状态、取消动作及局部探测。 |
| **C：自定义事件** | 双向广播 | Java 触发 8 个（含 12 个内部事件注册点）如 `OnNPCFWReady / OnNPCTraitsApplied / OnNPCFWRanged* / OnNPCFWVehicle*`；Lua 另挂钩 30+ 原版生命周期与界面事件（`OnTick` 轮询推送 `DisableNPCBumping` 变更并步进 `npcfwTick()`）。 |

### 1.2 防泄露与精确完成检测范式（弱表 + 令牌 + 1-tick `SignalAction`）
因 B42 动画状态机在动作结束后常残留 `IsPerformingAnAction` 数帧，直接轮询 `hasTimedActions()` 极易误判，故 SelfCare/Sleep/Leisure/Farming/Corpse 胶水统一采用同一契约：
1. **弱键状态表**：`setmetatable({}, { __mode = "k" })` 以 `IsoGameCharacter` 作弱键缓存状态，防止角色卸载造成 Lua 内存泄漏。
2. **单调递增 `token` + 动作打标**：入队动作附加模块标（如 `action.npcfwSelfCare = true`）并包装 `stop()` 在异常中断时将状态改为 `"failed"`。
3. **1-tick 信号尾部动作 `SignalAction`**：紧接最后一个原版动作入队 `maxTime = 1` 的信号动作，在其 `perform()` 中校验 `token` 未失效后才写入 `"complete"`，Java 轮询返回 `"none | running | complete | failed | cancelled"`。

---

## 2. 全 14 个 Lua 原生动作与兼容胶水清单

| Lua 模块 | 封装的原版系统/动作 | 核心机制与避坑要点 |
|---|---|---|
| `NPCFW_RangedActions.lua` | `ISReloadWeaponAction`、`ISRackFirearm`、`ISLoadBulletsInMagazine`、`HandcraftLogic` | `requestFire` 校 `authorizeAttack` 调 `attackHook` 并回调 `npcfwPlayRangedShotSound/ResolveRangedShot`；装弹匣按余弹降序排序并调用 `HandcraftLogic` 现场拆弹药盒。 |
| `NPCFW_SelfCareActions.lua` | `ISEatFoodAction`、`ISDrinkFluidAction`、`ISTakePillAction`、`ISTakeWaterAction`、10 种医疗动作 | **严守原版背包取出→主栏使用→归还闭环**；开罐头支持临时带 `NPCFW_TEMP_CAN_OPENER` 戳开罐器并在结束/中断时销毁；支持从患者背包借药医治并原样归还。 |
| `NPCFW_SleepActions.lua` + `NPCFW_BedPoseResolver.lua` | `ISPathFindAction`、`SeatingManager`、床铺 `SpriteGrid` | 9 态床睡状态机驱 `NPCFWSleepAnim`；因入睡会清 `ISTimedActionQueue`，由 `NPCFW_commitSleepPrototype` 置 `poseDetached=true` 脱离队列保躺姿，醒来先还原站立锚点再 `forceGetUp()`；兼容单行 `SpriteGridPos` 解析床头尾。 |
| `NPCFW_LeisureActions.lua` | `ISReadABook`、`ISDeviceMediaAction`、`ISRadioAction`、`ISFitnessAction`、`ISWashYourself`、`ISWringClothing`、`ISLightSourceRadialMenu` 等 | 读书 `maxTime * 5` 且禁弹窗/标地图；健身修复无输入及动画循环空指针；洗浴水耗倍率 `0.30`；湿衣经脱→拧→可选重穿闭环；修手电配方绕过原版只读 Player 0 背包列表的缺陷。 |
| `NPCFW_SocialActions.lua` | `character:playEmote` | 非队列轻量胶水：严格白名单 10 个原版社交表情，不占用 `ISTimedActionQueue`。 |
| `NPCFW_FarmingActions.lua` | `CFarmingSystem.instance`、9 个农耕 `IS*Action` | `scanFarmerArea` 同步植物状态；与 Java 保持镜像布局；`applied` 校验在动作被中断后确认农地是否已真实变化防重扣种子。 |
| `NPCFW_CorpseActions.lua` | `ISGrabCorpseAction`、`ISDropCorpseAction` | 抓尸前强制站起并瞬间 `complete()` 双手 `ISUnequipAction`；取消时调 `setDoGrappleLetGo()`。 |
| `NPCFW_VehicleActions.lua` | `ISVehicleMenu`、`ISOpenVehicleDoor`、`ISUnlock/Enter/Switch/CloseVehicleDoor`、`ISVehicleMechanics` | 为无轮盘 NPC 注入占位 `dummyRadialMenu` 防 UI 空指针；多人同门排队按**先穿座后外座**排序；车速 `>1.2 km/h` 暂退回集结；无钥匙锁门等 120s 催玩家。 |
| `NPCFW_AutoDriveRoadGridBridge.lua` | `AutoDriveNavigationGraph` | 仅只读调用，Min-Heap A\* 返回路网航点给 Java 驾驶器。 |
| `NPCFW_DriveOrders.lua` | 上下文与地图驾驶指令 | 封装 `npcfwOrderDriveTo / OrderFollowVehicle / StopDriving`。 |
| `NPCFW_Progression.lua` | `Events.OnDeviceText` | 为 5 格同室内/同车队友复现电视/VHS/收音机 XP 与 `MEDIA_STAT_CODES` 情绪收益，按 `isKnownMediaLine` 防重刷。 |
| `NPCFW_TraitCompatibility.lua` | *More Traits* / *Somewhat Traits* | 在 `OnNPCTraitsApplied` 补齐 50+ 项安全 ModData 默认键并转发更新回调，禁止直接重放 `OnCreatePlayer` 崩游戏。 |
| `NPCFW_PapaChadCompatibility.lua` | `tankAimSystemPC`、`ReloadTurretAction` | 1 号位 NPC 炮手接管时禁 0 号玩家鼠标开火，同步 8 向炮手姿态变量与炮塔 2° 步进转角。 |

---

## 3. 全套交互与 ISUI 体系架构

1. **屏幕缩放与设置**：`NPCFW_UIScale.lua` 基于 `1920×1080` 自适应缩放 `[1.0..2.2]` 叠乘用户倍率（接入 B42 `PZAPI.ModOptions`）。
2. **上下文菜单与 UI 重绑 `NPCFW_ContextMenu.lua`**：
   - 双挂 `OnPreFillWorldObjectContextMenu` 与 `OnFillWorldObjectContextMenu` 保底入口；通过递归检测原版菜单是否挂了 `onDrink/onTakeWater/onWash*` 精确识别水源（不硬编码贴图）；
   - **`NPCFW_RebindUI(playerIdx)`**：切人夺舍后重置 `playerInventory` / `lootInventory` / 销毁重构 `ISNewHealthPanel` / `ISHotbar` / `vehicleDashboard`；包装体检动作支持随移动 NPC 跟走。
3. **小队 HUD、头像、跨成员背包与陪睡**：
   - **`NPCFW_PartyUI.lua`**：4 槽位可拖拽栏（位置持久化在原主角 ModData），支持拖到头像上链接编组、拖出拆离，监听 `OnPlayerDeath` 抑制原版死屏 `120 tick` 并立即继任控制权；
   - **`NPCFW_PartyPortrait.lua`**：渲染 Java 离屏 `DeadBodyAtlas` 烘焙头像、血条、呼号、30 tick 高优 Moodle、Shift 多选、左键夺舍、右键策略菜单及驾驶快捷 `"D"` 钮；
   - **`NPCFW_PartyInventoryUI.lua`**：并行打开多 `ISInventoryPage`，利用临时 `withCharacterOwner` 作用域劫持 `getSpecificPlayer / getPlayerHotbar` 使右键与快捷栏准确作用到目标 NPC；远距投递 `distSq > 4.0` 先寻路靠近（1800 tick 超时），已穿衣物先插 `ISUnequipAction`；
   - **`NPCFW_PartyCommands.lua` + `NPCFW_PartyRadial.lua`**：快照多选目标群发休整/装弹/ROE/搜楼，复用原版轮盘（Hold/Toggle 模式，在载具/动物上下文按 `V` 让权原版）；`NPCFW_PartyRest.lua` 触发全队加速睡眠。
4. **据点管理、拖拽画笔与世界叠层**：
   - **`NPCFW_FactionManagementUI.lua`**：4 页签（`SAFEHOUSE / FARMING / STORAGE / MEMBERS`）+ 4 个轻量浮动确认窗 + 岗位专属配置；
   - **`NPCFW_SafehouseSelection.lua` + `NPCFW_GuardSelection.lua`**：`OnRenderTick` 驱动 6 种据点划区模式（自动拆并防重叠、农田按布局预览可耕性）与警卫 3×3 岗哨 / 禁折返 1 格宽巡逻路线画笔；渲染走 Java `SafehouseAreaRenderer`；
   - **`NPCFW_SafehouseStorage.lua` + `Overlay`**：注入搜索栏容器图标右键分配 60+ 精细仓储角色与 `ONLY` 单品过滤，并在 `STORAGE` 页签实时向世界物体头顶投影角色图标；
   - **`NPCFW_CookRequestUI.lua` + `NPCFW_ActivityLabels.lua`**：三栏厨师点菜面板（`Available / Need Items / Recipe Book` + 份数确认窗）与 ~250 个状态 ID 本地化（含进度 `%` / `x/y` 解析）。
5. **多幸存者建人与辅助 UI**：`NPCFW_PartyCreation.lua` 在选城界面嵌入 1–4 人建队人数屏、串行建人与 AI 人设生成（Hex 存入预设）；`NPCFW_RangedCrosshairOverlay.lua` 按散热平滑 lerp(0.1) 与命中率染色绘制原版准星。

---

## 4. 对话、`Gibberish`、三路由 LLM 与 47 动作安全执行链

### 4.1 情景台词与被动感知收集（`GibberishBridge` + `NPCFW_Gibberish*`）
- Java 上报 53 个短事件、~80 个任务切换与 2s 观察心跳；Lua 按 17 大类语料、动态替换 `[DIRECTION]` 与相对领队 `[CLOCK]` 时钟方位，施加单人 `10s` / 全队 `3s` / 同目标 `8s` 三级冷却；开启 LLM 时同步调 `npcfwRememberPerception` 写入持久化感知日志。

### 4.2 `LLMConversationService` 三路由管线与安全护栏
- 单线程后台守护池 `ThreadPoolExecutor("NPCFW-LLM", queue=8)`；支持 Ollama 与 OpenAI 兼容协议，并通过 **`sanitizeOpenAiEndpoint` 强制锁定 `http://localhost|127.0.0.1/.../chat/completions` 环回硬锁**（由外部本地代理中转）。
- **`startTalk()` 三级路由**：
  1. **挂起确认快通道**：`NPCDirectOrderController.answerPending` 命中待确认/消歧即零延迟同步完成；
  2. **本地确定性指令直通 `INSTANT`**：拼装 `3400/4096` Token 预算的实景上下文后，若 `LLMActionProposal.fastOrder` 命中单一清晰指令直接同步执行（若主线程校验被拒，自动无缝降级回异步 `ACTION_LLM`）；
  3. **异步模型推理**：`ACTION_LLM` 绑定封闭 JSON Schema + `temperature = 0`（最多 2 次含契约纠偏）；`DIALOGUE_LLM` 启用 100ms 流式冒泡，生成后经 **3 道幻觉越权检查**（违规触发 20s 重写或接地台词兜底）。
- **防漂移指纹与确定性关系**：请求快照双方状态/车辆/手持物体 `Fingerprint`，异步交付前不匹配即弃用；关系数值更新不信模型输出，由确定性关键词事件钳制单次目标 `±3`、8 格内旁听者 `±2`。

### 4.3 `LLMAwareness` 防幻觉与 `LLMActionProposal`（47 动作）
- **`LLMAwareness`**：用 `LosUtil.lineClear` 判可见性（3m/8m 全向，远场 `-0.35F` 视锥，16m 上限）；容器强制标注 `"contents uninspected"`、`WORLD.CONTAINER_CONTENTS = unknown`、超时标注 `SCAN_TRUNCATED` 防编造物；指示代词（“这个/那个”）沿玩家前向 `dot >= 0.55F`、6m 内自动锁定注意力物体；融合 120s 短期记忆与 32 条持久化感知日志。
- **`LLMActionProposal`**：封闭 **47 个枚举动作**；严格 JSON 解析限制嵌套深度 `<= 4`、总长 `<= 4096`、禁用复合多指令；**`contextualType + textBackedType` 核验必须有玩家原话词面支撑**并抹空非授权字段；高风险策略变更（ROE/岗位/仓储/批量 `all`）强制触发二次确认。

### 4.4 `NPCDirectOrderController` 电子围栏与 `RECOVER` 归还闭环
- **角色白名单 + 安全屋电子围栏**：居民禁离安全屋领地执行非跟随命令、禁越权接管小队/载具命令；解析期与安装期双重实景校验存活/下车前置/防抢锁/装备保护/寻路可达。
- **阶段机 & 失败强制 `RECOVER` 归还**：`START → ACQUIRE/TRAVEL → DELIVER/ADAPTER/HOLD/RETURN → RECOVER → finish`。遇险仅放行交战/撤退（烹饪/治疗感知战斗安全收口，其余暂停）；**任何中途失败、取消或换令，绝不私吞或丢弃中途拿到的物品，强制走 `Stage.RECOVER` 逐件退还原人/原柜/兼容仓（阻塞每 3s 重试）后才结束或切新指令**；全部底层执行完全复用 `NPCItemTransferExecutor` / `Safehouse*` / `SelfCare` / `PartyCommandCoordinator`（装弹通过前后台弹量快照对比验真）/ `VehicleAccess`。

---

## 5. 沙盒配置与媒体资源清单

1. **沙盒配置 `42/media/sandbox-options.txt`（页 `ProjectRemnants_NPCNeeds`，10 项）**：
   - LLM 开关：`EnableLLMActions(false)`、`EnableLLMConversations(false)`、`EnableLLMRelationships(false)`
   - 生理倍率：`FatigueRate / HungerRate / ThirstRate ∈ [0..2] (1.0)`、`SleepThreshold ∈ [0..1] (0.6)`、`AutoSelfCare(true)`
   - 碰撞与警报：`DisableNPCBumping(true)`（`OnTick` 实时热同步）、`SafehouseAlertRadius ∈ [0..50] (18.0)`
2. **媒体资源盘点**：
   - `AnimSets/`：27 XML（17 `player/idle/NPCFWGuardRifle*` + 5 `sitonfurniture/idle/*Sleep*` + 5 `sitonground-sitting/*FloorSleep*`）；
   - `anims_X/Bob/`：12 `.glb` 自定义步枪警卫动作；
   - `npcfw/roadgraph.bin`：离线压缩路网；`ui/`：3 张辅助图标 PNG；
   - `Translate/`：`EN / IT / JP / PTBR / RU` 五语 `IG_UI.json`（含 `IGUI_NPCFW_OrderSpeech_*` 应答台词与 ~250 条状态文本）+ `Sandbox.json`。
