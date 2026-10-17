/*
 * AI SQL for DBeaver — журнал аудита v2.
 *
 * Формат: JSON Lines (ai-sql-audit.jsonl), одна запись — одна строка JSON.
 * Записи связаны цепочкой хешей SHA-256: hash = SHA256(prev + "\n" + json),
 * что позволяет обнаружить удаление/правку строк задним числом (кроме
 * случаев, когда злоумышленник пересчитал всю цепочку — для защиты журнала
 * нужны внешние средства).
 *
 * Пример записи:
 *   {"ts":"2026-10-16T12:00:00.123456Z","user":"demo","app":"1.0.0",
 *    "function":"db_executeSql","decision":"ask","conn":"sakila [PostgreSQL]",
 *    "category":"DML","outcome":"OK","rows":1,"bytes":-1,"ms":12,
 *    "detail":"выполнение после подтверждения","sql":"UPDATE ...",
 *    "prev":"genesis","hash":"..."}
 *
 * Ротация: audit.maxBytes (по умолчанию 5 МБ), audit.maxFiles (по умолчанию 5,
 * включая текущий файл); ротации: ai-sql-audit.jsonl.1 ... .N.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Запись журнала аудита (JSONL + цепочка хешей + ротация).
 */
public final class AisqlAudit {

    public static final String FILE_NAME = "ai-sql-audit.jsonl";

    public static final String KEY_REDACT_LITERALS = "audit.redactLiterals";
    public static final String KEY_MAX_BYTES = "audit.maxBytes";
    public static final String KEY_MAX_FILES = "audit.maxFiles";

    public static final int DEFAULT_MAX_BYTES = 5 * 1024 * 1024;
    public static final int DEFAULT_MAX_FILES = 5;

    private static final String GENESIS = "genesis";
    private static final Pattern STRING_LITERAL_RE = Pattern.compile("'(?:[^']|'')*'", Pattern.DOTALL);
    private static final Pattern HASH_RE = Pattern.compile("\"hash\":\"([0-9a-f]{64})\"");
    private static final Pattern PREV_RE = Pattern.compile("\"prev\":\"((?:[0-9a-f]{64})|genesis)\"");
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final Object LOCK = new Object();
    private static String lastHash;

    private AisqlAudit() {
    }

    /** Одна запись журнала. Поля со значением по умолчанию можно не заполнять. */
    public static final class Event {
        public String function = "-";
        public String decision = "-";
        public String conn = "-";
        public String category = "";
        public String outcome = "-";
        public String sql;
        public String detail = "";
        public String error;
        public long rows = -1;
        public long bytes = -1;
        public long durationMs = -1;
    }

    /** Путь к текущему файлу журнала. */
    public static Path file() throws org.jkiss.dbeaver.DBException {
        return AisqlCore.configDir().resolve(FILE_NAME);
    }

    /** Записывает событие (учитывает настройки включения/редактирования/ротации). */
    public static void write(Event e) {
        if (!AisqlCore.getBool(AisqlCore.KEY_AUDIT_ENABLED, true)) {
            return;
        }
        try {
            Properties settings = AisqlCore.loadSettings();
            boolean redact = Boolean.parseBoolean(
                settings.getProperty(KEY_REDACT_LITERALS, "false").trim());
            int maxBytes = parseInt(settings.getProperty(KEY_MAX_BYTES), DEFAULT_MAX_BYTES);
            int maxFiles = parseInt(settings.getProperty(KEY_MAX_FILES), DEFAULT_MAX_FILES);

            Path f = file();
            synchronized (LOCK) {
                rotateIfNeeded(f, maxBytes, maxFiles);
                String prev = lastHash != null ? lastHash : (lastHash = loadLastHash(f, maxFiles));
                String content = buildJson(e, prev, redact);
                String hash = sha256(prev + "\n" + content);
                String line = content.substring(0, content.length() - 1)
                    + ",\"hash\":\"" + hash + "\"}\n";
                Files.writeString(f, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                lastHash = hash;
            }
        } catch (Exception ex) {
            AisqlCorePlugin.log().debug("Не удалось записать журнал аудита", ex);
        }
    }

    /**
     * Проверка целостности цепочки в одном файле:
     * hash = SHA256(prev + "\n" + строка без поля hash) и prev ссылается
     * на hash предыдущей строки.
     */
    public static boolean verify(Path f) {
        try {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            String prevHash = null;
            for (String line : lines) {
                if (line.isEmpty()) {
                    continue;
                }
                String content = stripHash(line);
                String statedPrev = field(content, PREV_RE);
                if (statedPrev == null) {
                    return false;
                }
                String statedHash = field(line, HASH_RE);
                if (statedHash == null) {
                    return false;
                }
                if (!sha256(statedPrev + "\n" + content).equals(statedHash)) {
                    return false;
                }
                if (prevHash != null && !statedPrev.equals(prevHash)) {
                    return false;
                }
                prevHash = statedHash;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ----------------------------------------------------------------------

    private static void rotateIfNeeded(Path f, int maxBytes, int maxFiles) throws IOException {
        if (maxBytes <= 0 || !Files.exists(f) || Files.size(f) < maxBytes) {
            return;
        }
        if (maxFiles <= 1) {
            Files.deleteIfExists(f);
            return;
        }
        String base = f.getFileName().toString();
        Files.deleteIfExists(f.resolveSibling(base + "." + (maxFiles - 1)));
        for (int i = maxFiles - 2; i >= 1; i--) {
            Path src = f.resolveSibling(base + "." + i);
            if (Files.exists(src)) {
                Files.move(src, f.resolveSibling(base + "." + (i + 1)),
                    StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(f, f.resolveSibling(base + ".1"), StandardCopyOption.REPLACE_EXISTING);
    }

    private static String loadLastHash(Path f, int maxFiles) {
        String h = hashFromTail(f);
        if (h != null) {
            return h;
        }
        String base = f.getFileName().toString();
        for (int i = 1; i < Math.max(2, maxFiles); i++) {
            h = hashFromTail(f.resolveSibling(base + "." + i));
            if (h != null) {
                return h;
            }
        }
        return GENESIS;
    }

    private static String hashFromTail(Path f) {
        try {
            if (!Files.exists(f)) {
                return null;
            }
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0; i--) {
                Matcher m = HASH_RE.matcher(lines.get(i));
                if (m.find()) {
                    return m.group(1);
                }
            }
        } catch (Exception ignored) {
            // журнал недоступен — начинаем цепочку заново
        }
        return null;
    }

    private static String buildJson(Event e, String prev, boolean redact) {
        StringBuilder sb = new StringBuilder(320);
        sb.append('{');
        appendString(sb, "ts", Instant.now().toString(), true);
        appendString(sb, "user", System.getProperty("user.name", "?"), false);
        appendString(sb, "app", AisqlCorePlugin.version(), false);
        appendString(sb, "function", nz(e.function), false);
        appendString(sb, "decision", nz(e.decision), false);
        appendString(sb, "conn", nz(e.conn), false);
        if (e.category != null && !e.category.isEmpty()) {
            appendString(sb, "category", e.category, false);
        }
        appendString(sb, "outcome", nz(e.outcome), false);
        sb.append(",\"rows\":").append(e.rows);
        sb.append(",\"bytes\":").append(e.bytes);
        sb.append(",\"ms\":").append(e.durationMs);
        if (e.error != null && !e.error.isEmpty()) {
            appendString(sb, "error", e.error, false);
        }
        if (e.detail != null && !e.detail.isEmpty()) {
            appendString(sb, "detail", e.detail, false);
        }
        if (e.sql != null && !e.sql.isEmpty()) {
            String sql = redact ? redactLiterals(e.sql) : e.sql;
            appendString(sb, "sql", sql, false);
        }
        appendString(sb, "prev", prev, false);
        sb.append('}');
        return sb.toString();
    }

    private static String redactLiterals(String sql) {
        try {
            return STRING_LITERAL_RE.matcher(sql).replaceAll(Matcher.quoteReplacement("'?'"));
        } catch (Exception e) {
            return sql;
        }
    }

    private static void appendString(StringBuilder sb, String name, String value, boolean first) {
        if (!first) {
            sb.append(',');
        }
        sb.append('"').append(name).append("\":\"");
        escape(sb, value);
        sb.append('"');
    }

    private static void escape(StringBuilder sb, String value) {
        if (value == null) {
            return;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
    }

    /** Убирает завершающее поле ,"hash":"..."} и возвращает исходный content. */
    private static String stripHash(String line) {
        int idx = line.lastIndexOf(",\"hash\":\"");
        if (idx < 0 || !line.endsWith("}")) {
            return line;
        }
        return line.substring(0, idx) + "}";
    }

    private static String field(String text, Pattern p) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String sha256(String text) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(text.getBytes(StandardCharsets.UTF_8));
        char[] out = new char[d.length * 2];
        for (int i = 0; i < d.length; i++) {
            int v = d[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    private static int parseInt(String value, int def) {
        if (value == null) {
            return def;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String nz(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }
}
