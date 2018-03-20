package com.openmind.intellij.bean;

import org.apache.commons.lang.StringUtils;


public class UploadConfig
{
    private final String projectName;
    private final String fullFileName;
    private final String fileName;
    private final String subProjectName;
    private final boolean isProd;
    private String version;

    public UploadConfig(String projectName, String versionFileName) {
        this.projectName = projectName;
        this.fullFileName = versionFileName;
        this.fileName = StringUtils.substringBeforeLast(versionFileName, ".");
        this.subProjectName = StringUtils.substringAfterLast(this.fileName, "-");
        this.isProd = this.fileName.startsWith("pro");
    }

    public String getProjectName()
    {
        return projectName;
    }

    public String getFullFileName()
    {
        return fullFileName;
    }

    public String getFileName()
    {
        return fileName;
    }

    public String getSubProjectName()
    {
        return subProjectName;
    }

    public boolean isProd()
    {
        return isProd;
    }

    public String getVersion()
    {
        return version;
    }

    public void setVersion(String version)
    {
        this.version = version;
    }
}
