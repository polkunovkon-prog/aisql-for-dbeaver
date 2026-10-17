#!/usr/bin/env bash
#
# Офлайн-тесты плагина AI SQL for DBeaver:
#   - сборка бандлов (releng/build.sh);
#   - компиляция и запуск тестов на JDK + jar-ах установленного DBeaver.
#
# Использование:
#   ./releng/test.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DBEAVER_HOME="${DBEAVER_HOME:-/usr/share/dbeaver-ce}"
PLUGINS_DIR="${PLUGINS_DIR:-$DBEAVER_HOME/plugins}"

echo "== Сборка бандлов =="
"$ROOT/releng/build.sh" | tail -4

CP="$(find "$PLUGINS_DIR" -maxdepth 1 -name '*.jar' ! -name '*.dbeaver-orig' | sort | tr '\n' ':')"
CP="$CP$ROOT/build/com.aisql.dbeaver.core/classes"
CP="$CP:$ROOT/build/com.aisql.dbeaver.ui/classes"

TEST_OUT="$ROOT/build/tests"
rm -rf "$TEST_OUT"
mkdir -p "$TEST_OUT"
find "$ROOT/tests/src" -name '*.java' | sort > "$ROOT/build/test-sources.txt"

echo "== Компиляция тестов =="
javac --release 21 -encoding UTF-8 -Xlint:-options \
    -cp "$CP" -d "$TEST_OUT" "@$ROOT/build/test-sources.txt"

echo "== Запуск тестов =="
java -cp "$TEST_OUT:$CP" com.aisql.dbeaver.tests.AllTests
