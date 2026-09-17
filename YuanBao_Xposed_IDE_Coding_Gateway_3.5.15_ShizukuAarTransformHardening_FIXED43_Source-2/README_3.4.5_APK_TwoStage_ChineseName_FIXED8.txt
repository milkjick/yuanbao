3.4.5 FIXED8

Fixes from FIXED7:
1. NativeToolCard.addTool overload mismatch fixed. beginTool(name,args,displayName) now compiles against the 3-argument ToolRow constructor and preserves raw tool name for finishTool matching while showing displayName in the UI.
2. detectAgentStyle(sp, toolsLen) remains: toolsLen > 0 returns false, so standard OpenAI tools requests are not wrapped into private action JSON.
3. APK open path handling recognizes locator/uri/source as path-like arguments and converts /storage/emulated/0/MT2/... to MT2-relative paths before MCP tools/call.
4. APK stage-2 open prefers locator when the discovered schema exposes it and sets temporary=true when supported.
5. Native model-facing result prompt now includes a compact technical diagnostic block: tools.len, agent chain, and the three routing changes.
6. APK list -> items[].path -> mt_apk_open remains the deterministic two-stage flow. Multiple APKs require a confident Chinese name/package/path match or a single-item list; otherwise inventory is returned instead of guessing.

Example expected stage 2:
mt_apk_open {
  "locator": "mcp/something.apk",
  "temporary": true
}

Absolute user paths are never intentionally passed to mt_apk_open. The MCP 2026-07-28 transport remains compatible with modern stateless tools/call routing and legacy fallback.

Build note: source ZIP integrity was checked. Full Gradle compilation could not be completed in the build environment because Gradle 8.2 distribution download is unavailable offline.
