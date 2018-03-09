package com.openmind.intellij.action;

import static com.intellij.notification.NotificationType.*;
import static com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.openmind.intellij.bean.UploadInfo;
import com.openmind.intellij.helper.AmazonS3Helper;
import com.openmind.intellij.helper.FileHelper;
import com.openmind.intellij.helper.NotificationGuiHelper;


/**
 * Upload File to s3
 */
public class UploadFileToS3Action extends AnAction {

    private UploadInfo uploadInfo;
    private AmazonS3Helper amazonS3Helper;

    public UploadFileToS3Action(@Nullable UploadInfo uploadInfo, @Nullable AmazonS3Helper amazonS3Helper){
        super(uploadInfo.getFileName(), null, null);
        this.uploadInfo = uploadInfo;
        this.amazonS3Helper = amazonS3Helper;
    }

    /**
     * Start upload
     * @param anActionEvent
     */
    public void actionPerformed(AnActionEvent anActionEvent) {
        final Project project = anActionEvent.getData(PlatformDataKeys.PROJECT);
        final PsiFile psiFile = anActionEvent.getData(PlatformDataKeys.PSI_FILE);
        final VirtualFile originalFile = VIRTUAL_FILE.getData(anActionEvent.getDataContext());
        final VirtualFile fileToUpload = getFileToUpload(psiFile, originalFile);

        if (fileToUpload == null) {
            NotificationGuiHelper.showEventAndBaloon("File to upload not found", ERROR);
            return;
        }

        // upload to S3
        amazonS3Helper.uploadFile(project, fileToUpload, originalFile, uploadInfo);
    }

    @Override
    public void update(AnActionEvent anActionEvent) {
        final Project project = anActionEvent.getData(CommonDataKeys.PROJECT);
        if (project == null)
            return;

        VirtualFile originalFile = VIRTUAL_FILE.getData(anActionEvent.getDataContext());
        anActionEvent.getPresentation().setEnabledAndVisible(!originalFile.isDirectory());
    }


    /**
     * Get file to upload
     * @param psiFile
     * @param virtualFile
     * @return virtualFile
     */
    @Nullable
    private VirtualFile getFileToUpload(@NotNull PsiFile psiFile, @NotNull VirtualFile virtualFile) {

        // get compiled files
        if (psiFile instanceof PsiJavaFile) {
            return FileHelper.getCompiledFile((PsiJavaFile) psiFile);
        }

        return virtualFile;
    }

}
