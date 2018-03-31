package com.openmind.intellij.service;

import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;


public interface OutputFileService {

    static OutputFileService getInstance(@NotNull Project project) {
        return ServiceManager.getService(project, OutputFileService.class);
    }

    @NotNull
    VirtualFile getCompiledOrOriginalFile(@Nullable Module module, @NotNull VirtualFile originalFile);

    @NotNull
    List<VirtualFile> findSubclasses(VirtualFile originalFile, VirtualFile outputFile);

    @NotNull
    String getProjectRelativeDeployPath(@NotNull VirtualFile originalFile)
        throws IllegalArgumentException;
}
