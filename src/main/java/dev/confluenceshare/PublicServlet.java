package dev.confluenceshare;

import com.atlassian.annotations.security.UnrestrictedAccess;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PushbackInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

@UnrestrictedAccess
public final class PublicServlet extends HttpServlet {
    private final ShareService shares;
    private final PageAccess pages;
    private final StorageRenderer renderer = new StorageRenderer();

    public PublicServlet(ShareService shares, ConfluencePages pages) {
        this(shares, (PageAccess) pages);
    }

    PublicServlet(ShareService shares, PageAccess pages) {
        this.shares = shares;
        this.pages = pages;
    }

    @Override
    @UnrestrictedAccess
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String[] route = route(request);
            Share share = shares.available(route[0]);
            if (!granted(request, share)) {
                if (route.length > 1) Html.error(response, 403, "Enter the password on the page to open this file.");
                else passwordPage(response, request, 200, "");
                return;
            }
            PageAccess.Document page = pages.sharedPage(share);
            String path = publicPath(request, share.token());
            StorageRenderer.Rendered rendered = renderer.render(page, path);
            if (route.length > 1) {
                String name = request.getParameter("name");
                if (!StorageRenderer.validFilename(name) || !rendered.attachments().contains(name))
                    throw new ShareService.Unavailable();
                streamAsset(response, page, name);
            } else {
                Html.page(response, 200, page.title(), "<article><h1>" + Html.escape(page.title()) + "</h1>" + rendered.html() + "</article>");
            }
        } catch (ShareService.Unavailable e) {
            Html.error(response, 404, "Link unavailable.");
        } catch (IllegalArgumentException e) {
            Html.error(response, 400, "Unable to display the page.");
        }
    }

    @Override
    @UnrestrictedAccess
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String[] route = route(request);
            if (route.length != 1) throw new ShareService.Unavailable();
            Map<String, String> form = Forms.read(request, pages.baseUrl());
            if (!shares.unlock(route[0], form.get("password"))) {
                passwordPage(response, request, 401, "Incorrect password.");
                return;
            }
            Share share = shares.available(route[0]);
            String path = publicPath(request, share.token());
            boolean secure = request.isSecure() || "https".equalsIgnoreCase(URI.create(pages.baseUrl()).getScheme());
            Html.headers(response);
            response.addHeader("Set-Cookie", cookieName(share) + "=" + Secrets.grant(share, Instant.now())
                    + "; Path=" + path + "; Max-Age=1800; HttpOnly; SameSite=Lax" + (secure ? "; Secure" : ""));
            response.setStatus(303);
            response.setHeader("Location", path);
        } catch (ShareService.TooManyAttempts e) {
            response.setHeader("Retry-After", "60");
            passwordPage(response, request, 429, "Too many attempts. Try again in a minute.");
        } catch (ShareService.Forbidden e) {
            Html.error(response, 403, "Request denied.");
        } catch (ShareService.Unavailable e) {
            Html.error(response, 404, "Link unavailable.");
        } catch (IllegalArgumentException e) {
            Html.error(response, 400, "Invalid request.");
        }
    }

    private static String[] route(HttpServletRequest request) {
        String path = request.getPathInfo();
        if (path == null || path.length() > 64 || !path.startsWith("/")) throw new ShareService.Unavailable();
        String[] route = path.substring(1).split("/", -1);
        if (!Secrets.validToken(route[0]) || route.length > 2 || (route.length == 2 && !route[1].equals("asset"))) {
            throw new ShareService.Unavailable();
        }
        return route;
    }

    private static String publicPath(HttpServletRequest request, String token) {
        return request.getContextPath() + "/plugins/servlet/confluence-share/s/" + token;
    }

    private static String cookieName(Share share) {
        return "confluence_share_" + Secrets.digest(share.token()).substring(0, 16);
    }

    private static boolean granted(HttpServletRequest request, Share share) {
        if (!share.protectedByPassword()) return true;
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return false;
        return Arrays.stream(cookies).filter(c -> c.getName().equals(cookieName(share)))
                .anyMatch(c -> Secrets.validGrant(share, c.getValue(), Instant.now()));
    }

    private static void passwordPage(HttpServletResponse response, HttpServletRequest request, int status, String error) throws IOException {
        String body = "<section class=\"card narrow\"><h1>Enter password</h1><p class=\"muted\">Ask the person who shared the link for the password.</p>"
                + (error.isEmpty() ? "" : "<p class=\"message error\" role=\"alert\">" + Html.escape(error) + "</p>")
                + "<form method=\"post\" action=\"" + Html.escape(request.getRequestURI()) + "\"><label for=\"password\">Password</label>"
                + "<input id=\"password\" type=\"password\" name=\"password\" required maxlength=\"256\" autocomplete=\"current-password\">"
                + "<button type=\"submit\">Open page</button></form></section>";
        Html.page(response, status, "Enter password", body);
    }

    private void streamAsset(HttpServletResponse response, PageAccess.Document page, String name) throws IOException {
        try (PageAccess.Asset asset = pages.asset(page, name)) {
            PushbackInputStream input = new PushbackInputStream(asset.data(), 12);
            byte[] prefix = input.readNBytes(12);
            input.unread(prefix);
            String imageType = imageType(prefix);
            Html.headers(response);
            response.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");
            boolean inline = imageType != null && imageType.equalsIgnoreCase(asset.contentType());
            response.setContentType(inline ? imageType : "application/octet-stream");
            String filename = java.net.URLEncoder.encode(asset.filename(), StandardCharsets.UTF_8).replace("+", "%20");
            response.setHeader("Content-Disposition", (inline ? "inline" : "attachment") + "; filename*=UTF-8''" + filename);
            response.setContentLengthLong(asset.length());
            input.transferTo(response.getOutputStream());
        }
    }

    private static String imageType(byte[] bytes) {
        if (bytes.length >= 8 && Arrays.equals(Arrays.copyOf(bytes, 8), new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}))
            return "image/png";
        if (bytes.length >= 3 && bytes[0] == (byte) 255 && bytes[1] == (byte) 216 && bytes[2] == (byte) 255)
            return "image/jpeg";
        if (bytes.length >= 6 && (new String(bytes, 0, 6, StandardCharsets.US_ASCII).equals("GIF87a")
                || new String(bytes, 0, 6, StandardCharsets.US_ASCII).equals("GIF89a"))) return "image/gif";
        if (bytes.length >= 12 && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) return "image/webp";
        return null;
    }
}
