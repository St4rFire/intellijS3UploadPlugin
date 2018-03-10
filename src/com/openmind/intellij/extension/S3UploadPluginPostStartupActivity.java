package com.openmind.intellij.extension;

import static com.intellij.notification.NotificationType.INFORMATION;

import java.util.List;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.openmind.intellij.action.ScrollToClassFileAction;
import com.openmind.intellij.action.UploadFileToS3Action;
import com.openmind.intellij.bean.UploadConfig;
import com.openmind.intellij.service.AmazonS3Service;
import com.openmind.intellij.helper.NotificationGuiHelper;


public class S3UploadPluginPostStartupActivity implements StartupActivity {


    public void runActivity(@NotNull Project project) {

        ActionManager am = ActionManager.getInstance();
        DefaultActionGroup group = (DefaultActionGroup) am.getAction("S3UploadPlugin.Menu");

        if (group == null) {
            return;
        }

        AmazonS3Service amazonS3Service;
        try {
            amazonS3Service = new AmazonS3Service(project);

        } catch (IllegalArgumentException e) {
            NotificationGuiHelper.showEvent("S3UploadPlugin disabled: " + e.getMessage(), INFORMATION);
            return;
        }

        // add actions
        for (UploadConfig uploadConfig : amazonS3Service.getUploadConfigs()) {
            AnAction action = new UploadFileToS3Action(uploadConfig, amazonS3Service);
            am.registerAction("S3UploadPlugin.UploadAction" + uploadConfig.getFileName(), action);
            group.add(action);
        }

        // add ScrollToClassFileAction
        AnAction scrollToClassFileAction = new ScrollToClassFileAction("Scroll to .class");
        am.registerAction("S3UploadPlugin.ScrollToClassFile", scrollToClassFileAction);
        group.add(scrollToClassFileAction);

        NotificationGuiHelper.showEvent("S3UploadPlugin ready", INFORMATION);
    }
}