package com.openmind.intellij.bean;

import org.apache.commons.lang.StringUtils;


public class UploadConfig
{
    private String projectName;
    private String fullFileName;
    private String fileName;
    private String subProjectName;
    private boolean isProd;

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
}
