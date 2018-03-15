package com.openmind.intellij.service.impl;

import static com.openmind.intellij.helper.FileHelper.COLON;
import static com.openmind.intellij.helper.FileHelper.COMMA;
import static com.openmind.intellij.helper.FileHelper.DOT;
import static com.openmind.intellij.helper.FileHelper.getProjectProperties;
import static java.io.File.separator;
import static org.apache.commons.lang.StringUtils.EMPTY;
import static org.apache.commons.lang.StringUtils.contains;
import static org.apache.commons.lang.StringUtils.isNotEmpty;
import static org.apache.commons.lang.StringUtils.replaceOnce;
import static org.apache.commons.lang.StringUtils.split;
import static org.apache.commons.lang.StringUtils.startsWith;
import static org.apache.commons.lang.StringUtils.substringAfter;
import static org.apache.commons.lang.StringUtils.substringBeforeLast;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.intellij.openapi.vfs.VirtualFile;
import com.openmind.intellij.helper.FileHelper;
import com.openmind.intellij.service.OutputFileService;
import com.intellij.openapi.project.Project;


public class OutputFileServiceImpl implements OutputFileService {

    // extensions to convert
    private static final String EXTENSION_MAPPING_KEY = "compiled.mapping.extension."; // java=class

    // paths to convert
    private static final String COMPILED_PATH_KEY = "compiled.mapping.path."; //java =/path1:/path2,/path3:/path4

    // subclasses
    private static final String SUBCLASSES_KEY = "compiled.mapping.subclasses."; //java = $

    private static final ExtensionBehavior JAVA_BEHAVIOR = new ExtensionBehavior("class",
        ImmutableMap.of("/src/main/java", "/target/classes", "/src/groovy", "/target/classes"), "$");

    private final HashMap<String, ExtensionBehavior> EXTENSIONS_BEHAVIOR = Maps.newHashMap(ImmutableMap.<String, ExtensionBehavior>builder()
        .put("java", JAVA_BEHAVIOR)
        .put("groovy", JAVA_BEHAVIOR)
        .build());


    // src deploy path transformation - search custom mappings from source path to deploy path
    // todo usare classi idea?
    private static final String FOLDERS_REMAPPING_KEY = "output.mapping.folder.";
    private final HashMap<String,String> FOLDERS_REMAPPING =
        Maps.newHashMap(ImmutableMap.<String, String>builder()
            .put("src/main/java",         "WEB-INF/classes")
            .put("src/main/resources",    "WEB-INF/classes")
            .put("src/main/webapp/",      "")
            .build());

    private final Project project;
    private final Properties customProperties;



    public OutputFileServiceImpl(@NotNull Project project) {

        this.project = project;
        this.customProperties = getProjectProperties(project);

        customProperties.forEach((k,v) -> {
            final String key = k.toString();

            // path behavior
            if (startsWith(key, COMPILED_PATH_KEY) && contains(key, COLON)) {
                String extension = replaceOnce(key, COMPILED_PATH_KEY, EMPTY);
                List<String> pathMappings = Arrays.asList(split(v.toString().replaceAll("\\s",""), COMMA));

                Map<String, String> pathMappingsMap = pathMappings.stream()
                    .map(m -> split(m, COLON))
                    .collect(Collectors.toMap(m -> m[0], m -> m[1]));

                ExtensionBehavior extensionBehavior = getOrCreateExtensionBehavior(extension);
                extensionBehavior.addPathMappings(pathMappingsMap);
            }

            // extension behavior
            if (startsWith(key, EXTENSION_MAPPING_KEY)) {
                String extension = replaceOnce(key, EXTENSION_MAPPING_KEY, EMPTY);
                ExtensionBehavior extensionBehavior = getOrCreateExtensionBehavior(extension);
                extensionBehavior.setOutputExtension(v.toString());
            }

            // subclasses behavior
            if (startsWith(key, SUBCLASSES_KEY)) {
                String extension = replaceOnce(key, SUBCLASSES_KEY, EMPTY);
                ExtensionBehavior extensionBehavior = getOrCreateExtensionBehavior(extension);
                extensionBehavior.setSubclassesSeparator(v.toString());
            }
        });

        FileHelper.updateConfigFromProperties(customProperties, FOLDERS_REMAPPING_KEY, FOLDERS_REMAPPING);
    }


    /**
     * Get compiled file if needed
     * @param originalFile
     * @return null if not found
     */
    @Nullable
    public VirtualFile getOutputFile(@NotNull VirtualFile originalFile) {
        final String originalExtension = originalFile.getExtension();
        final ExtensionBehavior extensionBehavior = getExtensionBehavior(originalExtension);
        if (extensionBehavior == null) {
            return originalFile;
        }
        final String originalPath = originalFile.getCanonicalPath();
        String outputPath = originalPath;

        // replace path
        Optional<Map.Entry<String, String>> pathToReplace = extensionBehavior.getPathMappings().entrySet().stream()
            .filter(m -> contains(originalPath, m.getKey())) // todo regex
            .findFirst();

        if (pathToReplace.isPresent()) {
            outputPath = replaceOnce(outputPath, pathToReplace.get().getKey(), pathToReplace.get().getValue());
        }

        // replace extension
        final String outputExtension = extensionBehavior.getOutputExtension();
        if (isNotEmpty(outputExtension)) {
            outputPath = replaceOnce(outputPath, DOT + originalExtension, DOT + outputExtension);
        }

        // return original
        if(StringUtils.equals(outputPath, originalPath))
        {
            return originalFile;
        }

        // return derived file
        final VirtualFile outputFile = originalFile.getFileSystem().findFileByPath(outputPath);
        if (outputFile != null && outputFile.exists()) {
            return outputFile;
        }

        // unable to find file
        return null;
    }

    @NotNull
    @Override
    public List<VirtualFile> findSubclasses(VirtualFile originalFile, VirtualFile outputFile) {

        // find subclasses
        final ExtensionBehavior extensionBehavior = getExtensionBehavior(originalFile.getExtension());
        if (extensionBehavior == null) {
            return Collections.emptyList();
        }
        final String subclassesSeparator = extensionBehavior.getSubclassesSeparator();
        if (isNotEmpty(subclassesSeparator)) {
            List<VirtualFile> allClasses = Arrays.asList(outputFile.getParent().getChildren()).stream()
                .filter(f -> f.getName().startsWith(outputFile.getNameWithoutExtension() + subclassesSeparator))
                .collect(Collectors.toList());
            allClasses.add(outputFile);
            return allClasses;
        }
        return Collections.emptyList();
    }


    /**
     * Convert original path to path inside deployed project
     *
     * @param originalFile
     * @return
     */
    @Override
    @NotNull
    public String getProjectRelativeDeployPath(@NotNull VirtualFile originalFile)
        throws IllegalArgumentException {

        String originalPath = substringBeforeLast(originalFile.getCanonicalPath(), separator);
        if (originalPath == null) {
            throw new IllegalArgumentException("Could not get deploy originalPath");
        }

        final String pathInProjectFolder = substringAfter(originalPath, project.getBasePath() + separator);
        Optional<Map.Entry<String, String>> mapping = FOLDERS_REMAPPING.entrySet().stream()
            .filter(e -> pathInProjectFolder.startsWith(e.getKey()))
            .findFirst();

        if (mapping.isPresent()) {
            return replaceOnce(pathInProjectFolder, mapping.get().getKey(), mapping.get().getValue()) + separator;

        }
        return pathInProjectFolder + separator;
    }


    @Nullable
    private ExtensionBehavior getExtensionBehavior(@NotNull String key) {
        return EXTENSIONS_BEHAVIOR.get(key);
    }

    @NotNull
    private ExtensionBehavior getOrCreateExtensionBehavior(@NotNull String key) {
        ExtensionBehavior extensionBehavior = EXTENSIONS_BEHAVIOR.get(key);
        if (extensionBehavior == null) {
            extensionBehavior = new ExtensionBehavior();
            EXTENSIONS_BEHAVIOR.put(key, extensionBehavior);
        }
        return extensionBehavior;
    }

    private static class ExtensionBehavior {

        private String outputExtension;

        private Map<String, String> pathMappings;

        private String subclassesSeparator;


        public ExtensionBehavior()
        {
        }

        public ExtensionBehavior(String outputExtension, Map<String, String> pathMappings, String subclassesSeparator)
        {
            this.outputExtension = outputExtension;
            this.pathMappings = new HashMap<>(pathMappings);
            this.subclassesSeparator = subclassesSeparator;
        }

        public String getOutputExtension()
        {
            return outputExtension;
        }

        public void setOutputExtension(String outputExtension)
        {
            this.outputExtension = outputExtension;
        }

        public Map<String, String> getPathMappings()
        {
            return pathMappings;
        }

        public void addPathMappings(Map<String, String> pathMappings)
        {
            this.pathMappings.putAll(pathMappings);
        }

        public String getSubclassesSeparator()
        {
            return subclassesSeparator;
        }

        public void setSubclassesSeparator(String subclassesSeparator)
        {
            this.subclassesSeparator = subclassesSeparator;
        }
    }
}
