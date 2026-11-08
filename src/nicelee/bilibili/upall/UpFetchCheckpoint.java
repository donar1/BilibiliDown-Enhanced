package nicelee.bilibili.upall;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import nicelee.bilibili.util.Logger;
import nicelee.bilibili.util.ResourcesUtil;

/**
 * 断点续拉：每成功拉完一页就落盘一次。
 * 文件位置 config/upall/checkpoint_<mid>_<hash>.json
 */
public class UpFetchCheckpoint {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final long mid;
    private final int pageSize;
    private final String order;
    private final String keyword;

    private int lastPage;
    private int totalCount;
    private final List<UpVideoItem> items = new ArrayList<>();

    public UpFetchCheckpoint(long mid, int pageSize, String order, String keyword) {
        this.mid = mid;
        this.pageSize = pageSize;
        this.order = order == null ? "" : order;
        this.keyword = keyword == null ? "" : keyword;
    }

    public static File dir() {
        File base;
        try {
            base = ResourcesUtil.baseDirFile();
        } catch (Throwable t) {
            base = new File(".");
        }
        File dir = new File(base, "config" + File.separator + "upall");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public File file() {
        String sig = order + "|" + keyword + "|" + pageSize;
        return new File(dir(), "checkpoint_" + mid + "_" + shortHash(sig) + ".json");
    }

    private static String shortHash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(UTF8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "000000";
        }
    }

    /** 读取断点，返回是否读到了可用数据 */
    public boolean load() {
        File f = file();
        if (!f.isFile()) {
            return false;
        }
        try {
            String content = new String(Files.readAllBytes(f.toPath()), UTF8);
            JSONObject o = new JSONObject(content);
            if (o.optLong("mid") != mid) {
                return false;
            }
            lastPage = o.optInt("lastPage", 0);
            totalCount = o.optInt("totalCount", 0);
            items.clear();
            JSONArray arr = o.optJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    items.add(UpVideoItem.fromCacheJson(arr.getJSONObject(i)));
                }
            }
            Logger.printf("[upall] 命中断点，已拉 %d 页 / %d 条\n", lastPage, items.size());
            return lastPage > 0;
        } catch (Exception e) {
            Logger.println("[upall] 断点读取失败: " + e.getMessage());
            return false;
        }
    }

    public void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("mid", mid);
            o.put("order", order);
            o.put("keyword", keyword);
            o.put("pageSize", pageSize);
            o.put("lastPage", lastPage);
            o.put("totalCount", totalCount);
            JSONArray arr = new JSONArray();
            for (UpVideoItem it : items) {
                arr.put(it.toJson());
            }
            o.put("items", arr);
            Files.write(file().toPath(), o.toString().getBytes(UTF8));
        } catch (IOException e) {
            Logger.println("[upall] 断点保存失败: " + e.getMessage());
        }
    }

    public void delete() {
        File f = file();
        if (f.isFile() && !f.delete()) {
            Logger.println("[upall] 断点文件删除失败: " + f.getAbsolutePath());
        }
    }

    public void appendPage(UpPageResult page) {
        for (UpVideoItem it : page.getItems()) {
            items.add(it);
        }
        lastPage = page.getPageNum();
        totalCount = page.getTotalCount();
        save();
    }

    public int getLastPage() {
        return lastPage;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public List<UpVideoItem> getItems() {
        return items;
    }
}
