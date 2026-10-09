package dev.confluenceshare;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

final class Html {
  private static final Object CSRF_LOCK = new Object();

  static String escape(String text) {
    return text == null
        ? ""
        : text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
  }

  static void headers(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store, private, max-age=0");
    response.setHeader("Pragma", "no-cache");
    response.setHeader("X-Content-Type-Options", "nosniff");
    // no-referrer makes browser form POSTs send Origin: null, breaking the same-origin check.
    response.setHeader("Referrer-Policy", "same-origin");
    response.setHeader("X-Robots-Tag", "noindex, nofollow, noarchive");
    response.setHeader("X-Frame-Options", "DENY");
  }

  static void page(HttpServletResponse response, int status, String title, String body)
      throws IOException {
    headers(response);
    String nonce = UUID.randomUUID().toString();
    response.setHeader(
        "Content-Security-Policy",
        "default-src 'none'; style-src 'nonce-"
            + nonce
            + "'; img-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");
    response.setStatus(status);
    response.setContentType("text/html;charset=UTF-8");
    response
        .getWriter()
        .write(
            "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>"
                + escape(title)
                + "</title><style nonce=\""
                + nonce
                + "\">"
                + stylesheet()
                + "</style></head><body>"
                + "<main><header><a class=\"brand\" href=\"#\">Confluence Share</a></header>"
                + body
                + "</main></body></html>");
  }

  private static String stylesheet() throws IOException {
    try (InputStream input = Html.class.getResourceAsStream("/share.css")) {
      if (input == null) throw new IOException("Missing share.css");
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  static void error(HttpServletResponse response, int status, String message) throws IOException {
    page(
        response,
        status,
        "Confluence Share",
        "<section class=\"card\"><h1>" + escape(message) + "</h1></section>");
  }

  static String csrf(HttpServletRequest request) {
    HttpSession session = request.getSession();
    // Atlassian creates a new session wrapper on each call, so the wrapper itself is not a stable
    // mutex.
    synchronized (CSRF_LOCK) {
      Object existing = session.getAttribute("confluenceShare.csrf");
      if (existing instanceof String value) return value;
      String value = Secrets.token();
      session.setAttribute("confluenceShare.csrf", value);
      return value;
    }
  }

  static boolean validCsrf(HttpServletRequest request, String token) {
    if (request.getSession(false) == null) return false;
    Object stored = request.getSession(false).getAttribute("confluenceShare.csrf");
    return stored instanceof String value && Secrets.equal(value, token);
  }

  static String hidden(String name, String value) {
    return "<input type=\"hidden\" name=\"" + escape(name) + "\" value=\"" + escape(value) + "\">";
  }
}
