package com.openmind.intellij.service;

import java.util.List;

import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.openmind.intellij.bean.UploadConfig;

import org.jetbrains.annotations.NotNull;


public interface AmazonS3Service
{
    static AmazonS3Service getInstance(@NotNull Project project)
    {
        return ServiceManager.getService(project, AmazonS3Service.class);
    }

    @NotNull
    List<UploadConfig> getUploadConfigs();

    @NotNull
    String getProjectName();

    void uploadFile(@NotNull VirtualFile originalFile, @NotNull UploadConfig uploadConfig);
}
