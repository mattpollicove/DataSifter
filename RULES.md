w# DataSifter Engineering Rules

## 1. Scope and intent
This repository is a Java/Spring Boot + React control-plane project. The rules below are meant to keep implementation aligned with the spec while remaining portable across Linux, macOS, and Windows.

This file supersedes ad hoc assumptions and should be treated as the project-level operating guide for development.

## 2. Cross-platform compatibility requirements
- Development may occur on Windows, but all application code, scripts, configuration, and operational behavior must remain compatible with the latest stable versions of Linux (Debian, Fedora, Arch), macOS, and Windows.
- Do not hard-code Windows-only paths, drive letters, shell syntax, or file assumptions.
- Prefer Java `Path`/`Files` APIs, portable configuration, and OS-neutral behavior. Avoid string concatenation of filesystem paths when a standard API exists.
- If a platform-specific capability is truly required, encapsulate it behind a compatibility layer and define the behavior for each supported platform.
- The default runtime assumptions must work without local environment edits on Linux, macOS, or Windows.
- CI validation must be Linux-compatible, with equivalent functionality on macOS and Windows.

## 3. File and path handling
- Use relative paths and portable file names whenever practical.
- Prefer Java APIs over shell-specific path logic.
- Do not assume a PowerShell-only or bash-only environment unless the feature is deliberately platform-gated.
- Keep script behavior portable; when shell logic is required, use the smallest compatible abstraction and document platform differences clearly.
- Avoid file locking, directory assumptions, or naming patterns that are invalid or inconsistent across supported operating systems.

## 4. Project workflow and development discipline
- For non-trivial work, follow a plan-ask-execute pattern: plan the change, confirm the approach when needed, then implement it.
- Before running a build or any project command that could change runtime state, ask for confirmation unless the user has explicitly approved the execution step or the command is a minimal validation requested by the current task.
- Keep changes narrow and focused; do not touch unrelated code, public APIs, or formatting unless required.
- Prefer root-cause fixes over superficial patches.
- Preserve the repository's current formatting and service layering unless a broader design change is explicitly approved.
- For multi-step tasks, maintain a human-readable plan and update it as work progresses.
- Use only best practices for the current stack: clear layering, safe defaults, validation, minimal scope, maintainable naming, and portable logic.
- If a chosen approach would introduce avoidable risk, complexity, or platform-specific behavior, do not proceed without calling it out and proposing a better path.

## 5. Testing and validation
- Run the smallest relevant command that checks the changed behavior.
- Use Maven/Gradle tests, targeted backend validation, or focused local checks before concluding work is complete.
- When adding persistent behavior, validate the relevant code path and ensure the change does not require a Windows-only assumption.
- If a change impacts configuration or persistence, verify the behavior on the cross-platform default path, not only on a single workstation setup.
- Notify the user of any gaps, errors, or issues discovered during implementation, validation, or review before finalizing the work.

## 6. Security and data handling
- Treat secrets, credentials, connectors, keys, and audit-sensitive records as protected data.
- Never print secret or credential values to logs, console output, or test diagnostics.
- Any connector, workflow, key-vault, or file-agent work must validate input and prefer secure defaults.
- Secrets should be masked in responses when displayed to a UI or client.
- Persisted state should be durable and safe, even if it is still an MVP implementation.

## 7. Documentation, help, and migration tracking
- Update README and project documentation whenever the behavior, configuration, or operation model changes.
- Keep UI/help text in sync with functional changes.
- Create documentation as work progresses; do not wait until the end of a milestone to record the implementation.
- Update documentation with each build or significant implementation milestone so the project remains reviewable and reproducible.
- Maintain a running changelog for notable changes. If a project changelog does not already exist, create `CHANGELOG.md` and append the summary for the current work.
- If a change has platform-specific migration implications, document it in `Docs/APP_CHANGES_FOR_MIGRATION.md` with the date, files changed, platforms affected, old-to-new behavior snippets, and any remaining compatibility notes.
- Preserve project knowledge in reviewable docs so the team can understand why a change was made and how to reproduce it.
- When completing work, doing a version bump, or pushing to a code repository, summarize the changes in the changelog or the most relevant project markdown file.

## 8. Issue management and review discipline
- When errors or issues are discovered, document them in `pastissues.md` with date, description, root cause, solution, affected files, and prevention notes.
- Before making updates, check `pastissues.md` to avoid reintroducing known problems.
- Keep a prompt log for each session in `PROMPT_LOG.md` with timestamps when the instruction set or user request is reviewed.
- Review changes against the cross-platform requirement before completion; do not rely on hidden assumptions tied to a single developer machine.

## 9. Required execution rules
- Do not perform destructive changes without explicit confirmation.
- Keep API calls, logs, and diagnostics free of secrets.
- Prefer controlled, explicit validation rather than broad or unnecessary system-level dry runs.
- Use the available project tooling and the existing iteration flow for this workspace; do not invoke generic tools or commands that do not exist in the current environment.
- If a build is required, confirm before execution when the user has not already explicitly approved the build step.

## 10. Known incompatibilities and correction notes
The earlier draft of these rules contained a number of generic, tool-specific, and stack-specific instructions that do not fit this repository as written. These items should be treated as intent, not literal commands for DataSifter:

- `apply_patch` is not the active editing workflow in this environment; the project uses the available IDE/editor tools.
- `manage_todo_list` is not a present repository tool or standard workflow here; a human-readable plan can be kept in the session or code review notes instead.
- `QRunnable`, `asyncio.run`, `AsyncClient`, `api_calls.log`, `connection_errors.log`, and `credential_logger` are examples from another stack and are not applicable to the Java/Spring Boot + React codebase unless a future subsystem explicitly introduces that model.
- `local_validate_user`, UI-specific help functions such as `show_*_help`, and database-specific validation references are not part of this project unless a matching subsystem is implemented.
- The issue-tracking and migration-log requirements remain valid, but they must be adapted to this project structure and platform constraints rather than copied verbatim from a different application.

## 11. Current project-specific priorities
- Keep the control plane layered and portable: model, repository, service, controller, and DTO boundaries should stay consistent.
- Continue to prefer durable, safe state over mock-only behavior.
- Preserve the spec-aligned architecture while ensuring the implementation remains compatible across modern Linux, macOS, and Windows environments.
- Do not treat Windows development as a reason to accept Windows-only implementation details.
