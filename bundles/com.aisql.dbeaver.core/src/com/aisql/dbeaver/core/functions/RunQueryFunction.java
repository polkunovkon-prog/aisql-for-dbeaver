/*
 * AI SQL for DBeaver — AI-функция db_runQuery (read-only).
 *
 * Позволяет встроенному AI Chat выполнять read-only SQL
 * (SELECT/WITH/SHOW/DESCRIBE/EXPLAIN/VALUES/TABLE) на текущем активном
 * подключении и возвращать строки модели.
 *
 * Проверка «только чтение» выполняется классификатором на основе парсера
 * DBeaver (SqlClassifier), а не по эвристике ключевых слов.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.core.functions;

import com.aisql.dbeaver.core.AisqlCore;
import com.aisql.dbeaver.core.ConnectionProfiles;
import com.aisql.dbeaver.core.PromptInjectionDefense;
import com.aisql.dbeaver.core.SqlAstValidator;
import com.aisql.dbeaver.core.SqlClassifier;
import com.aisql.dbeaver.core.TableWhitelist;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.ai.AIFunction;
import org.jkiss.dbeaver.model.ai.AIFunctionContext;
import org.jkiss.dbeaver.model.ai.AIFunctionResult;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.util.List;
import java.util.Map;

/**
 * Выполняет read-only SQL-запрос на текущем подключении и возвращает строки.
 *
 * <p>Модель видит эту функцию как {@code db_runQuery}. Запрос выполняется на
 * активном подключении пользователя, с его правами («как пользователь»).</p>
 */
public class RunQueryFunction implements AIFunction {

    @Override
    public AIFunctionResult callFunction(AIFunctionContext context, Map<String, Object> args) throws DBException {
        DBCExecutionContext executionContext = AisqlCore.execContext(context);
        AisqlCore.guardToolEnabled("runQuery", executionContext);

        String sql = AisqlCore.str(args, "sql");
        if (sql == null) {
            throw new DBException("Параметр 'sql' обязателен и не может быть пустым.");
        }

        // Разбор и классификация средствами парсера DBeaver.
        SqlClassifier.ScriptInfo info = SqlClassifier.analyze(executionContext.getDataSource(), sql);
        if (info.isEmpty()) {
            throw new DBException("Пустой SQL-запрос: выполнять нечего.");
        }
        if (!info.isSingle()) {
            throw new DBException("Несколько SQL-операторов в одном запросе запрещены (обнаружено "
                + info.stmts.size() + "). Выполните один запрос. "
                + "Для скриптов из нескольких операторов используйте db_executeSql.");
        }
        SqlClassifier.Stmt stmt = info.stmts.get(0);

        boolean strict = AisqlCore.getBool(AisqlCore.KEY_RUNQUERY_STRICT, true);
        if (strict && !stmt.readOnly) {
            throw new DBException("db_runQuery разрешает только операторы чтения ("
                + String.join(", ", SqlClassifier.READONLY_KEYWORDS) + "). "
                + "Разбор показал категорию " + stmt.categoryName
                + " («" + stmt.firstKeyword + "»). "
                + "Для изменения данных используйте db_executeSql.");
        }

        ConnectionProfiles.Profile profile = AisqlCore.profileOf(executionContext);
        if (profile == ConnectionProfiles.Profile.READ_ONLY && SqlClassifier.isExplainAnalyze(stmt)) {
            throw new DBException("Профиль READ_ONLY: EXPLAIN ANALYZE фактически ВЫПОЛНЯЕТ запрос, "
                + "поэтому запрещён. Используйте обычный EXPLAIN (без ANALYZE).");
        }

        // Белый список таблиц (policy.allowedTables) — проверка по дереву разбора,
        // до выполнения. Пустой список = ограничения нет (поведение по умолчанию).
        SqlAstValidator.Result ast = SqlAstValidator.validate(sql, -1);
        if (ast.statement != null) {
            java.util.List<String> wl = TableWhitelist.violations(
                AisqlCore.connectionName(executionContext), ast.tables);
            if (!wl.isEmpty()) {
                AisqlCore.auditExec("runQuery", executionContext, sql, "deny",
                    "whitelist", stmt.categoryName, "DENIED", -1, -1, -1, wl.get(0));
                throw new DBException(String.join(" ", wl));
            }
        }

        // Признаки инъекции в тексте задания/SQL — пометка в аудите,
        // а в режиме strict — блокировка попыток угона модели.
        PromptInjectionDefense.auditIfSuspicious("runQuery", executionContext, sql, "sql");
        PromptInjectionDefense.checkStrict("runQuery", executionContext, sql, "sql");

        int maxRows = AisqlCore.maxRows(args);
        int timeout = AisqlCore.timeoutSec(args);

        DBRProgressMonitor monitor = context.getMonitor();
        long start = System.currentTimeMillis();
        AisqlCore.ExecResult res = AisqlCore.execute(
            monitor, executionContext, sql, maxRows, AisqlCore.DEFAULT_CELL_LENGTH, timeout);
        long duration = System.currentTimeMillis() - start;

        AisqlCore.auditExec("runQuery", executionContext, sql,
            "read",
            "строгий режим: " + strict,
            stmt.categoryName,
            res.error == null ? "OK" : "ERROR",
            res.hasResultSet ? res.rowCount : res.affectedRows,
            res.payloadBytes,
            duration,
            res.error == null ? null : res.error.getMessage());

        if (res.error != null) {
            throw new DBException(res.text, res.error instanceof Exception ? (Exception) res.error : null);
        }
        return AisqlCore.info(res.text);
    }
}
