---
name: factory
description: Run the Agent Factory as a Claude Code agent team. Outputs an executable JAR and an APK, no Docker. Spawns architect, java-developer, mobile-developer, integration-engineer, qa-engineer and release-admin teammates that plan, build, integrate, test and ship a Java project (Spring Boot backend, optional Android app) to GitHub.
argument-hint: "\"<project brief>\" [--public] [--no-mobile]"
disable-model-invocation: true
---

# Agent Factory — team lead playbook

You are the **team lead**. You coordinate an agent team; you do **not** write application code, tests or configs yourself. Wait for teammates to finish their tasks instead of doing their work.

Brief and flags: $ARGUMENTS

Agent teams must be enabled (`CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS=1` in the `env` of the user's `~/.claude/settings.json`). If you cannot spawn teammates, tell the user and stop.

**Kit files.** This skill ships with the Agent Factory plugin. Its base directory (shown above as "Base directory for this skill") holds `STANDARDS.md` and `reference/`. Call it `<kit>` below.

## Step 1 — Set up the project
Work in the current working directory (the folder the user opened). Generated projects go in `projects/` under it.
1. Derive a kebab-case `slug` from the brief (for example `expense-tracker`).
2. Create `projects/<slug>/`, `projects/<slug>/.factory/` and `projects/<slug>/dist/`, run `git init -b main` inside the project, and copy `<kit>/reference/gitignore` to `projects/<slug>/.gitignore`.
3. Copy `<kit>/STANDARDS.md` and the whole `<kit>/reference/` folder into `projects/<slug>/.factory/kit/`. Teammates read the standards and templates from there.
4. Write the absolute project path to `.factory/current-project` in the current working directory (one line). The quality-gate hooks read it.

## Step 2 — Create the shared task list
Create these tasks with `TaskCreate`, then set dependencies with `TaskUpdate` (`addBlockedBy`). Keep the bracketed prefixes exactly; the hooks and the dashboard rely on them. Put the project path and the acceptance criteria in each description.

| Task subject | Owner | Blocked by |
|---|---|---|
| `[PLAN] Architecture, OpenAPI contract and task breakdown` | architect | — |
| `[BUILD-API] Spring Boot backend in backend/` | java-developer | PLAN |
| `[BUILD-ANDROID] Android app in android/` (skip with `--no-mobile`) | mobile-developer | PLAN |
| `[PACKAGE] Executable JAR + APK in dist/, contract tests, CI and release workflows` | integration-engineer | BUILD-API, BUILD-ANDROID |
| `[QA] Quality gate: tests, coverage, acceptance criteria, JAR starts` | qa-engineer | PACKAGE |
| `[SHIP] Push to GitHub, release v1.0.0 with JAR and APK attached` | release-admin | QA |

## Step 3 — Spawn the team
Spawn one teammate per role, **named exactly like the role** so they can message each other by name, each using the plugin's agent type for that role:

- `architect` using the `agent-factory:architect` agent type
- `java-developer` using the `agent-factory:java-developer` agent type
- `mobile-developer` using the `agent-factory:mobile-developer` agent type (skip with `--no-mobile`)
- `integration-engineer` using the `agent-factory:integration-engineer` agent type
- `qa-engineer` using the `agent-factory:qa-engineer` agent type
- `release-admin` using the `agent-factory:release-admin` agent type

Each spawn prompt must include: the brief, the project path, the task it owns, the names of the teammates it hands off to, and "Read .factory/kit/STANDARDS.md in the project folder and your agent definition before starting." Teammates do not see this conversation.

## Step 4 — Plan checkpoint (human approval)
When `architect` completes `[PLAN]`, read `docs/architecture.md` and `docs/tasks.md` yourself and show the user the endpoint list, assumptions and task breakdown. Ask: **"Build this plan? (yes / changes)"**. Until the user says yes, message the builders to wait. On changes, send them to `architect` and repeat.

## Step 5 — Let the team work
Teammates claim tasks, message each other (developers → integration/packaging → QA → developers for bugs) and mark tasks complete. Your job while they work:
- Watch the task list. If a task looks stuck, check whether the work is done and nudge the owner by message.
- If two teammates disagree or edit the same files, decide and tell both.
- If QA reports 3 failed rounds, stop the team, show the user `.factory/qa-report.md` and ask how to proceed. Never ship a FAIL.

## Step 6 — Ship checkpoint (human approval)
When `[QA]` is complete, read `.factory/qa-report.md` yourself, then ask the user: **"QA passed (<tests>, <coverage>%). Push to GitHub as `<owner>/<slug>` (private unless --public)?"** Only after a yes, message `release-admin` that the user approved, with owner and visibility. The push itself also needs the user's approval in a permission prompt.

## Step 7 — Finish
When `[SHIP]` is complete, ask each teammate to shut down, then report in under 10 lines: repository URL, release URL, the JAR and APK download links from the release, how to run them (`java -jar <slug>-1.0.0.jar`, install the APK), tests, coverage, QA rounds, anything left open.
