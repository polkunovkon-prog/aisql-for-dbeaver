/*
 * AI SQL for DBeaver — карточка правки выделения ИИ.
 *
 * Небольшое немодальное окно (Dialog) рядом с выделенным фрагментом:
 * поле инструкции, состояние генерации, список точечных замен (в режиме
 * «дифф») и кнопки «Сгенерировать» / «Применить» / «В чат» / «Отмена».
 * Не закрывается при клике в редактор, чтобы правку можно было проверить
 * в предпросмотре.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.ui.edit;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.ShellAdapter;
import org.eclipse.swt.events.ShellEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import java.util.List;

/**
 * Карточка команды «правка выделения ИИ» (Ctrl+Alt+E).
 */
final class AiEditCard extends Dialog {

    /** Обратные вызовы к сессии. */
    interface Callbacks {

        void onGenerate(String instruction);

        void onApplyToEditor();

        void onChat();

        void onCancel();

        /** Пользователь выбрал замену в списке (навигация). */
        void onHunkSelected(int index);

        /** Пользователь переключил галочку замены. */
        void onHunkToggled(int index, boolean checked);

        /** Пользователь нажал «Отметить все» / «Снять все». */
        void onToggleAll(boolean selected);
    }

    private final Point anchor;
    private final Callbacks callbacks;

    private Text instructionText;
    private Label statusLabel;
    private Button generateButton;
    private Button applyButton;
    private Button chatButton;
    private Button toggleAllButton;
    private Table hunkTable;

    /** Локальная копия состояний галочек (для синхронизации событий SWT). */
    private boolean[] checks = new boolean[0];
    private boolean suppressTableEvents;

    private boolean suppressCloseCallback;

    AiEditCard(Shell parent, Point anchor, Callbacks callbacks) {
        super(parent);
        this.anchor = anchor;
        this.callbacks = callbacks;
        setShellStyle(SWT.NO_TRIM | SWT.ON_TOP | SWT.TOOL);
        setBlockOnOpen(false);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        Composite content = new Composite(area, SWT.BORDER);
        content.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        content.setLayout(new GridLayout(1, false));
        content.addTraverseListener(event -> {
            if (event.detail == SWT.TRAVERSE_ESCAPE) {
                event.doit = false;
                callbacks.onCancel();
            }
        });

        Label title = new Label(content, SWT.NONE);
        title.setText("Правка выделения с ИИ (Ctrl+Alt+E)");
        title.setFont(JFaceResources.getBannerFont());
        title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        instructionText = new Text(content, SWT.BORDER);
        instructionText.setMessage("Что сделать с выделенным фрагментом?");
        GridData textData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        textData.widthHint = 440;
        instructionText.setLayoutData(textData);
        KeyAdapter keys = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR) {
                    generate();
                } else if (event.keyCode == SWT.ESC) {
                    callbacks.onCancel();
                } else if ((event.stateMask & SWT.ALT) != 0
                    && (event.keyCode == SWT.ARROW_DOWN || event.keyCode == SWT.ARROW_UP)) {
                    navigate(event.keyCode == SWT.ARROW_DOWN ? 1 : -1);
                }
            }
        };
        instructionText.addKeyListener(keys);

        hunkTable = new Table(content,
            SWT.CHECK | SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        hunkTable.setHeaderVisible(false);
        hunkTable.setLinesVisible(true);
        GridData tableData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        tableData.widthHint = 440;
        tableData.exclude = true;
        hunkTable.setLayoutData(tableData);
        TableColumn titleColumn = new TableColumn(hunkTable, SWT.LEFT);
        titleColumn.setWidth(295);
        TableColumn deltaColumn = new TableColumn(hunkTable, SWT.RIGHT);
        deltaColumn.setWidth(135);
        hunkTable.setVisible(false);
        hunkTable.setEnabled(false);
        hunkTable.addKeyListener(keys);
        hunkTable.addListener(SWT.Selection, event -> syncChecks());
        hunkTable.addControlListener(new org.eclipse.swt.events.ControlAdapter() {
            @Override
            public void controlResized(org.eclipse.swt.events.ControlEvent event) {
                fitColumns();
            }
        });
        hunkTable.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                if (suppressTableEvents) {
                    return;
                }
                int index = hunkTable.getSelectionIndex();
                if (index >= 0) {
                    callbacks.onHunkSelected(index);
                }
            }
        });

        statusLabel = new Label(content, SWT.WRAP);
        statusLabel.setText("Enter — сгенерировать, Esc — отмена");
        GridData statusData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        statusData.widthHint = 440;
        statusLabel.setLayoutData(statusData);

        Composite buttons = new Composite(content, SWT.NONE);
        buttons.setLayout(new RowLayout());
        generateButton = new Button(buttons, SWT.PUSH);
        generateButton.setText("Сгенерировать");
        generateButton.addListener(SWT.Selection, event -> generate());

        applyButton = new Button(buttons, SWT.PUSH);
        applyButton.setText("Применить");
        applyButton.setEnabled(false);
        applyButton.addListener(SWT.Selection, event -> callbacks.onApplyToEditor());

        toggleAllButton = new Button(buttons, SWT.PUSH);
        toggleAllButton.setText("Отметить все");
        toggleAllButton.setVisible(false);
        toggleAllButton.addListener(SWT.Selection, event -> {
            int count = hunkTable.getItemCount();
            boolean all = count > 0;
            for (int i = 0; i < checks.length; i++) {
                if (!checks[i]) {
                    all = false;
                    break;
                }
            }
            callbacks.onToggleAll(!all);
        });

        chatButton = new Button(buttons, SWT.PUSH);
        chatButton.setText("В чат");
        chatButton.addListener(SWT.Selection, event -> callbacks.onChat());

        Button closeButton = new Button(buttons, SWT.PUSH);
        closeButton.setText("Отмена");
        closeButton.addListener(SWT.Selection, event -> callbacks.onCancel());

        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        // Кнопки — в области диалога (см. createDialogArea).
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.addShellListener(new ShellAdapter() {
            @Override
            public void shellClosed(ShellEvent event) {
                if (!suppressCloseCallback) {
                    Display display = event.display;
                    if (display != null && !display.isDisposed()) {
                        display.asyncExec(() -> {
                            if (!suppressCloseCallback) {
                                callbacks.onCancel();
                            }
                        });
                    }
                }
            }
        });
        newShell.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if ((event.stateMask & SWT.ALT) != 0
                    && (event.keyCode == SWT.ARROW_DOWN || event.keyCode == SWT.ARROW_UP)) {
                    navigate(event.keyCode == SWT.ARROW_DOWN ? 1 : -1);
                }
            }
        });
    }

    @Override
    protected Point getInitialLocation(Point initialSize) {
        return clamp(anchor, initialSize);
    }

    /** Поставить окно под выделением (с поправкой на границы экрана). */
    void positionOnScreen() {
        Shell shell = getShell();
        if (shell == null || shell.isDisposed()) {
            return;
        }
        shell.setLocation(clamp(anchor, shell.getSize()));
        shell.forceActive();
    }

    /** Фокус — в поле инструкции. */
    void focusInstruction() {
        if (instructionText != null && !instructionText.isDisposed()) {
            instructionText.setFocus();
            instructionText.selectAll();
        }
    }

    /** Состояние «идёт генерация». */
    void setBusy() {
        instructionText.setEnabled(false);
        generateButton.setEnabled(false);
        toggleAllButton.setEnabled(false);
        resetApplyButton();
        setStatus("Генерация…", false);
    }

    /** Режим «весь фрагмент»: генерация завершена. */
    void setDoneFull(int resultLength) {
        instructionText.setEnabled(true);
        generateButton.setText("Ещё раз");
        generateButton.setEnabled(true);
        applyButton.setText("Применить");
        applyButton.setEnabled(true);
        setStatus("Готово: " + resultLength + " симв. Проверьте предпросмотр "
            + "в редакторе и нажмите «Применить».", false);
    }

    /**
     * Режим точечных замен: показать список замен с галочками.
     *
     * @param titles  подписи замен («Замена 1 · стр. 12–14»)
     * @param deltas  краткая мера изменения («−3 +1», «+2»)
     */
    void setHunksDone(List<String> titles, List<String> deltas, boolean[] selected) {
        instructionText.setEnabled(true);
        generateButton.setText("Ещё раз");
        generateButton.setEnabled(true);
        chatButton.setEnabled(true);
        checks = selected.clone();
        suppressTableEvents = true;
        try {
            hunkTable.removeAll();
            for (int i = 0; i < titles.size(); i++) {
                TableItem item = new TableItem(hunkTable, SWT.NONE);
                item.setText(0, titles.get(i));
                item.setText(1, i < deltas.size() ? deltas.get(i) : "");
                item.setChecked(i < checks.length && checks[i]);
            }
            int rows = Math.min(Math.max(titles.size(), 1), 6);
            GridData tableLayout = (GridData) hunkTable.getLayoutData();
            tableLayout.heightHint = rows * hunkTable.getItemHeight() + 4;
            tableLayout.exclude = false;
            hunkTable.setVisible(true);
            hunkTable.setEnabled(true);
            toggleAllButton.setVisible(true);
            toggleAllButton.setEnabled(true);
            if (!titles.isEmpty()) {
                hunkTable.select(0);
            }
        } finally {
            suppressTableEvents = false;
        }
        updateToggleAllLabel();
        updateApplyLabel();
        setStatus("Готово: " + titles.size() + " " + plural(titles.size(), "замена", "замены", "замен")
            + ". Отметьте нужные и нажмите «Применить». Alt+↑/↓ — переход между заменами.", false);
        relayout();
        fitColumns();
        Display display = hunkTable.getDisplay();
        if (display != null && !display.isDisposed()) {
            display.asyncExec(this::fitColumns);
        }
    }

    /** Подогнать ширину колонок под фактическую ширину списка. */
    private void fitColumns() {
        if (hunkTable.isDisposed() || hunkTable.getColumnCount() < 2) {
            return;
        }
        int width = hunkTable.getClientArea().width;
        if (width <= 0) {
            return;
        }
        int delta = 120;
        hunkTable.getColumn(0).setWidth(Math.max(140, width - delta - 4));
        hunkTable.getColumn(1).setWidth(delta);
    }

    /** Режим точечных замен: модель ничего не изменила. */
    void setNoChanges() {
        instructionText.setEnabled(true);
        generateButton.setText("Повторить");
        generateButton.setEnabled(true);
        resetApplyButton();
        clearHunks();
        setStatus("Модель не изменила фрагмент — применять нечего. "
            + "Измените инструкцию и повторите.", true);
    }

    /** Текст в редакторе изменился — предпросмотр снят. */
    void setStale(String message) {
        instructionText.setEnabled(false);
        generateButton.setEnabled(false);
        toggleAllButton.setEnabled(false);
        hunkTable.setEnabled(false);
        chatButton.setEnabled(false);
        resetApplyButton();
        setStatus(message, true);
    }

    /** Ошибка генерации. */
    void setError(String message) {
        instructionText.setEnabled(true);
        generateButton.setText("Повторить");
        generateButton.setEnabled(true);
        toggleAllButton.setEnabled(false);
        resetApplyButton();
        setStatus(message, true);
    }

    private void resetApplyButton() {
        applyButton.setText("Применить");
        applyButton.setEnabled(false);
    }

    /** Обновить состояния галочек после массового переключения. */
    void updateAllSelected(boolean[] selected) {
        checks = selected.clone();
        suppressTableEvents = true;
        try {
            for (int i = 0; i < hunkTable.getItemCount() && i < checks.length; i++) {
                hunkTable.getItem(i).setChecked(checks[i]);
            }
        } finally {
            suppressTableEvents = false;
        }
        updateToggleAllLabel();
        updateApplyLabel();
    }

    /** Выделить строку замены (без обратного вызова навигации). */
    void setActiveHunk(int index) {
        if (hunkTable.getItemCount() == 0) {
            return;
        }
        suppressTableEvents = true;
        try {
            hunkTable.select(Math.max(0, Math.min(index, hunkTable.getItemCount() - 1)));
        } finally {
            suppressTableEvents = false;
        }
        hunkTable.showSelection();
    }

    /** Скрыть список замен. */
    void clearHunks() {
        checks = new boolean[0];
        suppressTableEvents = true;
        try {
            hunkTable.removeAll();
        } finally {
            suppressTableEvents = false;
        }
        hunkTable.setVisible(false);
        hunkTable.setEnabled(false);
        ((GridData) hunkTable.getLayoutData()).exclude = true;
        toggleAllButton.setVisible(false);
        relayout();
    }

    boolean isClosed() {
        Shell shell = getShell();
        return shell == null || shell.isDisposed();
    }

    /** Закрыть без вызова onCancel (сессия уже всё сделала сама). */
    void closeQuietly() {
        suppressCloseCallback = true;
        close();
    }

    // ----------------------------------------------------------------------

    private void generate() {
        String text = instructionText.getText().trim();
        if (text.isEmpty()) {
            setStatus("Введите инструкцию — что сделать с выделенным фрагментом.", true);
            instructionText.setFocus();
            return;
        }
        callbacks.onGenerate(text);
    }

    private void navigate(int dir) {
        int count = hunkTable.getItemCount();
        if (count == 0) {
            return;
        }
        int current = Math.max(0, hunkTable.getSelectionIndex());
        int next = ((current + dir) % count + count) % count;
        callbacks.onHunkSelected(next);
    }

    /** Склонение существительного при числе (1 замена / 2 замены / 5 замен). */
    private static String plural(int number, String one, String few, String many) {
        int mod100 = number % 100;
        int mod10 = number % 10;
        if (mod100 >= 11 && mod100 <= 14) {
            return many;
        }
        if (mod10 == 1) {
            return one;
        }
        if (mod10 >= 2 && mod10 <= 4) {
            return few;
        }
        return many;
    }

    /** Синхронизация галочек: сообщить сессии о переключениях. */
    private void syncChecks() {
        if (suppressTableEvents || hunkTable.getItemCount() == 0) {
            return;
        }
        for (int i = 0; i < hunkTable.getItemCount(); i++) {
            boolean checked = hunkTable.getItem(i).getChecked();
            if (i >= checks.length) {
                continue;
            }
            if (checked != checks[i]) {
                checks[i] = checked;
                callbacks.onHunkToggled(i, checked);
            }
        }
        updateToggleAllLabel();
        updateApplyLabel();
    }

    private void updateToggleAllLabel() {
        boolean all = checks.length > 0;
        for (boolean check : checks) {
            if (!check) {
                all = false;
                break;
            }
        }
        toggleAllButton.setText(all ? "Снять все" : "Отметить все");
    }

    private void updateApplyLabel() {
        int total = checks.length;
        int selected = 0;
        for (boolean check : checks) {
            if (check) {
                selected++;
            }
        }
        if (total == 0) {
            applyButton.setText("Применить");
            return;
        }
        if (selected == 0) {
            applyButton.setText("Ничего не отмечено");
            applyButton.setEnabled(false);
        } else if (selected == total) {
            applyButton.setText("Применить все");
            applyButton.setEnabled(true);
        } else {
            applyButton.setText("Применить (" + selected + " из " + total + ")");
            applyButton.setEnabled(true);
        }
    }

    private void setStatus(String text, boolean error) {
        statusLabel.setText(text);
        Display display = statusLabel.getDisplay();
        statusLabel.setForeground(error
            ? display.getSystemColor(SWT.COLOR_RED)
            : display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
        Composite parent = statusLabel.getParent();
        if (parent != null) {
            parent.layout(true, true);
        }
    }

    /** Пересчитать размер окна после появления/скрытия списка замен. */
    private void relayout() {
        Shell shell = getShell();
        if (shell == null || shell.isDisposed()) {
            return;
        }
        Composite parent = statusLabel.getParent();
        if (parent != null) {
            parent.layout(true, true);
        }
        Point size = shell.computeSize(460, SWT.DEFAULT);
        shell.setSize(size);
        shell.setLocation(clamp(anchor, size));
    }

    private Point clamp(Point desired, Point size) {
        Display display = getParentShell() != null
            ? getParentShell().getDisplay() : Display.getCurrent();
        if (display == null || size == null) {
            return desired == null ? new Point(40, 40) : desired;
        }
        Rectangle area = display.getClientArea();
        int x = desired == null
            ? area.x + 40
            : Math.max(area.x + 4, Math.min(desired.x, area.x + area.width - size.x - 4));
        int y = desired == null ? area.y + 40 : desired.y;
        if (y + size.y > area.y + area.height) {
            y = (desired == null ? area.y : desired.y) - size.y - 24;
        }
        y = Math.max(area.y + 4, y);
        return new Point(x, y);
    }
}
