/*
 * AI SQL for DBeaver — сессия правки выделения ИИ (Ctrl+Alt+E).
 *
 * Порядок работы: проверка выделения → карточка с полем инструкции →
 * фоновая генерация (Job, потоковый ответ) → «призрачный» предпросмотр
 * в редакторе → применение замены одной операцией (один Ctrl+Z) или отмена.
 * В базе данных ничего не выполняется — меняется только текст редактора.
 *
 * Лицензия: Apache-2.0
 */
package com.pka.dbeaver.aisql.ui.edit;

import com.pka.dbeaver.aisql.core.AisqlCore;
import com.pka.dbeaver.aisql.ui.ChatPromptSender;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRewriteTarget;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Shell;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.DefaultProgressMonitor;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;

/**
 * Сессия правки выделения ИИ в SQL-редакторе.
 */
public final class AiEditSession {

    /** Имя события в журнале аудита. */
    public static final String AUDIT_FUNCTION = "ai_edit";

    private final Shell shell;
    private final SQLEditor editor;
    private final SourceViewer viewer;
    private final IDocument document;
    private final int offset;
    private final int length;
    private final String original;
    private final String connectionName;
    private final boolean directMode;
    private final Point anchor;

    private AiEditCard card;
    private AiEditGhost ghost;
    private Job job;
    private String instruction;
    private String generated;
    private long startedAt;
    private boolean finished;

    private AiEditSession(Shell shell, SQLEditor editor, SourceViewer viewer, IDocument document,
                          int offset, int length, String original, String connectionName,
                          boolean directMode) {
        this.shell = shell;
        this.editor = editor;
        this.viewer = viewer;
        this.document = document;
        this.offset = offset;
        this.length = length;
        this.original = original;
        this.connectionName = connectionName;
        this.directMode = directMode;
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
        new AiEditSession(shell, editor, viewer, document, offset, length, text,
            connectionName, direct).open();
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
        });
        card.open();
        card.positionOnScreen();
        card.focusInstruction();
    }

    // ----------------------------------------------------------------------
    //  Генерация
    // ----------------------------------------------------------------------

    private void generate(String value) {
        if (finished) {
            return;
        }
        instruction = value;
        if (job != null && job.getState() != Job.NONE) {
            return;
        }
        if (ghost == null) {
            ghost = new AiEditGhost(viewer);
        }
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
                            showGhost(accumulated, false);
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

    private void showGhost(String raw, boolean force) {
        String preview = AiEditLogic.extractReplacement(raw);
        if (preview.isEmpty()) {
            preview = raw.trim();
        }
        final String text = preview;
        UIUtils.asyncExec(() -> {
            if (finished || ghost == null || card == null || card.isClosed()) {
                return;
            }
            ghost.show(text, widgetOffset(offset + length), force);
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
            if (ghost != null) {
                ghost.show(replacement, widgetOffset(offset + length), true);
            }
            if (directMode) {
                applyToEditor();
            } else {
                card.setDone(replacement.length());
            }
        });
    }

    private void failed(Throwable error) {
        UIUtils.asyncExec(() -> {
            if (finished || card == null || card.isClosed()) {
                return;
            }
            if (ghost != null) {
                ghost.clear();
            }
            writeAudit("error", "ERROR", messageOf(error), null);
            card.setError("Ошибка генерации: " + messageOf(error));
        });
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
        if (!documentUnchanged()) {
            finished = true;
            disposeGhost();
            closeCard();
            writeAudit("canceled", "STALE", null, "текст в редакторе изменился");
            MessageDialog.openWarning(shell, "AI SQL",
                "Текст в редакторе изменился после запуска генерации — правка отменена. "
                    + "Выделите фрагмент заново (Ctrl+Alt+E).");
            return;
        }
        finished = true;
        String replacement = generated;
        disposeGhost();
        IRewriteTarget target = viewer.getRewriteTarget();
        if (target != null) {
            target.beginCompoundChange();
        }
        try {
            document.replace(offset, length, replacement);
        } catch (BadLocationException e) {
            writeAudit("error", "ERROR", messageOf(e), null);
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
        closeCard();
        editor.setFocus();
    }

    private void cancel() {
        if (finished) {
            closeCard();
            return;
        }
        finished = true;
        boolean wasGenerating = job != null && job.getState() != Job.NONE;
        if (job != null) {
            job.cancel();
        }
        disposeGhost();
        if (wasGenerating || generated != null) {
            writeAudit("canceled", "OK", null, null);
        }
        closeCard();
    }

    private void sendToChat() {
        if (finished) {
            return;
        }
        finished = true;
        if (job != null) {
            job.cancel();
        }
        disposeGhost();
        String prompt = AiEditLogic.chatPrompt(
            instruction == null ? "" : instruction, original, connectionName);
        writeAudit("chat", "OK", null, null);
        closeCard();
        ChatPromptSender.sendOrCopy(shell, prompt);
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
        int clamped = Math.max(0, Math.min(modelOffset, document.getLength()));
        if (viewer instanceof ITextViewerExtension5 extension) {
            int widget = extension.modelOffset2WidgetOffset(clamped);
            if (widget >= 0) {
                return widget;
            }
        }
        StyledText text = viewer.getTextWidget();
        return text == null ? 0 : text.getCaretOffset();
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

    private void disposeGhost() {
        if (ghost != null) {
            ghost.dispose();
            ghost = null;
        }
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
            .append("; фрагмент=").append(original.length()).append(" симв.");
        if (generated != null) {
            detail.append("; результат=").append(generated.length()).append(" симв.");
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
