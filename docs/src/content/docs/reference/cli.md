---
title: CLI Reference
description: Complete reference for the kit command-line tool.
---

## Package operations

```sh
kit install <name>[@version] [--system]
kit remove <name> [--system]
kit upgrade [<name>] [--system]
kit rollback [--to <gen>] [--system]
kit reconfigure [--system]
```

### install

Resolves the package and its dependency closure, fetches missing blobs, extracts them into the store, constructs a new generation with symlinks, runs the activation engine, and atomically switches `current`.

- Without `--system`: installs to the user profile. The package must be user-scoped.
- With `--system`: installs to the system profile. Requires admin privileges.
- `@version`: pin to a specific version. Without it, uses the latest from configured repos.

### remove

Constructs a new generation without the package and runs activation. Dropped-effect inverses run: services stop, users are removed, capabilities are revoked. Directories and first-run state persist.

### upgrade

Re-resolves the package (or entire profile) against the latest repository indexes. Produces a new generation with updated versions.

### rollback

Restores a previous generation. Without `--to`, rolls back one generation. With `--to <gen>`, rolls back to a specific generation number. The activation engine computes and applies the effect diff.

### reconfigure

Re-runs generator effects whose inputs have changed. Edit `<root>/etc/kit/system.toml`, then run `kit reconfigure` to regenerate config files and restart affected services.

## Repository

```sh
kit update                        # fetch latest indexes from all repos
kit search <query>                # search across repos by name/description
```

### update

Fetches the latest signed index from every configured repository. The index is verified against trusted keys before use.

### search

Searches across all locally-cached indexes, filtered by the current platform's target triple. No network request — uses the index from the last `kit update`.

### Admin commands

These require authentication to the repository server (bearer token):

```sh
kit add <tarball>                 # upload package to repo server
kit sign                          # tell repo server to re-sign its index
kit verify                        # check repo integrity (all blobs match hashes)
```

## Package testing

```sh
kit test <name>                   # test a specific package
kit test --all                    # test every package in the repo
kit test --all --force            # ignore cached results
kit test --report                 # show last test results
kit test --report <name>          # show results for one package
```

## Services

```sh
kit service list
kit service status <name>
kit service start <name>
kit service stop <name>
kit service restart <name>
kit service enable <name>
kit service disable <name>
```

Thin wrappers over the [init adapter](/concepts/adapters/).

## Introspection

```sh
kit list [--system | --user <u>]     # installed packages
kit generations [--system | --user <u>]  # generation history
kit deps <name>                      # dependency tree
kit why <name>                       # reverse dependency (why is this installed?)
kit effects [<name>]                 # declared effects
kit caps list                        # file capabilities in effect
kit users list                       # kit-managed users
kit adapters list                    # installed adapters
kit features                         # available platform features
```

## Administration

```sh
kit gc [--dry-run]                   # garbage-collect unreachable store entries
kit pin <content-hash>               # prevent GC from collecting a store entry
kit unpin <content-hash>
kit purge <name>                     # remove package + all state (dirs, first-run)
kit adapter install <name>           # register an adapter
kit adapter remove <name>
```

## Daemon

The daemon (`kitd`) is started separately:

```sh
kitd --root=<path>
```

The root is required. The daemon listens on `<root>/kit/var/kitd.sock`. The `kit` CLI connects to this socket to send requests.
