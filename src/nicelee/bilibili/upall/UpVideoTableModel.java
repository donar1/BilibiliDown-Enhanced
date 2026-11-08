package nicelee.bilibili.upall;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.swing.table.AbstractTableModel;

/**
 * 列表数据模型。列顺序：序号 / 标题 / BV号 / 时长 / 发布时间 / 播放量 / 分P数 / 所在页。
 * 排序交给 JTable 的 RowSorter（配合 getColumnClass 自动用数值比较）。
 */
public class UpVideoTableModel extends AbstractTableModel {

    private static final long serialVersionUID = 1L;

    public static final int COL_INDEX = 0;
    public static final int COL_TITLE = 1;
    public static final int COL_BVID = 2;
    public static final int COL_DURATION = 3;
    public static final int COL_PUBDATE = 4;
    public static final int COL_PLAY = 5;
    public static final int COL_PAGES = 6;
    public static final int COL_LIST_PAGE = 7;

    private static final String[] HEADERS = {
            "序号", "标题", "BV号", "时长", "发布时间", "播放量", "分P数", "所在页"
    };

    private final List<UpVideoItem> data = new ArrayList<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

    @Override
    public int getRowCount() {
        return data.size();
    }

    @Override
    public int getColumnCount() {
        return HEADERS.length;
    }

    @Override
    public String getColumnName(int column) {
        return HEADERS[column];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        switch (columnIndex) {
            case COL_INDEX:
                return Integer.class;
            case COL_DURATION:
                return Integer.class;
            case COL_PUBDATE:
                return Long.class;
            case COL_PLAY:
                return Long.class;
            case COL_PAGES:
                return Integer.class;
            case COL_LIST_PAGE:
                return Integer.class;
            default:
                return String.class;
        }
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        UpVideoItem item = data.get(rowIndex);
        switch (columnIndex) {
            case COL_INDEX:
                return rowIndex + 1;
            case COL_TITLE:
                return item.getTitle();
            case COL_BVID:
                return item.getBvid();
            case COL_DURATION:
                return item.getDurationSec();
            case COL_PUBDATE:
                return item.getCreatedSec();
            case COL_PLAY:
                return item.getPlayCount();
            case COL_PAGES:
                return item.getPageCount();
            case COL_LIST_PAGE:
                return item.getListPage();
            default:
                return "";
        }
    }

    /** 时长/日期的可读渲染，供 TableCellRenderer 调用 */
    public String formatCell(UpVideoItem item, int columnIndex) {
        switch (columnIndex) {
            case COL_DURATION:
                return item.getDurationText();
            case COL_PUBDATE:
                return dateFormat.format(new Date(item.getCreatedSec() * 1000L));
            case COL_PLAY:
                return String.valueOf(item.getPlayCount());
            default:
                return String.valueOf(getValueAt(data.indexOf(item), columnIndex));
        }
    }

    public void setItems(List<UpVideoItem> items) {
        data.clear();
        if (items != null) {
            data.addAll(items);
        }
        fireTableDataChanged();
    }

    public void appendItems(List<UpVideoItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        int start = data.size();
        data.addAll(items);
        fireTableRowsInserted(start, data.size() - 1);
    }

    public void clear() {
        data.clear();
        fireTableDataChanged();
    }

    public UpVideoItem getItem(int rowIndex) {
        return data.get(rowIndex);
    }

    public List<UpVideoItem> getItems() {
        return data;
    }
}
