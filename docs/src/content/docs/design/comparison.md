---
title: Comparison to Nix
description: How Kit relates to NixOS and where it differs.
---

Kit takes the structurally important ideas from Nix/NixOS and omits the complexity that makes them hard to learn and teach.

## What Kit keeps from Nix

- **Content-addressed immutable store.** Same idea, same benefits.
- **Generations with rollback.** First-class, atomic.
- **Closure-based GC.** Reachability from generation roots determines what's live.
- **Multi-user profiles.** Per-user and system profiles with composition.
- **Declarative system configuration.** `system.toml` is Kit's analog of `configuration.nix`.

## Where Kit differs

| Aspect | NixOS | Kit |
|---|---|---|
| **System config language** | Nix expression language | Plain TOML |
| **Where effects live** | Separate NixOS modules in nixpkgs | In the package manifest |
| **Activation** | Generated shell scripts, modules contribute snippets | Closed schema, journaled engine |
| **Package code during activation** | Full-authority shell | Sandboxed generators and first-run hooks only |
| **Rollback fidelity** | Best-effort (orphaned users linger) | Journaled, with defined inverses |
| **Portability** | Linux (+ nix-darwin, limited) | POSIX: Linux, macOS, BSD, Slix |
| **Platform differences** | Handled in Nix code | Handled by adapters |
| **Build system** | Fused with package manager | Separate tool (future) |

## The tradeoff

Kit is more constrained and more teachable. NixOS is more flexible and more battle-tested.

The constraints are deliberate. For a teaching OS and a portable package manager, the closed effect schema, sandboxed activation code, and journaled rollback demonstrate how a modern package manager could look if you were willing to give up some flexibility for legibility and rigor.

### What you lose

- **No arbitrary activation code.** You can't run `sed` on a config file during install. You write a generator instead.
- **No cross-package conditional behavior.** Packages can't inspect what else is installed. Coordination goes through the dependency graph.
- **No Nix language.** `system.toml` is plain TOML — powerful enough for key-value configuration, not powerful enough for abstraction. That's the point.

### What you gain

- **Deterministic rollback.** Every effect has a journal entry and a defined inverse. Rollback doesn't leave orphaned state.
- **Legibility.** A student can enumerate every operation Kit can perform on their system by reading the effect schema.
- **Portability.** The same packages work on Linux, macOS, BSD, and Slix without Nix-language conditionals.
- **Auditability.** `kit effects nginx` shows the complete list of system changes. No hidden shell snippets.
