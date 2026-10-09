package dev.confluenceshare;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ShareServiceTest {
    @Test
    void storesPersistAcrossServiceInstancesAndRotationAndRevokeInvalidateLinks() {
        var memory = new TestEnvironment.Memory();
        var pages = new TestEnvironment.FakePages();
        var first = new ShareService(new ShareStore(memory, memory), pages, Clock.systemUTC());
        var second = new ShareService(new ShareStore(memory, memory), pages, Clock.systemUTC());
        Share old = first.create(1, "admin", "");
        assertEquals(old, second.available(old.token()));
        Share replacement = second.create(1, "admin", "");
        assertThrows(ShareService.Unavailable.class, () -> first.available(old.token()));
        assertEquals(replacement, first.forPage(1));
        first.revoke(1, "admin");
        assertThrows(ShareService.Unavailable.class, () -> second.available(replacement.token()));
        assertNull(second.forPage(1));
    }

    @Test
    void permissionLossOrMovingThePageClosesTheLink() {
        var env = new TestEnvironment();
        Share share = env.shares.create(1, "admin", "");
        assertThrows(ShareService.Forbidden.class, () -> env.shares.revoke(1, "viewer"));
        assertThrows(ShareService.Forbidden.class, () -> env.shares.create(1, "viewer", ""));
        env.pages.accessible = false;
        assertThrows(ShareService.Unavailable.class, () -> env.shares.available(share.token()));
        env.pages.accessible = true;
        env.pages.document = new PageAccess.Document(1, "OTHER", "Moved", "<p>Moved</p>");
        assertThrows(ShareService.Unavailable.class, () -> env.shares.available(share.token()));
    }

    @Test
    void limitsPasswordAttemptsAcrossInstancesAndResetsAfterAMinute() {
        var memory = new TestEnvironment.Memory();
        var pages = new TestEnvironment.FakePages();
        var clock = new TestEnvironment.MutableClock();
        var first = new ShareService(new ShareStore(memory, memory), pages, clock);
        var second = new ShareService(new ShareStore(memory, memory), pages, clock);
        Share share = first.create(1, "admin", "a correct password");
        for (int i = 0; i < 10; i++) assertFalse((i % 2 == 0 ? first : second).unlock(share.token(), "wrong"));
        assertThrows(ShareService.TooManyAttempts.class, () -> second.unlock(share.token(), "a correct password"));
        clock.now = clock.now.plusSeconds(60);
        assertTrue(second.unlock(share.token(), "a correct password"));
    }

    @Test
    void concurrentCreationLeavesOneWorkingLink() throws Exception {
        var memory = new TestEnvironment.Memory();
        var pages = new TestEnvironment.FakePages();
        var first = new ShareService(new ShareStore(memory, memory), pages, Clock.systemUTC());
        var second = new ShareService(new ShareStore(memory, memory), pages, Clock.systemUTC());
        var pool = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Share>>();
            for (int i = 0; i < 12; i++) {
                var service = i % 2 == 0 ? first : second;
                futures.add(pool.submit(() -> service.create(1, "admin", "")));
            }
            for (var future : futures) future.get();
            int active = 0;
            for (var future : futures) {
                Share share = future.get();
                if (new ShareStore(memory, memory).byToken(share.token()) != null) active++;
            }
            assertEquals(1, active);
            assertNotNull(first.forPage(1));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aSlowerClusterClockCannotResetAnExhaustedPasswordLimit() {
        var memory = new TestEnvironment.Memory();
        var pages = new TestEnvironment.FakePages();
        var fasterClock = new TestEnvironment.MutableClock();
        var slowerClock = new TestEnvironment.MutableClock();
        slowerClock.now = fasterClock.now.minusSeconds(1);
        var fasterNode = new ShareService(new ShareStore(memory, memory), pages, fasterClock);
        var slowerNode = new ShareService(new ShareStore(memory, memory), pages, slowerClock);
        Share share = fasterNode.create(1, "editor", "a correct password");
        for (int i = 0; i < 10; i++) assertFalse(fasterNode.unlock(share.token(), "wrong"));
        assertThrows(ShareService.TooManyAttempts.class, () -> fasterNode.unlock(share.token(), "wrong"));
        assertThrows(ShareService.TooManyAttempts.class, () -> slowerNode.unlock(share.token(), "a correct password"));
        slowerClock.now = fasterClock.now.plusSeconds(59);
        assertThrows(ShareService.TooManyAttempts.class, () -> slowerNode.unlock(share.token(), "a correct password"));
        slowerClock.now = fasterClock.now.plusSeconds(60);
        assertTrue(slowerNode.unlock(share.token(), "a correct password"));
    }
}
