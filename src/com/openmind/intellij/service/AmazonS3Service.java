package com.openmind.intellij.service;

import static com.intellij.notification.NotificationType.*;
import static java.io.File.*;
import static org.apache.commons.lang.StringUtils.*;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;

import com.openmind.intellij.helper.FileHelper;
import com.openmind.intellij.helper.NotificationGuiHelper;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.openmind.intellij.bean.UploadConfig;


/**
 * Upload file to S3. S3 env credentials have to be set
 */
public class AmazonS3Service {

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
    private static final String S3_PROPERTIES_FILE = "s3upload.properties";
    private static final String S3_BUCKET_KEY = "bucket.name";
    private static final String PROJECT_NAME = "project.name";
    private static final String LAST_VERSIONS_PATH_KEY = "last.versions.path";
    private static final String VERSIONS_PATH_KEY = "versions.path";
    private static final String PATCH_PATH_KEY = "patch.path";
    private static final String DEPLOY_PATH_KEY = "deploy.path"; // relative to patch folder

    // project recognition
    private static final String MAPPING_PROJECT = "mapping.project.";
    private final HashMap<String,String> PROJECT_NAME_FROM_CONFIG_TO_DEPLOYED =
        Maps.newHashMap(ImmutableMap.<String, String>builder()
        .put("esb",         "esb")
        .put("magnolia",    "webapp")
        .put("hybris",      "todo")
        .build());

    // src deploy path transformation
    private static final String MAPPING_SRC = "mapping.src.";
    private static final String SRC_MAIN = "/src/main/";
    private final HashMap<String,String> SRC_FROM_PROJECT_TO_DEPLOYED =
        Maps.newHashMap(ImmutableMap.<String, String>builder()
            .put("java",         "WEB-INF/classes")
            .put("resources",    "WEB-INF/classes")
            .put("webapp/",      "")
            .build());


    private Properties customProperties;
    private Project project;
    private List<UploadConfig> uploadConfigs;



    public AmazonS3Service(@NotNull Project project) throws IllegalArgumentException {
        this.project = project;
        loadCustomProperties();
        checkSystemVars();
        loadUploadConfigs();
    }



    private void loadCustomProperties() {
        final String basePath = project.getBasePath();
        customProperties = FileHelper.getProperties(basePath + separator + S3_PROPERTIES_FILE);

        // search custom mappings from config file suffix to deployed project
        customProperties.forEach((k,v) -> {
            if (startsWith(k.toString(), MAPPING_PROJECT)) {
                PROJECT_NAME_FROM_CONFIG_TO_DEPLOYED.put(k.toString().replace(MAPPING_PROJECT, EMPTY), v.toString());
            }
        });

        // search custom mappings from source path to deploy path
        customProperties.forEach((k,v) -> {
            if (startsWith(k.toString(), MAPPING_SRC)) {
                SRC_FROM_PROJECT_TO_DEPLOYED.put(k.toString().replace(MAPPING_SRC, EMPTY), v.toString());
            }
        });
    }

    private void checkSystemVars() throws IllegalArgumentException {
        String projectPrefix = getProjectName().toUpperCase() + "_";
        String key = projectPrefix + AWS_SYSTEM_ACCESS_KEY;
        String secret = projectPrefix + AWS_SYSTEM_SECRET_ACCESS_KEY;

        if (isEmpty(System.getenv(key)) || isEmpty(System.getenv(secret))) {

            throw new IllegalArgumentException("System Variables " + key + " and " + secret + " not found");
        }
    }

    /**
     * Load upload options from s3
     * @return
     */
    @NotNull
    public void loadUploadConfigs() {
        final String projectName = getProjectName();
        final String bucketName = getBucketName(projectName);
        final String lastVersionsPath = getLastVersionsPath();

        AmazonS3 s3Client = getS3Client();

        ListObjectsV2Request request = new ListObjectsV2Request()
            .withBucketName(bucketName)
            .withPrefix(lastVersionsPath);

        try {
            ListObjectsV2Result listing = s3Client.listObjectsV2(request);
            this.uploadConfigs = listing.getObjectSummaries().stream()
                .map(s -> s.getKey().replaceFirst(lastVersionsPath, ""))
                .filter(s -> !s.isEmpty())
                .map(v -> new UploadConfig(v))
                .collect(Collectors.toList());
            return;

        } catch (Exception ex) {
            NotificationGuiHelper.showEventAndBaloon("Error " + ex.getMessage(), ERROR);
        }
        this.uploadConfigs = Lists.newArrayList();
    }


    @NotNull
    public List<UploadConfig> getUploadConfigs() {
        return this.uploadConfigs;
    }


    /**
     * Upload to S3
     * @param originalPath
     * @param uploadConfig
     */
    public void uploadFile(@NotNull String originalPath, @NotNull UploadConfig uploadConfig) {

        final String projectName = getProjectName();
        final String bucketName = getBucketName(projectName);
        final String lastVersionsPath = getLastVersionsPath();

        // connect to S3
        final AmazonS3 s3Client = getS3Client();

        // get current project version
        String versionFilePath = lastVersionsPath + uploadConfig.getFullFileName();
        S3Object versionS3object = s3Client.getObject(new GetObjectRequest(bucketName, versionFilePath));
        String version = FileHelper.getFirstLineFromFile(versionS3object.getObjectContent());

        try {
            final String pathToUpload = FileHelper.getPathToUpload(originalPath);
            final String patchPath = getVersionsPath() + version + separator + getPatchPath();
            final String deployedProjectPath = getDeployedProjectPath(s3Client, bucketName, patchPath, uploadConfig);
            final String deployPath = deployedProjectPath + getDeployPath(originalPath, pathToUpload);

            // upload file
            s3Client.putObject(new PutObjectRequest(bucketName, deployPath, new File(pathToUpload)));
            NotificationGuiHelper.showEventAndBaloon("Uploaded to " + deployPath, INFORMATION);

        } catch (Exception ex) {
            NotificationGuiHelper.showEventAndBaloon("Error " + ex.getMessage(), ERROR);
        }
    }

    @NotNull
    private String getProjectName() {
        return customProperties.getProperty(PROJECT_NAME, project.getName());
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
     * Path inside patch folder
     * @param s3Client
     * @param bucketName
     * @param patchPath
     * @param uploadConfig
     * @return
     * @throws IllegalArgumentException
     */
    @NotNull
    private String getDeployedProjectPath(@NotNull AmazonS3 s3Client, @NotNull String bucketName,
        @NotNull String patchPath, @NotNull UploadConfig uploadConfig) throws IllegalArgumentException {

        String deployedProjectName = customProperties.getProperty(DEPLOY_PATH_KEY);
        if (deployedProjectName != null) {
            return patchPath + deployedProjectName
                + (isNotEmpty(deployedProjectName) ? separator : EMPTY);
        }

        // get "webapp" from "magnolia"
        final String deployedProjectSuffix = PROJECT_NAME_FROM_CONFIG_TO_DEPLOYED.get(uploadConfig.getProjectName());

        // search in s3 a folder with calculated suffix
        ListObjectsV2Request request = new ListObjectsV2Request()
            .withBucketName(bucketName)
            .withPrefix(patchPath);

        try {
            ListObjectsV2Result listing = s3Client.listObjectsV2(request);
            List<String> projectsList = listing.getObjectSummaries().stream()
                .map(s -> substringBefore(s.getKey().replaceFirst(patchPath, ""), "/"))
                .distinct()
                .collect(Collectors.toList());

            if (projectsList.isEmpty()) {
                throw new IllegalArgumentException("No project found in path " + bucketName + separator + patchPath);
            }

            // skip folder if only one project
            if (projectsList.size() == 1) {
                return EMPTY;
            }

            Optional<String> matchingProject = projectsList.stream()
                .filter(s -> !s.isEmpty()
                    && StringUtils.equals(substringAfterLast(s, "-"), deployedProjectSuffix))
                .findFirst();

            if (matchingProject.isPresent()) {
                deployedProjectName = matchingProject.get();
            }

        } catch (Exception ex) {
            NotificationGuiHelper.showEvent("Error " + ex.getMessage(), ERROR);
        }

        if (isEmpty(deployedProjectName)) {
            throw new IllegalArgumentException("Could not map suffix " + deployedProjectSuffix
                + " to a deployed project in path: " + bucketName + separator + patchPath);
        }

        return patchPath + deployedProjectName + separator;
    }


    /**
     * Convert original path to path to upload
     *
     * @param originalPath
     * @param fileToUpload
     * @return
     */
    @NotNull
    public String getDeployPath(@NotNull String originalPath, @NotNull String pathToUpload)
        throws IllegalArgumentException {

        if (originalPath == null || !originalPath.contains(SRC_MAIN)) {
            throw new IllegalArgumentException("Could not get deploy originalPath");
        }

        final String pathInSrcMain = originalPath.substring(originalPath.lastIndexOf(SRC_MAIN) + SRC_MAIN.length()+ 1);
        Optional<Map.Entry<String, String>> mapping = SRC_FROM_PROJECT_TO_DEPLOYED.entrySet().stream()
            .filter(e -> pathInSrcMain.startsWith(e.getKey()))
            .findFirst();

        if (!mapping.isPresent()) {
            throw new IllegalArgumentException("Could not get deploy path "+ originalPath);
        }

        String convertedPath = pathInSrcMain.replaceFirst(mapping.get().getKey(), mapping.get().getValue());
        return convertedPath.replace("." + FileHelper.getFileExtension(originalPath), "." + FileHelper.getFileExtension(pathToUpload));
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
