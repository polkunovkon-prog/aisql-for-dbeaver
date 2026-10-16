# История версий

## 1.0.0 — 2026-10-16

Первая версия «ИИ-исполнитель SQL» для DBeaver CE 26.2.2. Полная переработка
пилотного (патченного) плагина в самостоятельные OSGi-бандлы.

**Состав**

- два бандла: `com.pka.dbeaver.aisql.core` (логика, функции) и
  `com.pka.dbeaver.aisql.ui` (команды, страница настроек, промпты);
- 11 функций тулбокса `db` в штатной точке расширения `com.dbeaver.ai.function`
  (`runQuery`, `executeSql`, `getContext`, `listObjects`, `getObjectDdl`,
  `explain`, `help`, `listMacros`, `runMacro`, `defineMacro`, `deleteMacro`);
- команды «выделил → спросил»: `Ctrl+Alt+A` (SQL-редактор),
  `Ctrl+Alt+I` (таблица результатов); вставка промпта в ИИ-чат без автоотправки;
- страница настроек `AI → «AI SQL: инструменты SQL»` (+ кнопка в панели
  ИИ-чата; динамический алиас для страницы «Инструменты ИИ» DBeaver).

**Безопасность**

- классификация SQL на основе парсера DBeaver (SQLScriptParser/SQLQuery) —
  без токенных эвристик; `SELECT … INTO` трактуется как запись;
- профили подключений `READ_ONLY` / `STANDARD` (по умолчанию) / `FULL`
  с масками имён (`policy.profile.<маска>`); `EXPLAIN ANALYZE` в READ_ONLY
  запрещён;
- `runQuery.strictReadOnly` (по умолчанию включён);
- `policy.forbidden` — regex-запреты (DROP, TRUNCATE и т.п.) во всех профилях,
  кроме FULL;
- отключение автокоммита перед изменяющими операторами с возможностью отката;
- маскирование значений секретных колонок до отправки в модель
  (`mask.enabled`, `mask.rules`);
- лимиты: строк (200, потолок 5000), таймаут (60 с), размер вывода для модели
  (40 000 символов), размер макроса (64 КиБ).

**Аудит v2**

- журнал `ai-sql-audit.jsonl` с цепочкой SHA-256 (`prev`/`hash`), ротацией
  (`audit.maxBytes`, `audit.maxFiles`) и опциональным редактированием
  строковых литералов (`audit.redactLiterals`);
- утилита проверки `releng/verify-audit.py`; старый `ai-sql-audit.log`
  не используется и не удаляется.

**Инфраструктура**

- сборка без Maven/Tycho: `releng/build.sh` (javac против установленного
  DBeaver; `dist/`, `SHA256SUMS`, `BUILD-INFO.txt`);
- офлайн-тесты `releng/test.sh` (105 проверок, plain Java);
- установка/удаление `releng/install.sh` / `releng/uninstall.sh`
  (dropins + регистрация p2-реконцилером под xvfb, запасной вариант —
  правка `bundles.info`; миграция со старого патченного плагина);
- документация `docs/` (INSTALL, USAGE, SECURITY, TESTING, ADR).

**Известные ограничения**

- совместимость с другими версиями DBeaver не заявляется (целевая — 26.2.2);
- `Ctrl+Alt+A`/`Ctrl+Alt+I` могут конфликтовать с локальными раскладками
  клавиатуры — переназначаются штатными средствами (`Preferences → Keys`).
