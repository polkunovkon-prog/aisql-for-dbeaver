/*
 * AI SQL for DBeaver — тесты подсказок по ошибкам SQL (SqlErrorHints).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.tests;

import com.aisql.dbeaver.core.SqlErrorHints;

/**
 * Проверки SqlErrorHints (корректирующий цикл для модели).
 */
public final class SqlErrorHintsTest {

    private SqlErrorHintsTest() {
    }

    public static void run() {
        T.section("SqlErrorHints (подсказки по ошибкам)");

        check("ERROR: current transaction is aborted, commands ignored until end of transaction block",
            "ROLLBACK");
        check("ERROR: relation \"orderss\" does not exist",
            "db_searchObjects");
        check("ERROR: column \"usr_nm\" does not exist",
            "db_getObjectDdl");
        check("ERROR: syntax error at or near \"FROM\"",
            "db_validateQuery");
        check("ERROR: permission denied for table orders",
            "права");
        check("ERROR: duplicate key value violates unique constraint \"orders_pkey\"",
            "уникального");
        check("ERROR: insert or update on table \"items\" violates foreign key constraint",
            "внешнего ключа");
        check("ERROR: canceling statement due to statement timeout",
            "таймаут");
        check("ERROR: division by zero",
            "NULLIF");
        check("ERROR: unterminated quoted string at or near \"abc\"",
            "кавычк");

        // Совпадение по PostgreSQL-коду без текста.
        check("25P02", "ROLLBACK");

        // Без подсказки.
        T.eq(null, SqlErrorHints.hintFor(null), "null — без подсказки");
        T.eq(null, SqlErrorHints.hintFor(""), "пусто — без подсказки");
        T.eq(null, SqlErrorHints.hintFor("some totally unknown failure"),
            "нераспознанная ошибка — без подсказки");

        // Подсказка формулирует следующее действие модели.
        String hint = SqlErrorHints.hintFor("relation \"x\" does not exist");
        T.check(hint != null && hint.toLowerCase().contains("db_searchobjects"),
            "подсказка упоминает инструмент");
    }

    private static void check(String error, String fragment) {
        String hint = SqlErrorHints.hintFor(error);
        T.check(hint != null && hint.contains(fragment),
            "подсказка для «" + error.substring(0, Math.min(40, error.length()))
                + "…» содержит «" + fragment + "»");
    }
}
