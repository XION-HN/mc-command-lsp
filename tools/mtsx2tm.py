#!/usr/bin/env python3
"""mtsx -> tmLanguage 半自动转换（叶子级）。

解析 MT 语法文件里形如 `{match: /.../ 0: "Style"}` 的简单规则，
跳过含 include(...)/group/start-end/§ 的复杂树，翻译为 TextMate pattern。
用法: python3 tools/mtsx2tm.py <in.mtsx> <out.tmLanguage.json>
"""
import json, re, sys

STYLE_SCOPE = {
    "Number": "constant.numeric.mcfunction",
    "String": "string.quoted.mcfunction",
    "Escape": "constant.character.escape.mcfunction",
    "Command": "entity.name.function.command.mcfunction",
    "Sub": "keyword.control.subcommand.mcfunction",
    "ID": "variable.other.id.mcfunction",
    "Sele": "variable.other.selector.mcfunction",
    "SeleSub": "variable.parameter.selector.mcfunction",
    "Opt": "support.type.option.mcfunction",
    "Json-Sym": "keyword.operator.json.mcfunction",
    "Json-Key": "support.type.property-name.json.mcfunction",
    "Json-String": "string.quoted.json.mcfunction",
    "Json-Number": "constant.numeric.json.mcfunction",
    "Json-Enum": "entity.name.tag.json.mcfunction",
    "Error": "invalid.illegal.mcfunction",
    "Ignore": "comment.ignore.mcfunction",
}

LEAF = re.compile(r"\{match:/(.*?)/\s*\d+:\s*\"(\w+)\"", re.S)


def main():
    src = sys.argv[1]
    out = sys.argv[2]
    text = open(src, encoding="utf-8").read()
    seen = set()
    patterns = []
    for m in LEAF.finditer(text):
        body = m.group(1)
        style = m.group(2)
        if style not in STYLE_SCOPE:
            continue
        if any(k in body for k in ('include(', '{', '}', '\n', '§')):
            continue
        body = body.replace('\\/', '/')
        key = (style, body)
        if key in seen:
            continue
        seen.add(key)
        patterns.append({"match": body, "name": STYLE_SCOPE[style]})

    grammar = {
        "name": "Minecraft Function",
        "scopeName": "source.mcfunction",
        "patterns": patterns,
    }
    json.dump(grammar, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(f"converted {len(patterns)} leaf rules -> {out}")


if __name__ == "__main__":
    main()
