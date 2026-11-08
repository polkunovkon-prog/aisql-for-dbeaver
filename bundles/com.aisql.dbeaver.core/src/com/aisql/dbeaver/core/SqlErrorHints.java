/*
 * AI SQL for DBeaver — подсказки по ошибкам выполнения SQL.
 *
 * Превращает типовые ошибки СУБД в конструктивные указания для модели,
 * замыкая цикл «запрос → ошибка → понимание → исправление» без участия
 * человека. Подсказка дописывается к тексту ошибки, который возвращается
 * модели (и виден пользователю в карточке результата).
 *
 * Сопоставление: маркеры `any` — достаточно любого (коды SQLSTATE и их
 * текстовые варианты), маркеры `all` — требуются все (чтобы отличать
 * «relation does not exist» от «column does not exist»).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.core;

import java.util.List;
import java.util.Locale;

/**
 * Распознавание типовых ошибок СУБД и подсказки для модели.
 */
public final class SqlErrorHints {

    /** Одно правило: альтернативные и обязательные маркеры + подсказка. */
    private record Hint(List<String> any, List<String> all, String advice) {
    }

    private static final List<Hint> HINTS = List.of(
        new Hint(List.of("current transaction is aborted", "25p02"), List.of(),
            "Транзакция прервана предыдущей ошибкой: сначала выполни ROLLBACK "
                + "(db_executeSql с sql=ROLLBACK), затем повтори запрос."),
        new Hint(List.of("deadlock detected", "40p01"), List.of(),
            "Обнаружена взаимоблокировка. Выполни ROLLBACK и повтори операцию позже."),
        new Hint(List.of(), List.of("relation", "does not exist"),
            "Проверь точное имя таблицы/представления через db_searchObjects "
                + "(поиск по подстроке имени), затем используй полное имя схема.таблица."),
        new Hint(List.of(), List.of("column", "does not exist"),
            "Проверь точное имя колонки через db_getObjectDdl для этой таблицы "
                + "или db_searchObjects, и используй квалифицированные имена."),
        new Hint(List.of("syntax error at or near", "syntax error near"), List.of(),
            "Синтаксическая ошибка рядом с указанным токеном. Проверь диалект СУБД "
                + "(db_getContext) и запятые/скобки/ключевые слова; можно прогнать "
                + "запрос через db_validateQuery."),
        new Hint(List.of("permission denied", "access denied"), List.of(),
            "Недостаточно прав учётной записи СУБД для этой операции. Попроси "
                + "пользователя выполнить операцию с подходящими правами."),
        new Hint(List.of("duplicate key value violates unique constraint"), List.of(),
            "Нарушение уникального ограничения: строка с таким ключом уже есть. "
                + "Проверь существующие строки (db_runQuery) перед вставкой."),
        new Hint(List.of("violates foreign key constraint"), List.of(),
            "Нарушение внешнего ключа: связанной строки нет. Проверь наличие "
                + "родительской строки до вставки/обновления."),
        new Hint(List.of("canceling statement due to statement timeout",
            "statement cancelled due to timeout", "query was cancelled",
            "cancelled due to timeout"), List.of(),
            "Превышен таймаут выполнения. Упрости запрос (фильтры, LIMIT, индексы) "
                + "или попроси пользователя увеличить limit.timeoutSec."),
        new Hint(List.of("too many connections"), List.of(),
            "Исчерпан лимит соединений СУБД — это не ошибка запроса. Сообщи пользователю."),
        new Hint(List.of(), List.of("database", "is being accessed by other users"),
            "База занята другими сессиями. Попроси пользователя завершить "
                + "посторонние подключения и повторить."),
        new Hint(List.of("value too long for type"), List.of(),
            "Значение не помещается в колонку. Уточни максимальную длину через "
                + "db_getObjectDdl и сократи значение."),
        new Hint(List.of("division by zero", "divide by zero"), List.of(),
            "Деление на ноль. Добавь проверку знаменателя (NULLIF(x, 0) или CASE)."),
        new Hint(List.of("unterminated quoted string"), List.of(),
            "Незакрытая кавычка в строковом литерале. Проверь парность кавычек; "
                + "апострофы внутри строк удваиваются ('').")
    );

    private SqlErrorHints() {
    }

    /**
     * Подсказка для текста ошибки либо {@code null}, если шаблон не распознан.
     */
    public static String hintFor(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return null;
        }
        String msg = errorMessage.toLowerCase(Locale.ROOT);
        for (Hint h : HINTS) {
            boolean anyOk = h.any().isEmpty();
            for (String marker : h.any()) {
                if (msg.contains(marker.toLowerCase(Locale.ROOT))) {
                    anyOk = true;
                    break;
                }
            }
            if (!anyOk) {
                continue;
            }
            boolean allOk = true;
            for (String marker : h.all()) {
                if (!msg.contains(marker.toLowerCase(Locale.ROOT))) {
                    allOk = false;
                    break;
                }
            }
            if (allOk) {
                return h.advice();
            }
        }
        return null;
    }
}
