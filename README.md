# IntelliJ IDEA S3 Upload Plugin

## Plugin description
The main purpose of this plugin is to easily patch files from the local project to the one deployed on Amazon S3.  
A new action "Upload to S3" will appear in the context menu. It will display a submenu of available projects and versions automatically retrieved from S3.
The "Scroll to .class" action will be added too.

S3 credentials need to be configured as described below. No further configurations are required for the standard folder structure.  
Compiled files will be automatically uploaded instead of the source code. 
The upload of folders or multiple selected files is supported. 

## Custom properties

To add custom properties you can create the following file in the project root folder:  
```
s3upload.properties
```

## S3 credentials

AWS credentials must be set as variables in your environment using the prefix MYPROJECT_  
Example:
```
MYPROJECT_AWS_ACCESS_KEY
MYPROJECT_AWS_SECRET_ACCESS_KEY
```

The project name is inferred from the Intellij project name, but it can be overridden in the properties file:
```
project.name = myProject
```


Note: in latest Mac OS X Environment versions you will need to set the variables in the ~/Library/LaunchAgents/environment.plist to make them available to all applications, not just the Terminal:  
```
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  <string>my.startup</string>
  <key>ProgramArguments</key>
  <array>
    <string>sh</string>
    <string>-c</string>
    <string>
    launchctl setenv MYPROJECT_AWS_ACCESS_KEY ...
    launchctl setenv MYPROJECT_AWS_SECRET_ACCESS_KEY ...
    </string>

  </array>
  <key>RunAtLoad</key>
  <true/>
</dict>
</plist>
```

## S3 standard folder structure example

A .txt in a specific path (default value is '/last/') on S3 will describe the available projects name and versions:
```
{bucket.name}/last/uat-myWEBproject.txt (containing current version: 12.3.5)
{bucket.name}/last/pro-myWEBproject.txt (containing current production version: 12.3.0)
{bucket.name}/last/uat-myESBproject.txt (containing current production version: 3.3.0)
...
```
The deployed projects path will be:
```
{bucket.name}/versions/12.3.5/patch/.*-myWEBproject/
{bucket.name}/versions/12.3.0/patch/.*-myWEBproject/
{bucket.name}/versions/3.3.0/patch/.*-myESBproject/
```
The full .*-project name is retrieved from .txt suffix automatically. If only one project exists, the project folder is skipped.


## S3 Bucket

The default bucket name is **{project.name}-releases**.  

To completely override the bucket name you can use:
```
bucket.name = myBucket
```


## S3 upload configs
Deployed projects and relative versions are read from .txt files in:
```
{bucket.name}/{last.versions.path}/
```

Those files should have a suffix corresponding to the project name. Example:
```
uat-myWEBproject.txt
```

The file content is the current project version. Example:
```
12.3.5
```
 
These info will appear in the context menu
  
  
## S3 deploy path
 
This is the deployed project path.   
Supposing that the selected version is 12.3.5, the deploy path is:  
```
{bucket.name}/{versions.path}/12.3.5/{patch.path}/  
```

Defaults are:  
```
versions.path = versions
patch.path = patch  
```

So it would be:
```
{bucket.name}/versions/12.3.5/patch/  
```

## Deployed projects

The project to patch will be the one matching the suffix read from .txt file.  
Note: the deploy folder will be skipped if only one project exists.

Custom mappings can be defined with the "mapping.project." prefix:
```
mapping.project.suffixOfTxtFile = suffixOfDeployedProject
```

## Output path (path conversions)

Paths can be different from the local ones in the deployed project.  
Default mappings follow the maven war plugin:
```
/src/main/java/      -> /WEB-INF/classes/
/src/main/resources/ -> /WEB-INF/classes/
/src/main/webapp/    -> /
 ```
 
  
Custom mappings can be defined with:
```
deploy.mapping.folder./pathLocal/ = /pathDeployed/
deploy.mapping.folder./a/b/ = / (skip those folders)
```

If the file to upload is detected to be in an unmapped source folder, the source path will be replaced with the default path automatically.
The default output path is "/WEB-INF/classes/", but can be changed with the property: 
```
deploy.source.output = /custompath/
 ```


## Root path 

This affects the path relative to the deployed project root.  
The default behaviour is to truncate the path at the bExampleinning of the output path, but it can be changed with the property:
```
deploy.auto.source.mapping = ...
```

Depending on the chosen strategy, the path relative to the project folder will differ.  Consider the following local path:
```
anyPath/myProject/anyFolders/myModule/src/main/java/com/example/JavaFile.java
```


##### FROM_SOURCES strategy (default)
Starts from custom mapping transformation or auto source to output transformation, exception if not found.  
Deployed path:  
```   
/WEB-INF/classes/com/example/JavaFile.class
```

##### FROM_MODULE_NAME strategy
Starts from module name (included).  
Deployed path:  
```
/myModule/WEB-INF/classes/com/example/JavaFile.class
```

##### AFTER_PROJECT_ROOT strategy
Basically keep all folders.  
Deployed path:
```
/anyFolders/myModule/WEB-INF/classes/com/example/JavaFile.class
```

<br/>

##### Custom prefix
To add a custom path before the calculated Root path, use:

```
deploy.path.prefix = /rootFoldersInDeployedProject
```

