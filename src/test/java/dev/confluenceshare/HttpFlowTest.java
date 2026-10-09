package dev.confluenceshare;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HTTP integration on Jetty; Confluence permission and storage adapters are replaced by test boundaries.
 */
class HttpFlowTest {
    private TestEnvironment env;
    private HttpClient admin;
    private HttpClient visitor;
    private String csrf;

    @BeforeEach
    void start() throws Exception {
        env = new TestEnvironment();
        env.start(0, "/wiki", false);
        admin = client();
        visitor = client();
        var response = get(admin, TestEnvironment.MANAGE + "?pageId=1", true);
        assertEquals(200, response.statusCode());
        csrf = Jsoup.parse(response.body()).selectFirst("input[name=csrf]").val();
        assertEquals("8", Jsoup.parse(response.body()).selectFirst("input[name=password]").attr("minlength"));
    }

    @AfterEach
    void stop() throws Exception {
        env.close();
    }

    @Test
    void anonymousLinkRendersLiveContentAndOnlyReferencedSamePageFiles() throws Exception {
        Share share = create("");
        String path = TestEnvironment.PUBLIC + share.token();
        var response = get(visitor, path, false);
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Startup guide"));
        var rendered = Jsoup.parse(response.body());
        assertEquals("Diagram for testing the public link", rendered.selectFirst(".image-caption").text());
        assertTrue(rendered.select("article").text().contains("Do not delete the backup."));
        assertFalse(response.body().contains("test-comment"));
        assertEquals("no-store, private, max-age=0", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("same-origin", response.headers().firstValue("Referrer-Policy").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Security-Policy").orElseThrow().contains("default-src 'none'"));
        var image = getBytes(visitor, path + "/asset?name=image.png");
        assertEquals(200, image.statusCode());
        assertArrayEquals(TestEnvironment.PNG, image.body());
        assertEquals("image/png", image.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(404, get(visitor, path + "/asset?name=unlisted.txt", false).statusCode());
        var file = getBytes(visitor, path + "/asset?name=guide.txt");
        assertEquals("application/octet-stream", file.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(file.headers().firstValue("Content-Disposition").orElseThrow().startsWith("attachment;"));
        env.pages.document = new PageAccess.Document(1, "TEST", "Updated title", "<p>Updated text</p>");
        assertTrue(get(visitor, path, false).body().contains("Updated text"));
        assertEquals(404, get(visitor, path + "/asset?name=image.png", false).statusCode());
    }

    @Test
    void passwordGuardsTitleAndAssetsAndWorksWithoutAConfluenceAccount() throws Exception {
        String password = "Café1234";
        Share share = create(password);
        String path = TestEnvironment.PUBLIC + share.token();
        var gate = get(visitor, path, false);
        assertEquals(200, gate.statusCode());
        assertTrue(gate.body().contains("Enter password"));
        assertFalse(gate.body().contains(env.pages.document.title()));
        assertEquals(403, get(visitor, path + "/asset?name=image.png", false).statusCode());
        assertEquals(401, post(visitor, path, Map.of("password", "wrong"), false).statusCode());
        var unlocked = post(visitor, path, Map.of("password", password), false);
        assertEquals(303, unlocked.statusCode());
        String cookie = unlocked.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookie.contains("HttpOnly; SameSite=Lax"));
        assertTrue(cookie.contains("Path=/wiki" + path));
        assertFalse(cookie.contains(password));
        assertTrue(get(visitor, path, false).body().contains(env.pages.document.title()));
        assertEquals(200, getBytes(visitor, path + "/asset?name=image.png").statusCode());
        assertFalse(get(client(), path, false).body().contains(env.pages.document.title()));
    }

    @Test
    void revokeAndRotationInvalidateAlreadyUnlockedClientsAndImages() throws Exception {
        Share first = create("a correct password");
        String oldPath = TestEnvironment.PUBLIC + first.token();
        assertEquals(303, post(visitor, oldPath, Map.of("password", "a correct password"), false).statusCode());
        Share replacement = create("another password");
        assertNotEquals(first.token(), replacement.token());
        assertEquals(404, get(visitor, oldPath, false).statusCode());
        assertEquals(404, getBytes(visitor, oldPath + "/asset?name=image.png").statusCode());
        String path = TestEnvironment.PUBLIC + replacement.token();
        assertTrue(get(visitor, path, false).body().contains("Enter password"));
        assertEquals(303, post(admin, TestEnvironment.MANAGE,
                Map.of("csrf", csrf, "pageId", "1", "action", "revoke"), true).statusCode());
        assertEquals(404, get(visitor, path, false).statusCode());
    }

    @Test
    void managementRequiresPermissionAndCsrfAndRejectsCrossOriginPosts() throws Exception {
        assertEquals(403, get(visitor, TestEnvironment.MANAGE + "?pageId=1", false).statusCode());
        assertEquals(403, post(admin, TestEnvironment.MANAGE,
                Map.of("pageId", "1", "action", "create"), true).statusCode());
        String body = form(Map.of("csrf", csrf, "pageId", "1", "action", "create"));
        var request = HttpRequest.newBuilder(URI.create(env.base + TestEnvironment.MANAGE))
                .header("X-Test-User", "admin").header("Origin", "https://attacker.test")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        assertEquals(403, admin.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertNull(env.shares.forPage(1));
    }

    @Test
    void ordinaryEditorCanManageLinksAndLosingEditPermissionClosesPublicAccess() throws Exception {
        Share original = create("");
        HttpClient editor = client();
        var management = get(editor, TestEnvironment.MANAGE + "?pageId=1", "editor");
        assertEquals(200, management.statusCode());
        String editorCsrf = Jsoup.parse(management.body()).selectFirst("input[name=csrf]").val();
        assertEquals(403, get(editor, TestEnvironment.MANAGE + "?pageId=1", "viewer").statusCode());
        Map<String, String> create = Map.of("csrf", editorCsrf, "pageId", "1", "action", "create", "password", "");
        Map<String, String> revoke = Map.of("csrf", editorCsrf, "pageId", "1", "action", "revoke");
        // Use the same valid session token so the viewer is rejected by page permissions, not CSRF.
        assertEquals(403, post(editor, TestEnvironment.MANAGE, create, "viewer").statusCode());
        assertEquals(403, post(editor, TestEnvironment.MANAGE, revoke, "viewer").statusCode());
        assertEquals(original, env.shares.forPage(1));
        assertEquals(303, post(editor, TestEnvironment.MANAGE, create, "editor").statusCode());
        Share replacement = env.shares.forPage(1);
        assertEquals("editor", replacement.creatorKey());
        assertNotEquals(original.token(), replacement.token());
        assertEquals(404, get(visitor, TestEnvironment.PUBLIC + original.token(), false).statusCode());
        String path = TestEnvironment.PUBLIC + replacement.token();
        assertEquals(200, get(visitor, path, false).statusCode());
        assertEquals(200, getBytes(visitor, path + "/asset?name=image.png").statusCode());
        env.pages.editors.remove("editor");
        assertEquals(403, get(editor, TestEnvironment.MANAGE + "?pageId=1", "editor").statusCode());
        assertEquals(404, get(visitor, path, false).statusCode());
        assertEquals(404, getBytes(visitor, path + "/asset?name=image.png").statusCode());
        env.pages.editors.add("editor");
        assertEquals(303, post(editor, TestEnvironment.MANAGE, revoke, "editor").statusCode());
        assertEquals(404, get(visitor, path, false).statusCode());
    }

    @Test
    void refusesOversizedOrDuplicateFormsInvalidPathsAndForgedImageTypes() throws Exception {
        Share share = create("");
        String path = TestEnvironment.PUBLIC + share.token();
        for (String body : new String[]{"password=x&password=y", "password=" + "x".repeat(8200), "password=%ZZ"}) {
            var request = HttpRequest.newBuilder(URI.create(env.base + path))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            assertEquals(400, visitor.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        assertEquals(404, get(visitor, path + "/unexpected", false).statusCode());
        assertEquals(404, get(visitor, TestEnvironment.PUBLIC + "not-a-token", false).statusCode());
        env.pages.files.put("image.png", "<svg onload='alert(1)'/>".getBytes(StandardCharsets.UTF_8));
        var image = getBytes(visitor, path + "/asset?name=image.png");
        assertEquals("application/octet-stream", image.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(image.headers().firstValue("Content-Disposition").orElseThrow().startsWith("attachment;"));
        assertEquals("nosniff", image.headers().firstValue("X-Content-Type-Options").orElseThrow());
    }

    @Test
    void publisherPermissionLossMakesPageAndAssetsUnavailable() throws Exception {
        Share share = create("");
        String path = TestEnvironment.PUBLIC + share.token();
        env.pages.accessible = false;
        assertEquals(404, get(visitor, path, false).statusCode());
        assertEquals(404, get(visitor, path + "/asset?name=image.png", false).statusCode());
    }

    @Test
    void httpsCanonicalUrlKeepsCookiesSecureBehindATlsTerminatingProxy() throws Exception {
        Share share = create("a correct password");
        env.pages.base = env.base.replace("http://", "https://");
        var request = HttpRequest.newBuilder(URI.create(env.base + TestEnvironment.PUBLIC + share.token()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", URI.create(env.pages.base).resolve("/").toString())
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("password", "a correct password")))).build();
        var response = visitor.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(303, response.statusCode());
        assertTrue(response.headers().firstValue("Set-Cookie").orElseThrow().endsWith("; Secure"));
    }

    @Test
    void exhaustedPasswordLimitReturnsRetryAfterWithoutLeakingContent() throws Exception {
        Share share = create("a correct password");
        env.store.save(share.withAttempt(java.time.Instant.now().getEpochSecond(), 10));
        var response = post(visitor, TestEnvironment.PUBLIC + share.token(), Map.of("password", "a correct password"), false);
        assertEquals(429, response.statusCode());
        assertEquals("60", response.headers().firstValue("Retry-After").orElseThrow());
        assertFalse(response.body().contains(env.pages.document.title()));
    }

    private Share create(String password) throws Exception {
        var response = post(admin, TestEnvironment.MANAGE,
                Map.of("csrf", csrf, "pageId", "1", "action", "create", "password", password), true);
        assertEquals(303, response.statusCode(), response.body());
        var management = get(admin, TestEnvironment.MANAGE + "?pageId=1", true);
        assertTrue(management.body().contains("share-url"));
        return env.shares.forPage(1);
    }

    private HttpResponse<String> get(HttpClient client, String path, boolean loggedIn) throws Exception {
        return get(client, path, loggedIn ? "admin" : null);
    }

    private HttpResponse<String> get(HttpClient client, String path, String user) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(env.base + path));
        if (user != null) builder.header("X-Test-User", user);
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<byte[]> getBytes(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(env.base + path)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> post(HttpClient client, String path, Map<String, String> fields, boolean loggedIn) throws Exception {
        return post(client, path, fields, loggedIn ? "admin" : null);
    }

    private HttpResponse<String> post(HttpClient client, String path, Map<String, String> fields, String user) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(env.base + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", URI.create(env.base).resolve("/").toString());
        if (user != null) builder.header("X-Test-User", user);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(form(fields))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }
}
