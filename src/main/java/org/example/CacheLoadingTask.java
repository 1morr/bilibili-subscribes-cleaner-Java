package org.example;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.swing.*;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 缓存加载任务类
 * 从缓存文件中加载用户数据，避免重复请求B站API
 */
public class CacheLoadingTask extends SwingWorker<List<UserData>, Void> {
    private final File cacheFile;
    private final int inactiveDays;
    private final JLabel statusLabel;
    private final ObjectMapper objectMapper;
    
    public CacheLoadingTask(File cacheFile, int inactiveDays, JLabel statusLabel) {
        this.cacheFile = cacheFile;
        this.inactiveDays = inactiveDays;
        this.statusLabel = statusLabel;
        this.objectMapper = new ObjectMapper();
    }
    
    @Override
    protected List<UserData> doInBackground() throws Exception {
        statusLabel.setText("正在从缓存加载数据...");
        
        // 读取缓存文件
        List<Map<String, Object>> userVidList = objectMapper.readValue(
                cacheFile, new TypeReference<List<Map<String, Object>>>() {});
        
        // 读取export_uids.json获取用户名和标签信息
        // 首先尝试在缓存文件所在目录查找
        File exportUidsFile = new File(cacheFile.getParentFile(), "export_uids.json");
        if (!exportUidsFile.exists()) {
            // 如果不存在，尝试在当前目录查找
            exportUidsFile = new File("export_uids.json");
            if (!exportUidsFile.exists()) {
                // 如果仍然不存在，让用户选择文件
                JFileChooser fileChooser = new JFileChooser();
                fileChooser.setDialogTitle("请选择export_uids.json文件");
                fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON文件", "json"));
                int result = fileChooser.showOpenDialog(null);
                
                if (result == JFileChooser.APPROVE_OPTION) {
                    exportUidsFile = fileChooser.getSelectedFile();
                } else {
                    throw new Exception("未选择export_uids.json文件，无法获取用户名和标签信息");
                }
            }
        }
        
        List<Map<String, Object>> userData = objectMapper.readValue(
                exportUidsFile, new TypeReference<List<Map<String, Object>>>() {});
        
        // 创建用户ID到用户名和标签的映射
        Map<Long, String> midToName = new HashMap<>();
        Map<Long, List<String>> midToTags = new HashMap<>();
        
        for (Map<String, Object> user : userData) {
            long mid = ((Number) user.get("mid")).longValue();
            String name = (String) user.get("name");
            List<String> tags = objectMapper.convertValue(user.get("tag"), new TypeReference<List<String>>() {});
            
            midToName.put(mid, name);
            midToTags.put(mid, tags);
        }
        
        // 处理数据
        return processUserData(userVidList, midToName, midToTags);
    }
    
    private List<UserData> processUserData(List<Map<String, Object>> userVidList, 
                                          Map<Long, String> midToName, 
                                          Map<Long, List<String>> midToTags) throws Exception {
        List<UserData> allUsers = new ArrayList<>();
        List<UserData> usersWithNoVideos = new ArrayList<>();
        
        for (Map<String, Object> entry : userVidList) {
            long mid = ((Number) entry.get("mid")).longValue();
            JsonNode userVid = objectMapper.convertValue(entry.get("user_vid"), JsonNode.class);
            
            try {
                // 检查是否有视频数据
                if (userVid.path("data").path("archives").size() > 0) {
                    JsonNode firstVideo = userVid.path("data").path("archives").get(0);
                    String videoTitle = firstVideo.path("title").asText();
                    long pubdate = firstVideo.path("pubdate").asLong();
                    String bvid = firstVideo.path("bvid").asText();
                    
                    UserData userData = new UserData(
                            mid, 
                            midToName.get(mid), 
                            midToTags.get(mid), 
                            videoTitle, 
                            pubdate, 
                            bvid);
                    
                    allUsers.add(userData);
                } else {
                    // 用户没有视频
                    UserData userData = new UserData(
                            mid, 
                            midToName.get(mid), 
                            midToTags.get(mid));
                    
                    usersWithNoVideos.add(userData);
                }
            } catch (Exception e) {
                System.err.println("处理用户 " + mid + " 的数据时出错: " + e.getMessage());
                // 创建一个没有视频的用户数据对象
                UserData userData = new UserData(
                        mid, 
                        midToName.get(mid), 
                        midToTags.get(mid));
                
                usersWithNoVideos.add(userData);
            }
        }
        
        // 合并所有用户列表
        List<UserData> allUsersCombined = new ArrayList<>(allUsers);
        allUsersCombined.addAll(usersWithNoVideos);
        
        // 返回所有用户数据，不再筛选不活跃用户
        // 筛选工作将由MainApp中的updateInactiveDaysFilter方法完成
        statusLabel.setText("从缓存加载数据完成，共 " + allUsersCombined.size() + " 个用户");
        return allUsersCombined;
    }
}