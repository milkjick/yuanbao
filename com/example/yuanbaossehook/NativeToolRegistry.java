package com.example.yuanbaossehook;

import org.json.JSONObject;

/**
 * Native Agent tool execution plane.
 *
 * Native YuanBao tasks use this registry directly. They do not enter the
 * local OpenAI-compatible HTTP gateway on port 8318. If a registered tool is
 * implemented by a remote MCP server, McpIdeGateway.callToolDirect() contacts
 * that MCP server directly; the :8318 bridge remains an external-client path.
 */
final class NativeToolRegistry {
    private NativeToolRegistry() {}

    static JSONObject callDirect(String publicName, JSONObject args) throws Exception {
        if (publicName == null || publicName.trim().isEmpty()) {
            throw new IllegalArgumentException("tool name is empty");
        }
        JSONObject safeArgs = args == null ? new JSONObject() : args;
        MainHook.log("[NATIVE-TOOL] direct call name=" + publicName + " args=" +
                McpIdeGateway.shortenForLog(safeArgs.toString(), 1200));
        long start = System.currentTimeMillis();
        try {
            JSONObject result = McpIdeGateway.callToolDirect(publicName, safeArgs);
            MainHook.log("[NATIVE-TOOL] direct result name=" + publicName +
                    " ms=" + (System.currentTimeMillis() - start) +
                    " len=" + (result == null ? 0 : result.toString().length()));
            return result;
        } catch (Throwable t) {
            MainHook.log("[NATIVE-TOOL] direct error name=" + publicName +
                    " ms=" + (System.currentTimeMillis() - start) + " err=" + t);
            throw t;
        }
    }
}
