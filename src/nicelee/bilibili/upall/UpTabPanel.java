package nicelee.bilibili.upall;

import java.awt.BorderLayout;

import javax.swing.JFrame;
import javax.swing.JPanel;

/**
 * 「UP主批量」标签页：直接把拉取界面（输入、排序、过滤、勾选、入队）内嵌进来，
 * 不需要再弹独立窗口。
 */
public class UpTabPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final UpVideoPanel inner = new UpVideoPanel();

    public UpTabPanel(JFrame frame) {
        setLayout(new BorderLayout());
        add(inner, BorderLayout.CENTER);
    }
}
