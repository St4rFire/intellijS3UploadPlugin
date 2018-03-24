package com.openmind.intellij.helper;

import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.util.AlarmFactory;


/**
 * Handle notifications in editor
 */
public class NotificationHelper
{

    private static final String NOTIFICATION_TITLE = "S3UploadPlugin";
    private static final NotificationGroup NOTIFICATION_GROUP = NotificationGroup.balloonGroup("S3UploadPlugin-balloon-notifications");
    private static final NotificationGroup NOTIFICATION_GROUP_LOG_ONLY = NotificationGroup.logOnlyGroup("S3UploadPlugin-balloon-notifications-log-only");

    /**
     * Show message in event log and baloon
     * @param project
     * @param html
     * @param notificationType
     */
    public static void showEventAndBalloon(@NotNull Project project, @NotNull String html, @NotNull NotificationType notificationType) {
        show(project, html, notificationType, true);
    }

    /**
     * Show message in event log
     * @param project
     * @param html
     * @param notificationType
     */
    public static void showEvent(@NotNull Project project, @NotNull String html, @NotNull NotificationType notificationType) {
        show(project, html, notificationType, false);
    }

    /**
     * Show message in event log and baloon (optional)
     * @param project
     * @param html
     * @param notificationType
     * @param showBalloon
     */
    private static void show(@NotNull Project project, @NotNull String html, @NotNull NotificationType notificationType, boolean showBalloon) {
        ApplicationManager.getApplication().invokeLater(() -> {

            NotificationGroup group = showBalloon ? NOTIFICATION_GROUP : NOTIFICATION_GROUP_LOG_ONLY;
            Notification notification =  group.createNotification(NOTIFICATION_TITLE, html, notificationType, null);
            notification.notify(project);

            if(showBalloon) {
                AlarmFactory.getInstance().create().addRequest(
                    notification::expire,
                    TimeUnit.SECONDS.toMillis(10)
                );
            }
        });
    }
}
