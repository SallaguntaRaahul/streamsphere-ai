import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Api, Recommendation, TitleView } from '../core/api';
import { AuthService } from '../core/auth.service';
import { problemMessage } from '../core/http';
import { StateView, TitleCard, Toasts, formatDuration } from '../ui/ui';

@Component({
  selector: 'ss-title-detail',
  imports: [RouterLink, TitleCard, StateView, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (error()) {
      <div class="page pt-32"><ss-state kind="error" heading="Title unavailable" [message]="error()"><a routerLink="/" class="btn-primary mt-5">Back to home</a></ss-state></div>
    } @else if (t(); as t) {
      <article>
        <div class="relative isolate overflow-hidden">
          @if (api.posterUrl(t.posterPath)) { <img [src]="api.posterUrl(t.posterPath)" alt="" class="absolute inset-0 -z-20 h-full w-full object-cover opacity-50" /> }
          <div class="absolute inset-0 -z-10 bg-gradient-to-t from-ink-950 via-ink-950/70 to-ink-950/30"></div>
          <div class="page grid gap-10 pb-12 pt-32 lg:grid-cols-[1.4fr_1fr]">
            <div>
              <div class="flex flex-wrap gap-2">@for (g of t.genres; track g) { <a [routerLink]="['/browse', g]" class="chip hover:bg-white/15">{{ g }}</a> }</div>
              <h1 class="mt-4 text-4xl font-bold sm:text-5xl">{{ t.title }}</h1>
              <p class="mt-3 text-sm text-ink-300">{{ t.releaseYear }} · {{ t.maturityRating }} · {{ fmt(t.durationSeconds) }} · {{ (t.language || '').toUpperCase() }} · by <span class="text-ink-100">{{ t.creatorName }}</span></p>
              <p class="mt-6 max-w-2xl text-lg leading-relaxed text-ink-100">{{ t.synopsis }}</p>
              <div class="mt-8 flex flex-wrap gap-3">
                <button class="btn-primary !px-7 !py-3" (click)="play()">▶ {{ resumeAt() ? 'Resume' : 'Play' }}</button>
                <button class="btn-ghost !py-3" (click)="toggleWatchlist()" [attr.aria-pressed]="inList()">{{ inList() ? '✓ In my list' : '+ My list' }}</button>
                <button class="btn-ghost !py-3" (click)="reportOpen.set(!reportOpen())" [attr.aria-expanded]="reportOpen()">Report</button>
              </div>
              @if (reportOpen()) {
                <form class="card mt-5 max-w-lg space-y-3 p-5" (ngSubmit)="report()">
                  <label class="label" for="cat">What's wrong?</label>
                  <select id="cat" class="field" [(ngModel)]="reportCategory" name="cat">
                    @for (c of categories; track c) { <option [value]="c">{{ c.toLowerCase() }}</option> }
                  </select>
                  <label class="label" for="det">Details (optional)</label>
                  <textarea id="det" class="field" rows="3" [(ngModel)]="reportDetails" name="det" maxlength="2000"></textarea>
                  <button class="btn-accent" type="submit">Send report</button>
                </form>
              }
            </div>
          </div>
        </div>
        @if (similar().length) {
          <section class="page mt-6" aria-labelledby="sim-h">
            <h2 id="sim-h" class="text-xl font-semibold">More like this</h2>
            <ul class="mt-4 grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-4">
              @for (s of similar(); track s.titleId) { <li><ss-title-card [id]="s.titleId" [title]="s.title" [subtitle]="s.reasons[0]" [poster]="api.posterUrl(similarPosters().get(s.titleId))" /></li> }
            </ul>
          </section>
        }
      </article>
    } @else {
      <div class="page pt-32"><div class="skeleton h-12 w-2/3"></div><div class="skeleton mt-4 h-40 w-full"></div></div>
    }`,
})
export class TitleDetailPage {
  protected api = inject(Api);
  private auth = inject(AuthService);
  private router = inject(Router);
  private toasts = inject(Toasts);
  id = input.required<string>();
  t = signal<TitleView | null>(null);
  similar = signal<Recommendation[]>([]);
  similarPosters = signal(new Map<string, string | null>());
  inList = signal(false);
  resumeAt = signal(0);
  error = signal<string | null>(null);
  reportOpen = signal(false);
  reportCategory = 'COPYRIGHT';
  reportDetails = '';
  categories = ['COPYRIGHT', 'ILLEGAL', 'HARASSMENT', 'SEXUAL', 'VIOLENCE', 'SPAM', 'OTHER'];
  fmt = formatDuration;

  constructor() {
    effect(() => {
      const id = this.id();
      this.t.set(null); this.error.set(null);
      this.api.title(id).then(t => this.t.set(t)).catch(e => this.error.set(problemMessage(e)));
      this.api.similar(id).then(async r => {
        const items = r.items.slice(0, 8);
        const posters = new Map((await this.api.titlesBatch(items.map(i => i.titleId))).map(t => [t.id!, t.posterPath ?? null]));
        this.similarPosters.set(posters);
        this.similar.set(items);
      }).catch(() => this.similar.set([]));
      if (this.auth.isAuthenticated()) {
        this.api.watchlist().then(w => this.inList.set(w.some(x => x.titleId === id))).catch(() => {});
        this.api.progress(id).then(p => this.resumeAt.set(p.completed ? 0 : p.positionSeconds ?? 0)).catch(() => {});
      }
    });
  }

  play() { this.router.navigate(['/watch', this.id()]); }

  async toggleWatchlist() {
    if (!this.auth.isAuthenticated()) return this.auth.login();
    try {
      if (this.inList()) { await this.api.removeFromWatchlist(this.id()); this.inList.set(false); this.toasts.show('Removed from your list'); }
      else { await this.api.addToWatchlist(this.id()); this.inList.set(true); this.toasts.show('Added to your list', 'success'); }
    } catch (e) { this.toasts.show(problemMessage(e), 'error'); }
  }

  async report() {
    if (!this.auth.isAuthenticated()) return this.auth.login();
    try { await this.api.report(this.id(), this.reportCategory, this.reportDetails); this.toasts.show('Thanks — our moderators will review it.', 'success'); this.reportOpen.set(false); }
    catch (e) { this.toasts.show(problemMessage(e), 'error'); }
  }
}
