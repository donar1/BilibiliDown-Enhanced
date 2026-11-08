package nicelee.bilibili.upall;

import java.lang.reflect.Field;

import javax.swing.JFrame;
import javax.swing.JTabbedPane;

/**
 * 把「下载记录」「UP主批量」「补采清单」三个入口做成标签页，加在「主页 / 下载页」同一行的后面，
 * 不额外占用菜单栏，也不改原有页签。
 *
 * 取不到 tabbedPane 时退回菜单栏方案，保证入口不会丢。
 */
public final class TabInjectHook {

    private TabInjectHook() {
    }

    public static void install(final JFrame frame) {
        JTabbedPane tabs = tabsOf(frame);

        if (tabs == null) {
            UpVideoMenuHook.install(frame);
            LedgerMenuHook.install(frame);
            return;
        }

        boolean changed = false;
        if (indexOf(tabs, "下载记录") < 0) {
            tabs.addTab("下载记录", new LedgerPanel(frame));
            changed = true;
        }
        if (indexOf(tabs, "UP主批量") < 0) {
            tabs.addTab("UP主批量", new UpTabPanel(frame));
            changed = true;
        }
        if (indexOf(tabs, "补采清单") < 0) {
            tabs.addTab("补采清单", new MakeupPanel(frame));
            changed = true;
        }
        if (changed) {
            tabs.revalidate();
            tabs.repaint();
        }
        writeTabsProbe(tabs);
    }

    /** 把当前实际页签写进 记录工具\.tabs.txt，便于确认注入结果 */
    private static void writeTabsProbe(JTabbedPane tabs) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tabs.getTabCount(); i++) {
                if (i > 0) {
                    sb.append(" | ");
                }
                sb.append(tabs.getTitleAt(i));
            }
            java.io.File f = new java.io.File(LedgerMenuHook.toolDir(), ".tabs.txt");
            java.nio.file.Files.write(f.toPath(), sb.toString().getBytes("UTF-8"));
        } catch (Throwable ignore) {
        }
    }

    private static int indexOf(JTabbedPane tabs, String title) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (title.equals(tabs.getTitleAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static JTabbedPane tabsOf(JFrame frame) {
        try {
            Field f = frame.getClass().getDeclaredField("jTabbedpane");
            f.setAccessible(true);
            Object v = f.get(frame);
            if (v instanceof JTabbedPane) {
                return (JTabbedPane) v;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
