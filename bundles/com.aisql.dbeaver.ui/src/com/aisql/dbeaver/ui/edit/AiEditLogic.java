/*
 * AI SQL for DBeaver — чистая логика правки выделения ИИ (Ctrl+Alt+E):
 * промпты и разбор ответа модели.
 *
 * Класс без UI/OSGi-зависимостей — покрыт офлайн-тестами (AiEditLogicTest).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.ui.edit;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Логика команды «правка выделения ИИ»: системная инструкция, сообщение
 * пользователя, промпт для чата и извлечение фрагмента-замены из ответа.
 */
public final class AiEditLogic {

    /** Системная инструкция модели: вернуть только новый фрагмент. */
    public static final String SYSTEM_PROMPT =
        "Ты — редактор SQL. Пользователь выделил в SQL-скрипте фрагмент и объяснил, "
            + "что с ним сделать. Верни ТОЛЬКО новый вариант этого фрагмента: "
            + "без пояснений, без markdown-разметки и без ```. "
            + "Сохрани стиль, регистр и отступы исходного фрагмента, если инструкция "
            + "не требует иного. Не добавляй другие операторы, не сокращай "
            + "и не переименовывай ничего без необходимости. "
            + "Если инструкцию выполнить нельзя — верни исходный фрагмент без изменений.";

    private static final Pattern FENCED_BLOCK = Pattern.compile(
        "```[^\\n]*\\n(.*?)(?:```|$)", Pattern.DOTALL);

    private static final Pattern SQL_START = Pattern.compile(
        "(?is)^\\s*(?:--|/\\*|with|select|insert|update|delete|merge|values|table|"
            + "create|alter|drop|truncate|comment|grant|revoke|call|do\\b|declare|"
            + "begin|set\\b|show|explain|analy[sz]e|describe|copy|vacuum|refresh|"
            + "lock|listen|notify|prepare|execute|commit|rollback|savepoint)");

    private AiEditLogic() {
    }

    /** Текст системного промпта. */
    public static String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    /** Сообщение пользователя: инструкция + фрагмент + контекст подключения. */
    public static String userMessage(String instruction, String fragment, String connectionName) {
        StringBuilder sb = new StringBuilder(256 + (fragment == null ? 0 : fragment.length()));
        sb.append("Инструкция: ").append(instruction == null ? "" : instruction.trim()).append('\n');
        if (connectionName != null && !connectionName.isBlank()) {
            sb.append("Подключение: ").append(connectionName.trim()).append('\n');
        }
        sb.append("\nФрагмент SQL:\n");
        sb.append(fragment == null ? "" : fragment);
        sb.append("\n\nВерни только новый вариант фрагмента.");
        return sb.toString();
    }

    /** Промпт для кнопки «В чат»: та же правка, но обсуждением в ИИ-чате. */
    public static String chatPrompt(String instruction, String fragment, String connectionName) {
        StringBuilder sb = new StringBuilder();
        sb.append("Помоги отредактировать фрагмент SQL");
        if (connectionName != null && !connectionName.isBlank()) {
            sb.append(" (подключение «").append(connectionName.trim()).append("»)");
        }
        sb.append(".\nИнструкция: ").append(instruction == null ? "" : instruction.trim())
            .append("\n\nФрагмент:\n");
        String text = fragment == null ? "" : fragment;
        for (String line : text.split("\n", -1)) {
            sb.append("> ").append(line).append('\n');
        }
        sb.append("\nПредложи вариант и коротко поясни изменения.");
        return sb.toString();
    }

    /**
     * Ответ модели → фрагмент-замена: обрезает края, снимает markdown-обёртку
     * (```/```sql) и вводные фразы вида «Вот исправленный вариант:».
     */
    public static String extractReplacement(String answer) {
        if (answer == null) {
            return "";
        }
        String text = answer.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (text.isEmpty()) {
            return "";
        }
        Matcher fenced = FENCED_BLOCK.matcher(text);
        if (fenced.find()) {
            text = fenced.group(1).trim();
        } else if (text.startsWith("```")) {
            int newline = text.indexOf('\n');
            text = newline < 0 ? "" : text.substring(newline + 1).trim();
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3).trim();
            }
        }
        return stripProse(text);
    }

    /** Выделение превышает лимит символов? (0 — без ограничения). */
    public static boolean isTooLarge(String fragment, int maxChars) {
        return maxChars > 0 && fragment != null && fragment.length() > maxChars;
    }

    // ----------------------------------------------------------------------

    /** Убирает вводные фразы перед SQL (строка «…:» или блок прозы). */
    private static String stripProse(String text) {
        String[] blocks = text.split("\n\n", -1);
        if (blocks.length > 1) {
            for (int i = 0; i < blocks.length; i++) {
                if (looksLikeSql(blocks[i])) {
                    return joinFrom(blocks, i);
                }
            }
            return text;
        }
        int newline = text.indexOf('\n');
        if (newline > 0) {
            String first = text.substring(0, newline).trim();
            String rest = text.substring(newline + 1).trim();
            if (isProseLine(first) && looksLikeSql(rest)) {
                return rest;
            }
        }
        return text;
    }

    private static boolean isProseLine(String line) {
        return line.endsWith(":") || line.startsWith("Вот")
            || line.startsWith("Here") || line.startsWith("Новый");
    }

    private static String joinFrom(String[] blocks, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < blocks.length; i++) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(blocks[i]);
        }
        return sb.toString().trim();
    }

    private static boolean looksLikeSql(String text) {
        return text != null && SQL_START.matcher(text).find();
    }
}
