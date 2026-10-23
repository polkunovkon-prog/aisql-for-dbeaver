/*
 * AI SQL for DBeaver — whitelist таблиц для подключения.
 *
 * Перенос принципа из статьи 5 («Защита сгенерированного SQL: синтаксическое
 * дерево + whitelist таблиц»): запрос проверяется по AST, набор таблиц берётся
 * из дерева разбора (SqlAstValidator.tables), а не из текста запроса.
 *
 * Настройка policy.allowedTables — правила по маске имени подключения:
 *   policy.allowedTables.<маска>=schema1.*,schema2.table1,…
 *   policy.allowedTables.*=public.*         # для всех подключений
 *
 * Пустое значение или отсутствие правила — ограничения нет (поведение
 * сохраняется). Если правило задано, обращение к таблице вне списка
 * отклоняется ДО выполнения (fail-closed).
 *
 * Класс без UI/OSGi — покрыт офлайн-тестами (TableWhitelistTest).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * Белый список таблиц, разрешённых для чтения на подключении.
 */
public final class TableWhitelist {

    /** Префикс ключей настроек: policy.allowedTables.&lt;маска подключения&gt;. */
    public static final String KEY_PREFIX = "policy.allowedTables.";

    private TableWhitelist() {
    }

    /**
     * Возвращает список разрешённых шаблонов для подключения либо пустой
     * список, если ограничение не задано (тогда проверка не выполняется).
     */
    public static List<String> patternsFor(String connectionName) {
        Properties p = AisqlCore.loadSettings();
        String raw = resolveRaw(p, connectionName);
        return parse(raw);
    }

    /**
     * Проверяет таблицы запроса по белому списку.
     *
     * @param connectionName имя подключения
     * @param tables         таблицы из дерева разбора (нижний регистр допустим)
     * @return список нарушений; пустой — проверка пройдена или ограничения нет
     */
    public static List<String> violations(String connectionName, Set<String> tables) {
        List<String> patterns = patternsFor(connectionName);
        List<String> problems = new ArrayList<>();
        if (patterns.isEmpty() || tables == null || tables.isEmpty()) {
            return problems;
        }
        Set<String> bad = new TreeSet<>();
        for (String t : tables) {
            if (t == null || t.isBlank()) {
                continue;
            }
            if (!matchesAny(patterns, t)) {
                bad.add(t);
            }
        }
        if (!bad.isEmpty()) {
            problems.add("Таблицы вне белого списка подключения «" + connectionName + "»: "
                + String.join(", ", bad) + ". Разрешено: " + String.join(", ", patterns)
                + " (настройка policy.allowedTables).");
        }
        return problems;
    }

    /** Разбор строки шаблонов: через запятую, точку с запятой или перевод строки. */
    public static List<String> parse(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String chunk : raw.split("[,;\\n]")) {
            String item = chunk.trim().toLowerCase(Locale.ROOT);
            if (item.isEmpty() || item.startsWith("#")) {
                continue;
            }
            out.add(item);
        }
        return out;
    }

    /** Таблица подходит хотя бы под один шаблон? Учитывается schema.table и просто table. */
    public static boolean matchesAny(List<String> patterns, String table) {
        String t = table == null ? "" : table.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return true;
        }
        String simple = t.contains(".") ? t.substring(t.lastIndexOf('.') + 1) : t;
        for (String pat : patterns) {
            if ("*".equals(pat) || "*.*".equals(pat)) {
                return true;
            }
            if (Globs.match(pat, t) || Globs.match(pat, simple)) {
                return true;
            }
            // Шаблон «schema.*» покрывает и «schema.table».
            if (pat.endsWith(".*") && t.startsWith(pat.substring(0, pat.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    private static String resolveRaw(Properties p, String connectionName) {
        if (connectionName != null && !connectionName.isBlank()) {
            String direct = p.getProperty(KEY_PREFIX + connectionName);
            if (direct != null) {
                return direct;
            }
            List<String> keys = new ArrayList<>(p.stringPropertyNames());
            Collections.sort(keys);
            for (String key : keys) {
                if (!key.startsWith(KEY_PREFIX)) {
                    continue;
                }
                String mask = key.substring(KEY_PREFIX.length());
                if (mask.isEmpty() || mask.equals(connectionName)) {
                    continue;
                }
                if (Globs.match(mask, connectionName)) {
                    return p.getProperty(key);
                }
            }
        }
        return p.getProperty(KEY_PREFIX + "*");
    }
}
