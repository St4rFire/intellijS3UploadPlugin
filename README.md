# IntelliJ IDEA S3 Upload Plugin

The only needed if the project has a standard structure are the environment vars for S3 credentials:
${project.name}_AWS_ACCESS_KEY 
${project.name}_AWS_SECRET_ACCESS_KEY


S3 folders structure can be configured with s3upload.properties in project root folder
Project name is automatically inferred from the project. Can be configured with "project.name" property.
Bucket name is ${project.name}-releases. Can be configured with "bucket.name" property.

Available projects and relative versions are read from files in: ${bucket.name}/${last.versions.path}
From these files will be retrieved:
 - the current version from file text
 - the project to deploy from name suffix. Es pro-esb will search a project ending with "esb" in deploy folder
 
Deployed folders path is: ${bucket.name}/${versions.path}/versionReadFromVersionFile/${patch.path}
Defaults are: ${project.name}-releases/versions/versionReadFromVersionFile/patch
Folders in this path will be read and a match with the previously retrieved suffix will be searched. Default mappings are:

  - "esb"       -> "esb"
  - "magnolia"  -> "webapp"
  - "hybris"    -> "todo"
 
Note: deploy folder will be skipped if only one project exists

Es:
${project.name}-releases/last/pro-magnolia.txt containing 12.3.5 will upload to:
${project.name}-releases/versions/12.3.5/patch/(*-webapp)?


