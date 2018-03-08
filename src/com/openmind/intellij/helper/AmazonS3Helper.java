package com.openmind.intellij.helper;

import java.io.File;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

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
import com.intellij.notification.NotificationType;
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


    private static String getProjectName(Project project)
    {
        return project.getName();
    }

    private static String getBucketName(String projectName)
    {
        return projectName + S3_BUCKET_SUFFIX;
    }

    private static String getLastVersionsPath()
    {
        return LAST_VERSIONS_PATH;
    }

    private static String getVersionsPath()
    {
        return VERSIONS_PATH;
    }


    /**
     * Get list of files indicating
     * @param project
     * @return
     */
    @NotNull
    public static List<String> getVersionFiles(@NotNull Project project) {
        final Properties customProperties = FileHelper.getCustomProperties(project); // metto in startup?
        final String projectName = getProjectName(project);
        final String bucketName = getBucketName(projectName);
        final String lastVersionsPath = getLastVersionsPath() + File.separator;

        try {
            AmazonS3 s3Client = getS3Client();

            ListObjectsV2Request request = new ListObjectsV2Request()
                .withBucketName(bucketName)
                .withPrefix(lastVersionsPath);

            ListObjectsV2Result listing = s3Client.listObjectsV2(request);
            return listing.getObjectSummaries().stream()
                .map(s -> s.getKey().replaceFirst(lastVersionsPath, ""))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());

        } catch (Exception ex) {
            NotificationGuiHelper.show("Error " + ex.getMessage(), NotificationType.ERROR);
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
            final AmazonS3 s3Client = getS3Client();

            // get current project version
            String versionFilePath = lastVersionsPath + File.separator + versionFile;
            S3Object versionS3object = s3Client.getObject(new GetObjectRequest(bucketName, versionFilePath));
            String version = FileHelper.getFirstLineFromFile(versionS3object.getObjectContent());

            // upload file
            String deployPath = FileHelper.getDeployPath(fileToUpload, originalFile);
            deployPath = getVersionsPath()
                + File.separator + version
                + File.separator + PATCH_PATH
                + File.separator + FileHelper.getProjectFolder(versionFile, projectName)
                + File.separator + deployPath;

            s3Client.putObject(new PutObjectRequest(bucketName, deployPath, new File(fileToUpload.getCanonicalPath())));
            NotificationGuiHelper.show("Uploaded to " + deployPath, NotificationType.INFORMATION);

        } catch (Exception ex) {
            NotificationGuiHelper.show("Error " + ex.getMessage(), NotificationType.ERROR);
        }
    }





    private static AmazonS3 getS3Client()
    {
        final AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
            .withRegion(Regions.EU_WEST_1)
            .build();
        return s3Client;
    }

}
