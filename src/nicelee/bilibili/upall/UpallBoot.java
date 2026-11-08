package nicelee.bilibili.upall;

import java.awt.Window;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import nicelee.ui.FrameMain;

/**
 * 启动引导：先按原样启动 BilibiliDown 主程序，等主窗口可见后再往菜单栏追加
 * 「UP主批量」「下载记录」两个菜单。
 *
 * 这样做的好处是：**不改动 INeedBiliAV.jar 里的任何类**（零侵入），
 * 原程序逻辑、下载链路、界面全部保持原样；补丁失效时最多是菜单少两项，
 * 不会影响程序本身启动。
 */
public final class UpallBoot {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private UpallBoot() {
    }

    public static void main(final String[] args) throws Exception {
        // 注入线程先跑起来，主程序启动过程不受影响
        Thread hook = new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 600 && !INSTALLED.get(); i++) {
                    try {
                        tryInstall();
                        if (!INSTALLED.get()) {
                            Thread.sleep(250);
                        }
                    } catch (InterruptedException e) {
                        return;
                    } catch (Throwable t) {
                        // 任何注入异常都不能影响主程序
                        System.err.println("[upall] 菜单注入失败: " + t);
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e2) {
                            return;
                        }
                    }
                }
            }
        }, "upall-menu-hook");
        hook.setDaemon(true);
        hook.start();

        // 原始入口，一字未改
        FrameMain.main(args);

        // 兜底：main 返回后（窗口已 setVisible）再补一次
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                try {
                    tryInstall();
                } catch (Throwable ignored) {
                }
            }
        });

        // 下载完成监控：每批下完自动比对推送清单，缺口进补采记录
        try {
            DownloadWatch.start();
        } catch (Throwable t) {
            System.err.println("[upall] 下载监控未启动: " + t);
        }
    }

    private static synchronized void tryInstall() {
        // 先占位再派发，避免轮询线程与兜底调用各派发一次造成菜单重复
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        for (Window w : Window.getWindows()) {
            if (w instanceof FrameMain && w.isVisible()) {
                final JFrame frame = (JFrame) w;
                SwingUtilities.invokeLater(new Runnable() {
                    public void run() {
                        try {
                            TabInjectHook.install(frame);
                            if (frame.getJMenuBar() != null) {
                                frame.getJMenuBar().revalidate();
                                frame.getJMenuBar().repaint();
                            }
                            System.out.println("[upall] 菜单已注入");
                        } catch (Throwable t) {
                            System.err.println("[upall] 菜单注入异常: " + t);
                        }
                    }
                });
                return;
            }
        }
        // 没找到窗口，释放占位让轮询继续
        INSTALLED.set(false);
    }
}
