/*
 * AI SQL for DBeaver — сессия правки выделения ИИ (Ctrl+Alt+E).
 *
 * Порядок работы: проверка выделения → карточка с полем инструкции →
 * фоновая генерация (Job, потоковый ответ) → предпросмотр в редакторе.
 * В режиме «дифф» (по умолчанию) показываются точечные замены: «было»
 * подсвечено и зачёркнуто, «стало» нарисовано рядом; замены можно
 * отмечать галочками и применять выборочно. В режиме «full» показывается
 * весь новый фрагмент целиком. В базе данных ничего не выполняется —
 * меняется только текст редактора.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.ui.edit;

import com.aisql.dbeaver.core.AisqlCore;
import com.aisql.dbeaver.ui.ChatPromptSender;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.IRewriteTarget;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.KeyListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Shell;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.DefaultProgressMonitor;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Сессия правки выделения ИИ в SQL-редакторе.
 */
public final class AiEditSession {

    /** Имя события в журнале аудита. */
    public static final String AUDIT_FUNCTION = "ai_edit";

    /** Обновление потокового предпросмотра — не чаще раза в 150 мс. */
    private static final int PREVIEW_THROTTLE_MS = 150;

    private final Shell shell;
    private final SQLEditor editor;
    private final SourceViewer viewer;
    private final IDocument document;
    private final int offset;
    private final int length;
    private final String original;
    private final String connectionName;
    private final boolean directMode;
    private final String previewMode;
    private final Point anchor;

    private AiEditCard card;
    private AiEditGhost ghost;
    private AiEditDiffPainter painter;
    private Job job;
    private String instruction;
    private String generated;
    private long startedAt;
    private long lastPreviewAt;
    private boolean finished;
    /** Текст в редакторе изменился — предпросмотр снят, применять нельзя. */
    private boolean stale;

    private final List<AiEditDiff.Hunk> hunks = new ArrayList<>();
    private boolean[] selected = new boolean[0];
    private int activeHunk = -1;

    private IDocumentListener documentListener;
    private KeyListener navigationKeys;

    private AiEditSession(Shell shell, SQLEditor editor, SourceViewer viewer, IDocument document,
                          int offset, int length, String original, String connectionName,
                          boolean directMode, String previewMode) {
        this.shell = shell;
        this.editor = editor;
        this.viewer = viewer;
        this.document = document;
        this.offset = offset;
        this.length = length;
        this.original = original;
        this.connectionName = connectionName;
        this.directMode = directMode;
        this.previewMode = previewMode;
        this.anchor = computeAnchor(widgetOffset(offset + length));
    }

    /** Точка входа команды: проверки + открытие карточки. */
    public static void start(Shell shell, SQLEditor editor) {
        if (editor == null) {
            return;
        }
        if (!AisqlCore.getBool(AisqlCore.KEY_EDIT_ENABLED, true)) {
            MessageDialog.openInformation(shell, "AI SQL",
                "Правка выделения ИИ отключена в настройках плагина (edit.enabled).");
            return;
        }
        ISelection selection = editor.getSelectionProvider() == null
            ? null : editor.getSelectionProvider().getSelection();
        if (!(selection instanceof ITextSelection textSelection) || textSelection.getLength() <= 0) {
            MessageDialog.openInformation(shell, "AI SQL",
                "Выделите фрагмент SQL в редакторе и повторите (Ctrl+Alt+E).");
            return;
        }
        SourceViewer viewer = editor.getViewer();
        IDocument document = viewer == null ? null : viewer.getDocument();
        if (document == null) {
            MessageDialog.openInformation(shell, "AI SQL",
                "Редактор ещё не готов — откройте SQL-редактор и повторите.");
            return;
        }
        int offset = textSelection.getOffset();
        int length = textSelection.getLength();
        String text;
        try {
            text = document.get(offset, length);
        } catch (BadLocationException e) {
            MessageDialog.openInformation(shell, "AI SQL",
                "Не удалось прочитать выделенный фрагмент: " + e.getMessage());
            return;
        }
        if (text.isBlank()) {
            MessageDialog.openInformation(shell, "AI SQL",
                "Выделен пустой фрагмент — выделите текст и повторите.");
            return;
        }
        int maxChars = AisqlCore.getInt(AisqlCore.KEY_EDIT_MAX_SELECTION,
            AisqlCore.DEFAULT_EDIT_MAX_SELECTION);
        if (AiEditLogic.isTooLarge(text, maxChars)) {
            MessageDialog.openInformation(shell, "AI SQL",
                "Выделено " + text.length() + " символов — это больше лимита " + maxChars
                    + " (edit.maxSelectionChars). Сократите выделение "
                    + "или увеличьте лимит в настройках.");
            return;
        }
        DBPDataSourceContainer container = editor.getDataSourceContainer();
        String connectionName = container == null ? null : container.getName();
        boolean direct = AisqlCore.EDIT_MODE_DIRECT.equalsIgnoreCase(
            AisqlCore.getSetting(AisqlCore.KEY_EDIT_APPLY_MODE, AisqlCore.EDIT_MODE_PREVIEW));
        String previewMode = AisqlCore.getSetting(AisqlCore.KEY_EDIT_PREVIEW_MODE,
            AisqlCore.EDIT_PREVIEW_DIFF);
        if (!AisqlCore.EDIT_PREVIEW_FULL.equalsIgnoreCase(previewMode)) {
            previewMode = AisqlCore.EDIT_PREVIEW_DIFF;
        }
        new AiEditSession(shell, editor, viewer, document, offset, length, text,
            connectionName, direct, previewMode).open();
    }

    private void open() {
        card = new AiEditCard(shell, anchor, new AiEditCard.Callbacks() {
            @Override
            public void onGenerate(String value) {
                generate(value);
            }

            @Override
            public void onApplyToEditor() {
                applyToEditor();
            }

            @Override
            public void onChat() {
                sendToChat();
            }

            @Override
            public void onCancel() {
                cancel();
            }

            @Override
            public void onHunkSelected(int index) {
                navigateTo(index);
            }

            @Override
            public void onHunkToggled(int index, boolean checked) {
                setHunkSelected(index, checked);
            }

            @Override
            public void onToggleAll(boolean value) {
                setAllSelected(value);
            }
        });
        card.open();
        card.positionOnScreen();
        card.focusInstruction();
        attachDocumentListener();
        attachNavigationKeys();
    }

    // ----------------------------------------------------------------------
    //  Генерация
    // ----------------------------------------------------------------------

    private void generate(String value) {
        if (finished || stale) {
            return;
        }
        instruction = value;
        if (job != null && job.getState() != Job.NONE) {
            return;
        }
        hunks.clear();
        selected = new boolean[0];
        activeHunk = -1;
        if (card != null) {
            card.clearHunks();
        }
        unbindCards();
        card.setBusy();
        startedAt = System.currentTimeMillis();
        String systemPrompt = AiEditLogic.systemPrompt();
        String userMessage = AiEditLogic.userMessage(instruction, original, connectionName);
        job = new Job("AI SQL: правка выделения") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                DBRProgressMonitor progress = new DefaultProgressMonitor(monitor);
                try {
                    AiEditClient.generate(progress, systemPrompt, userMessage, new AiEditClient.Sink() {
                        @Override
                        public void onDelta(String accumulated) {
                            showPreview(accumulated, false);
                        }

                        @Override
                        public void onDone(String fullText) {
                            generationDone(fullText);
                        }
                    });
                    return Status.OK_STATUS;
                } catch (Exception e) {
                    if (monitor.isCanceled()) {
                        return Status.CANCEL_STATUS;
                    }
                    failed(e);
                    return Status.OK_STATUS;
                }
            }
        };
        job.setUser(true);
        job.schedule();
    }

    /** Потоковое обновление предпросмотра (не чаще PREVIEW_THROTTLE_MS). */
    private void showPreview(String raw, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastPreviewAt < PREVIEW_THROTTLE_MS) {
            return;
        }
        lastPreviewAt = now;
        String preview = AiEditLogic.extractReplacement(raw);
        if (preview.isEmpty()) {
            preview = raw.trim();
        }
        if (AisqlCore.EDIT_PREVIEW_FULL.equals(previewMode)) {
            final String text = preview;
            UIUtils.asyncExec(() -> {
                if (finished || stale || card == null || card.isClosed()) {
                    return;
                }
                ensureGhost().show(text, widgetOffset(offset + length), force);
            });
            return;
        }
        final List<AiEditDiff.Hunk> previewHunks = AiEditDiff.diff(original, preview);
        UIUtils.asyncExec(() -> {
            if (finished || stale || card == null || card.isClosed()
                || AisqlCore.EDIT_PREVIEW_FULL.equals(previewMode)) {
                return;
            }
            if (previewHunks.isEmpty()) {
                ensurePainter().clear();
            } else {
                ensurePainter().setMarks(marksFor(previewHunks, null, -1));
            }
        });
    }

    private void generationDone(String fullText) {
        String replacement = AiEditLogic.extractReplacement(fullText);
        UIUtils.asyncExec(() -> {
            if (finished || card == null || card.isClosed()) {
                return;
            }
            if (replacement.isBlank()) {
                failed(new IllegalStateException("Модель вернула пустой ответ."));
                return;
            }
            generated = replacement;
            if (stale) {
                return;
            }
            if (AisqlCore.EDIT_PREVIEW_FULL.equals(previewMode)) {
                ensureGhost().show(replacement, widgetOffset(offset + length), true);
                if (directMode) {
                    applyToEditor();
                } else {
                    card.setDoneFull(replacement.length());
                }
                return;
            }
            List<AiEditDiff.Hunk> found = AiEditDiff.diff(original, replacement);
            if (found.isEmpty()) {
                ensurePainter().clear();
                card.setNoChanges();
                return;
            }
            hunks.clear();
            hunks.addAll(found);
            selected = new boolean[hunks.size()];
            Arrays.fill(selected, true);
            activeHunk = 0;
            ensurePainter().setMarks(buildMarks());
            card.setHunksDone(hunkTitles(), hunkDeltas(), selected);
            card.setActiveHunk(0);
            if (directMode) {
                applyToEditor();
            }
        });
    }

    private void failed(Throwable error) {
        UIUtils.asyncExec(() -> {
            if (finished || card == null || card.isClosed()) {
                return;
            }
            disposePreview();
            String message = messageOf(error);
            writeAudit("error", "ERROR", message, null);
            card.setError("Ошибка генерации: " + message + errorHint(message));
        });
    }

    /** Понятная подсказка к частым ошибкам генерации. */
    private static String errorHint(String message) {
        String lower = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("request failed")) {
            return " (сервер модели не ответил или оборвал соединение — "
                + "проверьте, что он доступен и не перегружен)";
        }
        if (lower.contains("token is not set")) {
            return " (в настройках ИИ DBeaver не задан API-ключ: Preferences → AI)";
        }
        return "";
    }

    // ----------------------------------------------------------------------
    //  Предпросмотр: навигация и выбор замен
    // ----------------------------------------------------------------------

    private void navigateTo(int index) {
        if (finished || hunks.isEmpty() || index < 0 || index >= hunks.size()) {
            return;
        }
        activeHunk = index;
        updateMarks();
        if (card != null) {
            card.setActiveHunk(index);
        }
        AiEditDiff.Hunk hunk = hunks.get(index);
        int start = Math.min(Math.max(0, offset + hunk.start),
            Math.max(0, document.getLength() - 1));
        viewer.revealRange(start, 1);
    }

    private void setHunkSelected(int index, boolean checked) {
        if (finished || index < 0 || index >= selected.length) {
            return;
        }
        selected[index] = checked;
        updateMarks();
    }

    private void setAllSelected(boolean value) {
        if (finished) {
            return;
        }
        Arrays.fill(selected, value);
        updateMarks();
        if (card != null) {
            card.updateAllSelected(selected);
        }
    }

    // ----------------------------------------------------------------------
    //  Применение / отмена / чат
    // ----------------------------------------------------------------------

    private void applyToEditor() {
        if (finished) {
            return;
        }
        if (job != null && job.getState() != Job.NONE) {
            job.cancel();
        }
        if (generated == null || generated.isBlank()) {
            return;
        }
        if (stale || !documentUnchanged()) {
            staleAbort();
            return;
        }
        if (AisqlCore.EDIT_PREVIEW_FULL.equals(previewMode)) {
            applyFull();
        } else {
            applyDiff();
        }
    }

    private void applyFull() {
        finished = true;
        String replacement = generated;
        disposePreview();
        IRewriteTarget target = viewer.getRewriteTarget();
        if (target != null) {
            target.beginCompoundChange();
        }
        try {
            document.replace(offset, length, replacement);
        } catch (BadLocationException e) {
            writeAudit("error", "ERROR", messageOf(e), null);
            detachListeners();
            closeCard();
            MessageDialog.openError(shell, "AI SQL",
                "Не удалось применить правку: " + e.getMessage());
            return;
        } finally {
            if (target != null) {
                target.endCompoundChange();
            }
        }
        viewer.setSelectedRange(offset + replacement.length(), 0);
        viewer.revealRange(offset, replacement.length());
        writeAudit("applied", "OK", null, null);
        detachListeners();
        closeCard();
        editor.setFocus();
    }

    private void applyDiff() {
        List<Integer> chosen = new ArrayList<>();
        for (int i = 0; i < hunks.size(); i++) {
            if (selected[i]) {
                chosen.add(i);
            }
        }
        if (chosen.isEmpty()) {
            return;
        }
        // Точная сверка участков перед применением (текст мог измениться).
        for (int index : chosen) {
            AiEditDiff.Hunk hunk = hunks.get(index);
            try {
                if (!document.get(offset + hunk.start, hunk.end - hunk.start)
                    .equals(hunk.originalText)) {
                    staleAbort();
                    return;
                }
            } catch (BadLocationException e) {
                staleAbort();
                return;
            }
        }
        finished = true;
        disposePreview();
        IRewriteTarget target = viewer.getRewriteTarget();
        if (target != null) {
            target.beginCompoundChange();
        }
        try {
            for (int k = chosen.size() - 1; k >= 0; k--) {
                AiEditDiff.Hunk hunk = hunks.get(chosen.get(k));
                document.replace(offset + hunk.start, hunk.end - hunk.start, hunk.replacementText);
            }
        } catch (BadLocationException e) {
            writeAudit("error", "ERROR", messageOf(e), null);
            detachListeners();
            closeCard();
            MessageDialog.openError(shell, "AI SQL",
                "Не удалось применить правку: " + e.getMessage());
            return;
        } finally {
            if (target != null) {
                target.endCompoundChange();
            }
        }
        AiEditDiff.Hunk first = hunks.get(chosen.get(0));
        int caret = offset + first.start + first.replacementText.length();
        viewer.setSelectedRange(Math.min(caret, document.getLength()), 0);
        viewer.revealRange(Math.min(offset + first.start, Math.max(0, document.getLength() - 1)),
            Math.max(1, first.replacementText.length()));
        writeAudit("applied", "OK", null,
            "применено замен: " + chosen.size() + " из " + hunks.size());
        detachListeners();
        closeCard();
        editor.setFocus();
    }

    private void staleAbort() {
        finished = true;
        disposePreview();
        hunks.clear();
        detachListeners();
        closeCard();
        writeAudit("canceled", "STALE", null, "текст в редакторе изменился");
        MessageDialog.openWarning(shell, "AI SQL",
            "Текст в редакторе изменился после запуска генерации — правка отменена. "
                + "Выделите фрагмент заново (Ctrl+Alt+E).");
    }

    private void cancel() {
        if (finished) {
            closeCard();
            return;
        }
        boolean wasGenerating = job != null && job.getState() != Job.NONE;
        if (job != null) {
            job.cancel();
        }
        finished = true;
        disposePreview();
        detachListeners();
        if (wasGenerating || generated != null) {
            writeAudit("canceled", "OK", null, null);
        }
        closeCard();
    }

    private void sendToChat() {
        if (finished) {
            return;
        }
        if (job != null) {
            job.cancel();
        }
        finished = true;
        disposePreview();
        String prompt = AiEditLogic.chatPrompt(
            instruction == null ? "" : instruction, original, connectionName);
        writeAudit("chat", "OK", null, null);
        detachListeners();
        closeCard();
        ChatPromptSender.sendOrCopy(shell, prompt);
    }

    // ----------------------------------------------------------------------
    //  Предпросмотр: построение и обновление
    // ----------------------------------------------------------------------

    /** Отметки для painter-а по текущим заменам и галочкам. */
    private List<AiEditDiffPainter.Mark> buildMarks() {
        return marksFor(hunks, selected, activeHunk);
    }

    private List<AiEditDiffPainter.Mark> marksFor(List<AiEditDiff.Hunk> list, boolean[] selection,
                                                  int active) {
        List<AiEditDiffPainter.Mark> marks = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            AiEditDiff.Hunk hunk = list.get(i);
            int widgetStart = widgetOffsetStrict(offset + hunk.start);
            int widgetEnd = widgetOffsetStrict(offset + hunk.end);
            if (widgetStart < 0 || widgetEnd < 0) {
                continue;
            }
            boolean isSelected = selection == null
                || (i < selection.length && selection[i]);
            marks.add(new AiEditDiffPainter.Mark(widgetStart, widgetEnd, hunk.insertion,
                hunk.addedLines, isSelected, i == active));
        }
        return marks;
    }

    private void updateMarks() {
        if (painter != null) {
            painter.setMarks(buildMarks());
        }
    }

    private AiEditDiffPainter ensurePainter() {
        if (painter == null) {
            painter = new AiEditDiffPainter(viewer);
        }
        return painter;
    }

    private AiEditGhost ensureGhost() {
        if (ghost == null) {
            ghost = new AiEditGhost(viewer);
        }
        return ghost;
    }

    private void disposePreview() {
        if (ghost != null) {
            ghost.dispose();
            ghost = null;
        }
        if (painter != null) {
            painter.dispose();
            painter = null;
        }
    }

    /** Снять оформление предпросмотра, не освобождая painter. */
    private void unbindCards() {
        if (ghost != null) {
            ghost.clear();
        }
        if (painter != null) {
            painter.clear();
        }
    }

    /** Подписи замен: «Замена 1 · стр. 12–14» / «Замена 2 · перед стр. 20». */
    private List<String> hunkTitles() {
        List<String> titles = new ArrayList<>(hunks.size());
        for (int i = 0; i < hunks.size(); i++) {
            AiEditDiff.Hunk hunk = hunks.get(i);
            String where = "?";
            try {
                if (hunk.insertion) {
                    int line = document.getLineOfOffset(
                        Math.min(offset + hunk.start, document.getLength())) + 1;
                    where = "перед стр. " + line;
                } else {
                    int first = document.getLineOfOffset(offset + hunk.start) + 1;
                    int last = document.getLineOfOffset(
                        offset + Math.max(hunk.start, hunk.end - 1)) + 1;
                    where = first == last ? "стр. " + first : "стр. " + first + "–" + last;
                }
            } catch (BadLocationException e) {
                // оставим «?»
            }
            titles.add("Замена " + (i + 1) + " · " + where);
        }
        return titles;
    }

    /** Краткая мера изменения: «−3 +1», «−1», «+2». */
    private List<String> hunkDeltas() {
        List<String> deltas = new ArrayList<>(hunks.size());
        for (AiEditDiff.Hunk hunk : hunks) {
            StringBuilder sb = new StringBuilder();
            if (hunk.removedLineCount() > 0) {
                sb.append('−').append(hunk.removedLineCount());
            }
            if (hunk.addedLineCount() > 0) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append('+').append(hunk.addedLineCount());
            }
            deltas.add(sb.toString());
        }
        return deltas;
    }

    // ----------------------------------------------------------------------
    //  Слушатели
    // ----------------------------------------------------------------------

    /** Изменение документа — предпросмотр снимается (позиции устаревают). */
    private void attachDocumentListener() {
        documentListener = new IDocumentListener() {
            @Override
            public void documentAboutToBeChanged(DocumentEvent event) {
            }

            @Override
            public void documentChanged(DocumentEvent event) {
                if (finished || stale) {
                    return;
                }
                stale = true;
                UIUtils.asyncExec(() -> {
                    if (finished || card == null || card.isClosed()) {
                        return;
                    }
                    if (job != null && job.getState() != Job.NONE) {
                        job.cancel();
                    }
                    finished = true;
                    disposePreview();
                    detachListeners();
                    card.clearHunks();
                    card.setStale("Текст в редакторе изменился — предпросмотр снят. "
                        + "Нажмите «Отмена» и запустите правку заново (Ctrl+Alt+E).");
                });
            }
        };
        document.addDocumentListener(documentListener);
    }

    /** Alt+↓ / Alt+↑ в редакторе — переход между заменами. */
    private void attachNavigationKeys() {
        final StyledText widget = viewer.getTextWidget();
        if (widget == null) {
            return;
        }
        navigationKeys = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (finished || stale || hunks.isEmpty()
                    || (event.stateMask & SWT.ALT) == 0) {
                    return;
                }
                if (event.keyCode == SWT.ARROW_DOWN || event.keyCode == SWT.ARROW_UP) {
                    int step = event.keyCode == SWT.ARROW_DOWN ? 1 : -1;
                    int index = (Math.max(0, activeHunk) + step + hunks.size()) % hunks.size();
                    navigateTo(index);
                    event.doit = false;
                }
            }
        };
        widget.addKeyListener(navigationKeys);
    }

    private void detachListeners() {
        if (documentListener != null) {
            document.removeDocumentListener(documentListener);
            documentListener = null;
        }
        if (navigationKeys != null) {
            StyledText widget = viewer.getTextWidget();
            if (widget != null && !widget.isDisposed()) {
                widget.removeKeyListener(navigationKeys);
            }
            navigationKeys = null;
        }
    }

    // ----------------------------------------------------------------------
    //  Вспомогательные
    // ----------------------------------------------------------------------

    private boolean documentUnchanged() {
        try {
            return document.getLength() >= offset + length
                && original.equals(document.get(offset, length));
        } catch (BadLocationException e) {
            return false;
        }
    }

    private int widgetOffset(int modelOffset) {
        int strict = widgetOffsetStrict(modelOffset);
        if (strict >= 0) {
            return strict;
        }
        StyledText text = viewer.getTextWidget();
        return text == null ? 0 : text.getCaretOffset();
    }

    /** Модельный → виджетный offset; −1, если позиция не отображается (фолдинг). */
    private int widgetOffsetStrict(int modelOffset) {
        int clamped = Math.max(0, Math.min(modelOffset, document.getLength()));
        if (viewer instanceof ITextViewerExtension5 extension) {
            return extension.modelOffset2WidgetOffset(clamped);
        }
        return clamped;
    }

    private Point computeAnchor(int widgetOffset) {
        StyledText text = viewer.getTextWidget();
        if (text == null || text.isDisposed()) {
            return null;
        }
        int clamped = Math.max(0, Math.min(widgetOffset, Math.max(0, text.getCharCount())));
        Point point = text.getLocationAtOffset(clamped);
        point.y += text.getLineHeight();
        return text.toDisplay(point);
    }

    private void closeCard() {
        if (card != null) {
            card.closeQuietly();
        }
    }

    private void writeAudit(String decision, String outcome, String error, String extra) {
        if (!AisqlCore.getBool(AisqlCore.KEY_EDIT_AUDIT, true)) {
            return;
        }
        StringBuilder detail = new StringBuilder();
        detail.append("режим=").append(directMode ? "direct" : "preview")
            .append('/').append(previewMode)
            .append("; фрагмент=").append(original.length()).append(" симв.");
        if (generated != null) {
            detail.append("; результат=").append(generated.length()).append(" симв.");
        }
        if (!hunks.isEmpty()) {
            detail.append("; замен=").append(hunks.size());
        }
        if (instruction != null && !instruction.isBlank()) {
            detail.append("; инструкция: ").append(AisqlCore.oneLine(instruction, 160));
        }
        if (extra != null) {
            detail.append("; ").append(extra);
        }
        long ms = startedAt > 0 ? System.currentTimeMillis() - startedAt : -1;
        AisqlCore.auditUi(AUDIT_FUNCTION, connectionName, decision, detail.toString(), outcome,
            generated == null ? -1 : generated.length(), ms, error);
    }

    private static String messageOf(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.isBlank()) {
            message = error == null ? "неизвестная ошибка" : error.getClass().getSimpleName();
        }
        return AisqlCore.oneLine(message, 300);
    }
}
