# YuanBao Xposed IDE Coding Gateway 3.3.7

本版继续基于 3.3.6 的动态 MCP Agent 架构，重点修复“各种 MCP 无法识别/无法自动调用”的兼容性问题。

## 主要改动

- MCP Server 地址全部来自用户配置，不预置外部 MCP URL。
- 2026-07-28 modern MCP 优先：`tools/list` / `tools/call` 使用 `MCP-Protocol-Version`、`Mcp-Method`、必要时 `Mcp-Name`。
- 2025 及更早 handshake-era 自动降级：依次尝试 `2025-11-25`、`2025-06-18`、`2025-03-26`、`2024-11-05`、`2024-10-07`。
- legacy HTTP+SSE 自动发现：GET 用户配置的 MCP URL，解析 `event: endpoint`，然后使用返回的 POST endpoint 完成 initialize。
- `tools/list` 自动读取分页 `nextCursor`，直到工具目录完整或达到保护上限。
- 动态工具目录：根据真实 MCP `name`、`description`、`inputSchema` 生成 Agent 工具，不写死工具名。
- Native YuanBao Agent 支持显式 tool call，以及无 tool-call 标签时的安全动态意图恢复。
- Native Agent 的自动恢复使用真实已发现工具；不会因为源码中不存在的固定工具名而调用失败。
- MCP 设置页新增“传输层诊断”，可直接看到 modern / legacy / HTTP+SSE 的实际探测结果。
- 保存 MCP 配置后在后台刷新，避免阻塞设置页。
- 保留旧版本内置 MCP 条目的迁移清理逻辑，但不会重新创建这些服务。

## 重要端口说明

- `8318`：YuanBao Local OpenAI Bridge 上游接口。
- `8788`：本项目自己的 MCP/OpenAI-compatible Gateway。
- 外部 MCP Server 的地址不由本项目硬编码，完全来自 MCP 设置页。

## 验证建议

1. 在元宝本地 Agent → MCP 中填写实际运行的 MCP URL。
2. 点击“保存 MCP 配置”。
3. 点击“测试所有 MCP 服务器状态”。
4. 点击“MCP 传输层诊断”，确认出现 modern 或 legacy 协议及 HTTP 状态。
5. 点击“刷新 tools/list”，确认“已发现 N 个外部 MCP 工具”。
6. 在元宝原生聊天中提出与已发现工具匹配的明确任务，观察日志中的 `[NATIVE-AGENT]`、`[MCP-REQ]`、`tools/call` 和工具结果。

本环境无法下载 Gradle 8.2 分发包，因此没有声称完成最终 Android `assembleDebug`；请在 AIDE/Android Studio/Android Code Studio 中执行实际构建。
