package dev.mccmd.data;

import dev.mccmd.model.CommandDef;
import dev.mccmd.model.ParamDef;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 命令索引加载器（commands.json，schema v1）。 */
public final class CommandIndex {

    private final String defaultVersion;
    private final Map<String, CommandDef> commands;

    public CommandIndex(String jsonPath) throws java.io.IOException {
        this(JsonFiles.readObject(jsonPath));
    }

    public CommandIndex(JSONObject root) {
        this.defaultVersion = root.optString("defaultVersion", "");
        Map<String, CommandDef> map = new LinkedHashMap<>();
        JSONObject cmds = root.optJSONObject("commands");
        if (cmds != null) {
            for (String name : cmds.keySet()) {
                JSONObject o = cmds.optJSONObject(name);
                if (o != null) map.put(name, parseCommand(name, o));
            }
        }
        this.commands = map;
    }

    private static CommandDef parseCommand(String name, JSONObject o) {
        Map<String, List<ParamDef>> paramsByVersion = new LinkedHashMap<>();
        JSONObject params = o.optJSONObject("params");
        if (params != null) {
            for (String ver : params.keySet()) {
                paramsByVersion.put(ver, parseParams(params.optJSONArray(ver)));
            }
        }
        return new CommandDef(
                JsonFiles.nvl(o.optString("name")).isEmpty() ? name : o.optString("name"),
                o.optString("category", ""),
                o.optString("description_cn", ""),
                o.optString("description_en", ""),
                o.optString("url", ""),
                paramsByVersion);
    }

    private static List<ParamDef> parseParams(JSONArray arr) {
        if (arr == null) return Collections.emptyList();
        List<ParamDef> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p == null) continue;
            out.add(new ParamDef(
                    JsonFiles.nvl(p.optString("name")),
                    p.optString("type", "text"),
                    p.optBoolean("required", false),
                    p.optBoolean("repeat", false),
                    JsonFiles.optStringList(p, "values"),
                    p.optString("suggestFrom", null),
                    p.optString("description_cn", null)));
        }
        return out;
    }

    /** 默认版本（用于版本切换时回落）。 */
    public String defaultVersion() {
        return defaultVersion;
    }

    /** 命令全集（保序）。 */
    public Map<String, CommandDef> commands() {
        return commands;
    }

    public CommandDef command(String name) {
        return commands.get(name);
    }

    /** 某版本可用的参数；命令不存在或该版本未声明时返回 null。 */
    public List<ParamDef> paramsFor(String command, String version) {
        CommandDef def = commands.get(command);
        if (def == null) return null;
        List<ParamDef> exact = def.paramsByVersion.get(version);
        if (exact != null) return exact;
        return def.paramsByVersion.getOrDefault(defaultVersion, null);
    }
}
