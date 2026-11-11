# CLAUDE.md

@AGENTS.md

Everything in `AGENTS.md` applies. This file only adds what is specific to Claude Code.

## Session start

1. Read `ARCHITECTURE.md` and the last two Handoff log entries in `TASKS.md`.
2. Pick the first unblocked task in your lane (default lane: ENGINE). State the task ID before editing.
3. Use plan mode for any T1 task before writing code.

## Subagents and model choice

- Run T1 tasks on the main model. Hand T2 tasks to a Sonnet subagent and T3 tasks to a Haiku subagent, with the task ID, file list, and acceptance criteria copied from `TASKS.md`.
- Use the Explore agent for "where is X" questions instead of reading whole directories.
- Independent T2/T3 tasks in different files can run in parallel. Never run two agents on the same file.
- Review every subagent diff yourself before marking a task done.

## Budget

Follow AGENTS.md section 9. At about 75% of the session budget, stop taking T1 work, write the handoff entry, and either stop or switch to delegating remaining T2/T3 tasks to lower-tier subagents.

## Git

- No `Co-Authored-By` lines and no Claude attribution in commits or PRs.
- Do not push unless asked.

## Verification

- `:core` unit tests must pass before an ENGINE task is done.
- You cannot test camera behavior without a device. List every on-device check in the handoff entry under "Not verified" so the owner can run it.
