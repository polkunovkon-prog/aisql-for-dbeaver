/*
 * AI SQL for DBeaver — построчный дифф правки выделения ИИ (Ctrl+Alt+E).
 *
 * Чистая логика без UI/OSGi-зависимостей: сравнивает исходный фрагмент
 * с ответом модели и выдаёт список точечных замен (hunks) — «строки X–Y
 * заменяются на такие-то строки». Диапазоны замен указывают на исходный
 * (не нормализованный) текст, поэтому применимы к документу как есть,
 * даже если в файле переводы строк CRLF.
 *
 * Инвариант (проверяется тестами): применение всех hunk-ов к исходному
 * тексту даёт ровно новый текст модели.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.ui.edit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Построчный дифф: исходный фрагмент + ответ модели → точечные замены.
 */
public final class AiEditDiff {

    /** Предел размера таблицы LCS (клеток); больше — режим «всё одним куском». */
    private static final int MAX_CELLS = 4_000_000;

    /**
     * Одна точечная замена. Диапазон {@code [start,end)} — в координатах
     * исходного текста (как он лежит в документе); {@code replacementText} —
     * на что заменить (взято из ответа модели дословно).
     */
    public static final class Hunk {

        public final int start;
        public final int end;
        /** Исходный текст заменяемого участка (как в документе). */
        public final String originalText;
        /** Текст замены (из ответа модели, дословно). */
        public final String replacementText;
        /** Строки заменяемого участка (без переводов строк) — для отображения. */
        public final List<String> removedLines;
        /** Строки замены (без переводов строк) — для отображения. */
        public final List<String> addedLines;
        /** Номер первой строки фрагмента (0-базовый). */
        public final int firstLine;
        /** Номер последней строки включительно; вставка: {@code firstLine-1}. */
        public final int lastLine;
        /** Чистая вставка строк (без удаления). */
        public final boolean insertion;

        Hunk(int start, int end, String originalText, String replacementText,
             int firstLine, int lastLine, boolean insertion) {
            this.start = start;
            this.end = end;
            this.originalText = originalText;
            this.replacementText = replacementText;
            this.removedLines = splitLines(originalText);
            this.addedLines = splitLines(replacementText);
            this.firstLine = firstLine;
            this.lastLine = lastLine;
            this.insertion = insertion;
        }

        /** Сколько строк исходного фрагмента заменяется. */
        public int removedLineCount() {
            return insertion ? 0 : lastLine - firstLine + 1;
        }

        /** Сколько строк в замене. */
        public int addedLineCount() {
            return addedLines.size();
        }
    }

    private AiEditDiff() {
    }

    /**
     * Сравнить исходный фрагмент с ответом модели.
     *
     * @return список замен по возрастанию позиции; пусто, если текст не изменился
     */
    public static List<Hunk> diff(String original, String replacement) {
        String orig = original == null ? "" : original;
        String repl = normalize(replacement);
        List<Unit> oUnits = units(orig);
        List<Unit> rUnits = units(repl);
        if (unitValues(oUnits).equals(unitValues(rUnits))) {
            return List.of();
        }
        int rTotal = rUnits.size();
        int total = oUnits.size();
        int pre = 0;
        while (pre < total && pre < rTotal
            && oUnits.get(pre).value.equals(rUnits.get(pre).value)) {
            pre++;
        }
        int suf = 0;
        while (suf < total - pre && suf < rTotal - pre
            && oUnits.get(total - 1 - suf).value.equals(rUnits.get(rTotal - 1 - suf).value)) {
            suf++;
        }
        int n = total - pre - suf;
        int m = rTotal - pre - suf;
        if ((long) n * m > MAX_CELLS) {
            return List.of(new Hunk(0, orig.length(), orig, repl, 0, total - 1, false));
        }
        short[][] lcs = lcsTable(oUnits, rUnits, pre, n, m);
        List<int[]> ops = backtrack(oUnits, rUnits, lcs, pre, n, m);
        return buildHunks(orig, repl, oUnits, rUnits, ops, pre);
    }

    /** Применить замены к исходному тексту (по возрастанию позиций). */
    public static String applyAll(String original, List<Hunk> hunks) {
        StringBuilder sb = new StringBuilder(original == null ? "" : original);
        for (int i = hunks.size() - 1; i >= 0; i--) {
            Hunk h = hunks.get(i);
            sb.replace(h.start, h.end, h.replacementText);
        }
        return sb.toString();
    }

    /** Нормализация переводов строк (как в {@link AiEditLogic#extractReplacement}). */
    public static String normalize(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
    }

    // ----------------------------------------------------------------------

    /** Строка фрагмента с переводом строки включительно; span — в исходном тексте. */
    private static final class Unit {

        final String value;
        final int spanStart;
        final int spanEnd;

        Unit(String value, int spanStart, int spanEnd) {
            this.value = value;
            this.spanStart = spanStart;
            this.spanEnd = spanEnd;
        }
    }

    private static List<Unit> units(String text) {
        List<Unit> list = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                list.add(new Unit(normalizeLine(text, start, i + 1), start, i + 1));
                start = i + 1;
            }
        }
        list.add(new Unit(normalizeLine(text, start, text.length()), start, text.length()));
        return list;
    }

    /** Строка текста {@code [from,to)} без «\r» (CRLF → LF), но с «\n» на конце. */
    private static String normalizeLine(String text, int from, int to) {
        StringBuilder sb = new StringBuilder(to - from);
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 < to && text.charAt(i + 1) == '\n') {
                    continue; // \r\n → останется \n
                }
                sb.append('\n'); // одиночный \r — тоже перевод строки
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static List<String> unitValues(List<Unit> units) {
        List<String> list = new ArrayList<>(units.size());
        for (Unit unit : units) {
            list.add(unit.value);
        }
        return list;
    }

    private static short[][] lcsTable(List<Unit> oUnits, List<Unit> rUnits, int pre, int n, int m) {
        short[][] lcs = new short[n + 1][m + 1];
        for (int i = 1; i <= n; i++) {
            String o = oUnits.get(pre + i - 1).value;
            short[] row = lcs[i];
            short[] prev = lcs[i - 1];
            for (int j = 1; j <= m; j++) {
                if (o.equals(rUnits.get(pre + j - 1).value)) {
                    row[j] = (short) (prev[j - 1] + 1);
                } else {
                    row[j] = (short) Math.max(prev[j], row[j - 1]);
                }
            }
        }
        return lcs;
    }

    /**
     * Обратный ход по таблице LCS → список операций.
     * Тип: 0 — строки равны (i,j); 1 — удалить строку i; 2 — вставить строку j.
     */
    private static List<int[]> backtrack(List<Unit> oUnits, List<Unit> rUnits,
                                         short[][] lcs, int pre, int n, int m) {
        List<int[]> ops = new ArrayList<>();
        int i = n;
        int j = m;
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0
                && oUnits.get(pre + i - 1).value.equals(rUnits.get(pre + j - 1).value)) {
                ops.add(new int[] { 0, pre + i - 1, pre + j - 1 });
                i--;
                j--;
            } else if (i > 0 && (j == 0 || lcs[i - 1][j] >= lcs[i][j - 1])) {
                ops.add(new int[] { 1, pre + i - 1, -1 });
                i--;
            } else {
                ops.add(new int[] { 2, -1, pre + j - 1 });
                j--;
            }
        }
        Collections.reverse(ops);
        return ops;
    }

    /** Сгруппировать операции в замены (подряд идущие удаления/вставки — одна замена). */
    private static List<Hunk> buildHunks(String orig, String repl, List<Unit> oUnits,
                                         List<Unit> rUnits, List<int[]> ops, int pre) {
        List<Hunk> hunks = new ArrayList<>();
        int idx = 0;
        int oCursor = pre;
        int rCursor = pre;
        while (idx < ops.size()) {
            if (ops.get(idx)[0] == 0) {
                oCursor = ops.get(idx)[1] + 1;
                rCursor = ops.get(idx)[2] + 1;
                idx++;
                continue;
            }
            int groupStartO = oCursor;
            int groupStartR = rCursor;
            int firstDel = -1;
            int dels = 0;
            int firstAdd = -1;
            int adds = 0;
            while (idx < ops.size() && ops.get(idx)[0] != 0) {
                int[] cur = ops.get(idx);
                if (cur[0] == 1) {
                    if (firstDel < 0) {
                        firstDel = cur[1];
                    }
                    dels++;
                    oCursor = cur[1] + 1;
                } else {
                    if (firstAdd < 0) {
                        firstAdd = cur[2];
                    }
                    adds++;
                    rCursor = cur[2] + 1;
                }
                idx++;
            }
            // Координаты участка в исходном тексте.
            int oa;
            int ob;
            int insPoint;
            if (dels > 0) {
                oa = oUnits.get(firstDel).spanStart;
                ob = oUnits.get(firstDel + dels - 1).spanEnd;
                insPoint = -1;
            } else {
                insPoint = groupStartO;
                oa = insPoint >= oUnits.size()
                    ? orig.length() : oUnits.get(insPoint).spanStart;
                ob = oa;
            }
            // Координаты участка в тексте ответа.
            int ra;
            int rb;
            if (adds > 0) {
                ra = rUnits.get(firstAdd).spanStart;
                rb = rUnits.get(firstAdd + adds - 1).spanEnd;
            } else {
                // Чистое удаление: точка вставки в ответе — конец группы.
                ra = groupStartR >= rUnits.size()
                    ? repl.length() : rUnits.get(groupStartR).spanStart;
                rb = ra;
            }
            String originalText = orig.substring(oa, ob);
            String replacementText = repl.substring(ra, rb);
            int firstLine = dels > 0 ? firstDel : insPoint;
            int lastLine = dels > 0 ? firstDel + dels - 1 : insPoint - 1;
            hunks.add(new Hunk(oa, ob, originalText, replacementText,
                firstLine, lastLine, dels == 0));
        }
        return hunks;
    }

    /** Строки текста без переводов строк (для отображения). */
    private static List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return List.of();
        }
        String[] parts = normalized.split("\n", -1);
        List<String> list = new ArrayList<>(parts.length);
        for (String part : parts) {
            list.add(part);
        }
        return list;
    }
}
