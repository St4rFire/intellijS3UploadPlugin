package com.openmind.intellij.extension;

import static com.intellij.notification.NotificationType.ERROR;
import static com.intellij.notification.NotificationType.INFORMATION;

import org.jetbrains.annotations.NotNull;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.openmind.intellij.action.UploadFileToS3Action;
import com.openmind.intellij.bean.UploadConfig;
import com.openmind.intellij.service.AmazonS3Service;
import com.openmind.intellij.helper.NotificationHelper;


/**
 * Setup on editor startup
 */
public class S3UploadPluginPostStartupActivity implements StartupActivity {


    public void runActivity(@NotNull Project project) {

        ActionManager am = ActionManager.getInstance();
        DefaultActionGroup group = (DefaultActionGroup) am.getAction("S3UploadPlugin.Menu");
        if (group == null) {
            return;
        }

        // NotificationHelper.showEvent("num test '" + group.getChildrenCount() + "'", INFORMATION);

        try {
            // setup S3 service
            AmazonS3Service amazonS3Service = ServiceManager.getService(project, AmazonS3Service.class);

            // add actions dynamically
            for (UploadConfig uploadConfig : amazonS3Service.getUploadConfigs()) {
                UploadFileToS3Action action = new UploadFileToS3Action(uploadConfig);
                if (am.getAction(action.getActionId()) == null) {
                    am.registerAction(action.getActionId(), action);
                    group.add(action);
                }
            }

            NotificationHelper.showEvent(project, "ready! Project: '" + amazonS3Service.getProjectName() + "'",
                INFORMATION);

        } catch (IllegalArgumentException e) {
            NotificationHelper.showEvent(project, "disabled! " + e.getMessage(), ERROR);
        }

    }
}