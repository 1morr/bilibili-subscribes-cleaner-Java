package org.example;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据导出工具类
 * 用于将不活跃用户的数据导出到文件中
 */
public class DataExporter {

    /**
     * 导出不活跃用户数据（仅UID，逗号分隔，供Greasyfork用户脚本读取）
     *
     * @param users 不活跃用户列表
     * @param directory 导出目录
     * @param exportType 导出类型（导出已选择、导出全部、导出无分组、导出有分组）
     * @return 导出文件名
     * @throws IOException 如果导出过程中发生IO错误
     */
    public static String exportInactiveUsers(List<UserData> users, File directory, ExportType exportType) throws IOException {
        List<UserData> usersToExport = filterByExportType(users, directory, exportType);

        // 生成文件名
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = "inactive_users_" + timestamp + ".txt";
        File outputFile = new File(directory, fileName);

        // 显式使用UTF-8写入，避免在中文Windows上因平台默认编码（GBK）导致乱码（修复 C4）
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile.toPath(), StandardCharsets.UTF_8)) {
            for (int i = 0; i < usersToExport.size(); i++) {
                UserData user = usersToExport.get(i);
                writer.write(String.valueOf(user.getUid()));

                // 如果不是最后一个用户，添加逗号分隔符
                if (i < usersToExport.size() - 1) {
                    writer.write(",");
                }
            }
        }

        return fileName;
    }

    /**
     * 导出详细的不活跃用户数据（包含用户名、分组、不活跃天数、最后更新视频的CSV，此前从未被UI调用，修复 C7）
     *
     * @param users 不活跃用户列表
     * @param directory 导出目录
     * @param exportType 导出类型
     * @return 导出文件名
     * @throws IOException 如果导出过程中发生IO错误
     */
    public static String exportDetailedInactiveUsers(List<UserData> users, File directory, ExportType exportType) throws IOException {
        List<UserData> usersToExport = filterByExportType(users, directory, exportType);

        // 生成文件名
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = "inactive_users_detailed_" + timestamp + ".txt";
        File outputFile = new File(directory, fileName);

        // 显式使用UTF-8写入，避免在中文Windows上因平台默认编码（GBK）导致乱码（修复 C4）
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile.toPath(), StandardCharsets.UTF_8)) {
            // CSV表头复用表格列标题的翻译，保证导出文件与UI表格用词一致
            String header = String.join(",",
                    Messages.get("table.header.uid"),
                    Messages.get("table.header.name"),
                    Messages.get("table.header.group"),
                    Messages.get("table.header.daysInactive"),
                    Messages.get("table.header.latestVideo")) + "\n";
            writer.write(header);

            for (UserData user : usersToExport) {
                writer.write(String.format("%d,\"%s\",\"%s\",%d,\"%s\"\n",
                        user.getUid(),
                        escapeCsv(user.getUsername()),
                        escapeCsv(String.join(", ", user.getTags())),
                        user.getInactiveDays(),
                        escapeCsv(user.getLastVideoTitle())));
            }
        }

        return fileName;
    }

    private static String escapeCsv(String value) {
        return value == null ? "" : value.replace("\"", "\\\"");
    }

    /**
     * 按导出类型筛选用户，并统一在此处剔除状态未知（UNKNOWN）的账号——
     * 无论调用方传入什么列表，未能确认活跃度的账号绝不能出现在导出文件里（修复 C1 的最后一道防线）。
     */
    private static List<UserData> filterByExportType(List<UserData> users, File directory, ExportType exportType) {
        if (users == null || users.isEmpty()) {
            throw new IllegalArgumentException(Messages.get("dialog.msg.noDataToExport"));
        }

        if (!directory.exists() || !directory.isDirectory()) {
            throw new IllegalArgumentException(Messages.get("export.error.dirInvalid"));
        }

        List<UserData> usersToExport = new ArrayList<>();

        for (UserData user : users) {
            if (user.isUnknown()) {
                // 未能确认活跃度的账号（请求失败/风控）永远不能被导出去取关
                continue;
            }

            switch (exportType) {
                case SELECTED:
                case ALL:
                    usersToExport.add(user);
                    break;
                case NO_GROUP:
                    if (!user.hasTags()) {
                        usersToExport.add(user);
                    }
                    break;
                case HAS_GROUP:
                    if (user.hasTags()) {
                        usersToExport.add(user);
                    }
                    break;
                default:
                    throw new AssertionError("Unhandled ExportType: " + exportType);
            }
        }

        if (usersToExport.isEmpty()) {
            throw new IllegalArgumentException(Messages.get("export.error.noMatching"));
        }

        return usersToExport;
    }
}
