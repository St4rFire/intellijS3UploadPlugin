package com.openmind.intellij.service;

import java.util.List;

import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.openmind.intellij.bean.UploadConfig;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;


public interface AmazonS3Service {

    static AmazonS3Service getInstance(@NotNull Project project) {
        return ServiceManager.getService(project, AmazonS3Service.class);
    }

    @NotNull
    List<UploadConfig> getUploadConfigs();

    @NotNull
    String getProjectName();

    void uploadFiles(@Nullable Module module, @NotNull List<VirtualFile> originalFiles, @NotNull UploadConfig uploadConfig);
}
