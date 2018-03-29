package com.openmind.intellij.action;

import static com.intellij.openapi.actionSystem.LangDataKeys.MODULE_CONTEXT_ARRAY;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;

import com.google.common.collect.Lists;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.compiler.CompilerPaths;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.openmind.intellij.bean.UploadConfig;
import com.openmind.intellij.helper.FileHelper;
import com.openmind.intellij.helper.NotificationHelper;
import com.openmind.intellij.service.AmazonS3Service;

/**
 * Upload a file to s3
 */
public class UploadFileToS3Action extends AnAction implements Disposable {

    private final String actionId;
    private UploadConfig uploadConfig;

    public UploadFileToS3Action(@NotNull UploadConfig uploadConfig){
        super(uploadConfig.getFileName() + " (" + uploadConfig.getVersion() + ")", null, null);
        this.actionId = "S3UploadPlugin.UploadAction." + uploadConfig.getFileName();
        this.uploadConfig = uploadConfig;
    }

    /**
     * Menu click callback: upload to S3
     * @param event
     */
    public void actionPerformed(AnActionEvent event) {
        final Project project = event.getData(PlatformDataKeys.PROJECT);
        final Module module = event.getData(LangDataKeys.MODULE);
        final VirtualFile[] files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);

        // flatten files
        ArrayList<VirtualFile> allFiles = Lists.newArrayList();
        FileHelper.flattenAllChildren(files, allFiles);

        if (project == null || allFiles.isEmpty()) {
            NotificationHelper.showEvent(project, "Could not find any selected file!", NotificationType.ERROR);
        }

        // upload
        AmazonS3Service amazonS3Service = ServiceManager.getService(project, AmazonS3Service.class);
        amazonS3Service.uploadFiles(module, allFiles, uploadConfig);
    }

    /**
     * Handle action visibility.
     * Menu item are shared between editor instances, so only the correct ones are to be shown
     * @param event
     */
    @Override
    public void update(AnActionEvent event) {
        final Project project = event.getData(PlatformDataKeys.PROJECT);
        final VirtualFile[] files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);

        // check S3 project
        AmazonS3Service amazonS3Service = ServiceManager.getService(project, AmazonS3Service.class);
        boolean isSameS3Project = StringUtils.equals(amazonS3Service.getProjectName(), uploadConfig.getProjectName());

        // check files
        boolean canUploadFiles = FileHelper.canUploadFiles(files);

        // hide or show
        event.getPresentation().setEnabledAndVisible(isSameS3Project && canUploadFiles);
    }

    @NotNull
    public String getActionId() {
        return actionId;
    }

    @Override
    public void dispose() {
        ActionManager.getInstance().unregisterAction(actionId);
        uploadConfig = null;
    }
}
