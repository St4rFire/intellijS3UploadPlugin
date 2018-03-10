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

    @NotNull
    public static String getPathToUpload(String filePath) {

        // get compiled files
        if (filePath.endsWith(".java")) {
            final String classFile = (filePath
                    .replace("/src/main/java", "/target/classes")
                    .replace(".java", ".class"));
            if (new File(classFile).exists()) {
                return classFile;
            }
            throw new IllegalArgumentException(".class not found for " + filePath);
        }

        return filePath;
    }

    public static String getFileExtension(String filePath) {
        return filePath.substring(filePath.lastIndexOf(".") + 1);
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
    public static Properties getProperties(@NotNull String filePath)
    {
        Properties prop = new Properties();
        File file = new File(filePath);
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
