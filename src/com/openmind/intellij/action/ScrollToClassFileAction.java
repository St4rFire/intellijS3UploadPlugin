package com.openmind.intellij.action;

import org.jetbrains.annotations.Nullable;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.openmind.intellij.helper.AmazonS3Helper;
import com.openmind.intellij.helper.FileHelper;
import com.openmind.intellij.helper.NotificationGuiHelper;
import com.openmind.intellij.helper.ScrollFromFileHelper;


/**
 * Scroll to compiled file in navigator
 */
public class ScrollToClassFileAction extends AnAction {

    public ScrollToClassFileAction(@Nullable String text){
        super(text, null, null);
    }


    /**
     * Start upload
     * @param anActionEvent
     */
    public void actionPerformed(AnActionEvent anActionEvent) {
        Project project = anActionEvent.getData(PlatformDataKeys.PROJECT);
        PsiFile psiFile = anActionEvent.getData(PlatformDataKeys.PSI_FILE);

        // get compiled class
        VirtualFile compiledFile = FileHelper.getCompiledFile((PsiJavaFile) psiFile);

        // scroll to file in navigator if found
        if(compiledFile != null) {
            PsiFile fileManaged = PsiManager.getInstance(project).findFile(compiledFile);
            ScrollFromFileHelper.scrollFromFile(project, fileManaged);

        } else {
            NotificationGuiHelper.show(".class file not found!", NotificationType.ERROR);
        }
    }


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
