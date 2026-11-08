package nicelee.bilibili.upall;

import java.io.File;

/**
 * 下载完成监控：盯着 download 目录的文件数。
 * 一旦出现过增长、随后 30 秒不再变化，就认为这一批已经下完，
 * 自动跑一次「推送清单 vs 落盘」比对，把缺口写进补采记录。
 *
 * 只做后台记账，不碰下载链路；出错只打日志，不影响主程序。
 */
public final class DownloadWatch {

    private static final long TICK_MS = 5000L;
    /** 文件数多久不变就认为这批下完了 */
    private static final long QUIET_MS = 30000L;
    /** 两次自动比对之间至少隔多久，防止反复触发 */
    private static final long COOLDOWN_MS = 120000L;

    private static volatile boolean started;

    private DownloadWatch() {
    }

    public static void start() {
        if (started) {
            return;
        }
        started = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "download-watch");
        t.setDaemon(true);
        t.start();
    }

    private static void loop() {
        File dir = ShortfallChecker.downloadDir();
        long lastCount = countFiles(dir);
        long lastChangeAt = 0L;
        long lastCheckAt = 0L;
        boolean grew = false;

        while (true) {
            try {
                Thread.sleep(TICK_MS);
            } catch (InterruptedException e) {
                return;
            }
            try {
                dir = ShortfallChecker.downloadDir();
                long now = System.currentTimeMillis();
                long count = countFiles(dir);
                if (count != lastCount) {
                    if (count > lastCount) {
                        grew = true;
                    }
                    lastCount = count;
                    lastChangeAt = now;
                    continue;
                }
                if (!grew) {
                    continue;
                }
                if (now - lastChangeAt < QUIET_MS) {
                    continue;
                }
                if (now - lastCheckAt < COOLDOWN_MS) {
                    continue;
                }
                grew = false;
                lastCheckAt = now;
                java.util.List<String[]> rows = ShortfallChecker.runCheck(false);
                int todo = 0;
                for (String[] r : rows) {
                    if (r != null && ShortfallChecker.STATUS_TODO.equals(r[7])) {
                        todo++;
                    }
                }
                nicelee.bilibili.util.Logger.println(
                        "[shortfall] 下载批次结束，自动比对完成：补采待补 " + todo + " 条");
            } catch (Throwable ignore) {
                // 记账线程不能把主程序带崩
            }
        }
    }

    private static long countFiles(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return 0L;
        }
        File[] fs = dir.listFiles();
        return fs == null ? 0L : fs.length;
    }
}
