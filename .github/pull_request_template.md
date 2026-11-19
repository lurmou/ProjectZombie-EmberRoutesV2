## Linear 关联
<!-- 独立完结任务保留 Closes；拆分后的阶段性子 PR 请改为 Part of 或 Refs -->
Closes EMB-xxx

## 核心改动摘要
- 

## 本地门禁验证
- [ ] `./build.ps1` 构建通过
- [ ] `node ../.workbuddy/tools/pz.mjs preflight .` 预检通过
- [ ] `./tools/preview-ui.ps1` UI 预览检查通过（如涉及 UI）
- [ ] `node ../.workbuddy/tools/pz.mjs doclink` 文档断链检查通过（如涉及文档）

## 变更文件统计 (`git diff --stat`)
```text
（粘贴 git diff --stat 输出）
```

## 高风险自检
- **存档序列化字段变更**：无 / 有（说明兼容处理）
- **Java ↔ Lua 跨端协议/事件变更**：无 / 有（说明影响范围）
- **`workshop.txt` 隔离确认**：已确认未向 `workshop.txt` 写入更新日志
