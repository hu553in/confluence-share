package dev.confluenceshare;

import com.atlassian.confluence.pages.Attachment;
import com.atlassian.confluence.pages.AttachmentManager;
import com.atlassian.confluence.pages.Page;
import com.atlassian.confluence.pages.PageManager;
import com.atlassian.confluence.security.Permission;
import com.atlassian.confluence.security.PermissionManager;
import com.atlassian.confluence.user.ConfluenceUser;
import com.atlassian.confluence.user.UserAccessor;
import com.atlassian.sal.api.ApplicationProperties;
import com.atlassian.sal.api.UrlMode;
import com.atlassian.sal.api.user.UserKey;
import com.atlassian.sal.api.user.UserManager;
import java.io.InputStream;
import javax.servlet.http.HttpServletRequest;

public final class ConfluencePages implements PageAccess {
  private final PageManager pageManager;
  private final AttachmentManager attachmentManager;
  private final PermissionManager permissions;
  private final UserAccessor users;
  private final UserManager userManager;
  private final ApplicationProperties properties;

  public ConfluencePages(
      PageManager pageManager,
      AttachmentManager attachmentManager,
      PermissionManager permissions,
      UserAccessor users,
      UserManager userManager,
      ApplicationProperties properties) {
    this.pageManager = pageManager;
    this.attachmentManager = attachmentManager;
    this.permissions = permissions;
    this.users = users;
    this.userManager = userManager;
    this.properties = properties;
  }

  @Override
  public String currentUserKey(HttpServletRequest request) {
    UserKey key = userManager.getRemoteUserKey(request);
    return key == null ? null : key.getStringValue();
  }

  @Override
  public PageAccess.Document managedPage(long pageId, String userKey) {
    Page page = page(pageId);
    if (!canPublish(userKey, page)) throw new ShareService.Forbidden();
    return view(page);
  }

  @Override
  public PageAccess.Document sharedPage(Share share) {
    Page page = page(share.pageId());
    if (!share.spaceKey().equals(page.getSpaceKey()) || !canPublish(share.creatorKey(), page)) {
      throw new ShareService.Unavailable();
    }
    return view(page);
  }

  private boolean canPublish(String userKey, Page page) {
    if (userKey == null) return false;
    ConfluenceUser user = users.getExistingUserByKey(new UserKey(userKey));
    return user != null
        && !users.isDeactivated(user)
        && permissions.hasPermissionNoExemptions(user, Permission.VIEW, page)
        && permissions.hasPermissionNoExemptions(user, Permission.EDIT, page);
  }

  @SuppressWarnings(
      "deprecation") // These host managers are available in the targeted 9.2.4 runtime.
  private Page page(long id) {
    Page page = pageManager.getPage(id);
    if (page == null || !page.isCurrent() || !page.isLatestVersion())
      throw new ShareService.Unavailable();
    return page;
  }

  private static PageAccess.Document view(Page page) {
    return new PageAccess.Document(
        page.getId(), page.getSpaceKey(), page.getTitle(), page.getBodyAsString());
  }

  @Override
  @SuppressWarnings("deprecation")
  public Asset asset(PageAccess.Document sharedPage, String filename) {
    Page owner = page(sharedPage.id());
    Attachment attachment = attachmentManager.getAttachment(owner, filename);
    if (attachment == null || !attachment.isCurrent() || !attachment.isLatestVersion())
      throw new ShareService.Unavailable();
    var container = attachment.getContainer();
    if (container == null || container.getId() != owner.getId())
      throw new ShareService.Unavailable();
    InputStream data = attachmentManager.getAttachmentData(attachment);
    // The host returns null when attachment metadata exists but its binary is missing.
    if (data == null) throw new ShareService.Unavailable();
    return new Asset(
        attachment.getFileName(), attachment.getMediaType(), attachment.getFileSize(), data);
  }

  @Override
  public String baseUrl() {
    return properties.getBaseUrl(UrlMode.CANONICAL).replaceAll("/+$", "");
  }
}
