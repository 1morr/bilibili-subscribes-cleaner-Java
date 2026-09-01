package org.example;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 缓存加载任务类
 * 从缓存文件中加载用户数据，避免重复请求B站API
 */
public class CacheLoadingTask extends SwingWorker<ProcessingResult, String> {
    private final File cacheFile;
    private final JLabel statusLabel;
    private final ObjectMapper objectMapper;

    public CacheLoadingTask(File cacheFile, JLabel statusLabel) {
        this.cacheFile = cacheFile;
        this.statusLabel = statusLabel;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    protected ProcessingResult doInBackground() throws Exception {
        publish(Messages.get("task.cache.loading"));

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
                // 如果仍然不存在，让用户选择文件 —— JFileChooser必须在EDT上打开（修复 C3）
                exportUidsFile = chooseExportUidsFileOnEdt();
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
        List<UserData> allUsersCombined = processUserData(userVidList, midToName, midToTags);

        int unknownCount = 0;
        for (UserData u : allUsersCombined) {
            if (u.isUnknown()) {
                unknownCount++;
            }
        }
        String summary = unknownCount > 0
                ? Messages.format("task.cache.doneWithUnknown",
                        String.valueOf(allUsersCombined.size()), String.valueOf(unknownCount))
                : Messages.format("task.cache.done", String.valueOf(allUsersCombined.size()));
        publish(summary);

        return new ProcessingResult(allUsersCombined, false, null, null);
    }

    /**
     * 在EDT上弹出文件选择对话框并阻塞等待结果，供后台线程（doInBackground）安全调用（修复 C3）。
     */
    private File chooseExportUidsFileOnEdt() throws Exception {
        File[] chosen = new File[1];
        int[] dialogResult = new int[1];

        Runnable openDialog = () -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setDialogTitle(Messages.get("filechooser.dialogTitle.selectExportUids"));
            fileChooser.setFileFilter(new FileNameExtensionFilter(Messages.get("filechooser.json"), "json"));
            dialogResult[0] = fileChooser.showOpenDialog(null);
            if (dialogResult[0] == JFileChooser.APPROVE_OPTION) {
                chosen[0] = fileChooser.getSelectedFile();
            }
        };

        if (SwingUtilities.isEventDispatchThread()) {
            openDialog.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(openDialog);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Exception(Messages.get("task.cache.interruptedChooser"), e);
            } catch (InvocationTargetException e) {
                throw new Exception(Messages.format("task.cache.chooserFailed", String.valueOf(e.getCause())), e);
            }
        }

        if (dialogResult[0] != JFileChooser.APPROVE_OPTION || chosen[0] == null) {
            throw new Exception(Messages.get("task.cache.noExportUidsSelected"));
        }
        return chosen[0];
    }

    private List<UserData> processUserData(List<Map<String, Object>> userVidList,
                                            Map<Long, String> midToName,
                                            Map<Long, List<String>> midToTags) {
        List<UserData> allUsers = new ArrayList<>();

        for (Map<String, Object> entry : userVidList) {
            long mid = ((Number) entry.get("mid")).longValue();
            JsonNode userVid = objectMapper.convertValue(entry.get("user_vid"), JsonNode.class);

            // 缓存中的mid可能已不在最新的export_uids.json里（用户已取关等）；传null让UserData.getUsername()
            // 按当前界面语言即时生成占位文案，而不是在这里固定成某一种语言（修复 C8 + i18n）
            String name = midToName.get(mid);
            List<String> tags = midToTags.getOrDefault(mid, Collections.emptyList());

            allUsers.add(UserData.fromApiResponse(mid, name, tags, userVid));
        }

        return allUsers;
    }

    @Override
    protected void process(List<String> chunks) {
        // process() 运行在EDT上，这里是唯一允许触碰statusLabel的地方（修复 C3）
        if (!chunks.isEmpty()) {
            statusLabel.setText(chunks.get(chunks.size() - 1));
        }
    }
}
