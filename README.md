# IntelliJ IDEA S3 Upload Plugin

## How to use



## S3 bucket
Default S3 folders structure can be changed by s3upload.properties in project root folder.  
All following ${} are the available properties.

The project name is automatically inferred from the project, but can be overridden with the property:
```
project.name
```

Bucket name is ${project.name}-releases. It can be configured with the property:
```
bucket.name
```

## S3 upload configs
Available projects and relative versions are read from existing files in:
```
${bucket.name}/${last.versions.path}
```

From these files will be retrieved:
 - the current version: from file first line.
 - the project to deploy: from file name suffix. 
 
Eg: pro-esb.txt containing 12.3.4 will search a project ending with "esb" in deploy folder, version 12.3.4
 
## S3 deploy path
 
The deploy path is:  
```
${bucket.name}/${versions.path}/versionReadFromVersionFile/${patch.path}/  
```

Defaults are:  
```
${project.name}-releases/versions/versionReadFromVersionFile/patch/  
```

## Mapping the deployed project 

The suffix of folders in "versions.path" will be matched with the "last.versions.path" suffix.  
Default mappings are:
```
"esb"      -> "esb"
"magnolia" -> "webapp"
"hybris"   -> "todo"
 ```
Note: deploy folder will be skipped if only one project exists.

Custom mappings can be defined with the "mapping.project." prefix:
```
mapping.project.example1 = example2
```

## Mapping the folders inside the project 

Folders that change path when deployed can be configured.
Default mappings are:
```
"/src/main/java/"      -> "/WEB-INF/classes/"
"/src/main/resources/" -> "/WEB-INF/classes/"
"/src/main/webapp/"   -> "/"
 ```
 
Custom mappings can be defined with:
```
deploy.mapping.folder./pathA/ = /pathB/
```

The path inside the deployed project folder will start after the found custom mapping by default.
It will start from module source path if no custom mapping is found or the following key is true:
```
deploy.mapping.keep.source.path = true
```


## Mapping compiled files 

This is how a file is mapped to its compiled ones. 
Java and groovy are already configured. Eg:
```
compile.mapping.extension.java = class
compile.mapping.subclasses.java = $
compile.mapping.path.java = /src/main/java:/target/classes,/path3:/path4
 ```
 

## Es:
Upload config:
```
${project.name}-releases/last/pro-magnolia.txt containing 12.3.5 
```
will upload to:
```
${project.name}-releases/versions/12.3.5/patch/(.*-webapp)?/
```
The full .*-webapp name is retrieved from s3 automatically. If only one project exists, it is skipped.


