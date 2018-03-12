package com.openmind.intellij.helper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;


public class FileHelper {

    /**
     * Get compile file
     * @param psiFile
     * @return null if not found
     */
    @Nullable
    public static VirtualFile getCompiledFile(@NotNull PsiFile psiFile) {
        final VirtualFile virtualFile = psiFile.getVirtualFile();
        String compiledFilePath = null;

        if (psiFile instanceof PsiJavaFile) {
            compiledFilePath = virtualFile.getCanonicalPath()
                .replace("/src/main/java", "/target/classes")
                .replace(".java", ".class");
        }

        if(StringUtils.isNotEmpty(compiledFilePath)) {

            final VirtualFile compiledFile = virtualFile.getFileSystem().findFileByPath(compiledFilePath);
            if (compiledFile != null && compiledFile.exists()) {
                return compiledFile;
            }
        }

        return null;
    }

    /**
     * Get file to upload
     * @param psiFile
     * @return virtualFile
     */
    @Nullable
    public static VirtualFile getFileToUpload(@NotNull PsiFile psiFile) {

        // get compiled files
        if (psiFile instanceof PsiJavaFile) {
            return FileHelper.getCompiledFile(psiFile);
        }

        return psiFile.getVirtualFile();
    }

    /**
     * Read text from file
     *
     * @param input
     * @return
     */
    @NotNull
    public static String getFirstLineFromFile(@NotNull InputStream input) throws IllegalArgumentException {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            return reader.readLine();

        } catch (IOException e) {
            throw new IllegalArgumentException("Could not get project version", e);
        }
    }


    /**
     * Load file as Properties
     * @param filePath
     * @return
     */
    @NotNull
    public static Properties getProperties(@NotNull String filePath) {
        Properties prop = new Properties();
        File file = new File(filePath);
        if (file.exists()) {
            try {
                prop.load(new FileInputStream(file));

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return prop;
    }
}
