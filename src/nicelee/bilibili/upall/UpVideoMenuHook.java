package nicelee.bilibili.upall;

import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;

/**
 * 菜单接入点：在现有 JMenuBar 末尾追加一个「UP主批量」菜单，不改动任何现有 UI 类。
 *
 * 只需要在 FrameMain 里加一行（唯一的侵入式改动，且只涉及 UI）：
 *   UpVideoMenuHook.install(frame);   // 放在 InitUI() 之后或 main() 里 frame.setVisible 之前
 */
public final class UpVideoMenuHook {

    private UpVideoMenuHook() {
    }

    public static void install(final JFrame frame) {
        JMenuBar bar = frame.getJMenuBar();
        if (bar == null) {
            bar = new JMenuBar();
            frame.setJMenuBar(bar);
        }
        // 幂等：已经挂过就不再重复追加
        for (int i = 0; i < bar.getMenuCount(); i++) {
            JMenu m = bar.getMenu(i);
            if (m != null && "UP主批量".equals(m.getText())) {
                return;
            }
        }
        JMenu menu = new JMenu("UP主批量");
        JMenuItem item = new JMenuItem("获取 UP 主全部投稿…");
        item.addActionListener(e -> UpVideoDialog.showDialog(frame));
        menu.add(item);
        bar.add(menu);
        bar.revalidate();
        bar.repaint();
    }
}
