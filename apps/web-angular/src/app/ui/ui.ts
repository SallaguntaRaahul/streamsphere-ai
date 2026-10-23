import { ChangeDetectionStrategy, Component, ElementRef, Injectable, computed, input, output, signal, viewChild } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'ss-logo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="inline-flex items-center gap-2.5">
      <svg viewBox="0 0 64 64" class="h-8 w-8 shrink-0" aria-hidden="true">
        <defs><radialGradient id="ssg" cx="35%" cy="30%" r="75%"><stop offset="0" stop-color="#b9a8ff"/><stop offset=".55" stop-color="#7c5cff"/><stop offset="1" stop-color="#1c1340"/></radialGradient></defs>
        <circle cx="32" cy="32" r="18" fill="url(#ssg)"/>
        <ellipse cx="32" cy="32" rx="29" ry="9" fill="none" stroke="#2ee6c5" stroke-width="3" transform="rotate(-20 32 32)"/>
      </svg>
      <span class="font-display text-lg font-bold tracking-tight">Stream<span class="text-aurora-300">Sphere</span></span>
    </span>`,
})
export class Logo {}

/** Poster card. Falls back to a generated gradient when no poster exists. */
@Component({
  selector: 'ss-title-card',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a [routerLink]="['/title', id()]" class="group relative block overflow-hidden rounded-xl bg-ink-800 ring-1 ring-white/8 transition duration-300 hover:-translate-y-1 hover:shadow-glow focus-visible:shadow-glow">
      <div class="aspect-video w-full overflow-hidden">
        @if (poster()) {
          <img [src]="poster()" [alt]="''" loading="lazy" decoding="async" class="h-full w-full object-cover transition duration-500 group-hover:scale-105" />
        } @else {
          <div class="h-full w-full" [style.background]="fallback()"></div>
        }
      </div>
      <div class="pointer-events-none absolute inset-0 bg-gradient-to-t from-ink-950 via-ink-950/20 to-transparent"></div>
      @if (progress() > 0) {
        <div class="absolute inset-x-0 bottom-0 h-1 bg-white/15" aria-hidden="true"><div class="h-full bg-lagoon-400" [style.width.%]="progress() * 100"></div></div>
      }
      <div class="absolute inset-x-0 bottom-0 p-3">
        <p class="truncate font-display text-sm font-semibold text-white">{{ title() }}</p>
        @if (subtitle()) { <p class="truncate text-xs text-ink-300">{{ subtitle() }}</p> }
      </div>
    </a>`,
})
export class TitleCard {
  id = input.required<string>();
  title = input.required<string>();
  subtitle = input<string | null>(null);
  poster = input<string | null>(null);
  progress = input(0);
  fallback = computed(() => {
    let h = 0;
    for (const c of this.id()) h = (h * 31 + c.charCodeAt(0)) % 360;
    return `linear-gradient(135deg, hsl(${h} 70% 35%), hsl(${(h + 60) % 360} 70% 18%))`;
  });
}

/** Horizontally scrolling row with keyboard-accessible scroll buttons. */
@Component({
  selector: 'ss-row',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="mt-10" [attr.aria-labelledby]="headingId">
      <div class="page mb-3 flex items-end justify-between gap-4">
        <div>
          <h2 [id]="headingId" class="text-xl font-semibold text-white sm:text-2xl">{{ heading() }}</h2>
          @if (caption()) { <p class="mt-0.5 text-sm text-ink-400">{{ caption() }}</p> }
        </div>
        <div class="hidden gap-2 sm:flex">
          <button class="btn-ghost h-9 w-9 !p-0" (click)="scroll(-1)" [attr.aria-label]="'Scroll ' + heading() + ' left'">‹</button>
          <button class="btn-ghost h-9 w-9 !p-0" (click)="scroll(1)" [attr.aria-label]="'Scroll ' + heading() + ' right'">›</button>
        </div>
      </div>
      <div #track class="no-scrollbar page flex snap-x gap-4 overflow-x-auto pb-4" role="list">
        <ng-content />
      </div>
    </section>`,
})
export class Row {
  heading = input.required<string>();
  caption = input<string | null>(null);
  track = viewChild.required<ElementRef<HTMLElement>>('track');
  headingId = 'row-' + Math.random().toString(36).slice(2, 8);
  scroll(dir: number) { const el = this.track().nativeElement; el.scrollBy({ left: dir * el.clientWidth * 0.85, behavior: 'smooth' }); }
}

@Component({
  selector: 'ss-skeleton-row',
  template: `<div class="page mt-10" aria-busy="true" aria-label="Loading"><div class="skeleton mb-3 h-6 w-48"></div>
    <div class="flex gap-4 overflow-hidden">@for (i of [1,2,3,4,5]; track i) {<div class="skeleton aspect-video w-64 shrink-0"></div>}</div></div>`,
})
export class SkeletonRow {}

@Component({
  selector: 'ss-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="card mx-auto my-10 max-w-lg p-8 text-center animate-rise" [attr.role]="kind() === 'error' ? 'alert' : 'status'">
      <div class="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full" [class]="kind() === 'error' ? 'bg-rose-400/15 text-rose-400' : 'bg-aurora-500/15 text-aurora-300'" aria-hidden="true">
        {{ kind() === 'error' ? '!' : '✦' }}
      </div>
      <h2 class="text-lg font-semibold">{{ heading() }}</h2>
      @if (message()) { <p class="mt-2 text-sm text-ink-300">{{ message() }}</p> }
      @if (action()) { <button class="btn-ghost mt-5" (click)="act.emit()">{{ action() }}</button> }
      <ng-content />
    </div>`,
})
export class StateView {
  kind = input<'empty' | 'error'>('empty');
  heading = input.required<string>();
  message = input<string | null>(null);
  action = input<string | null>(null);
  act = output<void>();
}

export interface Toast { id: number; text: string; tone: 'info' | 'success' | 'error' }

@Injectable({ providedIn: 'root' })
export class Toasts {
  readonly items = signal<Toast[]>([]);
  private n = 0;
  show(text: string, tone: Toast['tone'] = 'info') {
    const id = ++this.n;
    this.items.update(t => [...t, { id, text, tone }]);
    setTimeout(() => this.items.update(t => t.filter(x => x.id !== id)), 5000);
  }
}

export function formatDuration(s?: number | null) {
  if (!s) return '';
  const m = Math.round(s / 60);
  return m < 1 ? `${s}s` : m < 60 ? `${m} min` : `${Math.floor(m / 60)}h ${m % 60}m`;
}

export function formatBytes(b?: number | null) {
  if (b == null) return '—';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0, v = b;
  while (v >= 1000 && i < u.length - 1) { v /= 1000; i++; }
  return `${v.toFixed(v < 10 && i ? 1 : 0)} ${u[i]}`;
}
