package org.example;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * {@link DataProcessingTask} / {@link CacheLoadingTask} 的结果封装。
 * <p>
 * 除了用户列表之外，还带上了：
 * <ul>
 *     <li>{@code unknownCount} —— 状态为 {@link UserData.Status#UNKNOWN} 的账号数量，
 *         用于在UI上告知用户"有N个账号因请求失败/风控而未能确认活跃度"（修复 C1）；</li>
 *     <li>{@code stoppedByRiskControl} / {@code stopReason} —— 本轮抓取是否因命中B站风控
 *         （-352 / -412 / -799）而被提前中止（修复 C2）；</li>
 *     <li>{@code cacheFile} —— 本轮抓取实际写入的缓存文件路径（仅 DataProcessingTask 会设置），
 *         用于在UI上展示真实的绝对路径（修复 C6）。</li>
 * </ul>
 */
final class ProcessingResult {
    private final List<UserData> users;
    private final int unknownCount;
    private final boolean stoppedByRiskControl;
    private final String stopReason;
    private final File cacheFile;

    ProcessingResult(List<UserData> users, boolean stoppedByRiskControl, String stopReason, File cacheFile) {
        this.users = Collections.unmodifiableList(users);
        int unknown = 0;
        for (UserData user : users) {
            if (user.isUnknown()) {
                unknown++;
            }
        }
        this.unknownCount = unknown;
        this.stoppedByRiskControl = stoppedByRiskControl;
        this.stopReason = stopReason;
        this.cacheFile = cacheFile;
    }

    List<UserData> getUsers() {
        return users;
    }

    int getUnknownCount() {
        return unknownCount;
    }

    boolean isStoppedByRiskControl() {
        return stoppedByRiskControl;
    }

    String getStopReason() {
        return stopReason;
    }

    File getCacheFile() {
        return cacheFile;
    }
}
