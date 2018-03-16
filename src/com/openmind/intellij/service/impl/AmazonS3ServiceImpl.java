package com.openmind.intellij.service.impl;

import static com.intellij.notification.NotificationType.ERROR;
import static com.intellij.notification.NotificationType.INFORMATION;
import static com.intellij.openapi.util.io.FileUtil.toCanonicalPath;
import static java.io.File.separator;
import static org.apache.commons.lang.StringUtils.EMPTY;
import static org.apache.commons.lang.StringUtils.defaultString;
import static org.apache.commons.lang.StringUtils.isEmpty;
import static org.apache.commons.lang.StringUtils.isNotEmpty;
import static org.apache.commons.lang.StringUtils.replaceOnce;
import static org.apache.commons.lang.StringUtils.substringAfterLast;
import static org.apache.commons.lang.StringUtils.substringBefore;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.java.JavaModuleSourceRootTypes;
import org.jetbrains.jps.model.module.JpsModuleSourceRootType;
import org.springframework.util.CollectionUtils;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.openmind.intellij.bean.UploadConfig;
import com.openmind.intellij.helper.FileHelper;
import com.openmind.intellij.helper.NotificationHelper;
import com.openmind.intellij.service.AmazonS3Service;
import com.openmind.intellij.service.OutputFileService;


/**
 * Upload file to S3. S3 env credentials have to be set
 */
public class AmazonS3ServiceImpl implements AmazonS3Service {

    // credentials
    private static final String AWS_SYSTEM_ACCESS_KEY = "AWS_ACCESS_KEY";
    private static final String AWS_SYSTEM_SECRET_ACCESS_KEY = "AWS_SECRET_ACCESS_KEY";
    private static final String AWS_PROPERTY_ACCESS_KEY = "aws.accessKeyId";
    private static final String AWS_PROPERTY_SECRET_ACCESS_KEY = "aws.secretKey";

    // path defaults
    private static final String S3_BUCKET_SUFFIX = "-releases";
    private static final String LAST_VERSIONS_PATH = "last";
    private static final String VERSIONS_PATH = "versions";
    private static final String PATCH_PATH = "patch";

    // override keys in custom properties file
    private static final String S3_BUCKET_KEY = "bucket.name";
    private static final String PROJECT_NAME = "project.name";
    private static final String LAST_VERSIONS_PATH_KEY = "last.versions.path";
    private static final String VERSIONS_PATH_KEY = "versions.path";
    private static final String PATCH_PATH_KEY = "patch.path";
    private static final String DEPLOY_PATH_KEY = "deploy.path"; // relative to patch folder

    // project recognition - custom mappings from config file suffix to deployed project
    private static final String FROM_CONFIG_TO_DEPLOY_SUFFIX_KEY = "s3.mapping.project.";
    private final HashMap<String,String> FROM_CONFIG_TO_DEPLOY_SUFFIX =
        Maps.newHashMap(ImmutableMap.<String, String>builder()
            .put("esb",         "esb")
            .put("magnolia",    "webapp")
            .put("hybris",      "todo")
            .build());

    private final Project project;
    private final Properties customProperties;
    private final List<UploadConfig> uploadConfigs;
    private final OutputFileService outputFileService;


    /**
     * Load project configs
     * @param project
     * @throws IllegalArgumentException
     */
    public AmazonS3ServiceImpl(@NotNull Project project) throws IllegalArgumentException {
        this.project = project;
        this.customProperties = loadCustomProperties();
        checkSystemVars();
        this.uploadConfigs = loadUploadConfigs();
        this.outputFileService = ServiceManager.getService(project, OutputFileService.class);
    }

    /**
     * Get all available configs
     * @return
     */
    @NotNull
    @Override
    public List<UploadConfig> getUploadConfigs() {
        return this.uploadConfigs;
    }

    @NotNull
    @Override
    public String getProjectName() {
        return customProperties.getProperty(PROJECT_NAME, project.getName());
    }

    @NotNull
    public String getProject() {
        return customProperties.getProperty(PROJECT_NAME, project.getName());
    }

    /**
     * Add custom properties to defaults
     */
    private Properties loadCustomProperties() {
        Properties customProperties = FileHelper.getProjectProperties(project);
        FileHelper.updateConfigFromProperties(customProperties, FROM_CONFIG_TO_DEPLOY_SUFFIX_KEY, FROM_CONFIG_TO_DEPLOY_SUFFIX);
        return customProperties;
    }

    /**
     * Check if system envs are set
     * @throws IllegalArgumentException
     */
    private void checkSystemVars() throws IllegalArgumentException {
        String projectPrefix = getProjectName().toUpperCase() + "_";
        String key = projectPrefix + AWS_SYSTEM_ACCESS_KEY;
        String secret = projectPrefix + AWS_SYSTEM_SECRET_ACCESS_KEY;
        String keyValue = defaultString(System.getenv(key), execEcho(key));
        String secretValue = defaultString(System.getenv(secret), execEcho(secret));

        if (isEmpty(keyValue) || isEmpty(secretValue)) {
            throw new IllegalArgumentException("System Variables " + key + " or " + secret + " not found");
        }
    }

    /**
     * Sometimes Intellij is not finding system vars
     * @param systemVar
     * @return
     */
    private String execEcho(String systemVar) {
        try
        {
            String[] cmdline = { "sh", "-c", "echo $" + systemVar };
            Process process = Runtime.getRuntime().exec(cmdline);
            process.waitFor();
            BufferedReader stdInput = new BufferedReader(new InputStreamReader(process.getInputStream()));
            return stdInput.readLine();
        }
        catch (IOException | InterruptedException e)
        {
        }
        return null;
    }

    /**
     * Load configs from s3
     */
    @NotNull
    private List<UploadConfig> loadUploadConfigs() throws IllegalArgumentException {
        final String projectName = getProjectName();
        final String bucketName = getBucketName(projectName);
        final String lastVersionsPath = getLastVersionsPath();

        AmazonS3 s3Client = getS3Client();
        ListObjectsV2Request request = new ListObjectsV2Request()
            .withBucketName(bucketName)
            .withPrefix(lastVersionsPath);

        List<UploadConfig> uploadConfigs;
        try {
            ListObjectsV2Result listing = s3Client.listObjectsV2(request);
            uploadConfigs = listing.getObjectSummaries().stream()
                .map(s -> replaceOnce(s.getKey(), lastVersionsPath, ""))
                .filter(s -> !s.isEmpty())
                .map(versionFileName -> new UploadConfig(projectName, versionFileName))
                .collect(Collectors.toList());

        } catch (Exception ex) {
            throw new IllegalArgumentException("Error " + ex.getMessage());
        }

        if (CollectionUtils.isEmpty(uploadConfigs)) {
            throw new IllegalArgumentException("No config files found in " + bucketName + separator + lastVersionsPath);
        }
        return uploadConfigs;
    }


    /**
     * Upload to S3
     * @param originalFile
     * @param uploadConfig
     */
    @Override
    public void uploadFile(@NotNull VirtualFile originalFile, @NotNull UploadConfig uploadConfig) {

        // get file to really upload
        final VirtualFile outputFiles = outputFileService.getOutputFile(null, originalFile);
        if (outputFiles == null) {
            NotificationHelper.showEventAndBalloon(project, "File to upload not found", ERROR);
            return;
        }
        final String projectName = getProjectName();
        final String bucketName = getBucketName(projectName);
        final String lastVersionsPath = getLastVersionsPath();

        // get current project version from S3
        final AmazonS3 s3Client = getS3Client();
        final String versionFilePath = lastVersionsPath + uploadConfig.getFullFileName();
        final S3Object versionS3object = s3Client.getObject(new GetObjectRequest(bucketName, versionFilePath));
        if (versionS3object == null || versionS3object.getObjectContent() == null) {
            NotificationHelper.showEventAndBalloon(project, "Version file not found", ERROR);
            return;
        }

        final String version = FileHelper.getFirstLineFromFile(versionS3object.getObjectContent());
        if (StringUtils.isEmpty(version)) {
            NotificationHelper.showEventAndBalloon(project,"Version file is empty", ERROR);
            return;
        }

        // deploy file
        try {
            final String patchPath = getVersionsPath() + version + separator + getPatchPath();
            final String deployedProjectPath = getDeployedProjectPath(s3Client, bucketName, patchPath, uploadConfig);
            final String deployPath = deployedProjectPath + outputFileService.getProjectRelativeDeployPath(originalFile);

            // upload file todo batch
            List<VirtualFile> filesToUpload = Arrays.asList(outputFiles);
            filesToUpload.addAll(outputFileService.findSubclasses(originalFile, outputFiles));
            filesToUpload.forEach(o -> {
                String fullS3FilePath = deployPath + o.getName();
                s3Client.putObject(new PutObjectRequest(bucketName, fullS3FilePath, new File(o.getCanonicalPath())));
                NotificationHelper.showEventAndBalloon(project, "Uploaded to " + fullS3FilePath, INFORMATION);
            });

        } catch (Exception ex) {
            NotificationHelper.showEventAndBalloon(project, "Error " + ex.getMessage(), ERROR);
        }
    }

    @NotNull
    private String getBucketName(@NotNull String projectName) {
        return customProperties.getProperty(S3_BUCKET_KEY, projectName + S3_BUCKET_SUFFIX);
    }

    @NotNull
    private String getLastVersionsPath() {
        return customProperties.getProperty(LAST_VERSIONS_PATH_KEY, LAST_VERSIONS_PATH) + separator;
    }

    @NotNull
    private String getVersionsPath() {
        return customProperties.getProperty(VERSIONS_PATH_KEY, VERSIONS_PATH) + separator;
    }

    @NotNull
    private String getPatchPath() {
        return customProperties.getProperty(PATCH_PATH_KEY, PATCH_PATH) + separator;
    }



    /**
     * Get full path of project inside the patch folder
     * @param s3Client
     * @param bucketName
     * @param patchPath
     * @param uploadConfig
     * @return
     * @throws IllegalArgumentException
     */
    // todo FileUtilRt.toSystemIndependentName(
    @NotNull
    private String getDeployedProjectPath(@NotNull AmazonS3 s3Client, @NotNull String bucketName,
        @NotNull String patchPath, @NotNull UploadConfig uploadConfig) throws IllegalArgumentException {

        // custom deploy path
        String deployedProjectName = customProperties.getProperty(DEPLOY_PATH_KEY);
        if (deployedProjectName != null) {
            return patchPath + deployedProjectName
                + (isNotEmpty(deployedProjectName) ? separator : EMPTY);
        }

        // get deployed project suffix from the one in configs
        final String deployedProjectSuffix = FROM_CONFIG_TO_DEPLOY_SUFFIX.get(uploadConfig.getSubProjectName());

        final List<String> projectsList;
        try {
            // get list of deployed projects
            ListObjectsV2Request request = new ListObjectsV2Request()
                .withBucketName(bucketName)
                .withPrefix(patchPath);

            projectsList = s3Client.listObjectsV2(request).getObjectSummaries().stream()
                .map(s -> substringBefore(s.getKey().replaceFirst(patchPath, ""), "/"))
                .distinct()
                .collect(Collectors.toList());

        } catch (Exception ex) {
            throw new IllegalArgumentException("Error " + ex.getMessage());
        }

        if (CollectionUtils.isEmpty(projectsList)) {
            throw new IllegalArgumentException("No project found in path " + bucketName + separator + patchPath);
        }

        // skip folder if only one project
        if (projectsList.size() == 1) {
            return EMPTY;
        }

        // search in s3 a folder with the selected suffix
        Optional<String> matchingProject = projectsList.stream()
            .filter(s -> !s.isEmpty()
                && StringUtils.equals(substringAfterLast(s, "-"), deployedProjectSuffix))
            .findFirst();

        if (matchingProject.isPresent()) {
            deployedProjectName = matchingProject.get();
        }


        if (isEmpty(deployedProjectName)) {
            throw new IllegalArgumentException("Could not map suffix " + deployedProjectSuffix
                + " to a deployed project in path: " + bucketName + separator + patchPath);
        }

        return patchPath + deployedProjectName + separator;
    }




    private AmazonS3 getS3Client() {
        setSystemPropertiesFromEnvs();
        final AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
            .withRegion(Regions.EU_WEST_1)
            .build();
        return s3Client;
    }

    /**
     * Update system properties before any call based on current project
     */
    private void setSystemPropertiesFromEnvs() {
        String projectPrefix = getProjectName().toUpperCase() + "_";
        String awsAccessKey = System.getenv(projectPrefix + AWS_SYSTEM_ACCESS_KEY);
        String awsSecretAccessKey = System.getenv(projectPrefix + AWS_SYSTEM_SECRET_ACCESS_KEY);

        System.setProperty(AWS_PROPERTY_ACCESS_KEY, awsAccessKey);
        System.setProperty(AWS_PROPERTY_SECRET_ACCESS_KEY, awsSecretAccessKey);
    }


}
