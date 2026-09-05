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

    /** 加载主文件后再用额外文件里的命令覆盖（额外条目以 overloads 新格式编写）。 */
    public CommandIndex(String jsonPath, String extraPath) throws java.io.IOException {
        this(JsonFiles.readObject(jsonPath));
        this.mergeOverrides(JsonFiles.readObject(extraPath));
    }

    private void mergeOverrides(JSONObject overRoot) {
        JSONObject cmds = overRoot.optJSONObject("commands");
        if (cmds == null) return;
        for (String name : cmds.keySet()) {
            JSONObject o = cmds.optJSONObject(name);
            if (o != null) commands.put(name, parseCommand(name, o));
        }
    }

    /** 用另一份索引的命令覆盖同名条目（额外语法包）。 */
    public void merge(CommandIndex over) {
        if (over != null) commands.putAll(over.commands());
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
        // 新版格式：overloads: [{versions:[...], params:[...]}, ...] —— 一个版本可有多套签名；
        // 兼容旧式 params: {version:[...]}（视为每版本一套签名）。
        Map<String, List<List<ParamDef>>> byVersion = new LinkedHashMap<>();
        JSONArray overloads = o.optJSONArray("overloads");
        if (overloads != null && overloads.length() > 0) {
            for (int i = 0; i < overloads.length(); i++) {
                JSONObject ov = overloads.optJSONObject(i);
                if (ov == null) continue;
                List<ParamDef> ps = parseParams(ov.optJSONArray("params"));
                if (ps.isEmpty()) continue;
                JSONArray vers = ov.optJSONArray("versions");
                if (vers == null || vers.length() == 0) {
                    addSignature(byVersion, "*", ps); // 全版本通用签名（兜底）
                } else {
                    for (int v = 0; v < vers.length(); v++) {
                        addSignature(byVersion, vers.optString(v), ps);
                    }
                }
            }
        } else {
            JSONObject params = o.optJSONObject("params");
            if (params != null) {
                for (String ver : params.keySet()) {
                    addSignature(byVersion, ver, parseParams(params.optJSONArray(ver)));
                }
            }
        }
        return new CommandDef(
                JsonFiles.nvl(o.optString("name")).isEmpty() ? name : o.optString("name"),
                o.optString("category", ""),
                o.optString("description_cn", ""),
                o.optString("description_en", ""),
                o.optString("url", ""),
                byVersion);
    }

    /** 追加一套签名，而不是覆盖该版本已有签名。 */
    private static void addSignature(Map<String, List<List<ParamDef>>> byVersion,
                                     String version, List<ParamDef> ps) {
        List<List<ParamDef>> sigs = byVersion.computeIfAbsent(version, k -> new ArrayList<>());
        sigs.add(ps);
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

    /**
     * 某版本可用的全部签名；无匹配版本时依次回落到 "*" 与默认版本；都没有返回空表。
     */
    public List<List<ParamDef>> signaturesFor(String command, String version) {
        CommandDef def = commands.get(command);
        if (def == null) return Collections.emptyList();
        List<List<ParamDef>> exact = def.overloadsByVersion.get(version);
        if (exact != null) return exact;
        List<List<ParamDef>> any = def.overloadsByVersion.get("*"); // 全版本签名
        if (any != null) return any;
        List<List<ParamDef>> defVer = def.overloadsByVersion.get(defaultVersion);
        if (defVer != null) return defVer;
        return Collections.emptyList();
    }

    /** 兼容旧入口：返回该版本首套签名（无则 null）。 */
    public List<ParamDef> paramsFor(String command, String version) {
        List<List<ParamDef>> sigs = signaturesFor(command, version);
        return sigs.isEmpty() ? null : sigs.get(0);
    }
}
