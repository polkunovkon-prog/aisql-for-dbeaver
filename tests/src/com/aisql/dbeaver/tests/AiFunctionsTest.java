/*
 * AI SQL for DBeaver — тесты новых AI-функций: db_getTableSample
 * (сборка SQL и лимиты) и db_searchObjects (сопоставление шаблонов).
 *
 * Живая БД не нужна: проверяется логика, не зависящая от подключения.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.tests;

import com.aisql.dbeaver.core.AisqlCore;
import com.aisql.dbeaver.core.functions.GetTableSampleFunction;
import com.aisql.dbeaver.core.functions.SearchObjectsFunction;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Проверки GetTableSampleFunction и SearchObjectsFunction (офлайн-части).
 */
public final class AiFunctionsTest {

    private AiFunctionsTest() {
    }

    public static void run() {
        T.section("db_getTableSample / db_searchObjects (офлайн-логика)");

        // --- Сборка SQL образца.
        T.eq("SELECT * FROM public.orders",
            GetTableSampleFunction.buildSampleSql("public.orders", null),
            "без limit-части — чистый SELECT");
        T.eq("SELECT * FROM public.orders LIMIT 5 OFFSET 0",
            GetTableSampleFunction.buildSampleSql("public.orders", "LIMIT 5 OFFSET 0"),
            "limit-часть диалекта добавляется");
        T.eq("SELECT * FROM \"My Schema\".\"Order Items\" LIMIT 3 OFFSET 0",
            GetTableSampleFunction.buildSampleSql("\"My Schema\".\"Order Items\"", "LIMIT 3 OFFSET 0"),
            "экранированное имя подставляется как есть");

        // --- Лимит строк образца: параметр, настройка, потолок.
        AisqlCore.saveSettingsSafe(AisqlCore.defaultSettings());
        Map<String, Object> args = new HashMap<>();
        T.eq(5, GetTableSampleFunction.sampleRows(args),
            "по умолчанию sample.maxRows=5");
        args.put("rows", 10);
        T.eq(10, GetTableSampleFunction.sampleRows(args), "параметр rows работает");
        args.put("rows", 500);
        T.eq(50, GetTableSampleFunction.sampleRows(args),
            "потолок 50 строк для образца");
        args.put("rows", 0);
        T.eq(5, GetTableSampleFunction.sampleRows(args),
            "rows<=0 → значение по умолчанию");
        args.put("rows", 7.0); // JSON-числа могут прийти как Double
        T.eq(7, GetTableSampleFunction.sampleRows(args), "rows как Double принимается");
        AisqlCore.setSettingSafe(AisqlCore.KEY_SAMPLE_MAX_ROWS, "12");
        args.clear();
        T.eq(12, GetTableSampleFunction.sampleRows(args), "настройка sample.maxRows работает");
        AisqlCore.setSettingSafe(AisqlCore.KEY_SAMPLE_MAX_ROWS, "999");
        T.eq(50, GetTableSampleFunction.sampleRows(args), "настройка ограничена потолком");

        // --- Лимит совпадений db_searchObjects.
        args.clear();
        T.eq(30, SearchObjectsFunction.limitOf(args), "лимит поиска по умолчанию 30");
        args.put("limit", 100);
        T.eq(100, SearchObjectsFunction.limitOf(args), "параметр limit работает");
        args.put("limit", 100500);
        T.eq(200, SearchObjectsFunction.limitOf(args), "потолок поиска 200");

        // --- Сопоставление имён: подстрока и регулярное выражение.
        T.check(SearchObjectsFunction.matchesPattern(
            "sales_orders", "order", null), "подстрока без учёта регистра");
        T.check(!SearchObjectsFunction.matchesPattern(
            "customers", "order", null), "несовпадение подстроки");
        T.check(SearchObjectsFunction.matchesPattern(
            "ORDER_ITEMS", "order", null), "регистр имени не важен");

        Pattern regex = SearchObjectsFunction.compilePattern("/^order_.*_dt$/");
        T.check(regex != null, "шаблон /regex/ компилируется");
        T.check(SearchObjectsFunction.matchesPattern("order_items_dt", "^order_.*_dt$", regex),
            "регулярное выражение применяется");
        T.check(!SearchObjectsFunction.matchesPattern("xorder_items_dt", "^order_.*_dt$", regex),
            "регулярное выражение соблюдает границы");
        T.eq(null, SearchObjectsFunction.compilePattern("/([unclosed/"),
            "битая регулярка → поиск по подстроке");
        T.check(SearchObjectsFunction.matchesPattern(
            "tbl_([unclosed", "/([unclosed", SearchObjectsFunction.compilePattern("/([unclosed")),
            "битая регулярка откатывается к подстроке");

        T.check(SearchObjectsFunction.matchesPattern(null, "x", null) == false,
            "null-имя не совпадает");

        AisqlCore.saveSettingsSafe(AisqlCore.defaultSettings());
    }
}
