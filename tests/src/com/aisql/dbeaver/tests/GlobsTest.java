/*
 * AI SQL for DBeaver — тесты glob-шаблонов.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.tests;

import com.aisql.dbeaver.core.Globs;

/**
 * Проверки сопоставления имён с масками.
 */
public final class GlobsTest {

    private GlobsTest() {
    }

    public static void run() {
        T.section("Globs");
        T.check(Globs.match("prod-*", "prod-1"), "prod-* совпадает с prod-1");
        T.check(Globs.match("prod-*", "PROD-A"), "совпадение без учёта регистра");
        T.check(!Globs.match("prod-*", "dev-1"), "prod-* не совпадает с dev-1");
        T.check(Globs.match("a?c", "abc"), "? — ровно один символ");
        T.check(!Globs.match("a?c", "abbc"), "? не совпадает с двумя символами");
        T.check(Globs.match("*", "любое"), "* совпадает со всем");
        T.check(Globs.match("a.b", "a.b"), "точка — литерал");
        T.check(Globs.matchQualified("users", "public.users"), "короткое имя совпадает с schema.name");
        T.check(Globs.matchQualified("sakila.film", "sakila.film"), "полное имя совпадает");
        T.check(!Globs.matchQualified("other.film", "sakila.film"), "другая схема не совпадает");
        T.check(Globs.matchQualified("*.film", "sakila.film"), "*.film совпадает с sakila.film");
        T.check(!Globs.matchQualified("users", null), "null-имя не совпадает");
    }
}
