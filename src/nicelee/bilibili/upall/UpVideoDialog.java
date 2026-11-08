package nicelee.bilibili.upall;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;

import javax.swing.JDialog;
import javax.swing.SwingUtilities;

/**
 * 弹窗壳：内容全在 UpVideoPanel 里，这里只负责把它装进窗口。
 * 界面里主入口已经改成标签页，弹窗保留给菜单回退路径和外部调用。
 */
public class UpVideoDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final UpVideoPanel panel = new UpVideoPanel();

    public UpVideoDialog(java.awt.Frame owner) {
        super(owner, "UP 主全部投稿", false);
        setLayout(new BorderLayout());
        add(panel, BorderLayout.CENTER);
        setPreferredSize(new Dimension(1080, 620));
        pack();
        setLocationRelativeTo(owner);
    }

    public static void showDialog(Component parent) {
        java.awt.Frame owner = (java.awt.Frame) SwingUtilities.getWindowAncestor(parent);
        new UpVideoDialog(owner).setVisible(true);
    }

    /** 弹窗并直接把输入塞进去开始拉 */
    public static UpVideoDialog showDialogAndFetch(Component parent, String input) {
        java.awt.Frame owner = (java.awt.Frame) SwingUtilities.getWindowAncestor(parent);
        UpVideoDialog dlg = new UpVideoDialog(owner);
        dlg.setVisible(true);
        if (input != null && input.trim().length() > 0) {
            dlg.setInputAndFetch(input.trim());
        }
        return dlg;
    }

    public void setInputAndFetch(String input) {
        panel.setInputAndFetch(input);
    }
}
