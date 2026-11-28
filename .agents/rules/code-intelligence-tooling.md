---
trigger: always_on
description: 代码智能与源码检索分工规范：自有仓优先 Serena LSP，原版大仓用 ripgrep + ast-grep，固化避坑铁律
---

# 代码智能与源码检索分工规范

## 1. 自有业务仓（`EmberRoutesV2` / `EmberUI`）：优先 Serena MCP

- **激活项目**：首次调用 Serena 时，先调 `initial_instructions` 获取 `session`，再传入 `session` 调用 `activate_project("EmberRoutesV2")` 或 `activate_project("EmberUI")`。
- **大文件读法**：300 行以上的 Java / Lua 文件，先调 `get_symbols_overview` 获取类/方法目录，再通过 `find_symbol(..., include_body=True)` 精准摘取目标方法，**禁止无脑整文件分片 `read_file`**。
- **即时语义校验**：修改 Java 或 Lua 文件后，调用 `get_diagnostics_for_file` 检查编译错误、`projectzomboid.jar` / `ZombieBuddy.jar` 类型签名及 `.luarc.json` 桥接存根告警，不等全量 `./build.ps1` 才暴露类型错误。
- **Serena 避坑铁律**：
  1. **Java 查引用必须带前导斜杠**：调用 `find_referencing_symbols` 时，`name_path` **必须使用带前导 `/` 的绝对路径（如 `/BrainRouter/tick`）**。实测不带 `/` 的 `"BrainRouter/tick"` 会静默退化为查找类引用，从而漏掉真实方法调用方。
  2. **Lua 使用扁平符号名**：`LuaLS` 将 `function M.foo(...)` 索引为扁平函数名（如 `registerModule`、`displayPanel`、`syncSettlementAreas`），调 `find_symbol` / `find_referencing_symbols` 时不要写 `Kernel.registerModule`。
  3. **严禁对原版反编译目录启用 Serena**：不要把 `Serena` / `jdtls` 指向 `zombie反编译源码`（无标准构建工程且会卡顿报错）。

## 2. 原版大仓（`zombie反编译源码` / `media/lua` / `media/AnimSets`）：`rg` + `ast-grep`

- **全文检索一律用 `rg`**：严禁再用 PowerShell `Get-ChildItem | Select-String` 扫原版 7,400+ 文件（`rg` 快 2.4x–8.4x，且默认大小写敏感、自动跳过二进制与 `.gitignore`）。
- **结构化审计用 `ast-grep`（MCP 或 CLI）**：
  - 查方法调用（跨行参数）、方法/TimedAction 定义、子类继承关系时优先使用 `ast-grep` 跳过注释与字符串干扰。
  - **Java 规则避坑**：Java 存在 `@Override`、`public final`、`implements` 与跨行签名时，裸内联模式 `-p` 极易漏检；**必须改用 `ast-grep scan --inline-rules`（或 MCP `find_code_by_rule`）编写 `kind: method_declaration` / `kind: class_declaration` 的 YAML 规则**。
  - **禁止直接回吐大体积 JSON**：CLI 传 `--json` 时必须在脚本侧提取 `file` / `range.start.line` 等摘要字段，严禁将数百 KB 原始 JSON 灌入上下文。

## 3. 项目专属提效工具

- **原版字节码速查库（`tools/pz_index.py`）**：查原版多态继承链与精确重载调用方优先用 `python tools/pz_index.py subclasses|callers|callees|methods|sql`（如 `.cache/pz_b42.sqlite` 缺失则运行 `python tools/pz_index.py build` 重建）。
- **实机日志提纯（`tools/tail_pz_log.py`）**：排查 `C:/Users/MuMu/Zomboid/console.txt` 或 `Logs/*_DebugLog.txt` 前，先跑 `python tools/tail_pz_log.py`（或 `--debuglog`）提取最后一次启动的去重错误栈、生命周期与慢帧摘要，严禁整文件读入。
- **原版 Lua 全局存根（`tools/GenPzLuaGlobalsStub.java`）**：若 `.cache/pz-stubs/pz_b42_globals.lua` 缺失或游戏更新后，运行 `java tools/GenPzLuaGlobalsStub.java` 重建 Tier 1 全局存根（严禁引入全暴露类 Tier 2）。
- **自动轻门禁（`.agents/hooks.json`）**：`Stop` 阶段由 `tools/stop_gate_hook.py` 自动按脏文件触发 `check_i18n.py` / `check_arch.py`。
