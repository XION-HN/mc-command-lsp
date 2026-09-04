package dev.mccmd.lsp;

import dev.mccmd.data.CommandIndex;
import dev.mccmd.data.ItemIndex;
import dev.mccmd.engine.CommandCompleter;
import dev.mccmd.engine.CommandCompleter.Result;
import dev.mccmd.engine.CommandCompleter.Suggestion;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * LSP 核心逻辑（进程内复用）：文档镜像 + 版本 + completion 组装。
 *
 * <p>供两类使用者：stdio/TCP 网络 server（走 JSON-RPC）与 haisa 的“进程内 client”
 * （直接调本类，不经过 socket）。两端补全结果一致。
 */
public final class McLspCore {

    private final CommandCompleter completer;
    private final Map<String, String> docs = new HashMap<>();
    private volatile String version;

    public McLspCore(CommandIndex commands, ItemIndex items) {
        this.completer = new CommandCompleter(commands, items);
        this.version = commands.defaultVersion();
    }

    public void didOpen(String uri, String text) {
        docs.put(uri, text != null ? text : "");
    }

    public void didChange(String uri, String text) {
        docs.put(uri, text != null ? text : "");
    }

    public void didClose(String uri) {
        docs.remove(uri);
    }

    public void setVersion(String v) {
        if (v != null && !v.isEmpty()) {
            this.version = v;
        }
    }

    public String version() {
        return version;
    }

    /** 构造 completionProvider 能力（与网络 server 一致）。 */
    public static JSONObject capabilities() {
        JSONObject sync = new JSONObject().put("openClose", true).put("change", 1);
        JSONObject completion = new JSONObject()
                .put("triggerCharacters", new JSONArray().put("/").put(" ").put("[").put("=").put("@"))
                .put("resolveProvider", false);
        return new JSONObject()
                .put("textDocumentSync", sync)
                .put("completionProvider", completion);
    }

    public static JSONObject serverInfo() {
        return new JSONObject()
                .put("name", "mc-bedrock-command-lsp")
                .put("version", "0.1.0");
    }

    /** textDocument/completion 结果（JSONArray of CompletionItem，同网络 server 组装）。 */
    public JSONArray completion(String uri, int line, int character) {
        String text = docs.getOrDefault(uri, "");
        String lineText = lineText(text, line);
        if (character > lineText.length()) character = lineText.length();

        Result r = completer.complete(lineText.substring(0, character), version);
        int start = character - r.currentPrefix.length();
        if (start < 0) start = 0;

        JSONArray items = new JSONArray();
        for (Suggestion s : r.suggestions) {
            JSONObject it = new JSONObject();
            it.put("label", s.label);
            it.put("filterText", s.insertText);
            if (s.detail != null && !s.detail.isEmpty()) it.put("detail", s.detail);
            it.put("kind", kindNumber(s.kind));
            JSONObject range = new JSONObject()
                    .put("start", new JSONObject().put("line", line).put("character", start))
                    .put("end", new JSONObject().put("line", line).put("character", character));
            it.put("textEdit", new JSONObject()
                    .put("range", range)
                    .put("newText", s.insertText));
            items.put(it);
        }
        return items;
    }

    private static String lineText(String text, int line) {
        if (text.isEmpty()) return "";
        String[] lines = text.split("\n", -1);
        if (line < 0) return "";
        if (line >= lines.length) return lines[lines.length - 1];
        return lines[line];
    }

    private static int kindNumber(String kind) {
        if (kind == null) return 1;
        switch (kind) {
            case "command": return 3;
            case "value": return 12;
            case "enum": return 13;
            default: return 1;
        }
    }
}
