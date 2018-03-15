package com.openmind.intellij.action;

import static com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE;

import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.openmind.intellij.bean.UploadConfig;
import com.openmind.intellij.service.AmazonS3Service;

/**
 * Upload a file to s3
 */
public class UploadFileToS3Action extends AnAction implements Disposable {

    private final String actionId;
    private UploadConfig uploadConfig;

    public UploadFileToS3Action(@NotNull UploadConfig uploadConfig){
        super(uploadConfig.getFileName(), null, null);
        this.actionId = "S3UploadPlugin.UploadAction." + uploadConfig.getFileName();
        this.uploadConfig = uploadConfig;
    }

    /**
     * Menu click callback: upload to S3
     * @param anActionEvent
     */
    public void actionPerformed(AnActionEvent anActionEvent) {
        final Project project = anActionEvent.getData(PlatformDataKeys.PROJECT);
        final PsiFile selectedFile = anActionEvent.getData(PlatformDataKeys.PSI_FILE);

        AmazonS3Service amazonS3Service = ServiceManager.getService(project, AmazonS3Service.class);
        amazonS3Service.uploadFile(selectedFile.getVirtualFile(), uploadConfig);
    }

    /**
     * Handle action visibility
     * @param anActionEvent
     */
    @Override
    public void update(AnActionEvent anActionEvent) {
        final Project project = anActionEvent.getData(PlatformDataKeys.PROJECT);

        // could be a different project having same S3 project
        AmazonS3Service amazonS3Service = ServiceManager.getService(project, AmazonS3Service.class);
        boolean isSameS3Project = StringUtils.equals(amazonS3Service.getProjectName(), uploadConfig.getProjectName());
        VirtualFile originalFile = VIRTUAL_FILE.getData(anActionEvent.getDataContext());
        anActionEvent.getPresentation().setEnabledAndVisible(isSameS3Project && !originalFile.isDirectory());
    }

    @NotNull
    public String getActionId()
    {
        return actionId;
    }

    @Override
    public void dispose()
    {
        ActionManager.getInstance().unregisterAction(actionId);
        uploadConfig = null;
    }
}
