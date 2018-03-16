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
     * @param anActionEvent
     */
    public void actionPerformed(AnActionEvent anActionEvent) {
        final Project project = anActionEvent.getData(PlatformDataKeys.PROJECT);
        final Module module = anActionEvent.getData(LangDataKeys.MODULE);
        final PsiFile psiFile = anActionEvent.getData(PlatformDataKeys.PSI_FILE);

        // get compiled class
        OutputFileService outputFileService = ServiceManager.getService(project, OutputFileService.class);
        VirtualFile compiledFile = outputFileService.getOutputFile(module, psiFile.getVirtualFile());

        // scroll to file in navigator if found
        if(compiledFile != null) {
            PsiFile fileManaged = PsiManager.getInstance(project).findFile(compiledFile);
            ScrollToFile.scroll(project, fileManaged);

        } else {
            NotificationHelper.showEvent(project, ".class file not found!", NotificationType.ERROR);
        }
    }


    /**
     * Handle action visibility, only if is a java file
     * @param anActionEvent
     */
    @Override
    public void update(AnActionEvent anActionEvent) {
        final Project project = anActionEvent.getData(CommonDataKeys.PROJECT);
        if (project == null)
            return;

        PsiFile psiFile = anActionEvent.getData(PlatformDataKeys.PSI_FILE);
        anActionEvent.getPresentation().setEnabledAndVisible(psiFile != null && psiFile.getVirtualFile() != null
            && !psiFile.getVirtualFile().isDirectory() && psiFile instanceof PsiJavaFile);
    }
}
