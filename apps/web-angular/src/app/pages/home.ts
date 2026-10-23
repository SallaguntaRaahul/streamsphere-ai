import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Api, GenreCount, Progress, Recommendation, TitleView } from '../core/api';
import { AuthService } from '../core/auth.service';
import { problemMessage } from '../core/http';
import { Row, SkeletonRow, StateView, TitleCard, formatDuration } from '../ui/ui';

@Component({
  selector: 'ss-home',
  imports: [RouterLink, Row, TitleCard, SkeletonRow, StateView],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (error()) {
      <div class="page pt-32"><ss-state kind="error" heading="The catalog is unavailable" [message]="error()" action="Try again" (act)="load()" /></div>
    } @else if (featured(); as f) {
      <section class="relative isolate min-h-[78vh] overflow-hidden" aria-labelledby="hero-title">
        @if (api.posterUrl(f.posterPath)) {
          <img [src]="api.posterUrl(f.posterPath)" alt="" class="absolute inset-0 -z-20 h-full w-full scale-105 object-cover opacity-70 blur-[1px]" />
        }
        <div class="absolute inset-0 -z-10 bg-[radial-gradient(ellipse_at_top_left,rgba(124,92,255,.35),transparent_55%),linear-gradient(to_top,#07080d_8%,rgba(7,8,13,.55)_55%,rgba(7,8,13,.25))]"></div>
        <div class="page flex min-h-[78vh] flex-col justify-end pb-20 pt-32">
          <p class="eyebrow animate-rise">Featured independent premiere</p>
          <h1 id="hero-title" class="mt-3 max-w-3xl text-4xl font-bold text-white sm:text-6xl animate-rise">{{ f.title }}</h1>
          <p class="mt-3 flex flex-wrap items-center gap-2 text-sm text-ink-300 animate-rise">
            <span>{{ f.releaseYear }}</span><span aria-hidden="true">·</span><span class="chip">{{ f.maturityRating }}</span>
            @if (f.durationSeconds) { <span aria-hidden="true">·</span><span>{{ fmt(f.durationSeconds) }}</span> }
            <span aria-hidden="true">·</span><span>by {{ f.creatorName }}</span>
          </p>
          <p class="mt-4 max-w-2xl text-base leading-relaxed text-ink-100 sm:text-lg animate-rise">{{ f.synopsis }}</p>
          @if (featuredReason()) { <p class="mt-3 text-sm text-lagoon-300">✦ {{ featuredReason() }}</p> }
          <div class="mt-7 flex flex-wrap gap-3 animate-rise">
            <button class="btn-primary !px-7 !py-3 text-base" (click)="play(f.id!)">▶ Play</button>
            <a class="btn-ghost !px-6 !py-3 text-base" [routerLink]="['/title', f.id]">More info</a>
          </div>
        </div>
      </section>
    } @else {
      <div class="page pt-32"><div class="skeleton h-[50vh] w-full"></div></div>
    }

    @if (continueItems().length) {
      <ss-row heading="Continue watching" caption="Pick up exactly where you left off">
        @for (c of continueItems(); track c.t.id) {
          <div role="listitem" class="w-64 shrink-0 snap-start sm:w-72"><ss-title-card [id]="c.t.id!" [title]="c.t.title!" [poster]="api.posterUrl(c.t.posterPath)"
            [subtitle]="remaining(c.p)" [progress]="c.p.positionSeconds! / c.p.durationSeconds!" /></div>
        }
      </ss-row>
    }

    @if (recs().length) {
      <ss-row [heading]="recStrategy() === 'personalized' ? 'Recommended for you' : 'Trending now'" [caption]="'Explained, not mysterious — ' + recModel()">
        @for (r of recs(); track r.titleId) {
          <div role="listitem" class="w-64 shrink-0 snap-start sm:w-72">
            <ss-title-card [id]="r.titleId" [title]="r.title" [poster]="posterFor(r.titleId)" [subtitle]="r.reasons[0]" />
          </div>
        }
      </ss-row>
    }

    @if (loading()) { <ss-skeleton-row /><ss-skeleton-row /> }

    @for (g of genreRows(); track g.genre) {
      <ss-row [heading]="label(g.genre)" [caption]="g.items.length + ' titles'">
        @for (t of g.items; track t.id) {
          <div role="listitem" class="w-64 shrink-0 snap-start sm:w-72"><ss-title-card [id]="t.id!" [title]="t.title!" [poster]="api.posterUrl(t.posterPath)" [subtitle]="t.creatorName ?? null" /></div>
        }
      </ss-row>
    }

    @if (!loading() && !error() && titles().length === 0) {
      <div class="page pt-10"><ss-state heading="No titles yet" message="Be the first: open the creator studio and publish an original short film." /></div>
    }

    @if (genres().length) {
      <section class="page mt-14" aria-labelledby="genres-h">
        <h2 id="genres-h" class="text-xl font-semibold">Browse by genre</h2>
        <ul class="mt-4 flex flex-wrap gap-2">
          @for (g of genres(); track g.genre) { <li><a [routerLink]="['/browse', g.genre]" class="chip !px-4 !py-1.5 text-sm hover:bg-aurora-500/30">{{ label(g.genre!) }} <span class="ml-1.5 text-ink-400">{{ g.titles }}</span></a></li> }
        </ul>
      </section>
    }`,
})
export class HomePage implements OnInit {
  protected readonly api = inject(Api);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  titles = signal<TitleView[]>([]);
  genres = signal<GenreCount[]>([]);
  recs = signal<Recommendation[]>([]);
  recStrategy = signal('popularity');
  recModel = signal('');
  progress = signal<Progress[]>([]);
  loading = signal(true);
  error = signal<string | null>(null);
  byId = computed(() => new Map(this.titles().map(t => [t.id!, t])));
  featured = computed(() => (this.recs().length ? this.byId().get(this.recs()[0].titleId) : undefined) ?? this.titles()[0]);
  featuredReason = computed(() => this.recs()[0]?.titleId === this.featured()?.id ? this.recs()[0]?.reasons[0] : null);
  continueItems = computed(() => this.progress().map(p => ({ p, t: this.byId().get(p.titleId!) })).filter((x): x is { p: Progress; t: TitleView } => !!x.t));
  genreRows = computed(() => this.genres().slice(0, 5).map(g => ({ genre: g.genre!, items: this.titles().filter(t => t.genres?.includes(g.genre!)) })).filter(g => g.items.length));

  ngOnInit() { this.load(); }

  async load() {
    this.loading.set(true);
    this.error.set(null);
    try {
      const [page, genres] = await Promise.all([this.api.titles(undefined, undefined, 60), this.api.genres()]);
      this.titles.set(page.items);
      this.genres.set(genres);
    } catch (e) {
      this.error.set(problemMessage(e));
    } finally {
      this.loading.set(false);
    }
    this.api.recommendations(12).then(r => { this.recs.set(r.items); this.recStrategy.set(r.strategy); this.recModel.set(r.embeddingModel); }).catch(() => {});
    if (this.auth.isAuthenticated()) this.api.continueWatching().then(p => this.progress.set(p)).catch(() => {});
  }

  posterFor(id: string) { return this.api.posterUrl(this.byId().get(id)?.posterPath); }
  play(id: string) { this.router.navigate(['/watch', id]); }
  fmt = formatDuration;
  label(g: string) { return g.replace(/-/g, ' ').replace(/\b\w/g, c => c.toUpperCase()); }
  remaining(p: Progress) { return `${Math.max(1, Math.round((p.durationSeconds! - p.positionSeconds!) / 60))} min left`; }
}
