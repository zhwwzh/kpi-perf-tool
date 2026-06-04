#!/bin/bash
# 切换到项目根目录（scripts/ 的父目录），作为 user.dir / logs / output 的基准
cd "$(dirname "$0")/.." || exit 2

# 自动定位 jar（兼容版本号变化）
JAR=$(ls kpi-perf-tool-*.jar 2>/dev/null | head -n1)
if [ -z "$JAR" ]; then
    echo "Error: kpi-perf-tool-*.jar not found in $(pwd)" >&2
    exit 2
fi

exec java -jar "$JAR" "$@"
