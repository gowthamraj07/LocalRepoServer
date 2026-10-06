package com.localrepo.server.setup;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs a real Gradle build through the wrapper in src/test/resources/gradle-wrapper. The distribution is kept in a
 * Gradle user home under the system temp dir, so it is downloaded once rather than per test or per clean build.
 */
final class GradleRunner {

    static final Path GRADLE_USER_HOME = Path.of(System.getProperty("java.io.tmpdir"), "localrepo-gradle-test-home");

    record Result(int exitCode, String output) {
    }

    private GradleRunner() {
    }

    static Result run(Path projectDir, List<String> arguments) throws IOException, InterruptedException {
        Path wrapperJar;
        try {
            wrapperJar = Path.of(GradleRunner.class.getResource("/gradle-wrapper/gradle-wrapper.jar").toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", wrapperJar.toString(), "org.gradle.wrapper.GradleWrapperMain",
                "--project-dir", projectDir.toString(), "--no-daemon", "--console=plain", "--stacktrace"));
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(projectDir.toFile()).redirectErrorStream(true);
        builder.environment().put("GRADLE_USER_HOME", GRADLE_USER_HOME.toString());
        builder.environment().remove("LOCALREPO_DISABLED");
        builder.environment().remove("LOCALREPO_URL");
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException("Gradle did not finish:\n" + output);
        }
        return new Result(process.exitValue(), output);
    }
}
