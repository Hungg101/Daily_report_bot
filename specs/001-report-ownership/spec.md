# Feature Specification: Stable Employee Report Ownership

**Feature Branch**: `001-report-ownership` (artifact only; no branch created)

**Created**: 2026-07-01

**Status**: Draft - clarification required before planning or implementation

**Input**: Define stable employee report ownership semantics for M4 before reminder, dashboard, statistics, or manager APIs consume employee report status.

## User Scenarios & Testing

### User Story 1 - Preserve report ownership history (Priority: P1)

As an operator or manager, I need each submitted report to have predictable employee ownership so later Telegram remapping does not silently corrupt historical status and statistics.

**Why this priority**: Reminder and dashboard results cannot be trusted while historical report ownership can change implicitly.

**Independent Test**: Submit a report from a mapped Telegram user, change the mapping through an approved test fixture, then verify the report remains associated according to the selected ownership rule.

**Acceptance Scenarios**:

1. **Given** a Telegram user is mapped to an active employee, **When** the user submits one or more valid reports, **Then** every report has deterministic ownership under the approved rule.
2. **Given** a historical report exists, **When** the Telegram user is remapped later, **Then** the historical owner either remains unchanged or changes only because the approved dynamic-ownership rule explicitly requires it.
3. **Given** a manager queries an employee and date, **When** at least one valid owned report exists, **Then** the employee status is `SUBMITTED`.

---

### User Story 2 - Preserve unmapped report submission (Priority: P1)

As a Telegram user in the current demo flow, I can continue submitting reports without an employee mapping while the system handles those reports explicitly in employee status queries.

**Why this priority**: Current report submission does not require `/link`; changing that implicitly would regress the existing bot flow.

**Independent Test**: Submit a valid report from an unmapped Telegram user and verify the report is saved but is not incorrectly attributed to an employee.

**Acceptance Scenarios**:

1. **Given** an unmapped Telegram user has a pending `/report` session, **When** valid content is submitted, **Then** the report is saved without inventing employee ownership.
2. **Given** an unmapped report exists, **When** employee/day status is calculated, **Then** the report is excluded or backfilled only according to an approved documented rule.

---

### User Story 3 - Query status consistently by employee and date (Priority: P2)

As a service consumer, I need employee/day and department/day queries to apply the same ownership, active employee, report date, and multiple-submission rules.

**Why this priority**: Consistent read semantics are required before reminder or dashboard features are safe.

**Independent Test**: Create active and inactive employees, mapped and unmapped Telegram users, and multiple reports on different report dates; verify all service and repository results agree.

**Acceptance Scenarios**:

1. **Given** an active employee has multiple valid reports on the same report date, **When** status is calculated, **Then** the employee is counted once as `SUBMITTED` and all valid reports remain queryable.
2. **Given** an active employee has no owned report for the date, **When** status is calculated, **Then** the employee is `NOT_SUBMITTED`.
3. **Given** an employee is missing or inactive, **When** a single-employee status lookup runs, **Then** the current empty-result behavior is preserved unless a later approved specification changes it.

### Edge Cases

- A report was submitted before the Telegram user ran `/link`.
- A Telegram user is safely remapped from employee A to employee B.
- An employee or department becomes inactive after historical reports were submitted.
- Multiple Telegram reports exist for the same employee and report date.
- Server timezone differs from `APP_REPORT_TIME_ZONE` around midnight.
- Historical rows cannot be backfilled unambiguously.
- A schema migration is partially applied or must be rolled back.

## Requirements

### Functional Requirements

- **FR-001**: The system MUST define one official employee report ownership model: submission-time snapshot or dynamic Telegram mapping.
- **FR-002**: The system MUST continue allowing valid report submission without employee mapping unless a separate approved behavior change explicitly requires mapping.
- **FR-003**: The system MUST preserve the current multiple-reports-per-day rule; one or more valid reports count once as `SUBMITTED` for employee/day status.
- **FR-004**: Employee status queries MUST use `report_date` calculated with configured `APP_REPORT_TIME_ZONE` semantics.
- **FR-005**: Employee/day, employee-code/day, employee-list/day, and department/day queries MUST apply the same ownership rule.
- **FR-006**: Missing, blank, or inactive employee lookup behavior MUST remain explicit and test-covered.
- **FR-007**: A schema change MUST include migration, backfill, nullability, index, rollback, and compatibility decisions before implementation.
- **FR-008**: Reminder, dashboard, statistics, late/deadline status, and RBAC remain out of scope for this feature.
- **FR-009**: `/status`, `/last`, `/myreports`, `/reports`, `/whoami`, and `/link` MUST NOT create daily report rows.
- **FR-010**: Repository integration tests MUST validate the selected ownership query using actual JPA/database behavior rather than mocks alone.

### Decisions Requiring Clarification

- **DC-001**: [NEEDS CLARIFICATION] Should a report submitted before `/link` count for the employee after linking?
- **DC-002**: [NEEDS CLARIFICATION] Should future Telegram-to-employee remapping change historical report ownership?
- **DC-003**: [NEEDS CLARIFICATION] If snapshot ownership is selected, should existing reports be backfilled automatically, manually, or remain unowned when ambiguous?
- **DC-004**: [NEEDS CLARIFICATION] Is `daily_reports.employee_id` nullable permanently for unmapped submissions, or only during migration/demo operation?

### Key Entities

- **DailyReport**: Submitted report with Telegram user, report date, content, creation time, and potentially stable employee ownership.
- **TelegramUser**: Telegram identity with an optional one-to-one employee mapping.
- **Employee**: Active/inactive organizational identity used by report status, reminder, and future dashboard features.
- **Department**: Organizational grouping used for employee/day status aggregation.

## Success Criteria

### Measurable Outcomes

- **SC-001**: Every employee/day status test has deterministic results before and after Telegram mapping changes.
- **SC-002**: Repository integration tests cover mapped, unmapped, pre-link, remapped, inactive, multi-report, and timezone-boundary cases selected by the approved decisions.
- **SC-003**: Existing Telegram command and report submission tests continue to pass with no accidental report rows from read/link commands.
- **SC-004**: The full Maven test suite passes after implementation, with zero failures or errors.
- **SC-005**: `PROJECT_SPEC.md`, `ROADMAP_AND_PLAN.md`, schema documentation, and this feature artifact describe the same ownership rule.

## Assumptions

- The project remains in demo mode until schema migration governance is approved.
- Existing Telegram users and daily reports must remain readable during migration.
- Employee mapping remains optional for report submission in this feature.
- No real Telegram token/runtime test is required to decide ownership semantics; runtime smoke testing is reported separately if performed.
