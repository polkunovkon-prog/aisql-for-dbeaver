/*
 * AI SQL for DBeaver — тесты правил маскирования.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.tests;

import com.aisql.dbeaver.core.MaskingRules;

import java.util.List;

/**
 * Проверки разбора и применения правил маскирования.
 */
public final class MaskingRulesTest {

    private MaskingRulesTest() {
    }

    public static void run() {
        T.section("MaskingRules");

        List<MaskingRules.Rule> rules = MaskingRules.parse(
            "customers.email=***@***; users.phone\n# комментарий\n*.password=###");
        T.eq(3, rules.size(), "разобрано три правила");
        T.eq("customers", rules.get(0).table(), "таблица первого правила");
        T.eq("email", rules.get(0).column(), "колонка первого правила");
        T.eq("***@***", rules.get(0).replacement(), "замена первого правила");
        T.eq("***", rules.get(1).replacement(), "замена по умолчанию — ***");
        T.eq("*", rules.get(2).table(), "правило для всех таблиц");

        T.eq("***@***", MaskingRules.replacementFor(rules, "customers", "email", "Email"),
            "email в customers маскируется");
        T.eq("###", MaskingRules.replacementFor(rules, "app.users", "password", null),
            "короткое имя таблицы совпадает с schema.table");
        T.eq("***", MaskingRules.replacementFor(rules, "public.users", "phone", null),
            "маска по колонке");
        T.eq("***@***", MaskingRules.replacementFor(rules, "CUSTOMERS", "EMAIL", null),
            "сопоставление без учёта регистра");
        T.eq(null, MaskingRules.replacementFor(rules, "orders", "amount", "amount"),
            "нет правила — нет замены");
        T.eq(null, MaskingRules.replacementFor(rules, "customers", null, null),
            "null-колонка не матчится");
        T.eq("###", MaskingRules.replacementFor(rules, null, "password", "pwd"),
            "маска *.column работает и без имени таблицы");

        T.eq(0, MaskingRules.parse("# только комментарий\n\n  ").size(),
            "комментарии и пустые строки пропускаются");
        T.eq(0, MaskingRules.parse(null).size(), "null-правила — пустой список");
        T.eq(1, MaskingRules.parse("просто_колонка").size(),
            "правило без таблицы и «=»");
        T.eq("***", MaskingRules.parse("просто_колонка").get(0).replacement(),
            "замена по умолчанию");
    }
}
