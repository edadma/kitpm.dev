---
title: Introduction
description: What Kit is and why it exists.
---

Kit is a portable POSIX package manager designed as the native package manager for Slix — a university-level teaching operating system — and usable on Linux, macOS, and the BSDs as a personal-prefix package manager.

## What it does

Kit installs software that actually works. Not just binaries in a directory, but software that needs users, services, capabilities, directories, and generated configuration — and has it all running end-to-end after `kit install`.

It achieves this without arbitrary code execution at install time. Instead, packages declare their system integration needs through a closed effect schema, and Kit's activation engine applies them deterministically.

## Key ideas

**Content-addressed store.** Every package version lives at a path derived from the hash of its contents. Multiple versions coexist without conflict. Binaries reference their dependencies by exact store paths, so there's never ambiguity about which library version gets loaded.

**Generations with rollback.** Every state-changing operation — install, remove, upgrade — produces a new generation. Old generations persist until garbage-collected. Rollback is an atomic pointer flip combined with a journaled activation diff that reverses any system changes.

**Closed effect schema.** The set of things a package can do to the system is a fixed, enumerable schema: create users, register services, set capabilities, create directories, generate config files. No package can declare an effect outside the schema. A student can enumerate every operation the system can perform on their behalf.

**Platform adapters.** Kit's core uses only POSIX APIs. Platform-specific behavior — init systems, user databases, capability models, sandboxing — is handled by small, separately-distributed adapter programs. The same package format works everywhere; adapters translate.

**Exact pinning.** Dependencies are pinned by exact version and exact content hash. No version ranges, no constraint solver. Resolution is a simple transitive closure walk.

## What it is not

- **Not a build system.** Kit fetches, verifies, and installs. Building is a separate tool (`kit-build`, planned).
- **Not a replacement for apt/dnf/pacman/Homebrew.** On non-Slix platforms, Kit manages software you install in a personal prefix, alongside your system package manager.
- **Not Nix.** Kit takes Nix's best structural ideas and leaves behind the expression language, the arbitrary shell in activation, and the fusion of packaging with building.

## Next steps

- [Quick Start](/getting-started/quick-start/) — install Kit and your first package
- [Concepts](/concepts/store/) — understand the store, profiles, and effects
- [Design Overview](/design/overview/) — the full architecture
