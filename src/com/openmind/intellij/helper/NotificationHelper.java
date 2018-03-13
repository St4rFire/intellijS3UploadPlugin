package com.openmind.intellij.helper;

import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.AlarmFactory;


/**
 * Handle notifications in editor
 */
public class NotificationHelper
{

    private static final String GROUP_DISPLAY_ID = "S3UploadPlugin-balloon-notifications";
    private static final String NOTIFICATION_TITLE = "S3UploadPlugin";

    /**
     * Show message in event log and baloon
     * @param html
     * @param notificationType
     */
    public static void showEventAndBalloon(@NotNull String html, @NotNull NotificationType notificationType) {
        show(html, notificationType, false);
    }

    /**
     * Show message in event log
     * @param html
     * @param notificationType
     */
    public static void showEvent(@NotNull String html, @NotNull NotificationType notificationType) {
        show(html, notificationType, true);
    }

    /**
     * Show message in event log and baloon (optional)
     * @param html
     * @param notificationType
     * @param hideBalloon
     */
    private static void show(@NotNull String html, @NotNull NotificationType notificationType, boolean hideBalloon) {
        ApplicationManager.getApplication().invokeLater(() -> {
            Notification notification = new Notification(GROUP_DISPLAY_ID, NOTIFICATION_TITLE, html, notificationType);
            Notifications.Bus.notify(notification);

            if (hideBalloon && notification.getBalloon() != null)
            {
                notification.getBalloon().hide();
            }

            AlarmFactory.getInstance().create().addRequest(
                notification::expire,
                TimeUnit.SECONDS.toMillis(10)
            );
        });
    }
}
