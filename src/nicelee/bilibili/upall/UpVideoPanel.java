package nicelee.bilibili.upall;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.table.TableRowSorter;

import nicelee.bilibili.util.HttpRequestUtil;

/**
 * 「UP 主全部投稿」窗口：拉取 → 列表（可排序/过滤/勾选）→ 批量加入下载队列。
 *
 * 接入方式（FrameMain 里加菜单项即可，不影响现有界面）：
 *   JMenuItem miUpAll = new JMenuItem("UP主全部投稿");
 *   miUpAll.addActionListener(e -> UpVideoDialog.showDialog(FrameMain.this));
 */
public class UpVideoPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final JTextField txtInput = new JTextField(28);
    private final JCheckBox chkResume = new JCheckBox("断点续拉", true);
    private final JComboBox<String> cmbOrder = new JComboBox<>(new String[]{"最新发布", "最多播放", "最多收藏"});
    private final JComboBox<String> cmbQn = new JComboBox<>(new String[]{"默认清晰度", "1080P", "720P", "480P", "4K", "仅第1P"});
    private final JTextField txtFilter = new JTextField(12);
    private final UpVideoTableModel model = new UpVideoTableModel();
    private final JTable table = new JTable(model);
    private final TableRowSorter<UpVideoTableModel> sorter = new TableRowSorter<>(model);
    private final JLabel lbStatus = new JLabel("就绪");
    private final JButton btnFetch = new JButton("一键拉取");
    private final JButton btnEnqueue = new JButton("加入下载队列");

    private volatile boolean cancelled;
    private volatile Thread fetchThread;

    public UpVideoPanel() {
        initUI();
    }

    private void initUI() {
        setLayout(new BorderLayout(6, 6));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        top.add(new JLabel("UID / 空间链接 / UP主昵称:"));
        top.add(txtInput);
        top.add(btnFetch);
        top.add(chkResume);
        top.add(cmbOrder);
        top.add(cmbQn);
        top.add(new JLabel("过滤:"));
        top.add(txtFilter);

        table.setAutoCreateRowSorter(true);
        table.setRowSorter(sorter);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setRowHeight(24);
        table.getColumnModel().getColumn(UpVideoTableModel.COL_TITLE).setPreferredWidth(360);
        table.getColumnModel().getColumn(UpVideoTableModel.COL_BVID).setPreferredWidth(110);
        sorter.setSortKeys(java.util.Collections.singletonList(
                new javax.swing.RowSorter.SortKey(UpVideoTableModel.COL_PUBDATE, javax.swing.SortOrder.DESCENDING)));

        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton btnAll = new JButton("全选");
        JButton btnInvert = new JButton("反选");
        JButton btnClear = new JButton("清空勾选");
        JButton btnClearList = new JButton("清空列表");
        final JTextField txtFrom = new JTextField(3);
        final JTextField txtTo = new JTextField(3);
        JButton btnRange = new JButton("按页码勾选");
        tools.add(btnAll);
        tools.add(btnInvert);
        tools.add(btnClear);
        tools.add(btnClearList);
        tools.add(new JLabel("  页"));
        tools.add(txtFrom);
        tools.add(new JLabel("~"));
        tools.add(txtTo);
        tools.add(btnRange);
        tools.add(btnEnqueue);

        JPanel bottom = new JPanel(new GridLayout(2, 1));
        bottom.add(tools);
        bottom.add(lbStatus);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        txtFilter.addActionListener(e -> applyFilter());
        btnAll.addActionListener(e -> table.selectAll());
        btnInvert.addActionListener(e -> invertSelection());
        btnClear.addActionListener(e -> table.clearSelection());
        btnClearList.addActionListener(e -> clearList());
        btnRange.addActionListener(e -> selectByPageRange(txtFrom.getText(), txtTo.getText()));
        btnFetch.addActionListener(e -> startFetch());
        btnEnqueue.addActionListener(e -> startEnqueue());
        btnFetch.setActionCommand("fetch");
    }

    // ---------------- 清空列表 ----------------

    /** 清空当前投稿列表：只清界面数据，不动断点文件，避免误删续传进度 */
    private void clearList() {
        int n = model.getRowCount();
        if (n == 0) {
            lbStatus.setText("列表已经是空的。");
            return;
        }
        int r = JOptionPane.showConfirmDialog(this,
                "清空当前投稿列表？\n共 " + n + " 条显示记录。\n\n"
                        + "只清空界面列表，不会删除 config\\upall 下的断点文件；\n"
                        + "清空后再点「一键拉取」，仍会从断点续拉。",
                "清空列表", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (r != JOptionPane.OK_OPTION) {
            return;
        }
        model.clear();
        table.clearSelection();
        lbStatus.setText("列表已清空（" + n + " 条）。断点保留在 config\\upall，重新拉取会从断点续拉；"
                + "要彻底从第 1 页重来，删除对应的 checkpoint_*.json 即可。");
    }

    // ---------------- 拉取 ----------------

    private void startFetch() {
        if (fetchThread != null && fetchThread.isAlive()) {
            cancelled = true;
            lbStatus.setText("正在取消...");
            return;
        }
        final String input = txtInput.getText().trim();
        if (input.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入 UID、空间链接或 UP 主昵称");
            return;
        }
        model.clear();
        table.clearSelection();
        cancelled = false;
        btnFetch.setText("取消");

        fetchThread = new Thread(() -> {
            try {
                long mid = UpMidResolver.parseMid(input);
                if (mid < 0) {
                    mid = resolveMidBySearch(input);
                    if (mid < 0) {
                        setStatus("未选择任何 UP 主");
                        return;
                    }
                }
                UpFetchOptions options = UpFetchOptions.fromConfig();
                options.setOrder(orderKey());
                setStatus("开始拉取 UID " + mid);
                UpFetchService service = new UpFetchService(new UpSpaceApi(new HttpRequestUtil()), options);
                service.fetchAll(mid, chkResume.isSelected(), new UpFetchService.Listener() {
                    @Override
                    public void onMessage(String msg) {
                        setStatus(msg);
                    }

                    @Override
                    public void onPageFetched(UpPageResult page, int accumulated) {
                        SwingUtilities.invokeLater(() -> {
                            model.appendItems(page.getItems());
                            lbStatus.setText(String.format("已拉 %d 条（接口共 %d 条）",
                                    accumulated, page.getTotalCount()));
                        });
                    }

                    @Override
                    public void onFinished(final List<UpVideoItem> items, final boolean resumed) {
                        // 断点续拉命中缓存时不会有 onPageFetched，这里兜底填表
                        SwingUtilities.invokeLater(() -> {
                            if (model.getRowCount() == 0 && items != null && !items.isEmpty()) {
                                model.setItems(items);
                            }
                            setStatus(String.format("完成：共 %d 条%s", items == null ? 0 : items.size(),
                                    resumed ? "（含断点数据）" : ""));
                        });
                    }

                    @Override
                    public void onError(Throwable e) {
                        setStatus("失败：" + e.getMessage());
                    }

                    @Override
                    public boolean isCancelled() {
                        return cancelled;
                    }
                });
            } catch (Throwable t) {
                setStatus("异常：" + t.getMessage());
            } finally {
                SwingUtilities.invokeLater(() -> btnFetch.setText("一键拉取"));
            }
        }, "upall-fetch");
        fetchThread.setDaemon(true);
        fetchThread.start();
    }

    @SuppressWarnings("unchecked")
    private long resolveMidBySearch(String keyword) {
        try {
            List<UpMidResolver.UpUserHit> hits = new UpMidResolver(new HttpRequestUtil()).searchUser(keyword);
            if (hits.isEmpty()) {
                setStatus("没搜到该 UP 主");
                return -1;
            }
            if (hits.size() == 1) {
                return hits.get(0).mid;
            }
            Object[] options = hits.toArray();
            Object chosen = JOptionPane.showInputDialog(this, "请选择 UP 主", "搜索结果",
                    JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
            if (chosen == null) {
                return -1;
            }
            return ((UpMidResolver.UpUserHit) chosen).mid;
        } catch (Throwable t) {
            setStatus("搜索失败：" + t.getMessage());
            return -1;
        }
    }

    private String orderKey() {
        switch (cmbOrder.getSelectedIndex()) {
            case 1:
                return UpSpaceApi.ORDER_CLICK;
            case 2:
                return UpSpaceApi.ORDER_STOW;
            default:
                return UpSpaceApi.ORDER_PUBDATE;
        }
    }

    private void setStatus(String msg) {
        SwingUtilities.invokeLater(() -> lbStatus.setText(msg));
    }

    // ---------------- 勾选 ----------------

    private List<UpVideoItem> selectedItems() {
        List<UpVideoItem> list = new ArrayList<>();
        int[] viewRows = table.getSelectedRows();
        for (int viewRow : viewRows) {
            int modelRow = table.convertRowIndexToModel(viewRow);
            UpVideoItem item = model.getItem(modelRow);
            if (!list.contains(item)) {
                list.add(item);
            }
        }
        return list;
    }

    private void invertSelection() {
        int rowCount = table.getRowCount();
        int[] selected = table.getSelectedRows();
        boolean[] isSel = new boolean[rowCount];
        for (int r : selected) {
            if (r >= 0 && r < rowCount) {
                isSel[r] = true;
            }
        }
        table.clearSelection();
        for (int r = 0; r < rowCount; r++) {
            if (!isSel[r]) {
                table.addRowSelectionInterval(r, r);
            }
        }
    }

    private void selectByPageRange(String fromText, String toText) {
        int from;
        int to;
        try {
            from = Integer.parseInt(fromText.trim());
            to = Integer.parseInt(toText.trim());
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "页码请填整数");
            return;
        }
        if (from > to) {
            int t = from;
            from = to;
            to = t;
        }
        table.clearSelection();
        int rowCount = table.getRowCount();
        for (int viewRow = 0; viewRow < rowCount; viewRow++) {
            int modelRow = table.convertRowIndexToModel(viewRow);
            int listPage = model.getItem(modelRow).getListPage();
            if (listPage >= from && listPage <= to) {
                table.addRowSelectionInterval(viewRow, viewRow);
            }
        }
        lbStatus.setText("已按页 " + from + "~" + to + " 勾选 " + table.getSelectedRowCount() + " 条");
    }

    private void applyFilter() {
        String text = txtFilter.getText().trim();
        if (text.isEmpty()) {
            sorter.setRowFilter(null);
        } else {
            sorter.setRowFilter(RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(text),
                    UpVideoTableModel.COL_TITLE, UpVideoTableModel.COL_BVID));
        }
    }

    // ---------------- 入队 ----------------

    private void startEnqueue() {
        final List<UpVideoItem> items = selectedItems();
        if (items.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请先勾选要下载的投稿");
            return;
        }
        final int qn = qnOfSelection();
        final boolean allPages = cmbQn.getSelectedIndex() != 5;
        btnEnqueue.setEnabled(false);
        lbStatus.setText("正在投递 " + items.size() + " 条...");
        new Thread(() -> {
            int n = UpDownloadBridge.enqueue(items, qn, allPages, (done, total, msg) -> {
                SwingUtilities.invokeLater(() -> lbStatus.setText(
                        String.format("投递中 %d/%d：%s", done, total, msg)));
            });
            SwingUtilities.invokeLater(() -> {
                lbStatus.setText("已加入下载队列，共 " + n + " 个任务");
                btnEnqueue.setEnabled(true);
            });
        }, "upall-enqueue").start();
    }

    private int qnOfSelection() {
        switch (cmbQn.getSelectedIndex()) {
            case 1:
                return 80;
            case 2:
                return 64;
            case 3:
                return 32;
            case 4:
                return 120;
            default:
                return 0;
        }
    }

    /** 供外部（如菜单/命令行）直接注入输入并触发拉取 */
    public void setInputAndFetch(String input) {
        txtInput.setText(input);
        startFetch();
    }
}
