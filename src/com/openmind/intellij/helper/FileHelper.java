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

    private static final String ESB_PROJECT_SUFFIX = "-esb";

    private static final String WEBAPP_PROJECT_SUFFIX = "-product-webapp";

    private static final String SRC_MAIN = "/src/main/";

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
     * Convert original path to path to upload
     *
     * @param fileToUpload
     * @param originalFile
     * @return
     */
    @NotNull
    public static String getDeployPath(@NotNull VirtualFile fileToUpload, @NotNull VirtualFile originalFile)
    {
        String originalPath = originalFile.getCanonicalPath();
        if (originalPath == null || !originalPath.contains(SRC_MAIN))
        {
            throw new IllegalArgumentException("Could not get deploy originalPath");
        }

        String relativePath = originalPath.substring(originalPath.indexOf(SRC_MAIN) + SRC_MAIN.length());

        if (relativePath.startsWith("java"))
        {
            relativePath = relativePath.replaceFirst("java", "WEB-INF/classes");
        }
        else if (relativePath.startsWith("resources"))
        {
            relativePath = relativePath.replaceFirst("resources", "WEB-INF/classes");
        }
        else if (relativePath.startsWith("webapp"))
        {
            relativePath = relativePath.replaceFirst("webapp/", "");

        }
        else
        {
            throw new IllegalArgumentException("Could not get deploy originalPath");
        }

        return relativePath.replace("." + originalFile.getExtension(), "." + fileToUpload.getExtension());
    }


    /**
     * Get project folder from versionFile name
     *
     * @param versionFile
     * @return
     */
    @NotNull
    public static String getProjectFolder(String versionFile, String projectName)
    {
        return projectName + (versionFile.contains(ESB_PROJECT_SUFFIX) ? ESB_PROJECT_SUFFIX : WEBAPP_PROJECT_SUFFIX);
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

    @Nullable
    public static Properties getCustomProperties(@NotNull Project project)
    {
        File file = new File(project.getBasePath() + File.separator + "s3upload.properties");
        if (file.exists())
        {
            Properties prop = new Properties();
            try
            {
                prop.load(new FileInputStream(file));
                return prop;
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
        return null;
    }
}
