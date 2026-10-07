package com.localrepo.server.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class DownloadCoordinatorTest {

    private static final ArtifactPath PATH = ArtifactPath.of("g/a/maven-metadata.xml");
    private final Clock clock = Clock.systemUTC();
    private final DownloadCoordinator coordinator = new DownloadCoordinator(clock, Duration.ofSeconds(5),
            new DownloadTracker(clock));

    @Test
    void sharesADownloadThatIsStillRunning() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Download first = coordinator.join("k", PATH, d -> await(release));

        Download second = coordinator.join("k", PATH, d -> { });

        assertSame(first, second);
        release.countDown();
    }

    @Test
    void neverHandsOutADownloadThatHasAlreadyFinished() throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Download first = coordinator.join("k", PATH, d -> {
            d.notFound();
            finished.countDown();
            await(release); // finished, but its task has not returned yet
        });
        finished.await();

        Download second = coordinator.join("k", PATH, Download::notFound);

        assertNotSame(first, second);
        release.countDown();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
