#!/usr/bin/env bash
#
# Установка плагина «AI SQL for DBeaver» в системный DBeaver CE.
#
# ЗАПУСКАЕТСЯ ОТ ROOT (нужны права на запись в каталог установки):
#   sudo ./releng/install.sh
#
# Что делает скрипт:
#   1) выводит старый плагин: восстанавливает jar-ы DBeaver из *.dbeaver-orig
#      (патченые копии сохраняются рядом с суффиксом .aisql-install-backup-*);
#   2) копирует новые бандлы в <DBeaver>/dropins/plugins/;
#   3) делает короткий headless-запуск DBeaver (xvfb), чтобы p2-реконцилер
#      зарегистрировал бандлы; если регистрация не удалась — добавляет записи
#      в bundles.info вручную;
#   4) контрольным запуском проверяет, что плагин загружается.
#
# Переменные: DBEAVER_HOME (по умолчанию /usr/share/dbeaver-ce),
#             LAUNCHER (по умолчанию /usr/bin/dbeaver-ce).
#
set -euo pipefail

DBEAVER_HOME="${DBEAVER_HOME:-/usr/share/dbeaver-ce}"
LAUNCHER="${LAUNCHER:-/usr/bin/dbeaver-ce}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DIST_PLUGINS="$SCRIPT_DIR/../dist/dropins/plugins"
DROPINS="$DBEAVER_HOME/dropins/plugins"
BUNDLES_INFO="$DBEAVER_HOME/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
MARKER="com.pka.dbeaver.aisql"
BACKUP_SUFFIX=".aisql-install-backup"
WORK_DIR="/tmp/aisql-install"
LOG_DIR="$WORK_DIR/logs"
MARKER_TEXT="AI SQL UI plugin started"

die() { echo "ОШИБКА: $*" >&2; exit 1; }
info() { echo; echo "== $*"; }

[ "$(id -u)" -eq 0 ] || die "запустите скрипт с правами root: sudo $0"
[ -d "$DBEAVER_HOME" ] || die "не найден каталог DBeaver: $DBEAVER_HOME"
[ -f "$BUNDLES_INFO" ] || die "не найден $BUNDLES_INFO"

JARS=("$DIST_PLUGINS"/com.pka.dbeaver.aisql.*.jar)
[ -e "${JARS[0]}" ] || die "не найдены собранные бандлы в $DIST_PLUGINS (сначала выполните ./releng/build.sh)"

# ---------------------------------------------------------------------------
info "1/5 Миграция: вывод старого плагина (восстановление jar-ов DBeaver)"
restored=0
shopt -s nullglob
for orig in "$DBEAVER_HOME"/plugins/*.dbeaver-orig; do
    base="${orig%.dbeaver-orig}"
    if [ -f "$base" ]; then
        stamp="$(date +%Y%m%d-%H%M%S)"
        backup="$base$BACKUP_SUFFIX-$stamp"
        cp -p "$base" "$backup"
        cp -p "$orig" "$base"
        echo "   восстановлен $(basename "$base") (патченый jar сохранён: $(basename "$backup"))"
        restored=$((restored + 1))
    fi
done
shopt -u nullglob
if [ "$restored" -eq 0 ]; then
    echo "   патченных jar-ов (.dbeaver-orig) не найдено — шаг пропущен"
fi

# ---------------------------------------------------------------------------
info "2/5 Установка бандлов"
mkdir -p "$DROPINS"
cp -f "${JARS[@]}" "$DROPINS/"
chmod 644 "$DROPINS"/com.pka.dbeaver.aisql.*.jar
ls -l "$DROPINS"

# ---------------------------------------------------------------------------
info "3/5 Регистрация бандлов (DBeaver запускается под xvfb, ~1-3 минуты)"
mkdir -p "$LOG_DIR"

if ! command -v xvfb-run >/dev/null 2>&1; then
    die "не найден xvfb-run. Установите пакет xvfb и запустите скрипт снова (sudo apt install xvfb)"
fi

DATA_DIR="$WORK_DIR/workspace"

start_dbeaver() {
    rm -rf "$DATA_DIR"
    mkdir -p "$DATA_DIR"
    timeout -k 10 300 xvfb-run -a "$LAUNCHER" -data "$DATA_DIR" -nosplash \
        > "$LOG_DIR/stdout.log" 2>&1 &
    DBEAVER_PID=$!
}

wait_for_marker() {
    local waited=0
    while [ "$waited" -lt 240 ]; do
        if grep -rqs "$MARKER_TEXT" "$DATA_DIR/.metadata" 2>/dev/null; then
            return 0
        fi
        kill -0 "$DBEAVER_PID" 2>/dev/null || break
        sleep 3
        waited=$((waited + 3))
    done
    return 1
}

stop_dbeaver() {
    kill "$DBEAVER_PID" 2>/dev/null || true
    sleep 3
    kill -9 "$DBEAVER_PID" 2>/dev/null || true
    wait "$DBEAVER_PID" 2>/dev/null || true
    # Страховка от осиротевших процессов этого запуска (Java/Xvfb).
    # Шаблон — путь рабочей среды, он не совпадает с командной строкой самого скрипта.
    pkill -f "$DATA_DIR" 2>/dev/null || true
    sleep 1
}

# Первый запуск — регистрация.
start_dbeaver
sleep 2
wait_for_marker || true
stop_dbeaver

if grep -q "$MARKER" "$BUNDLES_INFO"; then
    echo "   бандлы зарегистрированы (p2-реконцилер добавил записи в bundles.info)"
else
    echo "   реконцилер не зарегистрировал бандлы — добавляю записи в bundles.info вручную"
    cp -p "$BUNDLES_INFO" "$BUNDLES_INFO$BACKUP_SUFFIX-$(date +%Y%m%d-%H%M%S)"
    for jar in "$DROPINS"/com.pka.dbeaver.aisql.*.jar; do
        name="$(basename "$jar")"
        sym="${name%%_[0-9]*}"
        ver="$(echo "$name" | sed -E 's/^.*_([0-9][^_]*[0-9])\.jar$/\1/')"
        printf '%s,%s,file:%s,4,false\n' "$sym" "$ver" "$jar" >> "$BUNDLES_INFO"
        echo "   + $sym,$ver"
    done
fi

# ---------------------------------------------------------------------------
info "4/5 Проверка загрузки плагина (контрольный запуск)"
start_dbeaver
sleep 2
if wait_for_marker; then
    echo "   плагин загружен: найдено «$MARKER_TEXT»"
    STOP_OK=1
else
    STOP_OK=0
fi
stop_dbeaver

if [ "${STOP_OK:-0}" != "1" ]; then
    echo "   в журнале рабочей среды не найдено «$MARKER_TEXT»."
    echo "   Последние строки журнала DBeaver:"
    LOGFILE="$(find "$DATA_DIR/.metadata" -name '*.log' 2>/dev/null | head -1 || true)"
    if [ -n "${LOGFILE:-}" ] && [ -f "$LOGFILE" ]; then
        tail -30 "$LOGFILE" || true
    fi
    echo "   stdout DBeaver (последние строки):"
    tail -20 "$LOG_DIR/stdout.log" 2>/dev/null || true
    die "плагин установлен, но не подтвердил загрузку — см. вывод выше"
fi

# ---------------------------------------------------------------------------
info "5/5 Готово"
cat <<'EOF'
Плагин установлен. Что дальше:

  1. Запустите DBeaver (обычным пользователем) и подключитесь к БД.
  2. Настройки плагина: Window → Preferences → AI → «AI SQL: инструменты SQL...»
     (там же откроется кнопка «шестерёнки» в панели ИИ-чата).
  3. В SQL-редакторе выделите текст → Ctrl+Alt+A («спросить ИИ о выделенном»).
     В таблице результатов выделите строки/ячейки → Ctrl+Alt+I.
  4. Файл настроек и журнал аудита лежат в
     <workspace>/.metadata/.config/  (ai-sql-plugin.properties, ai-sql-audit.jsonl).

Удаление: sudo ./releng/uninstall.sh
EOF
