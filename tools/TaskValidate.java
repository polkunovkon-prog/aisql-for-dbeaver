/*
 * AI SQL for DBeaver — обёртка для офлайн-прогона: AST-проверка SQL.
 *
 * Аргументы: <sql> <placeholders> <контракт колонок>
 * Контракт: «имя:тип!» через запятую, «!» — обязательная колонка.
 * placeholders: -1 — не проверять, 0 — заглушек быть не должно.
 *
 * Возвращает «OK …» и код 0 либо список замечаний и код 1.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.tools;

import com.aisql.dbeaver.core.SqlAstValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Проверка SQL по дереву разбора (без подключения к БД).
 */
public final class TaskValidate {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("FAIL: нужен текст SQL");
            System.exit(2);
        }
        String sql = args[0];
        int placeholders = -1;
        if (args.length > 1 && args[1] != null && !args[1].trim().isEmpty()) {
            try {
                placeholders = Integer.parseInt(args[1].trim());
            } catch (NumberFormatException e) {
                System.out.println("FAIL: placeholders должно быть целым числом");
                System.exit(2);
                return;
            }
        }
        List<SqlAstValidator.ColumnSpec> contract = parseContract(args.length > 2 ? args[2] : "");

        SqlAstValidator.Result r = SqlAstValidator.validate(sql, placeholders);
        if (!contract.isEmpty()) {
            SqlAstValidator.checkContract(r, contract);
        }

        if (r.ok()) {
            StringBuilder sb = new StringBuilder("OK");
            if (!r.outputColumns.isEmpty()) {
                sb.append("; колонки: ").append(String.join(", ", r.outputColumns));
            }
            if (!r.tables.isEmpty()) {
                sb.append("; таблицы: ").append(String.join(", ", r.tables));
            }
            System.out.println(sb);
            System.exit(0);
        }
        for (SqlAstValidator.Problem p : r.problems) {
            System.out.println("- [" + p.code + "] " + p.message);
        }
        System.exit(1);
    }

    /** Разбор контракта: «имя:тип» или «имя:тип!» через запятую. */
    private static List<SqlAstValidator.ColumnSpec> parseContract(String raw) {
        List<SqlAstValidator.ColumnSpec> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String chunk : raw.split(",")) {
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
                type = item.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
            }
            if (!name.isEmpty()) {
                out.add(new SqlAstValidator.ColumnSpec(name, type, required));
            }
        }
        return out;
    }
}
