---
trigger: always_on
description: 原版 B42 引擎全链路前置通读铁律：固化本地完整 Java 反编译源码、原版 Lua 与 AnimSets 路径，严禁 javap 碎片化摸象与挤牙膏式试错
---

# 原版引擎全链路前置通读铁律 (Vanilla B42 Full-Chain Audit Rule)

在涉及任何与 Project Zomboid B42 引擎耦合的模块（包括但不限于：NPC 身体控制与寻路、骨骼动画与根位移、TimedAction 动作交互、战斗与状态机、容器读写、载具、睡眠、ISUI 组件）时，必须无条件执行以下规则：

## 1. 本地原版完整源码基准路径（禁止宣称找不到或盲啃字节码）

本机已备齐与当前安装版 B42 一致的完整可读源码树，**必须直接作为第一手通读入口**：

- **原版 Java 完整反编译源码树（带完整局部变量名与行号）**：
  `D:/Software/GameApp/steam/steamapps/common/ProjectZomboid/zombie反编译源码`
  （例如角色与运动：`characters/IsoPlayer.java`、`characters/IsoGameCharacter.java`、`characters/IsoLivingCharacter.java`、`core/skinnedmodel/animation/AnimationPlayer.java`、`pathfind/PolygonalMap2.java`、`pathfind/PathFindBehavior2.java`、`pathfind/nativeCode/PathfindNative.java`）
- **原版动画状态机与混合树配置（AnimSets XML）**：
  `D:/Software/GameApp/steam/steamapps/common/ProjectZomboid/media/AnimSets`
- **原版 Lua 业务逻辑与 ISUI 源码**：
  `D:/Software/GameApp/steam/steamapps/common/ProjectZomboid/media/lua`
- **安装版二进制与字节码增强框架（仅在游戏版本升级核验差异或查非 `zombie.*` 包时使用 `javap`）**：
  - `D:/Software/GameApp/steam/steamapps/common/ProjectZomboid/projectzomboid.jar`
  - `D:/Software/GameApp/steam/steamapps/common/ProjectZomboid/ZombieBuddy.jar`

## 2. 动工前一次性拉通全链路（严禁碎片化“手电筒”排查）

- **严禁拿 `javap -c` 截取几十行片段盲人摸象**：有 `zombie反编译源码` 时，必须直接用文件搜索与全文分段读取 Java 源码，看清完整上下文与局部变量含义。
- **严禁被动按症状挤牙膏**：在写任何一行设计或代码前，必须从引擎帧入口自顶向下把**整条端到端调用树**一次性画全并核实所有分支。
  - *示例（角色每帧生命周期）*：`preupdate()` -> `update()` -> `updateInternal1()` -> `updateInternal2()`（输入冲刷、`MoveVars`、`AIComponent.postUpdatePlayer`）-> `IsoLivingCharacter.update()` -> `IsoGameCharacter.update()`（`updateMovementRates` / `calculateWalkSpeed` -> `applyDeltas` / `getTurnDelta` -> `updateAnimPlayer` -> `doDeferredMovement` / `getDeferredMovement`）-> `postupdate()`。
- **未读完全链路不得下结论或动手写代码**：凡未核查原版同场景（如真玩家键鼠/右键“移动至”/原版 TimedAction）完整执行链的方案，一律视为准备不足。

## 3. 原生过渡与引擎原语优先（严禁凭猜测覆写原版物理参数）

- 当需要实现角色转向过渡、步幅变化、动作衔接或碰撞推出时，**必须先在 `zombie反编译源码` 与 `media/AnimSets` 中查清真玩家是如何完成该过渡的**（例如步行态直接复用 `super.getTurnDelta() = 1.0f` 与 `AnimationPlayer.interpolateBodyAngle/Twist`，无级调速复用 `WalkSpeed` 2D 混合树）。
- 严禁在未核对原版默认值与生效条件（如 `isRunning()` 门控）的情况下，凭主观感觉塞入魔法倍率覆盖原版参数，把用户当成多轮实机排错测试员。
