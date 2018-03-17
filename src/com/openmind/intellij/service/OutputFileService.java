package com.openmind.intellij.service;

import java.util.List;

import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.openmind.intellij.service.impl.OutputFileServiceImpl;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;


public interface OutputFileService {

    static OutputFileService getInstance(@NotNull Project project) {
        return ServiceManager.getService(project, OutputFileService.class);
    }

    @NotNull
    VirtualFile getOutputFile(@NotNull VirtualFile originalFile);

    @NotNull
    List<VirtualFile> findSubclasses(VirtualFile originalFile, VirtualFile outputFile);

    @NotNull
    public abstract String getProjectRelativeDeployPath(@NotNull VirtualFile originalFile)
        throws IllegalArgumentException;
}
