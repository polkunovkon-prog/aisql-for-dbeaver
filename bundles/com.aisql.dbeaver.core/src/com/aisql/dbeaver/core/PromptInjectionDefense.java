/*
 * AI SQL for DBeaver — детекция инъекций и контаминации промпта.
 *
 * Перенос принципа из статьи 7 («Антиаллюцинационный барьер», слой 1):
 * подозрительный ввод ПОМЕЧАЕТСЯ и уходит в журнал аудита, но НЕ блокируется —
 * чтобы не ломать нормальную работу (легитимные запросы о «drop table»
 * в учебной БД должны выполняться).
 *
 * Проверяется: текст пользовательского задания, ответ модели, SQL и тексты
 * из базы (DDL функций, строки таблиц) — содержимое БД не является командой.
 *
 * Класс без UI/OSGi — покрыт офлайн-тестами (PromptInjectionDefenseTest).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Поиск признаков инъекции в промпт.
 */
public final class PromptInjectionDefense {

    /** Одно правило детекции: метка и регулярное выражение. */
    private record Rule(String label, Pattern pattern) {
    }

    private static final List<Rule> RULES = List.of(
        rule("ignore_previous", "\\bignore\\s+(?:all\\s+)?(?:the\\s+)?previous\\b"),
        rule("ignore_previous_ru", "\\bигнорируй\\s+(?:все\\s+)?предыдущие\\b"),
        rule("forget_rules_ru", "забудь\\s+(?:все\\s+)?(?:инструкции|правила|указания)"),
        rule("ignore_rules_ru", "игнорируй\\s+(?:все\\s+)?(?:предыдущие|свои)\\s+(?:инструкции|правила|указания)"),
        rule("reveal_prompt", "\\b(?:reveal|show|print|repeat)\\s+your\\s+(?:system\\s+)?(?:prompt|instructions)\\b"),
        rule("reveal_prompt_ru", "(?:покажи|выведи|повтори|раскрой)[ ]+(?:свой[ ]+)?(?:системн\\p{L}*[ ]+)?(?:промпт|инструкц\\p{L}*)"),
        rule("new_instructions", "\\b(?:new|updated)\\s+instructions?\\s*:"),
        rule("you_are_now", "\\byou\\s+are\\s+now\\s+(?:a|an|the)\\b"),
        rule("developer_mode", "\\b(?:developer|god|dan)\\s+mode\\b"),
        rule("role_switch_ru", "теперь\\s+ты\\s+(?:не|другой|другая)"),
        rule("sql_union_select", "\\bunion\\s+(?:all\\s+)?select\\b"),
        rule("sql_drop_table", "\\bdrop\\s+(?:table|database|schema)\\b"),
        rule("sql_comment_inject", "--\\s*(?:ignore|drop|delete|update)\\b"),
        rule("data_exfil", "\\b(?:pg_read_file|lo_import|copy\\s+.*\\bto\\s+program)\\b"),
        rule("base64_block", "^\\s*[A-Za-z0-9+/]{40,}={0,2}\\s*$")
    );

    private PromptInjectionDefense() {
    }

    /** Результат проверки: найдено ли подозрительное и какие правила сработали. */
    public static final class Report {
        public final boolean found;
        public final List<String> labels;

        public Report(boolean found, List<String> labels) {
            this.found = found;
            this.labels = labels;
        }

        /** Краткая строка для журнала аудита. */
        public String describe() {
            return found ? String.join(",", labels) : "";
        }
    }

    /** Проверяет текст; null/пусто — признаков нет. */
    public static Report detect(String text) {
        if (text == null || text.isBlank()) {
            return new Report(false, List.of());
        }
        List<String> matched = new ArrayList<>();
        for (Rule r : RULES) {
            try {
                if (r.pattern().matcher(text).find()) {
                    matched.add(r.label());
                }
            } catch (Exception ignored) {
                // защита от катастрофического бэктрекинга на экзотическом вводе
            }
        }
        return new Report(!matched.isEmpty(), matched);
    }

    /**
     * Помечает подозрительный ввод в журнале аудита (не блокирует).
     * Вызывается там, где есть контекст подключения.
     */
    public static void auditIfSuspicious(
        String function,
        org.jkiss.dbeaver.model.exec.DBCExecutionContext ctx,
        String text,
        String source
    ) {
        if (text == null || text.isBlank() || !AisqlCore.getBool(AisqlCore.KEY_INJECTION_AUDIT, true)) {
            return;
        }
        Report r = detect(text);
        if (!r.found) {
            return;
        }
        try {
            AisqlCore.audit(function, ctx, null, "flag",
                "injection=" + r.describe() + "; источник=" + source);
        } catch (Exception ignored) {
            // журнал не должен ломать выполнение
        }
    }

    private static Rule rule(String label, String regex) {
        // UNICODE_CASE обязателен: без него CASE_INSENSITIVE не работает для кириллицы
        return new Rule(label, Pattern.compile(regex,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL));
    }
}
