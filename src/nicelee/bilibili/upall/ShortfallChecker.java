package nicelee.bilibili.upall;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 补采比对：把「推送清单」和磁盘上真实存在的文件对一遍，缺口写进补采记录。
 *
 * 数据文件（都在 记录工具\ 下）：
 *   最近推送.csv   每次「加入下载队列」时覆盖写入，记录这一次推了哪些视频
 *   补采记录.csv   缺口台账，状态为 待补 / 已补 / 忽略，二次推送就从这里取
 */
public final class ShortfallChecker {

    public static final String STATUS_TODO = "待补";
    public static final String STATUS_DONE = "已补";
    public static final String STATUS_IGNORE = "忽略";

    static final String[] HEAD = {
            "BV号", "标题", "分P数", "清单来源", "清单页码", "首次发现", "最近比对", "状态", "尝试次数"
    };

    private static final Pattern BV_PATTERN = Pattern.compile("(BV[0-9A-Za-z]{10})");

    /** 比对完成后回调界面刷新 */
    private static final List<Runnable> LISTENERS = new ArrayList<>();

    private ShortfallChecker() {
    }

    public static synchronized void addListener(Runnable r) {
        if (r != null && !LISTENERS.contains(r)) {
            LISTENERS.add(r);
        }
    }

    private static void fireChanged() {
        for (Runnable r : new ArrayList<>(LISTENERS)) {
            try {
                r.run();
            } catch (Throwable ignore) {
            }
        }
    }

    /* ================= 路径 ================= */

    static File toolDir() {
        return LedgerMenuHook.toolDir();
    }

    public static File makeupFile() {
        return new File(toolDir(), "补采记录.csv");
    }

    static File pushFile() {
        return new File(toolDir(), "最近推送.csv");
    }

    static File downloadDir() {
        File d = new File(LedgerMenuHook.appDir(), "download");
        if (!d.isDirectory()) {
            File d2 = new File(LedgerMenuHook.appDir(), "downloads");
            if (d2.isDirectory()) {
                return d2;
            }
        }
        return d;
    }

    static File upallDir() {
        return new File(LedgerMenuHook.appDir(), "config/upall");
    }

    /* ================= 磁盘现状 ================= */

    /** download 目录 + repo.config 里出现过的所有 BV */
    static Set<String> localBvs() {
        Set<String> set = new HashSet<>();
        File d = downloadDir();
        if (d.isDirectory()) {
            File[] fs = d.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.isFile()) {
                        String bv = extractBv(f.getName());
                        if (bv != null) {
                            set.add(bv);
                        }
                    }
                }
            }
        }
        File repo = new File(LedgerMenuHook.appDir(), "config/repo.config");
        if (repo.isFile()) {
            try {
                for (String line : Files.readAllLines(repo.toPath(), StandardCharsets.UTF_8)) {
                    String bv = extractBv(line);
                    if (bv != null) {
                        set.add(bv);
                    }
                }
            } catch (Throwable ignore) {
            }
        }
        return set;
    }

    private static String extractBv(String s) {
        if (s == null) {
            return null;
        }
        Matcher m = BV_PATTERN.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /* ================= 记录推送清单 ================= */

    /** 每次投递前调用，覆盖写 最近推送.csv */
    public static synchronized void recordPush(List<UpVideoItem> items, int qn) {
        if (items == null || items.isEmpty()) {
            return;
        }
        String now = nowText();
        List<String[]> rows = new ArrayList<>();
        for (UpVideoItem it : items) {
            if (it == null || it.getBvid() == null || it.getBvid().isEmpty()) {
                continue;
            }
            rows.add(new String[]{
                    it.getBvid(), it.getTitle() == null ? "" : it.getTitle(),
                    String.valueOf(it.getPageCount()), now, String.valueOf(qn)
            });
        }
        writeCsv(pushFile(), new String[]{"BV号", "标题", "分P数", "投递时间", "qn"}, rows);
    }

    private static List<String[]> loadPush() {
        File f = pushFile();
        if (!f.isFile()) {
            return new ArrayList<>();
        }
        try {
            List<String[]> all = SimpleCsv.readAll(f);
            List<String[]> out = new ArrayList<>();
            for (int i = 1; i < all.size(); i++) {
                String[] r = all.get(i);
                if (r.length >= 2 && r[0].trim().length() > 0) {
                    out.add(r);
                }
            }
            return out;
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    /** 所有 UP 主批量清单（checkpoint）合并后的条目 */
    private static List<String[]> loadCheckpointItems() {
        List<String[]> out = new ArrayList<>();
        File dir = upallDir();
        if (!dir.isDirectory()) {
            return out;
        }
        File[] fs = dir.listFiles();
        if (fs == null) {
            return out;
        }
        for (File f : fs) {
            if (!f.getName().startsWith("checkpoint_") || !f.getName().endsWith(".json")) {
                continue;
            }
            try {
                String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                JSONObject o = new JSONObject(s);
                JSONArray items = o.optJSONArray("items");
                String mid = o.optString("mid", "");
                if (items == null) {
                    continue;
                }
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.optJSONObject(i);
                    if (it == null) {
                        continue;
                    }
                    String bv = it.optString("bvid", "");
                    if (bv.isEmpty()) {
                        continue;
                    }
                    out.add(new String[]{
                            bv, it.optString("title", ""),
                            String.valueOf(it.optInt("pageCount", 1)),
                            "UP清单" + (mid.isEmpty() ? "" : "(" + mid + ")"),
                            String.valueOf(it.optInt("listPage", 0))
                    });
                }
            } catch (Throwable ignore) {
            }
        }
        return out;
    }

    /* ================= 比对 ================= */

    /**
     * @param allList true 用全部 checkpoint 清单，false 只比最近一次推送
     * @return 补采记录全表
     */
    public static synchronized List<String[]> runCheck(boolean allList) {
        Map<String, String[]> rec = loadMakeup();
        Set<String> local = localBvs();
        String now = nowText();

        List<String[]> src = allList ? loadCheckpointItems() : loadPush();
        for (String[] it : src) {
            if (it.length < 2) {
                continue;
            }
            String bv = it[0].trim();
            if (bv.isEmpty()) {
                continue;
            }
            String[] old = rec.get(bv);
            if (local.contains(bv)) {
                if (old != null && STATUS_TODO.equals(old[7])) {
                    old[6] = now;
                    old[7] = STATUS_DONE;
                }
                continue;
            }
            if (old == null) {
                String[] row = new String[HEAD.length];
                row[0] = bv;
                row[1] = it[1] == null ? "" : it[1];
                row[2] = it.length > 2 ? it[2] : "";
                if (allList) {
                    // checkpoint 行：BV,标题,分P数,UP清单(mid),清单页码
                    row[3] = it.length > 3 ? it[3] : "UP清单";
                    row[4] = it.length > 4 ? it[4] : "";
                } else {
                    // 最近推送行：BV,标题,分P数,投递时间,qn —— 后两项不是"来源/页码"，别硬塞
                    row[3] = "本次推送";
                    row[4] = "";
                }
                row[5] = now;
                row[6] = now;
                row[7] = STATUS_TODO;
                row[8] = "0";
                rec.put(bv, row);
            } else {
                old[6] = now;
                if (STATUS_TODO.equals(old[7])) {
                    int tries = parseInt(old[8]) + 1;
                    old[8] = String.valueOf(tries);
                }
            }
        }
        // 以前待补、现在磁盘已经有了 → 改已补
        for (String[] row : rec.values()) {
            if (STATUS_TODO.equals(row[7]) && local.contains(row[0])) {
                row[7] = STATUS_DONE;
                row[6] = now;
            }
        }
        saveMakeup(rec);
        List<String[]> rows = new ArrayList<>(rec.values());
        fireChanged();
        return rows;
    }

    /* ================= 读写 ================= */

    public static synchronized List<String[]> loadRows() {
        return new ArrayList<>(loadMakeup().values());
    }

    static Map<String, String[]> loadMakeup() {
        Map<String, String[]> m = new LinkedHashMap<>();
        File f = makeupFile();
        if (!f.isFile()) {
            return m;
        }
        try {
            List<String[]> all = SimpleCsv.readAll(f);
            for (int i = 1; i < all.size(); i++) {
                String[] r = all.get(i);
                if (r.length < 2 || r[0].trim().isEmpty()) {
                    continue;
                }
                String[] row = new String[HEAD.length];
                for (int c = 0; c < HEAD.length; c++) {
                    row[c] = c < r.length ? r[c] : "";
                }
                if (row[7].trim().isEmpty()) {
                    row[7] = STATUS_TODO;
                }
                if (row[8].trim().isEmpty()) {
                    row[8] = "0";
                }
                m.put(row[0].trim(), row);
            }
        } catch (Throwable ignore) {
        }
        return m;
    }

    private static void saveMakeup(Map<String, String[]> rec) {
        List<String[]> rows = new ArrayList<>(rec.values());
        writeCsv(makeupFile(), HEAD, rows);
    }

    /** 设置某条的状态（界面用） */
    public static synchronized void setStatus(String bv, String status) {
        Map<String, String[]> rec = loadMakeup();
        String[] row = rec.get(bv);
        if (row != null) {
            row[7] = status;
            row[6] = nowText();
            saveMakeup(rec);
        }
    }

    /** 清空补采记录（只留表头） */
    public static synchronized void clearMakeup() {
        writeCsv(makeupFile(), HEAD, new ArrayList<String[]>());
    }

    /** CSV 写，带 BOM，字段含逗号/引号就转义 */
    private static void writeCsv(File f, String[] head, List<String[]> rows) {
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory()) {
                dir.mkdirs();
            }
            StringBuilder sb = new StringBuilder();
            sb.append('\uFEFF');
            sb.append(joinCsv(head)).append("\r\n");
            for (String[] r : rows) {
                sb.append(joinCsv(r)).append("\r\n");
            }
            try (Writer w = new OutputStreamWriter(
                    new java.io.FileOutputStream(f), StandardCharsets.UTF_8)) {
                w.write(sb.toString());
            }
        } catch (Throwable ignore) {
        }
    }

    private static String joinCsv(String[] r) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(quote(r[i] == null ? "" : r[i]));
        }
        return sb.toString();
    }

    private static String quote(String s) {
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    static String nowText() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }
}
