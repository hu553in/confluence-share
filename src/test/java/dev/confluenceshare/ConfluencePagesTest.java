package dev.confluenceshare;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlassian.confluence.pages.Attachment;
import com.atlassian.confluence.pages.AttachmentManager;
import com.atlassian.confluence.pages.Page;
import com.atlassian.confluence.pages.PageManager;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Real host entities; only manager calls are replaced at the Confluence boundary. */
@SuppressWarnings("deprecation")
class ConfluencePagesTest {
  private final PageAccess.Document document =
      new PageAccess.Document(1, "TEST", "Title", "<p>Body</p>");

  @Test
  void missingAttachmentBinaryIsUnavailable() {
    ConfluencePages pages = pages(null);
    assertThrows(ShareService.Unavailable.class, () -> pages.asset(document, "image.png"));
  }

  @Test
  void attachmentWithoutAnOwnerIsUnavailable() {
    ConfluencePages pages = pages(new ByteArrayInputStream(new byte[] {1}), false);
    assertThrows(ShareService.Unavailable.class, () -> pages.asset(document, "image.png"));
  }

  @Test
  void existingAttachmentBinaryCanBeReadAndClosed() throws Exception {
    var closed = new AtomicBoolean();
    var stream =
        new ByteArrayInputStream(new byte[] {1, 2, 3}) {
          @Override
          public void close() {
            closed.set(true);
          }
        };
    try (var asset = pages(stream).asset(document, "image.png")) {
      assertEquals("image.png", asset.filename());
      assertEquals("image/png", asset.contentType());
      assertEquals(3, asset.length());
      assertArrayEquals(new byte[] {1, 2, 3}, asset.data().readAllBytes());
      assertFalse(closed.get());
    }
    assertTrue(closed.get());
  }

  private ConfluencePages pages(InputStream data) {
    return pages(data, true);
  }

  private ConfluencePages pages(InputStream data, boolean hasContainer) {
    Page owner = new Page();
    owner.setId(1);
    Attachment attachment = new Attachment();
    if (hasContainer) attachment.setContainer(owner);
    attachment.setFileName("image.png");
    attachment.setMediaType("image/png");
    attachment.setFileSize(3);
    PageManager pageManager =
        (PageManager)
            Proxy.newProxyInstance(
                PageManager.class.getClassLoader(),
                new Class<?>[] {PageManager.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("getPage") && args.length == 1 && args[0].equals(1L))
                    return owner;
                  throw new AssertionError("Unexpected PageManager call: " + method);
                });
    AttachmentManager attachmentManager =
        (AttachmentManager)
            Proxy.newProxyInstance(
                AttachmentManager.class.getClassLoader(),
                new Class<?>[] {AttachmentManager.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("getAttachment") && args.length == 2) {
                    assertSame(owner, args[0]);
                    assertEquals("image.png", args[1]);
                    return attachment;
                  }
                  if (method.getName().equals("getAttachmentData") && args.length == 1) {
                    assertSame(attachment, args[0]);
                    return data;
                  }
                  throw new AssertionError("Unexpected AttachmentManager call: " + method);
                });
    return new ConfluencePages(pageManager, attachmentManager, null, null, null, null);
  }
}
