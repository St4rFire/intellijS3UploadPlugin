package com.openmind.intellij.helper;

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
import com.openmind.intellij.bean.UploadInfo;


/**
 * Upload file to S3. S3 env credentials have to be set
 */
public class AmazonS3Helper {


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
    private static final HashMap<String,String> PROJECT_NAME_FROM_CONFIG_TO_DEPLOYED =
        Maps.newHashMap(ImmutableMap.<String, String>builder()
        .put("esb",         "esb")
        .put("magnolia",    "webapp")
        .put("hybris",      "todo")
        .build());

    // src deploy path transformation
    private static final String MAPPING_SRC = "mapping.src.";
    private static final String SRC_MAIN = "/src/main/";
    private static final HashMap<String,String> SRC_FROM_PROJECT_TO_DEPLOYED =
        Maps.newHashMap(ImmutableMap.<String, String>builder()
            .put("java",         "WEB-INF/classes")
            .put("resources",    "WEB-INF/classes")
            .put("webapp/",      "")
            .build());


    private static Properties customProperties = new Properties();


    public static void loadCustomProperties(@NotNull Project project)
    {
        final String basePath = project.getBasePath();
        AmazonS3Helper.customProperties = FileHelper.getProperties(basePath + separator + S3_PROPERTIES_FILE);

        // search custom mappings from config file suffix to deployed project
        AmazonS3Helper.customProperties.forEach((k,v) -> {
            if (startsWith(k.toString(), MAPPING_PROJECT)) {
                PROJECT_NAME_FROM_CONFIG_TO_DEPLOYED.put(k.toString().replace(MAPPING_PROJECT, EMPTY), v.toString());
            }
        });

        // search custom mappings from source path to deploy path
        AmazonS3Helper.customProperties.forEach((k,v) -> {
            if (startsWith(k.toString(), MAPPING_SRC)) {
                SRC_FROM_PROJECT_TO_DEPLOYED.put(k.toString().replace(MAPPING_SRC, EMPTY), v.toString());
            }
        });
    }

    /**
     * Get list of files indicating
     * @param project
     * @return
     */
    @NotNull
    public static List<String> getVersionFiles(@NotNull Project project) {
        final String projectName = getProjectName(project);
        final String bucketName = getBucketName(projectName);
        final String lastVersionsPath = getLastVersionsPath();

        AmazonS3 s3Client = getS3Client(project);

        ListObjectsV2Request request = new ListObjectsV2Request()
            .withBucketName(bucketName)
            .withPrefix(lastVersionsPath);

        try {
            ListObjectsV2Result listing = s3Client.listObjectsV2(request);
            return listing.getObjectSummaries().stream()
                .map(s -> s.getKey().replaceFirst(lastVersionsPath, ""))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        } catch (Exception ex) {
            NotificationGuiHelper.showEventAndBaloon("Error " + ex.getMessage(), ERROR);
        }
        return Lists.newArrayList();
    }



    /**
     * Upload to S3
     * @param project
     * @param fileToUpload
     * @param originalFile
     * @param uploadInfo
     */
    public static void uploadFile(@NotNull Project project, @NotNull VirtualFile fileToUpload,
        @NotNull VirtualFile originalFile, @NotNull UploadInfo uploadInfo) {

        final String projectName = getProjectName(project);
        final String bucketName = getBucketName(projectName);
        final String lastVersionsPath = getLastVersionsPath();

        // connect to S3
        final AmazonS3 s3Client = getS3Client(project);

        // get current project version
        String versionFilePath = lastVersionsPath + uploadInfo.getFullFileName();
        S3Object versionS3object = s3Client.getObject(new GetObjectRequest(bucketName, versionFilePath));
        String version = FileHelper.getFirstLineFromFile(versionS3object.getObjectContent());

        try {
            String patchPath = getVersionsPath() + version + separator + getPatchPath();
            String deployedProjectPath = getDeployedProjectPath(s3Client, bucketName, patchPath, uploadInfo);
            String deployPath = deployedProjectPath + getDeployPath(fileToUpload, originalFile);

            // upload file
            s3Client.putObject(new PutObjectRequest(bucketName, deployPath, new File(fileToUpload.getCanonicalPath())));
            NotificationGuiHelper.showEventAndBaloon("Uploaded to " + deployPath, INFORMATION);

        } catch (Exception ex) {
            NotificationGuiHelper.showEventAndBaloon("Error " + ex.getMessage(), ERROR);
        }
    }

    @NotNull
    private static String getProjectName(@NotNull Project project)
    {
        return customProperties.getProperty(PROJECT_NAME, project.getName());
    }

    @NotNull
    private static String getBucketName(@NotNull String projectName)
    {
        return customProperties.getProperty(S3_BUCKET_KEY, projectName + S3_BUCKET_SUFFIX);
    }

    @NotNull
    private static String getLastVersionsPath()
    {
        return customProperties.getProperty(LAST_VERSIONS_PATH_KEY, LAST_VERSIONS_PATH) + separator;
    }

    @NotNull
    private static String getVersionsPath()
    {
        return customProperties.getProperty(VERSIONS_PATH_KEY, VERSIONS_PATH) + separator;
    }

    @NotNull
    private static String getPatchPath()
    {
        return customProperties.getProperty(PATCH_PATH_KEY, PATCH_PATH) + separator;
    }

    /**
     * Path inside patch folder
     * @param s3Client
     * @param bucketName
     * @param patchPath
     * @param uploadInfo
     * @return
     * @throws IllegalArgumentException
     */
    @NotNull
    private static String getDeployedProjectPath(@NotNull AmazonS3 s3Client, @NotNull String bucketName,
        @NotNull String patchPath, @NotNull UploadInfo uploadInfo) throws IllegalArgumentException
    {
        String deployedProjectName = customProperties.getProperty(DEPLOY_PATH_KEY);
        if (deployedProjectName != null) {
            return patchPath + deployedProjectName
                + (isNotEmpty(deployedProjectName) ? separator : EMPTY);
        }

        // get "webapp" from "magnolia"
        final String deployedProjectSuffix = PROJECT_NAME_FROM_CONFIG_TO_DEPLOYED.get(uploadInfo.getProjectName());

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
     * @param fileToUpload
     * @param originalFile
     * @return
     */
    @NotNull
    public static String getDeployPath(@NotNull VirtualFile fileToUpload, @NotNull VirtualFile originalFile)
        throws IllegalArgumentException
    {
        String originalPath = originalFile.getCanonicalPath();
        if (originalPath == null || !originalPath.contains(SRC_MAIN)) {
            throw new IllegalArgumentException("Could not get deploy originalPath");
        }

        final String pathInSrcMain = substringAfter(originalPath, SRC_MAIN);

        Optional<Map.Entry<String, String>> mapping = SRC_FROM_PROJECT_TO_DEPLOYED.entrySet().stream()
            .filter(e -> pathInSrcMain.startsWith(e.getKey()))
            .findFirst();

        if (!mapping.isPresent()) {
            throw new IllegalArgumentException("Could not get deploy path "+ originalPath);
        }

        String convertedPath = pathInSrcMain.replaceFirst(mapping.get().getKey(), mapping.get().getValue());
        return convertedPath.replace("." + originalFile.getExtension(), "." + fileToUpload.getExtension());
    }



    public static void checkSystemVars(@NotNull Project project) throws IllegalArgumentException
    {
        String projectPrefix = getProjectName(project).toUpperCase() + "_";
        String key = projectPrefix + AWS_SYSTEM_ACCESS_KEY;
        String secret = projectPrefix + AWS_SYSTEM_SECRET_ACCESS_KEY;

        if (isEmpty(System.getenv(key)) || isEmpty(System.getenv(secret))) {

            throw new IllegalArgumentException("System Variables " + key + " and " + secret + " not found");
        }
    }

    private static AmazonS3 getS3Client(@NotNull Project project)
    {
        setSystemPropertiesFromEnvs(project);
        final AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
            .withRegion(Regions.EU_WEST_1)
            .build();
        return s3Client;
    }


    /**
     * Update system properties before any call based on current project
     * @param project
     */
    private static void setSystemPropertiesFromEnvs(@NotNull Project project)
    {
        String projectPrefix = getProjectName(project).toUpperCase() + "_";
        String awsAccessKey = System.getenv(projectPrefix + AWS_SYSTEM_ACCESS_KEY);
        String awsSecretAccessKey = System.getenv(projectPrefix + AWS_SYSTEM_SECRET_ACCESS_KEY);

        System.setProperty(AWS_PROPERTY_ACCESS_KEY, awsAccessKey);
        System.setProperty(AWS_PROPERTY_SECRET_ACCESS_KEY, awsSecretAccessKey);
    }


}
