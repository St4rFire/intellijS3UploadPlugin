package com.openmind.intellij.helper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Properties;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiJavaFile;


public class FileHelper
{

    @Nullable
    public static VirtualFile getCompiledFile(@NotNull PsiJavaFile psiJavaFile)
    {

        final VirtualFile virtualFile = psiJavaFile.getVirtualFile();
        final String classFilePath = virtualFile.getCanonicalPath()
            .replace("/src/main/java", "/target/classes")
            .replace(".java", ".class");

        // get .class file
        final VirtualFile compiledFile = virtualFile.getFileSystem().findFileByPath(classFilePath);
        if (compiledFile != null && compiledFile.exists())
        {
            return compiledFile;
        }
        return null;
    }




    /**
     * Read text from file
     *
     * @param input
     * @return
     */
    @NotNull
    public static String getFirstLineFromFile(InputStream input) throws IllegalArgumentException
    {

        try
        {
            BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            return reader.readLine();

        }
        catch (IOException e)
        {
            throw new IllegalArgumentException("Could not get project version", e);
        }
    }

    @NotNull
    public static Properties getCustomProperties(@NotNull Project project)
    {
        Properties prop = new Properties();
        File file = new File(project.getBasePath() + File.separator + "s3upload.properties");
        if (file.exists())
        {
            try
            {
                prop.load(new FileInputStream(file));
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
        return prop;
    }
}
