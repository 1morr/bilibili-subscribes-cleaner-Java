package org.example;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户数据模型类
 * 存储B站用户的基本信息和视频更新情况
 */
public class UserData {
    private long uid; // 用户ID - 使用long类型避免大UID溢出
    private String username; // 用户名
    private List<String> tags; // 用户分组标签
    private String lastVideoTitle; // 最后一个视频标题
    private long lastUpdateTimestamp; // 最后更新时间戳
    private String bvid; // 视频BV号
    private int inactiveDays; // 不活跃天数
    private boolean hasVideos; // 是否有视频

    // 构造函数 - 有视频的用户
    public UserData(long uid, String username, List<String> tags, String lastVideoTitle, 
                   long lastUpdateTimestamp, String bvid) {
        this.uid = uid;
        this.username = username;
        this.tags = tags != null ? tags : new ArrayList<>();
        this.lastVideoTitle = lastVideoTitle;
        this.lastUpdateTimestamp = lastUpdateTimestamp;
        this.bvid = bvid;
        this.hasVideos = true;
        calculateInactiveDays();
    }

    // 构造函数 - 没有视频的用户
    public UserData(long uid, String username, List<String> tags) {
        this.uid = uid;
        this.username = username;
        this.tags = tags != null ? tags : new ArrayList<>();
        this.lastVideoTitle = "无视频";
        this.lastUpdateTimestamp = 0;
        this.bvid = "";
        this.hasVideos = false;
        this.inactiveDays = Integer.MAX_VALUE; // 设置为最大值，表示无限不活跃
    }

    // 计算不活跃天数
    private void calculateInactiveDays() {
        if (lastUpdateTimestamp == 0) {
            inactiveDays = Integer.MAX_VALUE;
            return;
        }

        LocalDateTime lastUpdate = LocalDateTime.ofInstant(
                Instant.ofEpochSecond(lastUpdateTimestamp),
                ZoneId.systemDefault());
        LocalDateTime now = LocalDateTime.now();
        
        inactiveDays = (int) ChronoUnit.DAYS.between(lastUpdate, now);
    }

    // 检查用户是否不活跃（基于指定的天数阈值）
    public boolean isInactive(int thresholdDays) {
        return inactiveDays >= thresholdDays;
    }

    // 检查用户是否有特定标签
    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    // 检查用户是否有任何标签
    public boolean hasTags() {
        return tags != null && !tags.isEmpty();
    }

    // Getters and Setters
    public long getUid() {
        return uid;
    }

    public String getUsername() {
        return username;
    }

    public List<String> getTags() {
        return tags;
    }

    public String getLastVideoTitle() {
        return lastVideoTitle;
    }

    public long getLastUpdateTimestamp() {
        return lastUpdateTimestamp;
    }

    public String getBvid() {
        return bvid;
    }

    public int getInactiveDays() {
        return inactiveDays;
    }

    public boolean hasVideos() {
        return hasVideos;
    }

    public String getVideoUrl() {
        if (bvid != null && !bvid.isEmpty()) {
            return "https://www.bilibili.com/video/" + bvid + "/";
        }
        return "";
    }
    
    public String getSpaceUrl() {
        return "https://space.bilibili.com/" + uid;
    }

    @Override
    public String toString() {
        return "UserData{" +
                "uid=" + uid +
                ", username='" + username + '\'' +
                ", tags=" + tags +
                ", lastVideoTitle='" + lastVideoTitle + '\'' +
                ", inactiveDays=" + inactiveDays +
                "}";
    }
}