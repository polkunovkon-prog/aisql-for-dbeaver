/*
 * AI SQL for DBeaver — карточка правки выделения ИИ.
 *
 * Небольшое немодальное окно (Dialog) рядом с выделенным фрагментом:
 * поле инструкции, состояние генерации и кнопки «Сгенерировать» /
 * «Применить» / «В чат» / «Отмена». Не закрывается при клике в редактор,
 * чтобы правку можно было проверить в предпросмотре.
 *
 * Лицензия: Apache-2.0
 */
package com.pka.dbeaver.aisql.ui.edit;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
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
import org.eclipse.swt.widgets.Text;

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
    }

    private final Point anchor;
    private final Callbacks callbacks;

    private Text instructionText;
    private Label statusLabel;
    private Button generateButton;
    private Button applyButton;

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
        textData.widthHint = 420;
        instructionText.setLayoutData(textData);
        instructionText.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR) {
                    generate();
                } else if (event.keyCode == SWT.ESC) {
                    callbacks.onCancel();
                }
            }
        });

        statusLabel = new Label(content, SWT.WRAP);
        statusLabel.setText("Enter — сгенерировать, Esc — отмена");
        GridData statusData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        statusData.widthHint = 420;
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

        Button chatButton = new Button(buttons, SWT.PUSH);
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

    void setBusy() {
        instructionText.setEnabled(false);
        generateButton.setEnabled(false);
        applyButton.setEnabled(false);
        setStatus("Генерация…", false);
    }

    void setDone(int resultLength) {
        instructionText.setEnabled(true);
        generateButton.setText("Ещё раз");
        generateButton.setEnabled(true);
        applyButton.setEnabled(true);
        setStatus("Готово: " + resultLength + " симв. Проверьте предпросмотр "
            + "в редакторе и нажмите «Применить».", false);
    }

    void setError(String message) {
        instructionText.setEnabled(true);
        generateButton.setText("Повторить");
        generateButton.setEnabled(true);
        applyButton.setEnabled(false);
        setStatus(message, true);
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

    private void generate() {
        String text = instructionText.getText().trim();
        if (text.isEmpty()) {
            setStatus("Введите инструкцию — что сделать с выделенным фрагментом.", true);
            instructionText.setFocus();
            return;
        }
        callbacks.onGenerate(text);
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
