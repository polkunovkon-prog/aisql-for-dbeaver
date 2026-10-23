/*
 * AI SQL for DBeaver — тесты белого списка таблиц.
 *
 * Перенос принципа из статьи 5: набор таблиц берётся из AST, проверка — до
 * выполнения, при заданном списке — fail-closed.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.tests;

import com.aisql.dbeaver.core.SqlAstValidator;
import com.aisql.dbeaver.core.TableWhitelist;

import java.util.List;
import java.util.Set;

/**
 * Проверки policy.allowedTables (без обращения к настройкам — на чистых функциях).
 */
public final class TableWhitelistTest {

    private TableWhitelistTest() {
    }

    public static void run() {
        T.section("TableWhitelist");

        // Разбор строки шаблонов.
        List<String> parsed = TableWhitelist.parse("public.*, hogwarts.students ; cadia.orders\n# комментарий\n");
        T.eq(3, parsed.size(), "три шаблона, комментарий отброшен");
        T.eq("public.*", parsed.get(0), "первый шаблон в нижнем регистре");
        T.check(TableWhitelist.parse("").isEmpty(), "пустая строка — нет шаблонов");
        T.check(TableWhitelist.parse(null).isEmpty(), "null — нет шаблонов");

        List<String> patterns = TableWhitelist.parse("students,houses,public.*");

        // Таблица без схемы подходит под простой шаблон.
        T.check(TableWhitelist.matchesAny(patterns, "students"), "простое имя разрешено");
        T.check(TableWhitelist.matchesAny(patterns, "public.students"), "схема в шаблоне");
        T.check(TableWhitelist.matchesAny(patterns, "hogwarts.students"), "простое имя покрывает схему");
        T.check(!TableWhitelist.matchesAny(patterns, "salaries"), "чужое имя запрещено");

        // Шаблон schema.* покрывает таблицы схемы.
        List<String> schemaPattern = TableWhitelist.parse("cadia.*");
        T.check(TableWhitelist.matchesAny(schemaPattern, "cadia.personnel"), "schema.* покрывает таблицу");
        T.check(!TableWhitelist.matchesAny(schemaPattern, "hogwarts.students"), "другая схема не покрыта");

        // «*» — всё разрешено.
        T.check(TableWhitelist.matchesAny(TableWhitelist.parse("*"), "anything.at.all"), "звёздочка разрешает всё");

        // Нарушения по набору из AST: пустой список = ограничения нет.
        T.check(TableWhitelist.violations("нет-такого", Set.of("students", "houses")).isEmpty(),
            "без правила ограничения нет");

        // Таблицы из реального дерева разбора.
        SqlAstValidator.Result ast = SqlAstValidator.validate(
            "SELECT s.id FROM students s JOIN houses h ON h.id = s.house_id", 0);
        T.check(ast.tables.contains("students") && ast.tables.contains("houses"),
            "таблицы собраны из AST: " + ast.tables);

        // Подзапрос и CTE тоже дают таблицы.
        SqlAstValidator.Result deep = SqlAstValidator.validate(
            "WITH x AS (SELECT id FROM secret_table) SELECT * FROM x JOIN students s ON s.id = x.id", 0);
        T.check(deep.tables.contains("secret_table"), "таблица из CTE найдена");
        T.check(deep.tables.contains("students"), "таблица верхнего уровня найдена");
    }
}
