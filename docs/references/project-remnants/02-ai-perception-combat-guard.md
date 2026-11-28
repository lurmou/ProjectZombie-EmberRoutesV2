# 02 AI 行为树、感知、战斗微操与据点守卫全链路

> 覆盖源码：`npcfw/ai/**`、`npcfw/tasks/AttackExecutor.java`、`npcfw/utils/NPCCombatAudio.java`、`npcfw/safehouse/guard/*`、`42/media/AnimSets/player/idle/NPCFWGuardRifle*.xml`。

---

## 1. 反应式行为树与外层调度优先级

ProjectRemnants 的行为树（`NPCBehaviorTreeBuilder`）并非顶层唯一控制器，而是居于 `IsoNPC.tickAI()` 最底端的兜底调度器。只有在高优先级抢占（陪睡、实体脱困、上车）、`NPCDirectOrderController`、`PartyCommandCoordinator`、`SafehouseSleepSystem`、`SelfCareCoordinator` 及据点全部工种（`Guard -> Food -> GuardDuty -> Hauler -> Farmer -> FarmCare -> FarmProd -> Lumberjack -> Social -> Leisure`）均让权时，才执行 BT 评估。

```text
Root [Selector]
├── Combat [Sequence]
│   ├── Condition("HasThreat?")     // 载具免战 / 被动否决 / 同伴跟随拴绳 / 守卫拴绳 / ROE校验 / CQB覆写
│   └── WeaponPath [Selector]
│       ├── RangedSeq [Sequence] -> (primaryHand.isRanged()) -> RangedCombatNode
│       ├── MeleeCombatNode
│       └── ThreatInvestigateNode
├── OrdersGoals [Selector]
│   ├── CompanionOrders [Sequence] -> ((COMPANION || FACTION) && order != NONE)
│   │   └── Selector -> ExitVehicleLeaf | (COMPANION && FollowLeaf) | GuardLeaf | StayLeaf
│   └── IndependentGoal [Sequence] -> (INDEPENDENT) -> IdleLeaf
└── IdleLeaf
```

- **无状态反应式（Reactive）语义**：无 Running 记忆游标，每帧从根重新求值，`Combat` 随时无缝抢占后方一切行为（旧版 `FleeLeaf`、`WanderLeaf`、`ZombieInvestigateNode` 已从主树摘除）。
- **`HasThreat?` 同伴跟随拴绳（无主动点名目标时生效）**：
  - 领队移动速度 `> 2.5` 格/秒视为转移中，此时除非目标距本人 `<= 1.6F` 触发自卫或正处于攻击/推搡动画，否则禁止主动接敌；
  - 可见目标距领队不得超过 `CombatStance.engagementRange(stance, isRanged)`；自身距领队 `> 5.5F` 一律强制否决战斗优先追队；
  - 若处于近战且目标 `> 1.6m` 且直线绕路被阻，由 `holdFormationInsteadOfChasing` 维持队形；
  - **CQB 模式豁免**：当 `CQBMode.appliesTo` 为真时直接覆盖为 `hasBestTarget && !CQBMode.shouldYieldCombat(...)`。
- **三元身份与交战总闸（`NPCAssignment` × `NPCClassification` × `NPCFactionRelations`）**：
  - `WORLD → INDEPENDENT`、`ACTIVE_PARTY → COMPANION`（上限 3 名，独享 `FOLLOW` 订单、CQB 与同伴拴绳）、`BASE_RESIDENT → FACTION`；
  - `NPCFactionRelations.isHostileCombatPair(a, b)` 要求双方均为 `IsoPlayer`、至少一方显式设了 `factionId` 且关系为 `HOSTILE`，作为 `CombatManagerPvpPatch` 与 AI 敌对索敌的统一权威。

---

## 2. 感知、视野门控与记忆衰减数学模型

| 通道 | 刷新频率 / 节流 | 核心覆盖范围与关键机制 |
|---|---|---|
| **主目标扫描** `TargetSelector.update` | `80 + (idHash % 4)*10` ms 错峰；冷静期外层 0.15s，战时/CQB 每帧 | `MAX_RANGE = 15.0F`；统计 `2.0F` 内聚群 `mobNear` |
| **双向拉仇恨** `spotNpcForZombies` | `250ms` 轮询游标批处理 12 只 | 10m 同楼层视野畅通主动调 `zombie.spotted(npc, false)` |
| **友军扫描** `NPCSensorScan.scanFriendlies` | 战时 `0.16s` / 平时 `0.50s` | `3.0F` 内记录受保护非敌对友军至黑板 `nearbyFriendlies` |
| **世界声源快照** `WorldSoundSnapshot` | 全局 `35ms` 共享缓存 | 反射抓取 `WorldSoundManager.soundList` 尾部 192 条并生成指纹 |
| **听觉扫描**（敌对人 / 丧尸） | 敌对人 `100ms + 错峰`；丧尸随主扫描 | 丧尸上限 18m（ΔZ 按 `×3.0` 计权） |

- **严格视线与视野张角过滤**：
  - 垂直楼层：近战强制 `|Δz| < 0.5F` 且 `NavGrid.hasFenceBetween` 直接淘汰；远程允许跨层。
  - `LosUtil.lineClear` 判 `Blocked` / `ClearThroughClosedDoor` 不可见；野外无据点庇护时连 `ClearThroughWindow` 也剔除（防隔窗干瞪眼）。
  - **张角**：除 2m 环绕感知、CQB 5m/警戒弧及已锁我丧尸享有 360° 豁免外，要求 `getDotWithForwardDirection >= -0.35F`（±110.5°）。
  - 激进姿态（`aggressive`）下当丧尸距本人 `<= 1.3m` 或在 `3.5m` 内且旧仇恨在外时强制拉仇恨。
- **听觉倍率与记忆衰减公式**：
  - 听觉倍率：`DEAF = 0`，`HARD_OF_HEARING × 0.70/0.75`，`KEEN_HEARING × 1.35`，叠乘衣物与天气倍率（钳制上限 `1.75`）。
  - **丧尸听觉**：从「攻击我 / 锁我 / 声源为我 / ≤2.8m 近身 / 移速声 / 匹配 WorldSoundSnapshot」六路取最大置信度（跨房间 `×0.85`、无视线 `×0.82`，门槛 `0.22F`）；反应延迟 `clamp(850 - conf*520 - (d<=3?160:0), 180, 900)` ms；按每 `700ms` 重算一次随不确定度漂移的定位误差半径。视觉寿命 `3.5s` / 普通听觉 `2.6s` / 近身 `4.5s`，失视每秒置信 `-0.20*dt`（有效底限 `0.11F`）；换目标要求净增 `> +0.18F`。
  - **敌对幸存者听觉**：基于 `soundDelta = distSq3D / (radius * mult)^2 < 1.0`（跨房 `×1.2`，内外 `×1.4`），响声寿命 `6.5s` / 普通 `4.2s`，衰减 `0.16*dt`；延迟到达后同步写入原版 `setLastHeardSound`。
  - **`ThreatAwareness.reportShared`**：支持据点警报注入共享记忆点（`confidence=1.0`, `reactAt=+180ms`, `expiresAt=+3500ms`）。

---

## 3. 目标打分、承诺防抖与近战协同全链路

### 3.1 目标评估与优先级覆写
- **姿态分类器（`CombatTargetState`）**：精确分离「倒地/爬行可踩踏 `isGroundAttackable`」、「站立可推 `isStandingShoveTarget`」以及「倒地/起身过渡态 `isPostureTransition`」——后者允许普通挥刀压制但禁止踩踏与推搡，避免空手误推起身帧！另明确过滤 `isReanimatedForGrappleOnly()` 处决假尸体代理。
- **复合打分公式**（针对丧尸）：
  1. `base = maxRange - dist` + CQB 扇区/认领加减分（归己 `+60/+36/+18`，归他人 `-60`）；
  2. 队友受威胁时间分：`threatTimeToContactScore`（正在咬队友 `+40`，否则 `max(0, 30 - max(0,d-0.75)/speed * 6)`；被咬者是领队额外 `+8`）；
  3. 姿态与仇恨倍率：`isGroundAttackable < 0.8m` `×2.0`（非近危远倒地 `×0.3`）、`isStandingShoveTarget < 2.0m` `×4.0`、疾跑丧尸 `×1.5`、过渡态 `×0.5`、锁我 `×2.0`；
  4. 多敌收割加分（2m 内 ≥2 敌）：破绽态 `+30`、`<1.6m` `+12`、2.5m 孤立 `+18`；
  5. 队友认领分流：非即时危险时他人 `primary` 近战认领（900ms TTL）减 `1000` 分，远程认领（1200ms TTL）减 `4..24` 分。
- **抢占与防抖顺序**：玩家直指 `directedTarget` > 贴身紧急目标 `urgentTarget`（2m 内站立>爬行，或 1.75m 内紧逼威胁队友）> 承诺期内（近战 650ms / 远程 1100ms）防切锁（需超出 `+500` 分，期外 `+4.0` 分）> 近战硬直锁定目标 > CQB 响应归属最终一票过滤。

### 3.2 近战微操与硬直压制（`MeleeCombatNode` + `StaggerPressure` + `CombatMath`）
- **武器距离环基准**：`reach = weapon.getMaxRange * getRangeMod`（空手 `1.1F`），可靠命中距 `reliable = max(reach*0.9, reach - 0.08)`，衍生四环：`optimal = 1.08*reach`、`deadIn = 0.95*reach`、`deadOut = 1.15*reach`、`kite = 1.4*reach`；空手踩踏/武器砸地下限 `0.58F`。
- **架势迟滞防抽搐**：进架势基础距离按敌速/人数在 `[1.3, 3.5]` 格计算，一旦已处于 `isAiming()` 立刻附加 **`+0.35F` 迟滞余量**，杜绝架势边界来回拔收武器。
- **8 向承诺站位（550ms 锁）**：依 `npcId` 奇偶固定首选偏航侧（`0, ±30, ±60, ±100, 180°`），`support` 额外侧偏 70° 防止队友挤在一线；仅当前位黑名单、失效或目标移动超 `0.45m` 才重算，8 位全阻拉黑目标 `1600ms`。
- **连续间距控制器 `applySpacingController`**：
  - 帧率无关低通追踪目标速度（`0.80^(dt*60/1000)`，上限 350ms）；
  - 维持至少 **`0.2F` 内外死区间隔**（破绽态缩至 `0.55~0.90*reach`，常规单敌 `reliable`，多敌 `1.15*reach`）；
  - 叠加 `0.25s` 前向预测与非目标爬行者斥力，经 `wallAvoid` 五向探针偏转并做输出低通，**模长 `< 0.15F` 直接截断停步**，彻底根治微幅腿抽搐。
- **Strafe 局部投影与不扭身**：`NPCMovementUtil` 以骨骼实际渲染朝向 `animPlayer.getRenderedAngle()` 将世界移动向量逆旋至 `strafeX/Y`；挥砍与推搡动画播控期跳过 `faceThisObject` 防中途扭身。
- **硬直压制 `StaggerPressure`**：丧尸进受击/跌倒/起身瞬间开启绝对 **`1800ms`** 窗口（不续期）；计算冲刺动作预算 `(d - reliable)/1.75*1000*0.65 + 50 + 550` ms，确认次要敌人最近到达时间大于预算 `+ 250ms` 安全裕度时，允许直冲目标并在 `50ms`（常规 `135ms`）快瞄后立即挥刀。
- **推搡与砸地**：4 条互斥推/踩（冷却 500ms）+ 近战武器蓄力挥斩/倒地砸地；遇 `3m` 内 `>=4` 锁我且间距 `<0.5m` 丧尸触发 `1350ms` 脱身奔跑撤退（4s 冷却）。受击 `IsoNPC.Hit` 置 `600ms` 恢复期并拉高 `attackCollisionCancelled = true` 废除半途挥刀。

---

## 4. 远程完整链路、虚拟瞄准与室内 CQB 协同

### 4.1 虚拟瞄准与弹道劫持（`NPCVirtualAim` + `BallisticsControllerPatch` + `NPCFW_RangedActions`）

1. **骨骼采样与提前量**：每 `100ms` 采样头骨 `Bip01_Head*0.65 + Bip01_HeadNub*0.35`（水平限制 `0.45m`，站姿胸高兜底 `0.495F`，倒地 `0.22F`；瞬时倒地且距离 `>2.25m` 时锁定中心防止枪口骤跌）；叠加速度提前量 `clamp(vel*0.12s, max 0.35m)` 与 `0.10s` 平滑。
2. **准星运控**：带基于距离的角度稳定死区 `[0.003, 0.012] rad`；换目标前 `0.45s` 内角速度由 `90->270°/s`、`XY/Z` 追踪速度由 `1.5/0.7 -> 7.0/2.0` 格/秒做 Smoothstep 斜坡过渡；`750ms` 内重获瞄准保留原准星位置。
3. **逆投影接入原生弹道**：`IsoNPC.setAngleFromAim` 写入水平朝向并转换 `desiredForwardPitchRads`（兜底 `atan2(dz, dXY) * 2.44949F`）；`BallisticsControllerPatch` 在 `update()` 时按持枪者将 `AimingReticle.getX/Y` 定向为虚拟准星的屏幕像素坐标 `IsoUtils.X/YToScreen - IsoCamera.off`。
4. **扳机门控 `shouldPullTrigger`**：按紧急度 `urgency ∈ [0, 1]`（敌数/近距/疾跑/贴身加权）动态放宽角度容差 `[4°, 22°]` 与像素容差 `[8, 52]px`，满足稳瞄时长 `[80, 950]ms`（2.25m 内紧急高危直通快射）及 `aimingDelay` 门限时，规划 **1–4 发点射（`plannedBurstRounds`）**，通过 Lua `ISReloadWeaponAction.attackHook` + 注入 4 项 FMOD 参数的独立 `IsoWorld.getFreeEmitter` 枪声 + `npcfwResolveRangedShot` 立即计算 `CombatManager.attackCollisionCheck`（打 `NPCFWDirectRangedShot` 标记防动画事件重复结算）。

### 4.2 射界、风筝与换弹降级链路
- **射界与友伤**：`CQBMode.hasClearShot` 仅校验墙门障碍与车辆碰撞（未对友军身体停火，物理零友伤完全依赖 `CombatManagerPvpPatch` + `CQB` 错位/扇区）。
- **距离维持与紧急推搡**：普通模式按同层近敌数量在 `1.90 / 2.25 / 2.75m` 后退（带 `+0.90m` 防抖迟滞），超武器射程前进，通过 `±32/64°` 探测走廊并置退路阻断标识；有敌切入 `1.25m`（退路被堵 `1.50m`）直接发急推（冷却 `650ms`）。
- **换弹/排障优先链**：优先危急切换包内另一把已上膛枪（CQB / ≥2 敌 / ≤5m）；否则按「卡弹拉栓 → 无弹先换弹 → 仅空膛拉栓 → 常规换弹 → 闲时换更满弹匣战术换弹」走 Lua 250ms 节流动作；彻底弹尽时若 ROE 允许则按延迟切包内最优近战或卸枪空手肉搏。

### 4.3 室内 CQB 战术模块（`CQBMode`）
- 由开关开启且领队或跟随成员处于建筑室内（或距门窗极近）时自动激活：
  - **强制协同覆写**：ROE 强锁定 `"ranged"`、姿态锁定 `"defensive"`，仅派分责任扇区与 8 向弧段，且以 `FormationSnapshot`（80ms 缓存）依据环境宽窄自动在**楔形/横排（1–3 槽）与纵队（门口 ≤0.9m 停留 650ms、急弯、槽位受阻、误差 >1.25m 触发，全员 ≤0.55m 维持 800ms 才重展开）**间切换，配合 64 点 `BreadcrumbTrail` 轨迹精确跟弯。
  - **组内排队换弹锁**：同一编组同一时间仅允许 **1 人** 进入 `RELOAD_CLAIMS`（5s TTL），其余有装弹掩护火力时推迟换弹保火力不断档。

---

## 5. 非战斗叶节点摘要

- **`FollowLeaf`**（详见 [03-nav-locomotion-avoidance-tasks.md](file:///c:/Users/MuMu/Zomboid/Workshop/EmberRoutesV2/docs/references/project-remnants/03-nav-locomotion-avoidance-tasks.md) 避让与楼梯排队）：处理载具同乘/下车后 `30帧` 脱离车门碰撞 + `360 tick` 追队缓冲；`8m/6m` 直追迟滞与四档停走阈值；移动目标方向平滑（顺向 `0.22` / 急转反向 `0.65`）；直行↔A\* 切换 `2(6)/8` 帧迟滞、A\* 失败 `120..600` 帧指数退避、30 帧卡死四邻尝试与 20 帧垂直侧偏脱困。
- **`ExitVehicleLeaf`**：确认车停稳后派发原生下车动作，排空 8 tick 过渡或超时 240 tick 硬退出并清除车体锁。
- **`ThreatInvestigateNode`**：无可见目标但有记忆时收枪走近并在 `1.15m` 原地凝视警戒 `1050ms` 后消除记忆。
- **`GuardLeaf` / `StayLeaf` / `IdleLeaf`**：小队定守 1.5m 停靠、原地待命及闲置插入 `3–7s Wait`。

---

## 6. 据点守卫全链路与 16 步持枪站岗 AnimSet（`safehouse/guard/*`）

1. **双模式与严格几何校验（`GuardJobConfig`）**：
   - `GUARD_POST`：同层 `1×1..3×3` 矩形岗哨；
   - `PATROL`：单层连续四邻格（上限 `4096` 格），严禁自交与非相邻格四邻平行折返（防路径粘连，闭环自动循环，开环往返折返）。
2. **军械库扫描与三级后勤补给闭环（`GuardArmoryScanner` + `SafehouseGuardJob` + `GuardAmmunitionLogistics`）**：
   - 军械库 scanner（1s TTL）通过 B42 `CraftRecipe + OutputMapper` 自动反解弹药盒可拆弹数；
   - 顺序执行：配发/取用选定步枪或最佳库存枪（写入武器 ModData 戳 `npcfwGuardIssuedOwner`）→ 领足请求数弹匣 → **8 阶段弹药维护状态机**（仅当空仓或总备弹触及低水位时触发：优先拿散弹，否则取弹药盒走原版配方拆箱 → 装填弹匣 → 枪械补满 → 自动归还借用剩余散弹至弹药箱）。
   - 所有取用物品与岗哨/巡逻占用格均受 `30s` 互斥预留锁保护，遇威胁立即让位给战斗。
3. **哨位值守、战斗拴绳与据点联动警报**：
   - 岗哨每 `12–19s`（仅当 `GuardRifleAnimationController.canReposition` 处于居中态时）换位至最远空闲格；巡逻每走 `2–5` 格停下交替左右张望；
   - **`GuardCombatCoordinator`**：2.35m 内或正在挨打直接自卫；否则要求敌我距守卫区分别不超过 `{10/8m, +4m缓冲}`，超限或清敌后一键清零全部准星/黑板/架势平滑返岗；
   - **`SafehouseThreatAlert`**：据点居民发现距基地 `<= 12m` 威胁时（12s 冷却，入内打断冷却），在 `18m`（可调）半径内按优先级（内敌：`清醒GUARD > 清醒无业 > 睡GUARD/清醒工种`；外敌：`仅清醒GUARD`）唤醒最多 2 名支援者注入共享记忆。
4. **16 步持枪待机与巡逻扫描 AnimSet 状态机（`NPCFWGuardRifleBase.xml` + 16 子 XML）**：
   - 通过单一动画变量 `NPCFWGuardRifleAnim` 驱动 16 步序列（偶数 NPC 从 step 0、奇数从 step 8 起步错峰）：原版 `Bob_IdleRifle(0.8x)` → 过渡 → 自定义 `PR_Guard_Rifle_Idle_02(2.0x)` → `lookLeft_02(1.4x)` → `lookLeft_idle_02` → **`lookLeft_02` 倒放（reverse）平滑回中** → 居中 → 过渡回 `01` → 对称 `lookRight` 侧。
   - **关键 XML 防跳帧设置**：`<m_EarlyTransitionOut>false</m_EarlyTransitionOut>`（避免非循环过渡切片在条件仍命中时提前淡出导致从第 0 帧重播猛拉回中），并在 `<m_Transitions>` 中显式声明衔接 `aim_rifle / walkRifle / runRifle / sprintFirearm / sneak*`。巡逻停步时仅复用 `[1..7]`（左看）或 `[9..15]`（右看）子区间。
