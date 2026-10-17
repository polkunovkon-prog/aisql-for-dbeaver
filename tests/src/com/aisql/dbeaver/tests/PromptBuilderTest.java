/*
 * AI SQL for DBeaver — тесты построения промптов «выделил → спросил».
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.tests;

import com.aisql.dbeaver.ui.PromptBuilder;

import java.util.List;

/**
 * Проверки PromptBuilder (UI-бандл).
 */
public final class PromptBuilderTest {

    private PromptBuilderTest() {
    }

    public static void run() {
        T.section("PromptBuilder");

        String selection = PromptBuilder.forSelection(
            "SELECT 1;\nDROP TABLE t;", "Sakila");
        T.check(selection.startsWith("Проанализируй"), "заголовок промпта");
        T.check(selection.contains("(подключение «Sakila»)"), "имя подключения");
        T.check(selection.contains("> DROP TABLE t;"), "построчная цитата");
        T.check(selection.endsWith("Вопрос: "), "промпт завершается «Вопрос:»");

        String noConn = PromptBuilder.forSelection("SELECT 1", null);
        T.check(!noConn.contains("подключение"), "без имени подключения");

        String data = PromptBuilder.forDataRows("customers", "Sakila",
            List.of("email = a@b", "phone = 123"));
        T.check(data.contains("таблица customers"), "имя таблицы в промпте");
        T.check(data.contains("подключение «Sakila»"), "подключение в промпте");
        T.check(data.contains("- email = a@b"), "строки данных");

        String longText = PromptBuilder.forSelection("x".repeat(10000), null);
        T.check(longText.length() < 6000, "длинное выделение усечено");

        String manyLines = PromptBuilder.forDataRows(null, null,
            List.of("a".repeat(600), "b".repeat(600)));
        T.check(manyLines.length() < 2000, "длинные строки усечены");
    }
}
