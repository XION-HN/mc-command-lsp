#!/usr/bin/env bash
# LSP 冒烟：启动 installDist 产物，跑 initialize + didOpen + completion
set -euo pipefail
cd "$(dirname "$0")/.."
BIN=build/install/mc-command-lsp/bin/mc-command-lsp
test -x "$BIN" || { echo "先运行 ./gradlew installDist"; exit 1; }
JAVA_HOME="${JAVA_HOME:-/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk}" python3 - "$BIN" <<'PY'
import subprocess, json, sys, os
binp=sys.argv[1]
p = subprocess.Popen([binp,"data/commands.json","data/items.json"], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
def frame(o):
    b=json.dumps(o).encode(); return b"Content-Length: %d\r\n\r\n"%len(b)+b
def read_msg():
    h={}
    while True:
        line=p.stdout.readline()
        if not line: return None
        line=line.decode().rstrip("\r\n")
        if line=="": break
        k,v=line.split(":",1); h[k.strip().lower()]=v.strip()
    return json.loads(p.stdout.read(int(h["content-length"])))
p.stdin.write(frame({"jsonrpc":"2.0","id":1,"method":"initialize","params":{}})); p.stdin.flush()
print("server:", read_msg()["result"]["serverInfo"]["name"])
p.stdin.write(frame({"jsonrpc":"2.0","method":"initialized","params":{}})); p.stdin.flush()
p.stdin.write(frame({"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"file:///a.mcfunction","languageId":"mcfunction","text":"/give @p wool 1 \n"}}})); p.stdin.flush()
p.stdin.write(frame({"jsonrpc":"2.0","id":2,"method":"textDocument/completion","params":{"textDocument":{"uri":"file:///a.mcfunction"},"position":{"line":0,"character":16}}})); p.stdin.flush()
items=read_msg()["result"]["items"]
print("items:", len(items), "first:", items[0]["label"] if items else None)
assert any("白色羊毛" in i["label"] for i in items), "completion missing wool"
p.stdin.write(frame({"jsonrpc":"2.0","id":3,"method":"shutdown"})); p.stdin.flush(); read_msg()
p.stdin.write(frame({"jsonrpc":"2.0","method":"exit"})); p.stdin.flush()
assert p.wait(timeout=10)==0
print("OK")
PY
