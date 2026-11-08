/*
 * AI SQL for DBeaver — AI-функция db_validateQuery.
 *
 * Структурная проверка SQL до выполнения: разбор дерева (JSQLParser),
 * JOIN без ON/USING, смешение JOIN и перечисления, SELECT … INTO,
 * число заглушек «?», контракт выходных колонок.
 *
 * Модель вызывает функцию после генерации запроса и до db_runQuery:
 * дешёвая проверка без обращения к серверу БД отсекает ошибки, которые
 * иначе тратят итерации цикла самокоррекции.
 *
 * Тип — INFORMATION, режим — ALWAYS_ALLOW (только разбор текста).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.core.functions;

import com.aisql.dbeaver.core.AisqlCore;
import com.aisql.dbeaver.core.SqlAstValidator;

import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.ai.AIFunction;
import org.jkiss.dbeaver.model.ai.AIFunctionContext;
import org.jkiss.dbeaver.model.ai.AIFunctionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AST-валидация SQL-запроса.
 */
public class ValidateQueryFunction implements AIFunction {

    @Override
    public AIFunctionResult callFunction(AIFunctionContext context, Map<String, Object> args) throws DBException {
        AisqlCore.guardToolEnabled("validateQuery", null);
        String sql = AisqlCore.str(args, "sql");
        if (sql == null) {
            throw new DBException("Параметр 'sql' обязателен: передайте текст запроса для проверки.");
        }

        int placeholders = -1;
        String ph = AisqlCore.str(args, "placeholders");
        if (ph != null) {
            try {
                placeholders = Integer.parseInt(ph.trim());
            } catch (NumberFormatException e) {
                throw new DBException("Параметр 'placeholders' должен быть целым числом "
                    + "(0 — заглушек быть не должно).");
            }
        }

        List<SqlAstValidator.ColumnSpec> contract = parseContract(AisqlCore.str(args, "columns"));

        SqlAstValidator.Result r = SqlAstValidator.validate(sql, placeholders);
        if (!contract.isEmpty()) {
            SqlAstValidator.checkContract(r, contract);
        }

        StringBuilder sb = new StringBuilder();
        if (r.ok()) {
            sb.append("Проверка пройдена: структурных ошибок не найдено.\n");
            if (!r.outputColumns.isEmpty()) {
                sb.append("Выходные колонки: ").append(String.join(", ", r.outputColumns)).append('\n');
            }
            if (!r.tables.isEmpty()) {
                sb.append("Таблицы: ").append(String.join(", ", r.tables)).append('\n');
            }
            sb.append("Запрос можно выполнять через db_runQuery.");
        } else {
            sb.append("Проверка НЕ пройдена — исправьте и вызовите db_validateQuery снова:\n");
            sb.append(r.describe()).append('\n');
            sb.append("Не выполняйте запрос через db_runQuery, пока проверка не пройдена.");
        }
        return AisqlCore.info(sb.toString());
    }

    /**
     * Разбор строки контракта: «имя:тип», «имя:тип!» (обязательная), через запятую.
     * Пример: {@code house_code:string!, students_total:integer!}.
     */
    static List<SqlAstValidator.ColumnSpec> parseContract(String raw) throws DBException {
        List<SqlAstValidator.ColumnSpec> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String chunk : raw.split("[,;\\n]")) {
            String item = chunk.trim();
            if (item.isEmpty()) {
                continue;
            }
            boolean required = item.endsWith("!");
            if (required) {
                item = item.substring(0, item.length() - 1).trim();
            }
            String name = item;
            String type = "any";
            int colon = item.indexOf(':');
            if (colon > 0) {
                name = item.substring(0, colon).trim();
                type = item.substring(colon + 1).trim();
            }
            if (name.isEmpty()) {
                continue;
            }
            if (!SqlAstValidator.TYPES.contains(type.toLowerCase(java.util.Locale.ROOT))) {
                throw new DBException("Неизвестный тип колонки «" + type + "» в контракте. "
                    + "Допустимые: " + String.join(", ", SqlAstValidator.TYPES) + ".");
            }
            out.add(new SqlAstValidator.ColumnSpec(name, type, required));
        }
        return out;
    }
}
