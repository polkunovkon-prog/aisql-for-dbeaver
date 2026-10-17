/*
 * AI SQL for DBeaver — команда «Спросить ИИ о данных» (таблица результатов).
 *
 * Собирает выделенные ячейки/строки (или строку под курсором) в текстовый
 * промпт и вставляет его в поле ИИ-чата.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.ui.handlers;

import com.aisql.dbeaver.core.AisqlCore;
import com.aisql.dbeaver.ui.ChatPromptSender;
import com.aisql.dbeaver.ui.PromptBuilder;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.ui.controls.resultset.IResultSetController;
import org.jkiss.dbeaver.ui.controls.resultset.IResultSetProvider;
import org.jkiss.dbeaver.ui.controls.resultset.IResultSetSelection;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetModel;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Берёт выделенные данные таблицы результатов, формирует промпт
 * и вставляет его в поле ИИ-чата.
 */
public class AskAboutCellHandler extends AbstractHandler {

    private static final int MAX_ROWS = 5;
    private static final int MAX_CELL_CHARS = 300;

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);
        IResultSetController controller = findResultSetController();
        if (controller == null) {
            MessageDialog.openInformation(shell, "AI SQL",
                "Установите курсор в таблицу результатов (или выделите ячейки) "
                    + "и повторите (Ctrl+Alt+I).");
            return null;
        }
        ResultSetModel model = controller.getModel();
        DBDAttributeBinding[] allAttributes =
            model == null ? null : model.getAttributes();
        if (allAttributes == null || allAttributes.length == 0) {
            MessageDialog.openInformation(shell, "AI SQL",
                "В таблице результатов нет данных.");
            return null;
        }

        List<DBDAttributeBinding> attributes;
        List<ResultSetRow> rows;
        try {
            IResultSetSelection selection = controller.getSelection();
            attributes = selection == null
                ? new ArrayList<>()
                : new ArrayList<>(selection.getSelectedAttributes());
            rows = selection == null
                ? new ArrayList<>()
                : new ArrayList<>(selection.getSelectedRows());
        } catch (Exception e) {
            attributes = new ArrayList<>();
            rows = new ArrayList<>();
        }
        if (attributes.isEmpty()) {
            attributes = List.of(allAttributes);
        }
        if (rows.isEmpty()) {
            ResultSetRow current = controller.getCurrentRow();
            if (current != null) {
                rows.add(current);
            }
        }
        if (rows.isEmpty()) {
            MessageDialog.openInformation(shell, "AI SQL",
                "Не удалось определить строку: щёлкните по нужной строке таблицы результатов.");
            return null;
        }
        boolean rowsTruncated = rows.size() > MAX_ROWS;
        if (rowsTruncated) {
            rows = rows.subList(0, MAX_ROWS);
        }

        List<String> lines = new ArrayList<>();
        for (ResultSetRow row : rows) {
            if (rows.size() > 1 || rowsTruncated) {
                lines.add("строка " + row.getRowNumber() + ":");
            }
            Object[] values = row.getValues();
            for (DBDAttributeBinding attribute : attributes) {
                DBDAttributeBinding top = attribute.getTopParent();
                int index = indexOfIdentity(allAttributes, top == null ? attribute : top);
                Object value = values != null && index >= 0 && index < values.length
                    ? values[index]
                    : null;
                String label = attribute.getLabel();
                if (label == null || label.isEmpty()) {
                    label = attribute.getName();
                }
                lines.add(label + " = " + AisqlCore.formatValue(value, MAX_CELL_CHARS));
            }
        }
        if (rowsTruncated) {
            lines.add("… (показаны первые " + MAX_ROWS + " строк)");
        }

        String entityName = null;
        String connectionName = null;
        try {
            DBSEntity entity = model.getSingleSource();
            if (entity != null) {
                entityName = entity.getName();
                if (entity.getDataSource() != null
                    && entity.getDataSource().getContainer() != null) {
                    connectionName = entity.getDataSource().getContainer().getName();
                }
            }
        } catch (Exception ignored) {
        }
        if (connectionName == null) {
            try {
                DBPDataSourceContainer container = getActiveEditorContainer();
                if (container != null) {
                    connectionName = container.getName();
                }
            } catch (Exception ignored) {
            }
        }

        ChatPromptSender.sendOrCopy(shell,
            PromptBuilder.forDataRows(entityName, connectionName, lines));
        return null;
    }

    private static int indexOfIdentity(DBDAttributeBinding[] attributes,
                                       DBDAttributeBinding attribute) {
        for (int i = 0; i < attributes.length; i++) {
            if (attributes[i] == attribute) {
                return i;
            }
        }
        // Запасной вариант — по имени (на случай пересозданных биндингов).
        for (int i = 0; i < attributes.length; i++) {
            if (attributes[i].getName() != null
                && attributes[i].getName().equals(attribute.getName())) {
                return i;
            }
        }
        return -1;
    }

    private static DBPDataSourceContainer getActiveEditorContainer() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null || window.getActivePage() == null) {
            return null;
        }
        IEditorPart editor = window.getActivePage().getActiveEditor();
        if (editor instanceof IResultSetProvider) {
            IResultSetController controller =
                ((IResultSetProvider) editor).getResultSetController();
            if (controller != null && controller.getControl() != null) {
                Object data = controller.getControl().getData("dbeaver.datasource.container");
                if (data instanceof DBPDataSourceContainer container) {
                    return container;
                }
            }
        }
        return null;
    }

    private static IResultSetController findResultSetController() {
        // 1) Активный редактор данных (standalone DataEditor).
        try {
            IWorkbenchWindow window =
                PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window != null && window.getActivePage() != null) {
                IEditorPart editor = window.getActivePage().getActiveEditor();
                if (editor instanceof IResultSetProvider provider) {
                    IResultSetController controller = provider.getResultSetController();
                    if (controller != null) {
                        return controller;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // 2) По цепочке родителей контрола в фокусе (таблица внутри SQL-редактора).
        try {
            Display display = Display.getCurrent();
            Control focus = display == null ? null : display.getFocusControl();
            while (focus != null) {
                if (focus instanceof IResultSetController controller) {
                    return controller;
                }
                focus = focus.getParent();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
