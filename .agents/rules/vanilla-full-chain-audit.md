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
- **动工前必须先输出《原版全链路对照表》再写代码**：凡涉及任何原版机制的新模块开发、重构或对齐修复，在动笔改代码之前必须先向用户列出结构化对照表，至少逐项写清：
  1. **机制/环节**（入口、门控/距离/遮挡校验、动作与动画事件、时长/消耗/副作用公式、正常结束与异常打断/卸载回收）；
  2. **原版源码依据**（`zombie反编译源码` / `media/lua` / `media/AnimSets` 的具体文件、方法与行号）；
  3. **本次拟实现方式**（直接复用原版类/方法，或因非玩家槽位等硬限制做 1:1 等价对齐）；
  4. **是否存在刻意差异与原因**（凡有省略、近似或绕行，必须在表中明示并等用户确认，严禁未经核对凭经验抄近道）。
- **骨骼/根位移开关必验全程关键帧**：凡启用 `m_DeferredBoneName`、`m_useDeferedRotation` 等直接改写骨骼变换或反旋转的引擎开关时，必须将引擎源码公式代入目标动画的**起点、中段、终点全程关键帧**逐段验算，严禁只对比首尾两帧就下结论。

## 3. 尊重原版玩法、复用原版链路与机制（严禁自作聪明抄近道，也不机械照抄错配参数）

- **区分玩法/引擎机制与特定资产参数（质量无损下走最简原生路径）**：当需要实现角色转向过渡、步幅变化、动作衔接、吃喝医疗、手部模型挂载、物品掏取流转或碰撞推出时，**必须先在 `zombie反编译源码`、`media/lua`、`media/AnimSets` 以及 V1 / `ProjectRemnants` 参考实现中查清真玩家是如何完成该全流程的**，直接利用原版机制（例如：步行态复用 `super.getTurnDelta() = 1.0f` 与 `AnimationPlayer.interpolateBodyAngle/Twist`；吃喝/包扎遵循原版背包→主栏→`BaseAction`→归还；动画衔接优先使用原版 XML `<m_Transitions>`）。对于自定义资产，不得机械照抄不适配其骨骼姿态的原版 XML 开关并用 Java 数学补偿去硬扛副作用——在不牺牲最终质量的前提下走最简原生路径。
- **严禁自作聪明阉割原版步骤**：绝不允许为了省事跳过原版玩家必经的玩法约束或渲染挂载步骤，更不允许在未核对原版默认值与生效条件的情况下凭主观感觉塞入魔法倍率或假动作壳。
- **探查无捷径时必须先详细讨论**：若已全面探查完原版所有可用类、方法与状态机分支，发现确实存在硬限制、无现成捷径可用（例如非玩家槽位在某处会触发硬编码越界，必须做定制绕行），**必须先停止编码，向用户详细列出探查过的原版代码位置与阻塞原因，与用户充分讨论并达成一致后，才允许落地定制方案**。
