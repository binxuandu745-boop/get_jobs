package com.getjobs.worker.service;

import com.getjobs.worker.boss.BossTaskPausedException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.AWTError;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;

/** Desktop notification for a protective pause; acknowledging it does not resume the task. */
@Slf4j
@Component
public class BossPauseNotifier {
    @Value("${getjobs.boss.pause-desktop-alert-enabled:true}")
    private boolean enabled = true;

    private volatile boolean destroyed;
    // The window is created, read, updated and disposed only on the Swing event thread.
    private AlertWindow alertWindow;

    public void notifyPaused(BossTaskPausedException.Reason reason, String message) {
        if (!enabled || destroyed || reason == null) return;
        try {
            if (isHeadless()) {
                log.debug("跳过 Boss 暂停桌面提醒：当前运行环境没有图形桌面");
                return;
            }
            runOnEventDispatchThread(() -> showPaused(reason, message));
        } catch (RuntimeException | AWTError | LinkageError e) {
            log.warn("Boss 暂停桌面提醒失败，任务保持暂停", e);
        }
    }

    private void showPaused(BossTaskPausedException.Reason reason, String message) {
        if (destroyed) return;
        try {
            boolean newWindow = alertWindow == null || !alertWindow.isDisplayable();
            if (newWindow) alertWindow = createAlertWindow();
            alertWindow.show(titleFor(reason), readableMessage(reason, message));
            if (newWindow) {
                try {
                    beep();
                } catch (RuntimeException | AWTError | LinkageError e) {
                    log.warn("Boss 暂停桌面提醒提示音失败", e);
                }
            }
        } catch (RuntimeException | AWTError | LinkageError e) {
            log.warn("Boss 暂停桌面提醒显示失败，任务保持暂停", e);
            disposeAlertWindow();
        }
    }

    @PreDestroy
    public void destroy() {
        destroyed = true;
        try {
            runOnEventDispatchThread(this::disposeAlertWindow);
        } catch (RuntimeException | AWTError | LinkageError e) {
            log.warn("Boss 暂停桌面提醒窗口清理失败", e);
        }
    }

    private void disposeAlertWindow() {
        AlertWindow window = alertWindow;
        alertWindow = null;
        if (window == null) return;
        try {
            window.dispose();
        } catch (RuntimeException | AWTError | LinkageError e) {
            log.warn("Boss 暂停桌面提醒窗口关闭失败", e);
        }
    }

    protected boolean isHeadless() {
        return GraphicsEnvironment.isHeadless();
    }

    protected void runOnEventDispatchThread(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run();
        else SwingUtilities.invokeLater(action);
    }

    protected AlertWindow createAlertWindow() {
        return new SwingAlertWindow();
    }

    protected void beep() {
        Toolkit.getDefaultToolkit().beep();
    }

    private static String titleFor(BossTaskPausedException.Reason reason) {
        return switch (reason) {
            case SECURITY_VERIFICATION -> "Boss 任务已暂停：需要安全验证";
            case JOB_DETAILS_UNAVAILABLE -> "Boss 任务已暂停：页面读取异常";
        };
    }

    private static String readableMessage(BossTaskPausedException.Reason reason, String message) {
        if (message != null && !message.isBlank()) return message;
        return switch (reason) {
            case SECURITY_VERIFICATION -> "请在 Boss 浏览器手动完成安全验证，确认后再手动开始任务。";
            case JOB_DETAILS_UNAVAILABLE -> "未能读取岗位详情，请检查 Boss 浏览器页面，确认后再手动开始任务。";
        };
    }

    // A narrow window adapter lets tests verify notification behavior without desktop windows or sound.
    interface AlertWindow {
        boolean isDisplayable();

        void show(String title, String message);

        void dispose();
    }

    private static final class SwingAlertWindow implements AlertWindow {
        private final JDialog dialog;
        private final JTextArea messageArea;

        private SwingAlertWindow() {
            dialog = new JDialog((Frame) null, "", Dialog.ModalityType.MODELESS);
            messageArea = new JTextArea(6, 40);
            try {
                dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                dialog.setAlwaysOnTop(true);
                messageArea.setEditable(false);
                messageArea.setLineWrap(true);
                messageArea.setWrapStyleWord(true);

                JPanel content = new JPanel(new BorderLayout(0, 16));
                content.setBorder(BorderFactory.createEmptyBorder(20, 20, 16, 20));
                content.add(new JScrollPane(messageArea), BorderLayout.CENTER);

                JButton acknowledge = new JButton("知道了");
                acknowledge.addActionListener(event -> dialog.dispose());
                JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
                actions.add(acknowledge);
                content.add(actions, BorderLayout.SOUTH);
                dialog.setContentPane(content);
                dialog.getRootPane().setDefaultButton(acknowledge);
                dialog.setMinimumSize(new Dimension(480, 240));
                dialog.pack();
                dialog.setLocationRelativeTo(null);
            } catch (RuntimeException | AWTError | LinkageError e) {
                try {
                    dialog.dispose();
                } catch (RuntimeException | AWTError | LinkageError closeFailure) {
                    e.addSuppressed(closeFailure);
                }
                throw e;
            }
        }

        @Override
        public boolean isDisplayable() {
            return dialog.isDisplayable();
        }

        @Override
        public void show(String title, String message) {
            dialog.setTitle(title);
            messageArea.setText(message);
            messageArea.setCaretPosition(0);
            if (!dialog.isVisible()) dialog.setVisible(true);
            dialog.toFront();
        }

        @Override
        public void dispose() {
            dialog.dispose();
        }
    }
}
