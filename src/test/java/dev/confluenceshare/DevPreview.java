package dev.confluenceshare;

/** Loopback-only UI demo. No Confluence connection; its test records disappear on shutdown. */
public final class DevPreview {
  public static void main(String[] args) throws Exception {
    var env = new TestEnvironment();
    env.start(args.length == 0 ? 8098 : Integer.parseInt(args[0]), "/wiki", true);
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    env.close();
                  } catch (Exception e) {
                    System.getLogger(DevPreview.class.getName())
                        .log(System.Logger.Level.ERROR, "Failed to stop the local preview.", e);
                  }
                }));
    System.out.println("Local UI demo: " + env.base + TestEnvironment.MANAGE + "?pageId=1");
    env.server.join();
  }
}
