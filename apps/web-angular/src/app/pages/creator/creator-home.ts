import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { SlicePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Api, CreatorAccount, Title, Usage } from '../../core/api';
import { problemMessage } from '../../core/http';
import { StateView, Toasts, formatBytes } from '../../ui/ui';

const STATE_STYLE: Record<string, string> = {
  DRAFT: 'bg-white/10', IN_REVIEW: 'bg-ember-400/20 text-ember-400', APPROVED: 'bg-lagoon-400/15 text-lagoon-300',
  REJECTED: 'bg-rose-400/15 text-rose-400', PUBLISHED: 'bg-aurora-500/25 text-aurora-300', REMOVED: 'bg-white/5 text-ink-400',
};

@Component({
  selector: 'ss-creator-home',
  imports: [FormsModule, RouterLink, StateView, SlicePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page pt-28">
      <p class="eyebrow">Creator studio</p>
      @if (loading()) { <div class="skeleton mt-4 h-40"></div> }
      @else if (error()) { <ss-state kind="error" heading="Studio unavailable" [message]="error()" action="Retry" (act)="ngOnInit()" /> }
      @else if (!creator()) {
        <div class="mt-4 grid gap-10 lg:grid-cols-2">
          <div>
            <h1 class="text-3xl font-bold sm:text-4xl">Share your films with an audience that cares</h1>
            <p class="mt-4 text-ink-300">Upload original or openly licensed work. You keep your rights; we transcode to adaptive HLS, caption, and stream it securely. Every title is reviewed before it goes live.</p>
            <ul class="mt-6 space-y-2 text-sm text-ink-200">
              <li>✦ Direct, resumable uploads up to 2 GB</li><li>✦ Transparent analytics — views, completions, playback quality</li><li>✦ Clear moderation decisions with appeals</li>
            </ul>
          </div>
          <form class="card space-y-4 p-6" (ngSubmit)="register()">
            <h2 class="text-lg font-semibold">Become a creator</h2>
            <div><label class="label" for="cn">Creator name</label><input id="cn" name="cn" class="field" [(ngModel)]="name" required maxlength="80" /></div>
            <div><label class="label" for="bio">Short bio</label><textarea id="bio" name="bio" class="field" rows="3" [(ngModel)]="bio" maxlength="2000"></textarea></div>
            <div><label class="label" for="ctry">Country (ISO code)</label><input id="ctry" name="ctry" class="field" [(ngModel)]="country" maxlength="2" placeholder="US" /></div>
            <label class="flex items-start gap-3 text-sm text-ink-200"><input type="checkbox" name="terms" [(ngModel)]="terms" class="mt-1 h-4 w-4 accent-aurora-500" required />
              I will only upload work I own or am licensed to distribute, and I accept the creator terms and takedown policy.</label>
            <button class="btn-accent w-full" [disabled]="!terms || !name">Create creator account</button>
          </form>
        </div>
      } @else {
        <div class="mt-2 flex flex-wrap items-end justify-between gap-4">
          <div><h1 class="text-3xl font-bold">{{ creator()!.displayName }}</h1><p class="text-sm text-ink-400">{{ titles().length }} titles</p></div>
          <div class="flex gap-3"><a routerLink="/creator/analytics" class="btn-ghost">Analytics</a><button class="btn-accent" (click)="newTitle()">+ New title</button></div>
        </div>
        @if (quota(); as q) {
          <div class="card mt-6 p-4 text-sm" [class.ring-ember-400]="q.ratio! >= 0.7">
            <div class="flex justify-between"><span>Platform storage (estimate)</span><span>{{ bytes(q.sourceBytes! + q.mediaBytes!) }} of {{ bytes(q.quotaBytes) }} · {{ q.level }}</span></div>
            <div class="mt-2 h-2 overflow-hidden rounded-full bg-white/10" role="progressbar" [attr.aria-valuenow]="pct(q.ratio!)" aria-valuemin="0" aria-valuemax="100" aria-label="Storage used">
              <div class="h-full rounded-full" [class]="q.ratio! >= 0.85 ? 'bg-rose-400' : q.ratio! >= 0.7 ? 'bg-ember-400' : 'bg-lagoon-400'" [style.width.%]="pct(q.ratio!)"></div></div>
            @if (q.ratio! >= 0.95) { <p class="mt-2 text-rose-400">New uploads are paused to stay inside the free-tier budget.</p> }
          </div>
        }
        <div class="card mt-6 overflow-x-auto">
          <table class="w-full text-left text-sm">
            <caption class="sr-only">Your titles</caption>
            <thead class="text-xs uppercase tracking-wider text-ink-400"><tr><th class="p-4">Title</th><th class="p-4">Editorial</th><th class="p-4">Media</th><th class="p-4">Updated</th><th class="p-4"><span class="sr-only">Actions</span></th></tr></thead>
            <tbody>
              @for (t of titles(); track t.id) {
                <tr class="border-t border-white/5">
                  <td class="p-4 font-medium">{{ t.title }}</td>
                  <td class="p-4"><span class="chip" [class]="style(t.editorialState!)">{{ t.editorialState }}</span></td>
                  <td class="p-4 text-ink-300">{{ t.mediaState }}</td>
                  <td class="p-4 text-ink-400">{{ t.updatedAt | slice:0:10 }}</td>
                  <td class="p-4 text-right"><a [routerLink]="['/creator/titles', t.id]" class="text-lagoon-400 hover:underline">Open studio →</a></td>
                </tr>
              } @empty { <tr><td colspan="5" class="p-8 text-center text-ink-400">No titles yet. Create your first one.</td></tr> }
            </tbody>
          </table>
        </div>
      }
    </div>`,
})
export class CreatorHomePage implements OnInit {
  private api = inject(Api);
  private router = inject(Router);
  private toasts = inject(Toasts);
  creator = signal<CreatorAccount | null>(null);
  titles = signal<Title[]>([]);
  quota = signal<Usage | null>(null);
  loading = signal(true);
  error = signal<string | null>(null);
  name = ''; bio = ''; country = ''; terms = false;
  bytes = formatBytes;
  pct = (r: number) => Math.min(100, Math.round(r * 100));
  style = (s: string) => STATE_STYLE[s] ?? '';

  async ngOnInit() {
    this.loading.set(true); this.error.set(null);
    try {
      this.creator.set(await this.api.me());
      const [t, q] = await Promise.all([this.api.myTitles(), this.api.quota()]);
      this.titles.set(t); this.quota.set(q);
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 404) this.creator.set(null); else this.error.set(problemMessage(e));
    } finally { this.loading.set(false); }
  }

  async register() {
    try { await this.api.registerCreator(this.name, this.bio, this.country.toUpperCase()); this.toasts.show('Welcome to the studio!', 'success'); await this.ngOnInit(); }
    catch (e) { this.toasts.show(problemMessage(e), 'error'); }
  }

  async newTitle() {
    try {
      const t = await this.api.createTitle({ title: 'Untitled film', synopsis: '', genres: [], language: 'en', maturityRating: 'NR', kind: 'FILM' });
      this.router.navigate(['/creator/titles', t.id]);
    } catch (e) { this.toasts.show(problemMessage(e), 'error'); }
  }
}
