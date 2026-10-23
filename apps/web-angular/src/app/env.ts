/**
 * Runtime configuration, read from /config.js (written by the container entrypoint from env vars), so one
 * built image works on any host. Falls back to local-development defaults.
 */
interface RuntimeConfig { apiBase?: string; oidcAuthority?: string; oidcClientId?: string }
const rc = (globalThis as { STREAMSPHERE_CONFIG?: RuntimeConfig }).STREAMSPHERE_CONFIG ?? {};

export const env = {
  apiBase: rc.apiBase ?? 'http://localhost:8080',
  oidc: {
    authority: rc.oidcAuthority ?? 'http://localhost:8180/realms/streamsphere',
    clientId: rc.oidcClientId ?? 'streamsphere-web',
  },
};
