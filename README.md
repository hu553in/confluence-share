# Confluence Share

Share individual Confluence Data Center 9.2.4 pages through public links. Recipients do not need a Confluence account.
You can protect a link with a password.

## Build and install

Requires Maven 3.9+ and JDK 17 or 21.

```bash
cd ~/PersonalProjects/confluence-share
mvn clean verify
```

Upload `target/confluence-share-0.1.0.jar` through **Manage apps → Upload app** in Confluence administration.
Installation requires system administrator permissions.

If **Upload app** is missing, add the JVM option `-Dupm.plugin.upload.enabled=true` and restart Confluence. Apply it to
every node in a cluster. You can disable uploads again after installation. See
the [Atlassian instructions](https://confluence.atlassian.com/conf92/managing-system-and-marketplace-apps-1477576933.html).

For external access, configure HTTPS and the correct **Server Base URL**. If a proxy or SSO gateway requires
authentication, allow unauthenticated requests to `/plugins/servlet/confluence-share/s/*`. You do not need to enable
anonymous access to the entire space.

## Usage

Any active user with view and edit permissions for the page can manage its link, including replacing or revoking a link
created by another editor.

1. Open a page and select **Public link** in the actions menu.
2. Leave the password blank or enter a password of 8 to 256 characters.
3. Click **Create link** and copy the address from **Share URL**.
4. To change the password, create a new link. The previous URL will stop working.
5. To close access, click **Revoke link**.

Each page has one active link. It displays the latest published version; refresh the browser to see changes.

You can open the link settings directly:

```text
https://confluence.example.com/plugins/servlet/confluence-share/manage?pageId=12345
```

If Confluence runs under `/wiki`, add that path before `/plugins`.

## Passwords and revocation

Password protection hides the page title, content and files until the visitor enters the password. Browser access then
lasts 30 minutes. Each link allows 10 attempts per 60 seconds, including successful attempts. All visitors share this
limit.

Replacing or revoking a link blocks subsequent requests, including requests from browsers that already opened the page.
Content and files already received cannot be revoked.

Access closes if the link creator is deactivated or loses permissions for the page. Moving the page to another space
also closes access. Restoring the original conditions can reopen a link that has not been revoked.

## Page content

The public view preserves text, headings, lists, task completion status, tables, `code` and `noformat` blocks, and the
page's own images and captions. Styling is simplified. Text marked by an inline comment remains visible; the comment
itself is not published.

Only files attached to the selected page and explicitly referenced by an image or attachment link are available. PNG,
JPEG, GIF and WebP files display as images; other files download. External images and files from other pages are
unavailable. Links to other Confluence pages retain their labels without exposing the target page.

Other macros do not run. Child pages, comments, version history and the full attachment list are not published.

## Local preview

```bash
bash scripts/preview.sh
```

Open `http://127.0.0.1:8098/wiki/plugins/servlet/confluence-share/manage?pageId=1`. Pass a different port as an
argument: `bash scripts/preview.sh 8099`. Stop the preview with Ctrl+C.

The preview uses a test page and in-memory data. It does not connect to a real Confluence instance. Restarting clears
its links.

If the plugin fails to start, look for `dev.confluenceshare.confluence-share` in
`<local-home>/logs/atlassian-confluence.log` on the Confluence node.
