package dev.mccmd.engine;

import dev.mccmd.data.CommandIndex;
import dev.mccmd.data.ItemIndex;
import dev.mccmd.model.CommandDef;
import dev.mccmd.model.ExecGrammar;
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

    /** 单套签名的消费结果。 */
    private static final class WalkSig {
        final int idx;            // 消费后停在下个待填参数下标（==params.size() 表示已到底）
        final String itemId;      // 已消费的 item/block 参数值
        final boolean dirty;      // 消费过程中某必填参数不匹配或 token 溢出
        final List<String> errors;

        WalkSig(int idx, String itemId, boolean dirty, List<String> errors) {
            this.idx = idx;
            this.itemId = itemId;
            this.dirty = dirty;
            this.errors = errors;
        }
    }

    private Result completeParams(CommandDef def, String rest, String version) {
        List<List<ParamDef>> sigs = commands.signaturesFor(def.name, version);
        if (sigs.isEmpty()) {
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

        // 每套签名各自消费 doneTokens，选出“干净消费完全部 token”的签名作为候选。
        // 所有签名都消费不干净时，退化为消费最远（最接近真实意图）的一套并保留其报错。
        List<WalkSig> outs = new ArrayList<>();
        for (List<ParamDef> sig : sigs) {
            outs.add(walkSig(sig, doneTokens));
        }
        int chosenIdx = -1;
        for (int i = 0; i < outs.size(); i++) if (!outs.get(i).dirty) { chosenIdx = i; break; }
        if (chosenIdx < 0) {
            // 全部失败：取消费最远者；同深度取报错最少者；再取先声明者
            chosenIdx = 0;
            for (int i = 1; i < outs.size(); i++) {
                WalkSig o = outs.get(i);
                WalkSig c = outs.get(chosenIdx);
                if (o.idx > c.idx || (o.idx == c.idx && o.errors.size() < c.errors.size())) {
                    chosenIdx = i;
                }
            }
            WalkSig chosen = outs.get(chosenIdx);
            List<ParamDef> sig = sigs.get(chosenIdx);
            List<String> errs = chosen.errors.isEmpty() ? Collections.emptyList() : chosen.errors;
            if (chosen.idx >= sig.size()) {
                return new Result(Collections.emptyList(), null, errs, prefix);
            }
            ParamDef cur = sig.get(chosen.idx);
            return new Result(suggestFor(cur, prefix, chosen.itemId), cur.name, errs, prefix);
        }

        // 多个签名均可：合并每个签名“当前槽位”的建议（判别式补全，如 effect clear 与效果并行）。
        List<Suggestion> merged = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String paramName = null;
        for (int i = 0; i < sigs.size(); i++) {
            WalkSig o = outs.get(i);
            if (o.dirty) continue;
            List<ParamDef> sig = sigs.get(i);
            ParamDef cur = (o.idx < sig.size()) ? sig.get(o.idx) : null;
            String item = o.itemId;
            if (cur == null) continue;
            List<Suggestion> cand = suggestFor(cur, prefix, item);
            // 可选参数空值（未输入、无候选）时跳到下一个能出候选的可选参数（如 setblock 省略 data → 模式）
            if (cand.isEmpty() && prefix.isEmpty() && !cur.required) {
                for (int j = o.idx + 1; j < sig.size(); j++) {
                    if (sig.get(j).required) break; // 不能跳过必填
                    List<Suggestion> next = suggestFor(sig.get(j), prefix, item);
                    if (!next.isEmpty()) {
                        cur = sig.get(j);
                        cand = next;
                        break;
                    }
                }
            }
            for (Suggestion s : cand) {
                if (seen.add(s.insertText)) merged.add(s);
            }
            if (paramName == null && cur != null) paramName = cur.name;
        }
        if (merged.isEmpty() && paramName == null) {
            return new Result(Collections.emptyList(), null, Collections.emptyList(), prefix);
        }
        return new Result(merged, paramName, Collections.emptyList(), prefix);
    }

    /**
     * 用一套签名顺序消费已完整输入的 token。
     * 可选参数不匹配时跳过；必填参数不匹配或 token 溢出 → 标记 dirty 并停止继续消费。
     */
    private static WalkSig walkSig(List<ParamDef> params, List<String> done) {
        int idx = 0;
        String item = null;
        List<String> errors = new ArrayList<>();
        for (String tok : done) {
            while (idx < params.size() && !paramAccepts(params.get(idx), tok)) {
                if (params.get(idx).required) {
                    errors.add("参数「" + params.get(idx).name + "」不合法：" + tok);
                    return new WalkSig(idx, item, true, errors);
                }
                idx++; // 可选参数未填，跳到下一个
            }
            if (idx >= params.size()) {
                errors.add("多余参数：" + tok);
                return new WalkSig(idx, item, true, errors);
            }
            ParamDef cur = params.get(idx);
            if (cur.type.equals("item") && item == null) item = tok;
            int step = 1;
            if (cur.type.equals("coordinate")) {
                // 紧凑相对坐标（~13~13~13 / ~~~）按含几个坐标一次推进几步
                int f = fusedCoords(tok);
                if (f > 1) step = f;
            }
            idx += step;
            if (idx > params.size()) idx = params.size();
        }
        return new WalkSig(idx, item, false, errors);
    }

    /** 已完成 token 是否匹配某参数。enum 型严格校验 values（分支判别）；string/gamemode 等宽松。 */
    private static boolean paramAccepts(ParamDef p, String token) {
        if (token.isEmpty()) return false;
        if ("enum".equals(p.type) && p.values != null && !p.values.isEmpty()) {
            for (String v : p.values) {
                if (v.equalsIgnoreCase(token)) return true;
            }
            return false;
        }
        return accepts(p, token);
    }

    /** 统计无空格坐标串里包含的坐标个数（~13~13~13=3，~~~=3，~5=1，5=1）；非法返回 0。 */
    private static int fusedCoords(String token) {
        int n = 0;
        int i = 0;
        int L = token.length();
        while (i < L) {
            boolean marker = false;
            char c = token.charAt(i);
            if (c == '~' || c == '^') {
                marker = true;
                i++;
            } else if (c == '+' || c == '-' || c == '.' || Character.isDigit(c)) {
                // 绝对坐标，无前缀
            } else {
                return 0;
            }
            if (i < L && (token.charAt(i) == '+' || token.charAt(i) == '-')) {
                i++;
            }
            boolean dig = false;
            while (i < L) {
                char d = token.charAt(i);
                if (Character.isDigit(d) || d == '.') {
                    dig = true;
                    i++;
                } else {
                    break;
                }
            }
            if (!marker && !dig) {
                return 0; // 如 "-" / "." 等无意义输入
            }
            n++;
        }
        return n;
    }

    /** execute 链解析内部状态。null = 停在“子命令槽”。 */
    private abstract static class ExecNode {
    }

    /** 停在参数树某节点：node 待消费，follow 为同分支后续（链式）。 */
    private static final class TreeState extends ExecNode {
        final ExecGrammar.Node node;
        final ExecNode follow;
        TreeState(ExecGrammar.Node node, ExecNode follow) {
            this.node = node;
            this.follow = follow;
        }
    }

    /**
     * execute 链：语法树驱动。链式拼多个子命令，run 后递归给任意命令。
     * 解析方式：把已输入 token 逐个喂进状态机；停在“子命令槽”则补子命令关键字，
     * 停在“某子命令参数树内”则补该参数/字面量。多状态并存时合并建议。
     */
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
        ExecGrammar grammar = commands.execGrammarFor(version);
        if (grammar == null || grammar.clauses.isEmpty()) {
            return legacyExecuteSuggest(done, prefix);
        }

        // run 之后的嵌套命令直接递归（execute 链只解析到 run 前）
        for (int i = 0; i < done.size(); i++) {
            if ("run".equalsIgnoreCase(done.get(i))) {
                StringBuilder nested = new StringBuilder();
                for (int j = i + 1; j < done.size(); j++) {
                    if (nested.length() > 0) nested.append(' ');
                    nested.append(done.get(j));
                }
                if (trailing || !prefix.isEmpty()) {
                    if (nested.length() > 0) nested.append(' ');
                    nested.append(prefix);
                }
                if (trailing) nested.append(' ');
                return complete(nested.toString(), version);
            }
        }

        // 状态机：逐个喂 done token
        List<ExecNode> states = new ArrayList<>();
        states.add(null); // 初始在子命令槽
        for (String tok : done) {
            List<ExecNode> next = new ArrayList<>();
            for (ExecNode st : states) {
                stepExec(st, tok, grammar, next);
            }
            states = next;
            if (states.isEmpty()) break;
        }
        if (states.isEmpty()) {
            return legacyExecuteSuggest(done, prefix);
        }

        java.util.Set<String> seen = new java.util.HashSet<>();
        List<Suggestion> sug = new ArrayList<>();
        String slot = null;
        for (ExecNode st : states) {
            if (st == null) {
                // 停在子命令槽：建议下一个子命令关键字
                if (slot == null) slot = "子命令";
                for (String kw : grammar.clauses.keySet()) {
                    if (!kw.toLowerCase().startsWith(prefix.toLowerCase())) continue;
                    String detail = "run".equalsIgnoreCase(kw)
                            ? "以 run 执行命令" : "execute 子命令";
                    if (seen.add(kw)) sug.add(new Suggestion(kw, kw, detail, "command"));
                }
            } else {
                TreeState ts = (TreeState) st;
                for (NodeExpect x : expectOf(ts.node)) {
                    if (x.words != null) {
                        if (slot == null) slot = "子命令参数";
                        for (String w : x.words) {
                            if (w.toLowerCase().startsWith(prefix.toLowerCase())
                                    && seen.add(w)) {
                                sug.add(new Suggestion(w, w, x.detail, "command"));
                            }
                        }
                    } else if (x.param != null) {
                        if (slot == null) slot = x.param.def.name;
                        for (Suggestion s : suggestFor(x.param.def, prefix, null)) {
                            if (seen.add(s.insertText)) sug.add(s);
                        }
                    }
                }
            }
        }
        return new Result(sug, slot, Collections.emptyList(), prefix);
    }

    /** 状态机前进一个 token。 */
    private void stepExec(ExecNode state, String tok, ExecGrammar grammar, List<ExecNode> out) {
        if (state == null) {
            // 子命令槽：token 匹配某子命令关键字 → 进入其参数树
            for (String kw : grammar.clauses.keySet()) {
                if (!kw.equalsIgnoreCase(tok)) continue;
                ExecGrammar.Node tree = grammar.clauses.get(kw);
                if (tree == null) {
                    out.add(null); // 无参数子命令 → 消费后回子命令槽
                } else {
                    out.add(new TreeState(tree, null));
                }
            }
            return;
        }
        TreeState ts = (TreeState) state;
        consumeNode(ts.node, ts.follow, tok, grammar, out);
    }

    /**
     * 让一个节点消费 token。
     * 成功时：若 follow==null 说明本分支参数已结束 → 回到子命令槽(null)；
     * 否则继续 follow 链。Param 不匹配且必填、Lit 不匹配 → 该路径无输出（分支失败）。
     */
    private void consumeNode(ExecGrammar.Node node, ExecNode follow, String tok,
                             ExecGrammar grammar, List<ExecNode> out) {
        if (node instanceof ExecGrammar.Param p) {
            if (paramAccepts(p.def, tok)) {
                if (follow == null) out.add(null);
                else out.add(follow);
            }
        } else if (node instanceof ExecGrammar.Lit lit) {
            if (lit.word.equalsIgnoreCase(tok)) {
                if (follow == null) out.add(null);
                else out.add(follow);
            }
        } else if (node instanceof ExecGrammar.Seq seq) {
            if (seq.nodes.isEmpty()) {
                if (follow == null) out.add(null);
                else out.add(follow);
                return;
            }
            ExecGrammar.Node first = seq.nodes.get(0);
            List<ExecGrammar.Node> rest = seq.nodes.subList(1, seq.nodes.size());
            ExecNode restFollow = follow;
            if (!rest.isEmpty()) {
                restFollow = rest.size() == 1
                        ? new TreeState(rest.get(0), follow)
                        : new TreeState(new ExecGrammar.Seq(rest), follow);
            }
            consumeNode(first, restFollow, tok, grammar, out);
        } else if (node instanceof ExecGrammar.Alt alt) {
            for (ExecGrammar.Node b : alt.branches) {
                consumeNode(b, follow, tok, grammar, out);
            }
        }
    }

    /** 沿 follow 链与 seq/alt 找到最左侧待填的 Param 或 Lit（用于给建议）。 */

    /** 一棵树最左侧该补的内容（用于给建议）。 */
    private static NodeExpect[] expectOf(ExecGrammar.Node node) {
        if (node instanceof ExecGrammar.Param p) {
            return new NodeExpect[]{new NodeExpect(null, p)};
        }
        if (node instanceof ExecGrammar.Lit lit) {
            return new NodeExpect[]{new NodeExpect(java.util.List.of(lit.word), lit.word)};
        }
        if (node instanceof ExecGrammar.Seq seq) {
            if (seq.nodes.isEmpty()) return new NodeExpect[0];
            return expectOf(seq.nodes.get(0));
        }
        if (node instanceof ExecGrammar.Alt alt) {
            java.util.List<NodeExpect> out = new ArrayList<>();
            for (ExecGrammar.Node b : alt.branches) {
                for (NodeExpect x : expectOf(b)) out.add(x);
            }
            return out.toArray(new NodeExpect[0]);
        }
        return new NodeExpect[0];
    }

    /** 待填项：候选词列表 或 一个待填参数。 */
    private static final class NodeExpect {
        final java.util.List<String> words;
        final ExecGrammar.Param param;
        final String detail;
        NodeExpect(java.util.List<String> words, String detail) {
            this.words = words;
            this.param = null;
            this.detail = detail;
        }
        NodeExpect(String unused, ExecGrammar.Param param) {
            this.words = null;
            this.param = param;
            this.detail = param.def.name;
        }
    }

    /** 无 execute 语法时的退化建议（子命令关键字列表）。 */
    private Result legacyExecuteSuggest(List<String> done, String prefix) {
        List<Suggestion> sug = new ArrayList<>();
        for (String s : EXEC_SUBCOMMANDS) {
            if (s.toLowerCase().startsWith(prefix.toLowerCase())) {
                sug.add(new Suggestion(s, s, "execute 子命令", "command"));
            }
        }
        return new Result(sug, "子命令", Collections.emptyList(), prefix);
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
            int eq = inside.indexOf('=');
            if (eq >= 0) {
                // 正在输入某键的值：gamemode=/type= 等给候选
                String key = inside.substring(0, eq).trim();
                String valPrefix = inside.substring(eq + 1);
                for (String cand : selectorValueCandidates(key)) {
                    String full = head + key + "=" + cand;
                    if (full.toLowerCase().startsWith(lower)) {
                        out.add(new Suggestion(full, cand, "选择器值", "value"));
                    }
                }
                if (valPrefix.length() > 0) {
                    out.add(new Suggestion(head + "]", "]", "结束参数", "selector"));
                }
                return out;
            }
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

    /** 常见选择器键的取值候选（type=/gamemode=/scores=...）。 */
    private static java.util.List<String> selectorValueCandidates(String key) {
        switch (key.toLowerCase()) {
            case "type":
            case "family":
                return ENTITY_IDS;
            case "gamemode":
            case "m":
                return java.util.List.of("survival", "creative", "adventure",
                        "0", "1", "2");
            case "scores":
                return java.util.List.of("{");
            case "tag":
            case "name":
                return java.util.List.of();
            default:
                return java.util.List.of(); // 数值型自由输入
        }
    }

    private static final java.util.List<String> ENTITY_IDS =
            java.util.List.of("armor_stand", "arrow", "chicken", "cow", "pig", "sheep",
                    "zombie", "skeleton", "creeper", "enderman", "spider", "wolf",
                    "villager_v2", "wither_skeleton", "stray", "husk", "drowned",
                    "phantom", "shulker", "guardian", "elder_guardian", "slime",
                    "magma_cube", "blaze", "ghast", "wither", "ender_dragon", "item",
                    "tnt", "minecart", "boat", "item_frame", "allay", "goat", "warden");

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
                return fusedCoords(token) >= 1;
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
