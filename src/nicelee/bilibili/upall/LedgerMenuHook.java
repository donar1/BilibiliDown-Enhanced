package nicelee.bilibili.upall;

import java.awt.Desktop;
import java.io.File;

import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;

/**
 * 「下载记录」菜单：把记录工具的同步脚本和产物接进主界面。
 *
 * 三项：
 *   同步并打开台账   -> 调用 记录工具\同步下载记录.bat（脚本自己会同步并打开网页）
 *   打开台账网页     -> 打开 记录工具\下载记录.html
 *   打开台账表格     -> 打开 记录工具\下载记录.csv（Excel 筛选）
 */
public final class LedgerMenuHook {

    private static final String MENU_TEXT = "下载记录";
    private static final String TOOL_DIR = "记录工具";
    private static final String BAT = "同步下载记录.bat";
    private static final String HTML = "下载记录.html";
    private static final String CSV = "下载记录.csv";

    private LedgerMenuHook() {
    }

    public static void install(final JFrame frame) {
        JMenuBar bar = frame.getJMenuBar();
        if (bar == null) {
            return;
        }
        if (findMenu(bar) != null) {
            return; // 已注入过
        }

        JMenu menu = new JMenu(MENU_TEXT);

        JMenuItem syncItem = new JMenuItem("同步并打开台账");
        syncItem.addActionListener(e -> doSync(frame));
        menu.add(syncItem);

        JMenuItem openHtml = new JMenuItem("打开台账网页");
        openHtml.addActionListener(e -> openFile(frame, new File(toolDir(), HTML)));
        menu.add(openHtml);

        JMenuItem openCsv = new JMenuItem("打开台账表格 CSV");
        openCsv.addActionListener(e -> openFile(frame, new File(toolDir(), CSV)));
        menu.add(openCsv);

        bar.add(menu);
        bar.revalidate();
        bar.repaint();
    }

    private static JMenu findMenu(JMenuBar bar) {
        for (int i = 0; i < bar.getMenuCount(); i++) {
            JMenu m = bar.getMenu(i);
            if (m != null && MENU_TEXT.equals(m.getText())) {
                return m;
            }
        }
        return null;
    }

    /** 程序根目录：优先用程序自己的资源路径，取不到就退回工作目录 */
    static File appDir() {
        String base = null;
        try {
            base = nicelee.bilibili.util.ResourcesUtil.baseDirectory();
        } catch (Throwable ignored) {
        }
        File dir = null;
        if (base != null && base.trim().length() > 0) {
            dir = new File(base.trim());
        }
        if (dir == null || !dir.isDirectory()) {
            dir = new File(System.getProperty("user.dir", "."));
        }
        return dir;
    }

    static File toolDir() {
        return new File(appDir(), TOOL_DIR);
    }

    static void doSync(JFrame frame) {
        File bat = new File(toolDir(), BAT);
        if (!bat.isFile()) {
            JOptionPane.showMessageDialog(frame,
                    "找不到同步脚本：\n" + bat.getAbsolutePath(),
                    "下载记录", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            new ProcessBuilder("cmd.exe", "/c", bat.getAbsolutePath())
                    .directory(appDir())
                    .start();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame,
                    "启动同步脚本失败：" + ex.getMessage(),
                    "下载记录", JOptionPane.ERROR_MESSAGE);
        }
    }

    static void openHtml(JFrame frame) {
        openFile(frame, new File(toolDir(), HTML));
    }

    static void openCsv(JFrame frame) {
        openFile(frame, new File(toolDir(), CSV));
    }

    private static void openFile(JFrame frame, File f) {
        if (!f.isFile()) {
            JOptionPane.showMessageDialog(frame,
                    "还没有生成：" + f.getName() + "\n请先点「同步并打开台账」。",
                    "下载记录", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(f);
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            new ProcessBuilder("cmd.exe", "/c", "start", "", f.getAbsolutePath()).start();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame,
                    "打开文件失败：" + ex.getMessage(),
                    "下载记录", JOptionPane.ERROR_MESSAGE);
        }
    }
}
