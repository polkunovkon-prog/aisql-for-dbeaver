/*
 * AI SQL for DBeaver — команда «Спросить ИИ о выделенном» (SQL-редактор).
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.ui.handlers;

import com.aisql.dbeaver.ui.ChatPromptSender;
import com.aisql.dbeaver.ui.PromptBuilder;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;

/**
 * Берёт выделенный в SQL-редакторе текст, формирует промпт и вставляет
 * его в поле ИИ-чата. Отправку выполняет пользователь.
 */
public class AskAboutSelectionHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        IEditorPart editor = window == null || window.getActivePage() == null
            ? null
            : window.getActivePage().getActiveEditor();

        String text = null;
        String connectionName = null;
        if (editor instanceof SQLEditor sqlEditor) {
            ISelection selection = sqlEditor.getSelectionProvider() == null
                ? null
                : sqlEditor.getSelectionProvider().getSelection();
            if (selection instanceof ITextSelection textSelection) {
                String t = textSelection.getText();
                if (t != null && !t.isBlank()) {
                    text = t;
                }
            }
            DBPDataSourceContainer container = sqlEditor.getDataSourceContainer();
            if (container != null) {
                connectionName = container.getName();
            }
        }

        if (text == null) {
            MessageDialog.openInformation(HandlerUtil.getActiveShell(event), "AI SQL",
                "Выделите текст в SQL-редакторе и повторите (Ctrl+Alt+A).");
            return null;
        }

        ChatPromptSender.sendOrCopy(HandlerUtil.getActiveShell(event),
            PromptBuilder.forSelection(text, connectionName));
        return null;
    }
}
