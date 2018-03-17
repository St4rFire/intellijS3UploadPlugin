package com.openmind.intellij.service.impl;

import static com.openmind.intellij.helper.FileHelper.COLON;
import static com.openmind.intellij.helper.FileHelper.COMMA;
import static com.openmind.intellij.helper.FileHelper.DOT;
import static com.openmind.intellij.helper.FileHelper.getProjectProperties;
import static java.io.File.separator;
import static org.apache.commons.lang.StringUtils.EMPTY;
import static org.apache.commons.lang.StringUtils.contains;
import static org.apache.commons.lang.StringUtils.defaultString;
import static org.apache.commons.lang.StringUtils.isEmpty;
import static org.apache.commons.lang.StringUtils.isNotEmpty;
import static org.apache.commons.lang.StringUtils.replaceOnce;
import static org.apache.commons.lang.StringUtils.split;
import static org.apache.commons.lang.StringUtils.startsWith;
import static org.apache.commons.lang.StringUtils.substringAfter;
import static org.apache.commons.lang.StringUtils.substringBeforeLast;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.SystemIndependent;
import org.jetbrains.jps.model.java.JavaModuleSourceRootTypes;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.compiler.CompilerPaths;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.openmind.intellij.helper.FileHelper;
import com.openmind.intellij.helper.NotificationHelper;
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
        ImmutableMap.of("/src/main/java", "/target/classes", "/src/groovy", "/target/classes"), "$"); // todo src out nonserve +...

    private final HashMap<String, ExtensionBehavior> EXTENSIONS_BEHAVIOR = Maps.newHashMap(ImmutableMap.<String, ExtensionBehavior>builder()
        .put("java", JAVA_BEHAVIOR)
        .put("groovy", JAVA_BEHAVIOR)
        .build());


    // src deploy path transformation - search custom mappings from source path to deploy path
    // todo usare classi idea?
    private static final String DEPLOYED_FOLDERS_REMAPPING_KEY = "deployed.mapping.folder.";
    private final HashMap<String,String> DEPLOYED_FOLDERS_REMAPPING =
        Maps.newHashMap(ImmutableMap.<String, String>builder()
            .put("src/main/java",         "WEB-INF/classes")
            .put("src/main/resources",    "WEB-INF/classes")
            .put("src/main/webapp/",      "")
            .build());

    // Module module = ModuleUtil.findModuleForPsiElement( element );

    private final Project project;
    private final Properties customProperties;
    //private final List<String> modulesRoots; // todo usare come fallback?
    private final List<String> moduleSourceRoots;
    private final Map<String, Module> modulesByPath;

    public OutputFileServiceImpl(@NotNull Project project) {

        this.project = project;

        ProjectRootManager instance = ProjectRootManager.getInstance(project);
//        this.modulesRoots = Arrays.stream(instance.getContentRoots())
//            .map(v -> v.getCanonicalPath())
//            .sorted(Comparator.reverseOrder())
//            .collect(Collectors.toList());

        // .../main/java and .../main/resources
        this.moduleSourceRoots = instance.getModuleSourceRoots(JavaModuleSourceRootTypes.PRODUCTION).stream()
            .map(v -> v.getCanonicalPath())
            .sorted(Comparator.reverseOrder())
            .collect(Collectors.toList());


        // laod modules
        modulesByPath = Stream.of(ModuleManager.getInstance(project).getModules())
                    .collect(Collectors.toMap(m -> getModulePath(m), Function.identity()));

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

        FileHelper.updateConfigFromProperties(customProperties, DEPLOYED_FOLDERS_REMAPPING_KEY, DEPLOYED_FOLDERS_REMAPPING);
    }


    /**
     * Get compiled file if needed
     * @param originalFile
     * @throws IllegalArgumentException is not found
     */
    @NotNull
    public VirtualFile getOutputFile(@NotNull VirtualFile originalFile) {

        final String originalExtension = originalFile.getExtension();
        final ExtensionBehavior extensionBehavior = getExtensionBehavior(originalExtension);
        if (extensionBehavior == null) {
            return originalFile;
        }
        final String originalPath = originalFile.getCanonicalPath();

        // try automatic module path conversion
        String outputPath = convertToOutputPath(originalPath);

        // try custom path conversion
        if (isEmpty(outputPath)) {
            NotificationHelper.showEvent(project, "falling back to custom path ", NotificationType.ERROR);
            Optional<Map.Entry<String, String>> pathToReplace = extensionBehavior.getPathMappings().entrySet().stream()
                .filter(m -> contains(originalPath, m.getKey())) // todo regex
                .findFirst();

            if (pathToReplace.isPresent()) {
                outputPath = replaceOnce(originalPath, pathToReplace.get().getKey(), pathToReplace.get().getValue());
            }
        }

        // no conversion
        if (isEmpty(outputPath)) {
            outputPath = originalPath;
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
        throw new IllegalArgumentException("Unable to find file: " + outputPath);
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
            return Stream.of(outputFile.getParent().getChildren())
                .filter(f -> f.getName().startsWith(outputFile.getNameWithoutExtension() + subclassesSeparator))
                .collect(Collectors.toList());
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
    // com.intellij.openapi.util.io.FileUtil.toCanonicalPath(java.lang.String)
    public String getProjectRelativeDeployPath(@NotNull VirtualFile originalFile)
        throws IllegalArgumentException {

        String pathInProjectFolder = null;

        // full path without file name
        String originalPath = substringBeforeLast(originalFile.getCanonicalPath(), separator);
        if (originalPath == null) {
            throw new IllegalArgumentException("Could not get deploy originalPath");
        }

        // use compiled file clas1!! per goglio..per feltri rimuovo target/classe..ma questo è generico
        // vedere cosa dice moduleOutputPath..no hasenso solo per compilati

        // todo ok per feltrom mamanca WEB-INF/classes daventi
        // ... base modulo + pezzo hce cambia + pezzo trovato dasource
        // todo goglio /web/src -> webroot/WEB-INF patch/bin/custom/goglioweb/web/webroot
        // search mapping in source folders
//        Optional<String> moduleSource = getModuleSource(originalPath);
//        if (moduleSource.isPresent()) {
//            String pathInSourceFolder = substringAfter(originalPath, moduleSource.get() + separator);
//            NotificationHelper.showEvent(project, "REMOVE pathInSourceFolder: " + pathInSourceFolder, NotificationType.ERROR);
//            //return pathInSourceFolder;
//        }

        // get module
        Optional<Module> module = getModule(originalPath);
        if (module.isPresent()) {
            String moduleFilePath = getModulePath(module.get());
            NotificationHelper.showEvent(project, "REMOVE moduleFilePath: " + moduleFilePath, NotificationType.ERROR);
            String pathInSourceFolder = substringAfter(originalPath, moduleFilePath + separator);
            NotificationHelper.showEvent(project, "REMOVE pathInSourceFolder: " + pathInSourceFolder, NotificationType.ERROR);
            if (isNotEmpty(pathInSourceFolder)) {
                pathInProjectFolder = pathInSourceFolder;
            }
        }

        // remove project path
        if (isEmpty(pathInProjectFolder)) {
            pathInProjectFolder = substringAfter(originalPath, project.getBasePath() + separator);
            NotificationHelper.showEvent(project, "REMOVE pathInProjectFolder: " + pathInProjectFolder, NotificationType.ERROR);
        }

        if (isEmpty(pathInProjectFolder)) {
            throw new IllegalArgumentException("Could not found project base for path " + originalPath);
        }

        // replace custom folders
        final String detectedPath = pathInProjectFolder;
        Optional<Map.Entry<String, String>> mapping = DEPLOYED_FOLDERS_REMAPPING.entrySet().stream()
            .filter(e -> detectedPath.contains(e.getKey()))
            .findFirst();

        if (mapping.isPresent()) {
            return replaceOnce(pathInProjectFolder, mapping.get().getKey(), mapping.get().getValue());
        }

        // no changes
        return pathInProjectFolder;
    }

    private Optional<Module> getModule(String path) {
        return modulesByPath.entrySet().stream()
            .filter(moduleByPath -> startsWith(path, moduleByPath.getKey()))
            .map(moduleByPath -> moduleByPath.getValue())
            .findFirst();
    }

    private String getModulePath(Module module) {
        return substringBeforeLast(module.getModuleFilePath(), separator);
    }

    @Nullable
    private String convertToOutputPath(@NotNull String originalPath) {

        Optional<Module> module = getModule(originalPath);
        if (module.isPresent()) {
            return null;
        }

        // get output path
        final String moduleOutputPath = CompilerPaths.getModuleOutputPath( module.get(), false );
        NotificationHelper.showEvent(project, "REMOVE getModuleOutputDirectory: " + moduleOutputPath, NotificationType.ERROR);

        if (isNotEmpty(moduleOutputPath)) {

            // get matching source path
            Optional<String> sourceRoot = getModuleSource(originalPath);
            if (sourceRoot.isPresent()) {
                NotificationHelper.showEvent(project, "REMOVE sourceRoot: " + sourceRoot, NotificationType.ERROR);

                // replace source path with output path
                return moduleOutputPath + replaceOnce(originalPath, sourceRoot.get(), EMPTY);
            }
        }
        return null;
    }

    private Optional<String> getModuleSource(String originalPath)
    {
        return moduleSourceRoots.stream().filter(s -> startsWith(originalPath, s)).findFirst();
    }


    @Nullable
    private ExtensionBehavior getExtensionBehavior(@Nullable String key) {
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
