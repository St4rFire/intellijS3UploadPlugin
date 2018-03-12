package com.openmind.intellij.helper;

import org.jetbrains.annotations.NotNull;

import com.intellij.ide.FileSelectInContext;
import com.intellij.ide.SelectInContext;
import com.intellij.ide.SelectInTarget;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.ide.projectView.impl.AbstractProjectViewPane;
import com.intellij.ide.projectView.impl.ProjectViewImpl;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;


public class ScrollToFile
{

    public static void scroll(@NotNull Project project, @NotNull PsiFile file) {
        final SelectInContext selectInContext = new FileSelectInContext(project, file.getVirtualFile());

        ProjectViewImpl projectView = (ProjectViewImpl) ProjectView.getInstance(project);
        AbstractProjectViewPane currentProjectViewPane = projectView.getCurrentProjectViewPane();
        if (currentProjectViewPane != null) {
            SelectInTarget target = currentProjectViewPane.createSelectInTarget();
            if (target != null && target.canSelect(selectInContext)) {
                target.selectIn(selectInContext, false);
            }
        }
    }
}
