# APK Large Decompile FIXED12

For APKs at or above 200 MB, the gateway no longer routes directly to mt_apk_open.
It first discovers a dedicated APK decompile/disassembly tool from MCP tools/list.
Preferred tool names include mt_apk_decompile, apk_decompile, decompile_apk, and tools whose
name/description indicates APK decompile, disassembly, dex2smali, smali, or JADX analysis.

The gateway passes only the path returned by mt_apk_list_available_apks (for example
mcp/something.apk). It does not pass the user's absolute /storage/emulated/0/MT2 path to the
analysis tool. It builds arguments from the discovered tool schema and prefers a Java/JADX
mode when the schema exposes it, otherwise smali/dex/disassembly mode. Recursive/full/open_all
flags are disabled when those properties exist.

If no decompiler is available, the gateway falls back to mt_apk_search overview. It never falls
back to mt_apk_open for a large APK merely because the lightweight tools are absent.

This is a routing change in the Xposed gateway. The MCP server must actually expose a suitable
analysis/decompile tool for the corresponding operation to execute.

Reference: Apktool documents APK decoding/disassembly via `apktool d`, and supports options such
as --no-res, --no-src, --all-src and --jobs. See https://apktool.org/docs/cli-parameters/ .
