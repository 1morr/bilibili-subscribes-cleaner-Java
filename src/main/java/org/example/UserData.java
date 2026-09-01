package org.example;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户数据模型类
 * 存储B站用户的基本信息和视频更新情况
 */
public class UserData {

    /**
     * 对B站 recArchivesByKeywords 接口响应的分类结果：
     * <ul>
     *     <li>{@link #HAS_VIDEOS} —— 请求成功（code == 0）且至少有一个视频；</li>
     *     <li>{@link #NO_VIDEOS}  —— 请求成功（code == 0）但确实没有视频；</li>
     *     <li>{@link #UNKNOWN}    —— 请求失败、HTTP状态非200，或API返回了非0的code
     *         （例如风控 -352 / -412 / -799）。此状态下账号是否活跃根本无法判断，
     *         {@link #isInactive(int)} 永远返回 false，绝不能被当作"不活跃"导出去取关。</li>
     * </ul>
     */
    public enum Status {
        HAS_VIDEOS,
        NO_VIDEOS,
        UNKNOWN
    }

    // 计算"不活跃天数"固定使用B站服务器所在时区，避免使用系统默认时区在境外出现最多一天的偏差
    private static final ZoneId BILIBILI_ZONE = ZoneId.of("Asia/Shanghai");

    // 这两个不再是固定文案，而是messages.properties里的key：getLastVideoTitle()在每次调用时
    // 都用当前语言重新查询，这样切换界面语言后已加载的数据也能立刻显示对应语言，不需要重新处理/加载。
    static final String NO_VIDEOS_KEY = "userdata.status.noVideos";
    static final String UNKNOWN_KEY = "userdata.status.unknown";
    private static final String UNKNOWN_ACCOUNT_KEY = "userdata.unknownAccount";

    private final long uid; // 用户ID - 使用long类型避免大UID溢出
    private final String username; // 用户名；为null表示该uid未出现在当前的export_uids.json中（修复 C8），
                                    // 此时getUsername()按当前语言即时生成占位文案
    private final List<String> tags; // 用户分组标签
    private final String lastVideoTitle; // 最后一个视频标题
    private final long lastUpdateTimestamp; // 最后更新时间戳（epoch秒），不适用时为0
    private final String bvid; // 视频BV号
    private final Status status;

    // 构造函数 - 有视频的用户
    public UserData(long uid, String username, List<String> tags, String lastVideoTitle,
                     long lastUpdateTimestamp, String bvid) {
        this.uid = uid;
        this.username = username;
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
        this.lastVideoTitle = lastVideoTitle;
        this.lastUpdateTimestamp = lastUpdateTimestamp;
        this.bvid = bvid != null ? bvid : "";
        this.status = Status.HAS_VIDEOS;
    }

    // 构造函数 - 没有视频 / 未知状态的用户（不得用HAS_VIDEOS调用此构造函数）
    public UserData(long uid, String username, List<String> tags, Status status) {
        if (status == Status.HAS_VIDEOS) {
            throw new IllegalArgumentException("HAS_VIDEOS 状态请使用携带视频信息的构造函数");
        }
        this.uid = uid;
        this.username = username;
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
        this.lastVideoTitle = null; // 按当前语言由getLastVideoTitle()即时生成，不在构造时固定语言
        this.lastUpdateTimestamp = 0;
        this.bvid = "";
        this.status = status;
    }

    /**
     * 根据B站 recArchivesByKeywords 接口的原始JSON响应判定用户状态（修复 C1）。
     * <p>
     * {@code userVid} 为 {@code null}（或Jackson的 NullNode）表示请求本身失败（网络异常/超时/HTTP非200）。
     * 只有当 {@code code == 0} 且 {@code data.archives} 非空时才判定为 {@link Status#HAS_VIDEOS}；
     * {@code code == 0} 但 {@code data.archives} 为空判定为 {@link Status#NO_VIDEOS}；
     * 其余一切情况（code缺失、code非0、data缺失/为null）一律判定为 {@link Status#UNKNOWN}，
     * 绝不会被误判为"无视频"从而被取关。
     * <p>
     * 包内可见以便单元测试直接验证该分类逻辑。
     */
    static UserData fromApiResponse(long mid, String username, List<String> tags, JsonNode userVid) {
        if (userVid == null || userVid.isNull() || userVid.isMissingNode()) {
            return new UserData(mid, username, tags, Status.UNKNOWN);
        }

        // 必须显式确认 code == 0 才能信任payload；code缺失时asInt默认值取一个非0值，同样落入UNKNOWN
        int code = userVid.path("code").asInt(Integer.MIN_VALUE);
        if (code != 0) {
            return new UserData(mid, username, tags, Status.UNKNOWN);
        }

        JsonNode data = userVid.path("data");
        if (data.isMissingNode() || data.isNull()) {
            return new UserData(mid, username, tags, Status.UNKNOWN);
        }

        JsonNode archives = data.path("archives");
        if (archives.isArray() && archives.size() > 0) {
            JsonNode firstVideo = archives.get(0);
            String title = firstVideo.path("title").asText("");
            long pubdate = firstVideo.path("pubdate").asLong(0);
            String bvid = firstVideo.path("bvid").asText("");
            return new UserData(mid, username, tags, title, pubdate, bvid);
        }

        return new UserData(mid, username, tags, Status.NO_VIDEOS);
    }

    // 计算不活跃天数：每次调用都基于当前时间重新计算，不缓存陈旧值（修复 C5）
    private int computeInactiveDays() {
        if (status == Status.NO_VIDEOS) {
            return Integer.MAX_VALUE; // 确认无视频，视为无限不活跃
        }
        if (status == Status.UNKNOWN || lastUpdateTimestamp == 0) {
            return -1; // 未知/不适用
        }

        ZonedDateTime lastUpdate = Instant.ofEpochSecond(lastUpdateTimestamp).atZone(BILIBILI_ZONE);
        ZonedDateTime now = ZonedDateTime.now(BILIBILI_ZONE);

        return (int) ChronoUnit.DAYS.between(lastUpdate, now);
    }

    /**
     * 判断用户是否"不活跃"：距最后一次发布视频已超过 thresholdDays 天，采用严格大于（与原Python版本一致，修复 C5）。
     * UNKNOWN 状态永远返回 false —— 未能确认的账号绝不能被当作不活跃处理，更不能被导出去取关（修复 C1）。
     */
    public boolean isInactive(int thresholdDays) {
        if (status == Status.UNKNOWN) {
            return false;
        }
        return computeInactiveDays() > thresholdDays;
    }

    // 检查用户是否有特定标签
    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    // 检查用户是否有任何标签
    public boolean hasTags() {
        return tags != null && !tags.isEmpty();
    }

    // Getters
    public long getUid() {
        return uid;
    }

    public String getUsername() {
        return username != null ? username : Messages.format(UNKNOWN_ACCOUNT_KEY, uid);
    }

    public List<String> getTags() {
        return tags;
    }

    public String getLastVideoTitle() {
        if (status == Status.NO_VIDEOS) {
            return Messages.get(NO_VIDEOS_KEY);
        }
        if (status == Status.UNKNOWN) {
            return Messages.get(UNKNOWN_KEY);
        }
        return lastVideoTitle;
    }

    public long getLastUpdateTimestamp() {
        return lastUpdateTimestamp;
    }

    public String getBvid() {
        return bvid;
    }

    public int getInactiveDays() {
        return computeInactiveDays();
    }

    public Status getStatus() {
        return status;
    }

    public boolean hasVideos() {
        return status == Status.HAS_VIDEOS;
    }

    public boolean isUnknown() {
        return status == Status.UNKNOWN;
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
                ", status=" + status +
                ", inactiveDays=" + computeInactiveDays() +
                "}";
    }
}
