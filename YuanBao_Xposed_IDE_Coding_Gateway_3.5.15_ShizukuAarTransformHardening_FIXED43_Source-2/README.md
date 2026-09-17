3.4.5 MCP AutoLoop Fix: 外部 MCP 固定迁移到 127.0.0.1:8787/mcp（10.103.160.2:8787/mcp 备用），补齐 tools/list、标准 OpenAI tool_calls -> tools/call -> role=tool Agent Loop，增加绝对路径归一化与 [MCP] 诊断日志；RecyclerView Tool Card 仍保持真实 Adapter Item。

# YuanBao Xposed IDE Coding Gateway 3.4.5

本版本在 3.4.1 基础上增加原生 YuanBao DeepSeek 风格 MCP Tool Card：工具调用、执行中、结果、失败状态均显示在连续卡片中；<yb_tool_call> 协议仍由原有 UI sanitizer 隐藏。真实 MCP 调用仍由 McpIdeGateway 执行。

DeepSeek 官方工具调用模型也是 tool call -> tool result -> 后续模型回答的循环；本项目仅借鉴该交互结构，不复制 YuanBao 内部私有 UI API。

# 3.4.1 Native continuous MCP UI fix

- Suppress `<yb_tool_call>`, `<tool_call>`, and `<call>` protocol fragments before YuanBao renders them.
- Preserve ordinary prose in the same native stream.
- Recover split protocol tags across streaming chunks.
- Treat short continuation prompts such as `继续分析` as continuation of the previous MCP task for up to 10 minutes.
- Keep dynamic tools/list discovery; no fixed MCP server URL or APK tool name is introduced.
- Native MCP execution still happens through the discovered MCP transport; this change only fixes native presentation/correlation.

# 3.4.0 — Standard OpenAI Tools / DeepSeek-style MCP client interoperability

- When `/v1/chat/completions` receives `tools`, the gateway now uses a strict standard OpenAI tool mode.
- The upstream assistant `message.tool_calls` are returned as native `tool_calls`; no `action/params/final_answer` wrapper and no `<yb_tool_call>` conversion.
- Streaming responses emit native `delta.tool_calls` chunks and `finish_reason=tool_calls`.
- `/v1/models` reports `tools=true` and `function_calling=true`.
- The gateway-side autonomous loop remains available when the client does not send a `tools` array.
- This is intended to let MCP-capable clients render their own tool-execution UI such as “已执行工具(1次)” and resource/tool result rows.

## 3.3.9 Native MCP Direct Intent Execution
- Native YuanBao requests with a high-confidence discovered MCP match execute the first safe MCP tool directly before asking the model to emit the private `<yb_tool_call>` protocol.
- APK analysis recognizes real discovered APK/open/decompile tools and extracts Android absolute paths such as `/storage/emulated/0/...` from Chinese natural-language requests.
- The model receives the real MCP result and can continue multi-round tool use.

# YuanBao Xposed IDE Coding Gateway 3.1.1

本版本针对 AIDE / Android Code Studio 离线构建进行了依赖收缩：设置界面不再依赖 AndroidX、Material Components、AppCompat、ViewPager2、Emoji2 等 AAR。

## 功能
- 元宝 App 内注入 Agent 设置入口
- API Base URL / MCP URL / 本地 API Key 一键复制
- MCP Server 增删改、启停、tools/list 刷新
- System Prompt / 角色定位
- 项目长期记忆
- OpenAI/MCP Agent 网关与多轮工具调用

## 构建

```text
./gradlew :app:assembleDebug
```

只需要 Android SDK、Android Gradle Plugin、Gradle 和 `app/libs/compile_only/xposed-api-82_compileonly.jar`。

## 注意
设置界面使用 Android 原生控件实现 Material You 风格，目的是避免 Xposed 模块在目标 App 进程内加载外部 Material/AAR 依赖导致离线构建或资源冲突。


## 3.1.2 Native YuanBao Agent

3.1.1 的 MCP Agent 网关只对外部调用 `127.0.0.1:8788/v1/chat/completions` 生效；元宝原生聊天走 `hb.I6.s3`，因此仅修改 `buildToolPrompt()` 不会让元宝原生聊天自动调用 MCP。

3.1.2 增加原生聊天前置执行器：
- 在 `hb.I6.s3` 发送阶段识别明确的 MCP / APK 分析 / 反编译 / 读取 / 搜索 / 构建 / 抓包意图；
- 后台调用真实 `tools/list` + `tools/call`；
- 将真实 MCP 结果附加到本轮元宝输入后重新发送；
- APK `open -> workspaceId -> analyze` 支持自动串联；
- 不根据模糊文本猜测写文件、shell、patch 等破坏性操作；
- 设置页增加“元宝内置 Agent：自动执行 MCP 工具”开关。

日志关键字：
`YB-MCP: [NATIVE-AGENT] CALL ...`
`YB-MCP: [NATIVE-AGENT] intent=... -> ...`


## 3.1.6 Gateway/overlay fixes

- 将元宝内浮动设置按钮从右下角移到右上角，避免覆盖原生输入框、发送/停止按钮。
- 8318 本地 OpenAI Bridge 使用更明确的 HTTP 连接关闭/响应边界。
- 8788 -> 8318 上游请求增加 EOF/连接异常的一次短重试，并记录 `[UPSTREAM]` 错误。
- 8788 `/health` 增加 8318 上游健康探测字段。
- 设置页增加“测试真实 Chat 反代”，会真正请求 `127.0.0.1:8788/v1/chat/completions`，而不是只测试端口。


## 3.1.6 修复
- 修复非流式 `/v1/chat/completions` 在 `pi.e` 完成回调中提前关闭客户端 Socket，导致 HTTP 500 / `unexpected end of stream`。
- Gateway 触发 `hb.I6.s3()` 时增加桥接请求标记，避免 Native Agent 对 8788→8318 内部请求递归拦截。
- Native Agent 触发线程正确清理 ThreadLocal。

## 3.1.6 针对 HTTP 500 / unexpected end of stream 的修复

本版本针对日志中出现的：
`java.net.SocketException: Socket closed` / 客户端 `unexpected end of stream`。

根因：非流式 `/v1/chat/completions` 使用 `ByteArrayOutputStream` 收集 YuanBao SSE；原实现的 `finish()` -> `cleanup()` 无条件关闭 RequestContext 的客户端 Socket，随后 HTTP handler 还要向同一个 Socket 写最终 JSON，因此写回时只能得到 `Socket closed`。

修复：RequestContext 增加 `streaming` 标记；只有真正的 `stream=true` 请求在完成后关闭客户端 Socket，`stream=false` 保留 Socket 到 HTTP handler 写完最终 `application/json` 后再关闭。

同时修复 8788 -> 8318 内部触发 `hb.I6.s3()` 时的 Native Agent 递归：Gateway 已经在处理中的请求通过 `CURRENT_REQ` 标记，`hb.I6.s3` Hook 不会再次进入 Native Agent。


## 3.1.6 LAN Gateway + MCP Status
- In-app MCP page adds “测试所有 MCP 服务器状态”: tests enabled servers with initialize + tools/list and reports tool count/latency/error.
- API page adds LAN Base URL/MCP URL and a switch to expose the gateway on `0.0.0.0:8788`.
- LAN mode automatically enables API-key authentication; loopback mode may remain unauthenticated if desired.
- Changing LAN mode restarts the Java gateway immediately.
- No credentials/cookies/signing material are extracted or replayed.


## 3.2.0 MCP Probe Fix
- MCP status testing no longer assumes every server supports the legacy `initialize` + `Mcp-Session-Id` flow.
- Probes MCP 2026-07-28 stateless `server/discover` and `tools/list` first, then falls back to 2025-era initialize/session compatibility.
- Reports actual HTTP status, negotiated/probed protocol, tool count, latency, and error details.
- The normal tools/list/tools/call client path uses the same compatibility negotiation, so modern MCP servers are not only detectable but callable.


## 3.1.9 Native Agent recursion/correlation fix
- 修复 `CURRENT_REQ` 被 I7.a4 长时间保留导致原生聊天永远被误判为 gatewayTriggered 的问题。
- Native Agent 递归保护改为独立 `BRIDGE_TRIGGER` ThreadLocal，仅在网关同步调用 `hb.I6.s3()` 的瞬间生效。
- `hb.I6.s3` 增加 `[NATIVE-AGENT-CHECK]` 诊断日志，显示 enabled/bridge/replay/match。
- 扩展“怎么修/修复/修改/修一下”等常见附件分析意图。


## 3.3.3 MCP 2026-07-28 detection fix

- Correctly reads `server/discover.result.supportedVersions` instead of relying on the obsolete singular `protocolVersion` field.
- `server/discover` is optional for HTTP probing; if it returns JSON-RPC `-32601` or HTTP 404/405, the client probes `tools/list` directly using the same 2026-07-28 stateless transport.
- No `initialize` handshake and no `Mcp-Session-Id` fallback is restored.
- Removes non-standard `io.modelcontextprotocol/serverId` and `serverAlias` request metadata.
- Native agent can recover a conservative tool call from the original explicit request when the model emits plain prose instead of the tool-call tag.

## 3.3.7 Dynamic MCP Auto-Call
- Native YuanBao Agent trigger is derived from the currently discovered MCP catalog instead of a fixed MCP/tool list.
- Tool matching uses public name, real MCP name, description, inputSchema, user intent keywords, and explicit tool-name mentions.
- `tools/list` remains the source of truth for available MCP tools.
- Read/search/list/analyze/decompile/network/build tools can be selected from explicit user intent; mutating/delete/shell tools require an explicit action or direct tool mention and are never guessed from vague requests.
- Tool arguments are mapped from the discovered JSON Schema and user text/path hints.
- No external MCP service URL is embedded in the source.


## 3.4.5 RecyclerView Item

MCP Tool Card 已从 RecyclerView Overlay 改为直接 Hook YuanBao 当前聊天 Adapter 的真实 Item 生命周期；不创建 Dialog/Window/Overlay。详见 `README_3.4.5_REAL_RECYCLERVIEW_ITEM.txt`。
