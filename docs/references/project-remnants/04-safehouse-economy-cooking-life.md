# 04 据点经济、六大分类器、岗位流水线、多步烹饪与睡眠休闲社交

> 覆盖源码：`npcfw/safehouse/*`（除 `safehouse/guard/*` 外全部 54 个类，含 `cooking/*` 16 类、`leisure/*` 6 类、`social/*` 2 类）及 10 个睡眠 AnimSet XML。

---

## 1. 据点领地、休息控制器、警报与深度渲染（`SafehouseManager`）

- **数据与存盘**：`FactionBase`（领地多矩形合并、2 人床位 `BedAssignment`、领地外单格 `CorpseZone`、多块禁用重叠 `FarmingArea`、配额达标滞回 `LumberArea`、59 种规范角色 `StorageAssignment`、三用权 `WaterSourceAssignment`）统一由 `SafehouseManager` 存入 `NPCFW_Safehouses.bin`（`SAVE_MAGIC=1314080838`, v19）。
- **矩形去重与连通性维护（`normalizeBaseAreas`）**：扩地强制与主区域邻接或重叠；剔除孤岛块后用贪心最大矩形分解压缩无重叠矩形集，同步清理越界的仓储/水源/床位与落入领地内的抛尸点。
- **多部件家具锚点标准化**：床铺收集全部 `SpriteGrid` 格按最小 `(z, x, y)` 定规范锚点 `bed:x:y:z`，`w > 1 && h > 1` 判容量为 2 个槽位，每 NPC 限绑 1 床 1 槽。
- **共享工休机 `SafehouseWorkRestController`**：搬运工与伐木工耐力跌破 `0.25F` 触发；若距抛尸点 `<= 4` 格则向 1–12 格避让，优先室内坐地休息（至少 `12s` 且恢复至 `>= 0.96F`）后 `3s` 安全起身离开。
- **OpenGL 深度测试高亮器 `SafehouseAreaRenderer`**：在 `FBORenderAreaHighlights.render` 注入 `FillDrawer`，用 `IsoDepthHelper` 偏移 `-1.4e-4 / -1.0e-4` 结合 `VBORenderer.addQuadDepth` 与 `LineDrawer` 在地图贴合表面绘制无内缝外轮廓。

---

## 2. 六大分类器与零死循环仓储（`*Role` + `SafehouseStorageAccess`）

### 2.1 严格顺序分类链（避免工具/武器、厨具/空瓶冲突）
`SafehouseHaulerJob.classifyRoleUncached` 按以下优先级裁决单一目标角色：
1. **`SafehouseFarmingRole`**：排除正常食物后识别种子与种子包（`OpenPacketOfSeeds`）→ `FARMING_SEEDS`；肥料/堆肥/空或满喷雾剂/空沙袋 → `FARMING_SUPPLIES`。
2. **`SafehouseEquipmentRole`（背包前置）**：可穿戴背包优先归 `GEAR_BAGS`。
3. **`SafehouseSupplyRole`**：先行分流所有 `FluidContainer`（空厨具→`TOOLS_COOKING`、其余空罐→`CONTAINERS_EMPTY`、燃料/油漆/医疗/清洁剂/不安全液；饮水/酒精留给 Food）；再识别堆肥/宠物粮/烟草/渔猎/枪械零件/钥匙锁具/文献书籍/载具件/照明/娱乐/电子/露营/家居/收纳箱/家具/医疗物资。
4. **`SafehouseFoodRole`**：不安全食物（腐烂/有毒/烧焦）→ `TRASH`；酒→`FOOD_ALCOHOL`；水饮→`FOOD_DRINKS`；熟食→`FOOD_COOKED`；干货调料→`FOOD_PANTRY`；生鲜可腐/未包装需冷存（`prefersColdStorage`）→`FOOD_FRESH`；即食→`FOOD_READY`。
5. **`SafehouseEquipmentRole`**：弹匣、弹药、容器、防弹/防咬护甲、普通衣物、枪械、非工具近战武器。
6. **`SafehouseToolRole` & `SafehouseMaterialRole`**：优先 `ItemTag` 识别细分工具（烹饪/裁缝/金工/农用/建筑/汽修）与建材（胶粘/电料/布匹/紧固件/金属/砖石/木材）；内置 `StorageAssignmentAudit` 可自动导出全 ScriptManager 分类自检 TSV 报告。

### 2.2 分层匹配罚分与严格改进门控（`SafehouseStorageAccess`）
- **白名单优先**：`ONLY` 白名单命中 `-20000`，未命中直接拒收（`MAX_VALUE`）；`ANY` 命中 `-18000`；精确角色 `0.0F`；父级大类回退 `10000.0F`；旧版粗类回退 `12000.0F`。
- **防死循环与防侵占炉灶**：
  - `acceptsDeposit` 拒绝自动存物进入 `GENERAL`（卸货中转箱只出不进）与纯 `COOKING` 炉灶（除非白名单显式匹配）；
  - 整理容器时强检 `isStrictRoleImprovement`：必须从 `GENERAL` 迁出、原仓错配、无过滤迁向有过滤、`ANY` 迁向 `ONLY`、或目标罚分严格更低（`> 0.001F`），**从数学上杜绝两个容器互相来回倒腾**；
  - `allowsTaking` 沿嵌套父容器向上溯源，保护 `!allowTakingItems` 容器内子包。

---

## 3. 搬运工、伐木工与农夫生产流水线

1. **搬运工 `SafehouseHaulerJob`**：
   - **搬尸管线（最高优先，`CorpsePhase` 4 态，90s 锁）**：跨领地全楼层 750ms 滑动窗口扫描尸体（上限 1500 格），按原版 `getGrabHeadPosition/LegsPosition` + Lua `ISGrabCorpseAction/ISDropCorpseAction` 抛到领地外 `corpseZone`；异常打断触发 3s 强制 `LetGoOfGrappled` 释放抓握物理锁定。
   - **分拣管线（25s 锁，18s 失败冷却）**：按「卸载 NPC 自身杂物 → 整理仓库 →（开关开启时）5.5 格聚簇批量扫地面物品（跳过木仓保护地面原木）」顺序搬运，单批最多 `6` 件 / `8.0F` 重量，全走 `NPCItemTransferExecutor`。
2. **伐木工 `SafehouseLumberjackJob` + `LumberStockpile`**：
   - **`50%` 滞回配额门控**：`current >= target` 达标停工并存盘，直降到 `current * 2 < target` 且全局扫描完毕才重新复工，消除临界锯木开关震荡。
   - **18 阶段全链路**：按先后借斧（`ItemTag.CHOP_TREE`，最大砍树伤）与锯（`ItemTag.SAW`），写 ModData 来源戳 `npcfwLumberAxeOrigin / SawOrigin`（空闲自动还工具，完工整批归还）；砍树前后对树格世界物品做快照 Diff 精确只收新掉原木；搬运时把斧挂回背部槽位，锯木走原版 `SawLogs` `CraftRecipe`；产出优先送卸木区，其次木料仓，满仓时在 `0..6` 格环上按 `floorWeight * 20 + distSq` 寻 `<= 50.0F` 安全地面溢出堆放（打 `npcfwLumberStockpile` 保护戳防搬运工偷搬）。
3. **农夫三协同 Job（共享 30s 地块锁 `farm:<base>:<x>:<y>:<z>`，全部经 Lua 胶水调用原生农耕动作）**：
   - **浇水 `SafehouseFarmerJob`**（11 态）：借喷壶/水桶/水瓶（写来源戳）去安全水源灌水，每次 `0.2L` 最多 10 次，`criticalWater`（低于阈值 `1/1.3`）优先权重 `-100000`；
   - **治病/除草/施肥 `SafehouseFarmCareJob`**（11 态）：按 `病害 > 除草（阔叶草需 CUT_PLANT 刀剪）> 施肥` 优先处理；若缺药水通过 `FarmerCureCrafting`（基于 `HandcraftLogic`）现场配制牛奶/蚜虫/香烟喷雾；
   - **收割/播种/翻废/开垄 `SafehouseFarmProductionJob`**（16 态）：收割用背包 Diff 自动分流种子与蔬菜；播种最多 24 粒/批（`"AUTO"` 按现状均分作物），可拆 `OpenPacketOfSeeds` 种子包并校验 `OutputMapper`；翻废与开垄按 `CLASSIC_ROWS / DISEASE_SPACING / TEND_EXISTING` 布局控制不超过库存可用种子数。

---

## 4. 食品总管与 16 类多步烹饪管线（`SafehouseFoodSystem` & `cooking/*`）

### 4.1 居民点餐与厨师调度（`SafehouseFoodSystem`）
- **居民端**：饥饿 `>= 0.15F`（紧急 `0.45F` / 极度 `0.75F`）先吃随身安全粮 → 走去熟食/即食/通用/生鲜仓搜粮 → 无成品时走至最近可用厨师（`<= 1.65m`）下单点菜；无人无料则 `30 -> 60 -> 120s` 指数退避（厨房库存签名变动或饥饿跃升秒唤醒）。
- **厨师 7 级工作序**：接管读档/中断炉灶残留食物 → 续跑多步烹饪 → 送餐 `MealDeliveryPlan`（直递给玩家/居民或放成品仓，并贴 `npcfwDirectCookRecipe` 追踪点餐交付）→ 归还借用厨具剩余料 → 炉灶直热（加温至 `HOT_MEAL_HEAT=1.3F`，**仅关自己亲手打开且已无他物的炉灶**）→ 玩家/居民点菜队列（24 单上限）→ 自发备餐保持 `readyMeals >= pending + 1`（冷藏偏置 `freezer=-4`, `fridge=-3`）。

### 4.2 `safehouse/cooking/*` 16 类深度架构
- **`CookingGoalRegistry`（22 个多步烹饪目标）**：统一声明多步依赖（如寿司依赖熟米饭）、`CraftRecipe`、`EvolvedRecipe`（蛋糕/派/汤配料）、加热输出与后处理切片配方（`PlaceCakeInBakingPan / SlicePizza / Get6Cookies`）。
- **`CookingContextSnapshot`（防与玩家抢容器/炉灶）**：遍历 6 层容器，并通过 Lua `getPlayerLoot(0..3)` 检测玩家界面是否正打开某炉灶（标 `PLAYER_OPENED` 自动避让）。
- **`WaterRequirementResolver`（高精度流体求解器）**：识别配方流体输入与 `[*]` 占位容器，按「水瓶 0 → 水桶 6 → 锅 12 → 碗 30 → 杯 35，满瓶奖 -50」计算灌水方案，避免将同配方的其他原料容器误征用去灌水。
- **`RecipePipelineGuard`**：限制最大 `8` 阶段，并在依赖展开链检查 `recipeId|output0` 环检测，彻底杜绝死循环。
- **`RecipePlanner` & `RecipeExecutor`**：用虚拟 `Allocation` 跨最多 `20` 批扣除物品/水/负重竞争，线性生成阶段并在 8 阶段 `RecipeJob` FSM 执行：在烹饪/烘烤后通过 **`OutputRef` 跟踪 B42 `replaceOnCooked` 引起的物品实例指针替换**，内置 `45s` 炉灶热变停滞看门狗（监控 `heat + cookingTime`）与 `120s` 阻塞恢复，失败时抢救炉灶内可回收食材。

---

## 5. 睡眠（10 XML）、休闲（19 活动）与双人社交

### 5.1 睡眠体系（`SafehouseSleepSystem` + `SafehouseSleepAnimationController` + 10 XML）
- **睡位优先级**：`室内绑定床位（双人床仅锁对应床尾部件允许同床睡两人） > 室内 ≥2 座沙发 > 室内安全地板（24 格，不堵门/无火/无尸/可达） > 室外床 > 室外沙发`；通过 `LeisureSystem.reserveFurnitureForSleep` 永久锁家具防他人坐下抢床。
- **双路径执行**：
  - **家具睡（BED / COUCH）**：走到 `0.35m` 旁 → Lua `NPCFW_queueSleepPrototype` 驱原版 `SeatingManager` 上床躺下 + Java 每帧 `driveFurnitureTimedActionPath()` → 达 `lying` 后切 `NPCFW_commitSleepPrototype` 脱离队列并调用原生 `SleepingEvent.instance.setPlayerFallAsleep(npc, 8)`（8 小时后 `enforceSleepDeadline` 兜底唤醒）；
  - **地板睡（FLOOR）**：由 Java 9 态动画控制器先触发坐地，坐稳 250ms 后在 `1200ms` 内 `smoothStep` 插值位置与方向进躺姿（起身反向 `1167ms` + `3s` `forceGetUp` 释放）。
- **紧急唤醒与三套恢复机**：着火、危险、流血、生命 `<=0.35F`、重度饥渴触发急醒；**三套恢复态**：`WakeRelease`（`3s` 强制清清全部躺坐/寻路/战斗残留防止起身滑步）、`InterruptedResume`（严重饥渴惊醒喝完水保留原睡位续睡）、`PendingResume`（读档后延迟 `500ms` 待脱离碰撞复原床睡）。
- **10 个自定义 AnimSet XML**：`sitonfurniture/idle` 5 个（继承 `SitOnFurnitureIdle`，End 触发 `AsleepEvent/AwakeEvent`）+ `sitonground-sitting` 5 个（`m_Priority=20` 覆盖 `sit_loop.xml`，显式留空 `m_DeferredBoneName` 并隐藏左右手持物）。

### 5.2 休闲与自持系统（`leisure/*` 6 类）
- **19 种带昼夜与独立冷却活动**：覆盖读书、看 TV/VHS、静休、健身（强体能与空手门控）、散步、听广播/音乐、抽烟（需 `SMOKER`）、望窗、写日记（非 `ILLITERATE`）、看地图、**枪械维护/装退弹匣/工具巡检归还/湿衣更换拧干/无线电与手电换电池/门窗安保检查/洗澡**（19 阶段通用 FSM）。
- **工程亮点机制**：
  - **`LeisureTemporaryItemManager` 零泄漏闭环**：抽烟与日记的香烟/打火机/日记本/笔统一写入 **5 个 ModData 戳 + 唯一令牌**，覆盖「正常完成 / 全类型抢占 / 存盘前 `prepareForSave` / 读档全世界+全容器+全地面大扫除 `sweepLoadedState()`」4 重回收门；
  - **多观众电源责任移交**：最初打开电视/收音机者离开时将关机责任移交同台其他观众，最后离场者负责关机退碟；
  - **换电池防吞噬**：拆下低电旧电池先打 `favorite=true` 防配方自动吸回，归还后取消收藏；
  - **离屏意图快照**：90m 退场仅将 `READ/WATCH_TV/VHS/REST/EXERCISE/STROLL` 等轻态打包为 `LeisureIntent`（保存已读页数），回屏 3 次内续做；重状态活动一律安全退还物品与设备。

### 5.3 双人社交系统（`SafehouseSocialSystem` & `Schedule`）
- 作息时段 `06:30..23:00`，按无聊/抑郁/压力算兴趣率，75% 短聊 `7–15` 游戏分钟 / 25% 长聊 `20–35` 分钟，完成后 `2.0–3.5h` 冷却并对上任聊友加 `+36.0` 距离罚分防两人死绑。
- 30 格内找人（可优雅抢占可让渡休闲），在对方 8 邻格预约会合点（若同层 `<= 2.75m` 且视线直通则原地开聊），`APPROACHING → CONVERSING → CLOSING` 三态经非阻塞表情桥 `playEmote` 轮流打招呼、做说/听动作、道别，结束后结算双方微幅正面情绪并释放会合锁与缓冲期。
