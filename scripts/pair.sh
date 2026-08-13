#!/usr/bin/env bash
# Print the QR code the Scoutr app scans to connect (and the plain JSON fallback).
set -euo pipefail
cd "$(dirname "$0")/../bridge"

node dist/cli.js pair
