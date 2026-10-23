// k6 API load test (local only — never point at free-tier cloud quotas).
// Mix: anonymous browse, keyword+semantic search, authenticated playback authorization + master playlist.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://host.docker.internal:8080';
const playbackAuth = new Trend('playback_authorization_ms', true);
export const options = {
  scenarios: {
    browse: { executor: 'constant-vus', vus: 10, duration: '60s', exec: 'browse' },
    search: { executor: 'constant-vus', vus: 5, duration: '60s', exec: 'search' },
    playback: { executor: 'constant-vus', vus: 5, duration: '60s', exec: 'playback' },
  },
  thresholds: { http_req_failed: ['rate<0.01'], playback_authorization_ms: ['p(95)<300'] },
};

export function setup() {
  const r = http.post(`${__ENV.KC || 'http://host.docker.internal:8180'}/realms/streamsphere/protocol/openid-connect/token`,
    { grant_type: 'password', client_id: 'streamsphere-e2e', username: 'viewer', password: __ENV.VIEWER_PASSWORD });
  const titles = http.get(`${BASE}/api/v1/titles?limit=20`).json('items').map(t => t.id);
  return { token: r.json('access_token'), titles };
}

export function browse() {
  check(http.get(`${BASE}/api/v1/titles?limit=24`), { 'browse 200': r => r.status === 200 });
  sleep(0.5);
}

const queries = ['space comedy', 'quiet films about the sea', 'documentary', 'neon', 'orchard', 'signals from a satellite'];
export function search() {
  const q = queries[Math.floor(Math.random() * queries.length)];
  check(http.get(`${BASE}/api/v1/search?q=${encodeURIComponent(q)}&mode=hybrid`), { 'search 200': r => r.status === 200 });
  sleep(0.5);
}

export function playback(data) {
  const id = data.titles[Math.floor(Math.random() * data.titles.length)];
  const s = http.post(`${BASE}/api/v1/playback/sessions`, JSON.stringify({ titleId: id }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` } });
  playbackAuth.add(s.timings.duration);
  if (check(s, { 'session 201': r => r.status === 201 })) {
    check(http.get(`${BASE}${s.json('manifestUrl')}`), { 'master 200': r => r.status === 200 });
  }
  sleep(1);
}
