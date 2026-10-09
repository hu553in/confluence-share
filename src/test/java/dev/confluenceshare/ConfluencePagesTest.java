package dev.confluenceshare;

import com.atlassian.confluence.pages.Attachment;
import com.atlassian.confluence.pages.AttachmentManager;
import com.atlassian.confluence.pages.Page;
import com.atlassian.confluence.pages.PageManager;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real host entities; only manager calls are replaced at the Confluence boundary.
 */
@SuppressWarnings("deprecation")
class ConfluencePagesTest {
    private final PageAccess.Document document = new PageAccess.Document(1, "TEST", "Title", "<p>Body</p>");

    @Test
    void missingAttachmentBinaryIsUnavailable() {
        ConfluencePages pages = pages(null);
        assertThrows(ShareService.Unavailable.class, () -> pages.asset(document, "image.png"));
    }

    @Test
    void existingAttachmentBinaryCanBeReadAndClosed() throws Exception {
        var closed = new AtomicBoolean();
        var stream = new ByteArrayInputStream(new byte[]{1, 2, 3}) {
            @Override
            public void close() {
                closed.set(true);
            }
        };
        try (var asset = pages(stream).asset(document, "image.png")) {
            assertEquals("image.png", asset.filename());
            assertEquals("image/png", asset.contentType());
            assertEquals(3, asset.length());
            assertArrayEquals(new byte[]{1, 2, 3}, asset.data().readAllBytes());
            assertFalse(closed.get());
        }
        assertTrue(closed.get());
    }

    private ConfluencePages pages(InputStream data) {
        Page owner = new Page();
        owner.setId(1);
        Attachment attachment = new Attachment();
        attachment.setContainer(owner);
        attachment.setFileName("image.png");
        attachment.setMediaType("image/png");
        attachment.setFileSize(3);
        PageManager pageManager = (PageManager) Proxy.newProxyInstance(PageManager.class.getClassLoader(),
                new Class<?>[]{PageManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getPage") && args.length == 1 && args[0].equals(1L)) return owner;
                    throw new AssertionError("Unexpected PageManager call: " + method);
                });
        AttachmentManager attachmentManager = (AttachmentManager) Proxy.newProxyInstance(AttachmentManager.class.getClassLoader(),
                new Class<?>[]{AttachmentManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getAttachment") && args.length == 2 && args[0] == owner
                            && args[1].equals("image.png")) return attachment;
                    if (method.getName().equals("getAttachmentData") && args.length == 1 && args[0] == attachment)
                        return data;
                    throw new AssertionError("Unexpected AttachmentManager call: " + method);
                });
        return new ConfluencePages(pageManager, attachmentManager, null, null, null, null);
    }
}
