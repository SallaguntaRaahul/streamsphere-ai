import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { DecimalPipe, PercentPipe } from '@angular/common';
import { Api, TitleStats } from '../../core/api';
import { problemMessage } from '../../core/http';
import { StateView } from '../../ui/ui';

@Component({
  selector: 'ss-creator-analytics',
  imports: [StateView, DecimalPipe, PercentPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page pt-28">
      <p class="eyebrow">Creator analytics</p>
      <h1 class="mt-2 text-3xl font-bold">How your films are doing</h1>
      <p class="mt-2 text-sm text-ink-400">Real playback events only. Viewer counts under 5 are hidden to protect privacy.</p>
      <div class="mt-4 flex gap-2" role="group" aria-label="Period">
        @for (d of [7, 30, 90]; track d) { <button class="chip !px-3 !py-1" [class.chip-on]="days() === d" [attr.aria-pressed]="days() === d" (click)="setDays(d)">{{ d }} days</button> }
      </div>
      @if (error()) { <ss-state kind="error" heading="Analytics unavailable" [message]="error()" /> }
      <div class="mt-6 grid gap-4 sm:grid-cols-4">
        <div class="card p-5"><p class="label">Views</p><p class="text-3xl font-bold">{{ totals().views }}</p></div>
        <div class="card p-5"><p class="label">Completions</p><p class="text-3xl font-bold">{{ totals().completions }}</p></div>
        <div class="card p-5"><p class="label">Watch time</p><p class="text-3xl font-bold">{{ totals().watch / 60 | number:'1.0-0' }} min</p></div>
        <div class="card p-5"><p class="label">Titles</p><p class="text-3xl font-bold">{{ stats().length }}</p></div>
      </div>
      <div class="mt-6 grid gap-4 lg:grid-cols-2">
        @for (s of stats(); track s.titleId) {
          <section class="card p-5" [attr.aria-label]="s.title">
            <h2 class="font-display font-semibold">{{ s.title }}</h2>
            <dl class="mt-3 grid grid-cols-3 gap-3 text-sm">
              <div><dt class="text-ink-400">Views</dt><dd class="text-lg font-semibold">{{ s.views }}</dd></div>
              <div><dt class="text-ink-400">Unique viewers</dt><dd class="text-lg font-semibold">{{ s.uniqueViewers }}</dd></div>
              <div><dt class="text-ink-400">Completion</dt><dd class="text-lg font-semibold">{{ s.completionRate | percent:'1.0-0' }}</dd></div>
              <div><dt class="text-ink-400">Avg startup</dt><dd>{{ s.avgStartupMs ?? '—' }} ms</dd></div>
              <div><dt class="text-ink-400">Rebuffer ratio</dt><dd>{{ s.rebufferRatio == null ? '—' : (s.rebufferRatio | percent:'1.1-2') }}</dd></div>
              <div><dt class="text-ink-400">Watch time</dt><dd>{{ s.watchSeconds! / 60 | number:'1.0-0' }} min</dd></div>
            </dl>
            @if (s.daily!.length) {
              <svg class="mt-4 h-24 w-full" [attr.viewBox]="'0 0 ' + s.daily!.length * 14 + ' 60'" preserveAspectRatio="none" role="img" [attr.aria-label]="'Daily views for ' + s.title">
                @for (d of s.daily; track d.day; let i = $index) {
                  <rect [attr.x]="i * 14 + 2" [attr.y]="60 - bar(d.views!, s)" width="10" [attr.height]="bar(d.views!, s)" rx="2" fill="#7c5cff"><title>{{ d.day }}: {{ d.views }} views</title></rect>
                }
              </svg>
            }
          </section>
        } @empty { @if (!loading()) { <ss-state heading="No data yet" message="Analytics appear once a published title is played." /> } }
      </div>
    </div>`,
})
export class CreatorAnalyticsPage implements OnInit {
  private api = inject(Api);
  stats = signal<TitleStats[]>([]);
  days = signal(30);
  loading = signal(true);
  error = signal<string | null>(null);
  totals = computed(() => this.stats().reduce<{ views: number; completions: number; watch: number }>((a, s) => ({ views: a.views + (s.views ?? 0), completions: a.completions + (s.completions ?? 0), watch: a.watch + (s.watchSeconds ?? 0) }), { views: 0, completions: 0, watch: 0 }));
  ngOnInit() { this.load(); }
  setDays(d: number) { this.days.set(d); this.load(); }
  async load() {
    this.loading.set(true);
    try { this.stats.set(await this.api.creatorStats(this.days())); } catch (e) { this.error.set(problemMessage(e)); } finally { this.loading.set(false); }
  }
  bar(v: number, s: TitleStats) { const max = Math.max(1, ...s.daily!.map(d => d.views!)); return Math.max(2, (v / max) * 56); }
}
