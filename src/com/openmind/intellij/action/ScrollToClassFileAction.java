package com.openmind.intellij.action;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.compiler.CompilerPaths;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.openmind.intellij.helper.FileHelper;
import com.openmind.intellij.helper.NotificationHelper;
import com.openmind.intellij.helper.ScrollToFile;
import com.openmind.intellij.service.OutputFileService;


/**
 * Scroll to compiled file in navigator
 */
public class ScrollToClassFileAction extends AnAction {

    public ScrollToClassFileAction() {
    }


    /**
     * Start upload
     * @param event
     */
    public void actionPerformed(AnActionEvent event) {
        final Project project = event.getData(PlatformDataKeys.PROJECT);
        final Module module = event.getData(LangDataKeys.MODULE);
        final PsiFile selectedFile = event.getData(PlatformDataKeys.PSI_FILE);

        if (project == null || selectedFile == null || selectedFile.getVirtualFile() == null) {
            NotificationHelper.showEvent(project, "Could not find any selected file!", NotificationType.ERROR);
            return;
        }

        // get compiled class
        OutputFileService outputFileService = ServiceManager.getService(project, OutputFileService.class);
        try
        {
            VirtualFile compiledFile = outputFileService.getOutputFile(selectedFile.getVirtualFile());
            PsiFile fileManaged = PsiManager.getInstance(project).findFile(compiledFile);

            // scroll to file in navigator if found
            ScrollToFile.scroll(project, fileManaged);
        }
        catch (Exception e)
        {
            NotificationHelper.showEvent(project, ".class file not found!", NotificationType.ERROR);
        }
    }


    /**
     * Handle action visibility, only if is a java file
     * @param event
     */
    @Override
    public void update(AnActionEvent event) {
        final Project project = event.getData(CommonDataKeys.PROJECT);
        if (project == null)
            return;

        PsiFile psiFile = event.getData(PlatformDataKeys.PSI_FILE);
        event.getPresentation().setEnabledAndVisible(
            psiFile != null
            && psiFile.getVirtualFile() != null
            && !psiFile.getVirtualFile().isDirectory()
            && psiFile instanceof PsiJavaFile);
    }
}
