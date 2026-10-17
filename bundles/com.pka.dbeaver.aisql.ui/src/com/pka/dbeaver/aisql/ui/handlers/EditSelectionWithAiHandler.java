/*
 * AI SQL for DBeaver — команда «Править выделенное ИИ» (SQL-редактор).
 *
 * Выделенный фрагмент заменяется по инструкции пользователя с предпросмотром
 * прямо в редакторе (Ctrl+Alt+E). Подробности — AiEditSession.
 *
 * Лицензия: Apache-2.0
 */
package com.pka.dbeaver.aisql.ui.handlers;

import com.pka.dbeaver.aisql.ui.edit.AiEditSession;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;

/**
 * Берёт выделенный в SQL-редакторе текст и запускает сессию правки ИИ.
 */
public class EditSelectionWithAiHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        IEditorPart editor = window == null || window.getActivePage() == null
            ? null
            : window.getActivePage().getActiveEditor();
        if (editor instanceof SQLEditor sqlEditor) {
            AiEditSession.start(HandlerUtil.getActiveShell(event), sqlEditor);
        } else {
            MessageDialog.openInformation(HandlerUtil.getActiveShell(event), "AI SQL",
                "Откройте SQL-редактор, выделите фрагмент и повторите (Ctrl+Alt+E).");
        }
        return null;
    }
}
