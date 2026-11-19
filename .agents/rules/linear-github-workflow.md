# Linear ↔ AI ↔ GitHub 协同开发与防腐化规范

## 1. 触发范围与旁路模式
- **常规开发模式**：所有涉及修改仓库代码（Java / Lua / Python / PS1）、本地化（`Translate/`）、配置或文档（`docs/`）的任务，必须走完整分支与 PR 闭环。
- **只读豁免**：纯技术问答、架构讨论、只读代码排查，不开设分支、不创建 PR。
- **实机报错快速入库模式**：当用户直接粘贴游戏红字堆栈或临时点子并要求“记一下/入库”时，**不切分支、不改代码**，直接提炼核心摘要、判定优先级，写入 Linear（归属 `EmberRoutes V2` 对应 Milestone/Inbox），并返回生成的 `EMB-xxx` 编号。

---

## 2. 标准开发闭环（严禁跳步）

### Step 1：领任务与前置检查（Linear → AI）
1. 根据用户指定的 `EMB-xxx` 或从 Linear（按 `EmberRoutes V2` 当前激活 Milestone + Priority）拉取最高优先级待办，读取需求、验收标准（AC）及官方 `gitBranchName`。
2. **依赖拦截**：检查该 Issue 是否存在未完成的 `Blocked by` 前置任务；若存在阻塞，立即停止开发并向用户汇报，严禁在错误前提下强行编码。
3. **按任务边界拆分（不卡死文件数）**：常规 PZ 模组功能常同时涉及 `Java + Lua + Translate/CN + Translate/EN + docs/`，**不得按固定文件数量机械拆分**。仅当单任务包含多个可独立验收的子阶段，或涉及跨模块重大重构时，先输出方案（Spec），征得用户同意后在 Linear 拆分为 Sub-issues 逐个交付。

### Step 2：工作区安全检查与分支隔离（Git）
1. **分支红线**：严禁直接在 `main` 分支修改或提交代码。
2. **脏工作区保护（切分支前必检）**：先执行 `git status`：
   - **工作区干净**：执行 `git checkout main && git pull --ff-only && git fetch -p`，再基于最新 `main` 检出独立任务分支（优先使用 Linear 提供的分支名，如 `feat/emb-12-xxx`）。
   - **存在未提交改动**：遵循 `Workshop/AGENTS.md` 保护用户已有改动的原则，**立即暂停并向用户确认处理方式**，严禁擅自执行 `git reset` 或 `git checkout --`。

### Step 3：编码与本地质量门禁（Mandatory Gates）
1. 严格遵循工作区及仓库级 `AGENTS.md`、`docs/` 架构规范；严禁将更新日志写入 `workshop.txt`。
2. **文档同步**：若改动涉及系统架构、存档结构、公共事件或构建脚本，必须同步更新 `docs/` 对应文档。
3. **门禁红线**：提交前必须全部跑通以下本地检查（未通过严禁 Commit 或创建 PR）：
   - **构建检查**：`./build.ps1`
   - **仓库预检**：`node ../.workbuddy/tools/pz.mjs preflight .`
   - **UI 专项检查**（涉及 UI 改动时必跑）：`./tools/preview-ui.ps1`
   - **文档断链检查**（涉及文档改动时必跑）：`node ../.workbuddy/tools/pz.mjs doclink <相关改动文件>`

### Step 4：规范提交与创建 PR（AI → GitHub）
1. **代理兜底**：在子进程调用 `gh` 命令遇到网络超时或 EOF 时，自动为当前命令注入环境变量 `HTTP_PROXY=http://127.0.0.1:7890` 与 `HTTPS_PROXY=http://127.0.0.1:7890`。
2. **Commit 规范**：按功能小步提交，格式必须包含任务编号：`feat(EMB-12): 简述改动内容`。
3. **创建 PR**：推送分支后执行 `gh pr create`，严格套用 `.github/pull_request_template.md` 填充正文：
   - **PR 标题**：`[EMB-xxx] 简明任务标题`
   - **Linear 联动词**：独立完结任务写 `Closes EMB-xxx`；若仅为拆分后的阶段性子交付（父任务未全完），**严禁使用 `Closes`**，必须改用 `Part of EMB-xxx` 或 `Refs EMB-xxx`。
   - **标签匹配**：根据改动目录附带 `--label`（`java-src/` → `java`，`Contents/mods/` → `lua`，UI 相关 → `ui`，`docs/` → `docs`；若仓库尚未建立对应标签则跳过不报错）。
   - **草稿模式**：尚在多轮调试中的半成品加 `--draft` 创建草稿 PR，确认门禁全通后再执行 `gh pr ready`。

### Step 5：人类验收、实机返工与闭环（User → GitHub → Linear）
1. **合并权限红线**：AI 默认**只创建/更新 PR，绝不擅自执行 `gh pr merge`**。由用户在 GitHub 审阅变更清单与风险自检（必要时切至该分支进游戏实机测试）后手动点击 Merge，触发 Linear 自动将 `EMB-xxx` 标记为 `Done`。（仅当用户在当次指令中明确要求“直接合并”时例外）。
2. **实机测试返工模式（Fix-on-Branch）**：若用户在 PR 合并前通过游戏实测反馈 Bug 或修改意见，**严禁新建分支或另开 PR**。AI 必须停留在当前任务分支上追加 `fix(EMB-xxx): 问题描述` 提交，重新跑通本地门禁后 `git push` 更新原 PR，并通过 `gh pr comment` 简述修复摘要。
3. **发版日志规范**：里程碑达成后可用 `gh release create <tag> --generate-notes` 生成 GitHub Release 或本地发布说明，严格遵守 `Workshop/AGENTS.md`，**不得写入 `workshop.txt`**。

---

## 3. 交付速查与经验沉淀

### 3.1 交付必附“1 分钟验收速查卡”
每次成功创建或更新 PR 后，AI 必须在回复末尾输出以下三项摘要供用户快速验收：
1. **PR 链接与改动量**：PR 编号、URL 及 `git diff --stat` 汇总。
2. **实机测试路径**：具体游戏内操作验证步骤（无需进游戏则注明“纯静态/构建改动”）。
3. **高风险盯防点**：指出本次改动最核心的 1~2 个文件或方法，提示用户优先审查。

### 3.2 经验沉淀（Self-Evolving Rules）
- 若本次任务或审查发现了新的架构约束或 Project Zomboid Build 42 底层接口陷阱，AI 应在同分支中将其精简为一条规范追加至 `AGENTS.md` 或 `.agents/rules/lessons.md`，防止后续任务重复踩坑。
