package dev.confluenceshare;

import com.atlassian.beehive.ClusterLock;
import com.atlassian.beehive.ClusterLockService;
import com.atlassian.sal.api.pluginsettings.PluginSettings;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.http.HttpServletRequest;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

/** Test host boundaries only. The store, renderer and HTTP handlers are the production classes. */
final class TestEnvironment implements AutoCloseable {
  static final String MANAGE = "/plugins/servlet/confluence-share/manage";
  static final String PUBLIC = "/plugins/servlet/confluence-share/s/";
  static final byte[] PNG =
      Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLttAAAAABJRU5ErkJggg==");
  final Memory memory = new Memory();
  final FakePages pages = new FakePages();
  final ShareStore store = new ShareStore(memory, memory);
  final ShareService shares = new ShareService(store, pages, Clock.systemUTC());
  final Server server = new Server();
  String base;

  void start(int port, String contextPath, boolean demo) throws Exception {
    pages.demo = demo;
    ServerConnector connector = new ServerConnector(server);
    connector.setHost("127.0.0.1");
    connector.setPort(port);
    server.addConnector(connector);
    ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
    context.setContextPath(contextPath);
    // Model a host authentication filter that reads parameters before dispatching to the plugin.
    Filter hostFilter =
        (request, response, chain) -> {
          request.setCharacterEncoding("UTF-8");
          request.getParameterMap();
          chain.doFilter(request, response);
        };
    context.addFilter(new FilterHolder(hostFilter), "/*", EnumSet.of(DispatcherType.REQUEST));
    context.addServlet(new ServletHolder(new ManageServlet(shares, pages)), MANAGE);
    context.addServlet(new ServletHolder(new PublicServlet(shares, pages)), PUBLIC + "*");
    server.setHandler(context);
    server.start();
    base =
        "http://127.0.0.1:"
            + connector.getLocalPort()
            + (contextPath.equals("/") ? "" : contextPath);
    pages.base = base;
  }

  @Override
  public void close() throws Exception {
    server.stop();
  }

  static final class FakePages implements PageAccess {
    volatile boolean accessible = true;
    final Set<String> editors = ConcurrentHashMap.newKeySet();
    volatile boolean demo;
    volatile String base = "http://localhost";
    volatile Document document =
        new Document(
            1,
            "TEST",
            "Startup guide",
            """
                <h2>Before you start</h2><p>Check the <strong>settings</strong> and save a backup.</p>
                <p><ac:inline-comment-marker ac:ref="test-comment">Do not delete the backup.</ac:inline-comment-marker></p>
                <ac:task-list><ac:task><ac:task-status>complete</ac:task-status><ac:task-body>Check settings</ac:task-body></ac:task>
                <ac:task><ac:task-status>incomplete</ac:task-status><ac:task-body>Save a backup</ac:task-body></ac:task></ac:task-list>
                <table><tbody><tr><th>Parameter</th><th>Value</th></tr><tr><td>Environment</td><td>Test</td></tr></tbody></table>
                <ac:structured-macro ac:name="code"><ac:plain-text-body><![CDATA[curl https://example.com/health]]></ac:plain-text-body></ac:structured-macro>
                <ac:link><ri:page ri:content-title="Home"/><ac:link-body><ac:image ac:alt="Sample image" ac:title="Diagram for testing the public link"><ri:attachment ri:filename="image.png">
                <ri:page ri:space-key="TEST" ri:content-title="Startup guide"/></ri:attachment></ac:image></ac:link-body></ac:link>
                <p><ac:link><ri:attachment ri:filename="guide.txt"/><ac:plain-text-link-body><![CDATA[Download guide]]></ac:plain-text-link-body></ac:link></p>
                """);
    final Map<String, byte[]> files =
        new ConcurrentHashMap<>(
            Map.of(
                "image.png",
                PNG,
                "guide.txt",
                "Guide".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "unlisted.txt",
                new byte[] {1}));

    FakePages() {
      editors.addAll(Set.of("admin", "editor"));
    }

    @Override
    public String currentUserKey(HttpServletRequest request) {
      return demo ? "admin" : request.getHeader("X-Test-User");
    }

    @Override
    public Document managedPage(long id, String user) {
      if (id != document.id()) throw new ShareService.Unavailable();
      if (!accessible || user == null || !editors.contains(user))
        throw new ShareService.Forbidden();
      return document;
    }

    @Override
    public Document sharedPage(Share share) {
      if (!accessible
          || share.pageId() != document.id()
          || !document.spaceKey().equals(share.spaceKey())
          || !editors.contains(share.creatorKey())) throw new ShareService.Unavailable();
      return document;
    }

    @Override
    public Asset asset(Document page, String name) {
      byte[] data = files.get(name);
      if (data == null) throw new ShareService.Unavailable();
      String type = name.endsWith(".png") ? "image/png" : "text/plain";
      return new Asset(name, type, data.length, new ByteArrayInputStream(data));
    }

    @Override
    public String baseUrl() {
      return base;
    }
  }

  static final class Memory implements PluginSettingsFactory, PluginSettings, ClusterLockService {
    private final Map<String, Object> values = new ConcurrentHashMap<>();
    private final Map<String, LocalLock> locks = new ConcurrentHashMap<>();
    String failedPutKey;

    @Override
    public PluginSettings createSettingsForKey(String key) {
      return this;
    }

    @Override
    public PluginSettings createGlobalSettings() {
      return this;
    }

    @Override
    public Object get(String key) {
      return copy(values.get(key));
    }

    @Override
    public Object put(String key, Object value) {
      if (key.equals(failedPutKey))
        throw new IllegalStateException("Simulated settings write failure");
      return values.put(key, copy(value));
    }

    @Override
    public Object remove(String key) {
      return values.remove(key);
    }

    @Override
    public ClusterLock getLockForName(String name) {
      return locks.computeIfAbsent(name, ignored -> new LocalLock());
    }

    private static Object copy(Object value) {
      return value instanceof Properties p ? p.clone() : value;
    }
  }

  static final class LocalLock extends ReentrantLock implements ClusterLock {}

  static final class MutableClock extends Clock {
    Instant now = Instant.parse("2026-10-09T00:00:00Z");

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
