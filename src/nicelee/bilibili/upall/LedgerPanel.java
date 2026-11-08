package nicelee.bilibili.upall;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;
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
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;

/**
 * 「下载记录」标签页：网页版台账的界面版（带封面缩略图）。
 *
 * - 深色主题、封面列、状态彩色标记，视觉与 下载记录.html 对齐
 * - 封面异步加载（4 线程 + 磁盘缓存 .covercache），不卡界面
 * - 搜索、状态筛选、排序、双击打开（有文件开文件，没文件开网页）
 */
public class LedgerPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /* ========== 配色（对齐网页台账） ========== */
    static final Color BG = new Color(15, 17, 21);
    static final Color BG_ALT = new Color(23, 26, 32);
    static final Color BG_SEL = new Color(30, 45, 62);
    static final Color FG = new Color(232, 235, 240);
    static final Color MUTED = new Color(150, 158, 170);
    static final Color HEAD_BG = new Color(28, 32, 40);
    static final Color LINE = new Color(38, 42, 50);
    static final Color ACCENT = new Color(0, 174, 236);

    static final Color C_LIVE = new Color(61, 220, 132);   // 在库
    static final Color C_MOVED = new Color(255, 167, 38);  // 已移走
    static final Color C_ONLY = new Color(122, 162, 247);  // 仅记录
    static final Color C_LIST = new Color(154, 160, 166);  // 清单

    /* ========== 列定义 ========== */
    private static final String[] COLS = {
            "", "标题", "BV号", "分P", "UP主", "时长", "大小MB", "状态",
            "发布日期", "清晰度", "最近校验", "文件路径"
    };
    /** 显示列 -> CSV 列序号；封面列用 -1 表示不来自 CSV */
    private static final int[] CSV_INDEX = {-1, 3, 1, 2, 4, 5, 9, 13, 6, 8, 14, 10};
    private static final int COL_COVER = 0;
    private static final int COL_TITLE = 1;
    private static final int COL_BV = 2;
    private static final int COL_STATUS = 7;
    private static final int COL_PATH = 11;

    private static final int COVER_W = 112;
    private static final int COVER_H = 63;
    private static final int ROW_H = 78;

    private final JFrame frame;
    private final CsvModel model = new CsvModel();
    private final JTable table = new JTable(model);
    private final TableRowSorter<CsvModel> sorter = new TableRowSorter<>(model);
    private final JTextField txtSearch = new JTextField(18);
    private final JComboBox<String> cmbStatus =
            new JComboBox<>(new String[]{"全部状态", "在库", "已移走", "仅记录", "清单"});
    private final JLabel lbInfo = new JLabel("尚未加载");

    public LedgerPanel(JFrame frame) {
        this.frame = frame;
        setLayout(new BorderLayout());
        setBackground(BG);

        add(buildBar(), BorderLayout.NORTH);
        add(buildTable(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        CoverStore.init(LedgerMenuHook.toolDir(), this::repaintTable);
        reload();
    }

    /* ================= 顶部工具条 ================= */
    private JPanel buildBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        bar.setBackground(new Color(20, 23, 29));
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LINE));

        JLabel lbSearch = new JLabel("搜索");
        lbSearch.setForeground(MUTED);
        lbSearch.setFont(lbSearch.getFont().deriveFont(Font.PLAIN, 12f));
        bar.add(lbSearch);

        styleField(txtSearch);
        bar.add(txtSearch);

        styleCombo(cmbStatus);
        bar.add(cmbStatus);

        bar.add(makeButton("刷新", e -> reload()));
        bar.add(makeButton("同步台账", e -> syncThenReload()));
        bar.add(makeButton("网页版", e -> LedgerMenuHook.openHtml(frame)));
        bar.add(makeButton("CSV", e -> LedgerMenuHook.openCsv(frame)));

        txtSearch.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });
        cmbStatus.addActionListener(e -> applyFilter());
        return bar;
    }

    private void styleField(JTextField f) {
        f.setBackground(new Color(32, 36, 44));
        f.setForeground(FG);
        f.setCaretColor(FG);
        f.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(52, 58, 70), 1, true),
                BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        f.setFont(f.getFont().deriveFont(Font.PLAIN, 12f));
    }

    private void styleCombo(JComboBox<String> c) {
        c.setBackground(new Color(32, 36, 44));
        c.setForeground(FG);
        c.setBorder(BorderFactory.createLineBorder(new Color(52, 58, 70), 1, true));
        c.setFont(c.getFont().deriveFont(Font.PLAIN, 12f));
    }

    private JButton makeButton(String text, java.awt.event.ActionListener l) {
        JButton b = new JButton(text);
        b.setFocusPainted(false);
        b.setBackground(new Color(32, 36, 44));
        b.setForeground(FG);
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(56, 62, 76), 1, true),
                BorderFactory.createEmptyBorder(4, 12, 4, 12)));
        b.setFont(b.getFont().deriveFont(Font.PLAIN, 12f));
        b.addActionListener(l);
        return b;
    }

    /* ================= 表格 ================= */
    private JScrollPane buildTable() {
        table.setRowSorter(sorter);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(ROW_H);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setBackground(BG);
        table.setForeground(FG);
        table.setFillsViewportHeight(true);
        table.setSelectionBackground(BG_SEL);
        table.setSelectionForeground(FG);
        table.setFont(table.getFont().deriveFont(Font.PLAIN, 12f));

        int[] widths = {128, 430, 108, 46, 108, 56, 68, 66, 84, 60, 128, 300};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        table.getColumnModel().getColumn(COL_COVER).setCellRenderer(new CoverRenderer());
        DefaultTableCellRenderer text = new TextRenderer();
        table.setDefaultRenderer(Object.class, text);
        table.getColumnModel().getColumn(COL_TITLE).setCellRenderer(new TitleRenderer());
        table.getColumnModel().getColumn(COL_STATUS).setCellRenderer(new StatusRenderer());

        JTableHeader header = table.getTableHeader();
        header.setDefaultRenderer(new HeaderRenderer());
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
        sp.getViewport().setBackground(BG);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getVerticalScrollBar().setUnitIncrement(24);
        return sp;
    }

    private JPanel buildFooter() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        p.setBackground(new Color(20, 23, 29));
        p.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, LINE));
        lbInfo.setForeground(MUTED);
        lbInfo.setFont(lbInfo.getFont().deriveFont(Font.PLAIN, 12f));
        p.add(lbInfo);
        return p;
    }

    private void repaintTable() {
        SwingUtilities.invokeLater(() -> {
            table.repaint();
            if (table.getRowCount() > 0) {
                table.setRowHeight(ROW_H);
            }
        });
    }

    static File csvFile() {
        return new File(LedgerMenuHook.toolDir(), "下载记录.csv");
    }

    /** 重新读一遍台账文件 */
    public void reload() {
        File f = csvFile();
        if (!f.isFile()) {
            model.setRows(new String[0][], new String[0][]);
            lbInfo.setText("还没有台账文件：" + f.getAbsolutePath());
            return;
        }
        try {
            List<String[]> all = SimpleCsv.readAll(f);
            List<String[]> view = new ArrayList<>();
            List<String[]> raw = new ArrayList<>();
            for (int i = 1; i < all.size(); i++) { // 跳过表头
                String[] r = all.get(i);
                if (r.length < 11) {
                    continue;
                }
                String[] v = new String[COLS.length];
                for (int c = 0; c < COLS.length; c++) {
                    int idx = CSV_INDEX[c];
                    if (idx < 0) {
                        v[c] = "";
                        continue;
                    }
                    v[c] = idx < r.length ? r[idx] : "";
                }
                view.add(v);
                raw.add(r);
            }
            model.setRows(view.toArray(new String[0][]), raw.toArray(new String[0][]));
            applyFilter();
        } catch (Exception ex) {
            lbInfo.setText("读取台账失败：" + ex.getMessage());
        }
    }

    private void applyFilter() {
        String kw = txtSearch.getText().trim();
        final String status = (String) cmbStatus.getSelectedItem();
        List<RowFilter<Object, Object>> filters = new ArrayList<>();
        if (kw.length() > 0) {
            final Pattern p = Pattern.compile("(?i)" + Pattern.quote(kw));
            filters.add(new RowFilter<Object, Object>() {
                public boolean include(Entry<? extends Object, ? extends Object> entry) {
                    for (int i = 0; i < COLS.length; i++) {
                        if (i == COL_COVER) {
                            continue;
                        }
                        Object v = entry.getValue(i);
                        if (v != null && p.matcher(v.toString()).find()) {
                            return true;
                        }
                    }
                    return false;
                }
            });
        }
        if (status != null && !"全部状态".equals(status)) {
            filters.add(RowFilter.regexFilter("^" + Pattern.quote(status) + "$", COL_STATUS));
        }
        sorter.setRowFilter(filters.isEmpty() ? null : RowFilter.andFilter(filters));
        lbInfo.setText("共 " + model.getRowCount() + " 条，当前显示 " + table.getRowCount()
                + " 条   |   双击一行：有本地文件就打开文件，没有就打开 B 站页面");
    }

    private void syncThenReload() {
        LedgerMenuHook.doSync(frame);
        javax.swing.Timer t = new javax.swing.Timer(6000, e -> reload());
        t.setRepeats(false);
        t.start();
        lbInfo.setText("正在后台同步台账（约几秒），完成后表格会自动刷新…");
    }

    private void openRow(int modelRow) {
        if (modelRow < 0) {
            return;
        }
        String[] view = model.rows.get(modelRow);
        String path = view[COL_PATH];
        if (path != null && path.trim().length() > 0) {
            File f = new File(path.trim());
            if (f.isFile()) {
                try {
                    Desktop.getDesktop().open(f);
                    return;
                } catch (Throwable ignored) {
                }
            }
        }
        String bv = view[COL_BV] == null ? "" : view[COL_BV].trim();
        if (bv.length() > 0) {
            try {
                Desktop.getDesktop().browse(new java.net.URI("https://www.bilibili.com/video/" + bv));
                return;
            } catch (Throwable ignored) {
            }
        }
        JOptionPane.showMessageDialog(frame,
                "这条记录既没有本地文件，也没有可打开的链接。",
                "下载记录", JOptionPane.INFORMATION_MESSAGE);
    }

    /* ================= 渲染器 ================= */
    static class HeaderRenderer extends DefaultTableCellRenderer {

        private static final long serialVersionUID = 1L;

        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            l.setOpaque(true);
            l.setBackground(HEAD_BG);
            l.setForeground(FG);
            l.setFont(l.getFont().deriveFont(Font.BOLD, 12f));
            l.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 1, 1, 0, LINE),
                    BorderFactory.createEmptyBorder(0, 10, 0, 6)));
            l.setHorizontalAlignment(JLabel.LEFT);
            return l;
        }
    }

    static class TextRenderer extends DefaultTableCellRenderer {

        private static final long serialVersionUID = 1L;

        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            l.setOpaque(true);
            l.setBackground(isSelected ? BG_SEL : (row % 2 == 0 ? BG : BG_ALT));
            l.setForeground(isSelected ? Color.WHITE : MUTED);
            l.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 6));
            l.setFont(l.getFont().deriveFont(Font.PLAIN, 12f));
            l.setHorizontalAlignment(JLabel.LEFT);
            return l;
        }
    }

    static class TitleRenderer extends DefaultTableCellRenderer {

        private static final long serialVersionUID = 1L;

        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            String title = value == null ? "" : value.toString();
            String status = "";
            String up = "";
            int mrow = table.convertRowIndexToModel(row);
            if (table.getModel() instanceof CsvModel) {
                String[] r = ((CsvModel) table.getModel()).rows.get(mrow);
                status = r[COL_STATUS] == null ? "" : r[COL_STATUS];
                up = r[4] == null ? "" : r[4];
            }
            String sub = (up == null || up.isEmpty() ? "" : up + "   ·   ") + status;
            String html = "<html><body style='width:400px'>"
                    + "<span style='color:" + (isSelected ? "#ffffff" : "#e8ebf0") + ";font-weight:bold'>"
                    + escapeHtml(title) + "</span>"
                    + "<br><span style='color:" + (isSelected ? "#ffffff" : "#8d95a3") + ";font-size:10px'>"
                    + escapeHtml(sub) + "</span></body></html>";
            l.setText(html);
            l.setOpaque(true);
            l.setBackground(isSelected ? BG_SEL : (row % 2 == 0 ? BG : BG_ALT));
            l.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 6));
            l.setVerticalAlignment(JLabel.CENTER);
            return l;
        }
    }

    static class StatusRenderer extends DefaultTableCellRenderer {

        private static final long serialVersionUID = 1L;

        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            String s = value == null ? "" : value.toString();
            l.setOpaque(true);
            l.setBackground(isSelected ? BG_SEL : (row % 2 == 0 ? BG : BG_ALT));
            l.setForeground(colorOf(s));
            l.setFont(l.getFont().deriveFont(Font.BOLD, 12f));
            l.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 6));
            return l;
        }

        static Color colorOf(String s) {
            if (s.contains("在库")) {
                return C_LIVE;
            }
            if (s.contains("已移走")) {
                return C_MOVED;
            }
            if (s.contains("仅记录")) {
                return C_ONLY;
            }
            if (s.contains("清单")) {
                return C_LIST;
            }
            return MUTED;
        }
    }

    /** 封面列：异步加载，未加载完显示深色占位 */
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
            if (table.getModel() instanceof CsvModel) {
                String[] r = ((CsvModel) table.getModel()).rows.get(mrow);
                if (r != null && r.length > COL_BV) {
                    bv = r[COL_BV] == null ? "" : r[COL_BV].trim();
                }
            }
            setBackground(isSelected ? BG_SEL : (row % 2 == 0 ? BG : BG_ALT));
            setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            ImageIcon ic = CoverStore.get(bv, COVER_W, COVER_H);
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

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /* ================= 封面缓存 ================= */
    static final class CoverStore {

        private static final Map<String, ImageIcon> MEM = new HashMap<>();
        private static final Set<String> FAILED = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        private static final Set<String> LOADING = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        private static final ExecutorService POOL = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "ledger-cover");
            t.setDaemon(true);
            return t;
        });
        private static Map<String, String> urls = new HashMap<>();
        private static File cacheDir;
        private static Runnable onLoaded = () -> {
        };

        private CoverStore() {
        }

        static synchronized void init(File toolDir, Runnable cb) {
            onLoaded = cb == null ? onLoaded : cb;
            if (cacheDir == null) {
                cacheDir = new File(toolDir, ".covercache");
                if (!cacheDir.isDirectory()) {
                    cacheDir.mkdirs();
                }
            }
            File j = new File(toolDir, ".covers.json");
            if (j.isFile()) {
                urls = parseCovers(j);
            }
        }

        /** .covers.json 是 {"BV...": "url"} 的平铺结构，直接正则抓对 */
        private static Map<String, String> parseCovers(File f) {
            Map<String, String> m = new HashMap<>();
            try {
                String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                Pattern p = Pattern.compile("\"(BV[0-9A-Za-z]{10})\"\\s*:\\s*\"([^\"]+)\"");
                Matcher mt = p.matcher(s);
                while (mt.find()) {
                    m.put(mt.group(1), mt.group(2));
                }
            } catch (Throwable ignore) {
            }
            return m;
        }

        static synchronized ImageIcon get(String bv, int w, int h) {
            if (bv == null || bv.isEmpty()) {
                return null;
            }
            ImageIcon ic = MEM.get(bv);
            if (ic != null) {
                return ic;
            }
            if (FAILED.contains(bv) || LOADING.contains(bv)) {
                return null;
            }
            File cf = cacheFile(bv);
            if (cf != null && cf.isFile()) {
                try {
                    BufferedImage img = ImageIO.read(cf);
                    if (img != null) {
                        ImageIcon scaled = new ImageIcon(scale(img, w, h));
                        MEM.put(bv, scaled);
                        return scaled;
                    }
                } catch (Throwable ignore) {
                }
            }
            String url = urls.get(bv);
            if (url == null) {
                FAILED.add(bv);
                return null;
            }
            LOADING.add(bv);
            POOL.submit(() -> {
                try {
                    byte[] data = fetch(url);
                    BufferedImage img = data == null ? null : ImageIO.read(new ByteArrayInputStream(data));
                    if (img != null) {
                        File cf2 = cacheFile(bv);
                        if (cf2 != null) {
                            try {
                                ImageIO.write(img, "jpg", cf2);
                            } catch (Throwable ignore) {
                            }
                        }
                        synchronized (CoverStore.class) {
                            MEM.put(bv, new ImageIcon(scale(img, w, h)));
                        }
                    } else {
                        FAILED.add(bv);
                    }
                } catch (Throwable t) {
                    FAILED.add(bv);
                } finally {
                    LOADING.remove(bv);
                    SwingUtilities.invokeLater(onLoaded);
                }
            });
            return null;
        }

        private static File cacheFile(String bv) {
            if (cacheDir == null) {
                return null;
            }
            return new File(cacheDir, bv + ".jpg");
        }

        private static byte[] fetch(String url) {
            String u = url.startsWith("http://") ? "https://" + url.substring(7) : url;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                    c.setConnectTimeout(6000);
                    c.setReadTimeout(12000);
                    c.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36");
                    c.setRequestProperty("Referer", "https://www.bilibili.com/");
                    if (c.getResponseCode() != 200) {
                        continue;
                    }
                    try (InputStream in = c.getInputStream();
                         ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            bos.write(buf, 0, n);
                        }
                        return bos.toByteArray();
                    }
                } catch (Throwable ignore) {
                    // 重试一次
                }
            }
            return null;
        }

        /** 按目标比例居中裁切后缩放，不变形 */
        private static BufferedImage scale(BufferedImage src, int w, int h) {
            double target = (double) w / h;
            double cur = (double) src.getWidth() / src.getHeight();
            int cw = src.getWidth();
            int ch = src.getHeight();
            if (cur > target) {
                cw = (int) Math.round(src.getHeight() * target);
            } else {
                ch = (int) Math.round(src.getWidth() / target);
            }
            int x = (src.getWidth() - cw) / 2;
            int y = (src.getHeight() - ch) / 2;
            BufferedImage cut = src.getSubimage(x, y, cw, ch);
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(cut, 0, 0, w, h, null);
            g.dispose();
            return out;
        }
    }

    /* ================= 表格模型 ================= */
    static class CsvModel extends AbstractTableModel {

        private static final long serialVersionUID = 1L;

        private String[][] view = new String[0][];
        final List<String[]> rows = new ArrayList<>();
        final List<String[]> rawRows = new ArrayList<>();

        void setRows(String[][] view, String[][] raw) {
            this.view = view;
            rows.clear();
            rawRows.clear();
            for (String[] r : view) {
                rows.add(r);
            }
            for (String[] r : raw) {
                rawRows.add(r);
            }
            fireTableDataChanged();
        }

        public int getRowCount() {
            return view.length;
        }

        public int getColumnCount() {
            return COLS.length;
        }

        public String getColumnName(int column) {
            return COLS[column];
        }

        public Object getValueAt(int rowIndex, int columnIndex) {
            if (rowIndex < 0 || rowIndex >= view.length) {
                return "";
            }
            String[] r = view[rowIndex];
            return columnIndex < r.length ? r[columnIndex] : "";
        }

        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return false;
        }
    }
}
