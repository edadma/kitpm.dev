---
title: Quick Start
description: Install Kit and your first package.
---

:::caution
Kit is under active development. These commands describe the intended interface; not all features are implemented yet.
:::

## Install Kit

On a personal prefix (macOS or Linux):

```sh
# Kit will live at ~/.kit
kitd --root=~/.kit &
```

On Slix (system-wide):

```sh
kitd --root=/
```

## Configure a repository

```sh
kit repo add https://repo.kitpm.dev/stable --key /path/to/trusted-key.pub
kit repo update
```

## Install a package

```sh
kit install hello
```

This resolves `hello` and its dependencies, fetches the blobs, extracts them into the store, builds a new generation with symlinks, and switches the `current` pointer atomically.

## Verify it works

```sh
kit list
# hello  1.0.0  sha256-abc123...

kit test hello
# hello:
#   PASS  prints-hello  12ms
#   1 passed, 0 failed, 0 skipped
```

## Roll back

```sh
kit rollback
```

The previous generation is restored. The hello binary is no longer on your PATH.

## Clean up

```sh
kit gc
# Removed 1 unreachable store entry
```

## Shell setup

Add to your shell profile:

```sh
export KIT_ROOT=~/.kit
export PATH="$KIT_ROOT/kit/profiles/users/$(whoami)/current/bin:$KIT_ROOT/kit/profiles/system/current/bin:$PATH"
```

User-installed packages shadow system packages by PATH order. Every binary brings its own correct library closure via store-path references.
