---
name: factory-dashboard
description: Start the Agent Factory live dashboard (robot floor) at http://localhost:7070 to watch an Agent Factory team run.
disable-model-invocation: true
---

# Start the Agent Factory dashboard

The dashboard ships with the Agent Factory plugin. This skill's base directory is shown above as "Base directory for this skill"; the dashboard is two folders up from it, in `dashboard/`.

1. Check whether it is already running: `curl -s -o /dev/null -w "%{http_code}" http://localhost:7070` returns `200` if so. If it is, just tell the user the URL.
2. Otherwise start it **in the background** (it is a long-running server), using Java 21+:
   `java "<plugin>/dashboard/Dashboard.java" "<plugin>/dashboard/web/index.html"`
   where `<plugin>` is the skill base directory with `/skills/factory-dashboard` removed.
3. Wait a few seconds, confirm `http://localhost:7070` returns 200, and tell the user to open it. **Play demo run** shows a sample run without using Claude.

If `java` is not found, tell the user to install Java 21 (for example `winget install EclipseAdoptium.Temurin.21.JDK`) and restart the Claude app.
