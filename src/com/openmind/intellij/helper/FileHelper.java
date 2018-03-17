
package com.openmind.intellij.helper;

import static java.io.File.separator;
import static org.apache.commons.lang.StringUtils.EMPTY;
import static org.apache.commons.lang.StringUtils.replaceOnce;
import static org.apache.commons.lang.StringUtils.startsWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.util.CollectionUtils;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWithoutContent;


public class FileHelper {


    public static final String PROJECT_PROPERTIES_FILE = "s3upload.properties";
    public static final String DOT = ".";
    public static final String COMMA = ",";
    public static final String COLON = ":";

    @NotNull
    public static long getLastModified(@NotNull VirtualFile virtualFile) {
        return new File(virtualFile.getCanonicalPath()).lastModified();
    }

    public static boolean hasContent(@NotNull VirtualFile file) {
        return !(file instanceof VirtualFileWithoutContent);
    }

    public static boolean canUploadFiles(@Nullable VirtualFile[] files) {
        if (files == null || files.length == 0) {
            return false;
        }
        return !Stream.of(files).anyMatch(f -> !hasContent(f));
    }

    public static void flattenAllChildren(VirtualFile[] virtualFiles, List<VirtualFile> files) {
        if (virtualFiles == null) {
            return;
        }
        for (VirtualFile child : virtualFiles) {
            flattenAllChildren(child, files);
        }
    }

    public static void flattenAllChildren(VirtualFile virtualFile, List<VirtualFile> files) {
        if (virtualFile.isDirectory()) {
            for (VirtualFile child : virtualFile.getChildren()) {
                flattenAllChildren(child, files);
            }
        } else if (hasContent(virtualFile)) { // todo test link
            files.add(virtualFile);
        }
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

    public static Properties getProjectProperties(@NotNull Project project) {
        return FileHelper.getProperties(project.getBasePath() + separator + PROJECT_PROPERTIES_FILE);
    }


    public static void updateConfigFromProperties(@NotNull Properties properties, @NotNull String prefix,
        @NotNull HashMap<String,String> config) {
        properties.forEach((k,v) -> {
            if (startsWith(k.toString(), prefix)) {
                config.put(replaceOnce(k.toString(), prefix, EMPTY), v.toString());
            }
        });
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
