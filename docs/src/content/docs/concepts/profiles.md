---
title: Profiles & Generations
description: How Kit tracks installed packages and supports rollback.
---

## Profiles

A profile is a named sequence of generations belonging to a principal — either the system or a user.

```
<root>/kit/profiles/
  system/           # packages available to all users
  users/
    alice/          # Alice's personal packages
    bob/            # Bob's personal packages
```

The **system profile** contains packages with system-wide effects: services, system users, shared directories. Only an administrator can modify it.

A **user profile** contains packages a user installed for themselves. User profiles may not contain packages with system-scoped effects.

## Generations

A generation is an immutable snapshot of a profile — a directory of symlinks into the store.

```
<root>/kit/profiles/system/
  generations/
    1/
    2/
    3/
      bin/nginx -> <root>/kit/store/sha256-abc-nginx-1.27.0/sbin/nginx
      lib/libssl.so -> <root>/kit/store/sha256-def-openssl-3.2.1/lib/libssl.so
      manifest.toml
  current -> generations/3
```

Every state-changing operation — install, remove, upgrade — produces a **new** generation. The `current` symlink is flipped atomically via `rename(2)`.

### What's in a generation

Each generation directory contains:

- **Symlinks** into the store for exposed binaries, libraries, and data files.
- **`manifest.toml`** — the closure of packages in this generation (what GC reads for reachability).
- **`effects.toml`** — the merged set of effects for this generation (what the activation engine diffs).
- **`activated-state.toml`** — concrete activation outcomes (assigned UIDs, generated-file paths).

## Rollback

Rolling back is:

1. Compute the effect diff between the current generation and the target.
2. Apply inverses in reverse dependency order (stop service, remove user, revoke capability).
3. Flip `current` to point at the target generation.

Old generations persist until garbage-collected. Rolling back to generation 5 from generation 10 doesn't rebuild anything — generation 5 is still on disk, and all its store paths are still in the store.

## Environment composition

What a user sees is composed from profiles at shell init:

```sh
PATH=<root>/kit/profiles/users/alice/current/bin:<root>/kit/profiles/system/current/bin:...
```

User-installed packages shadow system packages by PATH order. There's no on-disk merging — every binary's dependencies are pinned by absolute store paths, so whichever binary wins PATH brings its own correct library closure.
