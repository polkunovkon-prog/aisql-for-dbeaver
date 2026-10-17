#!/usr/bin/env bash
#
# Установка плагина «AI SQL for DBeaver» в системный DBeaver CE.
#
# ЗАПУСКАЕТСЯ ОТ ROOT (нужны права на запись в каталог установки):
#   sudo ./releng/install.sh
#
# Что делает скрипт:
#   1) выводит старый плагин: восстанавливает jar-ы DBeaver из *.dbeaver-orig
#      (патченые копии сохраняются рядом с суффиксом .aisql-install-backup-*;
#      одинаковые файлы пропускаются — повторный запуск безопасен);
#   2) копирует новые бандлы в <DBeaver>/dropins/plugins/ (прежние версии
#      нашего плагина там удаляются);
#   3) перерегистрирует бандлы в bundles.info: строки плагина заменяются
#      записями о новых jar-ах (копия исходного файла сохраняется; повторный
#      запуск без изменений файл не трогает);
#   4) проверяет загрузку плагина коротким headless-запуском DBeaver под xvfb:
#      сначала от имени владельца проекта (повторяет реальный запуск),
#      затем, при необходимости, от root; если проверить не удалось —
#      предупреждение с диагностикой, установка при этом завершена;
#   5) печатает инструкцию «что дальше».
#
# Переменные: DBEAVER_HOME (по умолчанию /usr/share/dbeaver-ce),
#             LAUNCHER (по умолчанию /usr/bin/dbeaver-ce).
#
set -euo pipefail

DBEAVER_HOME="${DBEAVER_HOME:-/usr/share/dbeaver-ce}"
LAUNCHER="${LAUNCHER:-/usr/bin/dbeaver-ce}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
DIST_PLUGINS="$SCRIPT_DIR/../dist/dropins/plugins"
DROPINS="$DBEAVER_HOME/dropins/plugins"
BUNDLES_INFO="$DBEAVER_HOME/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
MARKER="com.pka.dbeaver.aisql"
BACKUP_SUFFIX=".aisql-install-backup"
WORK_DIR="/tmp/aisql-install"          # каталог прошлых версий скрипта (для уборки хвостов)
VERIFY_DIR="/tmp/aisql-verify"         # рабочая среда контрольного запуска
VERIFY_WS="$VERIFY_DIR/workspace"
VERIFY_LOG="$VERIFY_DIR/stdout.log"
MARKER_TEXT="AI SQL UI plugin started"
DBEAVER_PID=""

die() { echo "ОШИБКА: $*" >&2; exit 1; }
info() { echo; echo "== $*"; }

[ "$(id -u)" -eq 0 ] || die "запустите скрипт с правами root: sudo $0"
[ -d "$DBEAVER_HOME" ] || die "не найден каталог DBeaver: $DBEAVER_HOME"
[ -f "$BUNDLES_INFO" ] || die "не найден $BUNDLES_INFO"

JARS=("$DIST_PLUGINS"/com.pka.dbeaver.aisql.*.jar)
[ -e "${JARS[0]}" ] || die "не найдены собранные бандлы в $DIST_PLUGINS (сначала выполните ./releng/build.sh)"

# Пользователь, чей DBeaver (и его кэши) обслуживаются: SUDO_USER при запуске
# через sudo, иначе — владелец каталога проекта (типичный случай: su/root-сессия).
CALLER="${SUDO_USER:-}"
if [ -z "$CALLER" ] || [ "$CALLER" = "root" ]; then
    CALLER="$(stat -c %U "$ROOT_DIR" 2>/dev/null || echo root)"
fi
CALLER_HOME=""
if [ "$CALLER" != "root" ]; then
    CALLER_HOME="$(getent passwd "$CALLER" 2>/dev/null | cut -d: -f6 || true)"
    if [ -z "$CALLER_HOME" ] || [ ! -d "$CALLER_HOME" ]; then
        CALLER="root"
    fi
fi

# DBeaver пользователя должен быть закрыт: работающий процесс при выходе
# перезапишет свой кэш, и сброс кэшей на шаге 2 не сработает.
USER_DBEAVER_RUNNING=0
if [ "$CALLER" != "root" ] && pgrep -u "$CALLER" -f "dbeaver-ce" >/dev/null 2>&1; then
    USER_DBEAVER_RUNNING=1
fi

# ---------------------------------------------------------------------------
info "1/5 Миграция: вывод старого плагина (восстановление jar-ов DBeaver)"
restored=0
skipped=0
shopt -s nullglob
for orig in "$DBEAVER_HOME"/plugins/*.dbeaver-orig; do
    base="${orig%.dbeaver-orig}"
    if [ ! -f "$base" ]; then
        continue
    fi
    if cmp -s "$orig" "$base"; then
        echo "   $(basename "$base"): уже оригинал (восстановлен ранее) — пропуск"
        skipped=$((skipped + 1))
        continue
    fi
    stamp="$(date +%Y%m%d-%H%M%S)"
    backup="$base$BACKUP_SUFFIX-$stamp"
    cp -p "$base" "$backup"
    cp -p "$orig" "$base"
    echo "   восстановлен $(basename "$base") (патченый jar сохранён: $(basename "$backup"))"
    restored=$((restored + 1))
done
shopt -u nullglob
if [ "$restored" -eq 0 ] && [ "$skipped" -eq 0 ]; then
    echo "   патченных jar-ов (.dbeaver-orig) не найдено — шаг пропущен"
fi

# ---------------------------------------------------------------------------
info "2/5 Установка бандлов"
mkdir -p "$DROPINS"
# Прежние версии нашего плагина удаляются, чтобы при обновлении (1.0.0 →
# 1.1.0) в dropins не оставалось старых jar-ов.
rm -f "$DROPINS"/com.pka.dbeaver.aisql.*.jar
cp -f "${JARS[@]}" "$DROPINS/"
chmod 644 "$DROPINS"/com.pka.dbeaver.aisql.*.jar
ls -l "$DROPINS"
# Equinox кэширует данные бандлов (configuration/org.eclipse.osgi) и модель
# реестра расширений (configuration/org.eclipse.core.runtime) и при совпадении
# пути и версии переиспользует старые данные, даже если jar заменён.
# Приёмочный тест 16.10.2026 показал оба проявления: «The bundle is not marked
# as singleton» и попытку загрузки удалённого класса PreferenceAliasRegistrar.
# Каталоги служебные и пересоздаются при запуске — безопасно удаляем всегда,
# чтобы любая переустановка давала гарантированно свежее состояние.
for rel in org.eclipse.osgi org.eclipse.core.runtime; do
    if [ -d "$DBEAVER_HOME/configuration/$rel" ]; then
        rm -rf "$DBEAVER_HOME/configuration/$rel"
        echo "   кэш OSGi сброшен ($DBEAVER_HOME/configuration/$rel)"
    fi
done
# У пользовательских запусков своя configuration: при недоступной системной
# DBeaver уводит её в персональный install-data
# (~/.local/share/DBeaverData/install-data/<product>/<version>/) — там же лежат
# кэши пользователя (манифесты бандлов и модель реестра). Дополнительно чистим
# следы старых Eclipse в домашнем каталоге; все области самовосстанавливаемые.
if [ "$CALLER" != "root" ] && [ -n "$CALLER_HOME" ]; then
    shopt -s nullglob
    for cfgdir in "$CALLER_HOME"/.local/share/DBeaverData/install-data/*/* \
                  "$CALLER_HOME/eclipse/configuration" \
                  "$CALLER_HOME"/.eclipse/*/configuration; do
        [ -d "$cfgdir" ] || continue
        for rel in org.eclipse.osgi org.eclipse.core.runtime; do
            if [ -d "$cfgdir/$rel" ]; then
                rm -rf "$cfgdir/$rel"
                echo "   кэш OSGi пользователя $CALLER сброшен ($cfgdir/$rel)"
            fi
        done
    done
    shopt -u nullglob
    if [ "$USER_DBEAVER_RUNNING" = "1" ]; then
        echo "   ВНИМАНИЕ: у $CALLER запущен DBeaver — закройте его и перезапустите"
        echo "   установку, иначе работающий процесс восстановит кэш при выходе."
    fi
fi

# ---------------------------------------------------------------------------
info "3/5 Регистрация бандлов (bundles.info)"
# Записи плагина перезаписываются целиком: при смене версии прежние строки
# указывали бы на старые jar-ы, и Equinox загрузил бы бандл прежней версии
# даже после обновления файлов. Записи других бандлов не трогаются.
MARKER_RE='^com\.pka\.dbeaver\.aisql\.'
new_entries="$(mktemp)"
trap 'rm -f "$new_entries"' EXIT
for jar in "$DROPINS"/com.pka.dbeaver.aisql.*.jar; do
    name="$(basename "$jar")"
    sym="${name%%_[0-9]*}"
    ver="$(echo "$name" | sed -E 's/^.*_([0-9][^_]*[0-9])\.jar$/\1/')"
    printf '%s,%s,file:%s,4,false\n' "$sym" "$ver" "$jar" >> "$new_entries"
done
if diff -q <(grep "$MARKER_RE" "$BUNDLES_INFO" || true) "$new_entries" >/dev/null 2>&1; then
    echo "   записи актуальны — изменения не требуются"
else
    cp -p "$BUNDLES_INFO" "$BUNDLES_INFO$BACKUP_SUFFIX-$(date +%Y%m%d-%H%M%S)"
    echo "   копия прежнего bundles.info сохранена (…$BACKUP_SUFFIX-*)"
    sed -i "/$MARKER_RE/d" "$BUNDLES_INFO"
    while IFS= read -r entry; do
        printf '%s\n' "$entry" >> "$BUNDLES_INFO"
        echo "   + ${entry%%,*}"
    done < "$new_entries"
fi
grep -q "$MARKER" "$BUNDLES_INFO" || die "не удалось зарегистрировать бандлы в bundles.info"

# ---------------------------------------------------------------------------
info "4/5 Проверка загрузки плагина (headless-запуск под xvfb)"
command -v xvfb-run >/dev/null 2>&1 || die "не найден xvfb-run. Установите пакет xvfb (sudo apt install xvfb) и запустите скрипт снова"

# CALLER/CALLER_HOME вычислены в начале скрипта (нужны шагу 2 для сброса кэшей).

sweep() {
    # Добиваем осиротевшие процессы прежних запусков (наши шаблоны
    # не совпадают с командной строкой самого скрипта).
    pkill -9 -f "$VERIFY_WS" 2>/dev/null || true
    pkill -9 -f "$WORK_DIR/workspace" 2>/dev/null || true
    sleep 1
}

stop_dbeaver() {
    [ -n "$DBEAVER_PID" ] || return 0
    kill -TERM -- "-$DBEAVER_PID" 2>/dev/null || true
    sleep 3
    kill -KILL -- "-$DBEAVER_PID" 2>/dev/null || true
    wait "$DBEAVER_PID" 2>/dev/null || true
    sweep
    DBEAVER_PID=""
}

wait_for_marker() {
    local ws="$1" limit="$2" waited=0
    while [ "$waited" -lt "$limit" ]; do
        if grep -rqs "$MARKER_TEXT" "$ws/.metadata" 2>/dev/null; then
            return 0
        fi
        if [ -n "$DBEAVER_PID" ] && ! kill -0 "$DBEAVER_PID" 2>/dev/null; then
            return 1
        fi
        sleep 3
        waited=$((waited + 3))
    done
    return 1
}

try_verify() {
    # $1 — пользователь (root или обычный), $2 — подпись для вывода
    local user="$1" label="$2" limit="$3"
    echo "   запуск: $label (до ${limit} с)"
    rm -rf "$VERIFY_DIR"
    mkdir -p "$VERIFY_WS"
    if [ "$user" = "root" ]; then
        setsid xvfb-run -a "$LAUNCHER" -data "$VERIFY_WS" -nosplash > "$VERIFY_LOG" 2>&1 &
    else
        chown -R "$user" "$VERIFY_DIR"
        local runenv=(env "HOME=$CALLER_HOME" "USER=$user" "LOGNAME=$user")
        local xdg="/run/user/$(id -u "$user")"
        [ -d "$xdg" ] && runenv+=("XDG_RUNTIME_DIR=$xdg")
        setsid runuser -u "$user" -- "${runenv[@]}" xvfb-run -a "$LAUNCHER" -data "$VERIFY_WS" -nosplash > "$VERIFY_LOG" 2>&1 &
    fi
    DBEAVER_PID=$!
    sleep 2
    if wait_for_marker "$VERIFY_WS" "$limit"; then
        stop_dbeaver
        return 0
    fi
    stop_dbeaver
    return 1
}

sweep   # уборка хвостов до начала проверки

if [ "$USER_DBEAVER_RUNNING" = "1" ]; then
    echo "   у пользователя $CALLER уже запущен DBeaver — проверка от его имени пропускается"
fi

VERIFIED=0
if [ "$CALLER" != "root" ] && [ "$USER_DBEAVER_RUNNING" = "0" ]; then
    if try_verify "$CALLER" "от имени пользователя $CALLER (как при обычном работе)" 180; then
        VERIFIED=1
    fi
fi
if [ "$VERIFIED" = "0" ]; then
    if try_verify "root" "от имени root" 120; then
        VERIFIED=1
    fi
fi

if [ "$VERIFIED" = "1" ]; then
    echo "   плагин загружен: найдено «$MARKER_TEXT»"
else
    echo "   ПРЕДУПРЕЖДЕНИЕ: автоматическая проверка не увидела «$MARKER_TEXT»."
    echo "   Это не обязательно ошибка: headless-запуск зависит от окружения"
    echo "   (например, запуск от root в xvfb может зависнуть). Установка при этом"
    echo "   завершена — запустите DBeaver обычным способом и проверьте загрузку."
    LOGFILE="$(find "$VERIFY_WS/.metadata" -name '*.log' 2>/dev/null | head -1 || true)"
    if [ -n "${LOGFILE:-}" ] && [ -f "$LOGFILE" ]; then
        echo "   Последние строки журнала контрольного запуска:"
        tail -20 "$LOGFILE" || true
    fi
    if grep -rqs "com.pka.*are ignored" "$VERIFY_WS/.metadata" 2>/dev/null; then
        echo "   ДИАГНОЗ: расширения плагина игнорируются Equinox (устаревший кэш OSGi)."
        echo "   Запустите установку ещё раз — шаг 2 сбросит кэш; либо вручную:"
        echo "   rm -rf $DBEAVER_HOME/configuration/org.eclipse.osgi"
    fi
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
