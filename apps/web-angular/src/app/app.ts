import { ChangeDetectionStrategy, Component, HostListener, effect, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from './core/auth.service';
import { Api, Notification } from './core/api';
import { Logo, Toasts } from './ui/ui';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, Logo],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a href="#main" class="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-50 focus:rounded-full focus:bg-white focus:px-4 focus:py-2 focus:text-ink-950">Skip to content</a>
    <header class="fixed inset-x-0 top-0 z-40 transition duration-300" [class]="scrolled() ? 'bg-ink-950/85 backdrop-blur-xl ring-1 ring-white/5' : 'bg-gradient-to-b from-ink-950/90 to-transparent'">
      <nav class="page flex h-16 items-center gap-6" aria-label="Primary">
        <a routerLink="/" aria-label="StreamSphere home"><ss-logo /></a>
        <ul class="hidden items-center gap-5 text-sm text-ink-300 md:flex">
          <li><a routerLink="/" routerLinkActive="text-white" [routerLinkActiveOptions]="{ exact: true }" class="hover:text-white">Home</a></li>
          <li><a routerLink="/search" routerLinkActive="text-white" class="hover:text-white">Discover</a></li>
          @if (auth.isAuthenticated()) {
            <li><a routerLink="/library" routerLinkActive="text-white" class="hover:text-white">My library</a></li>
            <li><a routerLink="/creator" routerLinkActive="text-white" class="hover:text-white">Creator studio</a></li>
          }
          @if (auth.isModerator()) { <li><a routerLink="/moderation" routerLinkActive="text-white" class="hover:text-white">Moderation</a></li> }
          @if (auth.isAdmin()) { <li><a routerLink="/admin" routerLinkActive="text-white" class="hover:text-white">Operations</a></li> }
        </ul>
        <div class="ml-auto flex items-center gap-2">
          <a routerLink="/search" class="btn-ghost h-9 w-9 !p-0 md:hidden" aria-label="Search">⌕</a>
          @if (auth.isAuthenticated()) {
            <div class="relative">
              <button class="btn-ghost relative h-9 w-9 !p-0" (click)="toggleNotifications()" [attr.aria-expanded]="notifOpen()" aria-haspopup="dialog"
                      [attr.aria-label]="'Notifications' + (unread() ? ', ' + unread() + ' unread' : '')">
                <span aria-hidden="true">🔔</span>
                @if (unread()) { <span class="absolute -right-0.5 -top-0.5 grid h-4 min-w-4 place-items-center rounded-full bg-ember-400 px-1 text-[10px] font-bold text-ink-950">{{ unread() }}</span> }
              </button>
              @if (notifOpen()) {
                <div role="dialog" aria-label="Notifications" class="card absolute right-0 mt-2 w-80 overflow-hidden p-0 shadow-2xl animate-rise">
                  <div class="flex items-center justify-between border-b border-white/5 px-4 py-3">
                    <p class="text-sm font-semibold">Notifications</p>
                    <button class="text-xs text-lagoon-400 hover:underline" (click)="readAll()">Mark all read</button>
                  </div>
                  <ul class="max-h-96 overflow-y-auto">
                    @for (n of notes(); track n.id) {
                      <li><a [routerLink]="n.link" (click)="openNote(n)" class="block px-4 py-3 hover:bg-white/5" [class.opacity-60]="n.readAt">
                        <p class="text-sm font-medium">{{ n.title }}</p><p class="mt-0.5 line-clamp-2 text-xs text-ink-300">{{ n.body }}</p></a></li>
                    } @empty { <li class="px-4 py-6 text-center text-sm text-ink-400">You're all caught up.</li> }
                  </ul>
                </div>
              }
            </div>
            <a routerLink="/profile" class="btn-ghost h-9 !px-3">
              <span class="grid h-6 w-6 place-items-center rounded-full bg-aurora-500 text-xs font-bold" aria-hidden="true">{{ (auth.displayName() || '?')[0] }}</span>
              <span class="hidden text-sm sm:inline">{{ auth.displayName() }}</span><span class="sr-only"> profile</span>
            </a>
            <button class="btn-ghost h-9 !px-3 text-sm" (click)="auth.logout()">Sign out</button>
          } @else if (auth.ready()) {
            <button class="btn-ghost h-9 text-sm" (click)="auth.login()">Sign in</button>
            <button class="btn-primary hidden h-9 text-sm sm:inline-flex" (click)="auth.register()">Join free</button>
          }
        </div>
      </nav>
      <nav class="no-scrollbar page flex gap-4 overflow-x-auto pb-2 text-sm text-ink-300 md:hidden" aria-label="Primary (mobile)">
        <a routerLink="/" class="shrink-0">Home</a><a routerLink="/search" class="shrink-0">Discover</a>
        @if (auth.isAuthenticated()) { <a routerLink="/library" class="shrink-0">Library</a><a routerLink="/creator" class="shrink-0">Studio</a> }
        @if (auth.isModerator()) { <a routerLink="/moderation" class="shrink-0">Moderation</a> }
        @if (auth.isAdmin()) { <a routerLink="/admin" class="shrink-0">Ops</a> }
      </nav>
    </header>

    <main id="main" tabindex="-1" class="min-h-[70dvh] outline-none">
      <router-outlet />
    </main>

    <footer class="mt-24 border-t border-white/5 py-10 text-sm text-ink-400">
      <div class="page flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <ss-logo />
        <p>Independent and openly licensed films only · Signed playback URLs are access control, not DRM.</p>
      </div>
    </footer>

    <div class="fixed bottom-4 right-4 z-50 flex flex-col gap-2" aria-live="polite" aria-atomic="false">
      @for (t of toasts.items(); track t.id) {
        <div class="card px-4 py-3 text-sm shadow-xl animate-rise" [class.ring-rose-400]="t.tone === 'error'" [class.ring-lagoon-400]="t.tone === 'success'"
             [attr.role]="t.tone === 'error' ? 'alert' : 'status'">{{ t.text }}</div>
      }
    </div>`,
})
export class App {
  protected readonly auth = inject(AuthService);
  protected readonly toasts = inject(Toasts);
  private readonly api = inject(Api);
  protected readonly scrolled = signal(false);
  protected readonly notifOpen = signal(false);
  protected readonly unread = signal(0);
  protected readonly notes = signal<Notification[]>([]);

  constructor() {
    effect(() => { if (this.auth.isAuthenticated()) this.refreshUnread(); });
    setInterval(() => { if (this.auth.isAuthenticated()) this.refreshUnread(); }, 30_000);
    inject(Router).events.pipe(filter(e => e instanceof NavigationEnd)).subscribe(() => this.notifOpen.set(false));
    effect(() => {
      if (!this.auth.isAuthenticated()) return;
      this.api.profile().then(r => document.documentElement.classList.toggle('reduce-motion', !!r.body?.reducedMotion)).catch(() => {});
    });
  }

  @HostListener('window:scroll') onScroll() { this.scrolled.set(window.scrollY > 24); }
  @HostListener('document:keydown.escape') esc() { this.notifOpen.set(false); }

  async refreshUnread() { try { this.unread.set((await this.api.unread()).unread); } catch { /* non-critical */ } }
  async toggleNotifications() {
    this.notifOpen.update(v => !v);
    if (this.notifOpen()) { try { this.notes.set((await this.api.notifications()).items); } catch { this.notes.set([]); } }
  }
  async openNote(n: Notification) { if (!n.readAt) { await this.api.markRead(n.id!); this.refreshUnread(); } }
  async readAll() { await this.api.markAllRead(); this.unread.set(0); this.notes.update(ns => ns.map(n => ({ ...n, readAt: new Date().toISOString() }))); }
}
