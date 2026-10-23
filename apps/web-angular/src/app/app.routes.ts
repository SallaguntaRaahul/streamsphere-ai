import { Routes } from '@angular/router';
import { authGuard, roleGuard } from './core/guards';

export const routes: Routes = [
  { path: '', loadComponent: () => import('./pages/home').then(m => m.HomePage), title: 'StreamSphere' },
  { path: 'browse/:genre', loadComponent: () => import('./pages/genre').then(m => m.GenrePage), title: 'Browse · StreamSphere' },
  { path: 'title/:id', loadComponent: () => import('./pages/title-detail').then(m => m.TitleDetailPage), title: 'Title · StreamSphere' },
  { path: 'watch/:id', canActivate: [authGuard], loadComponent: () => import('./pages/watch').then(m => m.WatchPage), title: 'Watch · StreamSphere' },
  { path: 'search', loadComponent: () => import('./pages/search').then(m => m.SearchPage), title: 'Search · StreamSphere' },
  { path: 'library', canActivate: [authGuard], loadComponent: () => import('./pages/library').then(m => m.LibraryPage), title: 'My library · StreamSphere' },
  { path: 'profile', canActivate: [authGuard], loadComponent: () => import('./pages/profile').then(m => m.ProfilePage), title: 'Profile · StreamSphere' },
  { path: 'creator', canActivate: [authGuard], loadComponent: () => import('./pages/creator/creator-home').then(m => m.CreatorHomePage), title: 'Creator studio · StreamSphere' },
  { path: 'creator/titles/:id', canActivate: [authGuard], loadComponent: () => import('./pages/creator/title-studio').then(m => m.TitleStudioPage), title: 'Title studio · StreamSphere' },
  { path: 'creator/analytics', canActivate: [authGuard], loadComponent: () => import('./pages/creator/analytics').then(m => m.CreatorAnalyticsPage), title: 'Analytics · StreamSphere' },
  { path: 'moderation', canActivate: [roleGuard('moderator')], loadComponent: () => import('./pages/moderation/moderation').then(m => m.ModerationPage), title: 'Moderation · StreamSphere' },
  { path: 'admin', canActivate: [roleGuard('admin')], loadComponent: () => import('./pages/admin/admin').then(m => m.AdminPage), title: 'Operations · StreamSphere' },
  { path: 'auth/callback', loadComponent: () => import('./pages/misc').then(m => m.AuthCallbackPage) },
  { path: '403', loadComponent: () => import('./pages/misc').then(m => m.ForbiddenPage), title: 'Access denied · StreamSphere' },
  { path: '**', loadComponent: () => import('./pages/misc').then(m => m.NotFoundPage), title: 'Not found · StreamSphere' },
];
