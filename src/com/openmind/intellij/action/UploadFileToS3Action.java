package com.openmind.intellij.action;

import static com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE;

import org.jetbrains.annotations.Nullable;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.openmind.intellij.bean.UploadConfig;
import com.openmind.intellij.service.AmazonS3Service;


/**
 * Upload a file to s3
 */
public class UploadFileToS3Action extends AnAction {

    private UploadConfig uploadConfig;
    private AmazonS3Service amazonS3Service;

    public UploadFileToS3Action(@Nullable UploadConfig uploadConfig, @Nullable AmazonS3Service amazonS3Service){
        super(uploadConfig.getFileName(), null, null);
        this.uploadConfig = uploadConfig;
        this.amazonS3Service = amazonS3Service;
    }

    /**
     * Menu click callback
     * @param anActionEvent
     */
    public void actionPerformed(AnActionEvent anActionEvent) {
        final PsiFile selectedFile = anActionEvent.getData(PlatformDataKeys.PSI_FILE);
        //final VirtualFile selectedFile = VIRTUAL_FILE.getData(anActionEvent.getDataContext());

        // upload to S3
        amazonS3Service.uploadFile(selectedFile, uploadConfig);
    }

    /**
     * Handle action visibility
     * @param anActionEvent
     */
    @Override
    public void update(AnActionEvent anActionEvent) {
        VirtualFile originalFile = VIRTUAL_FILE.getData(anActionEvent.getDataContext());
        anActionEvent.getPresentation().setEnabledAndVisible(!originalFile.isDirectory());
    }

}
