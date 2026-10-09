package dev.confluenceshare;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.CDataNode;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

/**
 * Converts the supported storage elements without executing Confluence macros or fetching external
 * resources.
 */
final class StorageRenderer {
  private static final Set<String> TAGS =
      Set.of(
          "p",
          "div",
          "span",
          "h1",
          "h2",
          "h3",
          "h4",
          "h5",
          "h6",
          "strong",
          "b",
          "em",
          "i",
          "u",
          "s",
          "del",
          "sub",
          "sup",
          "ul",
          "ol",
          "li",
          "blockquote",
          "pre",
          "code",
          "table",
          "thead",
          "tbody",
          "tfoot",
          "tr",
          "th",
          "td",
          "br",
          "hr",
          "tt",
          "small",
          "big");

  record Rendered(String html, Set<String> attachments) {}

  private record Context(PageAccess.Document page, String path, Set<String> assets) {}

  Rendered render(PageAccess.Document page, String publicPath) {
    if (page.storage() == null || page.storage().length() > 2_000_000) {
      throw new IllegalArgumentException("Page is too large to share.");
    }
    Element root =
        Jsoup.parse("<share-root>" + page.storage() + "</share-root>", "", Parser.xmlParser())
            .selectFirst("share-root");
    Set<String> assets = new HashSet<>();
    StringBuilder out = new StringBuilder();
    children(root, out, new Context(page, publicPath, assets), 0);
    return new Rendered(out.toString(), Set.copyOf(assets));
  }

  private void children(Node node, StringBuilder out, Context context, int depth) {
    if (depth > 100) throw new IllegalArgumentException("Page contains too many nested elements.");
    for (Node child : node.childNodes()) renderNode(child, out, context, depth + 1);
  }

  private void renderNode(Node node, StringBuilder out, Context context, int depth) {
    if (node instanceof CDataNode cdata) {
      out.append(Html.escape(cdata.text()));
      return;
    }
    if (node instanceof TextNode text) {
      out.append(Html.escape(text.getWholeText()));
      return;
    }
    if (!(node instanceof Element element)) return;
    String tag = element.tagName();
    if (TAGS.contains(tag)) {
      out.append('<').append(tag);
      if (tag.equals("th") || tag.equals("td")) {
        numericAttribute(element, out, "colspan", 100);
        numericAttribute(element, out, "rowspan", 100);
      }
      if (tag.equals("ol")) numericAttribute(element, out, "start", 10_000);
      out.append('>');
      children(element, out, context, depth);
      if (!tag.equals("br") && !tag.equals("hr")) out.append("</").append(tag).append('>');
      return;
    }
    switch (tag) {
      case "ac:image" -> image(element, out, context);
      case "ac:link" -> attachmentLink(element, out, context, depth);
      case "ac:inline-comment-marker" -> children(element, out, context, depth);
      case "ac:task-list" -> taskList(element, out, context, depth);
      case "a" -> htmlLink(element, out, context, depth);
      case "ac:structured-macro", "ac:macro" -> code(element, out);
      case "ac:layout", "ac:layout-section", "ac:layout-cell" -> {
        out.append("<div>");
        children(element, out, context, depth);
        out.append("</div>");
      }
      default -> {
        /* Unknown elements are omitted together with their contents. */
      }
    }
  }

  private static void numericAttribute(
      Element element, StringBuilder out, String name, int maximum) {
    try {
      int value = Integer.parseInt(element.attr(name));
      if (value > 0 && value <= maximum)
        out.append(' ').append(name).append("=\"").append(value).append('"');
    } catch (NumberFormatException ignored) {
      // Non-numeric attributes are omitted.
    }
  }

  private static Element ownAttachment(Element parent, PageAccess.Document page) {
    for (Element child : parent.children()) {
      if (!child.tagName().equals("ri:attachment")) continue;
      if (child.children().isEmpty()) return child;
      if (child.children().size() != 1) continue;
      Element owner = child.child(0);
      String space = owner.attr("ri:space-key");
      if (owner.tagName().equals("ri:page")
          && owner.children().isEmpty()
          && page.title().equals(owner.attr("ri:content-title"))
          && (space.isEmpty() || page.spaceKey().equals(space))) return child;
    }
    return null;
  }

  private static String attachmentName(Element attachment) {
    String name = attachment == null ? "" : attachment.attr("ri:filename");
    return validFilename(name) ? name : null;
  }

  static boolean validFilename(String name) {
    return name != null
        && !name.isBlank()
        && name.length() <= 255
        && name.codePoints().noneMatch(c -> c < 32 || c == 127)
        && !name.contains("/")
        && !name.contains("\\");
  }

  static String assetUrl(String path, String name) {
    return path + "/asset?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8);
  }

  private static void image(Element element, StringBuilder out, Context context) {
    String name = attachmentName(ownAttachment(element, context.page()));
    out.append("<span class=\"image\">");
    if (name == null) {
      out.append("<span class=\"omitted\">Image unavailable.</span>");
    } else {
      context.assets().add(name);
      out.append("<img loading=\"lazy\" src=\"")
          .append(Html.escape(assetUrl(context.path(), name)))
          .append("\" alt=\"")
          .append(Html.escape(element.attr("ac:alt")))
          .append("\">");
    }
    String caption = element.attr("ac:title");
    if (!caption.isBlank())
      out.append("<span class=\"image-caption\">").append(Html.escape(caption)).append("</span>");
    out.append("</span>");
  }

  private void attachmentLink(Element element, StringBuilder out, Context context, int depth) {
    String name = attachmentName(ownAttachment(element, context.page()));
    Element body = directChild(element, "ac:link-body");
    if (body == null) body = directChild(element, "ac:plain-text-link-body");
    StringBuilder label = new StringBuilder();
    if (body != null) children(body, label, context, depth + 1);
    if (label.toString().isBlank()) {
      Element targetAttachment = directChild(element, "ri:attachment");
      Element targetPage = directChild(element, "ri:page");
      label.append(
          Html.escape(
              targetAttachment != null
                  ? targetAttachment.attr("ri:filename")
                  : targetPage != null ? targetPage.attr("ri:content-title") : ""));
    }
    if (name == null) {
      out.append(label);
      return;
    }
    context.assets().add(name);
    out.append("<a href=\"")
        .append(Html.escape(assetUrl(context.path(), name)))
        .append("\" download>")
        .append(label)
        .append("</a>");
  }

  private void taskList(Element list, StringBuilder out, Context context, int depth) {
    out.append("<ul class=\"task-list\">");
    for (Element task : list.children()) {
      if (!task.tagName().equals("ac:task")) continue;
      Element body = directChild(task, "ac:task-body");
      if (body == null) continue;
      Element status = directChild(task, "ac:task-status");
      boolean complete = status != null && status.text().equals("complete");
      out.append("<li><span role=\"img\" aria-label=\"")
          .append(complete ? "Complete" : "Incomplete")
          .append("\">")
          .append(complete ? "☑" : "☐")
          .append("</span> ");
      children(body, out, context, depth + 2);
      out.append("</li>");
    }
    out.append("</ul>");
  }

  private static Element directChild(Element parent, String tag) {
    for (Element child : parent.children()) if (child.tagName().equals(tag)) return child;
    return null;
  }

  private void htmlLink(Element element, StringBuilder out, Context context, int depth) {
    String href = element.attr("href");
    boolean allowed = false;
    try {
      URI uri = URI.create(href);
      allowed =
          href.startsWith("#")
              || (uri.isAbsolute()
                  && Set.of("http", "https", "mailto")
                      .contains(uri.getScheme().toLowerCase(Locale.ROOT)));
    } catch (IllegalArgumentException ignored) {
      // Malformed URLs retain only their labels.
    }
    if (allowed)
      out.append("<a rel=\"noopener noreferrer\" href=\"").append(Html.escape(href)).append("\">");
    children(element, out, context, depth);
    if (allowed) out.append("</a>");
  }

  private static void code(Element macro, StringBuilder out) {
    if (!macro.attr("ac:name").equals("code") && !macro.attr("ac:name").equals("noformat")) {
      out.append("<p class=\"omitted\">This element is unavailable in the public version.</p>");
      return;
    }
    for (Element child : macro.children()) {
      if (child.tagName().equals("ac:plain-text-body")) {
        StringBuilder text = new StringBuilder();
        for (Node node : child.childNodes())
          if (node instanceof TextNode t) text.append(t.getWholeText());
        out.append("<pre><code>").append(Html.escape(text.toString())).append("</code></pre>");
      }
    }
  }
}
