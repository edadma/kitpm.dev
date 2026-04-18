---
title: Package Testing
description: Verifying that packaged software works after installation.
---

Packages can declare self-tests that verify the software works correctly after installation. These aren't Kit's own tests — they test the packaged software itself.

## Why

When you're building a package ecosystem, you need to answer: "does every package in the repo actually work?" `kit test --all` makes that question answerable, automated, and tied to exact content hashes so the answer doesn't go stale.

## Test declaration

Tests are declared in the package manifest:

```toml
[[tests]]
name = "starts-and-listens"
binary = "bin/nginx-test-start"
requires-features = ["service-registration"]

[[tests]]
name = "config-reload"
binary = "bin/nginx-test-reload"
requires-features = []
```

Each test declares a name, a path to the test script, and the platform features it needs.

## Test scripts are POSIX shell

Test scripts must be `#!/bin/sh` scripts. This is a deliberate constraint: test scripts must be independent of the software they test.

If a Python package ships tests written in Python and the package is broken, the test script itself can't run. POSIX shell is the one language guaranteed to exist on every Kit platform. It tests the package by running it as a subprocess:

```sh
#!/bin/sh
output=$(bin/python -c "print(1 + 1)")
test "$output" = "2"
```

Exit 0 means pass. Non-zero means fail. Stdout and stderr are captured.

## Execution

`kit test <name>`:

1. Creates a fresh ephemeral root at `/tmp/kit-test-<uuid>/`.
2. Installs the package and its full closure.
3. Runs each test in a sandbox (same as generators: unprivileged user, confined, resource-limited).
4. Skips tests whose `requires-features` aren't satisfied.
5. Tears down the ephemeral root.
6. Reports results.

## Batch testing

`kit test --all` walks every package in the configured repositories and runs their tests. This is the release gate for a Slix distribution.

Results are cached by content hash. Since the hash is the sole identity, a passing result for a hash means "this exact package was tested and passed." `kit test --all` skips packages with cached passing results. `kit test --all --force` retests everything.

## Reporting

```
kit test nginx
# nginx:
#   PASS  starts-and-listens  142ms
#   PASS  config-reload       38ms
#   SKIP  tls-handshake       (missing features: network)
#   2 passed, 0 failed, 1 skipped
```

`kit test --report` shows the last results for all tested packages.
