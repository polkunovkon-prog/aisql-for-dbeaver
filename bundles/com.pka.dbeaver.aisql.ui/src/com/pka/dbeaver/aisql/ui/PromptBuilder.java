/*
 * AI SQL for DBeaver — построение текста запроса к ИИ для функций
 * «выделил → спросил» (SQL-редактор и таблица результатов).
 *
 * Лицензия: Apache-2.0
 */
package com.pka.dbeaver.aisql.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Формирует готовый промпт для ИИ-чата на основе выделенного текста
 * или выделенных данных таблицы результатов.
 */
public final class PromptBuilder {

    public static final int MAX_QUOTE_CHARS = 4000;
    public static final int MAX_LINE_CHARS = 500;

    private PromptBuilder() {
    }

    /** Промпт по выделенному в SQL-редакторе тексту. */
    public static String forSelection(String selection, String connectionName) {
        StringBuilder sb = new StringBuilder();
        sb.append("Проанализируй выделенный фрагмент");
        appendContext(sb, connectionName);
        sb.append(":\n\n");
        appendQuote(sb, selection);
        sb.append("\n\nВопрос: ");
        return sb.toString();
    }

    /** Промпт по выделенным строкам/ячейкам таблицы результатов. */
    public static String forDataRows(String entityName, String connectionName, List<String> rowLines) {
        StringBuilder sb = new StringBuilder();
        sb.append("Данные из результата запроса");
        List<String> parts = new ArrayList<>(2);
        if (entityName != null && !entityName.isBlank()) {
            parts.add("таблица " + entityName.trim());
        }
        if (connectionName != null && !connectionName.isBlank()) {
            parts.add("подключение «" + connectionName.trim() + "»");
        }
        if (!parts.isEmpty()) {
            sb.append(" (").append(String.join(", ", parts)).append(')');
        }
        sb.append(":\n\n");

        int total = 0;
        if (rowLines != null) {
            for (String line : rowLines) {
                String l = line == null ? "" : line;
                if (l.length() > MAX_LINE_CHARS) {
                    l = l.substring(0, MAX_LINE_CHARS) + "…";
                }
                if (total + l.length() > MAX_QUOTE_CHARS) {
                    sb.append("… (список сокращён)\n");
                    break;
                }
                sb.append("- ").append(l).append('\n');
                total += l.length();
            }
        }
        sb.append("\nВопрос: ");
        return sb.toString();
    }

    private static void appendContext(StringBuilder sb, String connectionName) {
        if (connectionName != null && !connectionName.isBlank()) {
            sb.append(" (подключение «").append(connectionName.trim()).append("»)");
        }
    }

    private static void appendQuote(StringBuilder sb, String text) {
        String t = text == null ? "" : text.trim();
        if (t.length() > MAX_QUOTE_CHARS) {
            t = t.substring(0, MAX_QUOTE_CHARS) + "\n… (фрагмент сокращён)";
        }
        for (String line : t.split("\n", -1)) {
            sb.append("> ").append(line).append('\n');
        }
    }
}
