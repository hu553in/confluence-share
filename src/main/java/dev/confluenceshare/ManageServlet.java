package dev.confluenceshare;

import com.atlassian.annotations.security.LicensedOnly;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

@LicensedOnly
public final class ManageServlet extends HttpServlet {
    private final ShareService shares;
    private final PageAccess pages;

    public ManageServlet(ShareService shares, ConfluencePages pages) {
        this(shares, (PageAccess) pages);
    }

    ManageServlet(ShareService shares, PageAccess pages) {
        this.shares = shares;
        this.pages = pages;
    }

    @Override
    @LicensedOnly
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            long id = Forms.pageId(request.getParameter("pageId"));
            PageAccess.Document page = pages.managedPage(id, pages.currentUserKey(request));
            show(request, response, page);
        } catch (ShareService.Unavailable e) {
            Html.error(response, 404, "Page unavailable.");
        } catch (ShareService.Forbidden e) {
            Html.error(response, 403, "You need view and edit permissions to share this page.");
        } catch (IllegalArgumentException e) {
            Html.error(response, 400, e.getMessage());
        }
    }

    @Override
    @LicensedOnly
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            Map<String, String> form = Forms.read(request, pages.baseUrl());
            if (!Html.validCsrf(request, form.get("csrf"))) throw new ShareService.Forbidden();
            long id = Forms.pageId(form.get("pageId"));
            String user = pages.currentUserKey(request);
            String action = form.get("action");
            if ("create".equals(action)) shares.create(id, user, form.get("password"));
            else if ("revoke".equals(action)) shares.revoke(id, user);
            else throw new IllegalArgumentException("Unknown action.");
            Html.headers(response);
            response.setStatus(303);
            response.setHeader("Location", request.getContextPath() + "/plugins/servlet/confluence-share/manage?pageId=" + id);
        } catch (ShareService.Unavailable e) {
            Html.error(response, 404, "Page unavailable.");
        } catch (ShareService.Forbidden e) {
            Html.error(response, 403, "Action unavailable. Reopen the link settings and check your permissions.");
        } catch (IllegalArgumentException e) {
            Html.error(response, 400, e.getMessage());
        }
    }

    private void show(HttpServletRequest request, HttpServletResponse response, PageAccess.Document page) throws IOException {
        Share share = shares.forPage(page.id());
        String action = request.getContextPath() + "/plugins/servlet/confluence-share/manage";
        String hidden = Html.hidden("csrf", Html.csrf(request)) + Html.hidden("pageId", Long.toString(page.id()));
        StringBuilder body = new StringBuilder("<h1>Public link</h1><p>").append(Html.escape(page.title())).append("</p>");
        if (share != null) {
            String url = pages.baseUrl() + "/plugins/servlet/confluence-share/s/" + share.token();
            body.append("<section class=\"card\"><label for=\"share-url\">Share URL</label><input id=\"share-url\" readonly value=\"")
                    .append(Html.escape(url)).append("\"><p class=\"muted\">")
                    .append(share.protectedByPassword() ? "A password is required to open this page." : "Anyone with the link can open this page.")
                    .append("</p><div class=\"controls\"><a rel=\"noopener noreferrer\" target=\"_blank\" href=\"").append(Html.escape(url))
                    .append("\">Open public page</a><form method=\"post\" action=\"").append(Html.escape(action)).append("\">")
                    .append(hidden).append(Html.hidden("action", "revoke"))
                    .append("<button class=\"danger\" type=\"submit\">Revoke link</button></form></div></section>");
        }
        body.append("<section class=\"card\"><form method=\"post\" action=\"").append(Html.escape(action)).append("\">")
                .append(hidden).append(Html.hidden("action", "create"))
                .append("<label for=\"password\">Password</label><input id=\"password\" name=\"password\" type=\"password\" autocomplete=\"new-password\" minlength=\"8\" maxlength=\"256\" aria-describedby=\"password-help\">")
                .append("<p id=\"password-help\" class=\"password-help\">Leave blank for access by link. To protect the page, use a password of 8 to 256 characters.</p>");
        if (share != null)
            body.append("<p class=\"muted\">Creating a new link will invalidate the previous link and access granted through it.</p>");
        body.append("<button type=\"submit\">").append(share == null ? "Create link" : "Create new link")
                .append("</button></form></section><p class=\"muted\">The link shows the latest published version of this page and the attachments it references.</p>");
        Html.page(response, 200, "Public link", body.toString());
    }
}
