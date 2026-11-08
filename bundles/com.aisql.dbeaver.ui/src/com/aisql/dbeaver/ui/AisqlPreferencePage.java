/*
 * AI SQL for DBeaver — страница настроек плагина.
 *
 * Настройки хранятся в файле ai-sql-plugin.properties рядом с ai-tools.json
 * (<workspace>/.metadata/.config/) и применяются сразу после сохранения —
 * перезапуск DBeaver не требуется.
 *
 * Лицензия: Apache-2.0
 */
package com.aisql.dbeaver.ui;

import com.aisql.dbeaver.core.AisqlAudit;
import com.aisql.dbeaver.core.AisqlCore;
import com.aisql.dbeaver.core.AuditSyslog;
import com.aisql.dbeaver.core.ConnectionProfiles;
import com.aisql.dbeaver.core.EnterprisePolicy;
import com.aisql.dbeaver.core.MaskingRules;
import com.aisql.dbeaver.core.PromptInjectionDefense;
import com.aisql.dbeaver.core.QueryPolicy;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Страница «AI SQL: инструменты SQL» в разделе настроек AI (DBeaver).
 */
public class AisqlPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    public static final String PAGE_ID = "com.dbeaver.preferences.ai.tools";

    private static final String[] PROFILES = { "READ_ONLY", "STANDARD", "FULL" };

    private static final String[] EDIT_MODE_VALUES = {
        AisqlCore.EDIT_MODE_PREVIEW, AisqlCore.EDIT_MODE_DIRECT
    };
    private static final String[] EDIT_MODE_LABELS = {
        "preview — предпросмотр, применять по кнопке",
        "direct — применять сразу после генерации"
    };

    private static final String[] EDIT_PREVIEW_VALUES = {
        AisqlCore.EDIT_PREVIEW_DIFF, AisqlCore.EDIT_PREVIEW_FULL
    };
    private static final String[] EDIT_PREVIEW_LABELS = {
        "diff — точечные замены: «было/стало» на местах, можно отметить нужные",
        "full — весь новый фрагмент целиком под выделением"
    };

    private static final String[] INJECTION_MODE_VALUES = {
        AisqlCore.INJECTION_MODE_OFF, AisqlCore.INJECTION_MODE_FLAG, AisqlCore.INJECTION_MODE_STRICT
    };
    private static final String[] INJECTION_MODE_LABELS = {
        "off — не проверять",
        "flag — помечать в аудите (по умолчанию)",
        "strict — помечать и БЛОКИРОВАТЬ SQL с попыткой угона модели"
    };

    private Combo defaultProfileCombo;
    private Label defaultProfileDescription;
    private Table connectionProfilesTable;

    private Button runQueryStrictCheck;
    private Button disableAutocommitCheck;
    private Text maxRowsText;
    private Text timeoutText;
    private Text maxPayloadText;
    private Text macrosMaxBytesText;
    private Text forbiddenText;

    private Button maskEnabledCheck;
    private Text maskRulesText;

    private Button editEnabledCheck;
    private Combo editModeCombo;
    private Combo editPreviewCombo;
    private Text editMaxCharsText;
    private Button editAuditCheck;

    private Button auditEnabledCheck;
    private Button auditRedactCheck;
    private Text auditMaxBytesText;
    private Text auditMaxFilesText;

    private Button syslogEnabledCheck;
    private Text syslogHostText;
    private Text syslogPortText;
    private Text syslogFacilityText;
    private Text syslogAppNameText;

    private Combo injectionModeCombo;
    private Button toolFenceCheck;
    private Button detectPiiCheck;
    private Text piiReplacementText;
    private Text sampleMaxRowsText;

    private Label statusLabel;
    private Label policyLabel;

    private Label settingsPathLabel;
    private Label auditPathLabel;

    @Override
    public void init(IWorkbench workbench) {
        // без инициализации
    }

    @Override
    protected Control createContents(Composite parent) {
        ScrolledComposite scroller =
            new ScrolledComposite(parent, SWT.V_SCROLL | SWT.H_SCROLL);
        Composite composite = new Composite(scroller, SWT.NONE);
        scroller.setContent(composite);
        scroller.setExpandHorizontal(true);
        scroller.setExpandVertical(true);
        composite.setLayout(new GridLayout(1, false));

        createStatusSection(composite);
        createProfilesSection(composite);
        createLimitsSection(composite);
        createMaskingSection(composite);
        createSecuritySection(composite);
        createEditSection(composite);
        createAuditSection(composite);
        createBottomSection(composite);

        loadValues(AisqlCore.loadSettings());
        applyPolicyLocks();
        updateStatusLine();

        composite.layout();
        scroller.setMinSize(composite.computeSize(SWT.DEFAULT, SWT.DEFAULT));
        return scroller;
    }

    // ----------------------------------------------------------------------
    //  Секции страницы
    // ----------------------------------------------------------------------

    private void createStatusSection(Composite parent) {
        Group group = createGroup(parent, "Статус защиты");
        statusLabel = new Label(group, SWT.WRAP);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        policyLabel = new Label(group, SWT.WRAP);
        policyLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    }

    private void createProfilesSection(Composite parent) {
        Group group = createGroup(parent, "Профили безопасности подключений");

        Label label = new Label(group, SWT.NONE);
        label.setText("Профиль по умолчанию:");
        defaultProfileCombo = new Combo(group, SWT.READ_ONLY | SWT.DROP_DOWN);
        defaultProfileCombo.setItems(PROFILES);
        defaultProfileCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        defaultProfileCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                updateProfileDescription();
            }
        });

        defaultProfileDescription = new Label(group, SWT.WRAP);
        defaultProfileDescription.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        Label hint = new Label(group, SWT.WRAP);
        hint.setText("Профили отдельных подключений (по маске имени подключения, "
            + "поддерживаются «*» и «?»):");
        hint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        Composite tableLine = new Composite(group, SWT.NONE);
        tableLine.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false, 2, 1));
        tableLine.setLayout(new GridLayout(2, false));

        connectionProfilesTable = new Table(tableLine,
            SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION);
        connectionProfilesTable.setHeaderVisible(true);
        connectionProfilesTable.setLinesVisible(true);
        GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, false);
        tableData.heightHint = 90;
        connectionProfilesTable.setLayoutData(tableData);
        TableColumn maskColumn = new TableColumn(connectionProfilesTable, SWT.NONE);
        maskColumn.setText("Маска подключения");
        maskColumn.setWidth(240);
        TableColumn profileColumn = new TableColumn(connectionProfilesTable, SWT.NONE);
        profileColumn.setText("Профиль");
        profileColumn.setWidth(120);

        Composite buttons = new Composite(tableLine, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
        buttons.setLayout(new RowLayout(SWT.VERTICAL));
        createButton(buttons, "Добавить...", e -> addConnectionProfile());
        createButton(buttons, "Изменить...", e -> editConnectionProfile());
        createButton(buttons, "Удалить", e -> removeConnectionProfile());
    }

    private void createLimitsSection(Composite parent) {
        Group group = createGroup(parent, "Ограничения и лимиты");

        runQueryStrictCheck = new Button(group, SWT.CHECK);
        runQueryStrictCheck.setText("db_runQuery: разрешать только операторы чтения "
            + "(SELECT/WITH/SHOW/EXPLAIN/VALUES...)");
        runQueryStrictCheck.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        disableAutocommitCheck = new Button(group, SWT.CHECK);
        disableAutocommitCheck.setText("Отключать автокоммит перед изменяющими запросами "
            + "(защита от случайных правок — можно откатить)");
        disableAutocommitCheck.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        maxRowsText = createNumberField(group, "Максимум строк в ответе (limit.maxRows):",
            "1", 6);
        timeoutText = createNumberField(group, "Таймаут выполнения, сек (limit.timeoutSec):",
            "1", 6);
        maxPayloadText = createNumberField(group,
            "Максимум символов вывода для модели (limit.maxPayloadChars, 0 — без лимита):",
            "0", 8);
        macrosMaxBytesText = createNumberField(group,
            "Максимальный размер макроса, байт (macros.maxBytes):", "1024", 8);
        sampleMaxRowsText = createNumberField(group,
            "Строк в образце таблицы по умолчанию (sample.maxRows, максимум 50):", "5", 6);

        Label forbiddenLabel = new Label(group, SWT.NONE);
        forbiddenLabel.setText("Запрещённые конструкции (policy.forbidden) — "
            + "регулярные выражения, по одному на строку:");
        forbiddenLabel.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        forbiddenText = new Text(group, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL
            | SWT.H_SCROLL | SWT.WRAP);
        GridData forbiddenData = new GridData(SWT.FILL, SWT.FILL, true, false, 2, 1);
        forbiddenData.heightHint = 60;
        forbiddenText.setLayoutData(forbiddenData);

        Label note = new Label(group, SWT.WRAP);
        note.setText("Правила policy.forbidden действуют во всех профилях, кроме FULL. "
            + "Механизм предназначен для снижения рисков, а не для защиты "
            + "от злоумышленника: настоящая защита — права учётной записи СУБД.");
        note.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    }

    private void createMaskingSection(Composite parent) {
        Group group = createGroup(parent, "Маскирование данных в ответах ИИ");

        maskEnabledCheck = new Button(group, SWT.CHECK);
        maskEnabledCheck.setText("Маскировать значения колонок по правилам "
            + "(не действует при профиле FULL)");
        maskEnabledCheck.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        Label label = new Label(group, SWT.NONE);
        label.setText("Правила: таблица.колонка=замена; по одному на строку. "
            + "Примеры: customers.email=***@***; *.password=***");
        label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        maskRulesText = new Text(group, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL
            | SWT.H_SCROLL | SWT.WRAP);
        GridData data = new GridData(SWT.FILL, SWT.FILL, true, false, 2, 1);
        data.heightHint = 60;
        maskRulesText.setLayoutData(data);

        Label note = new Label(group, SWT.WRAP);
        note.setText("Колонка сопоставляется по имени и по подписи в результате; "
            + "таблица — по имени сущности (можно с схемой) или «*». "
            + "Заменённое значение подставляется до чтения данных, "
            + "поэтому реальные значения не попадают в ответ модели.");
        note.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        detectPiiCheck = new Button(group, SWT.CHECK);
        detectPiiCheck.setText("Автодетекция ПДн по значению: email, телефон, банковская карта "
            + "(алгоритм Луна), ИНН и СНИЛС (контрольные суммы) — замена даже если колонка "
            + "не покрыта правилами (SELECT * и вычислимые колонки)");
        detectPiiCheck.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        Label piiLabel = new Label(group, SWT.NONE);
        piiLabel.setText("Замена для распознанных ПДн (mask.piiReplacement):");
        piiReplacementText = new Text(group, SWT.BORDER);
        GridData piiData = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        piiData.widthHint = 140;
        piiReplacementText.setLayoutData(piiData);
    }

    private void createSecuritySection(Composite parent) {
        Group group = createGroup(parent, "Защита от промпт-инъекций");

        Label modeLabel = new Label(group, SWT.NONE);
        modeLabel.setText("Режим (injection.mode):");
        injectionModeCombo = new Combo(group, SWT.READ_ONLY | SWT.DROP_DOWN);
        injectionModeCombo.setItems(INJECTION_MODE_LABELS);
        injectionModeCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        toolFenceCheck = new Button(group, SWT.CHECK);
        toolFenceCheck.setText("Помечать данные из БД (DDL, образцы строк) как «данные, "
            + "не инструкции» — защита от непрямых инъекций через содержимое БД "
            + "(toolOutput.fence)");
        toolFenceCheck.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        Label note = new Label(group, SWT.WRAP);
        note.setText("Проверяются задание пользователя, SQL от модели и данные из БД. "
            + "Режим strict блокирует SQL с признаками угона модели («ignore previous "
            + "instructions», «reveal your prompt» и т.п.); обычные SQL-конструкции "
            + "(UNION SELECT, DROP TABLE в FULL-профиле) не блокируются — только помечаются. "
            + "Инструкции из содержимого БД не выполняются в любом режиме.");
        note.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    }

    private void createEditSection(Composite parent) {
        Group group = createGroup(parent, "Правка выделения с ИИ в редакторе (Ctrl+Alt+E)");

        editEnabledCheck = new Button(group, SWT.CHECK);
        editEnabledCheck.setText("Включить правку выделенного фрагмента SQL по инструкции ИИ");
        editEnabledCheck.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        Label modeLabel = new Label(group, SWT.NONE);
        modeLabel.setText("Применение (edit.applyMode):");
        editModeCombo = new Combo(group, SWT.READ_ONLY | SWT.DROP_DOWN);
        editModeCombo.setItems(EDIT_MODE_LABELS);
        editModeCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label previewLabel = new Label(group, SWT.NONE);
        previewLabel.setText("Предпросмотр правки (edit.previewMode):");
        editPreviewCombo = new Combo(group, SWT.READ_ONLY | SWT.DROP_DOWN);
        editPreviewCombo.setItems(EDIT_PREVIEW_LABELS);
        editPreviewCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        editMaxCharsText = createNumberField(group,
            "Максимум символов в выделении (edit.maxSelectionChars):", "4000", 8);

        editAuditCheck = new Button(group, SWT.CHECK);
        editAuditCheck.setText("Записывать применения/отмены правок в журнал аудита "
            + "(edit.audit)");
        editAuditCheck.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        Label note = new Label(group, SWT.WRAP);
        note.setText("Фрагмент отправляется выбранной в DBeaver модели (той же, что у чата), "
            + "но в базе данных ничего не выполняется: правка только меняет текст редактора "
            + "и отменяется одним Ctrl+Z. В режиме «diff» изменения показываются на своих "
            + "местах: «было» — красным с зачёркиванием, «стало» — зелёным; замены можно "
            + "отмечать галочками, переход между ними — Alt+↑/↓. Кнопка «В чат» в карточке "
            + "отправляет запрос в ИИ-чат без применения правки.");
        note.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    }

    private void createAuditSection(Composite parent) {
        Group group = createGroup(parent, "Журнал аудита");

        auditEnabledCheck = new Button(group, SWT.CHECK);
        auditEnabledCheck.setText("Вести журнал аудита (JSONL с цепочкой хешей)");
        auditEnabledCheck.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        auditRedactCheck = new Button(group, SWT.CHECK);
        auditRedactCheck.setText("Заменять строковые литералы в запросах на '?' "
            + "(снижает риск записи персональных данных в журнал)");
        auditRedactCheck.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        auditMaxBytesText = createNumberField(group,
            "Размер файла журнала до ротации, байт (audit.maxBytes):", "1024", 10);
        auditMaxFilesText = createNumberField(group,
            "Хранить файлов журнала, включая текущий (audit.maxFiles):", "1", 4);

        Label syslogTitle = new Label(group, SWT.WRAP);
        syslogTitle.setText("Дублирование событий в корпоративный SIEM (syslog/UDP, RFC 5424-подобный формат). "
            + "Рекомендация NIST SP 800-92: внешняя копия журнала защищает от подмены локального файла.");
        syslogTitle.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        syslogEnabledCheck = new Button(group, SWT.CHECK);
        syslogEnabledCheck.setText("Отправлять события аудита в syslog (audit.syslog.enabled)");
        syslogEnabledCheck.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        syslogHostText = createTextField(group, "Сервер (audit.syslog.host):", "syslog.corp.example", 220);
        syslogPortText = createNumberField(group, "Порт (audit.syslog.port):", "514", 6);
        syslogFacilityText = createNumberField(group,
            "Функция 0–23, local0=16 (audit.syslog.facility):", "16", 6);
        syslogAppNameText = createTextField(group, "Имя приложения (audit.syslog.appname):", "aisql", 100);

        Composite buttons = new Composite(group, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        buttons.setLayout(new RowLayout());
        createButton(buttons, "Открыть журнал аудита", e -> openAuditFile());
        createButton(buttons, "Открыть каталог настроек", e -> openSettingsDir());
        createButton(buttons, "Проверить целостность журнала", e -> verifyAuditAction());
        createButton(buttons, "Тест: отправить событие в syslog", e -> testSyslogAction());

        auditPathLabel = new Label(group, SWT.WRAP);
        auditPathLabel.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
    }

    private void createBottomSection(Composite parent) {
        settingsPathLabel = new Label(parent, SWT.WRAP);
        settingsPathLabel.setLayoutData(
            new GridData(SWT.FILL, SWT.CENTER, true, false));
        Label note = new Label(parent, SWT.WRAP);
        note.setText("Изменения применяются сразу после «Apply» — перезапуск DBeaver "
            + "не нужен. Файл настроек можно редактировать и вручную.");
        note.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    // ----------------------------------------------------------------------
    //  Значения
    // ----------------------------------------------------------------------

    private void loadValues(Properties p) {
        ConnectionProfiles.Profile defaultProfile = ConnectionProfiles.Profile.parse(
            p.getProperty(ConnectionProfiles.KEY_DEFAULT),
            ConnectionProfiles.DEFAULT_PROFILE);
        int profileIndex = 1;
        for (int i = 0; i < PROFILES.length; i++) {
            if (PROFILES[i].equals(defaultProfile.name())) {
                profileIndex = i;
            }
        }
        defaultProfileCombo.select(profileIndex);
        updateProfileDescription();

        connectionProfilesTable.removeAll();
        ArrayList<String> keys = new ArrayList<>(p.stringPropertyNames());
        keys.sort(String::compareToIgnoreCase);
        for (String key : keys) {
            if (key.startsWith(ConnectionProfiles.KEY_PREFIX)) {
                String mask = key.substring(ConnectionProfiles.KEY_PREFIX.length());
                if (mask.isEmpty()) {
                    continue;
                }
                TableItem item = new TableItem(connectionProfilesTable, SWT.NONE);
                item.setText(new String[] { mask, p.getProperty(key, "") });
            }
        }

        runQueryStrictCheck.setSelection(
            Boolean.parseBoolean(p.getProperty(AisqlCore.KEY_RUNQUERY_STRICT, "true")));
        disableAutocommitCheck.setSelection(
            Boolean.parseBoolean(p.getProperty(
                AisqlCore.KEY_WRITE_DISABLE_AUTOCOMMIT, "true")));
        maxRowsText.setText(p.getProperty(
            AisqlCore.KEY_LIMIT_MAX_ROWS, String.valueOf(AisqlCore.DEFAULT_MAX_ROWS)));
        timeoutText.setText(p.getProperty(
            AisqlCore.KEY_LIMIT_TIMEOUT, String.valueOf(AisqlCore.DEFAULT_TIMEOUT_SEC)));
        maxPayloadText.setText(p.getProperty(
            AisqlCore.KEY_LIMIT_MAX_PAYLOAD, String.valueOf(AisqlCore.DEFAULT_MAX_PAYLOAD)));
        macrosMaxBytesText.setText(p.getProperty(
            AisqlCore.KEY_MACROS_MAX_BYTES,
            String.valueOf(AisqlCore.DEFAULT_MACROS_MAX_BYTES)));
        forbiddenText.setText(p.getProperty(QueryPolicy.KEY_FORBIDDEN, ""));

        maskEnabledCheck.setSelection(
            Boolean.parseBoolean(p.getProperty(MaskingRules.KEY_ENABLED, "true")));
        maskRulesText.setText(p.getProperty(MaskingRules.KEY_RULES, ""));

        editEnabledCheck.setSelection(
            Boolean.parseBoolean(p.getProperty(AisqlCore.KEY_EDIT_ENABLED, "true")));
        String editMode = p.getProperty(
            AisqlCore.KEY_EDIT_APPLY_MODE, AisqlCore.EDIT_MODE_PREVIEW);
        editModeCombo.select(AisqlCore.EDIT_MODE_DIRECT.equalsIgnoreCase(editMode) ? 1 : 0);
        String editPreview = p.getProperty(
            AisqlCore.KEY_EDIT_PREVIEW_MODE, AisqlCore.EDIT_PREVIEW_DIFF);
        editPreviewCombo.select(AisqlCore.EDIT_PREVIEW_FULL.equalsIgnoreCase(editPreview) ? 1 : 0);
        editMaxCharsText.setText(p.getProperty(AisqlCore.KEY_EDIT_MAX_SELECTION,
            String.valueOf(AisqlCore.DEFAULT_EDIT_MAX_SELECTION)));
        editAuditCheck.setSelection(
            Boolean.parseBoolean(p.getProperty(AisqlCore.KEY_EDIT_AUDIT, "true")));

        auditEnabledCheck.setSelection(
            Boolean.parseBoolean(p.getProperty(AisqlCore.KEY_AUDIT_ENABLED, "true")));
        auditRedactCheck.setSelection(
            Boolean.parseBoolean(p.getProperty(AisqlAudit.KEY_REDACT_LITERALS, "false")));
        auditMaxBytesText.setText(p.getProperty(
            AisqlAudit.KEY_MAX_BYTES, String.valueOf(AisqlAudit.DEFAULT_MAX_BYTES)));
        auditMaxFilesText.setText(p.getProperty(
            AisqlAudit.KEY_MAX_FILES, String.valueOf(AisqlAudit.DEFAULT_MAX_FILES)));

        syslogEnabledCheck.setSelection(
            Boolean.parseBoolean(p.getProperty(AuditSyslog.KEY_ENABLED, "false")));
        syslogHostText.setText(p.getProperty(AuditSyslog.KEY_HOST, ""));
        syslogPortText.setText(p.getProperty(
            AuditSyslog.KEY_PORT, String.valueOf(AuditSyslog.DEFAULT_PORT)));
        syslogFacilityText.setText(p.getProperty(
            AuditSyslog.KEY_FACILITY, String.valueOf(AuditSyslog.DEFAULT_FACILITY)));
        syslogAppNameText.setText(p.getProperty(AuditSyslog.KEY_APPNAME, AuditSyslog.DEFAULT_APPNAME));

        String mode = PromptInjectionDefense.mode();
        int modeIndex = 1;
        for (int i = 0; i < INJECTION_MODE_VALUES.length; i++) {
            if (INJECTION_MODE_VALUES[i].equalsIgnoreCase(mode)) {
                modeIndex = i;
            }
        }
        injectionModeCombo.select(modeIndex);
        toolFenceCheck.setSelection(
            Boolean.parseBoolean(p.getProperty(AisqlCore.KEY_TOOL_OUTPUT_FENCE, "true")));

        detectPiiCheck.setSelection(
            Boolean.parseBoolean(p.getProperty(AisqlCore.KEY_DETECT_PII, "true")));
        piiReplacementText.setText(p.getProperty(
            AisqlCore.KEY_PII_REPLACEMENT, AisqlCore.DEFAULT_PII_REPLACEMENT));
        sampleMaxRowsText.setText(p.getProperty(
            AisqlCore.KEY_SAMPLE_MAX_ROWS, String.valueOf(AisqlCore.DEFAULT_SAMPLE_ROWS)));

        updatePathLabels();
    }

    private void updateProfileDescription() {
        int index = defaultProfileCombo.getSelectionIndex();
        ConnectionProfiles.Profile profile = index < 0
            ? ConnectionProfiles.DEFAULT_PROFILE
            : ConnectionProfiles.Profile.parse(PROFILES[index],
                ConnectionProfiles.DEFAULT_PROFILE);
        defaultProfileDescription.setText(ConnectionProfiles.describe(profile));
        defaultProfileDescription.getParent().layout();
    }

    private void updatePathLabels() {
        try {
            Path dir = AisqlCore.configDir();
            settingsPathLabel.setText("Файл настроек: "
                + dir.resolve(AisqlCore.SETTINGS_FILE));
            auditPathLabel.setText("Файл журнала: " + dir.resolve(AisqlCore.AUDIT_FILE));
        } catch (Exception e) {
            settingsPathLabel.setText("Файл настроек: <недоступен: " + e.getMessage() + ">");
            auditPathLabel.setText("");
        }
        if (settingsPathLabel.getParent() != null) {
            settingsPathLabel.getParent().layout();
        }
    }

    // ----------------------------------------------------------------------
    //  Профили подключений
    // ----------------------------------------------------------------------

    private void addConnectionProfile() {
        InputDialog maskDialog = new InputDialog(getShell(), "Профиль подключения",
            "Маска имени подключения (поддерживаются «*» и «?»).\n"
                + "Примеры: prod-*, Sakila, *test*",
            "prod-*", value ->
                value == null || value.trim().isEmpty() ? "Введите маску" : null);
        if (maskDialog.open() != Window.OK) {
            return;
        }
        String mask = maskDialog.getValue().trim();
        String profile = chooseProfile("STANDARD");
        if (profile == null) {
            return;
        }
        for (TableItem item : connectionProfilesTable.getItems()) {
            if (item.getText(0).equalsIgnoreCase(mask)) {
                item.setText(1, profile);
                return;
            }
        }
        TableItem item = new TableItem(connectionProfilesTable, SWT.NONE);
        item.setText(new String[] { mask, profile });
    }

    private void editConnectionProfile() {
        int index = connectionProfilesTable.getSelectionIndex();
        if (index < 0) {
            MessageDialog.openInformation(getShell(), "AI SQL",
                "Выберите строку в таблице профилей подключений.");
            return;
        }
        TableItem item = connectionProfilesTable.getItem(index);
        String profile = chooseProfile(item.getText(1));
        if (profile != null) {
            item.setText(1, profile);
        }
    }

    private void removeConnectionProfile() {
        int index = connectionProfilesTable.getSelectionIndex();
        if (index < 0) {
            MessageDialog.openInformation(getShell(), "AI SQL",
                "Выберите строку в таблице профилей подключений.");
            return;
        }
        connectionProfilesTable.remove(index);
    }

    private String chooseProfile(String current) {
        ProfileDialog dialog = new ProfileDialog(getShell(), current);
        if (dialog.open() == Window.OK) {
            return dialog.getValue();
        }
        return null;
    }

    // ----------------------------------------------------------------------
    //  Сохранение
    // ----------------------------------------------------------------------

    @Override
    public boolean performOk() {
        try {
            saveSettingsFromFields();
            updatePathLabels();
            updateStatusLine();
            return super.performOk();
        } catch (Exception e) {
            MessageDialog.openError(getShell(), "AI SQL",
                "Не удалось сохранить настройки:\n" + e.getMessage());
            return false;
        }
    }

    /**
     * Сохраняет значения полей в пользовательский файл настроек.
     * Ключи, зафиксированные корпоративной политикой, не записываются
     * (их значения всё равно берутся из политики).
     */
    private void saveSettingsFromFields() throws Exception {
        // Отправной файл — пользовательские настройки (без значений
        // корпоративной политики), чтобы политика не «прилипала» к файлу.
        Properties p = AisqlCore.userSettings();

            int profileIndex = defaultProfileCombo.getSelectionIndex();
            p.setProperty(ConnectionProfiles.KEY_DEFAULT,
                profileIndex < 0 ? ConnectionProfiles.DEFAULT_PROFILE.name()
                    : PROFILES[profileIndex]);

            for (String key : new ArrayList<>(p.stringPropertyNames())) {
                if (key.startsWith(ConnectionProfiles.KEY_PREFIX)) {
                    p.remove(key);
                }
            }
            for (TableItem item : connectionProfilesTable.getItems()) {
                String mask = item.getText(0).trim();
                String profile = item.getText(1).trim();
                if (!mask.isEmpty() && !profile.isEmpty()) {
                    p.setProperty(ConnectionProfiles.KEY_PREFIX + mask, profile);
                }
            }

            p.setProperty(AisqlCore.KEY_RUNQUERY_STRICT,
                String.valueOf(runQueryStrictCheck.getSelection()));
            p.setProperty(AisqlCore.KEY_WRITE_DISABLE_AUTOCOMMIT,
                String.valueOf(disableAutocommitCheck.getSelection()));
            p.setProperty(AisqlCore.KEY_LIMIT_MAX_ROWS,
                String.valueOf(parseNumber(maxRowsText, "limit.maxRows",
                    1, AisqlCore.HARD_MAX_ROWS)));
            p.setProperty(AisqlCore.KEY_LIMIT_TIMEOUT,
                String.valueOf(parseNumber(timeoutText, "limit.timeoutSec", 1, 3600)));
            p.setProperty(AisqlCore.KEY_LIMIT_MAX_PAYLOAD,
                String.valueOf(parseNumber(maxPayloadText, "limit.maxPayloadChars",
                    0, AisqlCore.HARD_MAX_PAYLOAD)));
            p.setProperty(AisqlCore.KEY_MACROS_MAX_BYTES,
                String.valueOf(parseNumber(macrosMaxBytesText, "macros.maxBytes",
                    1024, 10_000_000)));
            p.setProperty(QueryPolicy.KEY_FORBIDDEN, multiLine(forbiddenText));

            p.setProperty(MaskingRules.KEY_ENABLED,
                String.valueOf(maskEnabledCheck.getSelection()));
            p.setProperty(MaskingRules.KEY_RULES, multiLine(maskRulesText));

            p.setProperty(AisqlCore.KEY_EDIT_ENABLED,
                String.valueOf(editEnabledCheck.getSelection()));
            int editModeIndex = editModeCombo.getSelectionIndex();
            p.setProperty(AisqlCore.KEY_EDIT_APPLY_MODE,
                editModeIndex < 0 ? AisqlCore.EDIT_MODE_PREVIEW
                    : EDIT_MODE_VALUES[editModeIndex]);
            int previewIndex = editPreviewCombo.getSelectionIndex();
            p.setProperty(AisqlCore.KEY_EDIT_PREVIEW_MODE,
                previewIndex < 0 ? AisqlCore.EDIT_PREVIEW_DIFF
                    : EDIT_PREVIEW_VALUES[previewIndex]);
            p.setProperty(AisqlCore.KEY_EDIT_MAX_SELECTION,
                String.valueOf(parseNumber(editMaxCharsText, "edit.maxSelectionChars",
                    1, AisqlCore.HARD_MAX_EDIT_SELECTION)));
            p.setProperty(AisqlCore.KEY_EDIT_AUDIT,
                String.valueOf(editAuditCheck.getSelection()));

            p.setProperty(AisqlCore.KEY_AUDIT_ENABLED,
                String.valueOf(auditEnabledCheck.getSelection()));
            p.setProperty(AisqlAudit.KEY_REDACT_LITERALS,
                String.valueOf(auditRedactCheck.getSelection()));
            p.setProperty(AisqlAudit.KEY_MAX_BYTES,
                String.valueOf(parseNumber(auditMaxBytesText, "audit.maxBytes",
                    1024, 1_000_000_000)));
            p.setProperty(AisqlAudit.KEY_MAX_FILES,
                String.valueOf(parseNumber(auditMaxFilesText, "audit.maxFiles", 1, 100)));

            p.setProperty(AisqlCore.KEY_INJECTION_MODE,
                injectionModeCombo.getSelectionIndex() >= 0
                    ? INJECTION_MODE_VALUES[injectionModeCombo.getSelectionIndex()]
                    : AisqlCore.INJECTION_MODE_FLAG);
            p.setProperty(AisqlCore.KEY_TOOL_OUTPUT_FENCE,
                String.valueOf(toolFenceCheck.getSelection()));
            p.setProperty(AisqlCore.KEY_DETECT_PII,
                String.valueOf(detectPiiCheck.getSelection()));
            String piiReplacement = piiReplacementText.getText().trim();
            p.setProperty(AisqlCore.KEY_PII_REPLACEMENT,
                piiReplacement.isEmpty() ? AisqlCore.DEFAULT_PII_REPLACEMENT : piiReplacement);
            p.setProperty(AisqlCore.KEY_SAMPLE_MAX_ROWS,
                String.valueOf(parseNumber(sampleMaxRowsText, "sample.maxRows",
                    1, AisqlCore.HARD_MAX_SAMPLE_ROWS)));

            p.setProperty(AuditSyslog.KEY_ENABLED,
                String.valueOf(syslogEnabledCheck.getSelection()));
            String syslogHost = syslogHostText.getText().trim();
            if (syslogHost.isEmpty()) {
                p.remove(AuditSyslog.KEY_HOST);
            } else {
                p.setProperty(AuditSyslog.KEY_HOST, syslogHost);
            }
            p.setProperty(AuditSyslog.KEY_PORT,
                String.valueOf(parseNumber(syslogPortText, "audit.syslog.port", 1, 65535)));
            p.setProperty(AuditSyslog.KEY_FACILITY,
                String.valueOf(parseNumber(syslogFacilityText, "audit.syslog.facility", 0, 23)));
            String appname = syslogAppNameText.getText().trim();
            p.setProperty(AuditSyslog.KEY_APPNAME,
                appname.isEmpty() ? AuditSyslog.DEFAULT_APPNAME : appname);

            // Ключи, зафиксированные корпоративной политикой, в пользовательский
            // файл не пишем — их всё равно перекрывает политика.
            for (String locked : EnterprisePolicy.lockedKeys()) {
                p.remove(locked);
            }

            AisqlCore.saveSettings(p);
    }

    @Override
    protected void performDefaults() {
        loadValues(AisqlCore.defaultSettings());
        super.performDefaults();
    }

    private static int parseNumber(Text text, String key, int min, int max) {
        String value = text.getText().trim();
        int number;
        try {
            number = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Настройка " + key + ": «" + value + "» не является целым числом.");
        }
        if (number < min || number > max) {
            throw new IllegalArgumentException(
                "Настройка " + key + ": значение " + number
                    + " вне диапазона " + min + ".." + max + ".");
        }
        return number;
    }

    private static String multiLine(Text text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.getText().split("\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(trimmed);
            }
        }
        return sb.toString();
    }

    // ----------------------------------------------------------------------
    //  Кнопки открытия файлов
    // ----------------------------------------------------------------------

    private void openAuditFile() {
        try {
            Path file = AisqlCore.auditFile();
            Path target = Files.exists(file) ? file : file.getParent();
            openPath(target, "Журнал аудита");
        } catch (Exception e) {
            MessageDialog.openError(getShell(), "AI SQL",
                "Не удалось определить путь журнала: " + e.getMessage());
        }
    }

    private void openSettingsDir() {
        try {
            openPath(AisqlCore.configDir(), "Каталог настроек");
        } catch (Exception e) {
            MessageDialog.openError(getShell(), "AI SQL",
                "Не удалось определить каталог настроек: " + e.getMessage());
        }
    }

    private void openPath(Path path, String what) {
        try {
            Desktop.getDesktop().open(path.toFile());
        } catch (Exception e) {
            MessageDialog.openInformation(getShell(), "AI SQL",
                "Не удалось открыть автоматически.\n" + what + ":\n" + path);
        }
    }

    // ----------------------------------------------------------------------
    //  Статус защиты, корпоративная политика, проверки
    // ----------------------------------------------------------------------

    /** Краткая сводка действующих защит — мгновенная видимость состояния. */
    private void updateStatusLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("Инъекции: ").append(PromptInjectionDefense.mode());
        sb.append("  ·  ПДн-детекция: ")
            .append(AisqlCore.getBool(AisqlCore.KEY_DETECT_PII, true) ? "вкл" : "выкл");
        sb.append("  ·  Маскирование: ")
            .append(AisqlCore.getBool(MaskingRules.KEY_ENABLED, true)
                ? "вкл, правил: " + MaskingRules.loadRules().size() : "выкл");
        sb.append("  ·  Аудит: ")
            .append(AisqlCore.getBool(AisqlCore.KEY_AUDIT_ENABLED, true) ? "вкл" : "выкл");
        sb.append(AuditSyslog.enabled() ? " + syslog" : "");
        statusLabel.setText(sb.toString());

        if (EnterprisePolicy.isBroken()) {
            policyLabel.setText("КОРПОРАТИВНАЯ ПОЛИТИКА: задана, но не читается — "
                + "инструменты заблокированы (fail-closed). Обратитесь к администратору.");
        } else if (EnterprisePolicy.isActive()) {
            List<String> locked = EnterprisePolicy.lockedKeys();
            policyLabel.setText("КОРПОРАТИВНАЯ ПОЛИТИКА АКТИВНА — настройки, "
                + "зафиксированные администратором, нельзя изменить здесь. Ключи: "
                + String.join(", ", locked));
        } else {
            policyLabel.setText("Корпоративная политика не задана "
                + "(ai-sql-enterprise.properties или -Daisql.enterprise.policy).");
        }
        statusLabel.getParent().layout();
    }

    /** Поля, зафиксированные политикой, делаются нередактируемыми. */
    private void applyPolicyLocks() {
        lockIfManaged(defaultProfileCombo, ConnectionProfiles.KEY_DEFAULT);
        lockIfManaged(connectionProfilesTable, ConnectionProfiles.KEY_PREFIX);
        lockIfManaged(runQueryStrictCheck, AisqlCore.KEY_RUNQUERY_STRICT);
        lockIfManaged(disableAutocommitCheck, AisqlCore.KEY_WRITE_DISABLE_AUTOCOMMIT);
        lockIfManaged(maxRowsText, AisqlCore.KEY_LIMIT_MAX_ROWS);
        lockIfManaged(timeoutText, AisqlCore.KEY_LIMIT_TIMEOUT);
        lockIfManaged(maxPayloadText, AisqlCore.KEY_LIMIT_MAX_PAYLOAD);
        lockIfManaged(sampleMaxRowsText, AisqlCore.KEY_SAMPLE_MAX_ROWS);
        lockIfManaged(forbiddenText, QueryPolicy.KEY_FORBIDDEN);
        lockIfManaged(maskEnabledCheck, MaskingRules.KEY_ENABLED);
        lockIfManaged(maskRulesText, MaskingRules.KEY_RULES);
        lockIfManaged(detectPiiCheck, AisqlCore.KEY_DETECT_PII);
        lockIfManaged(piiReplacementText, AisqlCore.KEY_PII_REPLACEMENT);
        lockIfManaged(injectionModeCombo, AisqlCore.KEY_INJECTION_MODE);
        lockIfManaged(toolFenceCheck, AisqlCore.KEY_TOOL_OUTPUT_FENCE);
        lockIfManaged(auditEnabledCheck, AisqlCore.KEY_AUDIT_ENABLED);
        lockIfManaged(auditRedactCheck, AisqlAudit.KEY_REDACT_LITERALS);
        lockIfManaged(syslogEnabledCheck, AuditSyslog.KEY_ENABLED);
        lockIfManaged(syslogHostText, AuditSyslog.KEY_HOST);
        lockIfManaged(syslogPortText, AuditSyslog.KEY_PORT);
        lockIfManaged(editEnabledCheck, AisqlCore.KEY_EDIT_ENABLED);
    }

    private void lockIfManaged(Control control, String key) {
        if (control != null && (EnterprisePolicy.isLocked(key)
            || (key.endsWith(".") && isAnyLockedWithPrefix(key)))) {
            control.setEnabled(false);
        }
    }

    private boolean isAnyLockedWithPrefix(String prefix) {
        for (String k : EnterprisePolicy.lockedKeys()) {
            if (k.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Проверка цепочки хешей текущего и ротированных файлов журнала. */
    private void verifyAuditAction() {
        try {
            Path file = AisqlCore.auditFile();
            if (!Files.exists(file)) {
                MessageDialog.openInformation(getShell(), "Проверка журнала аудита",
                    "Журнал ещё не создан (событий не было).");
                return;
            }
            int files = 0;
            int ok = 0;
            List<String> problems = new ArrayList<>();
            int maxFiles = AisqlCore.getInt(AisqlAudit.KEY_MAX_FILES, AisqlAudit.DEFAULT_MAX_FILES);
            for (int i = 0; i < Math.max(1, maxFiles); i++) {
                Path f = i == 0 ? file : file.resolveSibling(
                    AisqlAudit.FILE_NAME + "." + i);
                if (!Files.exists(f)) {
                    continue;
                }
                files++;
                if (AisqlAudit.verify(f)) {
                    ok++;
                } else {
                    problems.add(f.getFileName().toString());
                }
            }
            if (problems.isEmpty()) {
                MessageDialog.openInformation(getShell(), "Проверка журнала аудита",
                    "Целостность подтверждена: " + ok + " из " + files
                        + " файлов, цепочка хешей не нарушена.");
            } else {
                MessageDialog.openError(getShell(), "Проверка журнала аудита",
                    "НАРУШЕНИЕ целостности в файл(ах): " + String.join(", ", problems)
                        + ". Возможно, журнал правился или повреждён. "
                        + "Сравните с копией в SIEM (audit.syslog.*).");
            }
        } catch (Exception e) {
            MessageDialog.openError(getShell(), "Проверка журнала аудита",
                "Не удалось проверить журнал: " + e.getMessage());
        }
    }

    /** Тестовая отправка события в syslog (по текущим настройкам полей). */
    private void testSyslogAction() {
        try {
            // Сохраняем текущие значения полей, чтобы тест шёл по ним
            // (без закрытия диалога настроек).
            saveSettingsFromFields();
            String host = AisqlCore.getSetting(AuditSyslog.KEY_HOST, "");
            if (host.isBlank() || !AisqlCore.getBool(AuditSyslog.KEY_ENABLED, false)) {
                MessageDialog.openInformation(getShell(), "Тест syslog",
                    "Заполните сервер, включите отправку и примените (Apply) перед тестом.");
                return;
            }
            AisqlAudit.Event e = new AisqlAudit.Event();
            e.function = "syslog_test";
            e.decision = "read";
            e.outcome = "OK";
            e.detail = "тестовая отправка со страницы настроек";
            AisqlAudit.write(e);
            MessageDialog.openInformation(getShell(), "Тест syslog",
                "Тестовое событие отправлено на " + host + ":"
                    + AisqlCore.getInt(AuditSyslog.KEY_PORT, AuditSyslog.DEFAULT_PORT)
                    + ". Проверьте приём в SIEM/rsyslog (приложение "
                    + AisqlCore.getSetting(AuditSyslog.KEY_APPNAME, AuditSyslog.DEFAULT_APPNAME)
                    + "). Отправка UDP — без подтверждения доставки.");
        } catch (Exception e) {
            MessageDialog.openError(getShell(), "Тест syslog",
                "Не удалось отправить тестовое событие: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------------
    //  Вспомогательные
    // ----------------------------------------------------------------------

    private static Group createGroup(Composite parent, String title) {
        Group group = new Group(parent, SWT.NONE);
        group.setText(title);
        group.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, false));
        group.setLayout(new GridLayout(2, false));
        return group;
    }

    private static Text createNumberField(Composite parent, String labelText,
                                          String widthHint, int textWidth) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(labelText);
        Text text = new Text(parent, SWT.BORDER);
        GridData data = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        data.widthHint = 90;
        text.setLayoutData(data);
        text.setText(widthHint);
        return text;
    }

    private static Text createTextField(Composite parent, String labelText,
                                        String placeholder, int width) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(labelText);
        Text text = new Text(parent, SWT.BORDER);
        GridData data = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        data.widthHint = width;
        text.setLayoutData(data);
        text.setText(placeholder);
        return text;
    }

    private static Button createButton(Composite parent, String text,
                                       org.eclipse.swt.widgets.Listener listener) {
        Button button = new Button(parent, SWT.PUSH);
        button.setText(text);
        button.addListener(SWT.Selection, listener);
        return button;
    }

    // ----------------------------------------------------------------------
    //  Диалог выбора профиля
    // ----------------------------------------------------------------------

    private static final class ProfileDialog extends Dialog {

        private final String initial;
        private Combo combo;
        private Label description;
        private String value;

        ProfileDialog(Shell parentShell, String initial) {
            super(parentShell);
            this.initial = initial;
            setShellStyle(SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        }

        @Override
        protected void configureShell(Shell newShell) {
            super.configureShell(newShell);
            newShell.setText("Профиль безопасности подключения");
        }

        @Override
        protected Control createDialogArea(Composite parent) {
            Composite area = (Composite) super.createDialogArea(parent);
            area.setLayout(new GridLayout(1, false));

            Composite line = new Composite(area, SWT.NONE);
            line.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            line.setLayout(new GridLayout(2, false));
            Label label = new Label(line, SWT.NONE);
            label.setText("Профиль:");
            combo = new Combo(line, SWT.READ_ONLY | SWT.DROP_DOWN);
            combo.setItems(PROFILES);
            combo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            int index = 1;
            for (int i = 0; i < PROFILES.length; i++) {
                if (PROFILES[i].equals(initial)) {
                    index = i;
                }
            }
            combo.select(index);
            combo.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    updateDescription();
                }
            });

            description = new Label(area, SWT.WRAP);
            description.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            updateDescription();
            return area;
        }

        private void updateDescription() {
            description.setText(ConnectionProfiles.describe(
                ConnectionProfiles.Profile.parse(combo.getText(),
                    ConnectionProfiles.DEFAULT_PROFILE)));
            description.getParent().layout();
        }

        @Override
        protected void okPressed() {
            value = combo.getText();
            super.okPressed();
        }

        String getValue() {
            return value;
        }
    }
}
