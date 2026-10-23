import { ChangeDetectionStrategy, Component, ElementRef, HostListener, OnDestroy, effect, inject, input, signal, viewChild } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import Hls, { ErrorData, Level } from 'hls.js';
import { Api, PlaybackSession, TitleView } from '../core/api';
import { AuthService } from '../core/auth.service';
import { problemMessage } from '../core/http';
import { env } from '../env';
import { StateView } from '../ui/ui';

interface CaptionOption { index: number; label: string; lang: string }

/**
 * Adaptive HLS player. hls.js where MSE exists; Safari/iOS use native HLS. Every playlist and segment
 * request is authorized by the playback service; segments arrive via 60-s presigned redirects.
 */
@Component({
  selector: 'ss-watch',
  imports: [RouterLink, StateView],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="fixed inset-0 z-50 bg-black" [class.chrome-on]="chrome() || paused()" (mousemove)="poke()" (touchstart)="poke()">
      @if (fatal()) {
        <div class="grid h-full place-items-center p-6"><ss-state kind="error" heading="Playback unavailable" [message]="fatal()">
          <a [routerLink]="['/title', id()]" class="btn-primary mt-5">Back to title</a></ss-state></div>
      } @else {
        <video #videoEl class="h-full w-full bg-black" playsinline [attr.aria-label]="'Video: ' + (title()?.title ?? '')"
               (click)="toggle()" (timeupdate)="onTime()" (play)="paused.set(false)" (pause)="onPause()" (ended)="onEnded()"
               (waiting)="onWaiting()" (playing)="onPlaying()" (loadedmetadata)="onMeta()" (volumechange)="muted.set(video().nativeElement.muted)"></video>

        @if (buffering()) { <div class="pointer-events-none absolute inset-0 grid place-items-center" role="status" aria-label="Buffering"><div class="h-14 w-14 animate-spin rounded-full border-4 border-white/20 border-t-lagoon-400"></div></div> }

        <div class="absolute inset-0 flex flex-col justify-between transition-opacity duration-300" [class.opacity-0]="!chrome() && !paused()" [class.pointer-events-none]="!chrome() && !paused()">
          <div class="flex items-center gap-4 bg-gradient-to-b from-black/80 to-transparent p-4 sm:p-6">
            <a [routerLink]="['/title', id()]" class="btn-ghost h-10 w-10 !p-0" aria-label="Back to title details">←</a>
            <div class="min-w-0">
              <p class="truncate font-display text-lg font-semibold">{{ title()?.title }}</p>
              @if (session()?.preview) { <p class="text-xs text-ember-400">Preview (not public)</p> }
            </div>
          </div>
          <div class="bg-gradient-to-t from-black/90 to-transparent p-4 sm:p-6">
            <label class="sr-only" for="seek">Seek</label>
            <input id="seek" type="range" min="0" [max]="duration() || 0" step="0.1" [value]="current()" (input)="seek($any($event.target).valueAsNumber)"
                   class="w-full accent-lagoon-400" [attr.aria-valuetext]="clock(current()) + ' of ' + clock(duration())" />
            <div class="mt-3 flex flex-wrap items-center gap-2 text-sm">
              <button class="btn-ghost h-10 w-10 !p-0" (click)="toggle()" [attr.aria-label]="paused() ? 'Play (k)' : 'Pause (k)'">{{ paused() ? '▶' : '❚❚' }}</button>
              <button class="btn-ghost h-10 w-10 !p-0" (click)="skip(-10)" aria-label="Back 10 seconds">↺</button>
              <button class="btn-ghost h-10 w-10 !p-0" (click)="skip(10)" aria-label="Forward 10 seconds">↻</button>
              <button class="btn-ghost h-10 w-10 !p-0" (click)="toggleMute()" [attr.aria-label]="muted() ? 'Unmute (m)' : 'Mute (m)'">{{ muted() ? '🔇' : '🔊' }}</button>
              <span class="tabular-nums text-ink-100" aria-live="off">{{ clock(current()) }} / {{ clock(duration()) }}</span>
              <div class="ml-auto flex items-center gap-2">
                <label class="sr-only" for="cc">Captions</label>
                <select id="cc" class="field !w-auto !py-1.5" [value]="captionIndex()" (change)="setCaptions(+$any($event.target).value)">
                  <option value="-1">Captions off</option>
                  @for (c of captions(); track c.index) { <option [value]="c.index">{{ c.label }}</option> }
                </select>
                @if (levels().length) {
                  <label class="sr-only" for="q">Quality</label>
                  <select id="q" class="field !w-auto !py-1.5" [value]="levelChoice()" (change)="setLevel(+$any($event.target).value)">
                    <option value="-1">Auto{{ autoLabel() }}</option>
                    @for (l of levels(); track $index) { <option [value]="$index">{{ l.height }}p</option> }
                  </select>
                }
                <button class="btn-ghost h-10 w-10 !p-0" (click)="fullscreen()" aria-label="Full screen (f)">⛶</button>
              </div>
            </div>
          </div>
        </div>
      }
    </div>`,
})
export class WatchPage implements OnDestroy {
  private api = inject(Api);
  private auth = inject(AuthService);
  private router = inject(Router);
  id = input.required<string>();
  video = viewChild.required<ElementRef<HTMLVideoElement>>('videoEl');
  title = signal<TitleView | null>(null);
  session = signal<PlaybackSession | null>(null);
  fatal = signal<string | null>(null);
  paused = signal(true);
  muted = signal(false);
  buffering = signal(false);
  chrome = signal(true);
  current = signal(0);
  duration = signal(0);
  levels = signal<Level[]>([]);
  levelChoice = signal(-1);
  autoLabel = signal('');
  captions = signal<CaptionOption[]>([]);
  captionIndex = signal(-1);

  private hls?: Hls;
  private captionsByDefault = false;
  private resumeAt = 0;
  private lastSaved = 0;
  private chromeTimer?: ReturnType<typeof setTimeout>;
  private refreshTimer?: ReturnType<typeof setTimeout>;
  private qoeTimer?: ReturnType<typeof setInterval>;
  // QoE accounting
  private requestedAt = 0;
  private startupMs: number | null = null;
  private waitingSince = 0;
  private rebufferMs = 0;
  private switches = 0;
  private lastPlayedSec = 0;
  private errorCode: string | null = null;

  constructor() {
    effect(() => { const id = this.id(); queueMicrotask(() => this.start(id)); });
  }

  private async start(id: string) {
    try {
      const [t, p, prof] = await Promise.all([this.api.title(id).catch(() => null), this.api.progress(id).catch(() => null),
        this.api.profile().catch(() => null)]);
      this.captionsByDefault = !!prof?.body?.captionsDefault; // honour the viewer's accessibility preference
      this.title.set(t);
      this.resumeAt = p && !p.completed ? p.positionSeconds ?? 0 : 0;
      await this.openSession(id, this.resumeAt);
      this.qoeTimer = setInterval(() => this.flushQoe(), 30_000);
    } catch (e) {
      this.fatal.set(problemMessage(e));
    }
  }

  private async openSession(id: string, startAt: number) {
    const s = await this.api.startPlayback(id);
    this.session.set(s);
    if (!this.title()) this.title.set({ id, title: 'Preview' } as TitleView);
    const src = env.apiBase + s.manifestUrl;
    const v = this.video().nativeElement;
    this.requestedAt = performance.now();
    if (Hls.isSupported()) {
      this.hls?.destroy();
      const hls = new Hls({ startPosition: startAt, capLevelToPlayerSize: true, enableWebVTT: true, renderTextTracksNatively: true, maxBufferLength: 30 });
      this.hls = hls;
      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        this.levels.set(hls.levels);
        v.play().catch(() => this.paused.set(true));
      });
      // subtitle tracks are populated after MANIFEST_PARSED in hls.js 1.7 — listen for the dedicated event
      hls.on(Hls.Events.SUBTITLE_TRACKS_UPDATED, (_e, d) => {
        this.captions.set(d.subtitleTracks.map((t, i) => ({ index: i, label: t.name, lang: t.lang ?? '' })));
        const preferred = d.subtitleTracks.findIndex(t => t.default);
        if (this.captionIndex() < 0 && (preferred >= 0 || this.captionsByDefault)) this.setCaptions(preferred >= 0 ? preferred : 0);
      });
      hls.on(Hls.Events.LEVEL_SWITCHED, (_e, d) => { this.switches++; this.autoLabel.set(` (${hls.levels[d.level]?.height}p)`); });
      hls.on(Hls.Events.ERROR, (_e, d: ErrorData) => this.onHlsError(d));
      hls.loadSource(src);
      hls.attachMedia(v);
    } else if (v.canPlayType('application/vnd.apple.mpegurl')) {
      v.src = src; // Safari/iOS native HLS (also renders the SUBTITLES group natively)
      v.addEventListener('loadedmetadata', () => { if (startAt) v.currentTime = startAt; v.play().catch(() => {}); }, { once: true });
    } else {
      throw new Error('This browser cannot play HLS video.');
    }
    // refresh authorization one minute before the session token expires
    clearTimeout(this.refreshTimer);
    const ms = new Date(s.expiresAt!).getTime() - Date.now() - 60_000;
    this.refreshTimer = setTimeout(() => this.openSession(id, this.video().nativeElement.currentTime).catch(e => this.fatal.set(problemMessage(e))), Math.max(10_000, ms));
  }

  private onHlsError(d: ErrorData) {
    const status = (d.response as { code?: number } | undefined)?.code;
    if (status === 403) { this.errorCode = 'revoked'; this.fatal.set('This title is no longer available or your session was revoked.'); this.hls?.destroy(); return; }
    if (!d.fatal) return;
    this.errorCode = d.details;
    if (d.type === Hls.ErrorTypes.NETWORK_ERROR) this.hls?.startLoad();
    else if (d.type === Hls.ErrorTypes.MEDIA_ERROR) this.hls?.recoverMediaError();
    else this.fatal.set('Playback failed: ' + d.details);
  }

  // ---- events
  onMeta() { this.duration.set(this.video().nativeElement.duration || this.session()?.durationSeconds || 0); }
  onTime() {
    const v = this.video().nativeElement;
    this.current.set(v.currentTime);
    if (Math.abs(v.currentTime - this.lastSaved) >= 10) this.save();
  }
  onWaiting() { this.buffering.set(true); this.waitingSince = performance.now(); }
  onPlaying() {
    this.buffering.set(false);
    this.paused.set(false);
    if (this.startupMs === null) this.startupMs = Math.round(performance.now() - this.requestedAt);
    else if (this.waitingSince) this.rebufferMs += performance.now() - this.waitingSince;
    this.waitingSince = 0;
  }
  onPause() { this.paused.set(true); this.save(); }
  onEnded() { this.save(); this.flushQoe(); }

  // ---- controls
  toggle() { const v = this.video().nativeElement; v.paused ? v.play() : v.pause(); this.poke(); }
  skip(s: number) { const v = this.video().nativeElement; v.currentTime = Math.max(0, Math.min(v.duration || 0, v.currentTime + s)); this.poke(); }
  seek(t: number) { this.video().nativeElement.currentTime = t; }
  toggleMute() { const v = this.video().nativeElement; v.muted = !v.muted; }
  fullscreen() { const el = this.video().nativeElement.parentElement!; document.fullscreenElement ? document.exitFullscreen() : el.requestFullscreen?.(); }
  setLevel(i: number) { this.levelChoice.set(i); if (this.hls) this.hls.currentLevel = i; }
  setCaptions(i: number) {
    this.captionIndex.set(i);
    if (this.hls) { this.hls.subtitleTrack = i; this.hls.subtitleDisplay = i >= 0; }
    const tracks = this.video().nativeElement.textTracks;
    for (let k = 0; k < tracks.length; k++) tracks[k].mode = k === i ? 'showing' : 'disabled';
  }
  poke() {
    this.chrome.set(true);
    clearTimeout(this.chromeTimer);
    this.chromeTimer = setTimeout(() => this.chrome.set(false), 3000);
  }

  @HostListener('document:keydown', ['$event'])
  keys(e: KeyboardEvent) {
    if ((e.target as HTMLElement)?.tagName === 'SELECT' || (e.target as HTMLElement)?.tagName === 'INPUT') return;
    const k = e.key.toLowerCase();
    if (k === ' ' || k === 'k') { e.preventDefault(); this.toggle(); }
    else if (k === 'arrowleft') this.skip(-10);
    else if (k === 'arrowright') this.skip(10);
    else if (k === 'm') this.toggleMute();
    else if (k === 'f') this.fullscreen();
    else if (k === 'c' && this.captions().length) this.setCaptions(this.captionIndex() >= 0 ? -1 : 0);
    else if (k === 'escape' && !document.fullscreenElement) this.router.navigate(['/title', this.id()]);
  }

  // ---- telemetry
  private save() {
    const v = this.video()?.nativeElement;
    const d = Math.round(v?.duration || this.session()?.durationSeconds || 0);
    if (!v || !d || this.session()?.preview) return;
    this.lastSaved = v.currentTime;
    this.api.saveProgress(this.id(), Math.floor(v.currentTime), d).catch(() => {});
  }

  private flushQoe(keepalive = false) {
    const s = this.session();
    const v = this.video()?.nativeElement;
    if (!s || !v || s.preview) return;
    const playedMs = Math.round((v.currentTime - this.lastPlayedSec) * 1000);
    this.lastPlayedSec = v.currentTime;
    const level = this.hls && this.hls.currentLevel >= 0 ? this.hls.levels[this.hls.currentLevel] : null;
    const beacon = { sessionId: s.sessionId, titleId: s.titleId, startupMs: this.startupMs, rebufferMs: Math.round(this.rebufferMs),
      playedMs: Math.max(0, playedMs), bitrateKbps: level ? Math.round(level.bitrate / 1000) : null, bitrateSwitches: this.switches, errorCode: this.errorCode };
    this.rebufferMs = 0; this.switches = 0;
    if (keepalive) {
      const token = this.auth.token();
      fetch(env.apiBase + '/api/v1/analytics/qoe', { method: 'POST', keepalive: true,
        headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify({ beacons: [beacon] }) }).catch(() => {});
    } else {
      this.api.sendQoe([beacon]).catch(() => {});
    }
  }

  ngOnDestroy() {
    this.save();
    this.flushQoe(true);
    clearTimeout(this.refreshTimer);
    clearTimeout(this.chromeTimer);
    clearInterval(this.qoeTimer);
    this.hls?.destroy();
  }

  clock(s: number) {
    if (!isFinite(s)) return '0:00';
    const m = Math.floor(s / 60), sec = Math.floor(s % 60);
    return `${m}:${sec.toString().padStart(2, '0')}`;
  }
}
