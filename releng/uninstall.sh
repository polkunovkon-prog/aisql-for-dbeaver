#!/usr/bin/env bash
#
# Удаление плагина «AI SQL for DBeaver» из системного DBeaver CE.
#
# ЗАПУСКАЕТСЯ ОТ ROOT:
#   sudo ./releng/uninstall.sh
#
# Удаляет бандлы из dropins/plugins и записи из bundles.info.
# Настройки, журнал аудита и макросы в рабочей среде пользователя
# НЕ удаляются (их можно удалить вручную, см. docs/SECURITY.md).
#
set -euo pipefail

DBEAVER_HOME="${DBEAVER_HOME:-/usr/share/dbeaver-ce}"
DROPINS="$DBEAVER_HOME/dropins/plugins"
BUNDLES_INFO="$DBEAVER_HOME/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
MARKER="com.pka.dbeaver.aisql"
BACKUP_SUFFIX=".aisql-uninstall-backup"

die() { echo "ОШИБКА: $*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "запустите скрипт с правами root: sudo $0"
[ -d "$DBEAVER_HOME" ] || die "не найден каталог DBeaver: $DBEAVER_HOME"

echo "== Удаление бандлов из $DROPINS"
shopt -s nullglob
removed=0
for jar in "$DROPINS"/com.pka.dbeaver.aisql.*.jar; do
    rm -f "$jar"
    removed=$((removed + 1))
done
shopt -u nullglob
echo "   удалено файлов: $removed"

echo "== Очистка записей в bundles.info"
if [ -f "$BUNDLES_INFO" ] && grep -q "$MARKER" "$BUNDLES_INFO"; then
    cp -p "$BUNDLES_INFO" "$BUNDLES_INFO$BACKUP_SUFFIX-$(date +%Y%m%d-%H%M%S)"
    grep -v "$MARKER" "$BUNDLES_INFO" > "$BUNDLES_INFO.tmp"
    mv "$BUNDLES_INFO.tmp" "$BUNDLES_INFO"
    echo "   записи удалены (копия bundles.info сохранена как ...$BACKUP_SUFFIX-*)"
else
    echo "   записей нет"
fi

echo "== Сброс кэшей OSGi"
# Equinox мог запомнить данные удалённых бандлов и модель реестра расширений
# (см. примечание в install.sh); кэши служебные и пересоздаются при запуске.
for rel in org.eclipse.osgi org.eclipse.core.runtime; do
    if [ -d "$DBEAVER_HOME/configuration/$rel" ]; then
        rm -rf "$DBEAVER_HOME/configuration/$rel"
        echo "   сброшен $DBEAVER_HOME/configuration/$rel"
    fi
done
UCALLER="${SUDO_USER:-root}"
UHOME=""
if [ "$UCALLER" != "root" ]; then
    UHOME="$(getent passwd "$UCALLER" 2>/dev/null | cut -d: -f6 || true)"
fi
if [ -n "${UHOME:-}" ] && [ -d "$UHOME" ]; then
    shopt -s nullglob
    for cfgdir in "$UHOME/eclipse/configuration" "$UHOME"/.eclipse/*/configuration; do
        [ -d "$cfgdir" ] || continue
        for rel in org.eclipse.osgi org.eclipse.core.runtime; do
            if [ -d "$cfgdir/$rel" ]; then
                rm -rf "$cfgdir/$rel"
                echo "   сброшен $cfgdir/$rel"
            fi
        done
    done
    shopt -u nullglob
fi

echo
echo "Готово. Плагин удалён — перезапустите DBeaver."
echo "Примечание: jar-ы .dbeaver-orig в plugins/ и настройки в рабочей среде"
echo "остаются на диске; оставшиеся патчи старого плагина восстановлены при установке."
