/*
 * AI SQL for DBeaver — AI-функция db_explain.
 *
 * Возвращает план выполнения SQL-запроса (EXPLAIN) для СУБД, которые это
 * поддерживают. Для остальных — понятное сообщение.
 *
 * Тип — INFORMATION, режим — ALWAYS_ALLOW (только чтение).
 * В профиле READ_ONLY запрещён EXPLAIN ANALYZE (он фактически выполняет запрос).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.core.functions;

import com.aisql.dbeaver.core.AisqlCore;
import com.aisql.dbeaver.core.ConnectionProfiles;
import com.aisql.dbeaver.core.SqlClassifier;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.ai.AIFunction;
import org.jkiss.dbeaver.model.ai.AIFunctionContext;
import org.jkiss.dbeaver.model.ai.AIFunctionResult;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.util.Locale;
import java.util.Map;

/**
 * План выполнения запроса.
 */
public class ExplainFunction implements AIFunction {

    @Override
    public AIFunctionResult callFunction(AIFunctionContext context, Map<String, Object> args) throws DBException {
        DBCExecutionContext ctx = AisqlCore.execContext(context);
        DBRProgressMonitor monitor = context.getMonitor();

        String sql = AisqlCore.str(args, "sql");
        if (sql == null) {
            throw new DBException("Параметр 'sql' обязателен и не может быть пустым.");
        }
        SqlClassifier.ScriptInfo info = SqlClassifier.analyze(ctx.getDataSource(), sql);
        if (!info.isSingle()) {
            throw new DBException("Для плана выполнения нужен один SQL-запрос.");
        }

        String dialectId = "";
        try {
            if (ctx.getDataSource().getSQLDialect() != null) {
                dialectId = String.valueOf(ctx.getDataSource().getSQLDialect().getDialectId());
            }
        } catch (Exception ignored) {
        }
        String d = dialectId.toLowerCase(Locale.ROOT);

        String explainSql;
        if (d.contains("oracle")) {
            explainSql = "EXPLAIN PLAN FOR " + sql;
        } else if (d.contains("sqlserver") || d.contains("mssql")) {
            // SQL Server требует отдельного сеанса с SET SHOWPLAN; не поддерживаем напрямую.
            return AisqlCore.info("Для " + dialectId + " используйте встроенный план выполнения DBeaver "
                + "(Ctrl+Shift+E) или SET SHOWPLAN_ALL ON. Через db_explain не поддерживается.");
        } else {
            explainSql = "EXPLAIN " + sql;
        }

        if (AisqlCore.profileOf(ctx) == ConnectionProfiles.Profile.READ_ONLY) {
            SqlClassifier.ScriptInfo explainInfo =
                SqlClassifier.analyze(ctx.getDataSource(), explainSql);
            if (!explainInfo.stmts.isEmpty()
                && SqlClassifier.isExplainAnalyze(explainInfo.stmts.get(0))) {
                throw new DBException("Профиль READ_ONLY: EXPLAIN ANALYZE фактически ВЫПОЛНЯЕТ запрос, "
                    + "поэтому запрещён. Используйте обычный EXPLAIN (без ANALYZE).");
            }
        }

        long start = System.currentTimeMillis();
        AisqlCore.ExecResult res = AisqlCore.execute(
            monitor, ctx, explainSql, AisqlCore.maxRows(args), AisqlCore.DEFAULT_CELL_LENGTH, AisqlCore.timeoutSec(args));
        long duration = System.currentTimeMillis() - start;

        AisqlCore.auditExec("explain", ctx, explainSql, "read", "dialect=" + dialectId,
            null, res.error == null ? "OK" : "ERROR",
            res.hasResultSet ? res.rowCount : -1, res.payloadBytes, duration,
            res.error == null ? null : res.error.getMessage());

        StringBuilder sb = new StringBuilder();
        sb.append("План выполнения (").append(dialectId.isEmpty() ? "неизвестный диалект" : dialectId).append("):\n");
        sb.append("Запрос: ").append(AisqlCore.oneLine(sql, 500)).append("\n---\n");
        sb.append(res.text);
        if (res.error != null && d.contains("oracle")) {
            sb.append("\n\n(Подсказка: для Oracle после EXPLAIN PLAN нужен SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY);)");
        }
        return AisqlCore.info(sb.toString());
    }
}
