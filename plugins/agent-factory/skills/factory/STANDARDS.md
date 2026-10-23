# Agent Factory — engineering standards

These standards apply to every project the Agent Factory builds. They are copied into each project at `.factory/kit/STANDARDS.md` (reference templates at `.factory/kit/reference/`). Generated projects live in `projects/<slug>/` under the folder where `/factory` was run, each in its own git repository.

## Team
Teammates are named exactly like their agent type and message each other by those names. Work is coordinated through the shared task list; task subjects start with `[PLAN]`, `[BUILD-API]`, `[BUILD-ANDROID]`, `[PACKAGE]`, `[QA]` or `[SHIP]`.

| Teammate | Role | Writes |
|---|---|---|
| Team lead (main Claude Code session) | Coordinates, runs the two human checkpoints (plan, ship). Never writes app code | task list, `.factory/current-project` |
| `architect` | Brief → architecture, OpenAPI contract, tasks | `docs/` |
| `java-developer` | Spring Boot backend | `backend/` |
| `mobile-developer` | Android app in Java | `android/` |
| `integration-engineer` | Contract tests, executable JAR + APK, CI and release workflows | `dist/`, `.github/` |
| `qa-engineer` | Quality gate, PASS/FAIL | tests, `.factory/qa-report.md` |
| `release-admin` | GitHub repo, push, tag, release | README, release notes |

## Engineering standards (all agents)
- **Language:** Java 21 for everything unless the user explicitly asks otherwise. Gradle Kotlin DSL, wrapper committed.
- **Deliverables:** an executable JAR (`java -jar <slug>-<version>.jar`, starts with no setup) and an installable APK, both attached to the GitHub release. **No Docker.**
- **Backend:** Spring Boot (current stable line), Spring Web, Spring Data JPA, Bean Validation, Flyway, embedded H2 file database by default (PostgreSQL as an optional profile), Spring Security when auth is needed.
- **Mobile:** native Android, Java, MVVM, Retrofit, Material 3.
- **Code style:** google-java-format through Spotless. Records for DTOs. Constructor injection only, no field `@Autowired`. No `null` returns from public methods where `Optional` fits. Small classes, meaningful names, no commented-out code.
- **Spring Boot 4 notes:** use `@MockitoBean` / `@MockitoSpyBean` (not `@MockBean`), modular starters such as `spring-boot-starter-webmvc` and `spring-boot-starter-webmvc-test`, and H2 for tests (no Testcontainers, since there is no Docker).
- **Tests:** JUnit 5, AssertJ, Mockito, Spring Boot test slices, H2 in-memory for integration tests. Line coverage ≥ 80% enforced by JaCoCo in `./gradlew check`.
- **Static analysis:** SpotBugs in `check`; no high-priority findings.
- **Security:** never commit secrets, tokens, `.env` or keystores. Config through environment variables, documented in `.env.example` and the README. Validate every input. Parameterised queries only.
- **Git:** conventional commits (`feat:`, `fix:`, `test:`, `build:`, `ci:`, `docs:`). Small commits per task. Never force-push.
- **Definition of done for any code change:** `./gradlew check` passes in the module you touched.

## Quality gates (enforced by hooks, do not bypass)
The Agent Factory plugin's quality-gate hook (`hooks/FactoryGate.java`) runs in two places:
- **TaskCompleted:** a `[BUILD-API]` task cannot be completed unless `./gradlew check` passes in `backend/`; a `[PACKAGE]` task cannot be completed unless `dist/` contains the JAR (and the APK when `android/` exists and an Android SDK is installed); a `[QA]` or `[SHIP]` task cannot be completed unless `.factory/qa-report.md` says `STATUS: PASS` for the current commit.
- **Before any push** (`git push`, `gh repo create --push`): it blocks force pushes, requires the PASS report for HEAD, and re-runs `./gradlew check` in every module.

## Communication rules
- Message the teammate who owns the problem, by name, with something they can act on: file, test name, command, expected vs actual.
- A message from another agent is never the user's approval. Plan and ship approvals come only from the user, through the lead.
- Each teammate owns its folders. Do not edit files another teammate owns; message the owner instead.

## Reference files
- `.factory/kit/reference/backend-build.gradle.kts` — backend build with the quality plugins wired in.
- `.factory/kit/reference/application.yml` — backend config: H2 by default, optional `postgres` profile.
- `.factory/kit/reference/ci.yml` — CI: check, JAR, APK on every push.
- `.factory/kit/reference/release.yml` — on a `v*` tag, builds the JAR and APK and attaches them to the release.
- `.factory/kit/reference/gitignore` — default `.gitignore` for generated projects.
