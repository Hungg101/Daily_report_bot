# AI Agent Instructions

## Project Context

This is a small personal project for testing and gradually building a Telegram Bot for daily work reports.

Current priority:

```text
Make Telegram Bot connect successfully with Spring Boot.
```

Do not design the full system yet.

---

## Core Principles

| Rule                    | Instruction                                                                   |
| ----------------------- | ----------------------------------------------------------------------------- |
| Simplicity first        | Write the minimum code needed for the current task.                           |
| Test first milestone    | Make the Telegram Bot run and reply before adding other features.             |
| No over-engineering     | Do not create complex architecture, unnecessary layers, or abstract patterns. |
| No speculative features | Do not implement features that were not requested.                            |
| Small changes           | Touch only files needed for the current task.                                 |
| Clear assumptions       | If something is unclear, state assumptions before coding.                     |
| Verify result           | Every task must include how to run and how to test.                           |
| No hard-coded secrets   | Bot token and sensitive config must come from config/env variables.           |

---

## Coding Guidelines

| Rule                       | Instruction                                                                         |
| -------------------------- | ----------------------------------------------------------------------------------- |
| Keep code short            | If code can be simpler, simplify it.                                                |
| Use readable names         | Class, method, and variable names must clearly show purpose.                        |
| Use constructor injection  | Do not use field injection with `@Autowired`.                                       |
| Avoid premature interfaces | Do not create interfaces unless there are multiple implementations or a clear need. |
| Avoid unused abstractions  | Do not create factories, strategies, adapters, or helpers unless actually needed.   |
| Validate basic input       | Handle empty messages, non-text updates, and missing chat IDs safely.               |
| Log useful info            | Log Telegram user ID, username, chat ID, and message text.                          |
| Do not log secrets         | Never print bot token, database password, or private keys.                          |

---

## Scope Control

For now, do not implement:

```text
Database
JPA entities
Spring Security
Scheduler
REST API
Admin dashboard
Employee management
Department management
Report statistics
Docker
CI/CD
Microservices
Complex folder structure
```

Only implement what is needed to test Telegram Bot connection.

---

## Telegram Bot Test Requirements

The bot should support:

| Input           | Expected behavior                   |
| --------------- | ----------------------------------- |
| `/start`        | Reply: `Bot hoạt động bình thường.` |
| Any normal text | Echo the message back to the user.  |

The application should log:

```text
Telegram User ID
Telegram Username
Chat ID
Message Text
```

---

## AI Agent Behavior

Before coding:

1. State the goal.
2. State assumptions.
3. Provide a short plan.
4. Mention what will not be implemented.

While coding:

1. Make minimal changes.
2. Do not refactor unrelated code.
3. Do not add unnecessary dependencies.
4. Do not create unused files.
5. Keep the implementation easy to run locally.

After coding:

1. Summarize changed files.
2. Provide run command.
3. Provide Telegram test steps.
4. Provide expected result.
5. Mention any limitation clearly.

---

## Success Criteria

The task is complete only when:

```text
1. Spring Boot app starts successfully.
2. Telegram Bot receives `/start`.
3. Bot replies: `Bot hoạt động bình thường.`
4. Bot echoes normal text messages.
5. Console logs Telegram user ID, username, chat ID, and message text.
```

Do not move to the next feature until these criteria are verified.
