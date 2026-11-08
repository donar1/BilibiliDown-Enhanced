package nicelee.bilibili.upall;

import nicelee.ui.Global;

/**
 * 拉取参数。优先读 app.config（通过 Global.settings 注入），读不到用默认值。
 * <p>
 * app.config 新增项：
 * bilibili.up.fetch.pageSize     = 30    每页条数（接口上限 50）
 * bilibili.up.fetch.intervalMs   = 800   分页请求间隔（毫秒）
 * bilibili.up.fetch.maxItems     = 0     最大拉取条数，0 表示不限
 * bilibili.up.fetch.maxRetry     = 3     单页失败重试次数
 * bilibili.up.fetch.order        = pubdate  pubdate/click/stow
 * bilibili.up.fetch.allPages     = true  勾选视频时是否展开其全部分P
 */
public class UpFetchOptions {

    private int pageSize = 30;
    private long intervalMs = 800L;
    private int maxItems = 0;
    private int maxRetry = 3;
    private String order = UpSpaceApi.ORDER_PUBDATE;
    private boolean allPages = true;

    public static UpFetchOptions fromConfig() {
        UpFetchOptions o = new UpFetchOptions();
        o.pageSize = readInt("bilibili.up.fetch.pageSize", o.pageSize);
        o.intervalMs = readInt("bilibili.up.fetch.intervalMs", (int) o.intervalMs);
        o.maxItems = readInt("bilibili.up.fetch.maxItems", o.maxItems);
        o.maxRetry = readInt("bilibili.up.fetch.maxRetry", o.maxRetry);
        o.order = readString("bilibili.up.fetch.order", o.order);
        o.allPages = readBool("bilibili.up.fetch.allPages", o.allPages);
        if (o.pageSize <= 0 || o.pageSize > 50) {
            o.pageSize = 30;
        }
        if (o.intervalMs < 300) {
            o.intervalMs = 300;
        }
        return o;
    }

    private static String readString(String key, String def) {
        try {
            String v = Global.settings.get(key);
            return v == null || v.trim().isEmpty() ? def : v.trim();
        } catch (Throwable t) {
            return def;
        }
    }

    private static int readInt(String key, int def) {
        try {
            return Integer.parseInt(readString(key, String.valueOf(def)));
        } catch (Throwable t) {
            return def;
        }
    }

    private static boolean readBool(String key, boolean def) {
        try {
            String v = readString(key, String.valueOf(def));
            return "true".equalsIgnoreCase(v) || "1".equals(v);
        } catch (Throwable t) {
            return def;
        }
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }

    public int getMaxItems() {
        return maxItems;
    }

    public void setMaxItems(int maxItems) {
        this.maxItems = maxItems;
    }

    public int getMaxRetry() {
        return maxRetry;
    }

    public void setMaxRetry(int maxRetry) {
        this.maxRetry = maxRetry;
    }

    public String getOrder() {
        return order;
    }

    public void setOrder(String order) {
        this.order = order;
    }

    public boolean isAllPages() {
        return allPages;
    }

    public void setAllPages(boolean allPages) {
        this.allPages = allPages;
    }
}
