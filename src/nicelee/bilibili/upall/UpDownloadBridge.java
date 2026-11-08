package nicelee.bilibili.upall;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import nicelee.bilibili.INeedAV;
import nicelee.bilibili.model.ClipInfo;
import nicelee.bilibili.model.VideoInfo;
import nicelee.bilibili.util.Logger;
import nicelee.ui.Global;
import nicelee.ui.thread.DownloadRunnable;

/**
 * 把勾选的投稿投递进现有下载队列。
 *
 * 完全复用原有链路：INeedAV.getVideoDetail() 取详情（清晰度/命名/格式全部沿用现有配置），
 * 再 new DownloadRunnable(...).run()。
 *
 * 注意：DownloadRunnable.run() 内部要 new DownloadInfoPanel 并挂到
 * Global.downloadTab 的容器上，是 Swing 组件操作，**必须在 EDT 里执行**；
 * 直接丢进线程池会出现「任务不显示、也不开始下载」的情况。
 */
public class UpDownloadBridge {

    public interface Listener {
        void onProgress(int done, int total, String msg);
    }

    /**
     * @param items    勾选的投稿
     * @param qn       目标清晰度；<=0 表示沿用配置文件中的默认
     * @param allPages true 表示分P视频全部投递，false 只投第 1P
     * @return 实际投递的任务数
     */
    public static int enqueue(List<UpVideoItem> items, int qn, boolean allPages, Listener listener) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        int targetQn = resolveQn(qn);
        int taskCount = 0;
        INeedAV iNeedAV = new INeedAV();
        log("开始投递：items=" + items.size() + " qn=" + targetQn + " allPages=" + allPages);
        try {
            ShortfallChecker.recordPush(items, targetQn);
        } catch (Throwable ignore) {
        }
        for (int i = 0; i < items.size(); i++) {
            UpVideoItem item = items.get(i);
            if (listener != null) {
                listener.onProgress(i, items.size(), "正在取详情: " + item.getBvid());
            }
            try {
                VideoInfo info = iNeedAV.getVideoDetail(item.getBvid(), Global.downloadFormat, false);
                if (info == null || info.getClips() == null || info.getClips().isEmpty()) {
                    log("取详情失败，跳过 " + item.getBvid());
                    continue;
                }
                List<ClipInfo> clips = new ArrayList<>(info.getClips().values());
                int limit = allPages ? clips.size() : 1;
                for (int p = 0; p < limit; p++) {
                    final ClipInfo clip = clips.get(p);
                    final VideoInfo infoF = info;
                    final int qnF = targetQn;
                    // 关键：登记下载面板必须在 EDT 上
                    SwingUtilities.invokeLater(new Runnable() {
                        public void run() {
                            try {
                                new DownloadRunnable(infoF, clip, qnF).run();
                            } catch (Throwable t) {
                                log("下载任务登记失败 qn=" + qnF + " : " + t);
                            }
                        }
                    });
                    taskCount++;
                }
            } catch (Throwable t) {
                log("投递失败 " + item.getBvid() + " : " + t);
            }
        }
        log("投递结束：任务数=" + taskCount);
        if (listener != null) {
            listener.onProgress(items.size(), items.size(), "已投递 " + taskCount + " 个任务");
        }
        return taskCount;
    }

    /** qn<=0 时读配置，读不到用 80(1080P) */
    private static int resolveQn(int qn) {
        if (qn > 0) {
            return qn;
        }
        try {
            String v = Global.tab_qn;
            if (v != null && !v.trim().isEmpty()) {
                return Integer.parseInt(v.trim());
            }
        } catch (Throwable ignore) {
            // 忽略，走默认
        }
        return 80;
    }

    /** 投递诊断日志，方便排查"没推到下载页" */
    static void log(String msg) {
        Logger.println("[upall] " + msg);
        try {
            File f = new File(LedgerMenuHook.toolDir(), ".push.log");
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
                    + "  " + msg + "\r\n";
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
                w.write(line);
            }
        } catch (Throwable ignore) {
        }
    }
}
