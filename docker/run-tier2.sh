#!/bin/sh
# Build and run tier 2 tests in a Docker container.
# Must be run from the kitpm.dev root directory.
set -e

echo "=== Building tier 2 Docker image ==="
docker build -f docker/Dockerfile.tier2 -t kit-tier2 .

echo ""
echo "=== Running tier 2 tests ==="
docker run --rm kit-tier2
