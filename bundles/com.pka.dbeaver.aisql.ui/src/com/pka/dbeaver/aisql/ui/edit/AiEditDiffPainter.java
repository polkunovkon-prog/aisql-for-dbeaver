/*
 * AI SQL for DBeaver — отрисовка точечных замен в SQL-редакторе.
 *
 * Показывает предпросмотр диффа прямо в тексте, не меняя документ:
 *   - «было» — строки, которые будут заменены, подсвечены красным фоном
 *     и зачёркнуты (приглушённо, если замена не отмечена);
 *   - «стало» — строки замены рисуются зелёным в месте замены; под них
 *     резервируется вертикальное место (StyledText.setLineVerticalIndent —
 *     тот же приём, которым DBeaver показывает многострочные SQL-подсказки),
 *     поэтому строки документа не «раздвигаются» и нумерация не меняется;
 *   - активная замена обведена рамкой.
 *
 * Painter рисует поверх редактора через PaintListener/LineBackgroundListener
 * и полностью снимает своё оформление при clear()/dispose().
 *
 * Лицензия: Apache-2.0
 */
package com.pka.dbeaver.aisql.ui.edit;

import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.LineBackgroundEvent;
import org.eclipse.swt.custom.LineBackgroundListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Device;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Предпросмотр точечных замен поверх текста (без изменения документа).
 */
final class AiEditDiffPainter {

    /** Одна замена для отображения (координаты — в виджете редактора). */
    static final class Mark {

        final int widgetStart;
        final int widgetEnd;
        final boolean insertion;
        final List<String> added;
        final boolean selected;
        final boolean active;

        /** Строки виджета: первая/последняя заменяемая (для вставки lastLine = firstLine-1). */
        int firstLine = -1;
        int lastLine = -2;
        /** Строка, над которой зарезервировано место под «стало». */
        int gapLine = -1;
        /** Высота резерва, пикс. */
        int indent;
        /** Резерв установлен (место под «стало» есть). */
        boolean hasGap;

        Mark(int widgetStart, int widgetEnd, boolean insertion, List<String> added,
             boolean selected, boolean active) {
            this.widgetStart = widgetStart;
            this.widgetEnd = widgetEnd;
            this.insertion = insertion;
            this.added = added == null ? List.of() : added;
            this.selected = selected;
            this.active = active;
        }
    }

    private final ITextViewer viewer;
    private final StyledText widget;

    private Color removedBg;
    private Color removedActiveBg;
    private Color removedDimBg;
    private Color strike;
    private Color strikeActive;
    private Color strikeDim;
    private Color addedBg;
    private Color addedDimBg;
    private Color addedFg;
    private Color addedDimFg;
    private Color border;

    private final List<Mark> marks = new ArrayList<>();
    /** Линии, которым выставлен наш отступ: линия → высота. */
    private final Map<Integer, Integer> indentedLines = new HashMap<>();

    private final PaintListener paintListener = this::onPaint;
    private final LineBackgroundListener backgroundListener = this::onLineBackground;

    private boolean disposed;

    AiEditDiffPainter(ITextViewer viewer) {
        this.viewer = viewer;
        this.widget = viewer == null ? null : viewer.getTextWidget();
        Device device = widget == null || widget.isDisposed() ? null : widget.getDisplay();
        if (device != null) {
            removedBg = new Color(device, 0xFF, 0xEB, 0xEB);
            removedActiveBg = new Color(device, 0xFF, 0xD2, 0xD2);
            removedDimBg = new Color(device, 0xF0, 0xF0, 0xF0);
            strike = new Color(device, 0xC0, 0x39, 0x2B);
            strikeActive = new Color(device, 0x99, 0x2B, 0x1F);
            strikeDim = new Color(device, 0xB0, 0xB0, 0xB0);
            addedBg = new Color(device, 0xDF, 0xF0, 0xD8);
            addedDimBg = new Color(device, 0xF0, 0xF0, 0xF0);
            addedFg = new Color(device, 0x1E, 0x7A, 0x33);
            addedDimFg = new Color(device, 0x90, 0x90, 0x90);
            border = new Color(device, 0x60, 0x60, 0x60);
        }
        if (widget != null && !widget.isDisposed()) {
            widget.addPaintListener(paintListener);
            widget.addLineBackgroundListener(backgroundListener);
        }
    }

    /** Показать замены (пересчитывает резервы и перерисовывает). */
    void setMarks(List<Mark> newMarks) {
        if (widget == null || widget.isDisposed()) {
            return;
        }
        clearIndents();
        marks.clear();
        if (newMarks != null) {
            marks.addAll(newMarks);
        }
        int charCount = widget.getCharCount();
        int lineCount = widget.getLineCount();
        int lineHeight = widget.getLineHeight();
        for (Mark mark : marks) {
            int start = clamp(mark.widgetStart, 0, charCount);
            int end = clamp(mark.widgetEnd, start, charCount);
            if (mark.insertion || start == end) {
                mark.firstLine = widget.getLineAtOffset(start);
                mark.lastLine = mark.firstLine - 1;
            } else {
                mark.firstLine = widget.getLineAtOffset(start);
                mark.lastLine = widget.getLineAtOffset(Math.max(start, end - 1));
            }
        }
        // Резервы под «стало» — после расчёта строк всех замен, чтобы не
        // задеть отступы соседних замен.
        for (Mark mark : marks) {
            if (mark.added.isEmpty()) {
                continue;
            }
            int gapLine;
            if (mark.lastLine >= 0) {
                gapLine = mark.lastLine + 1;
                if (gapLine >= lineCount) {
                    // Замена в конце документа: место резервируется над блоком.
                    gapLine = Math.max(0, mark.firstLine);
                }
            } else {
                gapLine = Math.max(0, Math.min(mark.firstLine, lineCount - 1));
            }
            if (indentedLines.containsKey(gapLine)) {
                // Крайне редкий случай (замены в самом конце документа):
                // второй резерв на той же линии не ставим.
                continue;
            }
            int indent = mark.added.size() * lineHeight;
            if (indent <= 0) {
                continue;
            }
            int existing = widget.getLineVerticalIndent(gapLine);
            widget.setLineVerticalIndent(gapLine, existing + indent);
            indentedLines.put(gapLine, indent);
            mark.gapLine = gapLine;
            mark.indent = indent;
            mark.hasGap = true;
        }
        widget.redraw();
    }

    /** Снять оформление (слушатели остаются). */
    void clear() {
        if (widget == null || widget.isDisposed()) {
            return;
        }
        clearIndents();
        marks.clear();
        widget.redraw();
    }

    /** Полная очистка: снять оформление, слушателей и освободить цвета. */
    void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        if (widget != null && !widget.isDisposed()) {
            clearIndents();
            widget.removePaintListener(paintListener);
            widget.removeLineBackgroundListener(backgroundListener);
            widget.redraw();
        }
        for (Color color : new Color[] { removedBg, removedActiveBg, removedDimBg,
            strike, strikeActive, strikeDim, addedBg, addedDimBg, addedFg, addedDimFg, border }) {
            if (color != null && !color.isDisposed()) {
                color.dispose();
            }
        }
        marks.clear();
    }

    // ----------------------------------------------------------------------

    private void clearIndents() {
        if (widget == null || widget.isDisposed()) {
            indentedLines.clear();
            return;
        }
        int lineCount = widget.getLineCount();
        for (Map.Entry<Integer, Integer> entry : indentedLines.entrySet()) {
            int line = entry.getKey();
            if (line >= 0 && line < lineCount
                && widget.getLineVerticalIndent(line) >= entry.getValue()) {
                int existing = widget.getLineVerticalIndent(line);
                widget.setLineVerticalIndent(line, Math.max(0, existing - entry.getValue()));
            }
        }
        indentedLines.clear();
    }

    private void onLineBackground(LineBackgroundEvent event) {
        if (disposed || marks.isEmpty() || widget == null || widget.isDisposed()) {
            return;
        }
        int line = widget.getLineAtOffset(event.lineOffset);
        for (Mark mark : marks) {
            if (mark.lastLine >= mark.firstLine
                && line >= mark.firstLine && line <= mark.lastLine) {
                event.lineBackground = mark.selected
                    ? (mark.active ? removedActiveBg : removedBg)
                    : removedDimBg;
                return;
            }
        }
    }

    private void onPaint(PaintEvent event) {
        if (disposed || marks.isEmpty() || widget == null || widget.isDisposed()) {
            return;
        }
        GC gc = event.gc;
        if (gc == null || gc.isDisposed()) {
            return;
        }
        try {
            for (Mark mark : marks) {
                if (mark.hasGap && !mark.added.isEmpty()) {
                    drawAdded(gc, mark);
                }
            }
            for (Mark mark : marks) {
                if (mark.lastLine >= mark.firstLine) {
                    drawStrike(gc, mark);
                }
            }
            for (Mark mark : marks) {
                if (mark.active) {
                    drawBorder(gc, mark);
                }
            }
        } catch (RuntimeException e) {
            // Отрисовка предпросмотра никогда не должна ломать редактор.
        }
    }

    /** Строки «стало»: зелёный фон и текст в зарезервированном месте. */
    private void drawAdded(GC gc, Mark mark) {
        int lineHeight = widget.getLineHeight();
        int top = widget.getLinePixel(mark.gapLine);
        int width = widget.getClientArea().width;
        int margin = widget.getLeftMargin();
        int fontHeight = gc.getFontMetrics().getHeight();
        int textOffset = Math.max(0, lineHeight - fontHeight);
        for (int i = 0; i < mark.added.size(); i++) {
            int rowTop = top + i * lineHeight;
            gc.setBackground(mark.selected ? addedBg : addedDimBg);
            gc.fillRectangle(0, rowTop, width, lineHeight);
            String line = mark.added.get(i);
            if (!line.isEmpty()) {
                gc.setForeground(mark.selected ? addedFg : addedDimFg);
                gc.drawString(line, margin, rowTop + textOffset, true);
            }
        }
    }

    /** Зачёркивание «было» по точному диапазону замены. */
    private void drawStrike(GC gc, Mark mark) {
        int lineHeight = widget.getLineHeight();
        gc.setLineWidth(mark.active ? 2 : 1);
        gc.setForeground(mark.selected
            ? (mark.active ? strikeActive : strike) : strikeDim);
        int start = clamp(mark.widgetStart, 0, widget.getCharCount());
        int end = clamp(mark.widgetEnd, start, widget.getCharCount());
        for (int line = mark.firstLine; line <= mark.lastLine; line++) {
            int lineStart = widget.getOffsetAtLine(line);
            int lineEnd = line + 1 < widget.getLineCount()
                ? widget.getOffsetAtLine(line + 1) - 1
                : widget.getCharCount();
            int from = Math.max(start, lineStart);
            int to = Math.min(end, lineEnd);
            if (to <= from) {
                continue;
            }
            Point a = widget.getLocationAtOffset(from);
            Point b = widget.getLocationAtOffset(to);
            int y = a.y + lineHeight / 2;
            gc.drawLine(a.x, y, Math.max(a.x + 1, b.x), y);
        }
        gc.setLineWidth(1);
    }

    /** Рамка активной замены: вокруг «было» и вокруг «стало». */
    private void drawBorder(GC gc, Mark mark) {
        int lineHeight = widget.getLineHeight();
        gc.setForeground(border);
        gc.setLineWidth(1);
        if (mark.lastLine >= mark.firstLine && mark.firstLine >= 0) {
            int top = lineTop(mark.firstLine);
            int bottom = lineTop(mark.lastLine) + lineHeight;
            gc.drawRectangle(0, top - 1, widget.getClientArea().width - 1, bottom - top + 1);
        }
        if (mark.hasGap) {
            int top = widget.getLinePixel(mark.gapLine);
            gc.drawRectangle(0, top - 1, widget.getClientArea().width - 1,
                mark.indent + 1);
        }
    }

    private int lineTop(int line) {
        return widget.getLocationAtOffset(widget.getOffsetAtLine(line)).y;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    /** Создать цвета на текущем дисплее (для тестовых проверок painter-а). */
    @SuppressWarnings("unused")
    private static Color color(StyledText widget, int r, int g, int b) {
        Display display = widget.getDisplay();
        return new Color(display, r, g, b);
    }
}
