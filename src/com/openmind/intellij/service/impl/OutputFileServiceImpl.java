package com.openmind.intellij.service.impl;

import static com.openmind.intellij.helper.FileHelper.COLON;
import static com.openmind.intellij.helper.FileHelper.COMMA;
import static com.openmind.intellij.helper.FileHelper.DOT;
import static com.openmind.intellij.helper.FileHelper.ensureSeparators;
import static com.openmind.intellij.helper.FileHelper.forceEndingWithSeparator;
import static com.openmind.intellij.helper.FileHelper.forceNotStartingWithSeparator;
import static com.openmind.intellij.helper.FileHelper.getProjectProperties;
import static java.io.File.separator;
import static org.apache.commons.lang.StringUtils.EMPTY;
import static org.apache.commons.lang.StringUtils.contains;
import static org.apache.commons.lang.StringUtils.isEmpty;
import static org.apache.commons.lang.StringUtils.isNotEmpty;
import static org.apache.commons.lang.StringUtils.replaceOnce;
import static org.apache.commons.lang.StringUtils.split;
import static org.apache.commons.lang.StringUtils.startsWith;
import static org.apache.commons.lang.StringUtils.substringAfter;
import static org.apache.commons.lang.StringUtils.substringBeforeLast;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.BooleanUtils;
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
import com.openmind.intellij.service.OutputFileService;


public class OutputFileServiceImpl implements OutputFileService {

    // extensions to convert. Key suffix is extension. Eg: java = class
    private static final String COMPILE_MAPPING_EXTENSION_KEY = "compile.mapping.extension.";

    // paths to convert to find the compiled files. Key suffix is extension. Eg: java = /path1:/path2,/path3:/path4
    private static final String COMPILE_MAPPING_PATH_KEY = "compile.mapping.path.";

    // search subclasses after value. Key suffix is extension. Eg java = $
    private static final String COMPILE_MAPPING_SUBCLASSES_KEY = "compile.mapping.subclasses.";

    // paths to convert to deploy. Key suffix is extension. Eg: /path1 = /path2
    private static final String DEPLOY_PATH_MAPPINGS_KEY = "deploy.path.mappings.";

    // deploy path resolution strategy
    private static final String DEPLOY_PATH_STRATEGY_KEY = "deploy.path.strategy";

    // deploy path prefix
    private static final String DEPLOY_PATH_PREFIX_KEY = "deploy.path.prefix";

    // deploy path prefix
    private static final String DEPLOY_SOURCE_OUTPUT_KEY = "deploy.source.output";

    // deploy path prefix
    private static final String DEPLOY_AUTO_SOURCE_TO_DEPLOY_MAPPING_KEY = "deploy.auto.source.mapping";

    // maven-war-plugin defaults
    private static final String DEFAULT_DEPLOY_SOURCE_OUTPUT = "/WEB-INF/classes/";
    private static final List<String> DEFAULT_SOURCE_ROOTS = Arrays.asList("/src/main/java/", "/src/main/resources/");
    private static final List<String> DEFAULT_WEB_RESOURCES = Collections.singletonList("/src/main/webapp/");

    // src deploy path transformation - search custom mappings from source path to deploy path
    private final Map<String,String> customDeployMappings = new TreeMap<>();

    // compilation info
    private final Map<String, CompiledBehavior> compiledBehaviors = Maps.newLinkedHashMap();
    {
        compiledBehaviors.put("java", new CompiledBehavior("class", "$"));
        compiledBehaviors.put("groovy", new CompiledBehavior("class", "$"));
    }

    private final Project project;

    private final List<String> moduleContentRoots;
    private final List<String> moduleSourceRoots;
    private final Module[] modules;
    private final String deployPathPrefix;
    private final String sourceDeployOutput;
    private final boolean autoSouceToDeployOutputMapping;
    private DeployPathStrategy deployPathStrategy;
    private DeployPathStrategy defaultDeployPathStrategy = DeployPathStrategy.FROM_SOURCES;

    private enum DeployPathStrategy {

        // deploy path starts at custom path conversion
        FROM_MAPPINGS,

        // deploy path starts at source folders
        FROM_SOURCES,

        // deploy path starts at module names
        FROM_MODULE_NAME,

        // deploy path starts after custom path conversion
        AFTER_PROJECT_ROOT
    }

    // todo  com.intellij.openapi.util.io.FileUtil.toCanonicalPath(java.lang.String)
    // todo FileUtilRt.toSystemIndependentName()


    /**
     * Setup
     * @param project
     */
    public OutputFileServiceImpl(@NotNull Project project) {

        this.project = project;
        ProjectRootManager projectRootManager = ProjectRootManager.getInstance(project);

        // module paths
        moduleContentRoots = Arrays.stream(projectRootManager.getContentRoots())
            .map(VirtualFile::getCanonicalPath)
            .sorted(Comparator.reverseOrder())
            .collect(Collectors.toList());

        // sourceroots Eg: .../main/java and .../main/resources
        moduleSourceRoots = projectRootManager.getModuleSourceRoots(JavaModuleSourceRootTypes.PRODUCTION).stream()
            .map(VirtualFile::getCanonicalPath)
            .sorted(Comparator.reverseOrder())
            .collect(Collectors.toList());

        // laod modules
        modules = ModuleManager.getInstance(project).getModules();

        addKwownDefaults();

        // load properties
        Properties customProperties = getProjectProperties(project);
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

        // custom source deploy output path
        sourceDeployOutput = customProperties.getProperty(DEPLOY_SOURCE_OUTPUT_KEY, DEFAULT_DEPLOY_SOURCE_OUTPUT);

        // path mappings
        DEFAULT_SOURCE_ROOTS.forEach(root -> customDeployMappings.put(root, sourceDeployOutput));
        DEFAULT_WEB_RESOURCES.forEach(root -> customDeployMappings.put(root, separator));

        FileHelper.populateMapFromProperties(customProperties, DEPLOY_PATH_MAPPINGS_KEY, customDeployMappings,
            FileHelper::ensureSeparators);

        // deploy strategy
        String strategy = customProperties.getProperty(DEPLOY_PATH_STRATEGY_KEY);
        deployPathStrategy = isNotEmpty(strategy)
            ? DeployPathStrategy.valueOf(strategy)
            : defaultDeployPathStrategy;

        // deploy prefix
        deployPathPrefix = forceNotStartingWithSeparator(forceEndingWithSeparator(
            customProperties.getProperty(DEPLOY_PATH_PREFIX_KEY), true));

        // automatic replacement of sources when deploying
        String autoSourceToDeployOutputMappingSetting = customProperties.getProperty(DEPLOY_AUTO_SOURCE_TO_DEPLOY_MAPPING_KEY);
        autoSouceToDeployOutputMapping = isNotEmpty(autoSourceToDeployOutputMappingSetting)
            ? BooleanUtils.toBoolean(autoSourceToDeployOutputMappingSetting)
            : true;
    }


    private void addKwownDefaults() {
        boolean isHybris = new File(project.getBasePath(), "/bin/custom").exists();
        if (isHybris) {
            customDeployMappings.put("/src/", "/webroot/WEB-INF/classes/");
            defaultDeployPathStrategy = DeployPathStrategy.AFTER_PROJECT_ROOT;
        }
    }



    /**
     * Get compiled file or original
     * @param module
     * @param originalFile
     * @throws IllegalArgumentException if compiled file should exists but it doesn't
     */
    @NotNull
    @Override
    public VirtualFile getCompiledOrOriginalFile(@Nullable Module module, @NotNull VirtualFile originalFile) {
        module = getModuleOrSearch(module, originalFile);

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
            outputPath = automaticCompilePathConversion(module, originalFile);
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


    @Nullable
    private Module getModuleOrSearch(@Nullable Module module, @NotNull VirtualFile originalFile)
    {
        // if not provided try search
        if (module == null) {
            module = getContainingModule(originalFile).orElse(null);
        }
        return module;
    }

    @Nullable
    private String automaticCompilePathConversion(@Nullable Module module, @NotNull VirtualFile originalFile) {

        if (module == null) {
            return null;
        }

        // get output path from module
        final String moduleOutputPath;
        try
        {
            moduleOutputPath = CompilerPaths.getModuleOutputPath(module, false );
        }
        catch (Exception e)
        {
            return null;
        }

        if (isNotEmpty(moduleOutputPath)) {

            // get matching source path
            final String originalPath = originalFile.getCanonicalPath();
            Optional<String> sourceRoot = getModuleSourceRoot(originalPath);
            if (sourceRoot.isPresent()) {

                String pathFromSourceFolder = replaceOnce(originalPath, sourceRoot.get(), EMPTY);
                return moduleOutputPath + pathFromSourceFolder;
            }
        }
        return null;
    }

    /**
     * Convert original path to path inside deployed project
     *
     * @param originalFile
     * @return
     */
    @Override
    @NotNull
    public String getProjectRelativeDeployPath(@NotNull VirtualFile originalFile) throws IllegalArgumentException {
        return deployPathPrefix + getProjectRelativeDeployPathNoPrefix(originalFile);
    }


    @NotNull
    private String getProjectRelativeDeployPathNoPrefix(@NotNull VirtualFile originalFile) throws IllegalArgumentException {

        String originalPath = substringBeforeLast(originalFile.getCanonicalPath(), separator);
        if (isEmpty(originalPath)) {
            throw new IllegalArgumentException("Could not get deploy originalPath");
        }

        Optional<String> contentRoot = getModuleContentRoot(originalPath);
        Optional<String> sourceRoot = getModuleSourceRoot(originalPath);

        // search custom source - output mapping
        String processedPath = originalPath;
        String srcPath = null;
        String deployPath;

        Optional<Map.Entry<String, String>> customDeployMapping = customDeployMappings.entrySet().stream()
            .filter(e -> originalPath.contains(e.getKey()))
            .findFirst();
        if (customDeployMapping.isPresent()) {

            // custom new path
            srcPath = customDeployMapping.get().getKey();
            deployPath = customDeployMapping.get().getValue();
            processedPath = replaceOnce(originalPath, srcPath, deployPath);
        }

        // deploy path before custom mappings
        if (deployPathStrategy == DeployPathStrategy.FROM_MAPPINGS) {
            if (customDeployMapping.isPresent()) {
                return processedPath.substring(originalPath.indexOf(srcPath) + 1);

            } else {
                throw new IllegalArgumentException("No custom mapping found for " +originalPath);
            }
        }

        // auto source to output transformation
        if (autoSouceToDeployOutputMapping && contentRoot.isPresent() && sourceRoot.isPresent() && !customDeployMapping.isPresent()) {
            String sourceFolders = ensureSeparators(replaceOnce(sourceRoot.get(), contentRoot.get(), EMPTY));
            processedPath = replaceOnce(processedPath, sourceFolders, sourceDeployOutput);
        }

        if (deployPathStrategy == DeployPathStrategy.FROM_SOURCES) {
            if (contentRoot.isPresent() && sourceRoot.isPresent() && sourceRoot.get().length() > contentRoot.get().length()) {
                String beforeSourceFolders = sourceRoot.get().substring(0, contentRoot.get().length());
                return replaceOnce(processedPath, beforeSourceFolders + separator, EMPTY);
            } else {
                throw new IllegalArgumentException("No sourceRoot found for " + originalPath);
            }
        }

        // deploy path after content root - keep modules name
        if (deployPathStrategy == DeployPathStrategy.FROM_MODULE_NAME) {
            if (contentRoot.isPresent()) {
                String beforeModuleName = substringBeforeLast(contentRoot.get(), separator);
                return replaceOnce(processedPath, beforeModuleName + separator, EMPTY);
            } else {
                throw new IllegalArgumentException("No sourceRoot found for " + originalPath);
            }
        }

        // deploy path after project root
        if (deployPathStrategy == DeployPathStrategy.AFTER_PROJECT_ROOT) {
            return substringAfter(processedPath, project.getBasePath() + separator);
        }

        throw new IllegalArgumentException("Could not found base path strategy for: " + originalPath);
    }


    private Optional<Module> getContainingModule(@NotNull VirtualFile originalFile) {
        return Stream.of(modules).filter(m -> m.getModuleScope().accept(originalFile)).findFirst();
    }

    private Optional<String> getModuleContentRoot(String originalPath)
    {
        return moduleContentRoots.stream().filter(r -> startsWith(originalPath, r)).findFirst();
    }

    private Optional<String> getModuleSourceRoot(String originalPath)
    {
        return moduleSourceRoots.stream().filter(s -> startsWith(originalPath, s)).findFirst();
    }

    @Nullable
    private CompiledBehavior getExtensionBehavior(@Nullable String key) {
        return compiledBehaviors.get(key);
    }

    @NotNull
    private CompiledBehavior getOrCreateExtensionBehavior(@NotNull String key) {
        return compiledBehaviors.computeIfAbsent(key, CompiledBehavior::new);
    }

    private static class CompiledBehavior
    {

        private String outputExtension;

        private final Map<String, String> pathMappings;

        private String subclassesSeparator;


        CompiledBehavior(String outputExtension)
        {
            this(outputExtension, null);
        }

        CompiledBehavior(String outputExtension, String subclassesSeparator)
        {
            this.outputExtension = outputExtension;
            this.pathMappings = Maps.newLinkedHashMap();
            this.subclassesSeparator = subclassesSeparator;
        }

        String getOutputExtension()
        {
            return outputExtension;
        }

        void setOutputExtension(String outputExtension)
        {
            this.outputExtension = outputExtension;
        }

        Map<String, String> getPathMappings()
        {
            return pathMappings;
        }

        void addPathMappings(Map<String, String> pathMappings)
        {
            this.pathMappings.putAll(pathMappings);
        }

        String getSubclassesSeparator()
        {
            return subclassesSeparator;
        }

        void setSubclassesSeparator(String subclassesSeparator)
        {
            this.subclassesSeparator = subclassesSeparator;
        }
    }
}
