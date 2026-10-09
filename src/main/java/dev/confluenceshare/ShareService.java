package dev.confluenceshare;

import java.time.Clock;

public final class ShareService {
  private final ShareStore store;
  private final PageAccess pages;
  private final Clock clock;

  public ShareService(ShareStore store, ConfluencePages pages) {
    this(store, pages, Clock.systemUTC());
  }

  ShareService(ShareStore store, PageAccess pages, Clock clock) {
    this.store = store;
    this.pages = pages;
    this.clock = clock;
  }

  Share forPage(long pageId) {
    return store.forPage(pageId);
  }

  Share create(long pageId, String userKey, String password) {
    return store.locked(
        pageId,
        () -> {
          PageAccess.Document page = pages.managedPage(pageId, userKey);
          String hash = Secrets.hashPassword(password);
          Share previous = store.forPage(pageId);
          if (previous != null) store.remove(previous);
          Share share =
              new Share(
                  pageId, page.spaceKey(), userKey, Secrets.token(), hash, Secrets.token(), 0, 0);
          store.save(share);
          return share;
        });
  }

  void revoke(long pageId, String userKey) {
    store.locked(
        pageId,
        () -> {
          pages.managedPage(pageId, userKey);
          Share share = store.forPage(pageId);
          if (share != null) store.remove(share);
          return null;
        });
  }

  Share available(String token) {
    Share share = store.byToken(token);
    if (share == null) throw new Unavailable();
    pages.sharedPage(share);
    return share;
  }

  boolean unlock(String token, String password) {
    Share initial = available(token);
    return store.locked(
        initial.pageId(),
        () -> {
          Share share = available(token);
          if (!share.protectedByPassword()) return true;
          long now = clock.instant().getEpochSecond();
          // A slower cluster clock must keep the shared counter until the stored window ends.
          boolean reset = now - share.attemptWindow() >= 60;
          int attempts = reset ? 0 : share.attempts();
          if (attempts >= 10) throw new TooManyAttempts();
          store.save(share.withAttempt(reset ? now : share.attemptWindow(), attempts + 1));
          return Secrets.verifyPassword(password, share.passwordHash());
        });
  }

  static final class Unavailable extends RuntimeException {}

  static final class Forbidden extends RuntimeException {}

  static final class TooManyAttempts extends RuntimeException {}
}
