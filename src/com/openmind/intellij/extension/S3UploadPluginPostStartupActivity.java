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
import com.openmind.intellij.bean.UploadInfo;
import com.openmind.intellij.helper.AmazonS3Helper;
import com.openmind.intellij.helper.NotificationGuiHelper;


public class S3UploadPluginPostStartupActivity implements StartupActivity {


    public void runActivity(@NotNull Project project) {

        ActionManager am = ActionManager.getInstance();
        DefaultActionGroup group = (DefaultActionGroup) am.getAction("S3UploadPlugin.Menu");

        if (group == null) {
            return;
        }

        AmazonS3Helper amazonS3Helper;
        try
        {
            amazonS3Helper = new AmazonS3Helper(project);
        }
        catch (IllegalArgumentException e)
        {
            NotificationGuiHelper.showEvent("S3UploadPlugin disabled: " + e.getMessage(), INFORMATION);
            return;
        }

        // add actions
        List<UploadInfo> uploadInfos = amazonS3Helper.getVersionFiles().stream()
            .map(v -> new UploadInfo(v))
            .collect(Collectors.toList());

        for (UploadInfo uploadInfo : uploadInfos) {
            AnAction action = new UploadFileToS3Action(uploadInfo, amazonS3Helper);
            am.registerAction("S3UploadPlugin.UploadAction" + uploadInfo.getFileName(), action);
            group.add(action);
        }

        // add ScrollToClassFileAction
        AnAction scrollToClassFileAction = new ScrollToClassFileAction("Scroll to .class");
        am.registerAction("S3UploadPlugin.ScrollToClassFile", scrollToClassFileAction);
        group.add(scrollToClassFileAction);

        NotificationGuiHelper.showEvent("S3UploadPlugin ready", INFORMATION);
    }
}