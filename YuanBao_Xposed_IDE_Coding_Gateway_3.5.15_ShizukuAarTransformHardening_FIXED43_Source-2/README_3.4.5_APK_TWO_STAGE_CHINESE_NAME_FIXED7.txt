YuanBao Local OpenAI Bridge 3.4.5 FIXED7

APK two-stage MCP Agent loop:
1. User gives an APK directory, e.g. /storage/emulated/0/MT2/mcp/.
2. Gateway calls mt_apk_list_available_apks with normalized prefix mcp/.
3. Gateway parses returned items[].path and metadata.
4. Chinese APK names are extracted from common fields: chineseName/appName/applicationName/label/displayName/name/title.
5. If the user prompt clearly names an APK, the gateway scores and selects the matching item. If there is exactly one APK, it is selected automatically. If multiple APKs exist without a confident match, the gateway does not guess; it returns a Chinese-readable inventory to YuanBao for selection.
6. mt_apk_open receives ONLY the exact path returned by items[].path. The original absolute directory is never sent to mt_apk_open.
7. Tool card shows both stages in the same YuanBao chat RecyclerView item: list -> selected APK open -> results.
8. APK open results are fed back into YuanBao with Chinese name/package/version metadata when available.
9. No credentials, cookies, tokens, signatures, or TLS bypass logic is added.
