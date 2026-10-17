/*
 * AI SQL for DBeaver — ядро плагина «ИИ-исполнитель SQL».
 *
 * Общие вспомогательные функции для набора AI-функций плагина:
 *   - чтение/запись настроек плагина (.config/ai-sql-plugin.properties)
 *     с кэшем по времени изменения файла и атомарной записью;
 *   - журнал аудита v2 (.config/ai-sql-audit.jsonl): JSONL, цепочка хешей
 *     SHA-256, ротация (см. AisqlAudit);
 *   - профили безопасности подключений READ_ONLY/STANDARD/FULL
 *     (см. ConnectionProfiles) и правила policy.forbidden (см. QueryPolicy);
 *   - маскирование значений колонок в результатах (см. MaskingRules);
 *   - классификация SQL парсером DBeaver (см. SqlClassifier);
 *   - исполнение SQL на активном подключении и форматирование результатов;
 *   - хранилище макросов (.config/ai-macros/) с историей версий.
 *
 * Лицензия: Apache-2.0
 */
package com.pka.dbeaver.aisql.core;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.ai.AIFunctionContext;
import org.jkiss.dbeaver.model.ai.AIFunctionResult;
import org.jkiss.dbeaver.model.ai.AIFunctionType;
import org.jkiss.dbeaver.model.ai.engine.AIDatabaseContext;
import org.jkiss.dbeaver.model.data.DBDContent;
import org.jkiss.dbeaver.model.exec.DBCAttributeMetaData;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCResultSetMetaData;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCStatementType;
import org.jkiss.dbeaver.model.exec.DBCTransactionManager;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.runtime.DBWorkbench;

import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Ядро плагина «ИИ-исполнитель SQL».
 */
public final class AisqlCore {

    private static final Log log = Log.getLog(AisqlCore.class);

    /** Идентификатор внутреннего тулбокса DBeaver (функции видны как {@code db_*} ). */
    public static final String TOOLBOX_ID = "db";

    public static final String SETTINGS_FILE = "ai-sql-plugin.properties";
    /** Текущий журнал аудита (JSONL). */
    public static final String AUDIT_FILE = AisqlAudit.FILE_NAME;
    /** Старый текстовый журнал аудита (до v2) — остаётся на диске как есть. */
    public static final String LEGACY_AUDIT_FILE = "ai-sql-audit.log";
    public static final String MACROS_DIR = "ai-macros";

    // Ключи настроек
    /** Отключать autocommit для изменяющих запросов (чтобы можно было откатить). */
    public static final String KEY_WRITE_DISABLE_AUTOCOMMIT = "write.disableAutocommit";
    /** Строгий read-only режим для db_runQuery (проверка парсером DBeaver). */
    public static final String KEY_RUNQUERY_STRICT = "runQuery.strictReadOnly";
    /** Максимум строк, возвращаемых по умолчанию. */
    public static final String KEY_LIMIT_MAX_ROWS = "limit.maxRows";
    /** Жёсткий максимум строк. */
    public static final int HARD_MAX_ROWS = 5000;
    /** Таймаут выполнения оператора (сек) по умолчанию. */
    public static final String KEY_LIMIT_TIMEOUT = "limit.timeoutSec";
    /** Вести журнал аудита. */
    public static final String KEY_AUDIT_ENABLED = "audit.enabled";
    /** Максимум символов текстового результата, возвращаемого модели. */
    public static final String KEY_LIMIT_MAX_PAYLOAD = "limit.maxPayloadChars";
    /** Предел размера одного макроса (байт). */
    public static final String KEY_MACROS_MAX_BYTES = "macros.maxBytes";

    // --- Правка выделения ИИ в SQL-редакторе (Ctrl+Alt+E) ---
    /** Включена ли правка выделения ИИ. */
    public static final String KEY_EDIT_ENABLED = "edit.enabled";
    /** Режим применения: {@link #EDIT_MODE_PREVIEW} или {@link #EDIT_MODE_DIRECT}. */
    public static final String KEY_EDIT_APPLY_MODE = "edit.applyMode";
    /** Вид предпросмотра: {@link #EDIT_PREVIEW_DIFF} или {@link #EDIT_PREVIEW_FULL}. */
    public static final String KEY_EDIT_PREVIEW_MODE = "edit.previewMode";
    /** Максимум символов выделения для правки ИИ. */
    public static final String KEY_EDIT_MAX_SELECTION = "edit.maxSelectionChars";
    /** Записывать применения/отмены правок в журнал аудита. */
    public static final String KEY_EDIT_AUDIT = "edit.audit";

    public static final int DEFAULT_MAX_ROWS = 200;
    public static final int DEFAULT_TIMEOUT_SEC = 60;
    public static final int DEFAULT_CELL_LENGTH = 2000;
    public static final int DEFAULT_MAX_PAYLOAD = 40000;
    public static final int HARD_MAX_PAYLOAD = 200000;
    public static final int DEFAULT_MACROS_MAX_BYTES = 65536;
    /** Максимальная длина идентификатора макроса. */
    public static final int MAX_MACRO_ID_LEN = 64;
    /** Сколько версий макроса хранить в истории. */
    public static final int MACRO_HISTORY_KEEP = 5;

    /** Режим применения правки: предпросмотр, применение по кнопке. */
    public static final String EDIT_MODE_PREVIEW = "preview";
    /** Режим применения правки: применять сразу после генерации. */
    public static final String EDIT_MODE_DIRECT = "direct";
    /** Предпросмотр правки: точечные замены (дифф «было → стало» на местах). */
    public static final String EDIT_PREVIEW_DIFF = "diff";
    /** Предпросмотр правки: весь новый фрагмент целиком под выделением. */
    public static final String EDIT_PREVIEW_FULL = "full";
    public static final int DEFAULT_EDIT_MAX_SELECTION = 4000;
    public static final int HARD_MAX_EDIT_SELECTION = 200000;

    private static final DateTimeFormatter MACRO_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private AisqlCore() {
    }

    // ======================================================================
    //  Пути/настройки
    // ======================================================================

    /** Системное свойство-переопределение каталога настроек (используется в тестах). */
    public static final String CONFIG_DIR_PROPERTY = "aisql.config.dir";

    /** Каталог конфигурации плагина (рядом с ai-tools.json). */
    public static Path configDir() throws DBException {
        try {
            Path cfg;
            String override = System.getProperty(CONFIG_DIR_PROPERTY);
            if (override != null && !override.isBlank()) {
                cfg = Path.of(override.trim());
            } else {
                Path metadata = DBWorkbench.getPlatform().getWorkspace().getMetadataFolder();
                cfg = metadata.resolve(".config");
            }
            if (!Files.exists(cfg)) {
                Files.createDirectories(cfg);
            }
            return cfg;
        } catch (Exception e) {
            throw new DBException("Не удалось определить каталог настроек DBeaver: " + e.getMessage(), e);
        }
    }

    private static final Object SETTINGS_LOCK = new Object();
    private static volatile Properties settingsCache;
    private static volatile long settingsMtime = -1;
    private static volatile long settingsSize = -1;

    /**
     * Настройки плагина. Файл перечитывается только при изменении
     * (mtime + размер), поэтому правки в файле подхватываются без
     * перезапуска DBeaver. Возвращается копия — менять её безопасно.
     */
    public static Properties loadSettings() {
        synchronized (SETTINGS_LOCK) {
            try {
                Path f = configDir().resolve(SETTINGS_FILE);
                if (!Files.exists(f)) {
                    ensureSettingsFileLocked();
                }
                if (Files.exists(f)) {
                    long m = Files.getLastModifiedTime(f).toMillis();
                    long s = Files.size(f);
                    if (settingsCache == null || m != settingsMtime || s != settingsSize) {
                        Properties p = new Properties();
                        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                            p.load(r);
                        }
                        settingsCache = p;
                        settingsMtime = m;
                        settingsSize = s;
                    }
                }
            } catch (Exception e) {
                log.debug("Не удалось прочитать настройки плагина SQL-ИИ", e);
            }
            Properties copy = new Properties();
            if (settingsCache != null) {
                copy.putAll(settingsCache);
            }
            return copy;
        }
    }

    /** Значение настройки (строка) либо {@code def}. */
    public static String getSetting(String key, String def) {
        String v = loadSettings().getProperty(key);
        return v == null ? def : v;
    }

    /** Создаёт файл настроек со значениями по умолчанию, если его ещё нет. */
    public static void ensureSettingsFile() {
        synchronized (SETTINGS_LOCK) {
            try {
                Path f = configDir().resolve(SETTINGS_FILE);
                if (!Files.exists(f)) {
                    ensureSettingsFileLocked();
                }
            } catch (Exception e) {
                log.debug("Не удалось создать файл настроек плагина", e);
            }
        }
    }

    private static void ensureSettingsFileLocked() throws DBException {
        Path f = configDir().resolve(SETTINGS_FILE);
        if (!Files.exists(f)) {
            saveSettings(defaultSettings());
        }
    }

    /** Значения настроек по умолчанию (используются при первом запуске). */
    public static Properties defaultSettings() {
        Properties p = new Properties();
        p.setProperty(KEY_RUNQUERY_STRICT, "true");
        p.setProperty(KEY_WRITE_DISABLE_AUTOCOMMIT, "true");
        p.setProperty(KEY_LIMIT_MAX_ROWS, String.valueOf(DEFAULT_MAX_ROWS));
        p.setProperty(KEY_LIMIT_TIMEOUT, String.valueOf(DEFAULT_TIMEOUT_SEC));
        p.setProperty(KEY_LIMIT_MAX_PAYLOAD, String.valueOf(DEFAULT_MAX_PAYLOAD));
        p.setProperty(KEY_AUDIT_ENABLED, "true");
        p.setProperty(AisqlAudit.KEY_REDACT_LITERALS, "false");
        p.setProperty(AisqlAudit.KEY_MAX_BYTES, String.valueOf(AisqlAudit.DEFAULT_MAX_BYTES));
        p.setProperty(AisqlAudit.KEY_MAX_FILES, String.valueOf(AisqlAudit.DEFAULT_MAX_FILES));
        p.setProperty(ConnectionProfiles.KEY_DEFAULT, ConnectionProfiles.DEFAULT_PROFILE.name());
        p.setProperty(QueryPolicy.KEY_FORBIDDEN, "");
        p.setProperty(MaskingRules.KEY_ENABLED, "true");
        p.setProperty(MaskingRules.KEY_RULES, "");
        p.setProperty(KEY_MACROS_MAX_BYTES, String.valueOf(DEFAULT_MACROS_MAX_BYTES));
        p.setProperty(KEY_EDIT_ENABLED, "true");
        p.setProperty(KEY_EDIT_APPLY_MODE, EDIT_MODE_PREVIEW);
        p.setProperty(KEY_EDIT_PREVIEW_MODE, EDIT_PREVIEW_DIFF);
        p.setProperty(KEY_EDIT_MAX_SELECTION, String.valueOf(DEFAULT_EDIT_MAX_SELECTION));
        p.setProperty(KEY_EDIT_AUDIT, "true");
        return p;
    }

    /** Атомарная запись настроек (через временный файл). */
    public static void saveSettings(Properties p) throws DBException {
        synchronized (SETTINGS_LOCK) {
            try {
                Path dir = configDir();
                Path f = dir.resolve(SETTINGS_FILE);
                Path tmp = dir.resolve(SETTINGS_FILE + ".tmp");
                try (Writer w = new OutputStreamWriter(Files.newOutputStream(tmp,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE),
                        StandardCharsets.UTF_8)) {
                    p.store(w, "AI SQL for DBeaver — настройки плагина (UTF-8)");
                }
                try {
                    Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception atomicFailed) {
                    Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
                }
                settingsCache = new Properties();
                settingsCache.putAll(p);
                settingsMtime = Files.getLastModifiedTime(f).toMillis();
                settingsSize = Files.size(f);
            } catch (Exception e) {
                throw new DBException("Не удалось сохранить настройки плагина: " + e.getMessage(), e);
            }
        }
    }

    /** Сохранить одно значение, не теряя остальные настройки. */
    public static void setSetting(String key, String value) throws DBException {
        Properties p = loadSettings();
        if (value == null) {
            p.remove(key);
        } else {
            p.setProperty(key, value);
        }
        saveSettings(p);
    }

    /** Вариант {@link #saveSettings(Properties)} без проверяемого исключения. */
    public static void saveSettingsSafe(Properties p) {
        try {
            saveSettings(p);
        } catch (DBException e) {
            log.debug("Не удалось сохранить настройки (safe)", e);
        }
    }

    /** Вариант {@link #setSetting(String, String)} без проверяемого исключения. */
    public static void setSettingSafe(String key, String value) {
        try {
            setSetting(key, value);
        } catch (DBException e) {
            log.debug("Не удалось сохранить настройку " + key + " (safe)", e);
        }
    }

    public static boolean getBool(String key, boolean def) {
        String v = loadSettings().getProperty(key);
        if (v == null) {
            return def;
        }
        return Boolean.parseBoolean(v.trim());
    }

    public static int getInt(String key, int def) {
        String v = loadSettings().getProperty(key);
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ======================================================================
    //  Журнал аудита
    // ======================================================================

    /**
     * Запись в журнал аудита (совместимая «короткая» форма).
     */
    public static void audit(
        String function,
        DBCExecutionContext ctx,
        String sql,
        String decision,
        String detail
    ) {
        auditExec(function, ctx, sql, decision, detail, null, "-", -1, -1, -1, null);
    }

    /**
     * Расширенная запись аудита (v2): категория оператора, результат,
     * число строк, размер ответа, длительность, текст ошибки.
     */
    public static void auditExec(
        String function,
        DBCExecutionContext ctx,
        String sql,
        String decision,
        String detail,
        String category,
        String outcome,
        long rows,
        long bytes,
        long durationMs,
        String error
    ) {
        AisqlAudit.Event e = new AisqlAudit.Event();
        e.function = function;
        e.decision = decision;
        e.conn = ctx == null ? "-" : connectionLabel(ctx);
        e.category = category == null ? "" : category;
        e.outcome = outcome == null ? "-" : outcome;
        e.sql = sql;
        e.detail = detail == null ? "" : detail;
        e.error = error;
        e.rows = rows;
        e.bytes = bytes;
        e.durationMs = durationMs;
        AisqlAudit.write(e);
    }

    /**
     * Запись события аудита без контекста выполнения (например, правка
     * выделения ИИ в редакторе). {@code connection} — имя подключения
     * (допускается null, тогда «-»); sql/rows/category не заполняются.
     */
    public static void auditUi(
        String function,
        String connection,
        String decision,
        String detail,
        String outcome,
        long bytes,
        long durationMs,
        String error
    ) {
        AisqlAudit.Event e = new AisqlAudit.Event();
        e.function = function;
        e.decision = decision;
        e.conn = connection == null || connection.isBlank() ? "-" : connection;
        e.outcome = outcome == null ? "-" : outcome;
        e.detail = detail == null ? "" : detail;
        e.error = error;
        e.bytes = bytes;
        e.durationMs = durationMs;
        AisqlAudit.write(e);
    }

    /** Текущий файл журнала аудита (JSONL). */
    public static Path auditFile() throws DBException {
        return AisqlAudit.file();
    }

    // ======================================================================
    //  Доступ к соединению
    // ======================================================================

    public static DBCExecutionContext execContext(AIFunctionContext context) throws DBException {
        AIDatabaseContext dbContext = context == null ? null : context.getContext();
        if (dbContext == null) {
            throw new DBException("Нет активного подключения к базе данных. Подключитесь к БД перед выполнением запросов.");
        }
        DBCExecutionContext executionContext = dbContext.getExecutionContext();
        if (executionContext == null) {
            throw new DBException("Нет активного контекста подключения к базе данных.");
        }
        if (!executionContext.isConnected()) {
            throw new DBException("Соединение с базой данных не активно. Установите соединение.");
        }
        return executionContext;
    }

    /** Имя подключения DBeaver (без сведений о СУБД). */
    public static String connectionName(DBCExecutionContext ctx) {
        try {
            var container = ctx.getDataSource().getContainer();
            String name = container == null ? null : container.getName();
            return name == null ? ctx.getContextName() : name;
        } catch (Exception e) {
            return ctx.getContextName();
        }
    }

    public static String connectionLabel(DBCExecutionContext ctx) {
        try {
            String name = connectionName(ctx);
            String db = null;
            try {
                db = ctx.getDataSource().getInfo() == null ? null
                    : ctx.getDataSource().getInfo().getDatabaseProductName();
            } catch (Exception ignored) {
            }
            return name + (db == null ? "" : " [" + db + "]");
        } catch (Exception e) {
            return ctx.getContextName();
        }
    }

    /** Профиль безопасности текущего подключения. */
    public static ConnectionProfiles.Profile profileOf(DBCExecutionContext ctx) {
        return ConnectionProfiles.resolve(connectionName(ctx));
    }

    /** Правила маскирования для подключения (пусто при профиле FULL). */
    public static List<MaskingRules.Rule> maskingFor(DBCExecutionContext ctx) {
        if (ctx == null) {
            return List.of();
        }
        if (profileOf(ctx) == ConnectionProfiles.Profile.FULL) {
            return List.of();
        }
        if (!getBool(MaskingRules.KEY_ENABLED, true)) {
            return List.of();
        }
        return MaskingRules.loadRules();
    }

    // ======================================================================
    //  Аргументы
    // ======================================================================

    public static String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    public static int intArg(Map<String, Object> args, String key, int def) {
        Object v = args == null ? null : args.get(key);
        if (v == null) {
            return def;
        }
        // JSON-числа могут прийти как Number (в т.ч. Double вида 200.0)
        if (v instanceof Number n) {
            try {
                return (int) Math.min(Math.max(n.longValue(), Integer.MIN_VALUE), Integer.MAX_VALUE);
            } catch (Exception e) {
                return def;
            }
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            try {
                return (int) Double.parseDouble(String.valueOf(v).trim());
            } catch (NumberFormatException e2) {
                return def;
            }
        }
    }

    public static int maxRows(Map<String, Object> args) {
        int def = getInt(KEY_LIMIT_MAX_ROWS, DEFAULT_MAX_ROWS);
        int v = intArg(args, "maxRows", def);
        if (v <= 0) {
            v = def;
        }
        return Math.min(v, HARD_MAX_ROWS);
    }

    public static int timeoutSec(Map<String, Object> args) {
        int v = intArg(args, "timeout", getInt(KEY_LIMIT_TIMEOUT, DEFAULT_TIMEOUT_SEC));
        if (v <= 0) {
            v = DEFAULT_TIMEOUT_SEC;
        }
        return Math.min(v, 3600);
    }

    // ======================================================================
    //  Исполнение SQL
    // ======================================================================

    /** Результат исполнения одного SQL-оператора. */
    public static final class ExecResult {
        public String text = "";
        public boolean hasResultSet;
        public long affectedRows = -1;
        /** Число строк результата, -1 — результата нет. */
        public int rowCount = -1;
        /** Результат был усечён (maxRows или limit.maxPayloadChars). */
        public boolean truncated;
        /** Полный размер текстового результата до усечения по payload. */
        public long payloadBytes;
        public Throwable error;
    }

    /**
     * Выполняет SQL на текущем соединении.
     * Ошибки СУБД возвращаются в {@link ExecResult#error}/{@link ExecResult#text},
     * не бросаются (кроме неожиданных внутренних сбоев).
     */
    public static ExecResult execute(
        DBRProgressMonitor monitor,
        DBCExecutionContext ctx,
        String sql,
        int maxRows,
        int maxCellLength,
        int timeoutSec
    ) {
        ExecResult res = new ExecResult();
        long start = System.currentTimeMillis();
        // Тип оператора выбирает классификатор на основе парсера DBeaver:
        // одиночный read-only оператор — QUERY, всё остальное (DDL, скрипты) — SCRIPT.
        SqlClassifier.ScriptInfo info = SqlClassifier.analyze(ctx.getDataSource(), sql);
        DBCStatementType stType = info.isSingle() && info.isReadOnly()
            ? DBCStatementType.QUERY
            : DBCStatementType.SCRIPT;
        List<MaskingRules.Rule> masks = maskingFor(ctx);
        try (DBCSession session = ctx.openSession(monitor, DBCExecutionPurpose.USER, "Запрос ИИ")) {
            try (DBCStatement statement = session.prepareStatement(stType, sql, false, false, false)) {
                try {
                    statement.setStatementTimeout(timeoutSec);
                } catch (Exception ignored) {
                    // поддерживается не всеми драйверами
                }
                boolean hasResultSet;
                try {
                    hasResultSet = statement.executeStatement();
                } catch (Exception e) {
                    res.error = e;
                    res.text = "Ошибка выполнения: " + e.getMessage();
                    return res;
                }
                long duration = System.currentTimeMillis() - start;
                if (!hasResultSet) {
                    long updated = -1;
                    try {
                        updated = statement.getUpdateRowCount();
                    } catch (Exception ignored) {
                    }
                    res.affectedRows = updated;
                    res.text = updated >= 0
                        ? "Оператор выполнен успешно. Затронуто строк: " + updated + ". Время: " + duration + " мс."
                        : "Оператор выполнен успешно. Время: " + duration + " мс.";
                    return res;
                }
                res.hasResultSet = true;
                try (DBCResultSet resultSet = statement.openResultSet()) {
                    FormatOutput fo = formatResultSet(resultSet, sql, maxRows, maxCellLength, duration, masks);
                    res.rowCount = fo.rows;
                    res.truncated = fo.truncated;
                    res.payloadBytes = fo.text.length();
                    res.text = truncatePayload(fo.text);
                }
                return res;
            }
        } catch (Exception e) {
            res.error = e;
            res.text = "Ошибка выполнения: " + e.getMessage();
            return res;
        }
    }

    /** Усечение текста результата до limit.maxPayloadChars (<=0 — без ограничения). */
    public static String truncatePayload(String text) {
        int limit = getInt(KEY_LIMIT_MAX_PAYLOAD, DEFAULT_MAX_PAYLOAD);
        if (limit <= 0 || text == null || text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit)
            + "\n... [вывод усечён до " + limit
            + " символов; настройка limit.maxPayloadChars]";
    }

    /** Отформатированный результат (текст + метрики). */
    public static final class FormatOutput {
        public String text = "";
        public int rows;
        public boolean truncated;
    }

    public static FormatOutput formatResultSet(
        DBCResultSet resultSet,
        String sql,
        int maxRows,
        int maxCellLength,
        long durationMs,
        List<MaskingRules.Rule> masks
    ) throws DBCException {
        FormatOutput out = new FormatOutput();
        DBCResultSetMetaData meta = resultSet.getMeta();
        List<? extends DBCAttributeMetaData> attributes = meta.getAttributes();
        int columnCount = attributes.size();

        String[] maskReplacement = new String[columnCount];
        if (masks != null && !masks.isEmpty()) {
            for (int i = 0; i < columnCount; i++) {
                DBCAttributeMetaData attr = attributes.get(i);
                maskReplacement[i] = MaskingRules.replacementFor(masks,
                    attr.getEntityName(), attr.getName(), attr.getLabel());
            }
        }

        StringBuilder header = new StringBuilder();
        for (int i = 0; i < columnCount; i++) {
            if (i > 0) {
                header.append(" | ");
            }
            String label = attributes.get(i).getLabel();
            if (label == null || label.isEmpty()) {
                label = attributes.get(i).getName();
            }
            header.append(label);
        }

        int rows = 0;
        boolean truncated = false;
        StringBuilder body = new StringBuilder();
        while (resultSet.nextRow()) {
            if (rows >= maxRows) {
                truncated = true;
                break;
            }
            if (rows > 0) {
                body.append('\n');
            }
            for (int i = 0; i < columnCount; i++) {
                if (i > 0) {
                    body.append(" | ");
                }
                if (maskReplacement[i] != null) {
                    body.append(maskReplacement[i]);
                    continue;
                }
                Object value;
                try {
                    value = resultSet.getAttributeValue(i);
                } catch (Exception e) {
                    value = "<ошибка: " + e.getMessage() + ">";
                }
                body.append(formatValue(value, maxCellLength));
            }
            rows++;
        }

        StringBuilder sb = new StringBuilder(body.length() + 512);
        sb.append("Результат запроса: ").append(oneLine(sql, 500)).append('\n');
        sb.append("Получено строк: ").append(rows);
        if (truncated) {
            sb.append(" (обрезано на maxRows=").append(maxRows).append(")");
        }
        sb.append(". Время: ").append(durationMs).append(" мс.\n");
        if (columnCount > 0) {
            sb.append(header).append('\n');
            sb.append("---\n");
        }
        sb.append(body);
        if (rows == 0) {
            sb.append("(нет строк)");
        }
        out.text = sb.toString();
        out.rows = rows;
        out.truncated = truncated;
        return out;
    }

    public static String formatValue(Object value, int maxCellLength) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof DBDContent) {
            try {
                long len = ((DBDContent) value).getContentLength();
                return "<содержимое, длина=" + len + ">";
            } catch (Exception e) {
                return "<содержимое>";
            }
        }
        if (value instanceof byte[]) {
            return "<двоичные данные, " + ((byte[]) value).length + " байт>";
        }
        if (value instanceof Character || value instanceof CharSequence) {
            return shorten(String.valueOf(value), maxCellLength);
        }
        String str;
        try {
            str = String.valueOf(value);
        } catch (Exception e) {
            str = "<значение типа " + value.getClass().getSimpleName() + ">";
        }
        return shorten(str, maxCellLength);
    }

    public static String shorten(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String s = value.replace('\r', ' ').replace('\n', ' ');
        if (s.length() > maxLength) {
            s = s.substring(0, maxLength) + "... [обрезано]";
        }
        return s;
    }

    public static String oneLine(String value) {
        return oneLine(value, 2000);
    }

    public static String oneLine(String value, int max) {
        if (value == null) {
            return null;
        }
        String s = value.replace('\r', ' ').replace('\n', ' ').trim();
        if (s.length() > max) {
            s = s.substring(0, max) + "...";
        }
        return s;
    }

    // ======================================================================
    //  Транзакции
    // ======================================================================

    /**
     * Если запрос изменяющий и включена настройка, отключает autocommit,
     * чтобы пользователь мог откатить изменения в DBeaver.
     *
     * @return true, если autocommit был отключён этим вызовом
     */
    public static boolean disableAutoCommitForWrite(
        DBRProgressMonitor monitor,
        DBCExecutionContext ctx,
        String sql
    ) {
        SqlClassifier.ScriptInfo info = SqlClassifier.analyze(ctx.getDataSource(), sql);
        return disableAutoCommitIfNeeded(monitor, ctx, info);
    }

    /** Вариант без повторного разбора SQL (когда классификация уже есть). */
    public static boolean disableAutoCommitIfNeeded(
        DBRProgressMonitor monitor,
        DBCExecutionContext ctx,
        SqlClassifier.ScriptInfo info
    ) {
        if (!getBool(KEY_WRITE_DISABLE_AUTOCOMMIT, true)) {
            return false;
        }
        if (!info.needsTxProtection()) {
            return false;
        }
        try {
            DBCTransactionManager txn = DBUtils.getTransactionManager(ctx);
            if (txn != null && txn.isSupportsTransactions() && txn.isAutoCommit()) {
                txn.setAutoCommit(monitor, false);
                return true;
            }
        } catch (Exception e) {
            log.debug("Не удалось отключить autocommit для изменяющего запроса", e);
        }
        return false;
    }

    /**
     * Ключевые слова, способные изменять данные, схему или права.
     *
     * <p>ВНИМАНИЕ: это лишь эвристика (совместимость со старым кодом).
     * Основная классификация — {@link SqlClassifier} на основе парсера
     * DBeaver. Ни та, ни другая не является средством защиты: настоящая
     * защита — права учётной записи СУБД и подтверждение пользователя.</p>
     */
    private static final Set<String> WRITE_KEYWORDS = Set.of(
        "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "REPLACE", "TRUNCATE",
        "CREATE", "ALTER", "DROP", "RENAME", "COMMENT", "GRANT", "REVOKE",
        "COPY", "CALL", "EXEC", "EXECUTE", "DO", "DECLARE", "LOCK",
        "VACUUM", "ANALYZE", "REINDEX", "CLUSTER", "REFRESH", "IMPORT", "LOAD", "SET"
    );

    public static boolean isWriteStatement(String sql) {
        return containsWriteKeyword(sql);
    }

    /** Содержит ли запрос токен изменяющего ключевого слова. */
    public static boolean containsWriteKeyword(String sql) {
        for (String t : tokenize(sql)) {
            if (WRITE_KEYWORDS.contains(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Разбирает SQL на слова-токены, пропуская комментарии, строковые литералы
     * ({@code '...'}), кавычки-идентификаторы ({@code "..."}, {@code `...`},
     * {@code [..]}), dollar-quoted блоки ({@code $$..$$}, {@code $tag$..$tag$}).
     * Возвращает токены в верхнем регистре.
     */
    public static List<String> tokenize(String sql) {
        List<String> tokens = new ArrayList<>();
        if (sql == null) {
            return tokens;
        }
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int nl = sql.indexOf('\n', i);
                i = nl < 0 ? n : nl + 1;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '"' || c == '`') {
                char q = c;
                i++;
                while (i < n) {
                    if (sql.charAt(i) == q) {
                        if (i + 1 < n && sql.charAt(i + 1) == q) {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == '[') {
                int end = sql.indexOf(']', i + 1);
                i = end < 0 ? n : end + 1;
                continue;
            }
            if (c == '$') {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
                    j++;
                }
                if (j < n && sql.charAt(j) == '$') {
                    String tag = sql.substring(i, j + 1);
                    int end = sql.indexOf(tag, j + 1);
                    i = end < 0 ? n : end + tag.length();
                    continue;
                }
                i++;
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (Character.isLetterOrDigit(d) || d == '_' || d == '$') {
                        i++;
                    } else {
                        break;
                    }
                }
                tokens.add(sql.substring(start, i).toUpperCase(Locale.ROOT));
                continue;
            }
            i++;
        }
        return tokens;
    }

    /** Первое слово-токен (ключевое слово) запроса либо пустая строка. */
    public static String firstKeyword(String sql) {
        List<String> tokens = tokenize(sql);
        return tokens.isEmpty() ? "" : tokens.get(0);
    }

    /**
     * Совместимость (до v1.0): проверка «один оператор» по символам.
     * Новый код использует {@link SqlClassifier#analyze}.
     */
    public static boolean isSingleStatement(String sql) {
        int i = 0;
        int n = sql.length();
        int statements = 0;
        boolean hasContent = false;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int nl = sql.indexOf('\n', i);
                i = nl < 0 ? n : nl + 1;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (c == '\'') {
                i++;
                while (i < n) {
                    char d = sql.charAt(i);
                    if (d == '\'') {
                        if (i + 1 < n && sql.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                hasContent = true;
                continue;
            }
            if (c == '"') {
                i++;
                while (i < n && sql.charAt(i) != '"') {
                    i++;
                }
                i++;
                hasContent = true;
                continue;
            }
            if (c == ';') {
                if (hasContent) {
                    statements++;
                }
                hasContent = false;
                i++;
                continue;
            }
            if (!Character.isWhitespace(c)) {
                hasContent = true;
            }
            i++;
        }
        if (hasContent) {
            statements++;
        }
        return statements <= 1;
    }

    // ======================================================================
    //  Макросы
    // ======================================================================

    public static Path macrosDir() throws DBException {
        try {
            Path dir = configDir().resolve(MACROS_DIR);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            return dir;
        } catch (Exception e) {
            throw new DBException("Не удалось получить каталог макросов: " + e.getMessage(), e);
        }
    }

    /**
     * Проверка идентификатора макроса по белому списку:
     * латиница/цифры/точка/подчёркивание/дефис, начинается с буквы или цифры,
     * не длиннее {@link #MAX_MACRO_ID_LEN}, без «..».
     *
     * @return нормализованный идентификатор либо {@code null}, если он недопустим
     */
    public static String sanitizeId(String id) {
        if (id == null) {
            return null;
        }
        String s = id.trim();
        if (s.isEmpty() || s.length() > MAX_MACRO_ID_LEN) {
            return null;
        }
        if (!s.matches("[A-Za-z0-9][A-Za-z0-9_.\\-]*") || s.contains("..")) {
            return null;
        }
        return s;
    }

    /** Список идентификаторов макросов. */
    public static List<String> listMacroIds() throws DBException {
        List<String> ids = new ArrayList<>();
        try {
            Path dir = macrosDir();
            if (Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    stream.filter(p -> Files.isRegularFile(p)
                            && p.getFileName().toString().endsWith(".sql"))
                        .forEach(p -> {
                            String name = p.getFileName().toString();
                            ids.add(name.substring(0, name.length() - 4));
                        });
                }
            }
        } catch (Exception e) {
            throw new DBException("Не удалось прочитать список макросов: " + e.getMessage(), e);
        }
        ids.sort(String::compareToIgnoreCase);
        return ids;
    }

    /** Описание макроса (заголовок и пояснение). */
    public static String[] readMacroMeta(String id) {
        String title = id;
        String description = "";
        try {
            Path f = macrosDir().resolve(id + ".meta");
            if (Files.exists(f)) {
                Properties p = new Properties();
                try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    p.load(r);
                }
                title = p.getProperty("title", id);
                description = p.getProperty("description", "");
            }
        } catch (Exception e) {
            log.debug("Не удалось прочитать метаданные макроса " + id, e);
        }
        return new String[] { title, description };
    }

    public static String readMacroSql(String id) throws DBException {
        try {
            Path f = macrosDir().resolve(id + ".sql");
            if (!Files.exists(f)) {
                throw new DBException("Макрос '" + id + "' не найден.");
            }
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (DBException e) {
            throw e;
        } catch (Exception e) {
            throw new DBException("Не удалось прочитать макрос '" + id + "': " + e.getMessage(), e);
        }
    }

    public static void saveMacro(String id, String title, String description, String sql) throws DBException {
        if (sql == null || sql.isBlank()) {
            throw new DBException("Пустой текст макроса '" + id + "': сохранять нечего.");
        }
        int maxBytes = getInt(KEY_MACROS_MAX_BYTES, DEFAULT_MACROS_MAX_BYTES);
        byte[] bytes = sql.getBytes(StandardCharsets.UTF_8);
        if (maxBytes > 0 && bytes.length > maxBytes) {
            throw new DBException("Размер макроса '" + id + "' (" + bytes.length
                + " байт) превышает предел macros.maxBytes=" + maxBytes + " байт.");
        }
        try {
            Path dir = macrosDir();
            Path sqlFile = dir.resolve(id + ".sql");
            if (Files.exists(sqlFile)) {
                saveMacroHistory(dir, id, sqlFile);
            }
            writeAtomically(sqlFile, sql);
            Properties p = new Properties();
            p.setProperty("title", title == null ? id : title);
            p.setProperty("description", description == null ? "" : description);
            Path metaTmp = dir.resolve(id + ".meta.tmp");
            try (Writer w = new OutputStreamWriter(Files.newOutputStream(metaTmp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE),
                    StandardCharsets.UTF_8)) {
                p.store(w, "AI SQL macro");
            }
            Path meta = dir.resolve(id + ".meta");
            try {
                Files.move(metaTmp, meta, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception atomicFailed) {
                Files.move(metaTmp, meta, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (DBException e) {
            throw e;
        } catch (Exception e) {
            throw new DBException("Не удалось сохранить макрос '" + id + "': " + e.getMessage(), e);
        }
    }

    private static void writeAtomically(Path target, String text) throws Exception {
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception atomicFailed) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Сохраняет предыдущую версию макроса в history/ (не более MACRO_HISTORY_KEEP версий). */
    private static void saveMacroHistory(Path macrosDir, String id, Path currentSqlFile) {
        try {
            Path hist = macrosDir.resolve("history");
            Files.createDirectories(hist);
            String stamp = MACRO_TS.format(LocalDateTime.now());
            Files.copy(currentSqlFile, hist.resolve(id + "." + stamp + ".sql"),
                StandardCopyOption.REPLACE_EXISTING);
            List<Path> copies = new ArrayList<>();
            try (var stream = Files.list(hist)) {
                stream.filter(p -> {
                    String n = p.getFileName().toString();
                    return Files.isRegularFile(p) && n.startsWith(id + ".") && n.endsWith(".sql");
                }).forEach(copies::add);
            }
            copies.sort((a, b) ->
                a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()));
            for (int i = 0; i < copies.size() - MACRO_HISTORY_KEEP; i++) {
                Files.deleteIfExists(copies.get(i));
            }
        } catch (Exception e) {
            log.debug("Не удалось сохранить историю макроса " + id, e);
        }
    }

    public static boolean deleteMacro(String id) throws DBException {
        try {
            Path dir = macrosDir();
            boolean a = Files.deleteIfExists(dir.resolve(id + ".sql"));
            Files.deleteIfExists(dir.resolve(id + ".meta"));
            return a;
        } catch (Exception e) {
            throw new DBException("Не удалось удалить макрос '" + id + "': " + e.getMessage(), e);
        }
    }

    // ======================================================================

    public static AIFunctionResult info(String text) {
        return new AIFunctionResult(AIFunctionType.INFORMATION, text);
    }

    public static AIFunctionResult action(String text) {
        return new AIFunctionResult(AIFunctionType.ACTION, text);
    }

    private static String nz(String s) {
        return s == null ? "-" : s;
    }
}
