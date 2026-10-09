package dev.confluenceshare;

import com.atlassian.beehive.ClusterLockService;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import java.util.Properties;
import java.util.concurrent.locks.Lock;
import java.util.function.Supplier;

public final class ShareStore {
  private static final String PREFIX = "dev.confluenceshare.";
  private final PluginSettingsFactory settings;
  private final ClusterLockService locks;

  public ShareStore(PluginSettingsFactory settings, ClusterLockService locks) {
    this.settings = settings;
    this.locks = locks;
  }

  <T> T locked(long pageId, Supplier<T> action) {
    Lock lock = locks.getLockForName(PREFIX + "page." + pageId);
    lock.lock();
    try {
      return action.get();
    } finally {
      lock.unlock();
    }
  }

  Share byToken(String token) {
    if (!Secrets.validToken(token)) return null;
    PluginSettings global = settings.createGlobalSettings();
    Object value = global.get(PREFIX + "link." + Secrets.digest(token));
    Share share = value instanceof Properties p ? Share.from(p) : null;
    // Both records must agree: a failed page-index write must not leave an unmanageable public
    // link.
    return share != null
            && Secrets.equal(token, share.token())
            && token.equals(global.get(PREFIX + "page." + share.pageId()))
        ? share
        : null;
  }

  Share forPage(long pageId) {
    Object token = settings.createGlobalSettings().get(PREFIX + "page." + pageId);
    return token instanceof String t ? byToken(t) : null;
  }

  void save(Share share) {
    settings
        .createGlobalSettings()
        .put(PREFIX + "link." + Secrets.digest(share.token()), share.properties());
    settings.createGlobalSettings().put(PREFIX + "page." + share.pageId(), share.token());
  }

  void remove(Share share) {
    // Delete the capability first, so a partial failure never leaves a working old link.
    settings.createGlobalSettings().remove(PREFIX + "link." + Secrets.digest(share.token()));
    settings.createGlobalSettings().remove(PREFIX + "page." + share.pageId());
  }
}
