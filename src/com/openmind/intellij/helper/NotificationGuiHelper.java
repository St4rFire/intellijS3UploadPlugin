package com.openmind.intellij.helper;

import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.util.AlarmFactory;


public class NotificationGuiHelper {

    private static final String GROUP_DISPLAY_ID = "S3UploadPlugin balloon notifications";
    private static final String NOTIFICATION_TITLE = "S3UploadPlugin";

    public static void showEventAndBaloon(@NotNull String html, @NotNull NotificationType notificationType) {
        show(html, notificationType, false);
    }

    public static void showEvent(@NotNull String html, @NotNull NotificationType notificationType) {
        show(html, notificationType, true);
    }

    private static void show(@NotNull String html, @NotNull NotificationType notificationType, boolean hideBaloon) {

        Notification notification = new Notification(GROUP_DISPLAY_ID, NOTIFICATION_TITLE, html, notificationType);
        Notifications.Bus.notify(notification);

        if (hideBaloon) {
            notification.getBalloon().hide();
        }

        AlarmFactory.getInstance().create().addRequest(
            notification::expire,
            TimeUnit.SECONDS.toMillis(10)
        );
    }
}
