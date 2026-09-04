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

    /** 生成当前参数的候选。 */
    private List<Suggestion> suggestFor(ParamDef p, String prefix, String consumedItemId) {
        String lower = prefix.toLowerCase();
        List<Suggestion> out = new ArrayList<>();
        switch (p.type) {
            case "selector": {
                for (String s : List.of("@a", "@p", "@e", "@r", "@s")) {
                    if (s.toLowerCase().startsWith(lower)) {
                        out.add(new Suggestion(s, s, "选择器", "selector"));
                    }
                }
                return out;
            }
            case "item": {
                for (ItemDef it : items.items()) {
                    if (it.id.toLowerCase().startsWith(lower)) {
                        String cn = it.nameCn != null && !it.nameCn.isEmpty() ? it.nameCn : it.nameEn;
                        out.add(new Suggestion(it.id, it.id, cn, "item"));
                    }
                }
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
            case "item":
            case "block":
            case "player":
            case "entity":
            case "string":
            case "text":
            case "command":
            case "json":
            case "nbt":
            default:
                // 自由文本 / 由候选驱动：只要不包含空格即可
                return !token.contains(" ");
        }
    }

    private static final Pattern INT = Pattern.compile("[+-]?\\d+");
    private static final Pattern FLOAT = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)");
}
