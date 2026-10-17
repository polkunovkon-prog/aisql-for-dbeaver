/*
 * AI SQL for DBeaver — AI-функция db_executeSql (полный исполнитель SQL).
 *
 * Выполняет ЛЮБОЙ SQL на текущем подключении от имени пользователя:
 * SELECT, INSERT/UPDATE/DELETE, DDL, DCL, вызовы процедур, скрипты из
 * нескольких операторов. Права доступа определяются ролью пользователя СУБД.
 *
 * Перед выполнением:
 *   - скрипт разбирается классификатором SqlClassifier (парсер DBeaver);
 *   - проверяется политика подключения: профиль READ_ONLY и policy.forbidden;
 *   - для изменяющих операторов отключается автокоммит (если включено),
 *     чтобы пользователь мог Commit/Rollback из DBeaver.
 *
 * Тип функции — ACTION: результат визуализируется в чате, диалог завершается.
 * Режим подтверждения — ASK (по умолчанию): пользователь видит SQL и решает.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.core.functions;

import com.aisql.dbeaver.core.AisqlCore;
import com.aisql.dbeaver.core.ConnectionProfiles;
import com.aisql.dbeaver.core.QueryPolicy;
import com.aisql.dbeaver.core.SqlClassifier;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.ai.AIFunction;
import org.jkiss.dbeaver.model.ai.AIFunctionContext;
import org.jkiss.dbeaver.model.ai.AIFunctionResult;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.util.Map;

/**
 * Полный исполнитель SQL. Работает «как человек в SQL-редакторе»: выполняет
 * произвольные операторы с правами текущего подключения.
 */
public class ExecuteSqlFunction implements AIFunction {

    @Override
    public AIFunctionResult callFunction(AIFunctionContext context, Map<String, Object> args) throws DBException {
        DBCExecutionContext executionContext = AisqlCore.execContext(context);

        String sql = AisqlCore.str(args, "sql");
        if (sql == null) {
            throw new DBException("Параметр 'sql' обязателен и не может быть пустым.");
        }
        String reason = AisqlCore.str(args, "reason");

        int maxRows = AisqlCore.maxRows(args);
        int timeout = AisqlCore.timeoutSec(args);

        DBRProgressMonitor monitor = context.getMonitor();

        // Разбор скрипта на отдельные операторы средствами DBeaver
        // (учитываются кавычки, комментарии, разделители) + классификация.
        SqlClassifier.ScriptInfo script = SqlClassifier.analyze(executionContext.getDataSource(), sql);
        if (script.isEmpty()) {
            throw new DBException("Пустой SQL: выполнять нечего.");
        }

        // Политика подключения: профиль READ_ONLY и правила policy.forbidden.
        String connName = AisqlCore.connectionName(executionContext);
        QueryPolicy.Decision decision = QueryPolicy.evaluate(connName, script.stmts);
        if (!decision.allowed) {
            AisqlCore.auditExec("executeSql", executionContext, sql,
                "deny", "отклонено политикой: " + decision.rule,
                null, "DENIED", -1, -1, -1, null);
            throw new DBException("Выполнение отклонено. " + decision.reason);
        }

        // Транзакционная защита: автокоммит отключается для любых изменяющих
        // операторов (DML/DDL/прочее), а не только по списку ключевых слов.
        boolean autocommitDisabled =
            AisqlCore.disableAutoCommitIfNeeded(monitor, executionContext, script);

        StringBuilder report = new StringBuilder();
        report.append("Соединение: ").append(AisqlCore.connectionLabel(executionContext)).append('\n');
        report.append("Профиль безопасности: ").append(ConnectionProfiles.describe(decision.profile)).append('\n');
        if (reason != null) {
            report.append("Цель: ").append(reason).append('\n');
        }
        report.append("Операторов: ").append(script.stmts.size()).append('\n');
        if (autocommitDisabled) {
            report.append("Автокоммит отключён для этого соединения — проверьте изменения и при необходимости ")
                .append("зафиксируйте (Commit) или откатите (Rollback) в DBeaver.\n");
        }
        report.append("---\n");

        int okCount = 0;
        int errCount = 0;
        boolean multiple = script.stmts.size() > 1;
        for (int i = 0; i < script.stmts.size(); i++) {
            SqlClassifier.Stmt stmt = script.stmts.get(i);
            long start = System.currentTimeMillis();
            AisqlCore.ExecResult res = AisqlCore.execute(
                monitor, executionContext, stmt.text, maxRows, AisqlCore.DEFAULT_CELL_LENGTH, timeout);
            long duration = System.currentTimeMillis() - start;

            if (multiple) {
                report.append("\n[").append(i + 1).append('/').append(script.stmts.size()).append("] ");
            }
            report.append(res.text);

            long rows = res.hasResultSet ? res.rowCount : res.affectedRows;
            String detail = reason == null ? "" : "reason=" + AisqlCore.oneLine(reason, 200);
            if (res.error != null) {
                errCount++;
                AisqlCore.auditExec("executeSql", executionContext, stmt.text,
                    "exec", detail, stmt.categoryName, "ERROR",
                    rows, res.payloadBytes, duration, res.error.getMessage());
                // При первой ошибке останавливаемся — как в SQL-редакторе.
                break;
            } else {
                okCount++;
                AisqlCore.auditExec("executeSql", executionContext, stmt.text,
                    "exec", detail, stmt.categoryName, "OK",
                    rows, res.payloadBytes, duration, null);
            }
        }

        report.append("\n---\nИтог: выполнено ").append(okCount);
        if (errCount > 0) {
            report.append(", ошибок ").append(errCount);
        }
        report.append('.');

        return AisqlCore.action(report.toString());
    }
}
