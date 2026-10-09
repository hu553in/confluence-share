package dev.confluenceshare;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SecretsTest {
    private final Share share = new Share(1, "TEST", "admin", Secrets.token(), "hash", Secrets.token(), 0, 0);

    @Test
    void passwordsAreSaltedAndVerified() {
        String password = "Password café 123";
        String hash = Secrets.hashPassword(password);
        assertNotEquals(hash, Secrets.hashPassword(password));
        assertFalse(hash.contains(password));
        assertTrue(Secrets.verifyPassword(password, hash));
        assertFalse(Secrets.verifyPassword("wrong", hash));
        assertFalse(Secrets.verifyPassword(password, "pbkdf2-sha256$600000$!$!"));
    }

    @Test
    void emptyPasswordMakesAnOpenLinkAndShortPasswordsAreRejected() {
        assertEquals("", Secrets.hashPassword(""));
        assertEquals("", Secrets.hashPassword(null));
        String minimumPassword = "secret88";
        assertTrue(Secrets.verifyPassword(minimumPassword, Secrets.hashPassword(minimumPassword)));
        assertThrows(IllegalArgumentException.class, () -> Secrets.hashPassword("short77"));
        assertThrows(IllegalArgumentException.class, () -> Secrets.hashPassword("x".repeat(257)));
    }

    @Test
    void grantsExpireAndCannotBeTamperedWithOrUsedForAnotherShare() {
        Instant now = Instant.parse("2026-10-09T00:00:00Z");
        String grant = Secrets.grant(share, now);
        assertTrue(Secrets.validGrant(share, grant, now));
        assertTrue(Secrets.validGrant(share, grant, now.plusSeconds(1799)));
        assertFalse(Secrets.validGrant(share, grant, now.plusSeconds(1800)));
        assertFalse(Secrets.validGrant(share, grant + "x", now));
        String otherGrant = Secrets.grant(new Share(2, "TEST", "admin", Secrets.token(), "hash", share.accessSecret(), 0, 0), now);
        assertFalse(Secrets.validGrant(share, otherGrant, now));
        assertFalse(Secrets.validGrant(share, "999999999999999999999999.signature", now));
        assertFalse(Secrets.validGrant(share, null, now));
    }

    @Test
    void tokensHave256RandomBitsAndRejectPathCharacters() {
        String token = Secrets.token();
        assertTrue(Secrets.validToken(token));
        assertNotEquals(token, Secrets.token());
        assertFalse(Secrets.validToken(token + "/asset"));
        assertFalse(Secrets.validToken(null));
    }

    @Test
    void signedGrantWorksOnAClusterNodeWhoseClockIsSlightlyBehind() {
        Instant issued = Instant.parse("2026-10-09T00:00:00Z");
        String grant = Secrets.grant(share, issued);
        assertTrue(Secrets.validGrant(share, grant, issued.minusSeconds(1)));
        assertFalse(Secrets.validGrant(share, grant, issued.plusSeconds(1800)));
        assertFalse(Secrets.validGrant(share, grant + "x", issued.minusSeconds(1)));
    }
}
