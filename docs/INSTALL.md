# Установка AI SQL for DBeaver

## 1. Требования

| Компонент | Требование |
|---|---|
| ОС | Linux (проверено на рабочей станции предприятия) |
| DBeaver | DBeaver CE 26.2.2, каталог `/usr/share/dbeaver-ce` |
| Java | JDK 21+ с `javac` (только для сборки) |
| Утилиты | `xvfb-run` (авторегистрация бандлов), `python3` (иконки, проверка аудита) |
| Права | сборка — обычный пользователь; установка/удаление — root |

Проверка версии DBeaver: `ls /usr/share/dbeaver-ce/plugins/org.jkiss.dbeaver.core_*.jar`
(имя jar-а содержит версию, например `26.2.2.202610041844`).

## 2. Сборка

```bash
cd ~/aisql-for-dbeaver
./releng/build.sh
```

Что делает скрипт:

1. компилирует `javac --release 21` оба бандла против **всех** jar-ов
   `/usr/share/dbeaver-ce/plugins` (бэкапы `*.dbeaver-orig` исключаются);
2. упаковывает бандлы в jar-ы с корректным `MANIFEST.MF`;
3. раскладывает результат:
   - `build/com.pka.dbeaver.aisql.core_1.0.0.jar`
   - `build/com.pka.dbeaver.aisql.ui_1.0.0.jar`
   - `dist/dropins/plugins/*.jar` — готовые к установке артефакты;
   - `dist/SHA256SUMS`, `dist/BUILD-INFO.txt` — контрольные суммы и паспорт сборки.

Другой каталог DBeaver: `DBEAVER_HOME=/opt/dbeaver ./releng/build.sh`.

## 3. Тесты (рекомендуется до установки)

```bash
./releng/test.sh
```

Офлайн-тесты не требуют запущенного DBeaver: они стартуют обычную JVM с
jar-ами DBeaver и подменяют каталог настроек (`-Daisql.config.dir`), поэтому
рабочие настройки не затрагиваются. Ожидаемый итог: `все проверки пройдены`.

## 4. Установка (root)

```bash
sudo ./releng/install.sh
```

Скрипт выполняет четыре шага.

### Шаг 1. Миграция: вывод старого плагина

Старый плагин поставлялся патчеными jar-ами DBeaver
(`*.jar.dbeaver-orig` — резервные копии оригиналов). Скрипт:

- находит каждый `*/plugins/*.dbeaver-orig`;
- сохраняет текущий (патченый) jar рядом как `*.jar.aisql-install-backup-<дата>`;
- восстанавливает оригинальный jar из `.dbeaver-orig`.

Если патчей нет — шаг пропускается.

### Шаг 2. Копирование бандлов

`dist/dropins/plugins/com.pka.dbeaver.aisql.{core,ui}_1.0.0.jar` →
`/usr/share/dbeaver-ce/dropins/plugins/` (штатный каталог расширений DBeaver).

### Шаг 3. Регистрация бандлов

DBeaver использует p2-реконцилер: бандлы из `dropins` регистрируются при
запуске. Скрипт делает короткий headless-запуск (`xvfb-run`) и ждёт либо
регистрации реконцилером, либо делает запасной вариант: аккуратно дописывает
записи в `/usr/share/dbeaver-ce/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info`
(перед правкой создаётся копия файла).

### Шаг 4. Контрольный запуск

Второй короткий запуск: скрипт ищет в журнале рабочей среды строку
`AI SQL UI plugin started` — это подтверждение, что бандлы видны и плагин
загружается. Если строки нет — скрипт показывает хвост журнала и завершается
с ошибкой (установка при этом уже выполнена — см. «Диагностика»).

После успешной установки запустите DBeaver обычным пользователем.

## 5. Проверка установки в интерфейсе

1. `Window → Preferences → AI` — присутствует страница
   **«AI SQL: инструменты SQL»** (иконка — ползунки);
2. в Preferences → AI → «Инструменты ИИ» в списке **11** функций
   с префиксом `db_`;
3. в SQL-редакторе: выделить текст → `Ctrl+Alt+A` — откроется ИИ-чат
   с вставленным промптом (отправлять не нужно, текст только вставлен);
4. в таблице результатов: выделить строки → `Ctrl+Alt+I`.

## 6. Обновление версии

```bash
./releng/build.sh          # пересобрать
sudo ./releng/install.sh   # повторить установку поверх (идемпотентно)
```

Обновившийся jar в `dropins` реконцилер подхватит при следующем запуске
DBeaver; при сомнениях запустите DBeaver один раз и проверьте журнал.

## 7. Удаление

```bash
sudo ./releng/uninstall.sh
```

Удаляются бандлы из `dropins/plugins` и записи из `bundles.info`.
Настройки, журнал аудита и макросы в рабочей среде пользователя
**не удаляются** (ничего не пропадёт) — при необходимости удалите вручную:

- `<workspace>/.metadata/.config/ai-sql-plugin.properties` — настройки;
- `<workspace>/.metadata/.config/ai-sql-audit.jsonl*` — журнал аудита;
- `<workspace>/.metadata/.config/ai-macros/` — макросы.

## 8. Ручная установка (если скрипт не подходит)

1. Скопировать оба jar-а в `/usr/share/dbeaver-ce/dropins/plugins/` (root);
2. Дописать в `bundles.info` две строки (root; перед правкой сделать копию):

   ```
   com.pka.dbeaver.aisql.core,1.0.0,file:/usr/share/dbeaver-ce/dropins/plugins/com.pka.dbeaver.aisql.core_1.0.0.jar,4,false
   com.pka.dbeaver.aisql.ui,1.0.0,file:/usr/share/dbeaver-ce/dropins/plugins/com.pka.dbeaver.aisql.ui_1.0.0.jar,4,false
   ```

3. Запустить DBeaver и проверить журнал.

## 9. Диагностика

| Симптом | Что проверить |
|---|---|
| Страницы настроек нет | письмена `com.pka.dbeaver.aisql` в `bundles.info`; права на чтение jar-ов (`chmod 644`) |
| В чате нет функций `db_*` | страница Preferences → AI → «Инструменты ИИ»: функции должны быть включены (набор «db» включён по умолчанию) |
| Хоткей не срабатывает | конфликт привязок: `Window → Preferences → Keys` найди команду «Спросить ИИ…» и назначьте свободную комбинацию |
| `install.sh` не находит DBeaver | задайте `DBEAVER_HOME` / `LAUNCHER` |
| Регистрация не прошла | повторите `sudo ./releng/install.sh` или добавьте записи вручную (п. 8) |
| Пусто в журнале аудита | `audit.enabled=true` на странице настроек; проверьте `<workspace>/.metadata/.config/ai-sql-audit.jsonl` |

Журналы:

- DBeaver-лог: `<workspace>/.metadata/.log` (искать `AI SQL`, `aisql`);
- хвост ручной установки: `/tmp/aisql-install/logs/`.
