/*
 * AI SQL for DBeaver — тесты построчного диффа (AiEditDiff).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.tests;

import com.aisql.dbeaver.ui.edit.AiEditDiff;

import java.util.List;

/**
 * Точечные замены: локализация изменений, диапазоны, инвариант применения.
 */
public final class AiEditDiffTest {

    private AiEditDiffTest() {
    }

    public static void run() {
        T.section("AiEditDiff: точечные замены");

        T.eq(List.of(), AiEditDiff.diff("SELECT 1", "SELECT 1"), "без изменений — пусто");
        T.eq(List.of(), AiEditDiff.diff("SELECT 1\r\nFROM t", "SELECT 1\nFROM t"),
            "различие только в переводах строк — не замена");
        T.eq(List.of(), AiEditDiff.diff(null, null), "null-тексты — пусто");

        // Одна изменённая строка в середине.
        String orig = "SELECT a, b\nFROM t\nWHERE x = 1";
        String repl = "SELECT a, b\nFROM t\nWHERE x = 2";
        List<AiEditDiff.Hunk> hunks = AiEditDiff.diff(orig, repl);
        T.eq(1, hunks.size(), "одна изменённая строка — одна замена");
        AiEditDiff.Hunk h = hunks.get(0);
        T.eq("WHERE x = 1", h.originalText, "исходный текст замены");
        T.eq("WHERE x = 2", h.replacementText, "текст замены");
        T.eq(2, h.firstLine, "номер первой строки замены");
        T.eq(2, h.lastLine, "номер последней строки замены");
        T.check(!h.insertion, "замена строки — не вставка");
        T.eq(repl, AiEditDiff.applyAll(orig, hunks), "применение замены даёт ответ модели");

        // Две отдельные замены (верх и низ), середина не тронута.
        orig = "SELECT a, b\nFROM t\nWHERE x = 1\nORDER BY a";
        repl = "SELECT a, c\nFROM t\nWHERE x = 1\nORDER BY c";
        hunks = AiEditDiff.diff(orig, repl);
        T.eq(2, hunks.size(), "изменения в начале и конце — две замены");
        T.eq(0, hunks.get(0).firstLine, "первая замена — строка 0");
        T.eq(3, hunks.get(1).firstLine, "вторая замена — строка 3");
        T.check(!hunks.get(0).insertion && !hunks.get(1).insertion, "это не вставки");
        T.eq(repl, AiEditDiff.applyAll(orig, hunks), "две замены применяются вместе");

        // Блок подряд изменённых строк — одна замена.
        orig = "SELECT a,\n  b,\n  c\nFROM t";
        repl = "SELECT a, x\n  c\nFROM t";
        hunks = AiEditDiff.diff(orig, repl);
        T.eq(1, hunks.size(), "подряд изменённые строки — одна замена");
        T.eq(0, hunks.get(0).firstLine, "блок начинается со строки 0");
        T.eq(1, hunks.get(0).lastLine, "блок заканчивается строкой 1");
        T.eq(2, hunks.get(0).removedLineCount(), "удалено две строки");
        T.eq(1, hunks.get(0).addedLineCount(), "добавлена одна строка");
        T.eq(repl, AiEditDiff.applyAll(orig, hunks), "блочная замена применяется");

        // Добавление строки в конец (заменяется последняя строка).
        orig = "SELECT a\nFROM t\nWHERE x = 1";
        repl = "SELECT a\nFROM t\nWHERE x = 1\n  AND y = 2";
        hunks = AiEditDiff.diff(orig, repl);
        T.eq(1, hunks.size(), "добавление строки в конец — одна замена");
        T.check(!hunks.get(0).insertion, "строка в конце — замена последней строки");
        T.eq(1, hunks.get(0).removedLineCount(), "заменяется одна строка");
        T.eq(2, hunks.get(0).addedLineCount(), "стало две строки");
        T.eq(repl, AiEditDiff.applyAll(orig, hunks), "добавление в конец применяется");

        repl = "SELECT a\nFROM t\nWHERE x = 1\n  AND y = 2\n  AND z = 3";
        hunks = AiEditDiff.diff(orig, repl);
        T.eq(1, hunks.size(), "две добавленные строки — одна замена");
        T.eq(1, hunks.get(0).removedLineCount(), "заменяется последняя строка");
        T.eq(3, hunks.get(0).addedLineCount(), "три строки в замене");
        T.eq(repl, AiEditDiff.applyAll(orig, hunks), "многострочная замена применяется");

        // Чистая вставка строки в середину.
        orig = "BEGIN\n  SELECT 1;\nEND";
        repl = "BEGIN\n  SELECT 1;\n  COMMIT;\nEND";
        hunks = AiEditDiff.diff(orig, repl);
        T.eq(1, hunks.size(), "вставка в середине — одна замена");
        T.check(hunks.get(0).insertion, "это чистая вставка");
        T.eq(2, hunks.get(0).firstLine, "вставка перед строкой 2");
        T.eq(1, hunks.get(0).lastLine, "у вставки нет заменяемых строк");
        T.eq(0, hunks.get(0).removedLineCount(), "вставка ничего не удаляет");
        T.eq(1, hunks.get(0).addedLineCount(), "вставлена одна строка");
        T.check(hunks.get(0).start == hunks.get(0).end, "вставка — пустой диапазон");
        T.eq(repl, AiEditDiff.applyAll(orig, hunks), "вставка применяется");

        // Удаление строки в середине.
        orig = "SELECT a\nFROM t\nWHERE x = 1\n  AND y = 2\nORDER BY a";
        repl = "SELECT a\nFROM t\nWHERE x = 1\nORDER BY a";
        hunks = AiEditDiff.diff(orig, repl);
        T.eq(1, hunks.size(), "удаление строки — одна замена");
        T.eq(3, hunks.get(0).firstLine, "удаляется строка 3");
        T.eq("  AND y = 2\n", hunks.get(0).originalText, "удаляемая строка с переводом строки");
        T.eq("", hunks.get(0).replacementText, "замена пустая");
        T.eq(repl, AiEditDiff.applyAll(orig, hunks), "удаление применяется");

        // CRLF в документе: диапазоны указывают на исходный текст с \r\n.
        orig = "SELECT a, b\r\nFROM t";
        repl = "SELECT a, c\nFROM t";
        hunks = AiEditDiff.diff(orig, repl);
        T.eq(1, hunks.size(), "CRLF: одна замена");
        T.eq("SELECT a, b\r\n", hunks.get(0).originalText, "CRLF: исходный текст как в документе");
        T.eq("SELECT a, c\n", hunks.get(0).replacementText, "CRLF: текст замены");
        T.eq(0, hunks.get(0).start, "CRLF: замена с начала текста");
        T.eq(13, hunks.get(0).end, "CRLF: диапазон в исходных координатах");
        T.eq(AiEditDiff.normalize(repl),
            AiEditDiff.normalize(AiEditDiff.applyAll(orig, hunks)),
            "CRLF: применение даёт ответ модели");

        // «Упростить запрос»: две далёкие друг от друга замены.
        orig = "SELECT t.a,\n       t.b,\n  FROM schema1.table1 t\n WHERE 1 = 1\n   AND t.a = 1";
        repl = "SELECT a,\n       b,\n  FROM table1 t\n WHERE 1 = 1\n   AND a = 1";
        hunks = AiEditDiff.diff(orig, repl);
        T.eq(2, hunks.size(), "упрощение: две замены");
        T.eq(0, hunks.get(0).firstLine, "упрощение: первая замена — строки 0–2");
        T.eq(2, hunks.get(0).lastLine, "упрощение: блок из трёх строк");
        T.eq(4, hunks.get(1).firstLine, "упрощение: вторая замена — строка 4");
        T.eq(repl, AiEditDiff.applyAll(orig, hunks), "упрощение применяется целиком");
        T.eq(repl, AiEditDiff.applyAll(orig, AiEditDiff.diff(orig, repl)),
            "повторный дифф детерминирован");

        String partially = AiEditDiff.applyAll(orig, List.of(hunks.get(1)));
        T.check(partially.contains("schema1.table1"),
            "частичное применение не трогает первую замену");
        T.check(partially.contains("   AND a = 1"), "частично применена вторая замена");
        T.check(!partially.contains("   AND t.a = 1"), "старый текст второй замены ушёл");

        // Страховка: слишком большой LCS — одна замена «всё целиком».
        StringBuilder bigOrig = new StringBuilder();
        StringBuilder bigRepl = new StringBuilder();
        for (int i = 0; i < 2100; i++) {
            bigOrig.append("old_").append(i).append('\n');
            bigRepl.append("new_").append(i).append('\n');
        }
        hunks = AiEditDiff.diff(bigOrig.toString(), bigRepl.toString());
        T.eq(1, hunks.size(), "слишком большой дифф — одна замена целиком");
        T.eq(0, hunks.get(0).start, "замена целиком с начала");
        T.eq(bigOrig.length(), hunks.get(0).end, "замена целиком до конца");
        T.eq(bigRepl.toString(), AiEditDiff.applyAll(bigOrig.toString(), hunks),
            "замена целиком применяется");

        // Хвостовой перевод строки: снятие и добавление.
        hunks = AiEditDiff.diff("SELECT 1\n", "SELECT 1");
        T.eq(1, hunks.size(), "снятие хвостового перевода строки — замена");
        T.eq("SELECT 1", AiEditDiff.applyAll("SELECT 1\n", hunks),
            "снятие перевода строки применяется");
        hunks = AiEditDiff.diff("SELECT 1", "SELECT 1\n");
        T.eq(1, hunks.size(), "добавление хвостового перевода строки — замена");
        T.eq("SELECT 1\n", AiEditDiff.applyAll("SELECT 1", hunks),
            "добавление перевода строки применяется");
    }
}
