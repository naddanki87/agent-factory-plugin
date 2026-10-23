import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent Factory quality gate — one Claude Code hook for two events.
 *
 * <p><b>PreToolUse (Bash):</b> lets every command through except pushes ({@code git push}, {@code
 * gh repo create --push}). Before a push it checks that the push is not a force push, that {@code
 * .factory/qa-report.md} says {@code STATUS: PASS} for the current HEAD commit, and that {@code
 * ./gradlew check} passes in every module.
 *
 * <p><b>TaskCompleted (agent teams):</b> refuses to complete a {@code [BUILD-API]} task unless
 * {@code ./gradlew check} passes in {@code backend/}, a {@code [PACKAGE]} task unless {@code dist/}
 * holds the executable JAR (and the APK when an Android SDK is installed), and a {@code [QA]} or
 * {@code [SHIP]} task
 * unless the QA report says PASS for HEAD. Other tasks complete normally.
 *
 * <p>Exit code 0 allows. Exit code 2 blocks, and the stderr text is sent back to the agent.
 *
 * <p>Run with: {@code java hooks/FactoryGate.java} (Java 21+, no dependencies). As a plugin it is
 * active in every session, so pushes are only gated inside Agent Factory projects.
 */
public final class FactoryGate {

  private static final Pattern EVENT =
      Pattern.compile("\"hook_event_name\"\\s*:\\s*\"([A-Za-z]+)\"");
  private static final Pattern SUBJECT =
      Pattern.compile("\"(?:task_)?(?:subject|title)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

  private static final Pattern COMMAND =
      Pattern.compile("\"command\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
  private static final Pattern CWD = Pattern.compile("\"cwd\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
  private static final Pattern GIT_PUSH = Pattern.compile("\\bgit\\b[^;&|\\n]*\\spush\\b");
  private static final Pattern GH_CREATE_PUSH =
      Pattern.compile("\\bgh\\s+repo\\s+create\\b[^;&|\\n]*--push\\b");
  private static final Pattern FORCE =
      Pattern.compile("\\spush\\b[^;&|\\n]*(\\s--force\\b|\\s--force-with-lease\\b|\\s-f\\b|\\s\\+\\S)");
  private static final boolean WINDOWS =
      System.getProperty("os.name").toLowerCase().contains("win");

  private FactoryGate() {}

  public static void main(String[] args) throws Exception {
    String input = readAll(System.in);
    if ("TaskCompleted".equals(first(EVENT, input))) {
      taskCompleted(input);
      return;
    }
    String command = unescape(first(COMMAND, input));
    if (command == null || !isPush(command)) {
      System.exit(0); // not a push: allow silently
    }

    Path project = locateProject(unescape(first(CWD, input)));
    if (project == null) {
      System.exit(0); // installed as a plugin: pushes outside Agent Factory projects are not gated
    }

    if (FORCE.matcher(command).find()) {
      block("Force pushes are not allowed by the Agent Factory. Push normally instead.");
    }

    checkQaReport(project);

    List<Path> modules = buildModules(project);
    if (modules.isEmpty()) {
      block("Quality gate found no Gradle build (gradlew) in " + project + ". Nothing to verify.");
    }
    for (Path module : modules) {
      runCheck(module);
    }

    System.err.println("Quality gate passed for " + project + ". Push allowed.");
    System.exit(0);
  }

  private static void taskCompleted(String input) throws Exception {
    String subject = unescape(first(SUBJECT, input));
    if (subject == null) {
      System.exit(0);
    }
    boolean build = subject.startsWith("[BUILD-API]");
    boolean pkg = subject.startsWith("[PACKAGE]");
    boolean qaOrShip = subject.startsWith("[QA]") || subject.startsWith("[SHIP]");
    if (!build && !pkg && !qaOrShip) {
      System.exit(0);
    }
    Path project = locateProject(unescape(first(CWD, input)));
    if (project == null) {
      block("Cannot verify '" + subject + "': .factory/current-project is missing.");
    }
    if (pkg) {
      checkArtifacts(project);
    } else if (build) {
      Path backend = project.resolve("backend");
      if (!Files.isRegularFile(backend.resolve(WINDOWS ? "gradlew.bat" : "gradlew"))) {
        block("Cannot complete " + subject + ": no Gradle wrapper in " + backend + ".");
      }
      runCheck(backend);
    } else {
      checkQaReport(project);
    }
    System.exit(0);
  }

  /** The deliverables: an executable JAR, and an APK when the project has an Android app. */
  private static void checkArtifacts(Path project) throws IOException {
    Path dist = project.resolve("dist");
    List<String> files = new ArrayList<>();
    if (Files.isDirectory(dist)) {
      try (var stream = Files.list(dist)) {
        stream.map(p -> p.getFileName().toString()).forEach(files::add);
      }
    }
    boolean jar = files.stream().anyMatch(f -> f.endsWith(".jar") && !f.endsWith("-plain.jar"));
    if (!jar) {
      block("Cannot complete [PACKAGE]: no executable JAR in " + dist + ". Run ./gradlew bootJar in "
          + "backend/ and copy build/libs/<slug>-<version>.jar to dist/.");
    }
    Path android = project.resolve("android");
    boolean apk = files.stream().anyMatch(f -> f.endsWith(".apk"));
    if (Files.isDirectory(android) && androidSdkAvailable(android) && !apk) {
      block("Cannot complete [PACKAGE]: android/ exists and an Android SDK is installed, but there is "
          + "no APK in " + dist + ". Run ./gradlew assembleDebug in android/ and copy the APK to dist/.");
    }
    if (Files.isDirectory(android) && !apk) {
      System.err.println("No Android SDK here: the APK will be built by the release workflow on GitHub.");
    }
  }

  static boolean isPush(String command) {
    return GIT_PUSH.matcher(command).find() || GH_CREATE_PUSH.matcher(command).find();
  }

  private static Path locateProject(String cwd) throws IOException {
    String root = System.getenv("CLAUDE_PROJECT_DIR");
    if (root != null) {
      Path pointer = Path.of(root, ".factory", "current-project");
      if (Files.isRegularFile(pointer)) {
        Path p = Path.of(Files.readString(pointer).trim());
        if (Files.isDirectory(p)) {
          return p;
        }
      }
    }
    // Fallback: walk up from the command's working directory to the nearest .factory folder.
    if (cwd != null) {
      for (Path p = Path.of(cwd).toAbsolutePath(); p != null; p = p.getParent()) {
        if (Files.isDirectory(p.resolve(".factory")) && Files.isDirectory(p.resolve(".git"))) {
          return p;
        }
      }
    }
    return null;
  }

  private static void checkQaReport(Path project) throws Exception {
    Path report = project.resolve(".factory").resolve("qa-report.md");
    if (!Files.isRegularFile(report)) {
      block("No QA report at " + report + ". Run the qa-engineer agent before shipping.");
    }
    List<String> lines = Files.readAllLines(report);
    String status = lines.stream().filter(l -> l.startsWith("STATUS:")).findFirst().orElse("");
    if (!status.trim().equals("STATUS: PASS")) {
      block("QA report does not say STATUS: PASS (" + status.trim() + "). Fix failures first.");
    }
    String reported =
        lines.stream()
            .filter(l -> l.startsWith("Commit:"))
            .map(l -> l.substring("Commit:".length()).trim())
            .findFirst()
            .orElse("");
    Result head = exec(project, List.of("git", "rev-parse", "HEAD"), 30);
    String headSha = head.output().trim();
    if (head.code() != 0 || reported.isEmpty() || !headSha.startsWith(reported)) {
      block(
          "QA report was written for commit '"
              + reported
              + "' but HEAD is '"
              + headSha
              + "'. New commits have not been tested. Re-run qa-engineer.");
    }
  }

  private static List<Path> buildModules(Path project) {
    List<Path> modules = new ArrayList<>();
    for (Path candidate :
        List.of(project, project.resolve("backend"), project.resolve("android"))) {
      if (!Files.isRegularFile(candidate.resolve(WINDOWS ? "gradlew.bat" : "gradlew"))) {
        continue;
      }
      if (candidate.endsWith("android") && !androidSdkAvailable(candidate)) {
        System.err.println("Android SDK not found; skipping android/ (CI still checks it).");
        continue;
      }
      modules.add(candidate);
    }
    return modules;
  }

  private static boolean androidSdkAvailable(Path android) {
    return System.getenv("ANDROID_HOME") != null
        || System.getenv("ANDROID_SDK_ROOT") != null
        || Files.isRegularFile(android.resolve("local.properties"));
  }

  private static void runCheck(Path module) throws Exception {
    String wrapper = WINDOWS ? "gradlew.bat" : "./gradlew";
    List<String> cmd =
        module.endsWith("android")
            ? List.of(wrapper, "lint", "testDebugUnitTest", "--no-daemon", "-q")
            : List.of(wrapper, "check", "--no-daemon", "-q");
    System.err.println("Quality gate: running " + String.join(" ", cmd) + " in " + module);
    Result r = exec(module, cmd, 840);
    if (r.code() != 0) {
      block(
          "Quality gate FAILED in "
              + module
              + " (exit "
              + r.code()
              + "). Blocked until it passes. Last output:\n"
              + tail(r.output(), 40));
    }
  }

  private record Result(int code, String output) {}

  private static Result exec(Path dir, List<String> cmd, long timeoutSeconds) throws Exception {
    List<String> full = new ArrayList<>(cmd);
    if (WINDOWS && cmd.get(0).endsWith(".bat")) {
      full.addAll(0, List.of("cmd", "/c"));
    }
    Process p = new ProcessBuilder(full).directory(dir.toFile()).redirectErrorStream(true).start();
    p.getOutputStream().close();
    byte[] out = p.getInputStream().readAllBytes();
    if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      return new Result(124, "Timed out after " + timeoutSeconds + "s");
    }
    return new Result(p.exitValue(), new String(out, StandardCharsets.UTF_8));
  }

  private static String tail(String text, int lines) {
    String[] all = text.split("\\R");
    int from = Math.max(0, all.length - lines);
    return String.join("\n", java.util.Arrays.copyOfRange(all, from, all.length));
  }

  private static String first(Pattern p, String s) {
    Matcher m = p.matcher(s);
    return m.find() ? m.group(1) : null;
  }

  private static String unescape(String s) {
    if (s == null) {
      return null;
    }
    StringBuilder b = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c != '\\' || i + 1 >= s.length()) {
        b.append(c);
        continue;
      }
      char n = s.charAt(++i);
      switch (n) {
        case 'n' -> b.append('\n');
        case 't' -> b.append('\t');
        case 'r' -> b.append('\r');
        case 'u' -> {
          b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
          i += 4;
        }
        default -> b.append(n);
      }
    }
    return b.toString();
  }

  private static String readAll(InputStream in) throws IOException {
    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
  }

  private static void block(String reason) {
    System.err.println(reason);
    System.exit(2);
  }
}
