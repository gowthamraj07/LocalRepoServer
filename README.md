# LocalRepoServer

### About
This application acts as a local repository server for Gradle projects.


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

### How to configure the Gradle project to point to local repository server
Use the following code inside repository block of build.gradle file
```
    maven {
        url "http://localhost:8082/cache"
    }
```
