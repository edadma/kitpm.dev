---
title: Design Overview
description: Kit's architecture and the ideas behind it.
---

The full design document lives in [`design.md`](https://github.com/edadma/kitpm.dev/blob/dev/design.md) in the repository. This page summarizes the key architectural decisions.

## Architecture

Kit is a two-component system:

- **`kitd`** — a privileged daemon that listens on a Unix domain socket. It verifies manifests, fetches blobs, manages the store, writes generations, runs the activation engine, and invokes platform adapters.
- **`kit`** — an unprivileged CLI client that constructs requests, sends them to the daemon, and displays responses.

The daemon's scope is bounded. It applies effects from a closed schema; it does not run arbitrary package code. The two exceptions — config generators and first-run hooks — execute in tight sandboxes with declared inputs and outputs.

## Core model

1. **Store** — content-addressed, immutable, append-only. Every package version lives at a hash-derived path.
2. **Profiles** — named sequences of generations. System profile for system-wide packages, user profiles for personal ones.
3. **Generations** — immutable snapshots. Symlinks into the store plus activation metadata. Every operation produces a new one.
4. **Effects** — the closed schema of system integration. Groups, users, directories, capabilities, services, generators, first-run hooks.
5. **Activation engine** — diffs effects between generations, applies changes in dependency order, journals everything, unwinds on failure.
6. **Adapters** — out-of-process helpers for platform-specific operations.
7. **Resolution** — transitive closure over exact-pinned dependencies. No solver.

## What it is not

- **Not a build system.** Building is a separate concern for a separate tool.
- **Not Nix.** Same structural foundation, different complexity budget. No expression language, no arbitrary shell in activation, no fused build/install pipeline.
- **Not a replacement for apt/dnf/pacman.** On non-Slix platforms, Kit complements the system package manager for personal-prefix software.

## Design goals

- **Portable POSIX.** Core uses only POSIX APIs.
- **Actually useful.** Packages that need system integration work end-to-end.
- **Pedagogically legible.** A student can read `kitd`'s source and enumerate every operation it can perform.
- **Bounded and auditable.** The effect schema is closed and invertible.
- **Future-proof for builds.** Source-built and repo-fetched packages will be interchangeable.
