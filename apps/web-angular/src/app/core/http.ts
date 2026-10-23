import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { env } from '../env';
import { AuthService } from './auth.service';

/** Adds the bearer token ONLY for our API origin (never leaks tokens to object storage or third parties). */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const token = auth.token();
  const ours = req.url.startsWith(env.apiBase + '/api/');
  const r = ours && token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;
  return next(r).pipe(catchError((e: HttpErrorResponse) => {
    if (ours && e.status === 401 && token) auth.user.set(null);
    return throwError(() => e);
  }));
};

/** Human message from an application/problem+json error. */
export function problemMessage(e: unknown): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) return 'Cannot reach StreamSphere. Check your connection and try again.';
    const p = e.error as { detail?: string; title?: string } | null;
    return p?.detail ?? p?.title ?? `Request failed (${e.status})`;
  }
  return e instanceof Error ? e.message : 'Something went wrong';
}
