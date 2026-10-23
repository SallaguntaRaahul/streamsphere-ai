import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Api, GenreCount, SearchResults } from '../core/api';
import { problemMessage } from '../core/http';
import { StateView, formatDuration } from '../ui/ui';

@Component({
  selector: 'ss-search',
  imports: [FormsModule, RouterLink, StateView],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page pt-28">
      <p class="eyebrow">Discover</p>
      <h1 class="mt-2 text-3xl font-bold sm:text-4xl">Search by title, creator — or by feeling</h1>
      <p class="mt-2 text-ink-300">Try “quiet films about the sea” or “space comedy”. Semantic search runs on a local open-weight model.</p>
      <form class="mt-6 flex flex-col gap-3 sm:flex-row" role="search" (ngSubmit)="go()">
        <label for="q" class="sr-only">Search</label>
        <input id="q" name="q" class="field !py-3 text-base" [(ngModel)]="q" placeholder="Search StreamSphere" autocomplete="off" maxlength="200" (input)="onType()" />
        <label for="genre" class="sr-only">Genre</label>
        <select id="genre" name="genre" class="field !w-auto !py-3" [(ngModel)]="genre" (change)="go()">
          <option value="">All genres</option>
          @for (g of genres(); track g.genre) { <option [value]="g.genre">{{ g.genre }}</option> }
        </select>
        <button class="btn-accent !py-3" type="submit">Search</button>
      </form>
      @if (suggestions().length) {
        <ul class="mt-2 flex flex-wrap gap-2" aria-label="Suggestions">
          @for (s of suggestions(); track s) { <li><button class="chip hover:bg-white/15" (click)="q = s; go()">{{ s }}</button></li> }
        </ul>
      }
      <fieldset class="mt-4 flex flex-wrap items-center gap-2 text-sm">
        <legend class="sr-only">Search mode</legend>
        @for (m of modes; track m.v) {
          <label class="chip cursor-pointer !px-3 !py-1" [class.chip-on]="mode === m.v">
            <input type="radio" name="mode" class="sr-only" [value]="m.v" [(ngModel)]="mode" (change)="go()" />{{ m.label }}
          </label>
        }
      </fieldset>

      @if (error()) { <ss-state kind="error" heading="Search failed" [message]="error()" action="Retry" (act)="go()" /> }
      @if (res(); as r) {
        <p class="mt-6 text-sm text-ink-400" role="status">{{ r.items!.length }} results in {{ r.tookMs }} ms
          @if (r.semanticAvailable) { · semantic via {{ r.embeddingModel }} } @else if (mode !== 'keyword') { · semantic unavailable, keyword only }</p>
        <ul class="mt-4 grid gap-4 md:grid-cols-2">
          @for (h of r.items; track h.titleId) {
            <li><a [routerLink]="['/title', h.titleId]" class="card flex h-full flex-col gap-2 p-5 transition hover:ring-aurora-500/60 animate-rise">
              <div class="flex items-start justify-between gap-3">
                <h2 class="font-display text-lg font-semibold">{{ h.title }}</h2>
                <span class="flex shrink-0 gap-1">
                  @if (h.keywordRank) { <span class="chip !bg-aurora-500/20" title="Matched keywords">keyword</span> }
                  @if (h.semanticRank) { <span class="chip !bg-lagoon-400/15" title="Matched meaning">meaning</span> }
                  @if (h.fuzzy) { <span class="chip !bg-ember-400/15" title="Matched despite a typo">fuzzy</span> }
                </span>
              </div>
              <p class="text-xs text-ink-400">{{ h.releaseYear }} · {{ fmt(h.durationSeconds) }} · {{ h.creatorName }} · {{ h.genres!.join(', ') }}</p>
              <p class="line-clamp-2 text-sm text-ink-200">{{ h.synopsis }}</p>
            </a></li>
          }
        </ul>
        @if (!r.items!.length) { <ss-state heading="No matches" message="Try fewer words, a different genre, or semantic mode." /> }
      }
      @if (loading()) { <div class="mt-6 grid gap-4 md:grid-cols-2">@for (i of [1,2,3,4]; track i) {<div class="skeleton h-28"></div>}</div> }
    </div>`,
})
export class SearchPage implements OnInit {
  private api = inject(Api);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  q = '';
  genre = '';
  mode = 'hybrid';
  modes = [{ v: 'hybrid', label: 'Hybrid' }, { v: 'semantic', label: 'Meaning' }, { v: 'keyword', label: 'Exact words' }];
  res = signal<SearchResults | null>(null);
  genres = signal<GenreCount[]>([]);
  suggestions = signal<string[]>([]);
  loading = signal(false);
  error = signal<string | null>(null);
  fmt = formatDuration;
  private typeTimer?: ReturnType<typeof setTimeout>;

  ngOnInit() {
    this.api.genres().then(g => this.genres.set(g)).catch(() => {});
    this.route.queryParamMap.subscribe(p => {
      this.q = p.get('q') ?? ''; this.genre = p.get('genre') ?? ''; this.mode = p.get('mode') ?? 'hybrid';
      this.run();
    });
  }

  go() { this.suggestions.set([]); this.router.navigate([], { queryParams: { q: this.q || null, genre: this.genre || null, mode: this.mode } }); }

  onType() {
    clearTimeout(this.typeTimer);
    this.typeTimer = setTimeout(() => this.q.length >= 2 ? this.api.suggest(this.q).then(s => this.suggestions.set(s)).catch(() => {}) : this.suggestions.set([]), 200);
  }

  private async run() {
    this.loading.set(true); this.error.set(null);
    try { this.res.set(await this.api.search(this.q, this.mode, this.genre || undefined)); }
    catch (e) { this.error.set(problemMessage(e)); }
    finally { this.loading.set(false); }
  }
}
