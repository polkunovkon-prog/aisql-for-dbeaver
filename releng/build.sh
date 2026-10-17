#!/usr/bin/env bash
#
# Сборка бандлов плагина AI SQL for DBeaver без Maven/Tycho:
# компиляция javac против jar-ов установленного DBeaver CE и упаковка в jar.
#
# Использование:
#   ./releng/build.sh                     # автоопределение /usr/share/dbeaver-ce
#   DBEAVER_HOME=/opt/dbeaver ./releng/build.sh
#
# Результат:
#   build/       — классы и jar-ы бандлов
#   dist/dropins/plugins/*.jar — артефакты для установки
#   dist/SHA256SUMS
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DBEAVER_HOME="${DBEAVER_HOME:-/usr/share/dbeaver-ce}"
PLUGINS_DIR="${PLUGINS_DIR:-$DBEAVER_HOME/plugins}"
JAVAC="${JAVAC:-javac}"
JAR="${JAR:-jar}"
VERSION="1.2.1"
RELEASE="${JAVAC_RELEASE:-21}"

OUT="$ROOT/build"
DIST="$ROOT/dist"

if [ ! -d "$PLUGINS_DIR" ]; then
    echo "ОШИБКА: не найден каталог плагинов DBeaver: $PLUGINS_DIR" >&2
    echo "Укажите DBEAVER_HOME или PLUGINS_DIR (например, DBEAVER_HOME=/opt/dbeaver)." >&2
    exit 1
fi
if ! command -v "$JAVAC" >/dev/null 2>&1; then
    echo "ОШИБКА: не найден компилятор $JAVAC. Установите JDK 21+." >&2
    exit 1
fi

echo "== Сборка AI SQL for DBeaver v$VERSION =="
echo "DBeaver: $DBEAVER_HOME"
echo "JDK:     $("$JAVAC" -version 2>&1)"

# Classpath: все jar-ы плагинов DBeaver (бэкапы *.dbeaver-orig исключаем).
CP="$(find "$PLUGINS_DIR" -maxdepth 1 -name '*.jar' ! -name '*.dbeaver-orig' | sort | tr '\n' ':')"
# Плюс классы ранее собранных бандлов (core нужен для компиляции ui).
DEPS_CP=""

rm -rf "$OUT"
mkdir -p "$OUT" "$DIST"
rm -rf "$DIST/dropins"
mkdir -p "$DIST/dropins/plugins"

BUILT_JARS=()

for BUNDLE in com.pka.dbeaver.aisql.core com.pka.dbeaver.aisql.ui; do
    SRC="$ROOT/bundles/$BUNDLE"
    if [ ! -d "$SRC/src" ]; then
        echo "== Пропускаю $BUNDLE (нет исходников) =="
        continue
    fi
    CLASSES="$OUT/$BUNDLE/classes"
    JARROOT="$OUT/$BUNDLE/jarroot"
    mkdir -p "$CLASSES" "$JARROOT"

    echo "== Компиляция $BUNDLE =="
    find "$SRC/src" -name '*.java' | sort > "$OUT/$BUNDLE/sources.txt"
    if [ ! -s "$OUT/$BUNDLE/sources.txt" ]; then
        echo "== Пропускаю $BUNDLE (нет исходников) =="
        continue
    fi
    "$JAVAC" --release "$RELEASE" -encoding UTF-8 -Xlint:-options \
        -cp "$CP$DEPS_CP" -d "$CLASSES" "@$OUT/$BUNDLE/sources.txt"
    DEPS_CP="$DEPS_CP:$CLASSES"

    # Ресурсы из src (properties, png и т.п.) — с сохранением структуры пакетов.
    (cd "$SRC/src" && find . -type f ! -name '*.java' -print0 \
        | while IFS= read -r -d '' f; do
              mkdir -p "$CLASSES/$(dirname "$f")"
              cp "$f" "$CLASSES/$f"
          done)

    # Сборка jar: классы+ресурсы + plugin.xml + OSGI-INF + icons + MANIFEST.
    (cd "$CLASSES" && "$JAR" cf "$OUT/$BUNDLE/tmp.jar" .)
    cp "$OUT/$BUNDLE/tmp.jar" "$JARROOT/bundle.jar"
    (cd "$JARROOT" && "$JAR" xf bundle.jar && rm bundle.jar)
    cp "$SRC/plugin.xml" "$JARROOT/plugin.xml" 2>/dev/null || true
    for d in OSGI-INF icons schema; do
        if [ -d "$SRC/$d" ]; then
            cp -r "$SRC/$d" "$JARROOT/"
        fi
    done

    JARFILE="$OUT/${BUNDLE}_${VERSION}.jar"
    (cd "$JARROOT" && "$JAR" cfm "$JARFILE" "$SRC/META-INF/MANIFEST.MF" .)
    echo "   -> $JARFILE"
    BUILT_JARS+=("$JARFILE")
    cp "$JARFILE" "$DIST/dropins/plugins/"
done

if [ ${#BUILT_JARS[@]} -eq 0 ]; then
    echo "ОШИБКА: ничего не собрано." >&2
    exit 1
fi

(cd "$DIST" && sha256sum dropins/plugins/*.jar > SHA256SUMS)

# Информация о сборке.
DBEAVER_VER="$(ls "$PLUGINS_DIR"/org.jkiss.dbeaver.core_*.jar 2>/dev/null | grep -v orig | head -1 | sed -E 's/^.*core_([0-9][^_]*[0-9])\.jar$/\1/')"
{
    echo "AI SQL for DBeaver"
    echo "Версия плагина: $VERSION"
    echo "Дата сборки:    $(date '+%Y-%m-%d %H:%M:%S %z')"
    echo "JDK (javac):    $("$JAVAC" -version 2>&1)"
    echo "Целевая Java:   --release $RELEASE"
    echo "DBeaver:        ${DBEAVER_VER:-неизвестно} ($DBEAVER_HOME)"
    echo "Бандлы:"
    for jar in "$DIST/dropins/plugins/"*.jar; do
        echo "  $(basename "$jar")"
    done
    echo
    echo "Контрольные суммы: SHA256SUMS"
} > "$DIST/BUILD-INFO.txt"

echo ""
echo "== Готово =="
ls -l "$DIST/dropins/plugins/"
echo "Контрольные суммы: $DIST/SHA256SUMS"
