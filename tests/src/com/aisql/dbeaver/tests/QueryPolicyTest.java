/*
 * AI SQL for DBeaver — тесты политики выполнения SQL.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.tests;

import com.aisql.dbeaver.core.AisqlCore;
import com.aisql.dbeaver.core.ConnectionProfiles;
import com.aisql.dbeaver.core.QueryPolicy;
import com.aisql.dbeaver.core.SqlClassifier;

import org.jkiss.dbeaver.model.sql.SQLQueryCategory;

import java.util.List;

/**
 * Проверки профиля READ_ONLY и правил policy.forbidden.
 */
public final class QueryPolicyTest {

    private QueryPolicyTest() {
    }

    public static void run() {
        T.section("QueryPolicy");

        AisqlCore.saveSettingsSafe(AisqlCore.defaultSettings());

        SqlClassifier.Stmt select = SqlClassifier.classify("SELECT 1", SQLQueryCategory.SQL);
        SqlClassifier.Stmt drop = SqlClassifier.classify("DROP TABLE t", SQLQueryCategory.DDL);
        SqlClassifier.Stmt insert = SqlClassifier.classify(
            "INSERT INTO t VALUES (1)", SQLQueryCategory.DML);
        SqlClassifier.Stmt explain = SqlClassifier.classify(
            "EXPLAIN SELECT 1", SQLQueryCategory.UNKNOWN);
        SqlClassifier.Stmt explainAnalyze = SqlClassifier.classify(
            "EXPLAIN ANALYZE SELECT 1", SQLQueryCategory.UNKNOWN);

        QueryPolicy.Decision decision = QueryPolicy.evaluate("conn", List.of(drop));
        T.check(decision.allowed, "STANDARD: DDL разрешён без правил");

        AisqlCore.setSettingSafe(QueryPolicy.KEY_FORBIDDEN, "DROP\\s+TABLE;\nTRUNCATE");
        decision = QueryPolicy.evaluate("conn", List.of(drop));
        T.check(!decision.allowed, "policy.forbidden блокирует DROP TABLE");
        T.eq("policy.forbidden(DROP\\s+TABLE)", decision.rule, "сработало первое правило");
        decision = QueryPolicy.evaluate("conn", List.of(select));
        T.check(decision.allowed, "SELECT не заблокирован правилами");
        AisqlCore.setSettingSafe(QueryPolicy.KEY_FORBIDDEN, "");

        AisqlCore.setSettingSafe(ConnectionProfiles.KEY_PREFIX + "full-conn", "FULL");
        AisqlCore.setSettingSafe(QueryPolicy.KEY_FORBIDDEN, "DROP");
        decision = QueryPolicy.evaluate("full-conn", List.of(drop));
        T.check(decision.allowed, "FULL игнорирует policy.forbidden");
        AisqlCore.setSettingSafe(QueryPolicy.KEY_FORBIDDEN, "");

        AisqlCore.setSettingSafe(ConnectionProfiles.KEY_PREFIX + "ro-conn", "READ_ONLY");
        decision = QueryPolicy.evaluate("ro-conn", List.of(select));
        T.check(decision.allowed, "READ_ONLY: SELECT разрешён");
        decision = QueryPolicy.evaluate("ro-conn", List.of(insert));
        T.check(!decision.allowed, "READ_ONLY: INSERT запрещён");
        decision = QueryPolicy.evaluate("ro-conn", List.of(drop));
        T.check(!decision.allowed, "READ_ONLY: DROP запрещён");
        decision = QueryPolicy.evaluate("ro-conn", List.of(explain));
        T.check(decision.allowed, "READ_ONLY: EXPLAIN разрешён");
        decision = QueryPolicy.evaluate("ro-conn", List.of(explainAnalyze));
        T.check(!decision.allowed, "READ_ONLY: EXPLAIN ANALYZE запрещён");
        T.eq(ConnectionProfiles.Profile.READ_ONLY, decision.profile,
            "в решении указан профиль");

        decision = QueryPolicy.evaluate("default-conn", List.of(drop));
        T.check(decision.allowed, "STANDARD (по умолчанию): DROP без правил разрешён");

        AisqlCore.saveSettingsSafe(AisqlCore.defaultSettings());
    }
}
