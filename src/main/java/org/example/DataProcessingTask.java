package org.example;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.swing.*;
import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import me.tongfei.progressbar.ProgressBar;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

/**
 * 数据处理任务类
 * 在后台线程中处理B站API请求和数据分析
 */
public class DataProcessingTask extends SwingWorker<List<UserData>, Integer> {
    private final File inputFile;
    private final int inactiveDays;
    private final JProgressBar progressBar;
    private final JLabel statusLabel;
    private final ObjectMapper objectMapper;
    private final int batchSize; // 批量处理数量
    private final double batchInterval; // 批次间隔(秒)，支持小数
    
    // B站API请求头
    private static final Map<String, String> HEADERS = new HashMap<>();
    static {
        HEADERS.put("authority", "api.vc.bilibili.com");
        HEADERS.put("accept", "application/json, text/plain, */*");
        HEADERS.put("accept-language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6");
        HEADERS.put("content-type", "application/x-www-form-urlencoded");
        HEADERS.put("origin", "https://message.bilibili.com");
        HEADERS.put("referer", "https://message.bilibili.com/");
        HEADERS.put("sec-ch-ua", "\"Chromium\";v=\"116\", \"Not)A;Brand\";v=\"24\", \"Microsoft Edge\";v=\"116\"");
        HEADERS.put("sec-ch-ua-mobile", "?0");
        HEADERS.put("sec-ch-ua-platform", "\"Windows\"");
        HEADERS.put("sec-fetch-dest", "empty");
        HEADERS.put("sec-fetch-mode", "cors");
        HEADERS.put("sec-fetch-site", "same-site");
        HEADERS.put("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36 Edg/116.0.1938.81");
    }
    
    public DataProcessingTask(File inputFile, int inactiveDays, JProgressBar progressBar, JLabel statusLabel) {
        this(inputFile, inactiveDays, progressBar, statusLabel, 2, 1);
    }
    
    public DataProcessingTask(File inputFile, int inactiveDays, JProgressBar progressBar, JLabel statusLabel, int batchSize, double batchInterval) {
        this.inputFile = inputFile;
        this.inactiveDays = inactiveDays;
        this.progressBar = progressBar;
        this.statusLabel = statusLabel;
        this.objectMapper = new ObjectMapper();
        this.batchSize = Math.max(1, batchSize); // 确保至少为1
        this.batchInterval = Math.max(0.1, batchInterval); // 确保至少为0.1秒
    }
    
    @Override
    protected List<UserData> doInBackground() throws Exception {
        // 读取输入文件
        publish(0);
        statusLabel.setText("正在读取用户数据...");
        
        List<Map<String, Object>> userData = objectMapper.readValue(
                inputFile, new TypeReference<List<Map<String, Object>>>() {});
        
        // 提取用户ID和名称
        List<Long> mids = new ArrayList<>();
        Map<Long, String> midToName = new HashMap<>();
        Map<Long, List<String>> midToTags = new HashMap<>();
        
        for (Map<String, Object> user : userData) {
            long mid = ((Number) user.get("mid")).longValue();
            String name = (String) user.get("name");
            List<String> tags = objectMapper.convertValue(user.get("tag"), new TypeReference<List<String>>() {});
            
            mids.add(mid);
            midToName.put(mid, name);
            midToTags.put(mid, tags);
        }
        
        // 设置进度条
        progressBar.setMaximum(mids.size());
        progressBar.setValue(0);
        
        // 获取用户视频数据
        statusLabel.setText("正在从B站API获取用户视频数据...");
        List<Map<String, Object>> userVidList = new ArrayList<>();
        
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            int count = 0;
            int batchCount = 0;
            List<Long> currentBatch = new ArrayList<>();
            
            for (int i = 0; i < mids.size(); i++) {
                currentBatch.add(mids.get(i));
                
                // 当达到批量大小或处理到最后一个用户时，处理当前批次
                if (currentBatch.size() >= batchSize || i == mids.size() - 1) {
                    batchCount++;
                    statusLabel.setText(String.format("正在处理第 %d 批数据 (共 %d 个用户)", batchCount, currentBatch.size()));
                    
                    // 并行处理当前批次的用户
                    for (Long mid : currentBatch) {
                        try {
                            String url = String.format("https://api.bilibili.com/x/series/recArchivesByKeywords?mid=%d&keywords=&orderby=senddate", mid);
                            HttpGet request = new HttpGet(url);
                            
                            // 设置请求头
                            for (Map.Entry<String, String> entry : HEADERS.entrySet()) {
                                request.setHeader(entry.getKey(), entry.getValue());
                            }
                            
                            // 设置超时
                            RequestConfig requestConfig = RequestConfig.custom()
                                    .setConnectTimeout(5000)
                                    .setSocketTimeout(5000)
                                    .build();
                            request.setConfig(requestConfig);
                            
                            // 执行请求
                            try (CloseableHttpResponse response = httpClient.execute(request)) {
                                HttpEntity entity = response.getEntity();
                                if (entity != null) {
                                    String result = EntityUtils.toString(entity);
                                    JsonNode userVid = objectMapper.readTree(result);
                                    
                                    Map<String, Object> entry = new HashMap<>();
                                    entry.put("mid", mid);
                                    entry.put("user_vid", userVid);
                                    userVidList.add(entry);
                                }
                            }
                            
                            // 更新进度
                            count++;
                            publish(count);
                            statusLabel.setText(String.format("正在获取用户数据 (%d/%d): %s", count, mids.size(), midToName.get(mid)));
                            
                        } catch (Exception e) {
                            System.err.println("获取用户 " + mid + " 的数据时出错: " + e.getMessage());
                            // 继续处理下一个用户
                            count++;
                            publish(count);
                        }
                    }
                    
                    // 清空当前批次，准备下一批
                    currentBatch.clear();
                    
                    // 批次间休眠，避免请求过快
                    if (i < mids.size() - 1) { // 如果不是最后一批
                        statusLabel.setText(String.format("等待 %.1f 秒后处理下一批...", batchInterval));
                        // 将秒转换为毫秒，支持小数
                        Thread.sleep((long)(batchInterval * 1000));
                    }
                }
            }
        }
        
        // 保存原始数据到缓存文件
        statusLabel.setText("正在保存用户数据到缓存...");
        // 将缓存文件保存在与输入文件相同的目录下
        File cacheFile = new File(inputFile.getParentFile(), "user_data_cache.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile, userVidList);
        
        // 处理数据
        statusLabel.setText("正在处理数据...");
        return processUserData(userVidList, midToName, midToTags);
    }
    
    private List<UserData> processUserData(List<Map<String, Object>> userVidList, 
                                          Map<Long, String> midToName, 
                                          Map<Long, List<String>> midToTags) throws Exception {
        List<UserData> allUsers = new ArrayList<>();
        List<UserData> usersWithNoVideos = new ArrayList<>();
        
        for (Map<String, Object> entry : userVidList) {
            long mid = ((Number) entry.get("mid")).longValue();
            JsonNode userVid = (JsonNode) entry.get("user_vid");
            
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
        // 筛选工作将由MainApp中的updateInactiveDaysFilter方法完成，与CacheLoadingTask保持一致
        statusLabel.setText("处理完成，共 " + allUsersCombined.size() + " 个用户");
        return allUsersCombined;
    }
    
    @Override
    protected void process(List<Integer> chunks) {
        // 更新进度条
        if (!chunks.isEmpty()) {
            int progress = chunks.get(chunks.size() - 1);
            progressBar.setValue(progress);
            progressBar.setString(progress + " / " + progressBar.getMaximum());
        }
    }
}