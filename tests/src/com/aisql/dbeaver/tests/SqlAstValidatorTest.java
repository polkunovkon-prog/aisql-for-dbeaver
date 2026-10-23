/*
 * AI SQL for DBeaver — тесты AST-валидатора SQL.
 *
 * Проверяются реальные ошибки генерации, из-за которых запросы приходилось
 * править руками: JOIN без ON, смешение JOIN и перечисления через запятую,
 * SELECT INTO, лишние «?», нарушение контракта колонок.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.tests;

import com.aisql.dbeaver.core.SqlAstValidator;

import java.util.List;

/**
 * Проверки структурной валидации SQL по дереву разбора.
 */
public final class SqlAstValidatorTest {

    private SqlAstValidatorTest() {
    }

    public static void run() {
        T.section("SqlAstValidator");

        // Корректный запрос проходит без замечаний.
        SqlAstValidator.Result good = SqlAstValidator.validate(
            "SELECT h.code AS house_code, count(s.id) AS students_total "
                + "FROM students s JOIN houses h ON h.id = s.house_id "
                + "GROUP BY h.code ORDER BY h.code", 0);
        T.check(good.ok(), "корректный запрос без замечаний: " + good.describe());
        T.eq(2, good.outputColumns.size(), "две выходные колонки");
        T.check(good.tables.contains("students"), "таблица students найдена");

        // Синтаксическая ошибка — ловится парсером.
        SqlAstValidator.Result broken = SqlAstValidator.validate(
            "SELECT * FROM students WHERE", 0);
        T.check(!broken.ok(), "битый SQL не проходит");
        T.eq("PARSE", broken.problems.get(0).code, "код PARSE");

        // JOIN без ON (было в cd-3: JOIN без условия).
        SqlAstValidator.Result noOn = SqlAstValidator.validate(
            "SELECT * FROM students s JOIN houses h WHERE s.house_id = h.id", 0);
        T.check(!noOn.ok(), "JOIN без ON — замечание");
        T.check(containsCode(noOn, "JOIN_NO_ON"), "код JOIN_NO_ON");

        // CROSS JOIN и NATURAL JOIN — законны без ON.
        T.check(SqlAstValidator.validate(
            "SELECT * FROM a CROSS JOIN b", 0).ok(), "CROSS JOIN без ON допустим");
        T.check(SqlAstValidator.validate(
            "SELECT * FROM a NATURAL JOIN b", 0).ok(), "NATURAL JOIN без ON допустим");

        // LEFT JOIN с ON — законен.
        T.check(SqlAstValidator.validate(
            "SELECT * FROM a LEFT JOIN b ON b.id = a.b_id", 0).ok(),
            "LEFT JOIN с ON допустим");

        // JOIN ... USING — тоже законен.
        T.check(SqlAstValidator.validate(
            "SELECT * FROM a JOIN b USING (id)", 0).ok(), "JOIN USING допустим");

        // Смешение перечисления и явного JOIN.
        SqlAstValidator.Result mixed = SqlAstValidator.validate(
            "SELECT * FROM a, b JOIN c ON c.id = b.id", 0);
        T.check(containsCode(mixed, "MIXED_JOIN"), "код MIXED_JOIN при смешении");

        // SELECT INTO — изменяющий оператор.
        SqlAstValidator.Result into = SqlAstValidator.validate(
            "SELECT * INTO new_t FROM t", 0);
        T.check(containsCode(into, "SELECT_INTO"), "код SELECT_INTO");

        // Заглушки: ожидание 0, в запросе 2.
        SqlAstValidator.Result ph = SqlAstValidator.validate(
            "SELECT * FROM a WHERE x = ? AND y = ?", 0);
        T.check(containsCode(ph, "PLACEHOLDER"), "лишние заглушки — замечание");
        T.check(SqlAstValidator.validate(
            "SELECT * FROM a WHERE x = ? AND y = ?", 2).ok(), "две заглушки — норма");

        // Заглушки в литералах и комментариях не считаются.
        T.eq(0, SqlAstValidator.countPlaceholders(
            "SELECT '?', \"a?b\" FROM t -- ?\n/* ? */"), "«?» в литералах/комментариях");
        T.eq(1, SqlAstValidator.countPlaceholders("SELECT ? FROM t"), "одна заглушка");

        // CTE и подзапросы обходятся.
        SqlAstValidator.Result cte = SqlAstValidator.validate(
            "WITH x AS (SELECT a.id FROM a JOIN b ON b.id = a.id) "
                + "SELECT x.id AS some_id FROM x", 0);
        T.check(cte.ok(), "CTE без ошибок: " + cte.describe());
        T.check(cte.tables.contains("a") && cte.tables.contains("b"), "таблицы из CTE найдены");

        SqlAstValidator.Result badSub = SqlAstValidator.validate(
            "SELECT (SELECT count(*) FROM a JOIN b) AS cnt", 0);
        T.check(containsCode(badSub, "JOIN_NO_ON"), "JOIN без ON в подзапросе");

        // UNION: колонки первого SELECT считаются контрактом.
        SqlAstValidator.Result union = SqlAstValidator.validate(
            "SELECT a.x AS year_label, 1 AS cnt FROM a UNION ALL SELECT b.y, 2 FROM b", 0);
        T.check(union.ok(), "UNION без ошибок");
        T.eq(2, union.outputColumns.size(), "колонки UNION");

        // Контракт: обязательные колонки на месте.
        SqlAstValidator.Result contractOk = SqlAstValidator.validate(
            "SELECT h.code AS house_code, h.name AS house_name, count(*) AS students_total "
                + "FROM houses h GROUP BY h.code, h.name", 0);
        SqlAstValidator.checkContract(contractOk, List.of(
            new SqlAstValidator.ColumnSpec("house_code", "string", true),
            new SqlAstValidator.ColumnSpec("students_total", "integer", true)));
        T.check(contractOk.ok(), "контракт выполнен: " + contractOk.describe());

        // Контракт: не хватает обязательной колонки.
        SqlAstValidator.Result contractBad = SqlAstValidator.validate(
            "SELECT h.code AS house_code FROM houses h", 0);
        SqlAstValidator.checkContract(contractBad, List.of(
            new SqlAstValidator.ColumnSpec("house_code", "string", true),
            new SqlAstValidator.ColumnSpec("students_total", "integer", true)));
        T.check(containsCode(contractBad, "CONTRACT"), "код CONTRACT при нехватке колонки");
        T.check(contractBad.describe().contains("students_total"), "имя колонки в сообщении");

        // Необязательная колонка может отсутствовать.
        SqlAstValidator.Result optional = SqlAstValidator.validate(
            "SELECT 1 AS a", 0);
        SqlAstValidator.checkContract(optional, List.of(
            new SqlAstValidator.ColumnSpec("extra", "string", false)));
        T.check(optional.ok(), "необязательная колонка не требуется");

        // Псевдонимы в кавычках сравниваются без кавычек.
        SqlAstValidator.Result quoted = SqlAstValidator.validate(
            "SELECT count(*) AS \"students_total\" FROM students", 0);
        SqlAstValidator.checkContract(quoted, List.of(
            new SqlAstValidator.ColumnSpec("students_total", "integer", true)));
        T.check(quoted.ok(), "псевдоним в кавычках сопоставлен");

        // Пустой SQL.
        T.check(!SqlAstValidator.validate("   ", 0).ok(), "пустой SQL — ошибка");

        // Не-SELECT оператор.
        SqlAstValidator.Result dml = SqlAstValidator.validate("DELETE FROM t", 0);
        T.check(!dml.ok(), "DELETE не проходит как SELECT-запрос");
    }

    private static boolean containsCode(SqlAstValidator.Result r, String code) {
        for (SqlAstValidator.Problem p : r.problems) {
            if (code.equals(p.code)) {
                return true;
            }
        }
        return false;
    }
}
