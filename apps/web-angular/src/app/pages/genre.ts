import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';
import { Api, TitleView } from '../core/api';
import { problemMessage } from '../core/http';
import { StateView, TitleCard } from '../ui/ui';

@Component({
  selector: 'ss-genre',
  imports: [TitleCard, StateView],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page pt-28">
      <p class="eyebrow">Collection</p>
      <h1 class="mt-2 text-3xl font-bold sm:text-4xl">{{ label() }}</h1>
      @if (error()) { <ss-state kind="error" heading="Couldn't load this collection" [message]="error()" action="Retry" (act)="load(true)" /> }
      <ul class="mt-8 grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
        @for (t of items(); track t.id) { <li><ss-title-card [id]="t.id!" [title]="t.title!" [poster]="api.posterUrl(t.posterPath)" [subtitle]="(t.releaseYear ?? '') + ' · ' + t.creatorName" /></li> }
        @if (loading()) { @for (i of [1,2,3,4]; track i) { <li class="skeleton aspect-video"></li> } }
      </ul>
      @if (!loading() && !items().length && !error()) { <ss-state heading="Nothing here yet" message="No published titles in this genre." /> }
      @if (next()) { <div class="mt-8 text-center"><button class="btn-ghost" (click)="load(false)">Load more</button></div> }
    </div>`,
})
export class GenrePage {
  protected api = inject(Api);
  genre = input.required<string>();
  items = signal<TitleView[]>([]);
  next = signal<string | null>(null);
  loading = signal(false);
  error = signal<string | null>(null);
  constructor() { effect(() => { this.genre(); this.load(true); }); }
  label() { return this.genre().replace(/-/g, ' ').replace(/\b\w/g, c => c.toUpperCase()); }
  async load(reset: boolean) {
    this.loading.set(true); this.error.set(null);
    try {
      const p = await this.api.titles(this.genre(), reset ? undefined : this.next() ?? undefined, 24);
      this.items.set(reset ? p.items : [...this.items(), ...p.items]);
      this.next.set(p.nextCursor ?? null);
    } catch (e) { this.error.set(problemMessage(e)); } finally { this.loading.set(false); }
  }
}
