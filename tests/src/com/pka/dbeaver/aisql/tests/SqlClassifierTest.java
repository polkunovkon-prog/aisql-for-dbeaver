/*
 * AI SQL for DBeaver — тесты классификатора SQL.
 *
 * Категория берётся от парсера DBeaver (SQLQuery.getType()) в рабочей среде;
 * в тестах категория задаётся напрямую — проверяется наша логика флагов.
 *
 * Лицензия: Apache-2.0
 */
package com.pka.dbeaver.aisql.tests;

import com.pka.dbeaver.aisql.core.SqlClassifier;

import org.jkiss.dbeaver.model.sql.SQLQueryCategory;

import java.util.List;

/**
 * Проверки классификации операторов.
 */
public final class SqlClassifierTest {

    private SqlClassifierTest() {
    }

    public static void run() {
        T.section("SqlClassifier");

        SqlClassifier.Stmt select = SqlClassifier.classify(
            "SELECT * FROM t", SQLQueryCategory.SQL);
        T.check(select.readOnly, "SELECT — только чтение");
        T.check(!select.mayWrite, "SELECT — без записи");
        T.eq("SELECT", select.firstKeyword, "первое слово SELECT");

        SqlClassifier.Stmt selectInto = SqlClassifier.classify(
            "SELECT a INTO new_t FROM t", SQLQueryCategory.SQL);
        T.check(!selectInto.readOnly, "SELECT INTO — не только чтение");
        T.check(selectInto.mayWrite, "SELECT INTO — запись");

        SqlClassifier.Stmt withQuery = SqlClassifier.classify(
            "WITH x AS (SELECT 1) SELECT * FROM x", SQLQueryCategory.SQL);
        T.check(withQuery.readOnly, "WITH ... SELECT — только чтение");

        SqlClassifier.Stmt insert = SqlClassifier.classify(
            "INSERT INTO t VALUES (1)", SQLQueryCategory.DML);
        T.check(!insert.readOnly, "INSERT — не чтение");
        T.check(insert.mayWrite, "INSERT — запись");

        SqlClassifier.Stmt commit = SqlClassifier.classify(
            "COMMIT", SQLQueryCategory.TCL);
        T.check(!commit.readOnly, "COMMIT — не чтение");
        T.check(!commit.mayWrite, "COMMIT — не требует tx-защиты");

        SqlClassifier.Stmt show = SqlClassifier.classify(
            "SHOW TABLES", SQLQueryCategory.UNKNOWN);
        T.check(show.readOnly, "SHOW — только чтение");

        SqlClassifier.Stmt describe = SqlClassifier.classify(
            "DESCRIBE t", SQLQueryCategory.UNKNOWN);
        T.check(describe.readOnly, "DESCRIBE — только чтение");

        SqlClassifier.Stmt vacuum = SqlClassifier.classify(
            "VACUUM", SQLQueryCategory.UNKNOWN);
        T.check(vacuum.mayWrite, "VACUUM — запись");

        SqlClassifier.Stmt explainAnalyze = SqlClassifier.classify(
            "EXPLAIN ANALYZE SELECT 1", SQLQueryCategory.UNKNOWN);
        T.check(SqlClassifier.isExplainAnalyze(explainAnalyze),
            "EXPLAIN ANALYZE распознан");
        T.check(explainAnalyze.readOnly, "EXPLAIN ANALYZE — read-only по категории");

        SqlClassifier.Stmt explain = SqlClassifier.classify(
            "EXPLAIN SELECT 1", SQLQueryCategory.UNKNOWN);
        T.check(!SqlClassifier.isExplainAnalyze(explain), "EXPLAIN без ANALYZE");

        // Литералы и комментарии не должны влиять на ключевые слова.
        SqlClassifier.Stmt literal = SqlClassifier.classify(
            "SELECT 'DROP TABLE x' -- DELETE FROM y\n", SQLQueryCategory.SQL);
        T.check(literal.readOnly, "слова в литералах/комментариях не считаются");

        SqlClassifier.ScriptInfo script = new SqlClassifier.ScriptInfo(
            List.of(select, insert), false);
        T.check(!script.isSingle(), "два оператора — не одиночный");
        T.check(!script.isReadOnly(), "скрипт с INSERT — не read-only");
        T.check(script.needsTxProtection(), "скрипт с INSERT требует tx-защиты");

        SqlClassifier.ScriptInfo readonlyScript = new SqlClassifier.ScriptInfo(
            List.of(select, show), false);
        T.check(readonlyScript.isReadOnly(), "два read-only — read-only");
        T.check(!readonlyScript.needsTxProtection(), "read-only не требует tx-защиты");

        SqlClassifier.ScriptInfo tclOnly = new SqlClassifier.ScriptInfo(
            List.of(commit), false);
        T.check(!tclOnly.needsTxProtection(), "COMMIT не требует tx-защиты");
        T.check(!tclOnly.isReadOnly(), "COMMIT — не read-only");

        SqlClassifier.ScriptInfo empty = new SqlClassifier.ScriptInfo(List.of(), false);
        T.check(empty.isEmpty(), "пустой скрипт");
        T.check(!empty.isSingle(), "пустой скрипт — не одиночный");
        T.check(!empty.isReadOnly(), "пустой скрипт — не read-only");
        T.check(!empty.needsTxProtection(), "пустой скрипт не требует tx-защиты");
    }
}
