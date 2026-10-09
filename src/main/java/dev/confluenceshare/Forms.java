package dev.confluenceshare;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;

final class Forms {
  static Map<String, String> read(HttpServletRequest request, String baseUrl) throws IOException {
    String origin = request.getHeader("Origin");
    if ("cross-site".equals(request.getHeader("Sec-Fetch-Site"))
        || (origin != null && !sameOrigin(origin, baseUrl))) {
      throw new ShareService.Forbidden();
    }
    String type = request.getContentType();
    if (type == null
        || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/x-www-form-urlencoded")) {
      throw new IllegalArgumentException("Unsupported request format.");
    }
    if (request.getContentLengthLong() > 8192)
      throw new IllegalArgumentException("Request is too large.");
    // Confluence's authentication filters may already have parsed the POST body.
    // Use the servlet parameter API instead of trying to read the consumed input stream.
    request.setCharacterEncoding("UTF-8");
    Map<String, String> form = new HashMap<>();
    int size = 0;
    for (var entry : request.getParameterMap().entrySet()) {
      String[] values = entry.getValue();
      if (values.length != 1) throw new IllegalArgumentException("Duplicate form field.");
      size +=
          entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
              + values[0].getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      if (size > 8192) throw new IllegalArgumentException("Request is too large.");
      form.put(entry.getKey(), values[0]);
    }
    return form;
  }

  private static boolean sameOrigin(String origin, String baseUrl) {
    try {
      URI a = URI.create(origin);
      URI b = URI.create(baseUrl);
      return a.getScheme() != null
          && a.getScheme().equalsIgnoreCase(b.getScheme())
          && a.getHost() != null
          && a.getHost().equalsIgnoreCase(b.getHost())
          && port(a) == port(b)
          && a.getRawUserInfo() == null
          && (a.getPath().isEmpty() || a.getPath().equals("/"));
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  private static int port(URI uri) {
    return uri.getPort() >= 0
        ? uri.getPort()
        : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  static long pageId(String value) {
    try {
      long id = Long.parseLong(value);
      if (id > 0) return id;
    } catch (NumberFormatException ignored) {
      // Invalid numbers use the same validation error as non-positive IDs.
    }
    throw new IllegalArgumentException("Page ID is missing or invalid.");
  }
}
