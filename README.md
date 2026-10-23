# Agent Factory plugin

Claude Code plugin version of the Agent Factory kit: `/agent-factory:factory "<brief>"` runs an agent team (architect, java-developer, mobile-developer, integration-engineer, qa-engineer, release-admin) that builds a Spring Boot API and optional Android app, tests it, and ships it to GitHub with an executable JAR and an APK.

## Install
```
claude plugin marketplace add naddanki87/agent-factory-plugin
claude plugin install agent-factory@agent-factory
```
Or inside Claude Code: `/plugin marketplace add naddanki87/agent-factory-plugin`, then `/plugin install agent-factory@agent-factory`.
Then enable agent teams once in `~/.claude/settings.json`:
```json
{ "env": { "CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS": "1" } }
```

## Use
- `/agent-factory:factory "Expense tracker - Spring Boot API + Android app"` (options: `--public`, `--no-mobile`)
- `/agent-factory:factory-dashboard` starts the live dashboard at http://localhost:7070

Requirements: Java 21+, Git, GitHub CLI signed in (`gh auth login`, plus `gh auth refresh -s workflow`). Projects are created in `projects/<slug>/` under the folder you open.
