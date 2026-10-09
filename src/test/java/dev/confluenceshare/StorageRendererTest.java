package dev.confluenceshare;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StorageRendererTest {
    private StorageRenderer.Rendered render(String storage) {
        return new StorageRenderer().render(new PageAccess.Document(1, "TEST", "Title", storage), "/public/token");
    }

    @Test
    void preservesTextTablesAndCodeWhileEscapingMarkup() {
        var result = render("""
                <p>Hello <strong>world</strong> &amp; friends</p><table><tbody><tr><td colspan="2">Cell</td></tr></tbody></table>
                <ac:structured-macro ac:name="code"><ac:plain-text-body><![CDATA[if (a < b) { print("<script>"); }]]></ac:plain-text-body></ac:structured-macro>
                """);
        var dom = Jsoup.parse(result.html());
        assertEquals("Hello world & friends", dom.selectFirst("p").text());
        assertEquals("2", dom.selectFirst("td").attr("colspan"));
        assertEquals("if (a < b) { print(\"<script>\"); }", dom.selectFirst("code").wholeText());
        assertTrue(dom.select("script").isEmpty());
    }

    @Test
    void refusesScriptsEventsStylesDangerousLinksAndUnknownMacroContents() {
        var result = render("""
                <p onclick="steal()" style="background:url(https://evil.test)">Safe</p><script>steal()</script>
                <a href="javascript:steal()">Bad link</a><a href="https://example.com/?x=1&amp;y=2">Good link</a>
                <img src="https://evil.test/tracker"/><iframe src="https://evil.test"/>
                <ac:structured-macro ac:name="include"><ac:rich-text-body>HIDDEN CONTENT</ac:rich-text-body></ac:structured-macro>
                """);
        var dom = Jsoup.parse(result.html());
        assertFalse(result.html().contains("steal"));
        assertFalse(result.html().contains("evil.test"));
        assertFalse(result.html().contains("HIDDEN CONTENT"));
        assertEquals(1, dom.select("a").size());
        assertEquals("https://example.com/?x=1&y=2", dom.selectFirst("a").attr("href"));
    }

    @Test
    void exposesOnlySamePageReferencedAttachmentsAndEncodesNames() {
        var result = render("""
                <ac:image ac:alt="Photo"><ri:attachment ri:filename="Photo café &amp; 1.png"/></ac:image>
                <ac:link><ri:attachment ri:filename="guide.pdf"/><ac:plain-text-link-body><![CDATA[Download]]></ac:plain-text-link-body></ac:link>
                <ac:image><ri:attachment ri:filename="private.png"><ri:page ri:content-title="Private"/></ri:attachment></ac:image>
                <ac:image><ri:attachment ri:filename="../secret"/></ac:image>
                """);
        assertEquals(java.util.Set.of("Photo café & 1.png", "guide.pdf"), result.attachments());
        var dom = Jsoup.parse(result.html());
        assertEquals(1, dom.select("img").size());
        assertTrue(dom.selectFirst("img").attr("src").contains("%C3%A9"));
        assertEquals("Download", dom.selectFirst("a").text());
        assertFalse(result.html().contains("private.png"));
    }

    @Test
    void boundsPageSizeAndDepth() {
        assertThrows(IllegalArgumentException.class, () -> render("x".repeat(2_000_001)));
        assertThrows(IllegalArgumentException.class, () -> render("<div>".repeat(110) + "text" + "</div>".repeat(110)));
    }

    @Test
    void preservesNativeTasksAndCompletionStatusWithoutExecutingTheirMacros() {
        var result = render("""
                <ac:task-list><ac:task><ac:task-id>1</ac:task-id><ac:task-status>complete</ac:task-status>
                <ac:task-body>Back up <strong>production</strong> before migration</ac:task-body></ac:task>
                <ac:task><ac:task-status>incomplete</ac:task-status><ac:task-body>Check backups
                <ac:structured-macro ac:name="include"><ac:parameter>SECRET</ac:parameter></ac:structured-macro>
                </ac:task-body></ac:task></ac:task-list>
                """);
        var dom = Jsoup.parse(result.html());
        assertEquals(2, dom.select("li").size());
        assertTrue(dom.select("li").get(0).text().contains("Back up production before migration"));
        assertEquals("Complete", dom.select("li").get(0).selectFirst("[role=img]").attr("aria-label"));
        assertEquals("Incomplete", dom.select("li").get(1).selectFirst("[role=img]").attr("aria-label"));
        assertFalse(result.html().contains("SECRET"));
    }

    @Test
    void preservesRichLinkLabelsAndOwnImagesWithoutFollowingInternalTargets() {
        var result = render("""
                <ac:link><ri:page ri:content-title="Private page"/><ac:link-body>
                <strong>Preview</strong> <tt>code</tt><ac:image><ri:attachment ri:filename="image.png"/></ac:image>
                <ac:image><ri:attachment ri:filename="private.png"><ri:page ri:content-title="Private page"/></ri:attachment></ac:image>
                </ac:link-body></ac:link>
                """);
        var dom = Jsoup.parse(result.html());
        assertEquals("Preview", dom.selectFirst("strong").text());
        assertTrue(dom.text().contains("code"));
        assertEquals(1, dom.select("img").size());
        assertEquals(java.util.Set.of("image.png"), result.attachments());
        assertTrue(dom.select("a").isEmpty());
        assertFalse(result.html().contains("private.png"));
    }

    @Test
    void labelsWithoutExplicitBodyUseTheStoredResourceName() {
        var result = render("""
                <p><ac:link><ri:page ri:content-title="Deployment guide"/></ac:link></p>
                <p><ac:link><ri:attachment ri:filename="guide.pdf"/></ac:link></p>
                """);
        var dom = Jsoup.parse(result.html());
        assertEquals("Deployment guide", dom.select("p").get(0).text());
        assertEquals("guide.pdf", dom.selectFirst("a").text());
    }

    @Test
    void preservesNativeSmallAndBigTextWithoutKeepingUnsafeAttributes() {
        var result = render("""
                <p>Before <small onclick="steal()">small <strong>detail</strong></small>
                <big style="background:url(https://evil.test)">big detail</big> after</p>
                """);
        var dom = Jsoup.parse(result.html());
        assertEquals("Before small detail big detail after", dom.selectFirst("p").text());
        assertEquals("small detail", dom.selectFirst("small").text());
        assertEquals("big detail", dom.selectFirst("big").text());
        assertFalse(result.html().contains("steal"));
        assertFalse(result.html().contains("evil.test"));
    }

    @Test
    void acceptsAbsoluteOwnPageAttachmentsAndRefusesForeignOrAmbiguousOwners() {
        var result = render("""
                <ac:image><ri:attachment ri:filename="image.png"><ri:page ri:space-key="TEST" ri:content-title="Title"/></ri:attachment></ac:image>
                <ac:link><ri:attachment ri:filename="guide.pdf"><ri:page ri:content-title="Title"/></ri:attachment>
                  <ac:plain-text-link-body><![CDATA[Download guide]]></ac:plain-text-link-body></ac:link>
                <ac:image><ri:attachment ri:filename="foreign-title.png"><ri:page ri:content-title="Other"/></ri:attachment></ac:image>
                <ac:image><ri:attachment ri:filename="foreign-space.png"><ri:page ri:space-key="OTHER" ri:content-title="Title"/></ri:attachment></ac:image>
                <ac:image><ri:attachment ri:filename="ambiguous.png"><ri:page ri:content-title="Title"/><ri:page ri:content-title="Other"/></ri:attachment></ac:image>
                """);
        var dom = Jsoup.parse(result.html());
        assertEquals(java.util.Set.of("image.png", "guide.pdf"), result.attachments());
        assertEquals(1, dom.select("img").size());
        assertEquals("Download guide", dom.selectFirst("a").text());
        assertTrue(dom.selectFirst("a").attr("href").contains("guide.pdf"));
        assertFalse(result.html().contains("foreign-title.png"));
        assertFalse(result.html().contains("foreign-space.png"));
        assertFalse(result.html().contains("ambiguous.png"));
    }

    @Test
    void preservesAutomaticForeignAttachmentLabelsWithoutExposingTheirFiles() {
        var result = render("""
                <p>Read <ac:link><ri:attachment ri:filename="policy &amp; guide.pdf">
                  <ri:page ri:space-key="TEST" ri:content-title="Private"/>
                </ri:attachment></ac:link> before deployment.</p>
                """);
        var dom = Jsoup.parse(result.html());
        assertEquals("Read policy & guide.pdf before deployment.", dom.selectFirst("p").text());
        assertTrue(dom.select("a").isEmpty());
        assertTrue(result.attachments().isEmpty());
    }

    @Test
    void preservesVisibleImageCaptionsSeparatelyFromAltTextWithoutExposingForeignImages() {
        var result = render("""
                <p><ac:image ac:title="WARNING: &amp; do not delete &lt;script&gt;backups&lt;/script&gt;" ac:alt="Backup diagram">
                  <ri:attachment ri:filename="backup.png"/>
                </ac:image></p>
                <p><ac:image ac:title="Private diagram caption">
                  <ri:attachment ri:filename="private.png"><ri:page ri:content-title="Private"/></ri:attachment>
                </ac:image></p>
                """);
        var dom = Jsoup.parse(result.html());
        assertEquals(2, dom.select(".image-caption").size());
        assertEquals("WARNING: & do not delete <script>backups</script>", dom.select(".image-caption").get(0).text());
        assertEquals("Private diagram caption", dom.select(".image-caption").get(1).text());
        assertEquals("Backup diagram", dom.selectFirst("img").attr("alt"));
        assertEquals(1, dom.select("img").size());
        assertTrue(dom.select("script").isEmpty());
        assertFalse(result.html().contains("private.png"));
        assertEquals(java.util.Set.of("backup.png"), result.attachments());
        assertEquals(2, dom.select("p").size());
    }

    @Test
    void preservesAnnotatedPageTextWhileDiscardingInlineCommentMetadataAndUnsafeChildren() {
        var result = render("""
                <p>Do <ac:inline-comment-marker ac:ref="private-comment-id" onclick="steal()"><strong>not</strong></ac:inline-comment-marker> delete backups.</p>
                <h2><ac:inline-comment-marker ac:ref="heading-comment-id">Instructions</ac:inline-comment-marker></h2>
                <ac:task-list><ac:task><ac:task-status>incomplete</ac:task-status><ac:task-body>
                  <ac:inline-comment-marker ac:ref="task-comment-id">Check <ac:image><ri:attachment ri:filename="backup.png"/></ac:image>
                    <script>steal()</script><ac:structured-macro ac:name="include"><ac:rich-text-body>SECRET</ac:rich-text-body></ac:structured-macro>
                  </ac:inline-comment-marker>
                </ac:task-body></ac:task></ac:task-list>
                """);
        var dom = Jsoup.parse(result.html());
        assertEquals("Do not delete backups.", dom.selectFirst("p").text());
        assertEquals("not", dom.selectFirst("strong").text());
        assertEquals("Instructions", dom.selectFirst("h2").text());
        assertTrue(dom.selectFirst("li").text().contains("Check"));
        assertEquals(java.util.Set.of("backup.png"), result.attachments());
        assertEquals(1, dom.select("img").size());
        assertFalse(result.html().contains("comment-id"));
        assertFalse(result.html().contains("steal"));
        assertFalse(result.html().contains("SECRET"));
        assertThrows(IllegalArgumentException.class, () -> render("<ac:inline-comment-marker>".repeat(110)
                + "text" + "</ac:inline-comment-marker>".repeat(110)));
    }
}
