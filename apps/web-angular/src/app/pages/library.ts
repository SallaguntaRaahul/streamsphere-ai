import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { Api, Progress, TitleView } from '../core/api';
import { problemMessage } from '../core/http';
import { StateView, TitleCard, Toasts } from '../ui/ui';

@Component({
  selector: 'ss-library',
  imports: [TitleCard, StateView],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page pt-28">
      <h1 class="text-3xl font-bold sm:text-4xl">My library</h1>
      @if (error()) { <ss-state kind="error" heading="Couldn't load your library" [message]="error()" action="Retry" (act)="load()" /> }
      <section class="mt-8" aria-labelledby="wl-h">
        <h2 id="wl-h" class="text-xl font-semibold">My list</h2>
        <ul class="mt-4 grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-4">
          @for (t of list(); track t.id) { <li><ss-title-card [id]="t.id!" [title]="t.title!" [poster]="api.posterUrl(t.posterPath)" [subtitle]="t.creatorName ?? null" /></li> }
        </ul>
        @if (!loading() && !list().length) { <p class="mt-3 text-sm text-ink-400">Nothing saved yet — use “+ My list” on any title.</p> }
      </section>
      <section class="mt-12" aria-labelledby="h-h">
        <div class="flex items-center justify-between">
          <h2 id="h-h" class="text-xl font-semibold">Watch history</h2>
          @if (history().length) { <button class="btn-danger !py-1.5 text-xs" (click)="clear()">Clear history</button> }
        </div>
        <ul class="mt-4 grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-4">
          @for (h of history(); track h.p.titleId) {
            <li><ss-title-card [id]="h.t.id!" [title]="h.t.title!" [poster]="api.posterUrl(h.t.posterPath)"
              [subtitle]="h.p.completed ? 'Watched' : 'In progress'" [progress]="h.p.completed ? 1 : h.p.positionSeconds! / h.p.durationSeconds!" /></li>
          }
        </ul>
        @if (!loading() && !history().length) { <p class="mt-3 text-sm text-ink-400">Your viewing history will appear here.</p> }
      </section>
    </div>`,
})
export class LibraryPage implements OnInit {
  protected api = inject(Api);
  private toasts = inject(Toasts);
  list = signal<TitleView[]>([]);
  history = signal<{ p: Progress; t: TitleView }[]>([]);
  loading = signal(true);
  error = signal<string | null>(null);

  ngOnInit() { this.load(); }

  async load() {
    this.loading.set(true); this.error.set(null);
    try {
      const [wl, hist] = await Promise.all([this.api.watchlist(), this.api.history()]);
      const titles = await this.api.titlesBatch([...new Set([...wl.map(w => w.titleId!), ...hist.map(h => h.titleId!)])]);
      const byId = new Map(titles.map(t => [t.id!, t]));
      this.list.set(wl.map(w => byId.get(w.titleId!)).filter((t): t is TitleView => !!t));
      this.history.set(hist.map(p => ({ p, t: byId.get(p.titleId!)! })).filter(x => !!x.t));
    } catch (e) { this.error.set(problemMessage(e)); } finally { this.loading.set(false); }
  }

  async clear() {
    if (!confirm('Erase your entire watch history? This cannot be undone.')) return;
    await this.api.clearHistory();
    this.history.set([]);
    this.toasts.show('Watch history erased', 'success');
  }
}
