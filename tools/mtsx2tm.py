#!/usr/bin/env python3
"""mtsx -> tmLanguage 转换 v2：defines 宏解析 + include() 递归展开（叶子级）。

MT 语法里大量规则写成 `{match: include("start")+...+/.../}`，
先读取顶层 `defines` 里的宏（`"Name": /regex/`），递归替换 include()。
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
    "Json-Key": "support.type.property-name.json.mcfunction",
    "Json-String": "string.quoted.json.mcfunction",
    "Json-Number": "constant.numeric.json.mcfunction",
    "Json-Enum": "entity.name.tag.json.mcfunction",
    "Error": "invalid.illegal.mcfunction",
}

DEF_HEAD = re.compile(r'^[ \t]*"([A-Za-z_][\w-]*)"\s*:\s*/', re.M)


def extract_defs(text):
    """name -> raw regex（去掉外层 / 定界符）。"""
    defs = {}
    for m in DEF_HEAD.finditer(text):
        name = m.group(1)
        i = m.end()
        j = i
        # 找到未转义且后随 , 或换行的结尾 /
        while j < len(text):
            if text[j] == '\\':
                j += 2
                continue
            if text[j] == '/':
                nxt = j + 1
                while nxt < len(text) and text[nxt] in ' \t':
                    nxt += 1
                if nxt >= len(text) or text[nxt] in ',\n':
                    break
            j += 1
        if j < len(text):
            defs[name] = text[i:j]
    return defs


class Resolver:
    def __init__(self, defs):
        self.defs = defs

    def resolve(self, name, depth=0):
        if depth > 14 or name not in self.defs:
            return None
        raw = self.defs[name]
        out = self.expand(raw, depth)
        return out

    def expand(self, raw, depth):
        # 递归展开 include("X")
        for _ in range(40):
            inc = re.search(r'include\("([A-Za-z_][\w-]*)"\)', raw)
            if not inc:
                break
            name = inc.group(1)
            val = self.resolve(name, depth + 1)
            if val is None:
                return None
            # 去掉宏左右的 "/+"、"+/" 拼接符
            s, e = inc.start(), inc.end()
            pre, post = raw[:s], raw[e:]
            pre = re.sub(r'[+ ]*/+$', '', pre)
            post = re.sub(r'^/+[+ ]*', '', post)
            raw = pre + val + post
        return raw if 'include(' not in raw else None


def body_of(leaf):
    """取 {match: 之后到首个 ' 0:'/'1:' 之前的正文。"""
    start = leaf.find('{match:')
    if start < 0:
        return None
    i = start + len('{match:')
    j = leaf.find(' 0:', i)
    k = leaf.find(' 1:', i)
    if j < 0:
        j = k if k >= 0 else len(leaf)
    if k >= 0:
        j = min(j, k)
    body = leaf[i:j].strip()
    body = body.strip()
    if body.startswith('/'):
        body = body[1:]
        if body.endswith('/'):
            body = body[:-1]
    return body


def main():
    src, out = sys.argv[1], sys.argv[2]
    text = open(src, encoding='utf-8').read()
    defs = extract_defs(text)
    res = Resolver(defs)
    seen = set()
    patterns = []
    for leaf in re.finditer(r'\{match:[^{}]*\}', text, re.S):
        body = body_of(leaf.group(0))
        style_m = re.search(r'\b0:\s*"([A-Za-z_][\w-]*)"', leaf.group(0))
        if body is None or style_m is None:
            continue
        style = style_m.group(1)
        if style not in STYLE_SCOPE:
            continue
        rx = res.expand(body, 0)
        if not rx or 'include(' in rx or any(x in rx for x in ('{', '}', '\n', '§')):
            continue
        rx = rx.replace('\\/', '/')
        key = (style, rx)
        if key in seen:
            continue
        seen.add(key)
        patterns.append({'match': rx, 'name': STYLE_SCOPE[style]})

    grammar = {'name': 'Minecraft Function', 'scopeName': 'source.mcfunction',
               'patterns': patterns}
    json.dump(grammar, open(out, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
    print(f'defs={len(defs)} converted={len(patterns)} leaf rules -> {out}')


if __name__ == '__main__':
    main()
