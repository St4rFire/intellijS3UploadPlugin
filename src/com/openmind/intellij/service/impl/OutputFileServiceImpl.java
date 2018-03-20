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
    private static final String DEPLOY_PATH_MAPPINGS_KEY = "deploy.path.mappings.";

    // deploy path resolution strategy
    private static final String DEPLOY_PATH_STRATEGY = "deploy.path.strategy";

    // compilation info
    private final Map<String, CompiledBehavior> COMPILED_BEHAVIORS = Maps.newLinkedHashMap();
    {
        COMPILED_BEHAVIORS.put("java", new CompiledBehavior("class", "$"));
        COMPILED_BEHAVIORS.put("groovy", new CompiledBehavior("class", "$"));
    }

    // src deploy path transformation - search custom mappings from source path to deploy path
    private final Map<String,String> DEFAULT_DEPLOY_MAPPING = Maps.newLinkedHashMap();
    {
        DEFAULT_DEPLOY_MAPPING.put("/src/main/java/",       "/WEB-INF/classes/");
        DEFAULT_DEPLOY_MAPPING.put("/src/main/resources/",  "/WEB-INF/classes/");
        DEFAULT_DEPLOY_MAPPING.put("/src/main/webapp/",     "/");
    }

    private final Map<String,String> CUSTOM_DEPLOY_MAPPINGS = Maps.newLinkedHashMap();

    private final Project project;
    private final Properties customProperties;
    private final List<String> moduleContentRoots;
    private final List<String> moduleSourceRoots;
    private final Module[] modules;
    private final DeployPathStrategy deployPathStrategy;

    private enum DeployPathStrategy {

        // deploy path starts at custom path conversion
        FROM_CUSTOM_MAPPING,

        // deploy path starts at module names
        FROM_MODULE_NAME,

        // deploy path starts after custom path conversion
        AFTER_PROJECT_ROOT;
    }

    /**
     * Setup
     * @param project
     */
    public OutputFileServiceImpl(@NotNull Project project) {

        this.project = project;

        ProjectRootManager instance = ProjectRootManager.getInstance(project);

        // module names
        moduleContentRoots = Arrays.stream(instance.getContentRoots())
            .map(v -> v.getCanonicalPath())
            .sorted(Comparator.reverseOrder())
            .collect(Collectors.toList());
        NotificationHelper.showEvent(project, "all moduleContentRoots "+ moduleContentRoots.stream().collect(Collectors.joining(System.lineSeparator())), NotificationType.ERROR);

        // Eg: sourceroots .../main/java and .../main/resources
        this.moduleSourceRoots = instance.getModuleSourceRoots(JavaModuleSourceRootTypes.PRODUCTION).stream()
            .map(v -> v.getCanonicalPath())
            .sorted(Comparator.reverseOrder())
            .collect(Collectors.toList());
        NotificationHelper.showEvent(project, "all moduleSourceRoots "+ moduleSourceRoots.stream().collect(Collectors.joining(System.lineSeparator())), NotificationType.ERROR);

        // laod module
        this.modules = ModuleManager.getInstance(project).getModules();

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

        FileHelper.updateConfigMapFromProperties(customProperties, DEPLOY_PATH_MAPPINGS_KEY, CUSTOM_DEPLOY_MAPPINGS,
            FileHelper::ensureSeparators);
        CUSTOM_DEPLOY_MAPPINGS.putAll(DEFAULT_DEPLOY_MAPPING);

        String strategy = customProperties.getProperty(DEPLOY_PATH_STRATEGY);
        deployPathStrategy = isNotEmpty(strategy)
            ? DeployPathStrategy.valueOf(strategy)
            : DeployPathStrategy.FROM_CUSTOM_MAPPING;
    }


    /**
     * Get compiled file or original
     * @param module
     * @param originalFile
     * @throws IllegalArgumentException if compiled file should exists but it doesn't
     */
    @NotNull
    public VirtualFile getCompiledOrOriginalFile(@Nullable Module module, @NotNull VirtualFile originalFile) {

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

        NotificationHelper.showEventAndBalloon(project, "deployPathStrategy : " + deployPathStrategy, INFORMATION);

        // search custom source - output mapping
        String originalPathAfterCustomMapping = null;
        String srcPath = null;
        String deployPath = null;
        Optional<Map.Entry<String, String>> customDeployMapping = CUSTOM_DEPLOY_MAPPINGS.entrySet().stream()
            .filter(e -> originalPath.contains(e.getKey()))
            .findFirst();
        if (customDeployMapping.isPresent()) {

            // custom new path
            srcPath = customDeployMapping.get().getKey();
            deployPath = customDeployMapping.get().getValue();
            originalPathAfterCustomMapping = replaceOnce(originalPath, srcPath, deployPath);
        }

        // deploy path before custom mappings
        if (deployPathStrategy == DeployPathStrategy.FROM_CUSTOM_MAPPING) {
            if (isNotEmpty(originalPathAfterCustomMapping)) {
                NotificationHelper.showEventAndBalloon(project, "originalPathAfterCustomMapping : " + originalPathAfterCustomMapping, INFORMATION);
                return originalPathAfterCustomMapping.substring(originalPathAfterCustomMapping.indexOf(deployPath) + 1);

            } else {
                throw new IllegalArgumentException("No custom mapping found for " +originalPath);
            }
        }

        originalPathAfterCustomMapping = defaultString(originalPathAfterCustomMapping, originalPath);

        // deploy path after content root - keep modules name
        if (deployPathStrategy == DeployPathStrategy.FROM_MODULE_NAME) {
            Optional<String> contentRoot = moduleContentRoots.stream().filter(r -> originalPath.contains(r)).findFirst();
            if (contentRoot.isPresent()) {
                String beforeModuleName = substringBeforeLast(contentRoot.get(), separator);
                NotificationHelper.showEventAndBalloon(project, "beforeModuleName : " + beforeModuleName, INFORMATION);
                return replaceOnce(originalPathAfterCustomMapping, beforeModuleName + separator, EMPTY);
            } else {
                throw new IllegalArgumentException("No sourceRoot found for " + originalPath);
            }
        }

        // deploy path after project root
        if (deployPathStrategy == DeployPathStrategy.AFTER_PROJECT_ROOT) {
            return substringAfter(originalPathAfterCustomMapping, project.getBasePath() + separator);
        }

        throw new IllegalArgumentException("Could not found base path strategy for: " + originalPath);
    }

    private Module getContainingModule(@NotNull VirtualFile originalFile) {
        return Stream.of(modules).filter(m -> m.getModuleScope().accept(originalFile)).findFirst().orElse(null);
    }


    @Nullable
    private String automaticCompilePathConversion(@Nullable Module module, @NotNull VirtualFile originalFile) {

        // if not provided try search
        if (module == null) {
            module = getContainingModule(originalFile);
            NotificationHelper.showEvent(project, "event module null, tried auto find: " + module + ", originalPath" + originalFile.getCanonicalPath(),  NotificationType.ERROR);
        }

        if (module == null) {
            return null;
        }

        // get output path from module
        final String moduleOutputPath;
        try
        {
            moduleOutputPath = CompilerPaths.getModuleOutputPath( module, false );
        }
        catch (Exception e)
        {
            NotificationHelper.showEventAndBalloon(project, "Error retrieving Module OutputPath: " + e.getMessage(), NotificationType.ERROR);
            return null;
        }

        NotificationHelper.showEvent(project, "found moduleOutputPath: " + moduleOutputPath, NotificationType.ERROR);
        if (isNotEmpty(moduleOutputPath)) {

            // get matching source path
            final String originalPath = originalFile.getCanonicalPath();
            Optional<String> sourceRoot = getModuleSource(originalPath);
            if (sourceRoot.isPresent()) {
                NotificationHelper.showEvent(project, ".. and found  sourceRoot: " + sourceRoot, NotificationType.ERROR);

                // compile path = output path + originalPath without source root
                return moduleOutputPath + replaceOnce(originalPath, sourceRoot.get(), EMPTY);
            } else {
                NotificationHelper.showEvent(project, " ... but not found sourceRoot for" + originalPath, NotificationType.ERROR);
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
            extensionBehavior = new CompiledBehavior(key);
            COMPILED_BEHAVIORS.put(key, extensionBehavior);
        }
        return extensionBehavior;
    }

    private static class CompiledBehavior
    {

        private String outputExtension;

        private final Map<String, String> pathMappings;

        private String subclassesSeparator;


        public CompiledBehavior(String outputExtension)
        {
            this(outputExtension, null);
        }

        public CompiledBehavior(String outputExtension, String subclassesSeparator)
        {
            this.outputExtension = outputExtension;
            this.pathMappings = Maps.newLinkedHashMap();
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
