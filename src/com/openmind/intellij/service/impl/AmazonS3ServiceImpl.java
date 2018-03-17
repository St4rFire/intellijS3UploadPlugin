package com.openmind.intellij.service.impl;

import static com.intellij.notification.NotificationType.ERROR;
import static com.intellij.notification.NotificationType.INFORMATION;
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
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.util.CollectionUtils;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.transfer.MultipleFileUpload;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;
import com.amazonaws.services.s3.transfer.Upload;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
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
    private static final String DEFAULT_REGION = "EU_WEST_1";
    private static final String AWS_REGION = "aws.region";

    // path defaults
    private static final String S3_BUCKET_SUFFIX = "-releases";
    private static final String LAST_VERSIONS_PATH = "last";
    private static final String VERSIONS_PATH = "versions";
    private static final String PATCH_PATH = "patch";

    // keys in custom properties file
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


    // todo list
    /**
     * Upload to S3
     * @param originalFiles
     * @param uploadConfig
     */
    @Override
    public void uploadFiles(@NotNull List<VirtualFile> originalFiles, @NotNull UploadConfig uploadConfig) {

        try {
            withS3Client((s3Client) -> uploadFile(s3Client, originalFiles, uploadConfig));

        } catch (Exception ex) {
            NotificationHelper.showEventAndBalloon(project, "Error uploading: " + ex.getMessage(), ERROR);
        }
    }


    /**
     * Upload to S3
     * @param s3Client
     * @param originalFiles
     * @param uploadConfig
     */
    private void uploadFile(@NotNull AmazonS3 s3Client, @NotNull List<VirtualFile> originalFiles,
        @NotNull UploadConfig uploadConfig) throws IllegalArgumentException {

        final String projectName = getProjectName();
        final String bucketName = getBucketName(projectName);
        final String lastVersionsPath = getLastVersionsPath();

        // get current project version from S3
        final String versionFilePath = lastVersionsPath + uploadConfig.getFullFileName();
        final String version = readS3FileFirstLine(s3Client, bucketName, versionFilePath, "Version file");

        // get deploy path
        final String patchPath = getVersionsPath() + version + separator + getPatchPath();
        final String deployedProjectPath = getDeployedProjectPath(s3Client, bucketName, patchPath, uploadConfig);

        // get files to really upload
        ListMultimap<String, VirtualFile> projectRelativePathTofiles = ArrayListMultimap.create();
        for (VirtualFile originalFile : originalFiles) {
            final VirtualFile outputFile = outputFileService.getOutputFile(originalFile);
            final String projectRelativeDeployPath = outputFileService.getProjectRelativeDeployPath(originalFile);

            // check timestamp
            long originalFileLastModified = FileHelper.getLastModified(originalFile);
            long outputFileLastModified = FileHelper.getLastModified(outputFile);
            if (originalFile != outputFile && originalFileLastModified > outputFileLastModified &&
                Messages.showOkCancelDialog(project,
                    "Compiled file " + outputFile.getName() +" is older than the source file. Continue anyway?",
                    "Warning!", "OK", "Cancel", Messages.getInformationIcon()) != Messages.OK) {
                NotificationHelper.showEventAndBalloon(project, "Deploy stopped", INFORMATION);
                return;
            }

            projectRelativePathTofiles.put(projectRelativeDeployPath, outputFile);
            projectRelativePathTofiles.putAll(projectRelativeDeployPath, outputFileService.findSubclasses(originalFile, outputFile));
        }

        // upload files
        // https://aws.amazon.com/blogs/developer/amazon-s3-transfermanager/
        projectRelativePathTofiles.asMap().forEach((projectRelativeDeployPath, virtualFiles) -> {
            final String fullS3DeployPath = deployedProjectPath + projectRelativeDeployPath;
            List<File> filesToUpload = virtualFiles.stream()
                .map(v -> new File(v.getCanonicalPath()))
                .collect(Collectors.toList());

            try {
                TransferManager tx = TransferManagerBuilder.standard().withS3Client(s3Client).build();
                MultipleFileUpload xfer = tx.uploadFileList(bucketName, fullS3DeployPath, filesToUpload.get(0).getParentFile(), filesToUpload);
                xfer.waitForCompletion();

                String uploadedFiles = virtualFiles.stream()
                    .map(f -> fullS3DeployPath + separator + f.getName())
                    .collect(Collectors.joining(System.lineSeparator()));

                String message = (uploadedFiles.isEmpty() ? EMPTY : System.lineSeparator()) + uploadedFiles;
                NotificationHelper.showEventAndBalloon(project, "Uploaded to: " + message, INFORMATION);
            } catch (InterruptedException | AmazonServiceException e) {
                // to do custom exc
                throw new IllegalArgumentException(e);
            }
        });
    }

    /**
     * Get all available configs
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


    @NotNull
    private String readS3FileFirstLine(@NotNull AmazonS3 s3Client, @NotNull String bucketName,
        @NotNull String versionFilePath, @NotNull String fileDescription)
    {

        final S3Object versionS3object = s3Client.getObject(new GetObjectRequest(bucketName, versionFilePath));
        if (versionS3object == null || versionS3object.getObjectContent() == null) {
            throw new IllegalArgumentException(fileDescription + " not found, searched: " + versionFilePath);
        }

        final String version = FileHelper.getFirstLineFromFile(versionS3object.getObjectContent());
        if (StringUtils.isEmpty(version)) {
            throw new IllegalArgumentException(fileDescription+ " is empty, searched: " + versionFilePath);
        }
        return version;
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
        String projectKey = addProjectPrefix(AWS_SYSTEM_ACCESS_KEY);
        String projectSecret = addProjectPrefix(AWS_SYSTEM_SECRET_ACCESS_KEY);
        String keyValue = getProjectSystemEnvValue(projectKey);
        String secretValue = getProjectSystemEnvValue(projectSecret);

        if (isEmpty(keyValue) || isEmpty(secretValue)) {
            throw new IllegalArgumentException("System Variable " + projectKey + " or " + projectSecret + " not found");
        }
    }

    private String addProjectPrefix(String systemEnv) {
        return getProjectName().toUpperCase() + "_" + systemEnv;
    }

    private String getProjectSystemEnvValue(String projectSystemEnv) {
        return defaultString(System.getenv(projectSystemEnv), execEcho(projectSystemEnv));
    }


    private void withS3Client(@NotNull Consumer<AmazonS3> consumer) {
        String existingAwsKey = null;
        String existingAwsSecretAccessKey = null;

        try {
            // save existing values
            existingAwsKey = System.getProperty(AWS_PROPERTY_ACCESS_KEY);
            existingAwsSecretAccessKey = System.getProperty(AWS_PROPERTY_SECRET_ACCESS_KEY);

            // set project values
            String projectAwsAccessKey = addProjectPrefix(AWS_SYSTEM_ACCESS_KEY);
            String projectAwsSecretAccessKey = addProjectPrefix(AWS_SYSTEM_SECRET_ACCESS_KEY);
            System.setProperty(AWS_PROPERTY_ACCESS_KEY, getProjectSystemEnvValue(projectAwsAccessKey));
            System.setProperty(AWS_PROPERTY_SECRET_ACCESS_KEY, getProjectSystemEnvValue(projectAwsSecretAccessKey));

            // build client
            String region = customProperties.getProperty(AWS_REGION, DEFAULT_REGION);
            final AmazonS3 s3Client = AmazonS3ClientBuilder.standard()
                .withRegion(Regions.valueOf(region))
                .build();

            consumer.accept(s3Client);

        } finally {

            // replace existing values
            if (isNotEmpty(existingAwsKey)) {
                System.setProperty(AWS_PROPERTY_ACCESS_KEY, existingAwsKey);
            }
            if (isNotEmpty(existingAwsSecretAccessKey)) {
                System.setProperty(AWS_PROPERTY_SECRET_ACCESS_KEY, existingAwsSecretAccessKey);
            }
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
            String s = stdInput.readLine();
            return s;
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
        final List<UploadConfig> uploadConfigs = Lists.newArrayList();

        try {
            withS3Client((s3Client) -> {

                ListObjectsV2Request request = new ListObjectsV2Request()
                    .withBucketName(bucketName)
                    .withPrefix(lastVersionsPath);

                ListObjectsV2Result listing = s3Client.listObjectsV2(request);
                uploadConfigs.addAll(listing.getObjectSummaries().stream()
                    .map(s -> replaceOnce(s.getKey(), lastVersionsPath, ""))
                    .filter(s -> !s.isEmpty())
                    .map(versionFileName -> new UploadConfig(projectName, versionFileName))
                    .collect(Collectors.toList()));
            });

        } catch (Exception ex) {
            throw new IllegalArgumentException("Error reading upload configs: " + ex.getMessage(), ex);
        }

        if (CollectionUtils.isEmpty(uploadConfigs)) {
            throw new IllegalArgumentException("No config files found in " + bucketName + separator + lastVersionsPath);
        }
        return uploadConfigs;
    }


}
