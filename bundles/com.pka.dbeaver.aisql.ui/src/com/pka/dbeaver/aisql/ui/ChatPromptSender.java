/*
 * AI SQL for DBeaver — отправка текста в ИИ-чат DBeaver.
 *
 * Открывает панель ИИ-чата и вставляет текст в поле ввода (без отправки —
 * пользователь сам дополняет вопрос и нажимает Enter). Если поле ввода не
 * найдено (изменилась версия DBeaver), текст копируется в буфер обмена.
 *
 * Лицензия: Apache-2.0
 */
package com.pka.dbeaver.aisql.ui;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.jkiss.dbeaver.ui.ai.chat.AIChatView;
import org.jkiss.dbeaver.ui.ai.chat.controls.AIChatControl;
import org.jkiss.dbeaver.ui.ai.chat.controls.PromptComposite;

/**
 * Вставка подготовленного промпта в ИИ-чат.
 */
public final class ChatPromptSender {

    private ChatPromptSender() {
    }

    /**
     * Открывает ИИ-чат и вставляет текст в поле ввода.
     *
     * @return true, если вставка удалась
     */
    public static boolean insertIntoChat(String text) {
        try {
            AIChatView view = AIChatView.show();
            if (view == null) {
                return false;
            }
            AIChatControl chat = view.getChat();
            if (chat == null) {
                return false;
            }
            PromptComposite prompt = findPrompt(chat);
            if (prompt == null) {
                return false;
            }
            prompt.setPromptText(text);
            prompt.setFocusOnPrompt();
            return true;
        } catch (Exception e) {
            AisqlUIPlugin.log().debug("Не удалось вставить текст в поле ИИ-чата", e);
            return false;
        }
    }

    /**
     * Вставка в чат; при неудаче — копирование в буфер обмена
     * и понятное уведомление пользователю.
     */
    public static void sendOrCopy(Shell shell, String text) {
        if (insertIntoChat(text)) {
            return;
        }
        Display display = shell != null ? shell.getDisplay() : Display.getCurrent();
        if (display != null && !display.isDisposed()) {
            copyToClipboard(display, text);
        }
        MessageDialog.openInformation(shell, "AI SQL",
            "Не удалось автоматически вставить текст в поле ИИ-чата.\n"
                + "Текст скопирован в буфер обмена — вставьте его в поле чата (Ctrl+V).");
    }

    private static void copyToClipboard(Display display, String text) {
        Clipboard clipboard = new Clipboard(display);
        try {
            clipboard.setContents(new Object[] { text },
                new Transfer[] { TextTransfer.getInstance() });
        } finally {
            clipboard.dispose();
        }
    }

    private static PromptComposite findPrompt(Composite root) {
        for (Control child : root.getChildren()) {
            if (child instanceof PromptComposite pc) {
                return pc;
            }
            if (child instanceof Composite composite) {
                PromptComposite found = findPrompt(composite);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
