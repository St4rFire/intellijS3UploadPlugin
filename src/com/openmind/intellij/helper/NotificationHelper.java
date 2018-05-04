package com.openmind.intellij.helper;

import static com.intellij.notification.NotificationType.INFORMATION;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.util.CollectionUtils;

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
     * Show message in event log and balloon
     * @param project
     * @param html
     * @param notificationType
     */
    public static void showEventAndBalloon(@NotNull Project project, @NotNull String html, @NotNull NotificationType notificationType) {
        show(project, html, notificationType, true, null);
    }

    /**
     * Show message in event log
     * @param project
     * @param html
     * @param notificationType
     */
    public static void showEvent(@NotNull Project project, @NotNull String html, @NotNull NotificationType notificationType) {
        show(project, html, notificationType, false, null);
    }

    /**
     * Show message in event log and balloon (optional)
     * @param project
     * @param html
     * @param notificationType
     * @param showBalloon
     */
    public static void show(@NotNull Project project, @NotNull String html, @NotNull NotificationType notificationType,
        boolean showBalloon, @Nullable String notificationTitle) {

        ApplicationManager.getApplication().invokeLater(() -> {

            NotificationGroup group = showBalloon ? NOTIFICATION_GROUP : NOTIFICATION_GROUP_LOG_ONLY;
            String title = StringUtils.defaultString(notificationTitle, NOTIFICATION_TITLE);
            Notification notification =  group.createNotification(title, html, notificationType, null);
            notification.notify(project);

            if(showBalloon) {
                AlarmFactory.getInstance().create().addRequest(
                    notification::expire,
                    TimeUnit.SECONDS.toMillis(10)
                );
            }
        });
    }

    public static void scheduleRandomNotifications(@NotNull Project project, @NotNull List<String> messages, long period) {
        if (!CollectionUtils.isEmpty(messages)) {
            new Timer().schedule(new RandomNotificationTimerTask(project, messages), 0, period);
        }
    }

    private static class RandomNotificationTimerTask extends TimerTask
    {
        private final Project project;
        private final List<String> messages;
        private LinkedList<String> previousMessages;

        public RandomNotificationTimerTask(Project project, List<String> messages)
        {
            this.project = project;
            this.messages = messages;
        }

        public void run() {
            if (CollectionUtils.isEmpty(messages)) {
                return;
            }

            if (CollectionUtils.isEmpty(previousMessages)) {
                previousMessages = new LinkedList<>(messages);
            }

            Collections.shuffle(previousMessages);
            String selectedMessage = previousMessages.pop();

            NotificationHelper.show(project, selectedMessage, INFORMATION, true, StringUtils.EMPTY);
        }
    }
}
