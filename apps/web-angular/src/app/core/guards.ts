import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

async function ready(auth: AuthService) {
  while (!auth.ready()) await new Promise(r => setTimeout(r, 20));
}

export const authGuard: CanActivateFn = async (_r, state) => {
  const auth = inject(AuthService);
  await ready(auth);
  if (auth.isAuthenticated()) return true;
  await auth.login(state.url);
  return false;
};

export const roleGuard = (role: 'moderator' | 'admin'): CanActivateFn => async (_r, state) => {
  const auth = inject(AuthService);
  const router = inject(Router); // inject() must run before the first await (injection context)
  await ready(auth);
  if (!auth.isAuthenticated()) { await auth.login(state.url); return false; }
  const ok = role === 'admin' ? auth.isAdmin() : auth.isModerator();
  return ok ? true : router.parseUrl('/403');
};
