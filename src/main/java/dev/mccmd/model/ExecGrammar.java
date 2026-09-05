package dev.mccmd.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * execute 子命令链语法（数据驱动）。
 *
 * <p>Bedrock execute = {@code execute (子命令 参数*)* run <命令>}：链式拼多个子命令，最终 run。
 * 每个子命令关键字对应一棵参数树：普通参数（选择器/坐标/方块/枚举…）与嵌套关键字
 * （如 {@code if block/entity/score}、{@code positioned as/over}）递归组合。
 *
 * <p>节点 JSON（kind 判别节点，type 保留给参数类型，与命令参数定义一致）：
 * <ul>
 *   <li>{"kind":"param","name":"目标","type":"entity","required":true,"values":[...]...}</li>
 *   <li>{"kind":"lit","word":"block"}</li>
 *   <li>{"kind":"seq","nodes":[...]}</li>
 *   <li>{"kind":"alt","branches":[...]}</li>
 * </ul>
 * 顶层为 clauses：子命令关键字 → 参数树（关键字由引擎按词匹配）。
 */
public final class ExecGrammar {

    /** 顶层：子命令关键字 → 关键字后要匹配的参数树（可为 null = 无参数）。 */
    public final Map<String, Node> clauses;

    public ExecGrammar(Map<String, Node> clauses) {
        this.clauses = clauses;
    }

    public abstract static class Node {
        Node() {
        }
    }

    /** 参数节点（字段对齐 ParamDef）。 */
    public static final class Param extends Node {
        public final ParamDef def;
        public Param(ParamDef def) {
            this.def = def;
        }
    }

    /** 字面量（如 positioned 后的 as/over、if 后的 block/entity）。 */
    public static final class Lit extends Node {
        public final String word;
        public Lit(String word) {
            this.word = word;
        }
    }

    /** 顺序匹配。 */
    public static final class Seq extends Node {
        public final List<Node> nodes;
        public Seq(List<Node> nodes) {
            this.nodes = nodes;
        }
    }

    /** 多选一：某分支须从当前位置完整匹配。 */
    public static final class Alt extends Node {
        public final List<Node> branches;
        public Alt(List<Node> branches) {
            this.branches = branches;
        }
    }

    /** 从 JSON 解析整棵 execute 语法。 */
    public static ExecGrammar fromJson(JSONObject grammar) {
        Map<String, Node> clauses = new LinkedHashMap<>();
        if (grammar != null) {
            JSONObject cl = grammar.optJSONObject("clauses");
            if (cl != null) {
                for (String kw : cl.keySet()) {
                    JSONObject n = cl.optJSONObject(kw);
                    clauses.put(kw, n == null ? null : nodeFromJson(n));
                }
            }
        }
        return new ExecGrammar(clauses);
    }

    private static Node nodeFromJson(JSONObject o) {
        String kind = o.optString("kind", "seq");
        switch (kind) {
            case "param":
                return new Param(new ParamDef(
                        o.optString("name", ""),
                        o.optString("type", "text"),
                        o.optBoolean("required", false),
                        o.optBoolean("repeat", false),
                        optStringList(o, "values"),
                        o.optString("suggestFrom", null),
                        o.optString("description_cn", null)));
            case "lit":
                return new Lit(o.optString("word", ""));
            case "alt": {
                List<Node> bs = new ArrayList<>();
                JSONArray arr = o.optJSONArray("branches");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        bs.add(nodeFromJson(arr.optJSONObject(i)));
                    }
                }
                return new Alt(bs);
            }
            case "seq":
            default: {
                List<Node> ns = new ArrayList<>();
                JSONArray arr = o.optJSONArray("nodes");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        ns.add(nodeFromJson(arr.optJSONObject(i)));
                    }
                }
                return new Seq(ns);
            }
        }
    }

    private static List<String> optStringList(JSONObject o, String key) {
        JSONArray a = o.optJSONArray(key);
        if (a == null) return Collections.emptyList();
        List<String> out = new ArrayList<>(a.length());
        for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }
}
