# Daily Report Telegram Bot Constitution

## Core Principles

### I. Preserve Explicit Business Rules

Every change MUST preserve the documented Telegram report flow unless the user explicitly approves a behavior change. `/report` starts the input session, ordinary text outside a pending session is not saved, blank content is rejected, `/cancel` creates no report, and read/link commands create no report. Multiple reports per employee per day remain valid until the product specification changes.

### II. Keep Changes Small and Traceable

Features MUST be split into the smallest independently testable increment that advances the current milestone. Existing package boundaries, Spring services, repositories, entities, and command handlers SHOULD be extended using established patterns. Broad refactors, new dependencies, schema changes, Mini App activation, dashboard work, security, or reminders require explicit scope and dependency justification.

### III. Test According to Risk

Behavior changes MUST include focused automated tests. Repository query changes require database-level integration coverage when mocks cannot validate derived query semantics. Relevant focused tests run first, followed by `mvn test`. A feature is not complete when tests were not run or failed. Telegram runtime testing MUST be reported separately and MUST NOT be claimed without a real bot runtime/token test.

### IV. Stabilize Identity and Data Semantics Before Automation

Employee identity, Telegram mapping, report ownership, report date, timezone, submitted/not-submitted semantics, and migration strategy MUST be explicit before reminder, dashboard, statistics, or manager APIs consume the data. Historical ownership MUST NOT change implicitly through remapping unless that behavior is deliberately specified and tested.

### V. Protect Secrets and Existing Work

Real `.env` values, bot tokens, passwords, private keys, and production data MUST NOT be read, copied, logged, indexed, committed, or placed in generated artifacts. Existing dirty or untracked user changes MUST NOT be reverted, deleted, moved, formatted, or overwritten without explicit approval. Installers and agent tooling MUST be reviewed, pinned when practical, isolated from production runtime, and have a rollback path.

## Project Constraints

- Runtime stack: Java 17+, Spring Boot 3, Maven, Spring Data JPA, PostgreSQL, and TelegramBots.
- `APP_REPORT_TIME_ZONE` determines `report_date`; server default timezone MUST NOT silently change report dates.
- Employee/department and report status are foundation modules; current report submission does not require employee mapping unless the approved feature spec changes this.
- Spec Kit and Codebase Memory MCP are development tools only. They MUST NOT become Spring Boot, Docker, or production dependencies.
- Parlant and other LLM runtime frameworks remain out of scope until a separate approved LLM use case, security boundary, data policy, evaluation plan, and operating budget exist.
- The four root Markdown files remain project-level documentation source of truth. Feature artifacts under `specs/` are subordinate and MUST remain consistent with them.
- Generated HTML under `docs/generated/` is updated only when explicitly requested.

## Development Workflow and Quality Gates

1. Inspect `git status`, relevant diffs, source files, and tests before changing behavior.
2. Clarify unresolved business decisions in the feature specification before implementation planning.
3. Review the implementation plan and task list before editing runtime code or schema.
4. Keep edits scoped; do not clean unrelated worktree changes.
5. Run focused tests, then the full Maven suite for Java changes.
6. Update affected Markdown after verified behavior changes. Do not claim unimplemented work as complete.
7. Review `git diff --check`, final status, secrets exposure, generated files, and tool-created configuration before handoff.

## Governance

This constitution governs Spec Kit feature artifacts and development workflow for this repository. Explicit user instructions take precedence for the active task. `PROJECT_SPEC.md` defines product scope, `ROADMAP_AND_PLAN.md` defines sequencing and dependencies, `README.md` defines handoff conventions, and `DEPLOYMENT_GUIDE.md` defines operations. Amendments require a documented reason, user approval when they change scope or business rules, and synchronized updates to affected source-of-truth Markdown. Compliance MUST be reviewed before implementation and again before completion.

**Version**: 1.0.0 | **Ratified**: 2026-07-01 | **Last Amended**: 2026-07-01
