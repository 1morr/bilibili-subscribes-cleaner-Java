package org.example;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.SwingWorker;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据处理任务类
 * 在后台线程中处理B站API请求和数据分析
 */
public class DataProcessingTask extends SwingWorker<ProcessingResult, DataProcessingTask.ProgressUpdate> {
    private final File inputFile;
    private final JProgressBar progressBar;
    private final JLabel statusLabel;
    private final ObjectMapper objectMapper;
    private final int batchSize; // 批量处理数量
    private final double batchInterval; // 批次间隔(秒)，支持小数
    private final HttpClient httpClient;

    // 单个请求最多重试次数（不含首次尝试），采用指数退避
    private static final int MAX_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 1000;
    // B站风控/频率限制返回码：命中时必须整轮停止，不能继续对同一IP发请求
    private static final int[] RISK_CONTROL_CODES = {-352, -412, -799};

    // B站API请求头
    private static final Map<String, String> HEADERS = new HashMap<>();
    static {
        HEADERS.put("accept", "application/json, text/plain, */*");
        HEADERS.put("accept-language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6");
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

    public DataProcessingTask(File inputFile, JProgressBar progressBar, JLabel statusLabel, int batchSize, double batchInterval) {
        this.inputFile = inputFile;
        this.progressBar = progressBar;
        this.statusLabel = statusLabel;
        this.objectMapper = new ObjectMapper();
        this.batchSize = Math.max(1, batchSize); // 确保至少为1
        // 强制1秒的下限，避免UI侧允许的极端值把请求速率推到每秒数次而触发风控（修复 C2）
        this.batchInterval = Math.max(1.0, batchInterval);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * 命中B站风控/频率限制时抛出，用于中止整轮抓取（修复 C2）。
     */
    private static final class RiskControlException extends Exception {
        final int code;

        RiskControlException(long mid, int code) {
            super("B站风控: mid=" + mid + ", code=" + code);
            this.code = code;
        }
    }

    /**
     * 后台线程向EDT汇报的一条更新：进度值、进度条最大值、状态文案，三者均可为null表示"本条不更新该项"。
     * 统一走SwingWorker的publish/process通道，避免在后台线程直接操作Swing组件（修复 C3）。
     */
    static final class ProgressUpdate {
        final Integer max;
        final Integer progress;
        final String status;

        ProgressUpdate(Integer max, Integer progress, String status) {
            this.max = max;
            this.progress = progress;
            this.status = status;
        }

        static ProgressUpdate status(String status) {
            return new ProgressUpdate(null, null, status);
        }

        static ProgressUpdate progress(int progress, String status) {
            return new ProgressUpdate(null, progress, status);
        }

        static ProgressUpdate init(int max) {
            return new ProgressUpdate(max, 0, null);
        }
    }

    @Override
    protected ProcessingResult doInBackground() throws Exception {
        publish(ProgressUpdate.status(Messages.get("task.process.readingFollowList")));

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

        publish(ProgressUpdate.init(mids.size()));

        // 获取用户视频数据
        publish(ProgressUpdate.status(Messages.get("task.process.fetchingVideos")));
        List<Map<String, Object>> userVidList = new ArrayList<>();

        boolean stoppedByRiskControl = false;
        String stopReason = null;
        int count = 0;

        fetchLoop:
        for (int i = 0; i < mids.size(); i++) {
            long mid = mids.get(i);
            JsonNode userVid;
            try {
                userVid = fetchUserVid(mid);
            } catch (RiskControlException rce) {
                stoppedByRiskControl = true;
                // 日志内容保持不变、不做提取（按约定日志维持原样），与下面展示给用户的stopReason分开维护
                String logMessage = String.format(
                        "检测到B站风控/频率限制 (code=%d)，已在处理到第 %d/%d 个用户时停止本轮请求，避免继续触发限制",
                        rce.code, i + 1, mids.size());
                System.err.println(logMessage);
                // stopReason会展示在UI对话框里，必须按当前界面语言生成（修复 i18n：与日志文案彻底解耦）
                stopReason = Messages.format("task.process.riskControlReason",
                        String.valueOf(rce.code), String.valueOf(i + 1), String.valueOf(mids.size()));
                break fetchLoop;
            }

            Map<String, Object> entry = new HashMap<>();
            entry.put("mid", mid);
            entry.put("user_vid", userVid); // 可能为null，表示该用户彻底获取失败
            userVidList.add(entry);

            count++;
            publish(ProgressUpdate.progress(count,
                    Messages.format("task.process.fetchingAccount",
                            String.valueOf(count), String.valueOf(mids.size()), midToName.get(mid))));

            boolean isLast = (i == mids.size() - 1);
            if (!isLast && (count % batchSize == 0)) {
                String intervalText = String.format(java.util.Locale.ROOT, "%.1f", batchInterval);
                publish(ProgressUpdate.status(Messages.format("task.process.waiting", intervalText)));
                Thread.sleep((long) (batchInterval * 1000));
            }
        }

        // 保存原始数据到缓存文件（Jackson写入File/OutputStream时默认使用UTF-8，无乱码问题）
        publish(ProgressUpdate.status(Messages.get("task.process.savingCache")));
        // 缓存文件保存在与输入文件相同的目录下
        File cacheFile = new File(inputFile.getParentFile(), "user_data_cache.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile, userVidList);

        // 处理数据
        publish(ProgressUpdate.status(Messages.get("task.process.processingData")));
        List<UserData> allUsersCombined = processUserData(userVidList, midToName, midToTags);

        int unknownCount = 0;
        for (UserData u : allUsersCombined) {
            if (u.isUnknown()) {
                unknownCount++;
            }
        }
        String summary = unknownCount > 0
                ? Messages.format("task.process.summaryDoneWithUnknown",
                        String.valueOf(allUsersCombined.size()), String.valueOf(unknownCount))
                : Messages.format("task.process.summaryDone", String.valueOf(allUsersCombined.size()));
        publish(ProgressUpdate.status(summary));

        return new ProcessingResult(allUsersCombined, stoppedByRiskControl, stopReason, cacheFile);
    }

    /**
     * 拉取单个用户的原始API响应，内置有限次数的指数退避重试。
     * 返回null表示该用户彻底获取失败（网络异常、超时或重试耗尽），交由上层判定为UNKNOWN（修复 C1）。
     * 一旦响应体中出现风控返回码，立即抛出RiskControlException，不重试，直接中止整轮抓取（修复 C2）。
     */
    private JsonNode fetchUserVid(long mid) throws RiskControlException {
        String url = String.format(
                "https://api.bilibili.com/x/series/recArchivesByKeywords?mid=%d&keywords=&orderby=senddate", mid);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET();
        HEADERS.forEach(builder::header);
        HttpRequest request = builder.build();

        Exception lastError = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                if (response.statusCode() != 200) {
                    lastError = new java.io.IOException("HTTP " + response.statusCode());
                } else {
                    JsonNode userVid = objectMapper.readTree(response.body());
                    int code = userVid.path("code").asInt(Integer.MIN_VALUE);
                    if (isRiskControlCode(code)) {
                        throw new RiskControlException(mid, code);
                    }
                    return userVid;
                }
            } catch (RiskControlException rce) {
                throw rce; // 风控：不重试，直接向上冒泡以中止整轮
            } catch (Exception e) {
                lastError = e;
            }

            if (attempt < MAX_RETRIES) {
                long backoffMs = BASE_BACKOFF_MS * (1L << attempt);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }

        System.err.println("获取用户 " + mid + " 的数据失败，已重试 " + MAX_RETRIES + " 次: "
                + (lastError != null ? lastError.getMessage() : "未知错误"));
        return null;
    }

    private static boolean isRiskControlCode(int code) {
        for (int riskCode : RISK_CONTROL_CODES) {
            if (riskCode == code) {
                return true;
            }
        }
        return false;
    }

    private List<UserData> processUserData(List<Map<String, Object>> userVidList,
                                            Map<Long, String> midToName,
                                            Map<Long, List<String>> midToTags) {
        List<UserData> allUsers = new ArrayList<>();

        for (Map<String, Object> entry : userVidList) {
            long mid = ((Number) entry.get("mid")).longValue();
            JsonNode userVid = (JsonNode) entry.get("user_vid");
            allUsers.add(UserData.fromApiResponse(mid, midToName.get(mid), midToTags.get(mid), userVid));
        }

        return allUsers;
    }

    @Override
    protected void process(List<ProgressUpdate> chunks) {
        // process() 运行在EDT上，这里是唯一允许触碰statusLabel/progressBar的地方（修复 C3）
        for (ProgressUpdate update : chunks) {
            if (update.max != null) {
                progressBar.setMaximum(update.max);
            }
            if (update.progress != null) {
                progressBar.setValue(update.progress);
                progressBar.setString(update.progress + " / " + progressBar.getMaximum());
            }
            if (update.status != null) {
                statusLabel.setText(update.status);
            }
        }
    }
}
