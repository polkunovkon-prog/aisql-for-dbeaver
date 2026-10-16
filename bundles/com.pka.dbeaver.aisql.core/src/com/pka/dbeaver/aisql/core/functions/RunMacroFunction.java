/*
 * AI SQL for DBeaver — AI-функция db_runMacro.
 *
 * Выполняет сохранённый макрос (именованный SQL-скрипт) с подстановкой
 * параметров. Поддерживаются подстановки ${имя} и позиционные ${1}, ${2}, ...
 * Значения передаются в параметре values в виде "имя=значение; имя2=значение2".
 *
 * Макрос проходит те же проверки, что и db_executeSql: профиль READ_ONLY
 * и правила policy.forbidden; для изменяющих операторов отключается автокоммит.
 *
 * Тип — INFORMATION, режим — ASK (макрос может изменять данные).
 *
 * Лицензия: Apache-2.0
 */
package com.pka.dbeaver.aisql.core.functions;

import com.pka.dbeaver.aisql.core.AisqlCore;
import com.pka.dbeaver.aisql.core.ConnectionProfiles;
import com.pka.dbeaver.aisql.core.QueryPolicy;
import com.pka.dbeaver.aisql.core.SqlClassifier;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.ai.AIFunction;
import org.jkiss.dbeaver.model.ai.AIFunctionContext;
import org.jkiss.dbeaver.model.ai.AIFunctionResult;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Выполнение макроса.
 */
public class RunMacroFunction implements AIFunction {

    @Override
    public AIFunctionResult callFunction(AIFunctionContext context, Map<String, Object> args) throws DBException {
        DBCExecutionContext ctx = AisqlCore.execContext(context);
        DBRProgressMonitor monitor = context.getMonitor();

        String name = AisqlCore.str(args, "name");
        if (name == null) {
            throw new DBException("Параметр 'name' обязателен (идентификатор макроса, см. db_listMacros).");
        }
        String id = AisqlCore.sanitizeId(name);
        if (id == null) {
            throw new DBException("Недопустимый идентификатор макроса '" + name
                + "': разрешены латиница, цифры, точка, дефис и подчёркивание.");
        }
        String sql = AisqlCore.readMacroSql(id);

        // Подстановка параметров.
        String values = AisqlCore.str(args, "values");
        if (values != null && !values.isEmpty()) {
            Map<String, String> named = new LinkedHashMap<>();
            List<String> positional = new ArrayList<>();
            for (String pair : values.split("[;\\n]")) {
                String p = pair.trim();
                if (p.isEmpty()) {
                    continue;
                }
                int eq = p.indexOf('=');
                if (eq > 0) {
                    named.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
                } else {
                    positional.add(p);
                }
            }
            for (Map.Entry<String, String> e : named.entrySet()) {
                sql = sql.replace("${" + e.getKey() + "}", e.getValue());
            }
            for (int i = 0; i < positional.size(); i++) {
                sql = sql.replace("${" + (i + 1) + "}", positional.get(i));
            }
        }

        // Разбор и классификация скрипта макроса (после подстановки значений).
        SqlClassifier.ScriptInfo script = SqlClassifier.analyze(ctx.getDataSource(), sql);
        if (script.isEmpty()) {
            throw new DBException("Макрос '" + id + "' пуст: выполнять нечего.");
        }

        // Политика подключения: профиль READ_ONLY и правила policy.forbidden.
        String connName = AisqlCore.connectionName(ctx);
        QueryPolicy.Decision decision = QueryPolicy.evaluate(connName, script.stmts);
        if (!decision.allowed) {
            AisqlCore.auditExec("runMacro", ctx, sql, "deny",
                "macro=" + id + "; отклонено политикой: " + decision.rule,
                null, "DENIED", -1, -1, -1, null);
            throw new DBException("Выполнение макроса '" + id + "' отклонено. " + decision.reason);
        }

        int maxRows = AisqlCore.maxRows(args);
        int timeout = AisqlCore.timeoutSec(args);

        boolean autocommitDisabled = AisqlCore.disableAutoCommitIfNeeded(monitor, ctx, script);

        StringBuilder sb = new StringBuilder();
        sb.append("Макрос '").append(id).append("' (").append(AisqlCore.connectionLabel(ctx)).append("):\n");
        sb.append("Профиль безопасности: ").append(ConnectionProfiles.describe(decision.profile)).append('\n');
        sb.append("---\n");

        int okCount = 0;
        int errCount = 0;
        Throwable lastError = null;
        boolean multiple = script.stmts.size() > 1;
        for (int i = 0; i < script.stmts.size(); i++) {
            SqlClassifier.Stmt stmt = script.stmts.get(i);
            long start = System.currentTimeMillis();
            AisqlCore.ExecResult res = AisqlCore.execute(
                monitor, ctx, stmt.text, maxRows, AisqlCore.DEFAULT_CELL_LENGTH, timeout);
            long duration = System.currentTimeMillis() - start;

            if (multiple) {
                sb.append("\n[").append(i + 1).append('/').append(script.stmts.size()).append("] ");
            }
            sb.append(res.text);

            long rows = res.hasResultSet ? res.rowCount : res.affectedRows;
            if (res.error != null) {
                errCount++;
                lastError = res.error;
                AisqlCore.auditExec("runMacro", ctx, stmt.text, "exec", "macro=" + id,
                    stmt.categoryName, "ERROR", rows, res.payloadBytes, duration,
                    res.error.getMessage());
                break;
            } else {
                okCount++;
                AisqlCore.auditExec("runMacro", ctx, stmt.text, "exec", "macro=" + id,
                    stmt.categoryName, "OK", rows, res.payloadBytes, duration, null);
            }
        }

        if (multiple) {
            sb.append("\n---\nВыполнено операторов: ").append(okCount);
            if (errCount > 0) {
                sb.append(", ошибок: ").append(errCount);
            }
            sb.append('.');
        }
        if (autocommitDisabled && lastError == null) {
            sb.append("\nАвтокоммит отключён — при необходимости зафиксируйте (Commit) или откатите (Rollback) в DBeaver.");
        }
        if (lastError != null) {
            throw new DBException(sb.toString(),
                lastError instanceof Exception ? (Exception) lastError : null);
        }
        return AisqlCore.info(sb.toString());
    }
}
