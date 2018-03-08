package com.openmind.intellij.helper;

import static com.intellij.notification.NotificationType.*;

import java.io.File;
import java.util.List;
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
import com.google.common.collect.Lists;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;


/**
 * Upload file to S3. S3 env credentials have to be set
 */
public class AmazonS3Helper {

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

    private static final String ESB_PROJECT_SUFFIX = "-esb";
    private static final String WEBAPP_PROJECT_SUFFIX = "-product-webapp";
    private static final String SRC_MAIN = "/src/main/";

    private static final String AWS_SYSTEM_ACCESS_KEY = "AWS_ACCESS_KEY";
    private static final String AWS_SYSTEM_SECRET_ACCESS_KEY = "AWS_SECRET_ACCESS_KEY";
    private static final String AWS_PROPERTY_ACCESS_KEY = "aws.accessKeyId";
    private static final String AWS_PROPERTY_SECRET_ACCESS_KEY = "aws.secretKey";

    private static Properties customProperties = new Properties();
    private static boolean isSingleProject = false;


    public static void loadCustomProperties(@NotNull Project project)
    {
        AmazonS3Helper.customProperties = FileHelper.getProperties(
            project.getBasePath()
            + File.separator
            + S3_PROPERTIES_FILE);
    }

    public static void setSingleProject(boolean isSingleProject)
    {
        AmazonS3Helper.isSingleProject = isSingleProject;
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

        try {
            AmazonS3 s3Client = getS3Client(project);

            ListObjectsV2Request request = new ListObjectsV2Request()
                .withBucketName(bucketName)
                .withPrefix(lastVersionsPath);

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
     * @param versionFile
     */
    public static void uploadFile(@NotNull Project project, @NotNull VirtualFile fileToUpload,
        @NotNull VirtualFile originalFile, @NotNull String versionFile) {

        // todo check existence
        final String projectName = getProjectName(project);
        final String bucketName = getBucketName(projectName);
        final String lastVersionsPath = getLastVersionsPath();

        try {
            // connect to S3
            final AmazonS3 s3Client = getS3Client(project);

            // get current project version
            String versionFilePath = lastVersionsPath + versionFile;
            S3Object versionS3object = s3Client.getObject(new GetObjectRequest(bucketName, versionFilePath));
            String version = FileHelper.getFirstLineFromFile(versionS3object.getObjectContent());

            String deployPath = getVersionsPath()
                + version + File.separator
                + getPatchPath()
                + getDeployedProjectPath(versionFile, projectName)
                + convertToDeployPath(fileToUpload, originalFile);;

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
        return customProperties.getProperty(LAST_VERSIONS_PATH_KEY, LAST_VERSIONS_PATH) + File.separator;
    }

    @NotNull
    private static String getVersionsPath()
    {
        return customProperties.getProperty(VERSIONS_PATH_KEY, VERSIONS_PATH) + File.separator;
    }

    @NotNull
    private static String getPatchPath()
    {
        return customProperties.getProperty(PATCH_PATH_KEY, PATCH_PATH) + File.separator;
    }

    /**
     * Path inside patch folder
     * @param versionFile
     * @param projectName
     * @return
     */
    @NotNull
    private static String getDeployedProjectPath(@NotNull String versionFile, @NotNull String projectName)
    {
        String deployedProjectName = customProperties.getProperty(DEPLOY_PATH_KEY);
        if (deployedProjectName != null) {
            return deployedProjectName
                + (StringUtils.isNotEmpty(deployedProjectName) ? File.separator : StringUtils.EMPTY);
        }

        // skip containing folder if there is only one project
        if (isSingleProject) {
            return StringUtils.EMPTY;
        }

        return projectName
            + (versionFile.contains(ESB_PROJECT_SUFFIX) ? ESB_PROJECT_SUFFIX : WEBAPP_PROJECT_SUFFIX)
            + File.separator;
    }




    /**
     * Convert original path to path to upload
     *
     * @param fileToUpload
     * @param originalFile
     * @return
     */
    @NotNull
    public static String convertToDeployPath(@NotNull VirtualFile fileToUpload, @NotNull VirtualFile originalFile)
    {
        String originalPath = originalFile.getCanonicalPath();
        if (originalPath == null || !originalPath.contains(SRC_MAIN))
        {
            throw new IllegalArgumentException("Could not get deploy originalPath");
        }

        String relativePath = originalPath.substring(originalPath.indexOf(SRC_MAIN) + SRC_MAIN.length());

        if (relativePath.startsWith("java"))
        {
            relativePath = relativePath.replaceFirst("java", "WEB-INF/classes");
        }
        else if (relativePath.startsWith("resources"))
        {
            relativePath = relativePath.replaceFirst("resources", "WEB-INF/classes");
        }
        else if (relativePath.startsWith("webapp"))
        {
            relativePath = relativePath.replaceFirst("webapp/", "");

        }
        else
        {
            throw new IllegalArgumentException("Could not get deploy originalPath");
        }

        return relativePath.replace("." + originalFile.getExtension(), "." + fileToUpload.getExtension());
    }



    public static void checkSystemVars(@NotNull Project project) throws IllegalArgumentException
    {
        String projectPrefix = getProjectName(project).toUpperCase() + "_";
        String key = projectPrefix + AWS_SYSTEM_ACCESS_KEY;
        String secret = projectPrefix + AWS_SYSTEM_SECRET_ACCESS_KEY;

        if (StringUtils.isEmpty(System.getenv(key)) || StringUtils.isEmpty(System.getenv(secret))) {

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
