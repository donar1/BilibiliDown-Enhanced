package nicelee.bilibili.upall;

import org.json.JSONObject;

/**
 * 一条投稿视频的列表摘要（来自 x/space/wbi/arc/search 的 vlist 数组）。
 * 与现有 VideoInfo/ClipInfo 解耦，仅用于列表展示与勾选。
 */
public class UpVideoItem {

    private long aid;
    private String bvid;
    private String title;
    private int durationSec;   // length "mm:ss" / "h:mm:ss" 解析而来
    private long createdSec;   // 发布时间（秒）
    private long playCount;    // 播放量
    private long commentCount;
    private int pageCount;     // video_review 字段，即分P数
    private String pic;
    private String description;
    private int listPage;      // 该条目在拉取时所属的接口页码，用于"按页码区间勾选"

    public static UpVideoItem fromJson(JSONObject o) {
        UpVideoItem it = new UpVideoItem();
        it.aid = o.optLong("aid");
        it.bvid = o.optString("bvid", "");
        it.title = o.optString("title", "");
        it.durationSec = parseLength(o.optString("length", ""));
        it.createdSec = o.optLong("created");
        it.playCount = o.optLong("play");
        it.commentCount = o.optLong("comment");
        it.pageCount = o.optInt("video_review", 1);
        if (it.pageCount <= 0) {
            it.pageCount = 1;
        }
        it.pic = o.optString("pic", "");
        it.description = o.optString("description", "");
        return it;
    }

    /** "mm:ss" 或 "h:mm:ss" → 秒 */
    public static int parseLength(String length) {
        if (length == null || length.isEmpty()) {
            return 0;
        }
        String[] parts = length.split(":");
        try {
            int sec = 0;
            for (String p : parts) {
                sec = sec * 60 + Integer.parseInt(p.trim());
            }
            return sec;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        o.put("aid", aid);
        o.put("bvid", bvid);
        o.put("title", title);
        o.put("duration", durationSec);
        o.put("created", createdSec);
        o.put("play", playCount);
        o.put("comment", commentCount);
        o.put("pageCount", pageCount);
        o.put("pic", pic);
        o.put("description", description);
        o.put("listPage", listPage);
        return o;
    }

    public static UpVideoItem fromCacheJson(JSONObject o) {
        UpVideoItem it = new UpVideoItem();
        it.aid = o.optLong("aid");
        it.bvid = o.optString("bvid", "");
        it.title = o.optString("title", "");
        it.durationSec = o.optInt("duration");
        it.createdSec = o.optLong("created");
        it.playCount = o.optLong("play");
        it.commentCount = o.optLong("comment");
        it.pageCount = o.optInt("pageCount", 1);
        it.pic = o.optString("pic", "");
        it.description = o.optString("description", "");
        it.listPage = o.optInt("listPage");
        return it;
    }

    /** 时长展示为 mm:ss / h:mm:ss */
    public String getDurationText() {
        int s = durationSec;
        int h = s / 3600;
        int m = (s % 3600) / 60;
        int sec = s % 60;
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, sec);
        }
        return String.format("%02d:%02d", m, sec);
    }

    public long getAid() {
        return aid;
    }

    public String getBvid() {
        return bvid;
    }

    public void setBvid(String bvid) {
        this.bvid = bvid;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getDurationSec() {
        return durationSec;
    }

    public long getCreatedSec() {
        return createdSec;
    }

    public long getPlayCount() {
        return playCount;
    }

    public long getCommentCount() {
        return commentCount;
    }

    public int getPageCount() {
        return pageCount;
    }

    public void setPageCount(int pageCount) {
        this.pageCount = pageCount <= 0 ? 1 : pageCount;
    }

    public String getPic() {
        return pic;
    }

    public String getDescription() {
        return description;
    }

    public int getListPage() {
        return listPage;
    }

    public void setListPage(int listPage) {
        this.listPage = listPage;
    }

    @Override
    public String toString() {
        return bvid + " " + title;
    }
}
