package org.example;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
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
     * 导出不活跃用户数据
     * 
     * @param users 不活跃用户列表
     * @param directory 导出目录
     * @param exportType 导出类型（导出全部、导出无分组、导出有分组）
     * @return 导出文件名
     * @throws IOException 如果导出过程中发生IO错误
     */
    public static String exportInactiveUsers(List<UserData> users, File directory, String exportType) throws IOException {
        if (users == null || users.isEmpty()) {
            throw new IllegalArgumentException("没有数据可导出");
        }
        
        if (!directory.exists() || !directory.isDirectory()) {
            throw new IllegalArgumentException("导出目录不存在或不是一个目录");
        }
        
        // 根据导出类型筛选用户
        List<UserData> usersToExport = new ArrayList<>();
        
        switch (exportType) {
            case "导出已选择":
                // 直接导出传入的用户列表，不需要额外筛选
                usersToExport.addAll(users);
                break;
            case "导出全部":
                usersToExport.addAll(users);
                break;
            case "导出无分组":
                for (UserData user : users) {
                    if (!user.hasTags()) {
                        usersToExport.add(user);
                    }
                }
                break;
            case "导出有分组":
                for (UserData user : users) {
                    if (user.hasTags()) {
                        usersToExport.add(user);
                    }
                }
                break;
            default:
                throw new IllegalArgumentException("不支持的导出类型: " + exportType);
        }
        
        if (usersToExport.isEmpty()) {
            throw new IllegalArgumentException("没有符合条件的用户可导出");
        }
        
        // 生成文件名
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = "inactive_users_" + timestamp + ".txt";
        File outputFile = new File(directory, fileName);
        
        // 写入文件
        try (FileWriter writer = new FileWriter(outputFile)) {
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
     * 导出详细的不活跃用户数据（包含更多信息）
     * 
     * @param users 不活跃用户列表
     * @param directory 导出目录
     * @param exportType 导出类型
     * @return 导出文件名
     * @throws IOException 如果导出过程中发生IO错误
     */
    public static String exportDetailedInactiveUsers(List<UserData> users, File directory, String exportType) throws IOException {
        if (users == null || users.isEmpty()) {
            throw new IllegalArgumentException("没有数据可导出");
        }
        
        if (!directory.exists() || !directory.isDirectory()) {
            throw new IllegalArgumentException("导出目录不存在或不是一个目录");
        }
        
        // 根据导出类型筛选用户
        List<UserData> usersToExport = new ArrayList<>();
        
        switch (exportType) {
            case "导出已选择":
                // 直接导出传入的用户列表，不需要额外筛选
                usersToExport.addAll(users);
                break;
            case "导出全部":
                usersToExport.addAll(users);
                break;
            case "导出无分组":
                for (UserData user : users) {
                    if (!user.hasTags()) {
                        usersToExport.add(user);
                    }
                }
                break;
            case "导出有分组":
                for (UserData user : users) {
                    if (user.hasTags()) {
                        usersToExport.add(user);
                    }
                }
                break;
            default:
                throw new IllegalArgumentException("不支持的导出类型: " + exportType);
        }
        
        if (usersToExport.isEmpty()) {
            throw new IllegalArgumentException("没有符合条件的用户可导出");
        }
        
        // 生成文件名
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String fileName = "inactive_users_detailed_" + timestamp + ".txt";
        File outputFile = new File(directory, fileName);
        
        // 写入文件
        try (FileWriter writer = new FileWriter(outputFile)) {
            writer.write("UID,用户名,分组,不活跃天数,最后更新视频\n");
            
            for (UserData user : usersToExport) {
                writer.write(String.format("%d,\"%s\",\"%s\",%d,\"%s\"\n",
                        user.getUid(),
                        user.getUsername().replace("\"", "\\\""),
                        String.join(", ", user.getTags()).replace("\"", "\\\""),
                        user.getInactiveDays(),
                        user.getLastVideoTitle().replace("\"", "\\\"")));
            }
        }
        
        return fileName;
    }
}