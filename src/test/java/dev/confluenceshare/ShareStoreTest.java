package dev.confluenceshare;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ShareStoreTest {
    private Share share() {
        return new Share(1, "TEST", "admin", Secrets.token(), "", Secrets.token(), 0, 0);
    }

    @Test
    void partialCreationCannotLeaveAnActiveLinkWithoutManagementEntry() {
        var memory = new TestEnvironment.Memory();
        var store = new ShareStore(memory, memory);
        Share share = share();
        memory.failedPutKey = "dev.confluenceshare.page.1";
        assertThrows(IllegalStateException.class, () -> store.save(share));
        assertNull(store.forPage(1));
        assertNull(store.byToken(share.token()), "Unindexed records must not allow public access");
    }

    @Test
    void staleTokenRecordsCannotBypassTheCurrentPageBinding() {
        var memory = new TestEnvironment.Memory();
        var store = new ShareStore(memory, memory);
        Share old = share(), current = share();
        store.save(old);
        store.save(current);
        assertNull(store.byToken(old.token()));
        assertEquals(current, store.byToken(current.token()));
    }
}
