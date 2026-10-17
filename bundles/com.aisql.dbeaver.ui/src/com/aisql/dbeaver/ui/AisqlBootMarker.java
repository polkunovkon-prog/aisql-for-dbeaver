/*
 * AI SQL for DBeaver — стартовый маркер UI-бандла.
 *
 * Класс намеренно пуст: его загрузка лениво активирует бандл при старте
 * рабочей среды, и активатор AisqlUIPlugin пишет в журнал строку
 * «AI SQL UI plugin started, version …». Эта строка используется
 * контрольным запуском releng/install.sh и для диагностики проблем
 * загрузки плагина.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.ui;

import org.eclipse.ui.IStartup;

/**
 * Форсирует активацию UI-бандла при старте рабочей среды (см. комментарий).
 */
public class AisqlBootMarker implements IStartup {

    @Override
    public void earlyStartup() {
        // Ничего не делает: стартовый маркер пишет активатор бандла.
    }
}
