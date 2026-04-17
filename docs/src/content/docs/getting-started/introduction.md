---
title: Introduction
description: What Kit is and why it exists.
---

Kit is a portable POSIX package manager. It is the native package manager for [Slix](https://github.com/edadma/slix) and runs on Linux, macOS, and BSD — anywhere a POSIX shell and a small set of core utilities are available.

## Key ideas

- **Content-addressed store.** Every package is stored by the hash of its contents. Two builds that produce the same output share the same store path, saving disk space and enabling trustless verification.

- **Generations with rollback.** Each install or removal creates a new generation. You can roll back to any previous generation instantly, making upgrades safe and reversible.

- **Closed effect schema.** Package definitions declare a fixed set of effects (files installed, services registered, environment variables set). Nothing outside the schema is permitted, so the system stays predictable.

- **Platform adapters.** Kit's core is platform-independent. Thin adapter layers handle platform-specific details (directory layout, init system integration, privilege escalation) so the same package definitions work across operating systems.

## Next steps

Check out the [Design Overview](/design/overview/) for a summary of Kit's architecture.
