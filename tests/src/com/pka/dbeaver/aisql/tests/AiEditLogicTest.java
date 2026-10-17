/*
 * AI SQL for DBeaver — тесты логики правки выделения ИИ (AiEditLogic).
 *
 * Лицензия: Apache-2.0
 */
package com.pka.dbeaver.aisql.tests;

import com.pka.dbeaver.aisql.ui.edit.AiEditLogic;

/**
 * Промпты команды Ctrl+Alt+E и разбор ответа модели.
 */
public final class AiEditLogicTest {

    private AiEditLogicTest() {
    }

    public static void run() {
        T.section("AiEditLogic: промпты и разбор ответа модели");

        T.check(!AiEditLogic.systemPrompt().isBlank(), "системный промпт не пуст");
        T.check(AiEditLogic.systemPrompt().contains("ТОЛЬКО"),
            "системный промпт требует вернуть только фрагмент");

        String message = AiEditLogic.userMessage("сделай lowercase", "SELECT * FROM T", "demo");
        T.check(message.contains("сделай lowercase"), "сообщение содержит инструкцию");
        T.check(message.contains("SELECT * FROM T"), "сообщение содержит фрагмент");
        T.check(message.contains("demo"), "сообщение содержит имя подключения");

        String chat = AiEditLogic.chatPrompt("добавь where", "SELECT 1", null);
        T.check(chat.contains("добавь where") && chat.contains("> SELECT 1"),
            "промпт для чата цитирует фрагмент");

        T.eq("SELECT 1", AiEditLogic.extractReplacement("SELECT 1"),
            "простой ответ без обёртки");
        T.eq("SELECT 1", AiEditLogic.extractReplacement("  SELECT 1\n"),
            "ответ обрезается по краям");
        T.eq("SELECT 1\nFROM t", AiEditLogic.extractReplacement("```sql\nSELECT 1\nFROM t\n```"),
            "снимается ```sql-обёртка");
        T.eq("SELECT 1", AiEditLogic.extractReplacement("```\nSELECT 1\n```"),
            "снимается обёртка без языка");
        T.eq("SELECT 1", AiEditLogic.extractReplacement("```sql\nSELECT 1"),
            "снимается незакрытая обёртка");
        T.eq("SELECT 1", AiEditLogic.extractReplacement(
            "Вот исправленный вариант:\nSELECT 1"), "срезается вводная строка");
        T.eq("SELECT 1\nFROM t", AiEditLogic.extractReplacement(
            "Вот вариант:\n\nSELECT 1\nFROM t"), "срезается вводный блок");
        T.eq("SELECT 1", AiEditLogic.extractReplacement(
            "Изменения:\n```sql\nSELECT 1\n```\nПояснение: убрал звёздочку"),
            "фрагмент берётся из первой ```-обёртки");
        T.eq("", AiEditLogic.extractReplacement(""), "пустой ответ");
        T.eq("", AiEditLogic.extractReplacement(null), "ответ null");
        T.eq("", AiEditLogic.extractReplacement("   \n  "), "ответ из пробелов");

        T.check(!AiEditLogic.isTooLarge("SELECT 1", 100), "короткий фрагмент в лимите");
        T.check(AiEditLogic.isTooLarge("SELECT 1", 4), "длинный фрагмент вне лимита");
        T.check(!AiEditLogic.isTooLarge("SELECT 1", 0), "нулевой лимит — без ограничения");
    }
}
