package dev.mccmd.engine;

import dev.mccmd.data.CommandIndex;
import dev.mccmd.data.ItemIndex;
import dev.mccmd.model.CommandDef;
import dev.mccmd.model.ItemDef;
import dev.mccmd.model.ParamDef;
import dev.mccmd.model.SpecialValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 基岩版命令补全引擎（Milestone 2，纯 JVM）。
 *
 * <p>输入一行命令（含可选前导 / 与光标处部分输入），按指定版本返回：
 * 当前应补的参数候选、命中到的参数/错误信息。候选可来自固定枚举、物品索引、
 * 或上一参数(物品)的 {@code special_values} 数据值。
 *
 * <p>局限（后续里程碑收口）：按“顺序参数”模型匹配，未做多套签名回溯；
 * position/coordinate 等多 token 类型按单 token 处理；建议区只服务光标前文本。
 */
public final class CommandCompleter {

    private final CommandIndex commands;
    private final ItemIndex items;

    public CommandCompleter(CommandIndex commands, ItemIndex items) {
        this.commands = commands;
        this.items = items;
    }

    /** 补全结果。 */
    public static final class Result {
        public final List<Suggestion> suggestions;
        /** 当前正匹配的参数名（可能为 null）。 */
        public final String currentParam;
        /** 解析告警/错误（如某必填参数不合法）。 */
        public final List<String> errors;
        /** 本次使用的输入前缀（供 LSP 计算替换范围，可能为 ""）。 */
        public final String currentPrefix;

        Result(List<Suggestion> suggestions, String currentParam, List<String> errors) {
            this(suggestions, currentParam, errors, null);
        }

        Result(List<Suggestion> suggestions, String currentParam, List<String> errors,
               String currentPrefix) {
            this.suggestions = suggestions;
            this.currentParam = currentParam;
            this.errors = errors;
            this.currentPrefix = currentPrefix != null ? currentPrefix : "";
        }
    }

    /** 单条候选。 */
    public static final class Suggestion {
        /** 要插入的文本（覆盖当前词）。 */
        public final String insertText;
        /** 展示标签（可含描述，如 "0 - 白色羊毛"）。 */
        public final String label;
        public final String detail;
        /** 类别：command/item/selector/value/enum/literal/other。 */
        public final String kind;

        public Suggestion(String insertText, String label, String detail, String kind) {
            this.insertText = insertText;
            this.label = label != null ? label : insertText;
            this.detail = detail;
            this.kind = kind;
        }
    }

    /** 主入口。line = 光标前文本（可为 null/空）。注意保留行尾空格以区分“新参数”。 */
    public Result complete(String line, String version) {
        String text = line != null ? line : "";
        String cmd = null;
        String rest = "";
        String head = text;
        if (text.startsWith("/")) head = text.substring(1);
        int sp = head.indexOf(' ');
        if (sp < 0) {
            cmd = head;
        } else {
            cmd = head.substring(0, sp);
            rest = head.substring(sp + 1);
        }
        if (cmd == null || cmd.isEmpty()) {
            return new Result(commandCandidates("", version), null, Collections.emptyList(), "");
        }

        CommandDef def = commands.command(cmd);
        if (def == null) {
            // 命令名未输完 → 命令候选；否则报未知命令
            List<Suggestion> cs = commandCandidates(cmd, version);
            if (!cs.isEmpty() && cs.get(0).insertText.startsWith(cmd)) {
                return new Result(cs, null, Collections.emptyList(), cmd);
            }
            return new Result(Collections.emptyList(), null,
                    List.of("未知命令：" + cmd), cmd);
        }

        if (sp < 0) {
            // 命令名刚好输完（无参数输入）
            return new Result(Collections.emptyList(), null, Collections.emptyList(), cmd);
        }
        if ("execute".equals(def.name)) {
            return completeExecute(def, rest, version);
        }
        return completeParams(def, rest, version);
    }

    private Result completeParams(CommandDef def, String rest, String version) {
        List<ParamDef> params = commands.paramsFor(def.name, version);
        if (params == null || params.isEmpty()) {
            return new Result(Collections.emptyList(), null,
                    List.of("命令在版本 " + version + " 无参数定义"), "");
        }

        // 拆分已完整输入段与正在输入段（光标处）
        boolean trailingSpace = rest.endsWith(" ");
        String body = trailingSpace ? rest.substring(0, rest.length() - 1) : rest;
        String prefix = "";
        List<String> doneTokens = new ArrayList<>();
        if (!body.isEmpty()) {
            // 最后一个 token 视为“正在输入”的部分（除非其后已有空格）
            List<String> all = splitTokens(body);
            if (trailingSpace) {
                doneTokens = all;
            } else {
                if (all.isEmpty()) {
                    prefix = "";
                } else {
                    prefix = all.get(all.size() - 1);
                    doneTokens = all.subList(0, all.size() - 1);
                }
            }
        }

        // 顺序消费已输入 token，记录是否出错
        List<String> errors = new ArrayList<>();
        String consumedItemId = null;
        int idx = 0;
        for (String tok : doneTokens) {
            while (idx < params.size() && !accepts(params.get(idx), tok)) {
                if (params.get(idx).required) {
                    errors.add("参数「" + params.get(idx).name + "」不合法：" + tok);
                    break;
                }
                idx++; // 可选参数未填，跳到下一个
            }
            if (idx >= params.size()) break;
            if (consumedItemId == null && params.get(idx).type.equals("item")) {
                consumedItemId = tok;
            }
            idx++;
        }

        if (idx >= params.size()) {
            return new Result(Collections.emptyList(), null,
                    errors.isEmpty() ? Collections.emptyList() : errors, prefix);
        }
        ParamDef cur = params.get(idx);
        List<Suggestion> sug = suggestFor(cur, prefix, consumedItemId);
        return new Result(sug, cur.name,
                errors.isEmpty() ? Collections.emptyList() : errors, prefix);
    }

    /** execute 链：as/at/... 组合后 run <命令>；run 之后递归交给任意命令补全。 */
    private Result completeExecute(CommandDef def, String rest, String version) {
        boolean trailing = rest.endsWith(" ");
        String body = trailing ? rest.substring(0, rest.length() - 1) : rest;
        List<String> done = new ArrayList<>();
        String prefix = "";
        if (!body.isEmpty()) {
            List<String> all = splitTokens(body);
            if (trailing) {
                done = all;
            } else {
                prefix = all.get(all.size() - 1);
                done = all.subList(0, all.size() - 1);
            }
        }

        // 定位 run（在已完整输入的 token 中）
        int runIdx = -1;
        for (int i = 0; i < done.size(); i++) {
            if ("run".equals(done.get(i))) {
                runIdx = i;
                break;
            }
        }
        if (runIdx >= 0) {
            // run 之后的内容交给嵌套命令补全
            StringBuilder nested = new StringBuilder();
            for (int i = runIdx + 1; i < done.size(); i++) {
                if (nested.length() > 0) nested.append(' ');
                nested.append(done.get(i));
            }
            if (trailing || !prefix.isEmpty()) {
                if (nested.length() > 0) nested.append(' ');
                nested.append(prefix);
                if (trailing) nested.append(' ');
            }
            return complete(nested.toString(), version);
        }

        // 尚未输入 run：判断当前停在“子命令槽”还是“某子命令的参数槽”
        String lastSub = null;
        for (String t : done) {
            if (EXEC_SUBCOMMANDS.contains(t)) {
                lastSub = t;
            } else {
                lastSub = null; // 这个 token 是上一个子命令的参数
            }
        }
        if ("run".equals(lastSub)) lastSub = null;

        List<Suggestion> sug = new ArrayList<>();
        String slotName;
        if (lastSub == null) {
            // 期待下一个子命令
            slotName = "子命令";
            for (String s : EXEC_SUBCOMMANDS) {
                if (s.toLowerCase().startsWith(prefix.toLowerCase())) {
                    sug.add(new Suggestion(s, s, "execute 子命令", "command"));
                }
            }
        } else {
            // 期待该子命令的参数（也允许继续输入子命令，容错）
            slotName = lastSub;
            if (EXEC_SELECTOR_SUBS.contains(lastSub)) {
                sug.addAll(selectorSuggestions(prefix));
            }
            for (String s : EXEC_SUBCOMMANDS) {
                if (s.toLowerCase().startsWith(prefix.toLowerCase())) {
                    sug.add(new Suggestion(s, s, "execute 子命令", "command"));
                }
            }
        }
        return new Result(sug, slotName, Collections.emptyList(), prefix);
    }

    /** 生成当前参数的候选。 */
    private List<Suggestion> suggestFor(ParamDef p, String prefix, String consumedItemId) {
        String lower = prefix.toLowerCase();
        List<Suggestion> out = new ArrayList<>();
        switch (p.type) {
            case "selector":
            case "player":
            case "entity": {
                out.addAll(selectorSuggestions(prefix));
                return out;
            }
            case "item": {
                out.addAll(itemSuggestions(prefix, false));
                return out;
            }
            case "block": {
                out.addAll(itemSuggestions(prefix, true));
                return out;
            }
            default:
                break;
        }
        // 固定枚举候选
        if (p.values != null && !p.values.isEmpty()) {
            for (String v : p.values) {
                if (v.toLowerCase().startsWith(lower)) {
                    out.add(new Suggestion(v, v, p.descriptionCn, "enum"));
                }
            }
            return out;
        }
        // 数据值：来自上一 item 参数的 special_values。
        // 显式 suggestFrom="special_values"，或（未声明时启发式）参数名含“数据值/data/damage”
        boolean isSpecialValues = "special_values".equals(p.suggestFrom)
                || (p.suggestFrom == null && p.values == null
                    && isDataTypeName(p.name));
        if (isSpecialValues && consumedItemId != null) {
            ItemDef it = items.byId(consumedItemId);
            if (it != null) {
                for (SpecialValue sv : it.specialValues) {
                    String label = sv.nameCn != null && !sv.nameCn.isEmpty()
                            ? (sv.value + " - " + sv.nameCn) : String.valueOf(sv.value);
                    if (label.toLowerCase().startsWith(lower)) {
                        out.add(new Suggestion(String.valueOf(sv.value), label,
                                sv.nameEn, "value"));
                    }
                }
                return out;
            }
        }
        return out; // int/float/string/json 等：自由输入，无静态候选
    }

    /** 物品/方块候选：id 前缀或中/英文名包含；onlyBlock=true 仅方块。 */
    private List<Suggestion> itemSuggestions(String prefix, boolean onlyBlock) {
        String lower = prefix.toLowerCase();
        List<Suggestion> out = new ArrayList<>();
        for (ItemDef it : items.items()) {
            if (onlyBlock && !it.isBlock) continue;
            boolean hit = it.id.toLowerCase().startsWith(lower);
            if (!hit && lower.length() > 0) {
                String cn = it.nameCn != null ? it.nameCn.toLowerCase() : "";
                String en = it.nameEn != null ? it.nameEn.toLowerCase() : "";
                hit = cn.contains(lower) || en.contains(lower);
            }
            if (!hit) continue;
            String cn = it.nameCn != null && !it.nameCn.isEmpty() ? it.nameCn : it.nameEn;
            out.add(new Suggestion(it.id, it.id, cn, "item"));
        }
        return out;
    }

    /** 选择器目标补全：@a..@s，进入 [ 后补参数键(name=/type=...)并可结束 ]。 */
    private List<Suggestion> selectorSuggestions(String prefix) {
        String lower = prefix.toLowerCase();
        List<Suggestion> out = new ArrayList<>();
        int bracket = prefix.indexOf('[');
        if (bracket >= 0) {
            String head = prefix.substring(0, bracket + 1);
            String inside = prefix.substring(bracket + 1);
            for (String key : SELECTOR_KEYS) {
                String full = head + key;
                if (full.toLowerCase().startsWith(lower)) {
                    out.add(new Suggestion(full, key, "选择器参数", "selector"));
                }
            }
            if (!inside.isEmpty()) {
                out.add(new Suggestion(head + "]", "]", "结束参数", "selector"));
            }
            return out;
        }
        for (String s : List.of("@a", "@p", "@e", "@r", "@s")) {
            if (s.toLowerCase().startsWith(lower)) {
                out.add(new Suggestion(s, s, "选择器", "selector"));
            }
        }
        return out;
    }

    private static boolean isDataTypeName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.equals("data") || n.equals("damage")
                || n.contains("数据值") || n.contains("特殊值");
    }

    private List<Suggestion> commandCandidates(String prefix, String version) {
        String lower = prefix.toLowerCase();
        List<Suggestion> out = new ArrayList<>();
        for (CommandDef def : commands.commands().values()) {
            if (!def.name.toLowerCase().startsWith(lower)) continue;
            if (commands.paramsFor(def.name, version) == null) continue;
            String cn = def.descriptionCn != null && !def.descriptionCn.isEmpty()
                    ? def.descriptionCn : def.descriptionEn;
            out.add(new Suggestion(def.name, def.name, cn, "command"));
        }
        return out;
    }

    private static List<String> splitTokens(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.split(" ")) {
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 判断已完整输入的 token 是否匹配该参数类型。 */
    private static boolean accepts(ParamDef p, String token) {
        if (token.isEmpty()) return false;
        switch (p.type) {
            case "int":
                return INT.matcher(token).matches();
            case "float":
                return FLOAT.matcher(token).matches();
            case "coordinate":
                return COORD.matcher(token).matches();
            case "item":
            case "block":
            case "player":
            case "entity":
            case "string":
            case "text":
            case "command":
            case "json":
            case "nbt":
            case "effect":
            default:
                // 自由文本 / 由候选驱动：只要不包含空格即可
                return !token.contains(" ");
        }
    }

    private static final Pattern INT = Pattern.compile("[+-]?\\d+");
    private static final Pattern FLOAT = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)");
    private static final Pattern COORD =
            Pattern.compile("[~^]?([+-]?(\\d+\\.?\\d*|\\.\\d+))?");

    // ---- execute 链（Bedrock）----
    private static final List<String> EXEC_SUBCOMMANDS =
            List.of("as", "at", "positioned", "rotated", "facing", "anchored", "align",
                    "if", "unless", "store", "run");
    /** 需要紧跟一个实体/选择器参数的 execute 子命令。 */
    private static final List<String> EXEC_SELECTOR_SUBS = List.of("as", "at");
    /** 选择器内部参数键（name=,type=,tag=...）。 */
    private static final List<String> SELECTOR_KEYS = List.of(
            "name=", "type=", "tag=", "family=", "x=", "y=", "z=",
            "dx=", "dy=", "dz=", "r=", "rm=", "l=", "lm=", "c=", "m=",
            "scores=", "gamemode=");
}
