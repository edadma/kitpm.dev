# Kit — Package Manager Design

**Project:** Kit  
**CLI:** `kit`  
**Daemon:** `kitd`  
**Home:** [kitpm.dev](https://kitpm.dev)

A portable POSIX package manager. Designed as the native package manager for Slix — a university-level teaching multi-user operating system — and usable on Linux, macOS, and the BSDs as a personal-prefix package manager with real rollback, multi-version coexistence, and deterministic installs.

Kit takes the structurally important ideas from Nix/NixOS — content-addressed immutable store, generations, atomic switches, closure-based GC, multi-user profile composition, declarative system configuration applied by an activation engine — and omits the complexity that makes them hard to learn and teach: the Nix expression language, arbitrary shell in activation scripts, derivation graphs as a user-facing concept, and the fusion of package management with build-system responsibilities.

This document specifies a working runtime package manager: one that can install software that requires system integration (users, services, capabilities, directories, generated config) and have it actually run — across POSIX platforms, via a small adapter layer that abstracts platform-specific differences. Package building is out of scope for the initial implementation but is a planned future addition; the design is explicitly future-proofed for it. Sections marked **(Future)** describe how build functionality will integrate without structural changes.

---

## 1. Design Goals

- **Portable POSIX.** Runs on any POSIX-conformant system: Linux, macOS, BSD, Slix. The core daemon uses only POSIX APIs; platform-specific integrations (init systems, user databases, capability models, sandboxing primitives) are handled by small, swappable adapters.
- **Multi-user correct.** Per-user profiles, a system profile, privilege separation enforced by a small daemon.
- **Actually useful.** Packages that need users, services, capabilities, or generated configuration work end-to-end. Installing nginx results in a running web server, not a pile of unused binaries.
- **Pedagogically legible.** The on-disk layout, the CLI, the effect schema, and the source should be readable end-to-end by a student. Invariants are visible, not buried.
- **Serious, not simplistic.** Real garbage collection rooted in live references. Real atomic switches via POSIX filesystem primitives. Real content addressing. Real dependency closures. Real journaled activation with reversible effects.
- **Bounded and auditable.** The set of things a package can do to the system is a closed schema, not arbitrary shell code. A student can enumerate every operation the system can perform on their behalf.
- **Focused.** A runtime package manager plus an activation engine. Not a build system, not an image builder. Those are separate tools with separate concerns.
- **Future-proof for builds.** Source-built and repo-fetched artifacts will be fully interchangeable when build functionality is added.

---

## 2. Positioning

Kit plays two roles depending on context, from a single codebase:

- **On Slix:** the native system package manager. Slix's conventions are designed around it. It owns system integration — users, services, capabilities — in the primary way the OS does configuration.
- **On Linux / macOS / BSD:** a portable package manager for user-installed software in a personal prefix. It is **not** a replacement for the host's system package manager (apt, dnf, pacman, Homebrew). It provides real rollback, hermetic installs, and multi-version coexistence for software a user chooses to install outside the system-managed set. Common uses: language toolchains, development tools, self-contained application stacks.

The two roles share code. The differences are handled by configuration (the root path, which adapters are installed, which effects are available) rather than by code forks. A student who learns Kit on Slix can continue using it on their Mac or Linux machine afterward; the tool and the knowledge transfer.

**Scope discipline:** on non-Slix platforms, Kit does not try to replace system-level capabilities the host already provides well. "Install nginx as a system service on Ubuntu via Kit" is outside the scope; "install three versions of Python side by side and roll back cleanly" is inside it. Keeping this boundary sharp protects the design from ecosystem-specific sprawl.

---

## 3. Load-Bearing Invariants

Every design decision below is in service of these seven invariants. Features that would erode them are rejected.

1. **Immutability.** Store paths are content-addressed and never mutated after creation.
2. **Exact pinning.** Dependency references name exact versions and exact content hashes. No version ranges, no constraint solving, ever.
3. **Separation of concerns.** The package manager fetches, verifies, registers, composes, and activates. It does not build. Outside of a narrow, sandboxed activation surface, it does not execute package code.
4. **Profile composition.** Each principal (user, system) has its own profile with its own generation history. Environments are composed from profiles, not conflicts resolved between them.
5. **Identity is content.** A store path is identified by the content hash of its bytes. The origin of those bytes — fetched, built, copied — is metadata, not identity.
6. **Bounded effects.** Every system state change a package can request is drawn from a fixed, enumerable schema of effect types, each with a defined inverse. No package can declare an effect outside the schema. No package-provided code runs during activation except through narrowly-sandboxed generators and first-run hooks with declared inputs and outputs.
7. **POSIX portability.** The daemon core uses only POSIX APIs. Platform-specific behavior is isolated behind adapter interfaces. The same source compiles and runs identically on every supported platform; differences in capability are expressed as the presence or absence of adapters.

---

## 4. Core Concepts

### 4.1 Root

Every Kit installation is rooted at a configured **root directory**. The root is a required parameter; the daemon refuses to run without one.

```
kitd --root=/              # Slix or Linux system-wide install
kitd --root=/usr/local/kit # Linux or macOS local install
kitd --root=~/.kit         # per-user install
kitd --root=/tmp/test-abc  # test environment
```

All paths in the design below are relative to this root. The root serves three distinct purposes at once:

- **Portability.** Different platforms use different conventions for where software lives.
- **Safety.** A test root cannot affect anything outside it.
- **Multi-installation.** A user can have a system-wide Kit and a personal Kit coexisting.

### 4.2 Store

```
<root>/kit/store/<content-hash>-<name>-<version>/
```

The store is a flat directory of every package version ever installed under this root, keyed by content hash. It is append-only (except during garbage collection), globally readable, and writable only by the `kitd` daemon.

Multiple versions coexist without conflict. Binaries reference their dependencies directly by absolute store path via RPATH (Linux, BSDs) or equivalent (`@rpath` / install_name on macOS, direct paths on Slix), so runtime resolution is unambiguous and independent of which profile is active.

The `<name>-<version>` suffix is human-affordance only. The content hash is the sole identity. No code parses name or version out of a store path.

### 4.3 Profile

A profile is a named sequence of generations belonging to a principal.

```
<root>/kit/profiles/system/
<root>/kit/profiles/users/alice/
<root>/kit/profiles/users/bob/
```

The **system profile** contains packages available to all users and packages that integrate with the system (services, system users, shared directories). Only an administrator can modify it. On personal-prefix installations (single user on macOS or Linux), the user owning the prefix is the administrator.

A **user profile** contains packages a user has installed for themselves. User profiles may not contain packages with system-scoped effects (services, system users, capabilities on binaries). A user package can declare user-scoped effects — user services, per-user generated config — that apply within the user's own context.

Profiles compose at environment-construction time; they do not merge on disk. See §4.5.

### 4.4 Generation

A generation is an immutable snapshot of a profile — a directory of symlinks into the store, plus the activation state associated with that profile at that point in time.

```
<root>/kit/profiles/system/
  generations/
    1/
    2/
    3/
      bin/nginx -> <root>/kit/store/abc-nginx-1.27.0/sbin/nginx
      lib/libssl.so -> <root>/kit/store/def-openssl-3.2.1/lib/libssl.so
      manifest.toml                # closure of packages in this generation
      effects.toml                 # union of effects declared by the closure
      activated-state.toml         # snapshot of activation state
  current -> generations/3
```

Every state-changing operation produces a new generation. Old generations persist until garbage-collected. Rollback is a pointer flip combined with an activation diff.

Each generation carries three files the daemon consults:

- `manifest.toml` — the closure of packages. What GC reads to determine reachability.
- `effects.toml` — the merged, validated set of effects for this generation. What the activation engine diffs against adjacent generations.
- `activated-state.toml` — the concrete outcomes of activation (assigned UIDs, generated-file paths, first-run completion flags, adapter outputs). Present only on generations that have been activated.

### 4.5 Environment Composition

What a user sees is composed from profiles at shell-init:

```
PATH=<root>/kit/profiles/users/alice/current/bin:<root>/kit/profiles/system/current/bin:...
```

User-installed packages shadow system packages by PATH order. There is no on-disk merging and no conflict to resolve: every binary's dependencies are pinned by absolute path into the store, so whichever binary wins PATH brings its own correct library closure with it.

---

## 5. Privilege Model

Relative to the root:

| Path | Owner | Writable by |
| --- | --- | --- |
| `<root>/kit/store/` | admin | `kitd` only |
| `<root>/kit/profiles/system/` | admin | `kitd`, on behalf of admin |
| `<root>/kit/profiles/users/<u>/` | user `<u>` | `kitd`, on behalf of user `<u>` |
| `<root>/kit/var/` | admin | `kitd` only |
| `<root>/etc/kit/` | admin | admin |

"Admin" means root on a system-wide install; the owning user on a personal-prefix install. The daemon detects which mode it's in from the ownership of the root and adjusts expectations accordingly.

Kit is a two-component system:

- **`kitd`** — a privileged daemon. Listens on a Unix domain socket. Accepts typed requests. Verifies manifests and signatures. Fetches blobs. Hashes and extracts into the store. Writes new generations. Runs the activation engine. Invokes platform adapters. Updates `current` symlinks atomically.
- **`kit`** — an unprivileged CLI client. Constructs requests, sends them to the daemon, displays responses.

The daemon's scope is larger than in a pure fetch-only design because it runs activation, but it is still bounded. It applies effects from a closed schema; it does not run arbitrary package code. The two exceptions — config generators and first-run hooks — execute in tight sandboxes with declared inputs and outputs (§7.6, §7.7).

A student should be able to read the daemon's source and enumerate every operation it can perform on their system. That is the bar.

---

## 6. Package Format

A package is a compressed tarball. Alongside it, in the repository, lives a manifest.

### 6.1 Manifest

```toml
name = "nginx"
version = "1.27.0"
target = "x86_64-linux-gnu"
content-hash = "sha256-1a2b3c..."
scope = "system"

requires-features = ["capabilities", "service-registration"]

[[deps]]
name = "libc"
version = "0.3.1"
content-hash = "sha256-..."

[[deps]]
name = "openssl"
version = "3.2.1"
content-hash = "sha256-..."

# --- Effects: the declared system integration for this package ---

[[effects.group]]
name = "nginx"
system = true

[[effects.user]]
name = "nginx"
group = "nginx"
home = "/var/lib/nginx"
shell = "/sbin/nologin"
system = true

[[effects.directory]]
path = "/var/lib/nginx"
owner = "nginx"
group = "nginx"
mode = "0750"

[[effects.directory]]
path = "/var/log/nginx"
owner = "nginx"
group = "nginx"
mode = "0755"

[[effects.capability]]
binary = "sbin/nginx"
caps = ["cap_net_bind_service+ep"]

[[effects.service]]
name = "nginx"
binary = "sbin/nginx"
args = ["-g", "daemon off;"]
user = "nginx"
group = "nginx"
requires = ["network"]
restart = "on-failure"

[[effects.generate]]
generator = "bin/nginx-genconfig"
output = "/etc/nginx/nginx.conf"
inputs = ["/etc/kit/system.toml#nginx"]
```

Fields:

- `name`, `version` — human-facing identity. Not used for correctness.
- `target` — platform triple. Mismatched targets are refused.
- `content-hash` — the sole identity. Computed over the canonicalized tarball (§6.3).
- `scope` — `system` or `user`. Constrains which profile types may install the package.
- `requires-features` — platform features the package needs. If the host lacks an adapter for a required feature, installation fails with a clear diagnostic. If the feature is optional (the package can function without it, just less securely), the package omits it from this list and gracefully degrades.
- `deps` — exact pins. Resolution is transitive closure.
- `effects.*` — declared system integration. The schema is closed; see §7.

Effects paths like `/var/lib/nginx` are relative to the root at application time: on a `--root=/` install the path is literal; on a `--root=/usr/local/kit` install it becomes `/usr/local/kit/var/lib/nginx`. The package doesn't know which; the daemon handles the substitution.

**Future-proofing:** a future `input-hash` field will identify the build plan that produced a source-built package. Today, `content-hash` is the only hash; naming it explicitly leaves namespace for `input-hash` later without migration.

### 6.2 Tarball Layout

```
<package>.tar.zst
├── bin/
├── sbin/
├── lib/
├── share/
├── manifest.toml          # identical to the repo manifest
└── .kit-provenance        # origin metadata; excluded from content hash
```

The manifest is duplicated inside the tarball so a store entry is self-describing. The daemon cross-checks the two copies; they must match.

Binaries have dependency references pointing into the store at exact paths via the platform's mechanism: RPATH on Linux and BSDs, `@rpath` with absolute install names on macOS, direct paths on Slix. This is enforced by whatever produced the package; the daemon does not rewrite references.

### 6.3 Content Hash Canonicalization

The content hash is computed over the tarball bytes after canonicalization:

- Deterministic entry ordering (lexicographic by path).
- Fixed mtime (0 or a documented epoch).
- Fixed uid/gid (0/0).
- Fixed permissions (clear setuid/setgid/sticky; normalize to 0755/0644).
- Exclude `.kit-provenance` from the hash computation.

Excluding provenance from the hash is the mechanical guarantee of invariant 5: two artifacts with identical contents produce identical hashes regardless of origin.

Because `manifest.toml` is inside the tarball and contributes to the content hash, changes to effects, dependencies, or metadata produce a new content hash. An effect change is a new package, not a silent mutation.

### 6.4 Provenance

```toml
origin = "fetch"
repo = "https://repo.kitpm.dev/stable"
fetched-at = "2026-04-17T14:23:00Z"
signature = "ed25519:..."
```

**(Future)** for locally-built packages:

```toml
origin = "build"
input-hash = "sha256-..."
built-at = "2026-05-02T09:11:00Z"
builder = "kit-build 0.2"
```

Provenance is metadata. It does not affect identity, hash, GC, activation, or resolution.

---

## 7. Effects Schema

The effect schema is closed. A package may declare effects only of the types listed here; the daemon rejects unknown effect types. Every effect type has a defined application procedure and a defined inverse.

Some effect types require platform adapters (§8). When an adapter is unavailable, the daemon's behavior depends on whether the package declared the corresponding feature in `requires-features`: required features cause install to fail cleanly; optional ones are skipped with a warning.

The activation engine reads the union of effects from all packages in a profile's closure, diffs that against the previously-activated generation's effect set, and applies the delta.

### 7.1 group

```toml
[[effects.group]]
name = "nginx"
system = true
```

**Apply:** Via the **user-database adapter**. Allocates a GID from the system range if not already allocated, records in `activated-state.toml` and in the adapter's native format (Linux: append to `/etc/group`; macOS: `dscl` call; Slix: native user database).

**Inverse:** Remove the group entry via the same adapter.

**Constraints:** User-scoped packages may not declare groups. Requires the `user-database` feature.

### 7.2 user

```toml
[[effects.user]]
name = "nginx"
group = "nginx"
home = "/var/lib/nginx"
shell = "/sbin/nologin"
system = true
```

**Apply:** Via the user-database adapter. Allocate a UID from the system range, create the user record. The assigned UID is recorded in `activated-state.toml` so it persists across rollbacks.

**Inverse:** Remove the user record via the adapter.

**Depends on:** the named group existing first. Requires the `user-database` feature.

### 7.3 directory

```toml
[[effects.directory]]
path = "/var/lib/nginx"
owner = "nginx"
group = "nginx"
mode = "0750"
```

**Apply:** Purely POSIX — `mkdir`, `chown`, `chmod`. No adapter required.

**Inverse:** Nothing. Directories declared by effects may contain user data (databases, logs, state); the package manager does not delete them on deactivation. Explicit removal is handled by `kit purge`.

**Depends on:** referenced user and group existing first.

### 7.4 capability

```toml
[[effects.capability]]
binary = "sbin/nginx"
caps = ["cap_net_bind_service+ep"]
```

**Apply:** Via the **capabilities adapter**. Linux: record grant in the system capability manifest and apply via `setcap` at profile-switch or via kernel-consulted profile state. Other platforms: no portable equivalent; adapter either refuses or maps to an approximate platform mechanism.

**Inverse:** Remove the capability grant.

**Fallback:** Packages that need elevated privileges on platforms without a capabilities adapter can declare a `suid` effect instead (a separate effect type, omitted here for brevity, whose use requires admin review). Capabilities are preferred where available; SUID is the portable fallback. A package manifest may declare both, guarded by `requires-features`.

**Rationale:** A student can `kit caps list` on a system with a capabilities adapter and see exactly what's granted. On a system without one, the package either falls back cleanly or fails with a clear message.

### 7.5 service

```toml
[[effects.service]]
name = "nginx"
binary = "sbin/nginx"
args = ["-g", "daemon off;"]
user = "nginx"
group = "nginx"
requires = ["network"]
restart = "on-failure"
environment = { NGINX_CONF = "/etc/nginx/nginx.conf" }
```

**Apply:** `kitd` writes a portable service description to `<root>/kit/var/services/<name>.toml`. The **init adapter** for the current platform reads these descriptions and registers them with the host init system (systemd, launchd, rc.d, s6, Slix's native service model). If no init adapter is installed, service descriptions are written but not registered; a warning is emitted.

**Inverse:** Remove the service description; the init adapter unregisters.

**Depends on:** declared user, group, and any generated config files.

**Rationale:** `kitd` is init-agnostic. It produces a declarative description; platform adapters translate it. The schema here is deliberately minimal — a service is a binary plus args plus identity plus a handful of policy fields. Complex service orchestration is out of scope.

### 7.6 generate

```toml
[[effects.generate]]
generator = "bin/nginx-genconfig"
output = "/etc/nginx/nginx.conf"
inputs = ["/etc/kit/system.toml#nginx"]
```

**Apply:** Run the generator in a sandbox. The **portable minimum sandbox** is POSIX-only:

- Run as an unprivileged `kit-generator` user.
- `chroot` (or equivalent directory confinement) to a scratch directory populated only with the declared inputs.
- Output path inside the scratch directory, moved atomically into the declared output location on success.
- `ulimit`-enforced resource limits.

The **sandbox adapter**, if present, strengthens this with platform-specific primitives: Linux namespaces and seccomp, macOS `sandbox-exec`, BSD jails/capsicum, Slix's native sandboxing. The package author targets the portable minimum; the adapter transparently provides stronger isolation where available.

**Inverse:** Remove the generated file from the output path (only if still owned by a dropped generation).

**Re-run:** `kit reconfigure` re-runs all generators whose inputs have changed.

**Generator chaining:** A generator's inputs may include another generator's declared output. For example, openssl's generator produces `/etc/ssl/certs/ca-certificates.crt`; nginx's generator declares that path as an input. The activation engine orders generators by the package dependency graph — since nginx depends on openssl, openssl's generator runs first. No additional coordination mechanism is needed; the dependency graph and declared inputs/outputs are sufficient.

**Rationale:** This is the escape valve for config templating, host-key generation, CA bundle compilation. It is the primary place package-provided code runs during activation, and the sandbox is tight by design: declared inputs, declared output, no ambient authority.

### 7.7 first-run

```toml
[effects.first-run]
binary = "bin/postgres-initdb"
```

**Apply:** On the first activation of this package on this system, run the binary once, as the package's declared service user, with access to the package's declared directories only. Sandbox follows the same portable-minimum/adapter pattern as generators, with writes scoped to the package's state directories. Set a completion flag in `activated-state.toml`.

**Inverse:** None. First-run initialization creates persistent state (database clusters, TLS keys) that is *not* the package manager's to delete. `kit purge` handles intentional removal.

**Subsequent activations:** The hook does not run.

### 7.8 Platform-Scoped Effects

Some effects only make sense on some platforms. They remain part of the closed schema but are guarded by `requires-features`:

| Effect | Required feature | Platforms |
| --- | --- | --- |
| `group`, `user` | `user-database` | All (with adapter) |
| `directory` | (none — POSIX) | All |
| `capability` | `capabilities` | Linux, Slix |
| `service` | `service-registration` | All (with init adapter) |
| `generate`, `first-run` | (none — portable sandbox) | All; stronger sandbox where available |

A package may declare a feature as required (install fails if unavailable) or tolerate its absence and gracefully degrade. Most packages needing services mark `service-registration` as required; a dev tool that optionally registers an auto-updater might mark it optional.

### 7.9 What the Schema Does Not Include

Deliberately omitted:

- **Arbitrary shell snippets.** There is no "run this script on activation" effect.
- **Cross-package coordination.** A package cannot declare "if package X is installed, do Y." Coordination is expressed through the dependency graph.
- **Modifying files outside declared outputs.** Generators write to their declared output paths; nothing else.
- **Interactive prompts.** Configuration is declarative; `<root>/etc/kit/system.toml` is the input.

These are real capability losses vs. `.deb` and occasionally vs. NixOS. They are the price of the closed schema, and the closed schema is the price of legibility and deterministic rollback.

---

## 8. Platform Adapters

Adapters are the portability mechanism. Each adapter is a small, separately-distributed component that implements a well-defined interface between `kitd` and some platform facility.

### 8.1 Adapter Interface

Adapters are out-of-process helpers invoked by `kitd` over a simple stdin/stdout protocol (JSON or TOML requests, structured responses). This isolates adapter failures, lets adapters run with narrowly-scoped privileges, and makes them individually testable and swappable.

```
kitd  ---request--->  kit-adapter-<facility>-<platform>
     <--response---
```

### 8.2 Standard Adapter Types

- **`user-database`** — creates and removes users and groups.
  - `kit-adapter-userdb-passwd` — edits `/etc/passwd` directly (universal POSIX baseline).
  - `kit-adapter-userdb-useradd` — wraps `useradd`/`groupadd` (Linux).
  - `kit-adapter-userdb-dscl` — wraps `dscl` (macOS).
  - `kit-adapter-userdb-pw` — wraps `pw` (FreeBSD).
  - `kit-adapter-userdb-slix` — native (Slix).

- **`init`** — registers and controls services.
  - `kit-adapter-init-systemd` (Linux).
  - `kit-adapter-init-openrc`, `kit-adapter-init-s6`, `kit-adapter-init-runit` (Linux/BSD alternatives).
  - `kit-adapter-init-launchd` (macOS).
  - `kit-adapter-init-rcd` (BSDs).
  - `kit-adapter-init-slix` (Slix).

- **`capabilities`** — applies and revokes file capabilities.
  - `kit-adapter-caps-linux` (Linux).
  - `kit-adapter-caps-slix` (Slix).
  - Absent on macOS and BSDs; packages fall back to SUID or declare the feature required and refuse to install.

- **`sandbox`** — strengthens the portable-minimum generator/first-run sandbox.
  - `kit-adapter-sandbox-linux` (namespaces + seccomp).
  - `kit-adapter-sandbox-macos` (sandbox-exec).
  - `kit-adapter-sandbox-freebsd` (capsicum + jails).
  - `kit-adapter-sandbox-slix` (native).
  - Absent → portable minimum (chroot + unprivileged user + rlimits) is used.

### 8.3 Adapter Discovery and Configuration

```
<root>/etc/kit/adapters.toml
```

```toml
[user-database]
adapter = "useradd"

[init]
adapter = "systemd"

[capabilities]
adapter = "linux"

[sandbox]
adapter = "linux"
```

An admin configures which adapters are installed. Absent entries mean the feature is unavailable; packages requiring it will fail to install with a clear diagnostic.

### 8.4 Why Adapters, Not Built-In

Three reasons:

- **Privilege minimization.** An adapter invoked for user creation can be installed setuid or granted narrow capabilities; the daemon doesn't need those privileges except transiently via the adapter.
- **Testability.** Adapter protocols are simple and mockable. Integration tests can use stub adapters that record calls, without touching the real system.
- **Pluggability.** A user running Kit on macOS who doesn't want it managing users can simply not install the user-database adapter. Packages that require one fail cleanly; packages that don't proceed normally.

---

## 9. Activation Engine

`kitd` runs the activation engine whenever a profile's generation changes. It is the bridge between "the package closure changed" and "the system reflects that."

### 9.1 Algorithm

For a transition from generation *N* to generation *N+1* on a profile *P*:

1. Compute the effect set of *N* from its `effects.toml` (empty if no previous generation).
2. Compute the effect set of *N+1* by merging effects declared by the closure of *N+1*.
3. Validate: no conflicting effects (two users with the same name but different groups, two generators writing to the same output, etc.). Check required features against installed adapters.
4. Diff: compute the ordered lists of effects to add, remove, and update.
5. Order by dependencies. Two levels of ordering apply: first, effect types are ordered (groups before users, users before directories, generators before services). Second, within a type, effects are ordered by the package dependency graph — if package A depends on package B, B's effects of a given type run before A's. This ensures generator chaining works: if A's generator declares B's generator output as an input, B's generator runs first.
6. For each effect in plan order:
   a. Apply, invoking the appropriate adapter where required.
   b. Write a journal entry recording the effect, its inverse, and any assigned state.
7. If any step fails, replay the journal in reverse to unwind, then abort the transition.
8. If all succeed, write `activated-state.toml` for generation *N+1*, flip `current` atomically, close the journal.

### 9.2 Journal

```
<root>/kit/var/activation-journal/<timestamp>-<profile>-<from>-to-<to>.log
```

Each journal is append-only, `fsync`'d per entry. On `kitd` startup, any open journal indicates a crash mid-activation; the daemon replays the journal in reverse to restore the previous `current`, then removes the journal.

Activation is atomic from the user's perspective: either the new generation is live and fully activated, or the old one is, never an inconsistent mix.

### 9.3 Rollback

Rollback runs the same engine with source and target swapped. The effect diff between current generation and target is computed, inverses are applied in reverse dependency order, `current` is flipped.

Rollback is not destructive to stateful data: the `directory` effect has no inverse; `first-run` has no inverse. User databases and generated persistent state survive rollback.

### 9.4 Reconfiguration

`kit reconfigure` re-runs generator effects whose declared inputs have changed since the last activation. The daemon tracks input file hashes in `activated-state.toml`; on `reconfigure`, it recomputes and re-runs only the generators whose input hashes differ. Output files are replaced atomically.

### 9.5 What Activation Does Not Do

- Run arbitrary code from packages.
- Consult the network.
- Modify the store.
- Modify paths outside the root's activation footprint or declared effect outputs.
- Interact with users (no prompts, no waiting).

Given the same inputs, it produces the same outcome.

---

## 10. Repositories

A repository is a signed, versioned, HTTP-accessible collection of manifests and blobs.

```
<repo-url>/
  index.toml                      # signed
  manifests/<content-hash>.toml
  blobs/<content-hash>.tar.zst
  recipes/<input-hash>.toml       # (Future)
```

### 10.1 Index

```toml
repo-name = "kit-stable"
revision = "2026-04-15"
signed-by = "ed25519:..."

[[packages]]
name = "nginx"
version = "1.27.0"
content-hash = "sha256-..."
target = "x86_64-linux-gnu"
scope = "system"
```

The index is signed. Clients verify against trusted keys before trusting any content hash.

### 10.2 Trust Configuration

```
<root>/etc/kit/repos.toml
```

```toml
[[repo]]
url = "https://repo.kitpm.dev/stable"
trusted-keys = ["ed25519:..."]
priority = 10
targets = ["x86_64-linux-gnu", "aarch64-linux-gnu"]

[[repo]]
url = "https://repo.kitpm.dev/macos"
trusted-keys = ["ed25519:..."]
priority = 10
targets = ["aarch64-apple-darwin"]
```

Higher priority wins on name conflicts. `targets` narrows which platform triples this repo serves, avoiding unnecessary index fetches.

### 10.3 Multiple Repositories

Lookup spans all configured repositories in priority order. Name conflicts resolve by priority. Different closures can pull different versions; both coexist in the store.

---

## 11. Resolution

Transitive closure over exact-pinned dependencies. No solver.

```
resolve(pkg):
    closure = {}
    queue = [pkg]
    while queue not empty:
        p = queue.pop()
        if p.content-hash in closure: continue
        verify p.target matches this platform
        verify p.scope is allowed in target profile
        verify p.requires-features are all satisfied by installed adapters
        closure[p.content-hash] = p
        for dep in p.deps:
            queue.push(lookup(dep.name, dep.version, dep.content-hash))
    return closure
```

Mismatches in target, scope, or features are hard errors with specific diagnostics.

---

## 12. System Configuration

System-wide configuration that feeds generator effects lives in:

```
<root>/etc/kit/system.toml
```

```toml
[nginx]
worker_processes = 4
listen_port = 443
server_name = "kit.example.edu"

[postgres]
max_connections = 100
shared_buffers = "256MB"

[networking]
hostname = "kit-lab-03"
```

Generators declare which sections they read as inputs. Editing this file and running `kit reconfigure` re-runs affected generators and restarts affected services.

This is Kit's analog of NixOS's `configuration.nix`, with a crucial simplification: it's plain TOML, not a programming language. The cost is that cross-package coordination has to be explicit; the benefit is that a student can read and edit it without learning a language first.

---

## 13. Operations

### 13.1 Install

```
kit install nginx                 # user profile if user-scoped; error otherwise
kit install --system nginx        # system profile; requires admin
kit install nginx@1.27.0
```

Flow:

1. Client sends request.
2. Daemon resolves closure, validates scope, target, and required features.
3. Fetches and verifies missing blobs, extracts into store.
4. Constructs new generation (symlink tree + effect set).
5. Runs activation engine with journal, invoking adapters as needed.
6. Flips `current`.

### 13.2 Remove

```
kit remove nginx [--system]
```

Constructs a new generation without the package and runs activation. Dropped-effect inverses run: stop service, unregister, remove user and group, revoke capabilities. Directories and first-run state persist.

### 13.3 Upgrade

```
kit upgrade                       # re-resolve entire profile against latest indexes
kit upgrade nginx
```

### 13.4 Rollback

```
kit rollback [--system]
kit rollback --to 7 [--system]
```

### 13.5 Reconfiguration

```
kit reconfigure [--system]
```

### 13.6 Service Control

```
kit service list
kit service status nginx
kit service start nginx
kit service stop nginx
kit service restart nginx
kit service enable nginx
kit service disable nginx
```

Wrappers over the init adapter.

### 13.7 Introspection

```
kit list
kit generations
kit deps <name>
kit why <name>
kit effects [<name>]
kit caps list
kit users list
kit adapters list                 # which adapters are installed
kit features                      # which features this installation supports
kit verify
```

### 13.8 Administration

```
kit gc [--dry-run]
kit pin <content-hash>
kit unpin <content-hash>
kit purge <name>
kit repo add <url> --key <keyfile>
kit repo remove <url>
kit repo update
kit adapter install <name>        # register an adapter in adapters.toml
kit adapter remove <name>
```

### 13.9 Future

```
kit build <recipe>
```

---

## 14. Garbage Collection

Roots:

- `current` generation of the system profile.
- `current` generation of every user profile.
- Last *N* generations of each profile (configurable; default 10).
- Any store path pinned via `kit pin`.

Reachability: transitive closure of store paths referenced by any root generation's manifest.

Unreachable store paths are deleted. The deletion does not invoke any package code.

GC does not touch `<root>/kit/var/` state databases, `<root>/etc/kit/`, or any paths written by effects (directories, generated files). Those are managed by activation's inverses and by `kit purge`.

---

## 15. Future-Proofing for Package Building

### 15.1 Interchangeability Guarantee

Source-built and repo-fetched packages are fully interchangeable. A store path produced by a future `kit-build` is a store path produced by fetching from a repo, if their content hashes match.

### 15.2 Input Hash vs. Content Hash

A future build recipe will carry an **input hash** (over the canonicalized recipe plus dependency input hashes) identifying the build plan. The executed build produces bytes with a **content hash** identifying the bytes. The package manager uses `content-hash` for identity, always.

### 15.3 Repository Support for Recipes

Source-capable repositories publish `recipes/<input-hash>.toml`. Index entries gain an optional `input-hash` field.

### 15.4 The Build Tool's Scope

`kit-build` will be a separate POSIX executable that:

1. Parses recipes.
2. Computes input hashes.
3. Resolves build-dependency closures.
4. Executes builds in a sandbox (using the same sandbox adapter infrastructure).
5. Canonicalizes output into a tarball.
6. Computes the content hash.
7. Hands the blob to `kitd` for registration.

The daemon does not know or care that the bytes came from a local build.

### 15.5 What Will Not Change When Builds Arrive

Store layout, manifest format (additive only), repository layout (additive only), resolution algorithm, profile and generation model, effects schema and activation engine, privilege model, adapter interface, GC.

### 15.6 Preserved Exclusions

- **Install-time code execution outside the effect schema.**
- **Runtime repository rewriting.**
- **Fused manifest/recipe format.**

---

## 16. CLI Reference

```
# Package operations
kit install <name>[@version] [--system]
kit remove <name> [--system]
kit upgrade [<name>] [--system]
kit rollback [--to <gen>] [--system]
kit reconfigure [--system]

# Services
kit service { list | status | start | stop | restart | enable | disable } [<name>]

# Introspection
kit list [--system | --user <u>]
kit generations [--system | --user <u>]
kit deps <name>
kit why <name>
kit effects [<name>]
kit caps list
kit users list
kit adapters list
kit features
kit verify

# Administration
kit gc [--dry-run]
kit pin <content-hash>
kit unpin <content-hash>
kit purge <name>
kit repo { add | remove | update } [...]
kit adapter { install | remove } <name>

# Future
kit build <recipe>
```

---

## 17. On-Disk Layout Summary

```
<root>/
  kit/
    store/
      <content-hash>-<name>-<version>/
        bin/, sbin/, lib/, share/
        manifest.toml
        .kit-provenance

    profiles/
      system/
        generations/1/, 2/, ... N/
          bin/, sbin/, lib/, share/         # symlinks into store
          manifest.toml
          effects.toml
          activated-state.toml
        current -> generations/N

      users/
        alice/
          generations/1/, 2/, ...
          current -> generations/N

    var/
      cache/                                # blobs pending verification
      kitd.sock                             # daemon socket
      activation-journal/                   # mid-activation journals
      services/                             # portable service descriptions
      state/
        users.db                            # kit-assigned UIDs/GIDs
        caps.db                             # capability grants
        generated-files.db                  # generator output ownership
        first-run.db                        # completion flags
      log/

    bin/kit                                 # unprivileged client
    sbin/kitd                               # privileged daemon

  etc/kit/
    repos.toml
    trusted-keys/
    system.toml                             # system configuration
    adapters.toml                           # adapter configuration
```

On a `--root=/` install, these appear at the top level. On a personal-prefix install, they appear under the prefix. The structure is identical.

---

## 18. Testing Strategy

Kit is tested in four tiers, each with a distinct cost, fidelity, and role in the development loop. The tiers are not alternatives; they compose. Most code is exercised by multiple tiers, with the cheaper tiers catching bugs the expensive tiers shouldn't have to.

### 18.1 Architectural Prerequisites

The testing strategy is only possible because the code is structured to permit it. These are design commitments, not testing tactics:

- **Pure core, I/O at the edges.** Business logic — resolution, effect diffing, plan ordering, canonicalization, journal planning — takes inputs as arguments and returns outputs as values. Side effects happen at the edges, in thin glue that calls into the pure core.
- **Filesystem access behind an interface.** `kitd` does not call `open()` directly from its business logic. It goes through a trait that has a real-filesystem implementation for production and an in-memory implementation for tests.
- **Adapters behind an interface.** `kitd` invokes adapters through an `AdapterClient` abstraction. In production this is a subprocess call; in tier 1 tests it is a stub that records the call and returns a canned response.
- **Time and randomness behind interfaces.** A `Clock` trait for time, an `Rng` trait for randomness. Real implementations in production, deterministic implementations in tests. Otherwise tests involving timestamps or UID allocation are flaky.

These are good design properties regardless of testing. Testability is the happy consequence of separating decisions from actions.

### 18.2 Tier 0 — Pure Unit Tests

**Scope:** Pure functions with no filesystem, no daemon, no root directory, no processes.

**What's tested here:** Manifest parsing and validation. Content hash canonicalization. Dependency resolution and closure computation. Effect merging and conflict detection. Effect diffing between generations. Plan ordering and topological sort. Repository index parsing and signature verification. Path substitution against the root. Generation manifest construction. Journal serialization and replay planning.

**What's not tested here:** Anything that touches the filesystem, the network, a subprocess, or the real clock.

**Coverage target:** 60–80% of `kitd`'s logic by line count. Higher by branch count, since pure functions have more branches than I/O-bound code.

**Execution cost:** Microseconds to milliseconds per test. A full tier-0 suite should complete in under a second even with thousands of tests.

**When it runs:**
- On every file save during development, via an editor-integrated test runner.
- On demand during coding, when about to make a risky change.
- As a pre-commit hook or comparable habit.
- First step in CI on every push. A tier-0 failure fails CI fast without spinning up containers.

**Why it's the primary loop:** Its speed. You run it hundreds of times an hour. If tier-0 couldn't cover the majority of Kit's logic, the project would be fundamentally slower to build.

### 18.3 Tier 1 — Native Rooted Tests with Stub Adapters

**Scope:** Full `kitd` and `kit` processes running natively on the developer's machine (macOS, Linux, or BSD), against a test root in a temp directory, with stub adapters instead of real ones.

**What's tested here:** The daemon's IPC protocol and request handling. Real filesystem operations — store population, symlink tree construction, atomic `rename()` for `current`, journal `fsync` behavior, GC reachability walks. End-to-end flows from CLI invocation through daemon to filesystem state. Error recovery paths, including mid-activation crashes that require journal replay.

**Stub adapter behavior:** A stub adapter is a small binary (shell script or minimal SysL program) that reads a request on stdin, appends it to a log file, and writes a canned success response. Tests assert on the log ("the daemon invoked the user-database adapter with these arguments") rather than on real system state.

**Isolation guarantees:** Each test gets a fresh root like `/tmp/kit-test-<uuid>`. The test harness refuses to run if `--root` is `/` or any other obviously-dangerous path. Nothing outside the test root is touched.

**Execution cost:** One to two seconds per test (daemon startup, request, teardown). A typical tier-1 suite runs in under a minute.

**When it runs:**
- Several times a day during development when working on stateful behavior.
- On every push in CI, after tier 0 passes.
- Interactively, when iterating on daemon behavior — `tail -f` the daemon log in one terminal, run `kit` commands in another, inspect the test root in a third.

**Why native instead of containerized:** Tier 1 uses only POSIX APIs with stubbed adapters, so it runs identically on the developer's Mac and on Linux CI. No container startup overhead. Native debuggers work. The developer's primary machine is the primary test environment.

**Safety:** Because adapters are stubbed, tier 1 never creates real users, never registers real services, never modifies real capabilities. The worst thing a bug at tier 1 can do is write files inside the test root.

### 18.4 Tier 2 — Container-Based Real-Adapter Tests

**Scope:** Full Kit running in a Docker container, with *real* adapters invoking *real* system facilities (`useradd`, `setcap`, service registration), against a fresh container per test.

**What's tested here:** Adapter correctness — that `kit-adapter-userdb-useradd` correctly invokes `useradd` with the right arguments and produces the right result in `/etc/passwd`. That `kit-adapter-caps-linux` correctly applies file capabilities and they behave as expected at exec time. That service registration actually works end-to-end with a real init.

**Container strategy:** A Dockerfile per tested platform (e.g., `kit-test-alpine`, `kit-test-ubuntu`, `kit-test-freebsd-via-qemu`) in the repository. The developer's Kit checkout is mounted as a volume at test time, so iteration doesn't require image rebuilds. `docker run --rm` means the container is destroyed after each test; no state carries over.

**Execution cost:** Five to ten seconds per test (container startup, test, teardown). Parallelizable across many containers.

**When it runs:**
- When actively developing an adapter or platform integration.
- On every push in CI, after tier 1 passes.
- Before releases, across the full matrix of platforms.

**What it doesn't cover:** macOS-specific adapters. Docker on Mac runs a Linux VM, so macOS adapters (`launchd`, `dscl`, `sandbox-exec`) can't be tested this way. Those run at tier 1 on a real Mac (with stubs) or at tier 2 on a real Mac using an isolated root (for integration with real macOS facilities). A comparable story applies to BSD and Slix — each platform gets its own tier-2 environment, and CI runs the matrix.

**Safety:** The host system is never touched. All side effects happen inside an ephemeral container whose lifetime is one test.

### 18.5 Tier 3 — Full VM Tests

**Scope:** A full Linux (or other) VM, booted fresh for the test suite, running Kit against `--root=/` with real everything.

**What's tested here:** Things Docker doesn't model well — boot sequence interactions, kernel module scenarios, behaviors that depend on a real init running as PID 1 in a way Docker fights. In practice, this is a small fraction of Kit's behavior.

**Execution cost:** 30+ seconds per test, including VM boot. Run serially, not in parallel.

**When it runs:**
- Rarely, when a tier-2 failure suggests a VM-level issue.
- Before major releases, as a final integration check.
- When developing features that genuinely need a full kernel (uncommon for Kit).

**Why it's an escape hatch, not a daily driver:** The cost-to-value ratio is bad for most Kit development. Tier 2 catches nearly everything tier 3 would catch at an order of magnitude less cost. Tier 3 exists so that when something weird happens, there's a way to reproduce it — not so that every change has to go through it.

### 18.6 The Test Scenario Format

Scenarios are declarative TOML files describing a test case:

```toml
[scenario]
name = "install-nginx-then-rollback"
tier = ["tier1", "tier2-linux"]
required-features = ["user-database", "service-registration"]

[repo]
# synthetic packages the test repo should contain
[[repo.package]]
name = "nginx"
version = "1.27.0"
content-hash-of = "fixtures/nginx-1.27.0.tar.zst"
deps = [{ name = "libc", version = "0.3.1" }]
effects = [
  { type = "user", name = "nginx", group = "nginx" },
  { type = "service", name = "nginx", binary = "sbin/nginx" },
]
# ... etc

[[step]]
action = "install"
args = ["--system", "nginx"]
expect = "ok"

[[step]]
action = "assert-store-contains"
name = "nginx"
version = "1.27.0"

[[step]]
action = "assert-effect-applied"
type = "user"
name = "nginx"

[[step]]
action = "rollback"
args = ["--system"]
expect = "ok"

[[step]]
action = "assert-effect-reverted"
type = "user"
name = "nginx"
```

A test runner reads the scenario, generates a synthetic signed repository from the `[repo]` block, sets up a fresh root, drives `kitd` through the steps, and asserts on outcomes. The same scenario runs at tier 1 with stub adapters (where "assert-effect-applied" checks the adapter log) and at tier 2 with real adapters (where it checks real `/etc/passwd`). The `tier` field controls which tiers a scenario targets; most scenarios target both.

This format pays for itself quickly. A new test is a new TOML file, not a new script with setup boilerplate. Scenarios are human-readable documentation of behavior. The test runner is written once and evolves independently of the scenarios.

### 18.7 The Synthetic Repository Generator

A small tool, `kit-mkrepo-test` (or similar), takes a specification and produces a real signed repository in a temp directory: index, manifests, blobs, signatures. Used by tier 1, tier 2, and tier 3 tests to construct repositories without needing real upstream ones.

The generator is worth writing early. It's not a test itself, but it's the fixture infrastructure everything else depends on. Shipping tests that depend on specific real packages from real repositories creates test fragility proportional to how much the world changes; synthetic fixtures are stable.

### 18.8 Anti-Patterns to Avoid

- **Defaulting the `--root` flag.** It must always be explicit. The test harness refuses to run if it's missing or points at `/`. A typo in a test script should never be able to damage the host.
- **Daemon state between tests.** Each test gets a fresh `kitd` against a fresh root. Reusing daemons across tests saves seconds but creates state-leak bugs that take hours to debug.
- **Hard-coded hashes in tests.** Tests should assert on structural properties ("a store path for nginx exists") unless they're specifically testing canonicalization. A change to canonicalization should break exactly one test suite, not every test.
- **Running integration tests against a real system.** Not on the developer's Mac, not on a shared server, not "just this once." Tier 2 and tier 3 use disposable environments. Tier 1 uses rooted directories with stubbed adapters. Tier 0 uses nothing at all.
- **Making tier-0 tests indirectly touch the filesystem.** If the test reads a TOML file to set up a manifest, that's a tier-1 test, not a tier-0 test. Tier 0 takes TOML as a string literal. Purity is enforced by discipline.

### 18.9 The Development Rhythm

A typical hour of Kit development, assuming the architecture is in place:

- 80% of time: Pure logic work. Tier 0 tests fire on save. Feedback in milliseconds. No daemon, no containers, no VMs. Hundreds of test runs per hour.
- 15% of time: Stateful behavior work. Tier 1 tests run on demand, a few seconds each. Developer has `kitd` log tailing in one window, runs `kit` commands interactively to explore. Dozens of test runs per hour.
- 4% of time: Adapter or platform-integration work. Tier 2 tests in containers, ~10 seconds each. Handful of runs per hour.
- 1% of time: Deep integration issues requiring a full VM. Rare.

The pyramid works because each tier catches what the one above couldn't, and you run the cheaper tiers more often.

### 18.10 What This Costs to Build

The test infrastructure described here is not free, but it's bounded:

- **Stub adapters:** One per standard adapter type. Each is tens of lines. Maybe a day's work total.
- **Synthetic repository generator:** A few hundred lines. A week's work.
- **Test runner for scenarios:** A few hundred lines. A week's work.
- **Per-platform Dockerfiles:** A dozen lines each. An hour each.
- **In-memory filesystem fake:** A few hundred lines, but only if real-filesystem performance becomes an issue at tier 1; often the real filesystem with a temp directory is fast enough.

Two to three weeks total for the test infrastructure, spread across early development. It pays itself back within the first month of real coding, because the alternative — ad-hoc scripts and manual testing — gets slower as the codebase grows, while this infrastructure scales.

Built from commit one, the safety and speed are free. Retrofitted later, they're expensive.

---

## 19. What Students See

A student working through a Slix-based OS course should be able to:

- `ls <root>/kit/store/` and see every package version side by side.
- Inspect a binary's dynamic dependencies (readelf, otool) and see exactly which library versions it will load.
- `kit install --system nginx`, watch it resolve, fetch, activate, start — no interactive prompts, no hidden state changes.
- `kit effects nginx` and see the complete closed-schema list of what installing nginx did.
- `kit caps list`, `kit users list`, `kit adapters list` and see exactly what the package manager has done and what it's configured to do.
- Edit `<root>/etc/kit/system.toml`, run `kit reconfigure`, watch the nginx config regenerate and the service restart.
- `kit rollback`, watch the service stop, user disappear, capabilities revoke, generation flip — one atomic operation, journaled.
- Read the activation journal of a completed operation and reconstruct what happened in what order.
- Install the same Kit on their Mac, run it with `--root=~/.kit`, and use it for their personal tools — carrying the knowledge out of the course.
- Read `kitd`'s source — targeted at a few thousand lines of SysL — and enumerate every operation it can perform on the system.

---

## 20. Comparison to NixOS

| Aspect | NixOS | Kit |
| --- | --- | --- |
| Store | Content-addressed immutable | Same |
| Generations | First-class with rollback | Same |
| System config | `configuration.nix` in Nix language | `system.toml`, plain TOML |
| Where effects live | Separate modules in nixpkgs | In the package manifest |
| Activation | Generated shell, modules contribute snippets | Closed schema, journaled engine |
| Package-code during activation | Full-authority shell | Sandboxed generators and first-run hooks only |
| Rollback fidelity | Best-effort (orphaned users linger) | Journaled, with defined inverses |
| Portability | Linux (+ nix-darwin for macOS, limited) | POSIX: Linux, macOS, BSD, Slix |
| Platform differences | Handled in Nix code | Handled by adapters |
| Build system | Fused with package manager | Separate tool (future), clean interface |

Kit is more constrained and more teachable. NixOS is more flexible and more battle-tested. The constraints here are deliberate: for a teaching OS (and for a portable package manager), the closed effect schema, sandboxed activation code, and journaled rollback demonstrate *how a modern package manager could look if you were willing to give up some flexibility for legibility and rigor*.

---

## 21. Summary

Kit is a portable POSIX package manager that:

- Installs software that actually works — including software that needs users, services, capabilities, directories, and generated config.
- Achieves this without install-time arbitrary code execution, via a closed effect schema applied by a journaled activation engine.
- Runs natively on Linux, macOS, BSD, and Slix, with platform differences handled by small swappable adapters.
- Preserves content-addressed immutability, exact pinning, deterministic rollback, and multi-user safety.
- Serves as Slix's native system package manager and as a portable personal-prefix package manager on other platforms, from a single codebase.
- Is small enough to read and understand.
- Is future-proofed for source builds without structural changes.

Seven invariants carry the weight: immutability, exact pinning, separation of concerns, profile composition, identity-is-content, bounded effects, POSIX portability. Together they make Kit both a real package manager and a teachable one that outlives the course.

A student finishing a Slix-based OS course should be able to point at `<root>/kit/` on any POSIX system and explain how it achieves rollback, multi-version coexistence, multi-user safety, deterministic system integration, platform portability, and clean separation from the build system — by reading files, not by reciting abstractions.
