#!/bin/bash
# ============================================================
# GeoDRSync 全自动化测试 (Linux)
# 用法:  bash run-tests.sh
# 依赖: maven (优先 ./mvnw，否则系统 mvn)
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"

MVN=""
if [ -x "./mvnw" ]; then MVN="./mvnw";
elif command -v mvn >/dev/null 2>&1; then MVN="mvn";
else echo "未找到 maven(mvnw/mvn)，请先安装 Maven"; exit 1; fi

echo "==> 使用构建工具: ${MVN}"
echo "==> 运行全自动化测试 (JUnit5 集成测试)..."
"${MVN}" test
RC=$?
if [ "${RC}" -eq 0 ]; then echo -e "\n[SUCCESS] 全部测试通过 ✅"; else echo -e "\n[FAIL] 测试存在失败用例 (RC=${RC})"; fi
exit ${RC}
