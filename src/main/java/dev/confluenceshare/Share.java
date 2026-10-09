package dev.confluenceshare;

import java.util.Properties;

record Share(
    long pageId,
    String spaceKey,
    String creatorKey,
    String token,
    String passwordHash,
    String accessSecret,
    long attemptWindow,
    int attempts) {
  boolean protectedByPassword() {
    return !passwordHash.isEmpty();
  }

  Share withAttempt(long window, int count) {
    return new Share(
        pageId, spaceKey, creatorKey, token, passwordHash, accessSecret, window, count);
  }

  Properties properties() {
    Properties p = new Properties();
    p.setProperty("pageId", Long.toString(pageId));
    p.setProperty("spaceKey", spaceKey);
    p.setProperty("creatorKey", creatorKey);
    p.setProperty("token", token);
    p.setProperty("passwordHash", passwordHash);
    p.setProperty("accessSecret", accessSecret);
    p.setProperty("attemptWindow", Long.toString(attemptWindow));
    p.setProperty("attempts", Integer.toString(attempts));
    return p;
  }

  static Share from(Properties p) {
    try {
      return new Share(
          Long.parseLong(p.getProperty("pageId")),
          p.getProperty("spaceKey"),
          p.getProperty("creatorKey"),
          p.getProperty("token"),
          p.getProperty("passwordHash"),
          p.getProperty("accessSecret"),
          Long.parseLong(p.getProperty("attemptWindow")),
          Integer.parseInt(p.getProperty("attempts")));
    } catch (RuntimeException e) {
      throw new IllegalStateException("Invalid share record", e);
    }
  }
}
