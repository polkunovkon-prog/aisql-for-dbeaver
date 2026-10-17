/*
 * AI SQL for DBeaver — «призрачный» предпросмотр правки в редакторе.
 *
 * Используется штатный (но не задействованный DBeaver) painter подсказок
 * SQLSuggestionTextPainter; создаётся собственная копия, поэтому конфликтов
 * с будущими вендорскими использованиями нет. Текст рисуется зелёным
 * на светлом фоне сразу после выделенного фрагмента и обновляется по мере
 * потоковой генерации.
 *
 * Лицензия: Apache-2.0
 */
package com.pka.dbeaver.aisql.ui.edit;

import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Device;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.editors.sql.suggestion.SQLSuggestionTextPainter;

/**
 * Обёртка над {@link SQLSuggestionTextPainter}: показать/обновить/убрать
 * подсказку и освободить ресурсы.
 */
final class AiEditGhost {

    private static final int THROTTLE_MS = 100;

    private final SQLSuggestionTextPainter painter;
    private final Color fontColor;
    private final Color background;

    private long lastUpdate;

    AiEditGhost(ITextViewer viewer) {
        this.painter = new SQLSuggestionTextPainter(viewer);
        StyledText widget = viewer.getTextWidget();
        Device device = widget != null && !widget.isDisposed() ? widget.getDisplay() : null;
        this.fontColor = device == null ? null : new Color(device, 0x1E, 0x7A, 0x33);
        this.background = device == null ? null : new Color(device, 0xDF, 0xF0, 0xD8);
        painter.setFontColor(fontColor);
        painter.setSuggestionBackgroundColor(background);
        painter.enable();
    }

    /** Показать очередную версию текста после выделения (не чаще 10 раз/сек). */
    void show(String text, int widgetOffset, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastUpdate < THROTTLE_MS) {
            return;
        }
        lastUpdate = now;
        painter.showHint(text, widgetOffset);
    }

    /** Убрать подсказку (без освобождения). */
    void clear() {
        painter.removeHint();
    }

    /** Полная очистка: снять подсказку, слушателей и освободить цвета. */
    void dispose() {
        UIUtils.runInUIThread(() -> {
            painter.disable(true);
            painter.dispose();
        });
        if (fontColor != null && !fontColor.isDisposed()) {
            fontColor.dispose();
        }
        if (background != null && !background.isDisposed()) {
            background.dispose();
        }
    }
}
