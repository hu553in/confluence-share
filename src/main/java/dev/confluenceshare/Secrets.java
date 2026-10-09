package dev.confluenceshare;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

final class Secrets {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
  private static final int ITERATIONS = 600_000;

  static String token() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return ENCODER.encodeToString(bytes);
  }

  static boolean validToken(String token) {
    return token != null && token.matches("[A-Za-z0-9_-]{43}");
  }

  static String digest(String value) {
    try {
      return ENCODER.encodeToString(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  static String hashPassword(String password) {
    if (password == null || password.isEmpty()) return "";
    if (password.length() < 8 || password.length() > 256) {
      throw new IllegalArgumentException("Password must contain 8 to 256 characters.");
    }
    byte[] salt = DECODER.decode(token());
    return "pbkdf2-sha256$"
        + ITERATIONS
        + "$"
        + ENCODER.encodeToString(salt)
        + "$"
        + ENCODER.encodeToString(derive(password, salt));
  }

  static boolean verifyPassword(String password, String stored) {
    if (password == null || password.length() > 256 || stored.isEmpty()) return false;
    String[] parts = stored.split("\\$", -1);
    if (parts.length != 4
        || !parts[0].equals("pbkdf2-sha256")
        || !parts[1].equals(Integer.toString(ITERATIONS))) return false;
    try {
      return MessageDigest.isEqual(
          DECODER.decode(parts[3]), derive(password, DECODER.decode(parts[2])));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static byte[] derive(String password, byte[] salt) {
    char[] chars = password.toCharArray();
    PBEKeySpec spec = new PBEKeySpec(chars, salt, ITERATIONS, 256);
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    } finally {
      spec.clearPassword();
      Arrays.fill(chars, '\0');
    }
  }

  static String grant(Share share, Instant now) {
    String expires = Long.toString(now.plusSeconds(1800).getEpochSecond());
    return expires + "." + sign(share, expires);
  }

  static boolean validGrant(Share share, String grant, Instant now) {
    if (grant == null || grant.length() > 120) return false;
    String[] parts = grant.split("\\.", -1);
    if (parts.length != 2) return false;
    try {
      long expires = Long.parseLong(parts[0]);
      // The signed expiry is issued with a fixed TTL; another cluster node may have a slightly
      // slower clock.
      return expires > now.getEpochSecond() && equal(parts[1], sign(share, parts[0]));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  static boolean equal(String a, String b) {
    return a != null
        && b != null
        && MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  private static String sign(Share share, String expires) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(DECODER.decode(share.accessSecret()), "HmacSHA256"));
      return ENCODER.encodeToString(
          mac.doFinal((share.token() + ":" + expires).getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
