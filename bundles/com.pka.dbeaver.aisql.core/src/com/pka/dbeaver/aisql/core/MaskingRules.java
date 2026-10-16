/*
 * AI SQL for DBeaver — маскирование данных в результатах запросов.
 *
 * Настройки (ai-sql-plugin.properties):
 *   mask.enabled=true
 *   mask.rules=customers.email=***@***; users.phone=+7-000-000-00-00; *.password=***
 *
 * Синтаксис правила: <таблица>.<колонка>=<замена>
 *   - таблица может быть точным именем, schema.table или маской (*, ?);
 *   - колонка — имя или метка столбца в результате, поддерживаются маски;
 *   - без «=» значение по умолчанию — ***.
 *
 * Маскирование применяется при профилях READ_ONLY и STANDARD и отключается
 * при профиле FULL.
 *
 * Лицензия: Apache-2.0
 */
package com.pka.dbeaver.aisql.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Правила маскирования значений отдельных колонок.
 */
public final class MaskingRules {

    public static final String KEY_ENABLED = "mask.enabled";
    public static final String KEY_RULES = "mask.rules";
    public static final String DEFAULT_REPLACEMENT = "***";

    /** Одно правило маскирования. */
    public record Rule(String table, String column, String replacement) {
    }

    private MaskingRules() {
    }

    /** Включено ли маскирование и есть ли вообще правила. */
    public static boolean enabled() {
        if (!AisqlCore.getBool(KEY_ENABLED, true)) {
            return false;
        }
        return !loadRules().isEmpty();
    }

    /** Загружает и разбирает правила из настроек. */
    public static List<Rule> loadRules() {
        return parse(AisqlCore.loadSettings().getProperty(KEY_RULES, ""));
    }

    /** Разбор строки правил (используется также в тестах). */
    public static List<Rule> parse(String raw) {
        List<Rule> rules = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return rules;
        }
        for (String chunk : raw.split("[;\\n]")) {
            String entry = chunk.trim();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            int eq = entry.indexOf('=');
            String left;
            String replacement;
            if (eq >= 0) {
                left = entry.substring(0, eq).trim();
                replacement = entry.substring(eq + 1).trim();
            } else {
                left = entry;
                replacement = DEFAULT_REPLACEMENT;
            }
            if (left.isEmpty()) {
                continue;
            }
            if (replacement.isEmpty()) {
                replacement = DEFAULT_REPLACEMENT;
            }
            String table;
            String column;
            int dot = left.lastIndexOf('.');
            if (dot > 0 && dot < left.length() - 1) {
                table = left.substring(0, dot).trim();
                column = left.substring(dot + 1).trim();
            } else {
                table = "*";
                column = left;
            }
            if (column.isEmpty()) {
                continue;
            }
            if (table.isEmpty()) {
                table = "*";
            }
            rules.add(new Rule(table, column, replacement));
        }
        return rules;
    }

    /**
     * Возвращает значение-замену для колонки результата либо {@code null},
     * если ни одно правило не подошло.
     */
    public static String replacementFor(List<Rule> rules, String entityName,
                                        String columnName, String columnLabel) {
        if (rules == null || rules.isEmpty()) {
            return null;
        }
        for (Rule r : rules) {
            if (!matchesTable(r.table(), entityName)) {
                continue;
            }
            boolean byName = columnName != null && Globs.match(r.column(), columnName);
            boolean byLabel = columnLabel != null && Globs.match(r.column(), columnLabel);
            if (byName || byLabel) {
                return r.replacement();
            }
        }
        return null;
    }

    private static boolean matchesTable(String tablePattern, String entityName) {
        if ("*".equals(tablePattern)) {
            return true;
        }
        if (entityName == null) {
            return false;
        }
        return Globs.matchQualified(tablePattern, entityName);
    }
}
