/*
 * AI SQL for DBeaver — команда открытия страницы настроек плагина.
 *
 * Лицензия: Apache-2.0
 */
package com.pka.dbeaver.aisql.ui.handlers;

import com.pka.dbeaver.aisql.ui.AisqlPreferencePage;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * Открывает настройки плагина AI SQL (используется кнопкой на панели ИИ-чата).
 */
public class OpenSettingsHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell shell = HandlerUtil.getActiveShell(event);
        if (shell == null) {
            return null;
        }
        PreferencesUtil.createPreferenceDialogOn(shell,
            AisqlPreferencePage.PAGE_ID,
            new String[] { AisqlPreferencePage.PAGE_ID },
            null).open();
        return null;
    }
}
