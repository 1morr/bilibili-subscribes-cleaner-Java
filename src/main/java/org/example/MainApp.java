package org.example;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * B站用户活跃度分析工具 - 主应用程序
 * 将Python项目转换为Java Swing GUI应用程序
 */
public class MainApp extends JFrame {
    private JTextField filePathField;
    private JTextField inactiveDaysField;
    private JButton browseButton;
    private JButton processButton;
    private JButton exportButton;
    private JTable resultTable;
    private DefaultTableModel tableModel;
    private JComboBox<ExportType> exportTypeComboBox;
    private JComboBox<ExportFormat> exportFormatComboBox;
    private JLabel statusLabel;
    private JProgressBar progressBar;
    private JButton loadCacheButton;
    private JLabel userCountLabel; // 新增：用于显示不活跃用户/全部的标签

    // 以下几个原本是layoutComponents()里的局部变量，提升为字段是为了retranslate()能在切换
    // 语言时重新设置它们的文字（i18n）
    private JLabel pathLabel;
    private JLabel daysLabel;
    private JLabel batchSizeLabel;
    private JLabel intervalLabel;
    private JLabel exportLabel;
    private JLabel languageLabel;
    private JComboBox<AppLocale> languageSelector;

    // statusLabel最近一次设置时用的消息key与参数，供retranslate()按新语言重新生成文案；
    // 后台线程（DataProcessingTask/DataLoadingTask的process()）直接写入的过程性进度文字不走
    // 这一套，因为它们总会在操作结束时被下面的某次setStatus()调用覆盖（见tooltip.language的说明）
    private String lastStatusKey;
    private Object[] lastStatusArgs = new Object[0];

    private File selectedFile;
    private List<UserData> inactiveUsers;
    // uid -> UserData 索引，避免每次筛选/导出都对inactiveUsers做线性扫描（修复 C9）
    private Map<Long, UserData> uidToUser = new HashMap<>();
    private boolean dataProcessed = false;
    private int unknownUserCount = 0; // 本轮未能确认活跃度的账号数量（修复 C1）

    // 不活跃天数输入框的筛选防抖计时器：停止输入300ms后才真正重建表格，
    // 避免像输入"365"这样连续三次按键各触发一次全表重建（修复 C9）
    private final javax.swing.Timer filterDebounceTimer;

    // 定义全局字体
    private Font mainFont;
    private Font boldFont;
    private Font tableFont;

    public MainApp() {
        setTitle(Messages.get("app.title"));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1050, 650);
        setLocationRelativeTo(null);

        filterDebounceTimer = new javax.swing.Timer(300, e -> updateInactiveDaysFilter());
        filterDebounceTimer.setRepeats(false);

        // 初始化字体
        initFonts();
        initComponents();
        layoutComponents();
        addListeners();

        // 界面语言切换时重新套用所有静态文案/表头/已加载数据的显示（i18n）
        Messages.addChangeListener(this::retranslate);
    }
    
    private void initFonts() {
        // 使用更现代的无衬线字体
        mainFont = new Font("Microsoft YaHei", Font.PLAIN, 12);
        boldFont = new Font("Microsoft YaHei", Font.BOLD, 12);
        tableFont = new Font("Microsoft YaHei", Font.PLAIN, 12);
        
        // 设置全局字体
        UIManager.put("Button.font", mainFont);
        UIManager.put("Label.font", mainFont);
        UIManager.put("TextField.font", mainFont);
        UIManager.put("ComboBox.font", mainFont);
        UIManager.put("Table.font", tableFont);
        UIManager.put("TableHeader.font", boldFont);
    }
    
    private void initComponents() {
        // 文件选择区域
        filePathField = new JTextField(30);
        filePathField.setEditable(false);
        filePathField.setFont(mainFont);
        browseButton = new JButton(Messages.get("button.browse"));
        browseButton.setFont(mainFont);
        
        // 不活跃天数设置
        inactiveDaysField = new JTextField("365", 5);
        inactiveDaysField.setFont(mainFont);
        // 添加文本变化监听器，实现实时筛选；重启防抖计时器而非立即重建表格（修复 C9）
        inactiveDaysField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                filterDebounceTimer.restart();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                filterDebounceTimer.restart();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                filterDebounceTimer.restart();
            }
        });
        
        // 处理按钮
        processButton = new JButton(Messages.get("button.processData"));
        processButton.setEnabled(false);
        processButton.setFont(mainFont);

        // 缓存加载按钮
        loadCacheButton = new JButton(Messages.get("button.loadCache"));
        loadCacheButton.setFont(mainFont);

        // 状态显示
        statusLabel = new JLabel();
        statusLabel.setFont(mainFont);
        setStatus("status.selectFile");
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setPreferredSize(new Dimension(300, 20)); // 固定进度条长度

        // 用户计数标签（将在左下角显示）
        userCountLabel = new JLabel(formatUserCountText(0, 0));
        userCountLabel.setFont(mainFont);

        // 语言选择器：显示各语言的本地名称，与formatUserCountText/setStatus等所有可翻译文案
        // 一样，随Messages.setLocale()触发的retranslate()即时更新
        languageLabel = new JLabel(Messages.get("label.language"));
        languageLabel.setFont(mainFont);
        languageSelector = new JComboBox<>(AppLocale.values());
        languageSelector.setFont(mainFont);
        languageSelector.setSelectedItem(AppLocale.from(Messages.getCurrentLocale()));
        languageSelector.setToolTipText(Messages.get("tooltip.language"));

        // 结果表格
        String[] columnNames = {
                Messages.get("table.header.uid"),
                Messages.get("table.header.name"),
                Messages.get("table.header.group"),
                Messages.get("table.header.daysInactive"),
                Messages.get("table.header.latestVideo"),
                Messages.get("table.header.videoLink"),
                Messages.get("table.header.space")
        };
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false; // 使表格不可编辑
            }
            
            @Override
            public Class<?> getColumnClass(int column) {
                switch (column) {
                    case 0: // UID
                        return Long.class;
                    case 3: // 不活跃天数
                        return Integer.class;
                    default:
                        return String.class;
                }
            }
        };
        resultTable = new JTable(tableModel);
        resultTable.setFont(tableFont);
        resultTable.getTableHeader().setFont(boldFont);
        resultTable.setRowHeight(25); // 增加行高，提高可读性
        // 英文表头比原来的中文宽（例如"Days inactive"/"Video link"），加宽对应列避免表头被裁切
        resultTable.getColumnModel().getColumn(0).setPreferredWidth(60);
        resultTable.getColumnModel().getColumn(1).setPreferredWidth(150);
        resultTable.getColumnModel().getColumn(2).setPreferredWidth(90);
        resultTable.getColumnModel().getColumn(3).setPreferredWidth(110);
        resultTable.getColumnModel().getColumn(4).setPreferredWidth(240);
        resultTable.getColumnModel().getColumn(5).setPreferredWidth(110);
        resultTable.getColumnModel().getColumn(6).setPreferredWidth(110);
        
        // 添加表格排序功能
        resultTable.setAutoCreateRowSorter(true);

        // 「不活跃天数」列的两个哨兵值不能直接显示给用户：Integer.MAX_VALUE 表示确认无
        // 视频、-1 表示状态未确认。底层仍然存整数，排序才会按天数正确排（无视频排在最
        // 不活跃的一端），只在渲染时换成文字。
        resultTable.getColumnModel().getColumn(3).setCellRenderer(
                new javax.swing.table.DefaultTableCellRenderer() {
                    @Override
                    protected void setValue(Object value) {
                        if (value instanceof Integer) {
                            int days = (Integer) value;
                            if (days == Integer.MAX_VALUE) {
                                setText(Messages.get(UserData.NO_VIDEOS_KEY));
                                return;
                            }
                            if (days < 0) {
                                setText(Messages.get(UserData.UNKNOWN_KEY));
                                return;
                            }
                        }
                        super.setValue(value);
                    }
                });
        // 数字靠右对齐，读起来才对得齐
        ((javax.swing.table.DefaultTableCellRenderer) resultTable.getColumnModel()
                .getColumn(3).getCellRenderer()).setHorizontalAlignment(SwingConstants.RIGHT);


        // 设置表格选择模式和网格线
        resultTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        resultTable.setGridColor(new Color(230, 230, 230));
        
        // 添加链接点击事件（视频链接和空间链接）
        resultTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                int row = resultTable.rowAtPoint(evt.getPoint());
                int col = resultTable.columnAtPoint(evt.getPoint());
                if ((col == 5 || col == 6) && row >= 0) { // 视频链接列或空间链接列
                    // 将视图行索引转换为模型行索引，解决表格排序后行索引不一致的问题
                    int modelRow = resultTable.convertRowIndexToModel(row);
                    String url = (String) tableModel.getValueAt(modelRow, col);
                    if (url != null && !url.isEmpty()) {
                        try {
                            Desktop.getDesktop().browse(new java.net.URI(url));
                        } catch (Exception e) {
                            JOptionPane.showMessageDialog(MainApp.this,
                                    Messages.format("dialog.msg.linkOpenFailed", e.getMessage()),
                                    Messages.get("dialog.title.error"), JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }
            }
        });
        
        // 导出选项：下拉框存的是枚举（与显示语言无关），渲染器负责按当前语言把枚举翻译成文字，
        // 这样切换语言时只需repaint()重绘，不需要也不能靠比较显示文本来判断选中的是哪个选项
        exportTypeComboBox = new JComboBox<>(ExportType.values());
        exportTypeComboBox.setFont(mainFont);
        exportTypeComboBox.setRenderer(localizedRenderer());
        // 导出格式：仅UID（供Greasyfork脚本使用）或详细CSV（用户名/分组/不活跃天数/最后视频），修复 C7
        exportFormatComboBox = new JComboBox<>(ExportFormat.values());
        exportFormatComboBox.setFont(mainFont);
        exportFormatComboBox.setRenderer(localizedRenderer());
        exportButton = new JButton(Messages.get("button.exportInactive"));
        exportButton.setFont(mainFont);
        exportButton.setEnabled(false);

        // JComboBox在装了自定义渲染器后，Windows风格的UI没有按内容正确算出首选宽度（英文选项
        // "UID only (for the userscript)"被省略号截断），这里按两种语言里较长的文案给出一个够宽
        // 的下限，高度仍沿用L&F算出的自然高度
        widenToFit(exportTypeComboBox, 170);
        widenToFit(exportFormatComboBox, 235);
    }

    /** 把下拉框的首选宽度至少提高到minWidth，高度保持L&F原本算出的自然高度不变。 */
    private static void widenToFit(JComboBox<?> comboBox, int minWidth) {
        Dimension natural = comboBox.getPreferredSize();
        comboBox.setPreferredSize(new Dimension(Math.max(natural.width, minWidth), natural.height));
    }

    /**
     * 通用的枚举下拉框渲染器：把ExportType/ExportFormat这类带messageKey()的枚举值，
     * 按Messages当前语言渲染成文字。每次绘制都重新查询，语言切换后只需repaint()即可生效。
     */
    private static javax.swing.ListCellRenderer<Object> localizedRenderer() {
        DefaultListCellRenderer base = new DefaultListCellRenderer();
        return (list, value, index, isSelected, cellHasFocus) -> {
            String text;
            if (value instanceof ExportType) {
                text = Messages.get(((ExportType) value).messageKey());
            } else if (value instanceof ExportFormat) {
                text = Messages.get(((ExportFormat) value).messageKey());
            } else {
                text = String.valueOf(value);
            }
            return base.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus);
        };
    }
    
    private JTextField batchSizeField;
    private JTextField batchIntervalField;
    private JPanel statusBarPanel; // 新增：状态栏面板
    
    private void layoutComponents() {
        // 主面板使用边界布局
        setLayout(new BorderLayout());
        
        // 顶部面板 - 文件选择和设置
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBorder(new EmptyBorder(10, 15, 10, 15));
        
        // 文件选择面板 - 使用GridBagLayout提高布局灵活性
        JPanel filePanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        
        // 添加文件路径标签和文本框
        pathLabel = new JLabel(Messages.get("label.filePath"));
        pathLabel.setFont(mainFont);
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        filePanel.add(pathLabel, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        filePanel.add(filePathField, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0;
        filePanel.add(browseButton, gbc);

        gbc.gridx = 3;
        filePanel.add(loadCacheButton, gbc);

        // 语言选择器，放在这一行的最右侧
        gbc.gridx = 4;
        gbc.weightx = 0;
        gbc.insets = new Insets(5, 15, 5, 5);
        filePanel.add(languageLabel, gbc);

        gbc.gridx = 5;
        gbc.insets = new Insets(5, 5, 5, 5);
        filePanel.add(languageSelector, gbc);

        // 设置面板 - 使用GridBagLayout
        JPanel settingsPanel = new JPanel(new GridBagLayout());
        GridBagConstraints settingsGbc = new GridBagConstraints();
        settingsGbc.insets = new Insets(5, 5, 5, 5);
        settingsGbc.fill = GridBagConstraints.HORIZONTAL;
        
        // 不活跃天数设置
        daysLabel = new JLabel(Messages.get("label.daysInactive"));
        daysLabel.setFont(mainFont);
        settingsGbc.gridx = 0;
        settingsGbc.gridy = 0;
        settingsGbc.weightx = 0;
        settingsPanel.add(daysLabel, settingsGbc);
        
        settingsGbc.gridx = 1;
        settingsGbc.weightx = 0.2;
        settingsPanel.add(inactiveDaysField, settingsGbc);
        
        // 批量处理设置
        batchSizeLabel = new JLabel(Messages.get("label.batchSize"));
        batchSizeLabel.setFont(mainFont);
        settingsGbc.gridx = 2;
        settingsGbc.weightx = 0;
        settingsPanel.add(batchSizeLabel, settingsGbc);
        
        // 默认值收紧为约0.67请求/秒（batchSize=1，间隔1.5秒），避免默认配置就接近或超过风控阈值（修复 C2）
        batchSizeField = new JTextField("1", 3);
        batchSizeField.setFont(mainFont);
        settingsGbc.gridx = 3;
        settingsGbc.weightx = 0.1;
        settingsPanel.add(batchSizeField, settingsGbc);

        intervalLabel = new JLabel(Messages.get("label.batchInterval"));
        intervalLabel.setFont(mainFont);
        settingsGbc.gridx = 4;
        settingsGbc.weightx = 0;
        settingsPanel.add(intervalLabel, settingsGbc);

        batchIntervalField = new JTextField("1.5", 3);
        batchIntervalField.setFont(mainFont);
        settingsGbc.gridx = 5;
        settingsGbc.weightx = 0.1;
        settingsPanel.add(batchIntervalField, settingsGbc);
        
        settingsGbc.gridx = 6;
        settingsGbc.weightx = 0;
        settingsGbc.insets = new Insets(5, 15, 5, 5); // 增加左侧间距
        settingsPanel.add(processButton, settingsGbc);
        
        topPanel.add(filePanel, BorderLayout.NORTH);
        topPanel.add(settingsPanel, BorderLayout.CENTER);
        
        // 状态面板 - 使用BorderLayout让进度条右侧自动增长
        JPanel statusPanel = new JPanel(new BorderLayout(10, 0));
        statusPanel.setBorder(new EmptyBorder(5, 10, 5, 10));
        
        // 状态标签 - 放在左侧，固定宽度（比原来略宽，容纳英文文案通常比中文长的情况）
        statusLabel.setPreferredSize(new Dimension(260, 20));
        statusPanel.add(statusLabel, BorderLayout.WEST);
        
        // 进度条 - 放在中间，自动占据剩余空间并向右扩展
        progressBar.setPreferredSize(new Dimension(300, 20));
        progressBar.setMinimumSize(new Dimension(300, 20));
        statusPanel.add(progressBar, BorderLayout.CENTER);
        
        // 添加一个空面板在右侧，保持一定的右侧边距
        JPanel rightPadding = new JPanel();
        rightPadding.setPreferredSize(new Dimension(20, 20));
        statusPanel.add(rightPadding, BorderLayout.EAST);
        
        topPanel.add(statusPanel, BorderLayout.SOUTH);
        
        // 中间面板 - 结果表格
        JScrollPane scrollPane = new JScrollPane(resultTable);
        scrollPane.setBorder(new EmptyBorder(0, 15, 0, 15));
        scrollPane.getViewport().setBackground(Color.WHITE);
        
        // 底部面板 - 包含状态栏和导出选项
        JPanel bottomPanel = new JPanel(new BorderLayout());
        
        // 状态栏面板 - 左侧显示不活跃用户/全部
        statusBarPanel = new JPanel(new BorderLayout());
        statusBarPanel.setBorder(new EmptyBorder(5, 15, 5, 15));
        statusBarPanel.add(userCountLabel, BorderLayout.WEST);
        
        // 导出选项面板 - 右侧
        JPanel exportPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        exportPanel.setBorder(new EmptyBorder(5, 5, 5, 15));
        exportLabel = new JLabel(Messages.get("label.export"));
        exportLabel.setFont(mainFont);
        exportPanel.add(exportLabel);
        exportPanel.add(exportTypeComboBox);
        exportPanel.add(exportFormatComboBox);
        exportPanel.add(exportButton);
        
        bottomPanel.add(statusBarPanel, BorderLayout.WEST);
        bottomPanel.add(exportPanel, BorderLayout.EAST);
        
        // 添加到主面板
        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);
    }
    
    private void addListeners() {
        // 选择文件按钮
        browseButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JFileChooser fileChooser = new JFileChooser();
                fileChooser.setFileFilter(new FileNameExtensionFilter(Messages.get("filechooser.json"), "json"));
                int result = fileChooser.showOpenDialog(MainApp.this);

                if (result == JFileChooser.APPROVE_OPTION) {
                    selectedFile = fileChooser.getSelectedFile();
                    filePathField.setText(selectedFile.getAbsolutePath());
                    processButton.setEnabled(true);
                    setStatus("status.fileSelected", selectedFile.getName());
                }
            }
        });
        
        // 处理数据按钮
        processButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (selectedFile == null) {
                    JOptionPane.showMessageDialog(MainApp.this, Messages.get("dialog.msg.selectFileFirst"),
                            Messages.get("dialog.title.error"), JOptionPane.ERROR_MESSAGE);
                    return;
                }

                try {
                    int days = Integer.parseInt(inactiveDaysField.getText().trim());
                    if (days <= 0) {
                        throw new NumberFormatException();
                    }
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(MainApp.this, Messages.get("dialog.msg.invalidDays"),
                            Messages.get("dialog.title.error"), JOptionPane.ERROR_MESSAGE);
                    return;
                }

                // 禁用按钮，防止重复点击
                processButton.setEnabled(false);
                browseButton.setEnabled(false);
                loadCacheButton.setEnabled(false);

                // 清空表格
                tableModel.setRowCount(0);

                // 获取批量处理参数（DataProcessingTask内部会对batchSize/batchInterval做下限保护，修复 C2）
                int batchSize = 1;
                double batchInterval = 1.5;
                try {
                    batchSize = Integer.parseInt(batchSizeField.getText().trim());
                    batchInterval = Double.parseDouble(batchIntervalField.getText().trim());
                } catch (NumberFormatException ex) {
                    // 使用默认值
                }

                // 执行数据处理任务
                new DataProcessingTask(selectedFile, progressBar, statusLabel, batchSize, batchInterval) {
                    @Override
                    protected void done() {
                        try {
                            ProcessingResult result = get();
                            unknownUserCount = result.getUnknownCount();
                            displayResults(result.getUsers());
                            dataProcessed = true;
                            exportButton.setEnabled(true);

                            // 重新启用按钮
                            browseButton.setEnabled(true);
                            processButton.setEnabled(true);
                            loadCacheButton.setEnabled(true);

                            // 缓存文件路径单一来源于DataProcessingTask实际写入的位置，展示真实绝对路径（修复 C6）
                            if (result.getCacheFile() != null) {
                                setStatus("status.processDoneWithCache", result.getCacheFile().getAbsolutePath());
                            } else {
                                setStatus("status.processDone");
                            }

                            // 因风控被提前终止时明确告知用户（修复 C2）
                            if (result.isStoppedByRiskControl()) {
                                JOptionPane.showMessageDialog(MainApp.this,
                                        Messages.format("dialog.msg.riskControlStopped", result.getStopReason()),
                                        Messages.get("dialog.title.riskControlStopped"), JOptionPane.WARNING_MESSAGE);
                            }

                            // 应用不活跃天数过滤器
                            updateInactiveDaysFilter();
                        } catch (InterruptedException | ExecutionException ex) {
                            JOptionPane.showMessageDialog(MainApp.this,
                                    Messages.format("dialog.msg.processError", ex.getMessage()),
                                    Messages.get("dialog.title.error"), JOptionPane.ERROR_MESSAGE);
                            setStatus("status.processFailed", ex.getMessage());

                            // 重新启用按钮
                            browseButton.setEnabled(true);
                            processButton.setEnabled(true);
                            loadCacheButton.setEnabled(true);
                        }
                    }
                }.execute();
            }
        });
        
        // 加载缓存按钮
        loadCacheButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JFileChooser fileChooser = new JFileChooser();
                fileChooser.setFileFilter(new FileNameExtensionFilter(Messages.get("filechooser.jsonCache"), "json"));
                int result = fileChooser.showOpenDialog(MainApp.this);

                if (result != JFileChooser.APPROVE_OPTION) {
                    return;
                }

                File selectedCacheFile = fileChooser.getSelectedFile();
                if (!selectedCacheFile.exists()) {
                    JOptionPane.showMessageDialog(MainApp.this, Messages.get("dialog.msg.cacheFileMissing"),
                            Messages.get("dialog.title.error"), JOptionPane.ERROR_MESSAGE);
                    return;
                }

                try {
                    int days = Integer.parseInt(inactiveDaysField.getText().trim());
                    if (days <= 0) {
                        throw new NumberFormatException();
                    }
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(MainApp.this, Messages.get("dialog.msg.invalidDays"),
                            Messages.get("dialog.title.error"), JOptionPane.ERROR_MESSAGE);
                    return;
                }

                // 禁用按钮，防止重复点击
                processButton.setEnabled(false);
                browseButton.setEnabled(false);
                loadCacheButton.setEnabled(false);

                // 清空表格
                tableModel.setRowCount(0);

                // 执行缓存加载任务
                new CacheLoadingTask(selectedCacheFile, statusLabel) {
                    @Override
                    protected void done() {
                        try {
                            ProcessingResult result = get();
                            unknownUserCount = result.getUnknownCount();
                            displayResults(result.getUsers());
                            dataProcessed = true;
                            exportButton.setEnabled(true);

                            // 重新启用按钮
                            browseButton.setEnabled(true);
                            processButton.setEnabled(true);
                            loadCacheButton.setEnabled(true);

                            setStatus("status.cacheLoaded");

                            // 应用不活跃天数过滤器
                            updateInactiveDaysFilter();
                        } catch (InterruptedException | ExecutionException ex) {
                            JOptionPane.showMessageDialog(MainApp.this,
                                    Messages.format("dialog.msg.cacheLoadError", ex.getMessage()),
                                    Messages.get("dialog.title.error"), JOptionPane.ERROR_MESSAGE);
                            setStatus("status.cacheLoadFailed", ex.getMessage());

                            // 重新启用按钮
                            browseButton.setEnabled(true);
                            processButton.setEnabled(true);
                            loadCacheButton.setEnabled(true);
                        }
                    }
                }.execute();
            }
        });
        
        // 导出按钮
        exportButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!dataProcessed || inactiveUsers == null || inactiveUsers.isEmpty()) {
                    JOptionPane.showMessageDialog(MainApp.this, Messages.get("dialog.msg.noDataToExport"),
                            Messages.get("dialog.title.error"), JOptionPane.ERROR_MESSAGE);
                    return;
                }

                // 根据导出类型选择要导出的用户数据
                ExportType exportType = (ExportType) exportTypeComboBox.getSelectedItem();
                List<UserData> usersToExport;

                if (exportType == ExportType.SELECTED) {
                    // 获取表格中选中的用户数据
                    usersToExport = getSelectedTableUsers();
                    if (usersToExport.isEmpty()) {
                        JOptionPane.showMessageDialog(MainApp.this, Messages.get("dialog.msg.selectRowsFirst"),
                                Messages.get("dialog.title.notice"), JOptionPane.INFORMATION_MESSAGE);
                        return;
                    }
                } else {
                    // 获取当前表格中显示的所有用户数据
                    usersToExport = getCurrentTableUsers();
                    if (usersToExport.isEmpty()) {
                        JOptionPane.showMessageDialog(MainApp.this, Messages.get("dialog.msg.noRowsShown"),
                                Messages.get("dialog.title.error"), JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                }

                JFileChooser dirChooser = new JFileChooser();
                dirChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                int result = dirChooser.showSaveDialog(MainApp.this);

                if (result == JFileChooser.APPROVE_OPTION) {
                    File dir = dirChooser.getSelectedFile();

                    try {
                        // 导出格式：仅UID（供脚本使用）或详细CSV，此前exportDetailedInactiveUsers从未被调用（修复 C7）
                        boolean detailed = exportFormatComboBox.getSelectedItem() == ExportFormat.DETAILED_CSV;
                        String fileName = detailed
                                ? DataExporter.exportDetailedInactiveUsers(usersToExport, dir, exportType)
                                : DataExporter.exportInactiveUsers(usersToExport, dir, exportType);
                        JOptionPane.showMessageDialog(MainApp.this,
                                Messages.format("dialog.msg.exportSuccess", fileName),
                                Messages.get("dialog.title.success"), JOptionPane.INFORMATION_MESSAGE);
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(MainApp.this,
                                Messages.format("dialog.msg.exportFailed", ex.getMessage()),
                                Messages.get("dialog.title.error"), JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
        });

        // 语言选择器
        languageSelector.addActionListener(e -> {
            AppLocale selected = (AppLocale) languageSelector.getSelectedItem();
            if (selected != null && !selected.locale.equals(Messages.getCurrentLocale())) {
                Messages.setLocale(selected.locale);
            }
        });
    }

    /**
     * 设置状态栏文字，同时记下用的哪个key/参数，供retranslate()在切换语言时按新语言重新生成
     * （后台任务通过process()直接写入的过程性进度文字不走这条路径，见字段声明处的说明）。
     */
    private void setStatus(String key, Object... args) {
        lastStatusKey = key;
        lastStatusArgs = args;
        statusLabel.setText(args.length == 0 ? Messages.get(key) : Messages.format(key, args));
    }

    /**
     * 切换界面语言后，重新套用所有静态文案：标题、按钮/标签、表头、下拉选项、已加载数据的
     * 表格内容（不活跃天数列的"无影片/未知"、"最新影片"列的占位文案均按当前语言即时计算，见
     * UserData），以及左下角计数。statusLabel按最近一次setStatus()记录的key/参数重新生成。
     */
    private void retranslate() {
        setTitle(Messages.get("app.title"));

        browseButton.setText(Messages.get("button.browse"));
        processButton.setText(Messages.get("button.processData"));
        loadCacheButton.setText(Messages.get("button.loadCache"));
        exportButton.setText(Messages.get("button.exportInactive"));

        pathLabel.setText(Messages.get("label.filePath"));
        daysLabel.setText(Messages.get("label.daysInactive"));
        batchSizeLabel.setText(Messages.get("label.batchSize"));
        intervalLabel.setText(Messages.get("label.batchInterval"));
        exportLabel.setText(Messages.get("label.export"));
        languageLabel.setText(Messages.get("label.language"));
        languageSelector.setToolTipText(Messages.get("tooltip.language"));

        String[] headers = {
                Messages.get("table.header.uid"),
                Messages.get("table.header.name"),
                Messages.get("table.header.group"),
                Messages.get("table.header.daysInactive"),
                Messages.get("table.header.latestVideo"),
                Messages.get("table.header.videoLink"),
                Messages.get("table.header.space")
        };
        for (int i = 0; i < headers.length; i++) {
            resultTable.getColumnModel().getColumn(i).setHeaderValue(headers[i]);
        }
        resultTable.getTableHeader().repaint();

        exportTypeComboBox.repaint();
        exportFormatComboBox.repaint();

        if (lastStatusKey != null) {
            statusLabel.setText(lastStatusArgs.length == 0
                    ? Messages.get(lastStatusKey)
                    : Messages.format(lastStatusKey, lastStatusArgs));
        }

        if (dataProcessed) {
            // 重新按当前筛选条件重建表格：行内容（含"无影片/未知"占位文案）与左下角计数都会用新语言刷新
            updateInactiveDaysFilter();
        } else {
            userCountLabel.setText(formatUserCountText(0, 0));
        }

        revalidate();
        repaint();
    }

    // 实时更新不活跃天数筛选
    private void updateInactiveDaysFilter() {
        if (!dataProcessed || inactiveUsers == null) {
            return;
        }
        
        try {
            int inactiveDays = Integer.parseInt(inactiveDaysField.getText().trim());
            if (inactiveDays < 0) {
                return;
            }
            
            List<UserData> filteredUsers = new ArrayList<>();
            
            // 当不活跃天数为0时，显示所有用户
            if (inactiveDays == 0) {
                filteredUsers = new ArrayList<>(inactiveUsers);
            } else {
                for (UserData user : inactiveUsers) {
                    if (user.isInactive(inactiveDays)) {
                        filteredUsers.add(user);
                    }
                }
            }
            
            // 更新表格显示
            displayFilteredResults(filteredUsers);
        } catch (NumberFormatException e) {
            // 忽略无效输入
        }
    }
    
    private void displayFilteredResults(List<UserData> users) {
        // 清空表格
        tableModel.setRowCount(0);
        
        // 添加数据到表格
        for (UserData user : users) {
            Object[] rowData = {
                user.getUid(),
                user.getUsername(),
                String.join(", ", user.getTags()),
                user.getInactiveDays(),
                user.getLastVideoTitle(),
                user.getVideoUrl(),
                user.getSpaceUrl()
            };
            tableModel.addRow(rowData);
        }
        
        // 设置默认按不活跃天数升序排序
        if (resultTable.getRowSorter() != null) {
            resultTable.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(3, SortOrder.ASCENDING)));
        }
        
        // 更新左下角状态栏标签，显示不活跃用户数量和总用户数量
        userCountLabel.setText(formatUserCountText(users.size(), inactiveUsers.size()));
    }

    /**
     * 在不活跃用户/全部计数后附加"未知"账号数量提示（修复 C1：让用户能看到有多少账号未能确认活跃度）
     */
    private String formatUserCountText(int shown, int total) {
        if (unknownUserCount > 0) {
            return Messages.format("status.count.withUnknown",
                    String.valueOf(shown), String.valueOf(total), String.valueOf(unknownUserCount));
        }
        return Messages.format("status.count.base", String.valueOf(shown), String.valueOf(total));
    }

    /**
     * 将inactiveUsers与其uid索引作为一个整体更新，避免二者不同步（修复 C9：索引替代线性扫描）
     */
    private void setInactiveUsers(List<UserData> users) {
        inactiveUsers = new ArrayList<>(users);
        Map<Long, UserData> index = new HashMap<>();
        for (UserData user : inactiveUsers) {
            index.put(user.getUid(), user);
        }
        uidToUser = index;
    }

    /**
     * 获取当前表格中显示的用户数据
     * @return 当前表格中显示的用户数据列表
     */
    private List<UserData> getCurrentTableUsers() {
        List<UserData> currentUsers = new ArrayList<>();

        // 获取当前表格中的所有行数据
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            // 获取行的模型索引（考虑表格排序）
            int modelRow = i;
            if (resultTable.getRowSorter() != null) {
                modelRow = resultTable.convertRowIndexToModel(i);
            }

            // O(1)通过uid索引找到对应的用户对象，而非线性扫描inactiveUsers（修复 C9）
            long uid = (long) tableModel.getValueAt(modelRow, 0);
            UserData user = uidToUser.get(uid);
            if (user != null) {
                currentUsers.add(user);
            }
        }

        return currentUsers;
    }

    /**
     * 获取表格中选中的用户数据
     * @return 表格中选中的用户数据列表
     */
    private List<UserData> getSelectedTableUsers() {
        List<UserData> selectedUsers = new ArrayList<>();

        // 获取表格中选中的行
        int[] selectedRows = resultTable.getSelectedRows();

        if (selectedRows.length == 0) {
            return selectedUsers; // 没有选中任何行
        }

        // 遍历选中的行
        for (int viewRow : selectedRows) {
            // 获取行的模型索引（考虑表格排序）
            int modelRow = viewRow;
            if (resultTable.getRowSorter() != null) {
                modelRow = resultTable.convertRowIndexToModel(viewRow);
            }

            // O(1)通过uid索引找到对应的用户对象，而非线性扫描inactiveUsers（修复 C9）
            long uid = (long) tableModel.getValueAt(modelRow, 0);
            UserData user = uidToUser.get(uid);
            if (user != null) {
                selectedUsers.add(user);
            }
        }

        return selectedUsers;
    }

    private void displayResults(List<UserData> users) {
        if (users == null || users.isEmpty()) {
            JOptionPane.showMessageDialog(this, Messages.get("dialog.msg.noInactiveFound"),
                    Messages.get("dialog.title.notice"), JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // 保存完整的用户列表及其uid索引，用于后续筛选
        setInactiveUsers(users);

        // 清空表格
        tableModel.setRowCount(0);
        
        // 添加数据到表格
        for (UserData user : users) {
            Object[] rowData = {
                user.getUid(),
                user.getUsername(),
                String.join(", ", user.getTags()),
                user.getInactiveDays(),
                user.getLastVideoTitle(),
                user.getVideoUrl(),
                user.getSpaceUrl()
            };
            tableModel.addRow(rowData);
        }
        
        // 设置默认按不活跃天数升序排序
        if (resultTable.getRowSorter() != null) {
            resultTable.getRowSorter().setSortKeys(List.of(new RowSorter.SortKey(3, SortOrder.ASCENDING)));
        }
        
        // 更新左下角状态栏标签，显示不活跃用户数量和总用户数量
        userCountLabel.setText(formatUserCountText(users.size(), users.size()));
    }

    public static void main(String[] args) {
        try {
            // 设置本地系统外观
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            
            // 设置全局字体渲染属性，提高字体显示质量
            System.setProperty("awt.useSystemAAFontSettings", "on");
            System.setProperty("swing.aatext", "true");
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new MainApp().setVisible(true);
            }
        });
    }
}