package com.openmind.intellij.service.impl;

import static com.intellij.notification.NotificationType.INFORMATION;
import static com.openmind.intellij.helper.FileHelper.COLON;
import static com.openmind.intellij.helper.FileHelper.COMMA;
import static com.openmind.intellij.helper.FileHelper.DOT;
import static com.openmind.intellij.helper.FileHelper.ensureSeparators;
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
import org.jetbrains.jps.model.java.JavaModuleSourceRootTypes;

import com.google.common.collect.Maps;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.compiler.CompilerPaths;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.openmind.intellij.helper.FileHelper;
import com.openmind.intellij.helper.NotificationHelper;
import com.openmind.intellij.service.OutputFileService;


public class OutputFileServiceImpl implements OutputFileService {

    // extensions to convert. Key suffix is extension. Eg: java = class
    private static final String COMPILE_MAPPING_EXTENSION_KEY = "compile.mapping.extension.";

    // paths to convert to find the compiled files. Key suffix is extension. Eg: java = /path1:/path2,/path3:/path4
    private static final String COMPILE_MAPPING_PATH_KEY = "compile.mapping.path.";

    // search subclasses after value. Key suffix is extension. Eg java = $
    private static final String COMPILE_MAPPING_SUBCLASSES_KEY = "compile.mapping.subclasses.";

    // paths to convert to deploy. Key suffix is extension. Eg: /path1 = /path2
    private static final String DEPLOY_MAPPING_FOLDER_KEY = "deploy.mapping.folder.";

    // keep searching for sourcepathwen deploying even after custom path conversion
    private static final String DEPLOY_MAPPING_KEEP_SOURCE_PATH = "deploy.mapping.keep.source.path";

    // compilation info
//    private final CompiledBehavior SOURCE_TO_COMPILED = new CompiledBehavior("class", Maps.newLinkedHashMap(), "$");
//    {
//        // todo only auto
//        SOURCE_TO_COMPILED.getPathMappings().put("/src/main/java/", "/target/classes/");
//        SOURCE_TO_COMPILED.getPathMappings().put("/src/groovy/", "/target/classes/");
//        SOURCE_TO_COMPILED.getPathMappings().put("/src/", "/webroot/WEB-INF/classes/"); //h
//    }

    private final Map<String, CompiledBehavior> COMPILED_BEHAVIORS = Maps.newLinkedHashMap();
    {
        COMPILED_BEHAVIORS.put("java", new CompiledBehavior("class", Maps.newLinkedHashMap(), "$"));
        COMPILED_BEHAVIORS.put("groovy", new CompiledBehavior("class", Maps.newLinkedHashMap(), "$"));
    }

    // src deploy path transformation - search custom mappings from source path to deploy path

    private final Map<String,String> DEPLOY_MAPPING_FOLDER = Maps.newLinkedHashMap();
    {
        DEPLOY_MAPPING_FOLDER.put("/src/main/java/",       "/WEB-INF/classes/");
        DEPLOY_MAPPING_FOLDER.put("/src/main/resources/",  "/WEB-INF/classes/");
        DEPLOY_MAPPING_FOLDER.put("/src/main/webapp/",     "/");
        //DEPLOY_MAPPING_FOLDER.put("/src/it/",              "/webroot/");
    }


    private final Project project;
    private final Properties customProperties;
    private final List<String> moduleSourceRoots;
    private final Map<String, Module> modulesByPath;
    private final boolean keepProjectSources;

    public OutputFileServiceImpl(@NotNull Project project) {

        this.project = project;

        ProjectRootManager instance = ProjectRootManager.getInstance(project);

        // Eg: .../main/java and .../main/resources
        this.moduleSourceRoots = instance.getModuleSourceRoots(JavaModuleSourceRootTypes.PRODUCTION).stream()
            .map(v -> v.getCanonicalPath())
            .sorted(Comparator.reverseOrder())
            .collect(Collectors.toList());


        // laod modules
        modulesByPath = Stream.of(ModuleManager.getInstance(project).getModules())
                    .collect(Collectors.toMap(m -> getModulePath(m), Function.identity(), (p1, p2) -> p1));

        // load properties
        this.customProperties = getProjectProperties(project);
        customProperties.forEach((k,v) -> {
            final String key = k.toString();

            // path behavior
            if (startsWith(key, COMPILE_MAPPING_PATH_KEY) && contains(key, COLON)) {
                String extension = replaceOnce(key, COMPILE_MAPPING_PATH_KEY, EMPTY);
                List<String> pathMappings = Arrays.asList(split(v.toString().replaceAll("\\s",""), COMMA));

                Map<String, String> pathMappingsMap = pathMappings.stream()
                    .map(m -> split(m, COLON))
                    .collect(Collectors.toMap(m -> ensureSeparators(m[0]), m -> ensureSeparators(m[1]), (m1, m2) -> m2));

                CompiledBehavior extensionBehavior = getOrCreateExtensionBehavior(extension);
                extensionBehavior.addPathMappings(pathMappingsMap);
            }

            // extension behavior
            if (startsWith(key, COMPILE_MAPPING_EXTENSION_KEY)) {
                String extension = replaceOnce(key, COMPILE_MAPPING_EXTENSION_KEY, EMPTY);
                CompiledBehavior extensionBehavior = getOrCreateExtensionBehavior(extension);
                extensionBehavior.setOutputExtension(v.toString());
            }

            // subclasses behavior
            if (startsWith(key, COMPILE_MAPPING_SUBCLASSES_KEY)) {
                String extension = replaceOnce(key, COMPILE_MAPPING_SUBCLASSES_KEY, EMPTY);
                CompiledBehavior extensionBehavior = getOrCreateExtensionBehavior(extension);
                extensionBehavior.setSubclassesSeparator(v.toString());
            }
        });

        keepProjectSources = Boolean.valueOf(customProperties.getProperty(DEPLOY_MAPPING_KEEP_SOURCE_PATH, EMPTY));

        FileHelper.updateConfigMapFromProperties(customProperties, DEPLOY_MAPPING_FOLDER_KEY, DEPLOY_MAPPING_FOLDER,
            FileHelper::ensureSeparators);
    }


    /**
     * Get compiled file if needed
     * @param originalFile
     * @throws IllegalArgumentException is not found
     */
    @NotNull
    public VirtualFile getCompiledOrOriginalFile(@NotNull VirtualFile originalFile) {

        // search behavior for specific extension
        final String originalExtension = originalFile.getExtension();
        final CompiledBehavior extensionBehavior = getExtensionBehavior(originalExtension);
        if (extensionBehavior == null) {
            return originalFile;
        }
        final String originalPath = originalFile.getCanonicalPath();
        String outputPath = null;

        // try custom path conversion
        Optional<Map.Entry<String, String>> pathToReplace = extensionBehavior.getPathMappings().entrySet().stream()
            .filter(m -> contains(originalPath, m.getKey()))
            .findFirst();

        if (pathToReplace.isPresent()) {
            outputPath = replaceOnce(originalPath, pathToReplace.get().getKey(), pathToReplace.get().getValue());
        }

        // try automatic module path conversion
        if (isEmpty(outputPath)) {
            outputPath = automaticCompilePathConversion(originalPath);
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
        throw new IllegalArgumentException("Unable to find compiled file: " + outputPath);
    }

    @NotNull
    @Override
    public List<VirtualFile> findSubclasses(VirtualFile originalFile, VirtualFile outputFile) {

        // find subclasses
        final CompiledBehavior extensionBehavior = getExtensionBehavior(originalFile.getExtension());
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
    public String getProjectRelativeDeployPath(@NotNull VirtualFile originalFile) throws IllegalArgumentException {

        // full path without file name
        String originalPath = substringBeforeLast(originalFile.getCanonicalPath(), separator);
        if (originalPath == null) {
            throw new IllegalArgumentException("Could not get deploy originalPath");
        }

        // search custom source - output srcToDeploy
        Optional<Map.Entry<String, String>> srcToDeploy = DEPLOY_MAPPING_FOLDER.entrySet().stream()
            .filter(e -> originalPath.contains(e.getKey()))
            .findFirst();


        String originalPathAfterCustomMapping = null;
        if (srcToDeploy.isPresent()) {

            // custom new path
            String srcPath = srcToDeploy.get().getKey();
            String deployPath = srcToDeploy.get().getValue();
            originalPathAfterCustomMapping = replaceOnce(originalPath, srcPath, deployPath);

            // keep only path after mapping
            if (!keepProjectSources) {
                NotificationHelper.showEventAndBalloon(project, "not keeping : " + originalPathAfterCustomMapping, INFORMATION);
                return originalPathAfterCustomMapping.substring(originalPath.indexOf(srcPath) + 1);
            }
        }

        originalPathAfterCustomMapping = defaultString(originalPathAfterCustomMapping, originalPath);

        // remove module source path
        String pathInProjectFolder = null;
        Optional<String> sourceRoot = getModuleSource(originalPath);
        if (sourceRoot.isPresent()) {
            String moduleFilePath = sourceRoot.get();
            String pathInSourceFolder = substringAfter(originalPathAfterCustomMapping, moduleFilePath + separator);
            if (isNotEmpty(pathInSourceFolder)) {
                pathInProjectFolder = pathInSourceFolder;
            }
        }

        // remove project path
        if (isEmpty(pathInProjectFolder)) {
            pathInProjectFolder = substringAfter(originalPathAfterCustomMapping, project.getBasePath() + separator);
        }

        if (isEmpty(pathInProjectFolder)) {
            throw new IllegalArgumentException("Could not found project base for path " + originalPath);
        }

        // no changes
        return pathInProjectFolder;
    }

    private Optional<Module> getModule(String path) {
        NotificationHelper.showEvent(project, modulesByPath.keySet().stream().collect(Collectors.joining(System.lineSeparator())), NotificationType.ERROR);

        return modulesByPath.entrySet().stream()
            .filter(moduleByPath -> startsWith(path, moduleByPath.getKey()))
            .map(moduleByPath -> moduleByPath.getValue())
            .findFirst();
    }

    private String getModulePath(Module module) {
        return substringBeforeLast(module.getModuleFilePath(), separator);
    }

    @Nullable
    private String automaticCompilePathConversion(@NotNull String originalPath) {

        Optional<Module> module = getModule(originalPath);
        if (!module.isPresent()) {
            return null;
        }

        // get output path
        final String moduleOutputPath;
        try
        {
            moduleOutputPath = CompilerPaths.getModuleOutputPath( module.get(), false );
        }
        catch (Exception e)
        {
            return null;
        }

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
    private CompiledBehavior getExtensionBehavior(@Nullable String key) {
        return COMPILED_BEHAVIORS.get(key);
    }

    @NotNull
    private CompiledBehavior getOrCreateExtensionBehavior(@NotNull String key) {
        CompiledBehavior extensionBehavior = COMPILED_BEHAVIORS.get(key);
        if (extensionBehavior == null) {
            extensionBehavior = new CompiledBehavior();
            COMPILED_BEHAVIORS.put(key, extensionBehavior);
        }
        return extensionBehavior;
    }

    private static class CompiledBehavior
    {

        private String outputExtension;

        private Map<String, String> pathMappings;

        private String subclassesSeparator;


        public CompiledBehavior()
        {
        }

        public CompiledBehavior(String outputExtension, Map<String, String> pathMappings, String subclassesSeparator)
        {
            this.outputExtension = outputExtension;
            this.pathMappings = pathMappings;
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
