package nicelee.bilibili.upall;

import java.util.ArrayList;
import java.util.List;

/** 一页投稿列表的结果。 */
public class UpPageResult {

    private final int pageNum;
    private final int pageSize;
    private final int totalCount;
    private final List<UpVideoItem> items;

    public UpPageResult(int pageNum, int pageSize, int totalCount, List<UpVideoItem> items) {
        this.pageNum = pageNum;
        this.pageSize = pageSize;
        this.totalCount = totalCount;
        this.items = items == null ? new ArrayList<UpVideoItem>() : items;
    }

    public int getPageNum() {
        return pageNum;
    }

    public int getPageSize() {
        return pageSize;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public List<UpVideoItem> getItems() {
        return items;
    }

    /** 总页数（按接口声明的 ps 计算，避免与请求 ps 不一致时算错） */
    public int getPageCount() {
        int ps = pageSize <= 0 ? items.size() : pageSize;
        if (ps <= 0) {
            return 1;
        }
        return (totalCount + ps - 1) / ps;
    }
}
