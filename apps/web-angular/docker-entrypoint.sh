#!/bin/sh
# Writes runtime config for the SPA from environment variables (no rebuild per environment).
set -eu
esc() { printf '%s' "$1" | sed 's/[\\"]/\\&/g'; }
printf 'window.STREAMSPHERE_CONFIG = { apiBase: "%s", oidcAuthority: "%s", oidcClientId: "%s" };\n' \
  "$(esc "$API_BASE")" "$(esc "$OIDC_AUTHORITY")" "$(esc "$OIDC_CLIENT_ID")" > /tmp/streamsphere-config.js
