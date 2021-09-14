# LocalRepoServer

### About
This application acts as a local repository server for Gradle projects.
-----

### How to start the local repository server
Run the following command in Terminal to start the Server.
```
java -jar server-1.0.0.jar --repos=<repo1>,<repo2>,...
```

Example (to use Maven Crental repository):
```
java -jar server-1.0.0.jar --repos=https://repo1.maven.org/maven2
```

Example (to use Google Crental repository):
```
java -jar server-1.0.0.jar --repos=https://dl.google.com/android/maven2
```

Example (to use Maven Crental repository and Google Crental repository):
```
java -jar server-1.0.0.jar --repos=https://repo1.maven.org/maven2,https://dl.google.com/android/maven2
```
-----

### How to configure the Gradle project to point to local repository server
Use the following code inside repository block of build.gradle file
```
maven {
    url "http://localhost:8082/cache"
}
```
-----

### How to initialize the local repository
 * Close all the IDEs (Eclipse/STS/IntelliJ/Android Studio)
 * delete the `~/.gradle/caches` folder
 * Open the IDE
 * clean and build the project `gradle clean build`
-----

### How it works
 * server.jar acts as an intermediate proxy server, that caches all the responses from actual repository, and save it in folder `local_repo`
 * And it creates records for every jar file in derby database
-----

### End points
 * `http://localhost:8082/` will display all the cached jar files list
 * `http://localhost:8082/delete` will delete all the redundant records from database
-----

### How to copy all the jars into a single folder
Use the following script to copy all the jars to single folder, that is shippable.

```
mkdir ~/jars
find $PWD -maxdepth 2 -type f | grep ".jar" | while read line; do cp $line ~/jars/. ; done
```
-----
