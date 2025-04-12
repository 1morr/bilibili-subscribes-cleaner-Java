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
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.ArrayList;

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
    private JComboBox<String> exportTypeComboBox;
    private JLabel statusLabel;
    private JProgressBar progressBar;
    private JButton loadCacheButton;
    private JLabel userCountLabel; // 新增：用于显示不活跃用户/全部的标签
    
    private File selectedFile;
    private File cacheFile;
    private List<UserData> inactiveUsers;
    private boolean dataProcessed = false;
    
    // 定义全局字体
    private Font mainFont;
    private Font boldFont;
    private Font tableFont;
    
    public MainApp() {
        setTitle("B站用户活跃度分析工具");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(950, 650);
        setLocationRelativeTo(null);
        
        // 初始化字体
        initFonts();
        initComponents();
        layoutComponents();
        addListeners();
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
        browseButton = new JButton("选择文件");
        browseButton.setFont(mainFont);
        
        // 不活跃天数设置
        inactiveDaysField = new JTextField("365", 5);
        inactiveDaysField.setFont(mainFont);
        // 添加文本变化监听器，实现实时筛选
        inactiveDaysField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                updateInactiveDaysFilter();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                updateInactiveDaysFilter();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                updateInactiveDaysFilter();
            }
        });
        
        // 处理按钮
        processButton = new JButton("处理数据");
        processButton.setEnabled(false);
        processButton.setFont(mainFont);
        
        // 缓存加载按钮
        loadCacheButton = new JButton("加载缓存");
        loadCacheButton.setFont(mainFont);
        
        // 状态显示
        statusLabel = new JLabel("请选择export_uids.json文件");
        statusLabel.setFont(mainFont);
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setPreferredSize(new Dimension(300, 20)); // 固定进度条长度
        
        // 用户计数标签（将在左下角显示）
        userCountLabel = new JLabel("0/0 不活跃用户/全部");
        userCountLabel.setFont(mainFont);
        
        // 结果表格
        String[] columnNames = {"UID", "用户名", "分组", "不活跃天数", "最后更新视频", "视频链接", "空间链接"};
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
        resultTable.getColumnModel().getColumn(0).setPreferredWidth(60);
        resultTable.getColumnModel().getColumn(1).setPreferredWidth(150);
        resultTable.getColumnModel().getColumn(2).setPreferredWidth(100);
        resultTable.getColumnModel().getColumn(3).setPreferredWidth(80);
        resultTable.getColumnModel().getColumn(4).setPreferredWidth(250);
        resultTable.getColumnModel().getColumn(5).setPreferredWidth(100);
        resultTable.getColumnModel().getColumn(6).setPreferredWidth(100);
        
        // 添加表格排序功能
        resultTable.setAutoCreateRowSorter(true);
        
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
                            JOptionPane.showMessageDialog(MainApp.this, "无法打开链接: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }
            }
        });
        
        // 导出选项
        exportTypeComboBox = new JComboBox<>(new String[]{"导出已选择", "导出全部", "导出无分组", "导出有分组"});
        exportTypeComboBox.setFont(mainFont);
        exportButton = new JButton("导出不活跃用户");
        exportButton.setFont(mainFont);
        exportButton.setEnabled(false);
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
        JLabel pathLabel = new JLabel("文件路径:");
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
        
        // 设置面板 - 使用GridBagLayout
        JPanel settingsPanel = new JPanel(new GridBagLayout());
        GridBagConstraints settingsGbc = new GridBagConstraints();
        settingsGbc.insets = new Insets(5, 5, 5, 5);
        settingsGbc.fill = GridBagConstraints.HORIZONTAL;
        
        // 不活跃天数设置
        JLabel daysLabel = new JLabel("不活跃天数:");
        daysLabel.setFont(mainFont);
        settingsGbc.gridx = 0;
        settingsGbc.gridy = 0;
        settingsGbc.weightx = 0;
        settingsPanel.add(daysLabel, settingsGbc);
        
        settingsGbc.gridx = 1;
        settingsGbc.weightx = 0.2;
        settingsPanel.add(inactiveDaysField, settingsGbc);
        
        // 批量处理设置
        JLabel batchSizeLabel = new JLabel("批量处理数量:");
        batchSizeLabel.setFont(mainFont);
        settingsGbc.gridx = 2;
        settingsGbc.weightx = 0;
        settingsPanel.add(batchSizeLabel, settingsGbc);
        
        batchSizeField = new JTextField("2", 3);
        batchSizeField.setFont(mainFont);
        settingsGbc.gridx = 3;
        settingsGbc.weightx = 0.1;
        settingsPanel.add(batchSizeField, settingsGbc);
        
        JLabel intervalLabel = new JLabel("批次间隔(秒):");
        intervalLabel.setFont(mainFont);
        settingsGbc.gridx = 4;
        settingsGbc.weightx = 0;
        settingsPanel.add(intervalLabel, settingsGbc);
        
        batchIntervalField = new JTextField("1", 3);
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
        
        // 状态标签 - 放在左侧，固定宽度
        statusLabel.setPreferredSize(new Dimension(200, 20));
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
        JLabel exportLabel = new JLabel("导出选项:");
        exportLabel.setFont(mainFont);
        exportPanel.add(exportLabel);
        exportPanel.add(exportTypeComboBox);
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
                fileChooser.setFileFilter(new FileNameExtensionFilter("JSON文件", "json"));
                int result = fileChooser.showOpenDialog(MainApp.this);
                
                if (result == JFileChooser.APPROVE_OPTION) {
                    selectedFile = fileChooser.getSelectedFile();
                    filePathField.setText(selectedFile.getAbsolutePath());
                    processButton.setEnabled(true);
                    statusLabel.setText("已选择文件: " + selectedFile.getName());
                }
            }
        });
        
        // 处理数据按钮
        processButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (selectedFile == null) {
                    JOptionPane.showMessageDialog(MainApp.this, "请先选择文件", "错误", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                
                int inactiveDays;
                try {
                    inactiveDays = Integer.parseInt(inactiveDaysField.getText().trim());
                    if (inactiveDays <= 0) {
                        throw new NumberFormatException();
                    }
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(MainApp.this, "请输入有效的天数", "错误", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                
                // 禁用按钮，防止重复点击
                processButton.setEnabled(false);
                browseButton.setEnabled(false);
                loadCacheButton.setEnabled(false);
                
                // 清空表格
                tableModel.setRowCount(0);
                
                // 获取批量处理参数
                int batchSize = 2;
                double batchInterval = 1.0;
                try {
                    batchSize = Integer.parseInt(batchSizeField.getText().trim());
                    batchInterval = Double.parseDouble(batchIntervalField.getText().trim());
                } catch (NumberFormatException ex) {
                    // 使用默认值
                }
                
                // 执行数据处理任务
                new DataProcessingTask(selectedFile, inactiveDays, progressBar, statusLabel, batchSize, batchInterval) {
                    @Override
                    protected void done() {
                        try {
                            inactiveUsers = get();
                            displayResults(inactiveUsers);
                            dataProcessed = true;
                            exportButton.setEnabled(true);
                            
                            // 重新启用按钮
                            browseButton.setEnabled(true);
                            processButton.setEnabled(true);
                            loadCacheButton.setEnabled(true);
                            
                            // 创建缓存文件
                            cacheFile = new File("user_data_cache.json");
                            statusLabel.setText("处理完成，已创建缓存文件: user_data_cache.json");
                            
                            // 应用不活跃天数过滤器
                            updateInactiveDaysFilter();
                        } catch (InterruptedException | ExecutionException ex) {
                            JOptionPane.showMessageDialog(MainApp.this, "处理数据时出错: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                            statusLabel.setText("处理失败: " + ex.getMessage());
                            
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
                fileChooser.setFileFilter(new FileNameExtensionFilter("JSON缓存文件", "json"));
                int result = fileChooser.showOpenDialog(MainApp.this);
                
                if (result != JFileChooser.APPROVE_OPTION) {
                    return;
                }
                
                File cacheFile = fileChooser.getSelectedFile();
                if (!cacheFile.exists()) {
                    JOptionPane.showMessageDialog(MainApp.this, "缓存文件不存在", "错误", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                
                int inactiveDays;
                try {
                    inactiveDays = Integer.parseInt(inactiveDaysField.getText().trim());
                    if (inactiveDays <= 0) {
                        throw new NumberFormatException();
                    }
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(MainApp.this, "请输入有效的天数", "错误", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                
                // 禁用按钮，防止重复点击
                processButton.setEnabled(false);
                browseButton.setEnabled(false);
                loadCacheButton.setEnabled(false);
                
                // 清空表格
                tableModel.setRowCount(0);
                
                // 执行缓存加载任务
                new CacheLoadingTask(cacheFile, inactiveDays, statusLabel) {
                    @Override
                    protected void done() {
                        try {
                            inactiveUsers = get();
                            displayResults(inactiveUsers);
                            dataProcessed = true;
                            exportButton.setEnabled(true);
                            
                            // 重新启用按钮
                            browseButton.setEnabled(true);
                            processButton.setEnabled(true);
                            loadCacheButton.setEnabled(true);
                            
                            statusLabel.setText("从缓存加载数据完成");
                            
                            // 应用不活跃天数过滤器
                            updateInactiveDaysFilter();
                        } catch (InterruptedException | ExecutionException ex) {
                            JOptionPane.showMessageDialog(MainApp.this, "加载缓存时出错: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                            statusLabel.setText("加载失败: " + ex.getMessage());
                            
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
                    JOptionPane.showMessageDialog(MainApp.this, "没有数据可导出", "错误", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                
                // 根据导出类型选择要导出的用户数据
                String exportType = (String) exportTypeComboBox.getSelectedItem();
                List<UserData> usersToExport;
                
                if ("导出已选择".equals(exportType)) {
                    // 获取表格中选中的用户数据
                    usersToExport = getSelectedTableUsers();
                    if (usersToExport.isEmpty()) {
                        JOptionPane.showMessageDialog(MainApp.this, "请先在表格中选择要导出的用户", "提示", JOptionPane.INFORMATION_MESSAGE);
                        return;
                    }
                } else {
                    // 获取当前表格中显示的所有用户数据
                    usersToExport = getCurrentTableUsers();
                    if (usersToExport.isEmpty()) {
                        JOptionPane.showMessageDialog(MainApp.this, "当前表格中没有显示任何用户数据", "错误", JOptionPane.ERROR_MESSAGE);
                        return;
                    }
                }
                
                JFileChooser dirChooser = new JFileChooser();
                dirChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                int result = dirChooser.showSaveDialog(MainApp.this);
                
                if (result == JFileChooser.APPROVE_OPTION) {
                    File dir = dirChooser.getSelectedFile();
                    
                    try {
                        String fileName = DataExporter.exportInactiveUsers(usersToExport, dir, exportType);
                        JOptionPane.showMessageDialog(MainApp.this, "导出成功: " + fileName, "成功", JOptionPane.INFORMATION_MESSAGE);
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(MainApp.this, "导出失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                    }
                }
            }
        });
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
        userCountLabel.setText(users.size() + "/" + inactiveUsers.size() + " 不活跃用户/全部");
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
            
            // 从原始用户列表中找到对应的用户对象
            long uid = (long) tableModel.getValueAt(modelRow, 0);
            for (UserData user : inactiveUsers) {
                if (user.getUid() == uid) {
                    currentUsers.add(user);
                    break;
                }
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
            
            // 从原始用户列表中找到对应的用户对象
            long uid = (long) tableModel.getValueAt(modelRow, 0);
            for (UserData user : inactiveUsers) {
                if (user.getUid() == uid) {
                    selectedUsers.add(user);
                    break;
                }
            }
        }
        
        return selectedUsers;
    }
    
    private void displayResults(List<UserData> users) {
        if (users == null || users.isEmpty()) {
            JOptionPane.showMessageDialog(this, "没有找到不活跃用户", "信息", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        
        // 保存完整的用户列表，用于后续筛选
        inactiveUsers = new ArrayList<>(users);
        
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
        userCountLabel.setText(users.size() + "/" + users.size() + " 不活跃用户/全部");
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