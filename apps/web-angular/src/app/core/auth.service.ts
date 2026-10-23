import { Injectable, computed, signal } from '@angular/core';
import { User, UserManager, WebStorageStateStore } from 'oidc-client-ts';
import { env } from '../env';

/** OIDC Authorization Code + PKCE against Keycloak (no passwords ever touch this app). */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly mgr = new UserManager({
    authority: env.oidc.authority,
    client_id: env.oidc.clientId,
    redirect_uri: `${location.origin}/auth/callback`,
    post_logout_redirect_uri: location.origin,
    response_type: 'code',
    scope: 'openid profile email',
    userStore: new WebStorageStateStore({ store: sessionStorage }),
    automaticSilentRenew: true,
  });
  readonly user = signal<User | null>(null);
  readonly ready = signal(false);
  readonly isAuthenticated = computed(() => !!this.user() && !this.user()!.expired);
  readonly roles = computed<string[]>(() => {
    const t = this.user()?.access_token;
    if (!t) return [];
    try {
      const payload = JSON.parse(atob(t.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
      return payload?.realm_access?.roles ?? [];
    } catch { return []; }
  });
  readonly isModerator = computed(() => this.roles().includes('moderator') || this.roles().includes('admin'));
  readonly isAdmin = computed(() => this.roles().includes('admin'));
  readonly displayName = computed(() => this.user()?.profile?.given_name ?? this.user()?.profile?.preferred_username ?? '');
  readonly userId = computed(() => this.user()?.profile?.sub ?? null);

  constructor() {
    this.mgr.events.addUserLoaded(u => this.user.set(u));
    this.mgr.events.addUserUnloaded(() => this.user.set(null));
    this.mgr.events.addSilentRenewError(() => this.user.set(null));
    this.mgr.getUser().then(u => { this.user.set(u && !u.expired ? u : null); this.ready.set(true); });
  }

  token(): string | null { const u = this.user(); return u && !u.expired ? u.access_token : null; }

  login(returnTo = location.pathname + location.search) { return this.mgr.signinRedirect({ state: { returnTo } }); }

  register() { return this.mgr.signinRedirect({ state: { returnTo: '/' }, extraQueryParams: { kc_action: 'register' }, prompt: 'create' }); }

  async completeLogin(): Promise<string> {
    const u = await this.mgr.signinRedirectCallback();
    this.user.set(u);
    return (u.state as { returnTo?: string } | undefined)?.returnTo ?? '/';
  }

  logout() { return this.mgr.signoutRedirect(); }
}
