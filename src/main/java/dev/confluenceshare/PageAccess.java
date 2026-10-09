package dev.confluenceshare;

import java.io.IOException;
import java.io.InputStream;

interface PageAccess {
  record Document(long id, String spaceKey, String title, String storage) {}

  record Asset(String filename, String contentType, long length, InputStream data)
      implements AutoCloseable {
    @Override
    public void close() throws IOException {
      data.close();
    }
  }

  String currentUserKey(javax.servlet.http.HttpServletRequest request);

  Document managedPage(long pageId, String userKey);

  Document sharedPage(Share share);

  Asset asset(Document page, String filename);

  String baseUrl();
}
