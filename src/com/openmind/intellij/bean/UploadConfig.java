package com.openmind.intellij.bean;

import org.apache.commons.lang.StringUtils;


public class UploadConfig
{
    private String fullFileName;
    private String fileName;
    private String projectName;
    private boolean isProd;

    public UploadConfig(String versionFile) {
        this.fullFileName = versionFile;
        this.fileName = StringUtils.substringBeforeLast(versionFile, ".");
        this.projectName = StringUtils.substringAfterLast(this.fileName, "-");
        this.isProd = this.fileName.startsWith("pro");
    }


    public String getFullFileName()
    {
        return fullFileName;
    }

    public String getFileName()
    {
        return fileName;
    }

    public String getProjectName()
    {
        return projectName;
    }

    public boolean isProd()
    {
        return isProd;
    }
}
