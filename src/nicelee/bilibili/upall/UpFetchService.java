package nicelee.bilibili.upall;

import java.util.ArrayList;
import java.util.List;

import nicelee.bilibili.util.Logger;

/**
 * 分页遍历 + 限速 + 重试 + 断点续拉。
 * 只负责"把列表拿全"，不涉及下载。
 */
public class UpFetchService {

    public interface Listener {
        /** 状态栏文案 */
        void onMessage(String msg);

        /** 每页成功后回调（在后台线程，UI 需自行切回 EDT） */
        void onPageFetched(UpPageResult page, int accumulated);

        void onFinished(List<UpVideoItem> items, boolean resumedFromCheckpoint);

        void onError(Throwable e);

        boolean isCancelled();
    }

    private final UpSpaceApi api;
    private final UpFetchOptions options;

    public UpFetchService(UpSpaceApi api, UpFetchOptions options) {
        this.api = api;
        this.options = options;
    }

    /**
     * @param resume true 时优先读断点接着拉；false 时清空断点从头拉
     */
    public List<UpVideoItem> fetchAll(long mid, boolean resume, Listener listener) {
        UpFetchCheckpoint cp = new UpFetchCheckpoint(mid, options.getPageSize(), options.getOrder(), null);
        if (!resume) {
            cp.delete();
        }
        boolean resumed = cp.load();

        int startPage = resumed ? cp.getLastPage() + 1 : 1;
        int pageCount = resumed && cp.getTotalCount() > 0
                ? (cp.getTotalCount() + options.getPageSize() - 1) / options.getPageSize()
                : Integer.MAX_VALUE;

        if (listener != null) {
            listener.onMessage(resumed
                    ? String.format("从第 %d 页继续（本地已有 %d 条）", startPage, cp.getItems().size())
                    : "从第 1 页开始拉取");
        }

        for (int pn = startPage; pn <= pageCount; pn++) {
            if (listener != null && listener.isCancelled()) {
                Logger.println("[upall] 用户取消拉取");
                break;
            }
            UpPageResult page = fetchWithRetry(mid, pn, listener);
            if (page == null) {
                break; // 重试耗尽
            }
            cp.appendPage(page);
            pageCount = page.getPageCount();
            if (listener != null) {
                listener.onPageFetched(page, cp.getItems().size());
            }
            // 拉够上限就停
            if (options.getMaxItems() > 0 && cp.getItems().size() >= options.getMaxItems()) {
                break;
            }
            if (pn >= pageCount || page.getItems().isEmpty()) {
                break;
            }
            sleepQuietly(options.getIntervalMs());
        }

        List<UpVideoItem> all = new ArrayList<>(cp.getItems());
        if (options.getMaxItems() > 0 && all.size() > options.getMaxItems()) {
            all = new ArrayList<>(all.subList(0, options.getMaxItems()));
        }
        if (listener != null) {
            listener.onFinished(all, resumed);
        }
        return all;
    }

    /** 单页重试：风控错误会强制刷新 wbi 密钥并加长退避 */
    private UpPageResult fetchWithRetry(long mid, int pn, Listener listener) {
        int retry = Math.max(1, options.getMaxRetry());
        Throwable last = null;
        for (int i = 0; i < retry; i++) {
            try {
                return api.fetchPage(mid, pn, options.getPageSize(), options.getOrder(), null);
            } catch (Throwable t) {
                last = t;
                boolean risk = t instanceof UpApiException && ((UpApiException) t).isRiskControl();
                long wait = options.getIntervalMs() * (i + 1) * (risk ? 4 : 2);
                String msg = String.format("第 %d 页失败(%s)，%d ms 后第 %d 次重试",
                        pn, t.getMessage(), wait, i + 1);
                Logger.println("[upall] " + msg);
                if (listener != null) {
                    listener.onMessage(msg);
                }
                if (risk) {
                    WbiSigner.invalidate();
                }
                sleepQuietly(wait);
            }
        }
        if (listener != null) {
            listener.onError(last == null ? new IllegalStateException("未知错误") : last);
        }
        return null;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
