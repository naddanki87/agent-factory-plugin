import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Agent Factory live dashboard.
 *
 * <p>Receives Claude Code hook events (HTTP hooks configured in .claude/settings.json) on
 * {@code POST /api/events}, turns them into factory events and streams them to the robot floor UI
 * with Server-Sent Events on {@code GET /api/stream}.
 *
 * <p>Zero dependencies, Java 21+. Run from the repository root: {@code java dashboard/Dashboard.java}
 * then open http://localhost:7070. Port can be changed with the FACTORY_PORT environment variable.
 */
public final class Dashboard {

  static final Set<String> AGENTS =
      Set.of(
          "architect",
          "java-developer",
          "mobile-developer",
          "integration-engineer",
          "qa-engineer",
          "release-admin");
  static final Pattern PUSH =
      Pattern.compile("\\bgit\\b[^;&|\\n]*\\spush\\b|\\bgh\\s+repo\\s+create\\b[^;&|\\n]*--push\\b");

  private static final int BACKLOG = 300;
  private static final boolean DEBUG = "1".equals(System.getenv("FACTORY_DEBUG"));
  private final List<OutputStream> clients = new CopyOnWriteArrayList<>();
  private final Deque<String> backlog = new ArrayDeque<>();
  private final Path indexHtml;

  Dashboard(Path indexHtml) {
    this.indexHtml = indexHtml;
  }

  public static void main(String[] args) throws IOException {
    int port = Integer.parseInt(Optional.ofNullable(System.getenv("FACTORY_PORT")).orElse("7070"));
    Path index = findIndex(args);
    Dashboard app = new Dashboard(index);
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/", app::handleIndex);
    server.createContext("/api/events", app::handleEvent);
    server.createContext("/api/stream", app::handleStream);
    server.createContext("/api/demo", app::handleDemo);
    server.createContext("/api/reset", app::handleReset);
    server.start();
    Thread.ofVirtual().start(app::heartbeat);
    System.out.println("Agent Factory dashboard on http://localhost:" + port + "  (UI: " + index + ")");
  }

  private static Path findIndex(String[] args) {
    List<Path> candidates = new ArrayList<>();
    if (args.length > 0) {
      candidates.add(Path.of(args[0]));
    }
    candidates.add(Path.of("dashboard", "web", "index.html"));
    candidates.add(Path.of("web", "index.html"));
    String root = System.getenv("CLAUDE_PROJECT_DIR");
    if (root != null) {
      candidates.add(Path.of(root, "dashboard", "web", "index.html"));
    }
    return candidates.stream()
        .filter(Files::isRegularFile)
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "index.html not found. Run from the repository root: java dashboard/Dashboard.java"));
  }

  // ---------------------------------------------------------------- HTTP handlers

  private void handleIndex(HttpExchange ex) throws IOException {
    if (!ex.getRequestURI().getPath().equals("/")) {
      send(ex, 404, "text/plain", "Not found");
      return;
    }
    send(ex, 200, "text/html; charset=utf-8", Files.readString(indexHtml));
  }

  private void handleEvent(HttpExchange ex) throws IOException {
    if (!ex.getRequestMethod().equals("POST")) {
      send(ex, 405, "text/plain", "POST only");
      return;
    }
    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    if (DEBUG) {
      System.out.println("hook> " + body);
    }
    try {
      Object parsed = Json.parse(body);
      if (parsed instanceof Map<?, ?> map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> hook = (Map<String, Object>) map;
        normalize(hook).ifPresent(this::publish);
      }
    } catch (RuntimeException e) {
      System.err.println("Ignored malformed hook payload: " + e.getMessage());
    }
    // Empty 2xx body = "success, no decision" for Claude Code HTTP hooks.
    ex.sendResponseHeaders(204, -1);
    ex.close();
  }

  private void handleStream(HttpExchange ex) throws IOException {
    ex.getResponseHeaders().add("Content-Type", "text/event-stream");
    ex.getResponseHeaders().add("Cache-Control", "no-cache");
    ex.sendResponseHeaders(200, 0);
    OutputStream out = ex.getResponseBody();
    synchronized (backlog) {
      for (String event : backlog) {
        write(out, "event: replay\ndata: " + event + "\n\n");
      }
      clients.add(out);
    }
    write(out, "event: ready\ndata: {}\n\n");
  }

  private void handleDemo(HttpExchange ex) throws IOException {
    ex.sendResponseHeaders(204, -1);
    ex.close();
    reset();
    Thread.ofVirtual().start(this::runDemo);
  }

  private void handleReset(HttpExchange ex) throws IOException {
    ex.sendResponseHeaders(204, -1);
    ex.close();
    reset();
  }

  /** Forgets the event history (so new browser tabs start clean) and tells open tabs to clear. */
  private void reset() {
    synchronized (backlog) {
      backlog.clear();
    }
    String frame = "event: reset\ndata: {}\n\n";
    clients.removeIf(c -> !write(c, frame));
  }

  // ---------------------------------------------------------------- hook → factory event

  /** Converts one Claude Code hook payload into a factory event, or empty if it is not shown. */
  static Optional<Map<String, Object>> normalize(Map<String, Object> hook) {
    String event = str(hook, "hook_event_name");
    String agent =
        agentId(firstNonEmpty(str(hook, "agent_type"), str(hook, "teammate_name"), str(hook, "agent_name")));
    String tool = str(hook, "tool_name");
    Map<String, Object> input = map(hook, "tool_input");
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("ts", System.currentTimeMillis());
    out.put("agent", agent);

    switch (event) {
      case "TaskCreated", "TaskCompleted" -> {
        Map<String, Object> task = map(hook, "task");
        String subject =
            firstNonEmpty(str(hook, "task_subject"), str(hook, "subject"), str(task, "subject"), str(task, "title"));
        out.put("kind", event.equals("TaskCreated") ? "task-created" : "task-done");
        out.put("taskId", firstNonEmpty(str(hook, "task_id"), str(task, "id")));
        out.put("text", clip(subject.isEmpty() ? "Task" : subject, 120));
        out.put("stage", stageOf(subject));
        String owner = firstNonEmpty(str(hook, "owner"), str(task, "owner"));
        if (!owner.isEmpty()) {
          out.put("agent", agentId(owner));
        }
      }
      case "TeammateIdle" -> {
        out.put("kind", "idle");
        out.put("text", "Idle, waiting for work");
      }
      case "PreToolUse" -> {
        if (!tool.equals("Agent") && !tool.equals("Task")) {
          return Optional.empty();
        }
        out.put("kind", "dispatch");
        out.put("from", agent);
        out.put("to", agentId(str(input, "subagent_type")));
        String text = str(input, "description");
        out.put("text", clip(text.isEmpty() ? str(input, "prompt") : text, 140));
      }
      case "SubagentStart" -> {
        out.put("kind", "start");
        out.put("text", "Started " + str(hook, "agent_type"));
      }
      case "SubagentStop" -> {
        out.put("kind", "stop");
        String last = str(hook, "last_assistant_message");
        out.put("text", clip(last.isEmpty() ? "Finished, report sent to lead" : last, 160));
      }
      case "PostToolUse" -> {
        switch (tool) {
          case "Write", "Edit", "MultiEdit" -> {
            out.put("kind", "tool");
            out.put("text", (tool.equals("Write") ? "Wrote " : "Edited ") + shortPath(str(input, "file_path")));
          }
          case "Bash" -> {
            String cmd = str(input, "command").strip();
            out.put("kind", PUSH.matcher(cmd).find() ? "push" : "tool");
            out.put("text", "$ " + clip(cmd.lines().findFirst().orElse(""), 110));
          }
          case "SendMessage" -> {
            out.put("kind", "message");
            out.put("from", agent);
            out.put("to", agentId(firstNonEmpty(str(input, "to"), str(input, "recipient"), str(input, "name"))));
            out.put(
                "text",
                clip(firstNonEmpty(str(input, "summary"), str(input, "message"), str(input, "content")), 160));
          }
          case "TaskUpdate" -> {
            String status = str(input, "status");
            String owner = str(input, "owner");
            if (status.isEmpty() && owner.isEmpty()) {
              return Optional.empty();
            }
            out.put("kind", "task-update");
            out.put("taskId", str(input, "taskId"));
            out.put("status", status);
            out.put("text", (owner.isEmpty() ? "Task " : owner + " claimed task ") + str(input, "taskId")
                + (status.isEmpty() ? "" : " → " + status));
            if (!owner.isEmpty()) {
              out.put("agent", agentId(owner));
            }
          }
          default -> {
            return Optional.empty();
          }
        }
      }
      default -> {
        return Optional.empty();
      }
    }
    return Optional.of(out);
  }

  static String stageOf(String subject) {
    if (subject.startsWith("[PLAN]")) {
      return "plan";
    }
    if (subject.startsWith("[BUILD")) {
      return "build";
    }
    if (subject.startsWith("[PACKAGE]") || subject.startsWith("[INTEGRATE]")) {
      return "integrate";
    }
    if (subject.startsWith("[QA]")) {
      return "test";
    }
    if (subject.startsWith("[SHIP]")) {
      return "ship";
    }
    return "";
  }

  static String firstNonEmpty(String... values) {
    for (String v : values) {
      if (v != null && !v.isBlank()) {
        return v;
      }
    }
    return "";
  }

  static String agentId(String agentType) {
    if (agentType == null || agentType.isEmpty()) {
      return "lead";
    }
    String bare = agentType.contains(":") ? agentType.substring(agentType.lastIndexOf(':') + 1) : agentType;
    return AGENTS.contains(bare) ? bare : "lead";
  }

  static String shortPath(String path) {
    String[] parts = path.replace('\\', '/').split("/");
    int from = Math.max(0, parts.length - 3);
    return String.join("/", java.util.Arrays.copyOfRange(parts, from, parts.length));
  }

  static String clip(String s, int max) {
    String one = s.replaceAll("\\s+", " ").strip();
    return one.length() <= max ? one : one.substring(0, max - 1) + "…";
  }

  private static String str(Map<String, Object> m, String key) {
    Object v = m == null ? null : m.get(key);
    return v == null ? "" : v.toString();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Map<String, Object> m, String key) {
    Object v = m.get(key);
    return v instanceof Map<?, ?> ? (Map<String, Object>) v : Map.of();
  }

  // ---------------------------------------------------------------- streaming

  private void publish(Map<String, Object> event) {
    String json = Json.write(event);
    synchronized (backlog) {
      backlog.addLast(json);
      while (backlog.size() > BACKLOG) {
        backlog.removeFirst();
      }
    }
    for (OutputStream c : clients) {
      if (!write(c, "data: " + json + "\n\n")) {
        clients.remove(c);
      }
    }
  }

  private static boolean write(OutputStream out, String frame) {
    try {
      synchronized (out) {
        out.write(frame.getBytes(StandardCharsets.UTF_8));
        out.flush();
      }
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  private void heartbeat() {
    while (true) {
      try {
        Thread.sleep(15_000);
      } catch (InterruptedException e) {
        return;
      }
      clients.removeIf(c -> !write(c, ": ping\n\n"));
    }
  }

  private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", type);
    ex.sendResponseHeaders(code, bytes.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(bytes);
    }
  }

  // ---------------------------------------------------------------- demo (real hook shapes)

  private void runDemo() {
    String p = "/work/agent-factory/projects/expense-tracker/";
    String pkg = p + "backend/src/main/java/com/factory/expensetracker/";
    List<Object[]> s = new ArrayList<>();
    s.add(step(500, task("TaskCreated", "[PLAN] Architecture, OpenAPI contract and task breakdown", "")));
    s.add(step(250, task("TaskCreated", "[BUILD-API] Spring Boot backend in backend/", "")));
    s.add(step(250, task("TaskCreated", "[BUILD-ANDROID] Android app in android/", "")));
    s.add(step(250, task("TaskCreated", "[PACKAGE] Executable JAR + APK in dist/, CI and release workflows", "")));
    s.add(step(250, task("TaskCreated", "[QA] Quality gate: tests, coverage, acceptance criteria, JAR starts", "")));
    s.add(step(250, task("TaskCreated", "[SHIP] Push to GitHub, release v1.0.0 with JAR and APK attached", "")));
    for (String a : List.of("architect", "java-developer", "mobile-developer", "integration-engineer", "qa-engineer", "release-admin")) {
      s.add(step(220, hook("PreToolUse", "", "Agent", Map.of("subagent_type", a, "name", a, "description", "Spawn teammate " + a))));
    }
    s.add(step(600, claim("architect", "1")));
    s.add(step(1200, tool("architect", "Write", p + "docs/architecture.md")));
    s.add(step(1200, tool("architect", "Write", p + "docs/openapi.yaml")));
    s.add(step(1000, tool("architect", "Write", p + "docs/tasks.md")));
    s.add(step(700, task("TaskCompleted", "[PLAN] Architecture, OpenAPI contract and task breakdown", "architect")));
    s.add(step(500, msg("architect", "java-developer", "Contract in docs/openapi.yaml. You own 6 /api/v1/expenses endpoints + JWT auth.")));
    s.add(step(900, msg("architect", "mobile-developer", "Android app: 4 screens. Use the examples in openapi.yaml for your MockWebServer tests.")));
    s.add(step(800, claim("java-developer", "2")));
    s.add(step(200, claim("mobile-developer", "3")));
    s.add(step(1000, tool("java-developer", "Write", pkg + "api/ExpenseController.java")));
    s.add(step(800, tool("mobile-developer", "Write", p + "android/app/src/main/java/com/factory/expensetracker/MainActivity.java")));
    s.add(step(900, msg("mobile-developer", "java-developer", "Does GET /expenses return a page object or a plain array?")));
    s.add(step(1100, msg("java-developer", "mobile-developer", "Page object: { content, page, size, totalElements }. See ExpensePage schema.")));
    s.add(step(900, tool("java-developer", "Write", pkg + "service/ExpenseService.java")));
    s.add(step(800, tool("mobile-developer", "Write", p + "android/app/src/main/java/com/factory/expensetracker/api/ApiClient.java")));
    s.add(step(1000, bash("java-developer", "./gradlew spotlessApply check")));
    s.add(step(900, bash("mobile-developer", "./gradlew lint testDebugUnitTest")));
    s.add(step(700, task("TaskCompleted", "[BUILD-API] Spring Boot backend in backend/", "java-developer")));
    s.add(step(400, msg("java-developer", "integration-engineer", "API ready on :8080. Runs on embedded H2, only JWT_SECRET needed.")));
    s.add(step(700, task("TaskCompleted", "[BUILD-ANDROID] Android app in android/", "mobile-developer")));
    s.add(step(400, msg("mobile-developer", "integration-engineer", "APK builds with ./gradlew assembleDebug, API_BASE_URL=http://10.0.2.2:8080/")));
    s.add(step(800, claim("integration-engineer", "4")));
    s.add(step(1000, bash("integration-engineer", "./gradlew bootJar && cp build/libs/expense-tracker-1.0.0.jar ../dist/")));
    s.add(step(900, bash("integration-engineer", "./gradlew assembleDebug && cp app/build/outputs/apk/debug/app-debug.apk ../dist/expense-tracker-1.0.0.apk")));
    s.add(step(900, tool("integration-engineer", "Write", p + ".github/workflows/ci.yml")));
    s.add(step(800, tool("integration-engineer", "Write", p + ".github/workflows/release.yml")));
    s.add(step(1000, bash("integration-engineer", "java -jar dist/expense-tracker-1.0.0.jar --server.port=18080  # smoke test: health UP")));
    s.add(step(700, task("TaskCompleted", "[PACKAGE] Executable JAR + APK in dist/, CI and release workflows", "integration-engineer")));
    s.add(step(400, msg("integration-engineer", "qa-engineer", "Artifacts ready: dist/expense-tracker-1.0.0.jar (31 MB) + .apk (6 MB). JAR smoke test: health UP.")));
    s.add(step(800, claim("qa-engineer", "5")));
    s.add(step(1300, bash("qa-engineer", "./gradlew clean check")));
    s.add(step(900, msg("qa-engineer", "java-developer", "FAIL: POST /expenses accepts amount -50. Test ExpenseApiTest#rejectsNegativeAmount.")));
    s.add(step(1100, tool("java-developer", "Edit", pkg + "api/ExpenseRequest.java")));
    s.add(step(900, bash("java-developer", "./gradlew check && git commit -m \"fix(api): reject negative amounts\"")));
    s.add(step(700, msg("java-developer", "qa-engineer", "Fixed in a3f9c1e with @Positive. Regression test added.")));
    s.add(step(1200, bash("qa-engineer", "./gradlew clean check")));
    s.add(step(800, tool("qa-engineer", "Write", p + ".factory/qa-report.md")));
    s.add(step(700, task("TaskCompleted", "[QA] Quality gate: tests, coverage, acceptance criteria, JAR starts", "qa-engineer")));
    s.add(step(400, msg("qa-engineer", "release-admin", "QA PASS: 59/59 tests, coverage 86%, commit a3f9c1e.")));
    s.add(step(900, msg("lead", "release-admin", "User approved shipping: your-org/expense-tracker, private.")));
    s.add(step(700, claim("release-admin", "6")));
    s.add(step(900, bash("release-admin", "gh auth status")));
    s.add(step(1400, bash("release-admin", "gh repo create your-org/expense-tracker --private --source=. --remote=origin --push")));
    s.add(step(1000, bash("release-admin", "gh release create v1.0.0 dist/*.jar dist/*.apk --title v1.0.0 --notes-file .factory/release-notes.md")));
    s.add(step(700, task("TaskCompleted", "[SHIP] Push to GitHub, release v1.0.0 with JAR and APK attached", "release-admin")));
    s.add(step(400, msg("release-admin", "lead", "Shipped github.com/your-org/expense-tracker · v1.0.0 with expense-tracker-1.0.0.jar and .apk")));
    for (String a : List.of("architect", "java-developer", "mobile-developer", "integration-engineer", "qa-engineer", "release-admin")) {
      s.add(step(150, idle(a)));
    }
    try {
      for (Object[] st : s) {
        Thread.sleep((Integer) st[0]);
        @SuppressWarnings("unchecked")
        Map<String, Object> h = (Map<String, Object>) st[1];
        normalize(h).ifPresent(this::publish);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static Object[] step(int delayMs, Map<String, Object> hook) {
    return new Object[] {delayMs, hook};
  }

  private static Map<String, Object> hook(String event, String agent, String tool, Map<String, Object> input) {
    Map<String, Object> h = new LinkedHashMap<>();
    h.put("hook_event_name", event);
    h.put("agent_type", agent.equals("lead") ? "" : agent);
    h.put("tool_name", tool);
    h.put("tool_input", input);
    return h;
  }

  private static final List<String> DEMO_TASKS =
      List.of("[PLAN]", "[BUILD-API]", "[BUILD-ANDROID]", "[PACKAGE]", "[QA]", "[SHIP]");

  private static Map<String, Object> task(String event, String subject, String owner) {
    Map<String, Object> h = hook(event, owner, "", Map.of());
    h.put("task_subject", subject);
    h.put("task_id", String.valueOf(DEMO_TASKS.indexOf(subject.substring(0, subject.indexOf(']') + 1)) + 1));
    return h;
  }

  private static Map<String, Object> claim(String agent, String taskId) {
    return hook("PostToolUse", agent, "TaskUpdate", Map.of("taskId", taskId, "owner", agent, "status", "in_progress"));
  }

  private static Map<String, Object> tool(String agent, String tool, String path) {
    return hook("PostToolUse", agent, tool, Map.of("file_path", path));
  }

  private static Map<String, Object> bash(String agent, String command) {
    return hook("PostToolUse", agent, "Bash", Map.of("command", command));
  }

  private static Map<String, Object> msg(String from, String to, String text) {
    return hook("PostToolUse", from, "SendMessage", Map.of("to", to, "summary", text));
  }

  private static Map<String, Object> idle(String agent) {
    return hook("TeammateIdle", agent, "", Map.of());
  }

  // ---------------------------------------------------------------- minimal JSON

  /** Small JSON reader/writer so the dashboard needs no libraries. */
  static final class Json {
    private final String s;
    private int i;

    private Json(String s) {
      this.s = s;
    }

    static Object parse(String text) {
      Json j = new Json(text);
      Object v = j.value();
      j.ws();
      if (j.i != j.s.length()) {
        throw new IllegalArgumentException("Trailing data at " + j.i);
      }
      return v;
    }

    private Object value() {
      ws();
      if (i >= s.length()) {
        throw new IllegalArgumentException("Unexpected end");
      }
      char c = s.charAt(i);
      return switch (c) {
        case '{' -> object();
        case '[' -> array();
        case '"' -> string();
        case 't' -> literal("true", Boolean.TRUE);
        case 'f' -> literal("false", Boolean.FALSE);
        case 'n' -> literal("null", null);
        default -> number();
      };
    }

    private Map<String, Object> object() {
      Map<String, Object> m = new LinkedHashMap<>();
      i++;
      ws();
      if (peek('}')) {
        i++;
        return m;
      }
      while (true) {
        ws();
        String k = string();
        ws();
        expect(':');
        m.put(k, value());
        ws();
        if (peek(',')) {
          i++;
          continue;
        }
        expect('}');
        return m;
      }
    }

    private List<Object> array() {
      List<Object> l = new ArrayList<>();
      i++;
      ws();
      if (peek(']')) {
        i++;
        return l;
      }
      while (true) {
        l.add(value());
        ws();
        if (peek(',')) {
          i++;
          continue;
        }
        expect(']');
        return l;
      }
    }

    private String string() {
      expect('"');
      StringBuilder b = new StringBuilder();
      while (i < s.length()) {
        char c = s.charAt(i++);
        if (c == '"') {
          return b.toString();
        }
        if (c != '\\') {
          b.append(c);
          continue;
        }
        char e = s.charAt(i++);
        switch (e) {
          case 'n' -> b.append('\n');
          case 't' -> b.append('\t');
          case 'r' -> b.append('\r');
          case 'b' -> b.append('\b');
          case 'f' -> b.append('\f');
          case 'u' -> {
            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
            i += 4;
          }
          default -> b.append(e);
        }
      }
      throw new IllegalArgumentException("Unterminated string");
    }

    private Object number() {
      int start = i;
      while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
        i++;
      }
      if (start == i) {
        throw new IllegalArgumentException("Unexpected character at " + i);
      }
      String n = s.substring(start, i);
      return n.matches("-?\\d+") ? (Object) Long.parseLong(n) : (Object) Double.parseDouble(n);
    }

    private Object literal(String word, Object v) {
      if (!s.startsWith(word, i)) {
        throw new IllegalArgumentException("Bad literal at " + i);
      }
      i += word.length();
      return v;
    }

    private void ws() {
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
        i++;
      }
    }

    private boolean peek(char c) {
      return i < s.length() && s.charAt(i) == c;
    }

    private void expect(char c) {
      if (!peek(c)) {
        throw new IllegalArgumentException("Expected '" + c + "' at " + i);
      }
      i++;
    }

    static String write(Object v) {
      if (v == null) {
        return "null";
      }
      if (v instanceof String str) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : str.toCharArray()) {
          switch (c) {
            case '"' -> b.append("\\\"");
            case '\\' -> b.append("\\\\");
            case '\n' -> b.append("\\n");
            case '\r' -> b.append("\\r");
            case '\t' -> b.append("\\t");
            default -> {
              if (c < 0x20 || c == ' ' || c == ' ') {
                b.append(String.format("\\u%04x", (int) c));
              } else {
                b.append(c);
              }
            }
          }
        }
        return b.append('"').toString();
      }
      if (v instanceof Map<?, ?> m) {
        StringBuilder b = new StringBuilder("{");
        m.forEach(
            (k, val) -> {
              if (b.length() > 1) {
                b.append(',');
              }
              b.append(write(String.valueOf(k))).append(':').append(write(val));
            });
        return b.append('}').toString();
      }
      if (v instanceof List<?> l) {
        StringBuilder b = new StringBuilder("[");
        for (Object o : l) {
          if (b.length() > 1) {
            b.append(',');
          }
          b.append(write(o));
        }
        return b.append(']').toString();
      }
      return v.toString();
    }
  }
}
