#!/usr/bin/env bash
# 前端一键：预编译 JSX -> app.js，并运行 headless 渲染测试。
# 需要受管 node workspace 已安装 @babel/standalone / jsdom / react / react-dom。
set -e
HERE_UNIX="$(cd "$(dirname "$0")" && pwd)"
# Windows 版 node 需要 Windows/混合风格路径（/d/... 会被误解析为 D:\d\...）
if command -v cygpath >/dev/null 2>&1; then HERE="$(cygpath -m "$HERE_UNIX")"; else HERE="$HERE_UNIX"; fi
NODE="${NODE_BIN:-/c/Users/Administrator/.workbuddy/binaries/node/versions/22.22.2/node.exe}"
export NODE_PATH="${WB_NODE_MODULES:-C:/Users/Administrator/.workbuddy/binaries/node/workspace/node_modules}"

echo "== [1/2] 预编译 app.jsx -> app.js =="
"$NODE" "$HERE/build_app.js"

echo "== [2/2] 运行前端渲染测试 =="
"$NODE" "$HERE/test/render.test.js"
