#!/usr/bin/env bash
# 运行指定测试：dev-test.sh TestA,TestB [label]
set -euo pipefail
ROOT=${ROOT:-/home/whl/fire-platform-n05-20261030}
export JAVA_HOME="$ROOT/.tools/linux/jdk-21"
export PATH="$ROOT/.tools/linux/node-24.16.0/bin:$ROOT/.tools/linux/jdk-21/bin:$ROOT/.tools/linux/apache-maven-3.9.11/bin:/usr/bin:/bin"
export TZ=UTC PYTHONDONTWRITEBYTECODE=1
cd "$ROOT/backend"
mvn -o -B -ntp "-Dtest=$1" test
