package dev.mccmd.lsp;

import dev.mccmd.data.CommandIndex;
import dev.mccmd.data.ItemIndex;
import dev.mccmd.engine.CommandCompleter;
import dev.mccmd.engine.CommandCompleter.Result;
import dev.mccmd.engine.CommandCompleter.Suggestion;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * MC 基岩版命令 LSP Server（stdio，LSP 协议子集）。
 *
 * <p>能力：full 文档同步（change=1）、completion（无 resolve）。
 * 文档按行取“光标前文本”交给 {@link CommandCompleter}，按版本返回候选；
 * 版本可通过 initialize 参数或 workspace/didChangeConfiguration 的 settings.mcVersion 切换。
 *
 * <p>协议：Content-Length 分帧 JSON-RPC 2.0。供 editor（如 haisa）以 stdio 启动对接。
 */
public final class McCommandLspServer {

    private final CommandIndex commands;
    private final ItemIndex items;
    private final CommandCompleter completer;

    private final Map<String, String> docs = new HashMap<>();
    private volatile String version;

    private InputStream in;
    private OutputStream out;

    public McCommandLspServer(String commandsPath, String itemsPath) throws IOException {
        this(new CommandIndex(commandsPath), new ItemIndex(itemsPath));
    }

    /** 共享已加载数据，绑定到指定流（stdin/stdout 或 TCP socket）。 */
    McCommandLspServer(CommandIndex commands, ItemIndex items,
                       InputStream in, OutputStream out) {
        this.commands = commands;
        this.items = items;
        this.completer = new CommandCompleter(commands, items);
        this.version = commands.defaultVersion();
        this.in = in;
        this.out = out;
    }

    McCommandLspServer(CommandIndex commands, ItemIndex items) {
        this(commands, items, new BufferedInputStream(System.in), System.out);
    }

    public static void main(String[] args) throws Exception {
        // 用法:
        //   stdio: mc-command-lsp <commands.json> <items.json>
        //   tcp:   mc-command-lsp --tcp <port> <commands.json> <items.json>
        if (args.length >= 1 && "--tcp".equals(args[0])) {
            int port = Integer.parseInt(args[1]);
            String cmds = args[2];
            String items = args[3];
            CommandIndex c = new CommandIndex(cmds);
            ItemIndex i = new ItemIndex(items);
            try (java.net.ServerSocket ss = new java.net.ServerSocket(port)) {
                System.err.println("mc-command-lsp listening on :" + port);
                while (true) {
                    java.net.Socket s = ss.accept();
                    s.setTcpNoDelay(true);
                    McCommandLspServer srv = new McCommandLspServer(
                            c, i, new BufferedInputStream(s.getInputStream()), s.getOutputStream());
                    Thread t = new Thread(() -> {
                        try {
                            srv.loop();
                        } catch (Exception e) {
                            // 单连接异常不影响其它连接
                        } finally {
                            try { s.close(); } catch (java.io.IOException ignored) {}
                        }
                    }, "mc-lsp-conn");
                    t.setDaemon(true);
                    t.start();
                }
            }
        } else {
            String cmds = args.length > 0 ? args[0] : "data/commands.json";
            String items = args.length > 1 ? args[1] : "data/items.json";
            new McCommandLspServer(cmds, items).loop();
        }
    }

    /** 读取/处理消息直至 EOF 或收到 exit。 */
    public void loop() throws IOException {
        boolean exit = false;
        while (!exit) {
            String raw = readFrame();
            if (raw == null) break; // EOF
            JSONObject msg = new JSONObject(raw);
            exit = dispatch(msg);
        }
        out.flush();
    }

    private boolean dispatch(JSONObject msg) {
        String method = msg.optString("method", "");
        boolean hasId = msg.has("id");
        switch (method) {
            case "initialize":
                reply(msg.opt("id"), initializeResult());
                return false;
            case "initialized":
                return false;
            case "textDocument/didOpen": {
                JSONObject td = msg.optJSONObject("params").optJSONObject("textDocument");
                if (td != null) {
                    docs.put(td.optString("uri"), td.optString("text", ""));
                }
                return false;
            }
            case "textDocument/didChange": {
                JSONObject p = msg.optJSONObject("params");
                String uri = p.optJSONObject("textDocument").optString("uri");
                JSONArray changes = p.optJSONArray("contentChanges");
                if (changes != null && changes.length() > 0) {
                    // change=1（full）：取最后一个 contentChanges.text
                    JSONObject last = changes.optJSONObject(changes.length() - 1);
                    docs.put(uri, last != null ? last.optString("text", "") : "");
                }
                return false;
            }
            case "textDocument/didClose": {
                String uri = msg.optJSONObject("params").optJSONObject("textDocument").optString("uri");
                docs.remove(uri);
                return false;
            }
            case "workspace/didChangeConfiguration": {
                JSONObject settings = msg.optJSONObject("params").optJSONObject("settings");
                applyVersion(settings != null ? settings.optString("mcVersion", "") : "");
                return false;
            }
            case "textDocument/completion": {
                if (hasId) {
                    reply(msg.opt("id"), completionResult(msg.optJSONObject("params")));
                }
                return false;
            }
            case "shutdown":
                if (hasId) reply(msg.opt("id"), JSONObject.NULL);
                return false;
            case "exit":
                return true;
            default:
                if (hasId) reply(msg.opt("id"), JSONObject.NULL); // 未知请求回 null
                return false;
        }
    }

    private void applyVersion(String v) {
        if (v != null && !v.isEmpty()) {
            this.version = v;
        }
    }

    private JSONObject initializeResult() {
        JSONObject sync = new JSONObject().put("openClose", true).put("change", 1);
        JSONObject completion = new JSONObject()
                .put("triggerCharacters", new JSONArray().put("/").put(" ").put("[").put("="))
                .put("resolveProvider", false);
        JSONObject caps = new JSONObject()
                .put("textDocumentSync", sync)
                .put("completionProvider", completion);
        return new JSONObject()
                .put("capabilities", caps)
                .put("serverInfo", new JSONObject()
                        .put("name", "mc-bedrock-command-lsp")
                        .put("version", "0.1.0"));
    }

    private JSONObject completionResult(JSONObject params) {
        JSONObject td = params.optJSONObject("textDocument");
        String uri = td != null ? td.optString("uri") : "";
        JSONObject pos = params.optJSONObject("position");
        int line = pos != null ? pos.optInt("line", 0) : 0;
        int character = pos != null ? pos.optInt("character", 0) : 0;

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
        return new JSONObject()
                .put("isIncomplete", false)
                .put("items", items);
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
            case "command": return 3;   // Function
            case "selector": return 1;  // Text
            case "item": return 1;
            case "value": return 12;    // Value
            case "enum": return 13;     // Enum
            default: return 1;
        }
    }

    // ---- framing ----

    private String readFrame() throws IOException {
        int contentLength = -1;
        while (true) {
            String header = readLine();
            if (header == null) return null;
            if (header.isEmpty()) break;
            String lower = header.toLowerCase();
            if (lower.startsWith("content-length:")) {
                contentLength = Integer.parseInt(header.substring("content-length:".length()).trim());
            }
        }
        if (contentLength < 0) return null;
        byte[] body = new byte[contentLength];
        int total = 0;
        while (total < contentLength) {
            int n = in.read(body, total, contentLength - total);
            if (n < 0) return null;
            total += n;
        }
        return new String(body, 0, contentLength, StandardCharsets.UTF_8);
    }

    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return c == -1 && sb.length() == 0 ? null : sb.toString();
    }

    private synchronized void reply(Object id, Object result) {
        JSONObject msg = new JSONObject();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("result", result == null ? JSONObject.NULL : result);
        write(msg);
    }

    private void write(JSONObject msg) {
        byte[] data = msg.toString().getBytes(StandardCharsets.UTF_8);
        String head = "Content-Length: " + data.length + "\r\n\r\n";
        try {
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(data);
            out.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
