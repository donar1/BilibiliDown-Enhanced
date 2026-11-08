package nicelee.bilibili.upall;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;

/**
 * 「补采清单」页签：把没下成的条目集中展示，方便二次推送。
 *
 * 数据来源 记录工具\补采记录.csv，由 ShortfallChecker 生成：
 *   - 每次「加入下载队列」都会记下推送清单
 *   - 下载批次结束后 DownloadWatch 自动比对，缺口进这里（状态=待补）
 *   - 也可以手动点「立即比对」
 */
public class MakeupPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final String[] COLS = {
            "", "标题", "BV号", "分P", "来源", "首次发现", "最近比对", "状态", "次数"
    };
    private static final int COL_COVER = 0;
    private static final int COL_TITLE = 1;
    private static final int COL_BV = 2;
    private static final int COL_PC = 3;
    private static final int COL_STATUS = 7;

    private static final int COVER_W = 112;
    private static final int COVER_H = 63;
    private static final int ROW_H = 78;

    private final JFrame frame;
    private final MakeupModel model = new MakeupModel();
    private final JTable table = new JTable(model);
    private final TableRowSorter<MakeupModel> sorter = new TableRowSorter<>(model);
    private final JComboBox<String> cmbStatus =
            new JComboBox<>(new String[]{"全部", ShortfallChecker.STATUS_TODO,
                    ShortfallChecker.STATUS_DONE, ShortfallChecker.STATUS_IGNORE});
    private final JLabel lbInfo = new JLabel("尚未比对");

    public MakeupPanel(JFrame frame) {
        this.frame = frame;
        setLayout(new BorderLayout());
        setBackground(LedgerPanel.BG);
        add(buildBar(), BorderLayout.NORTH);
        add(buildTable(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        ShortfallChecker.addListener(this::reload);
        reload();
    }

    private JPanel buildBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        bar.setBackground(new Color(20, 23, 29));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LedgerPanel.LINE));

        bar.add(makeButton("立即比对", e -> doCheck(false)));
        bar.add(makeButton("全选待补", e -> selectTodo()));
        bar.add(makeButton("加入下载队列", e -> pushSelected()));
        bar.add(makeButton("标记忽略", e -> markIgnore()));
        bar.add(makeButton("刷新", e -> reload()));
        bar.add(makeButton("打开CSV", e -> openCsv()));
        bar.add(makeButton("清空列表", e -> clearAll()));

        cmbStatus.setBackground(new Color(32, 36, 44));
        cmbStatus.setForeground(LedgerPanel.FG);
        cmbStatus.setBorder(BorderFactory.createLineBorder(new Color(52, 58, 70), 1, true));
        cmbStatus.setFont(cmbStatus.getFont().deriveFont(Font.PLAIN, 12f));
        cmbStatus.addActionListener(e -> applyFilter());
        bar.add(cmbStatus);
        return bar;
    }

    private JButton makeButton(String text, java.awt.event.ActionListener l) {
        JButton b = new JButton(text);
        b.setFocusPainted(false);
        b.setBackground(new Color(32, 36, 44));
        b.setForeground(LedgerPanel.FG);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(56, 62, 76), 1, true),
                BorderFactory.createEmptyBorder(4, 12, 4, 12)));
        b.setFont(b.getFont().deriveFont(Font.PLAIN, 12f));
        b.addActionListener(l);
        return b;
    }

    private JScrollPane buildTable() {
        table.setRowSorter(sorter);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setRowHeight(ROW_H);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setBackground(LedgerPanel.BG);
        table.setForeground(LedgerPanel.FG);
        table.setFillsViewportHeight(true);
        table.setSelectionBackground(LedgerPanel.BG_SEL);
        table.setSelectionForeground(LedgerPanel.FG);
        table.setFont(table.getFont().deriveFont(Font.PLAIN, 12f));

        int[] widths = {128, 430, 108, 46, 150, 128, 128, 66, 48};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.getColumnModel().getColumn(COL_COVER).setCellRenderer(new CoverRenderer());
        table.setDefaultRenderer(Object.class, new TextRenderer());
        table.getColumnModel().getColumn(COL_TITLE).setCellRenderer(new TitleRenderer());
        table.getColumnModel().getColumn(COL_STATUS).setCellRenderer(new StatusRenderer());

        JTableHeader header = table.getTableHeader();
        header.setDefaultRenderer(new LedgerPanel.HeaderRenderer());
        header.setPreferredSize(new Dimension(0, 34));
        header.setReorderingAllowed(false);

        table.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openRow(table.convertRowIndexToModel(table.getSelectedRow()));
                }
            }
        });

        JScrollPane sp = new JScrollPane(table);
        sp.getViewport().setBackground(LedgerPanel.BG);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getVerticalScrollBar().setUnitIncrement(24);
        return sp;
    }

    private JPanel buildFooter() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        p.setBackground(new Color(20, 23, 29));
        p.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, LedgerPanel.LINE));
        lbInfo.setForeground(LedgerPanel.MUTED);
        lbInfo.setFont(lbInfo.getFont().deriveFont(Font.PLAIN, 12f));
        p.add(lbInfo);
        return p;
    }

    /* ================= 数据 ================= */

    public void reload() {
        SwingUtilities.invokeLater(() -> {
            model.setRows(ShortfallChecker.loadRows());
            applyFilter();
        });
    }

    private void applyFilter() {
        String st = (String) cmbStatus.getSelectedItem();
        if (st == null || "全部".equals(st)) {
            sorter.setRowFilter(null);
        } else {
            sorter.setRowFilter(RowFilter.regexFilter("^" + java.util.regex.Pattern.quote(st) + "$", COL_STATUS));
        }
        int total = model.getRowCount();
        int todo = 0;
        for (String[] r : model.rows) {
            if (ShortfallChecker.STATUS_TODO.equals(r[COL_STATUS])) {
                todo++;
            }
        }
        lbInfo.setText("补采记录共 " + total + " 条，待补 " + todo + " 条，当前显示 "
                + table.getRowCount() + " 条   |   选中后点「加入下载队列」即可二次推送");
    }

    private void doCheck(final boolean allList) {
        lbInfo.setText(allList ? "正在和全部清单比对…" : "正在和最近一次推送比对…");
        new Thread(() -> {
            List<String[]> rows = ShortfallChecker.runCheck(allList);
            int todo = 0;
            for (String[] r : rows) {
                if (ShortfallChecker.STATUS_TODO.equals(r[7])) {
                    todo++;
                }
            }
            final int todoN = todo;
            SwingUtilities.invokeLater(() -> {
                model.setRows(rows);
                applyFilter();
                lbInfo.setText("比对完成：待补 " + todoN + " 条");
            });
        }, "makeup-check").start();
    }

    private void selectTodo() {
        table.clearSelection();
        for (int v = 0; v < table.getRowCount(); v++) {
            int m = table.convertRowIndexToModel(v);
            if (ShortfallChecker.STATUS_TODO.equals(model.rows.get(m)[COL_STATUS])) {
                table.addRowSelectionInterval(v, v);
            }
        }
    }

    private void pushSelected() {
        int[] viewRows = table.getSelectedRows();
        if (viewRows.length == 0) {
            JOptionPane.showMessageDialog(frame, "先在表里选中要补的视频（可先点「全选待补」）。",
                    "补采清单", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        final List<UpVideoItem> items = new ArrayList<>();
        for (int v : viewRows) {
            int m = table.convertRowIndexToModel(v);
            String[] r = model.rows.get(m);
            if (ShortfallChecker.STATUS_DONE.equals(r[COL_STATUS])) {
                continue;
            }
            UpVideoItem it = new UpVideoItem();
            it.setBvid(r[COL_BV]);
            it.setTitle(r[COL_TITLE]);
            it.setPageCount(parseIntSafe(r[COL_PC]));
            items.add(it);
        }
        if (items.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "选中的都是「已补」，不需要再推。",
                    "补采清单", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        lbInfo.setText("正在投递 " + items.size() + " 条…");
        new Thread(() -> {
            int n = UpDownloadBridge.enqueue(items, 0, false, (done, total, msg) ->
                    SwingUtilities.invokeLater(() -> lbInfo.setText(
                            String.format("投递中 %d/%d：%s", done, total, msg))));
            SwingUtilities.invokeLater(() -> lbInfo.setText(
                    "已加入下载队列，共 " + n + " 个任务（下载完成后会自动重新比对并标记已补）"));
        }, "makeup-push").start();
    }

    private void markIgnore() {
        int[] viewRows = table.getSelectedRows();
        if (viewRows.length == 0) {
            return;
        }
        for (int v : viewRows) {
            int m = table.convertRowIndexToModel(v);
            ShortfallChecker.setStatus(model.rows.get(m)[COL_BV], ShortfallChecker.STATUS_IGNORE);
        }
        reload();
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s == null ? "" : s.trim());
        } catch (Throwable ignore) {
            return 0;
        }
    }

    /** 清空补采列表（二次确认，避免误触） */
    private void clearAll() {
        int n = model.getRowCount();
        if (n == 0) {
            lbInfo.setText("列表已经是空的。");
            return;
        }
        int r = JOptionPane.showConfirmDialog(frame,
                "确定清空补采列表吗？\n共 " + n + " 条记录将被删除，只保留表头。\n"
                        + "（之后再做比对，若仍有缺失项会重新出现在这里）",
                "清空补采列表", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.OK_OPTION) {
            return;
        }
        ShortfallChecker.clearMakeup();
        reload();
        lbInfo.setText("已清空补采列表，共清除 " + n + " 条。");
    }

    private void openCsv() {
        File f = ShortfallChecker.makeupFile();
        if (!f.isFile()) {
            JOptionPane.showMessageDialog(frame,
                    "还没有补采记录：\n" + f.getAbsolutePath(), "补采清单", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(f);
                return;
            }
        } catch (Throwable ignore) {
        }
        try {
            new ProcessBuilder("cmd.exe", "/c", "start", "", f.getAbsolutePath()).start();
        } catch (Throwable ignore2) {
        }
    }

    private void openRow(int modelRow) {
        if (modelRow < 0) {
            return;
        }
        String bv = model.rows.get(modelRow)[COL_BV];
        try {
            Desktop.getDesktop().browse(new java.net.URI("https://www.bilibili.com/video/" + bv));
        } catch (Throwable ignore) {
        }
    }

    /* ================= 渲染器 ================= */

    static class TextRenderer extends DefaultTableCellRenderer {

        private static final long serialVersionUID = 1L;

        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            l.setOpaque(true);
            l.setBackground(isSelected ? LedgerPanel.BG_SEL : (row % 2 == 0 ? LedgerPanel.BG : LedgerPanel.BG_ALT));
            l.setForeground(isSelected ? Color.WHITE : LedgerPanel.MUTED);
            l.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 6));
            l.setFont(l.getFont().deriveFont(Font.PLAIN, 12f));
            return l;
        }
    }

    static class TitleRenderer extends DefaultTableCellRenderer {

        private static final long serialVersionUID = 1L;

        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            String title = value == null ? "" : value.toString();
            String sub = "";
            int mrow = table.convertRowIndexToModel(row);
            if (table.getModel() instanceof MakeupModel) {
                String[] r = ((MakeupModel) table.getModel()).rows.get(mrow);
                sub = (r[COL_STATUS] == null ? "" : r[COL_STATUS]);
            }
            String html = "<html><body style='width:400px'>"
                    + "<span style='color:" + (isSelected ? "#ffffff" : "#e8ebf0") + ";font-weight:bold'>"
                    + esc(title) + "</span>"
                    + "<br><span style='color:" + (isSelected ? "#ffffff" : "#8d95a3") + ";font-size:10px'>"
                    + esc(sub) + "</span></body></html>";
            l.setText(html);
            l.setOpaque(true);
            l.setBackground(isSelected ? LedgerPanel.BG_SEL : (row % 2 == 0 ? LedgerPanel.BG : LedgerPanel.BG_ALT));
            l.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 6));
            return l;
        }

        private String esc(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    static class StatusRenderer extends DefaultTableCellRenderer {

        private static final long serialVersionUID = 1L;

        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            String s = value == null ? "" : value.toString();
            l.setOpaque(true);
            l.setBackground(isSelected ? LedgerPanel.BG_SEL : (row % 2 == 0 ? LedgerPanel.BG : LedgerPanel.BG_ALT));
            if (ShortfallChecker.STATUS_TODO.equals(s)) {
                l.setForeground(LedgerPanel.C_MOVED);
            } else if (ShortfallChecker.STATUS_DONE.equals(s)) {
                l.setForeground(LedgerPanel.C_LIVE);
            } else {
                l.setForeground(LedgerPanel.MUTED);
            }
            l.setFont(l.getFont().deriveFont(Font.BOLD, 12f));
            l.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 6));
            return l;
        }
    }

    static class CoverRenderer extends JLabel implements TableCellRenderer {

        private static final long serialVersionUID = 1L;

        CoverRenderer() {
            setOpaque(true);
            setHorizontalAlignment(JLabel.CENTER);
            setVerticalAlignment(JLabel.CENTER);
        }

        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            int mrow = table.convertRowIndexToModel(row);
            String bv = "";
            if (table.getModel() instanceof MakeupModel) {
                String[] r = ((MakeupModel) table.getModel()).rows.get(mrow);
                if (r != null && r.length > COL_BV) {
                    bv = r[COL_BV] == null ? "" : r[COL_BV].trim();
                }
            }
            setBackground(isSelected ? LedgerPanel.BG_SEL : (row % 2 == 0 ? LedgerPanel.BG : LedgerPanel.BG_ALT));
            setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            ImageIcon ic = LedgerPanel.CoverStore.get(bv, COVER_W, COVER_H);
            if (ic != null) {
                setIcon(ic);
                setText(null);
            } else {
                setIcon(null);
                setText("<html><center><span style='color:#5a6270;font-size:10px'>"
                        + (bv.isEmpty() ? "" : "加载中") + "</span></center></html>");
            }
            return this;
        }
    }

    /* ================= 模型 ================= */

    static class MakeupModel extends AbstractTableModel {

        private static final long serialVersionUID = 1L;

        final List<String[]> rows = new ArrayList<>();

        /**
         * CSV 原始行顺序：BV号,标题,分P数,清单来源,清单页码,首次发现,最近比对,状态,尝试次数
         * 显示列顺序：  封面,BV号,标题,分P,来源,首次发现,最近比对,状态,次数
         * 这里先转成显示顺序，避免"列错位"（曾因此把分P数当成 BV 号推送出去）
         */
        void setRows(List<String[]> src) {
            rows.clear();
            if (src != null) {
                for (String[] r : src) {
                    rows.add(toDisplayRow(r));
                }
            }
            fireTableDataChanged();
        }

        private static String[] toDisplayRow(String[] r) {
            String bv = cell(r, 0);
            String[] out = new String[COLS.length];
            out[0] = bv;                 // 封面（用 BV 去取图，值本身不显示）
            out[1] = cell(r, 1);         // 标题
            out[2] = bv;                 // BV号
            out[3] = cell(r, 2);         // 分P数
            out[4] = cell(r, 3);         // 清单来源
            out[5] = cell(r, 5);         // 首次发现
            out[6] = cell(r, 6);         // 最近比对
            out[7] = cell(r, 7);         // 状态
            out[8] = cell(r, 8);         // 尝试次数
            return out;
        }

        private static String cell(String[] r, int i) {
            if (r == null || i < 0 || i >= r.length || r[i] == null) {
                return "";
            }
            return r[i];
        }

        public int getRowCount() {
            return rows.size();
        }

        public int getColumnCount() {
            return COLS.length;
        }

        public String getColumnName(int column) {
            return COLS[column];
        }

        public Object getValueAt(int rowIndex, int columnIndex) {
            if (rowIndex < 0 || rowIndex >= rows.size()) {
                return "";
            }
            String[] r = rows.get(rowIndex);
            return columnIndex < r.length ? (r[columnIndex] == null ? "" : r[columnIndex]) : "";
        }

        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }
    }
}
