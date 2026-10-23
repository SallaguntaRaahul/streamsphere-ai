import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { DatePipe, DecimalPipe, KeyValuePipe, PercentPipe } from '@angular/common';
import { Api, Usage } from '../../core/api';
import { problemMessage } from '../../core/http';
import { StateView, formatBytes } from '../../ui/ui';

@Component({
  selector: 'ss-admin',
  imports: [StateView, KeyValuePipe, DecimalPipe, PercentPipe, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page pt-28">
      <p class="eyebrow">Operations</p>
      <h1 class="mt-2 text-3xl font-bold">Platform health & cost guardrails</h1>
      <p class="mt-2 text-sm text-ink-400">Usage numbers are application-side estimates, not provider billing. Refreshes every 15 s.</p>
      @if (error()) { <ss-state kind="error" heading="Some data is unavailable" [message]="error()" /> }

      @if (health(); as h) {
        <section class="mt-8" aria-labelledby="hh"><h2 id="hh" class="text-lg font-semibold">Services <span class="text-sm font-normal text-ink-400">checked {{ h.checkedAt | date:'mediumTime' }}</span></h2>
          <ul class="mt-3 grid gap-3 sm:grid-cols-3 lg:grid-cols-6">
            @for (s of h.services | keyvalue; track s.key) {
              <li class="card p-3"><p class="text-sm font-medium">{{ s.key }}</p>
                <p class="mt-1 text-xs" [class]="s.value.status === 'UP' ? 'text-lagoon-400' : 'text-rose-400'">● {{ s.value.status }} · {{ s.value.latencyMs }} ms</p></li>
            }
          </ul></section>
      }

      <div class="mt-8 grid gap-4 lg:grid-cols-3">
        @if (cost(); as c) {
          <section class="card p-5" aria-labelledby="ch"><h2 id="ch" class="font-semibold">Storage & operations</h2>
            <p class="mt-3 text-3xl font-bold">{{ c.ratio | percent:'1.0-1' }}</p>
            <p class="text-sm text-ink-400">{{ bytes(c.sourceBytes! + c.mediaBytes!) }} of {{ bytes(c.quotaBytes) }} quota · level <strong [class.text-ember-400]="c.level !== 'OK'">{{ c.level }}</strong></p>
            <ul class="mt-3 text-sm">@for (o of c.operationsThisMonth | keyvalue; track o.key) { <li>{{ o.key }}: {{ o.value | number }} (est.)</li> }</ul>
            <p class="mt-3 text-xs text-ink-400">Thresholds: 50/70/85 % warn · 95 % uploads stop (fail-closed).</p>
          </section>
        }
        @if (qoe(); as q) {
          <section class="card p-5" aria-labelledby="qh"><h2 id="qh" class="font-semibold">Playback QoE (7 days, real beacons)</h2>
            <dl class="mt-3 grid grid-cols-2 gap-3 text-sm">
              <div><dt class="text-ink-400">Beacons</dt><dd class="text-xl font-semibold">{{ q['beacons'] }}</dd></div>
              <div><dt class="text-ink-400">Startup p50</dt><dd class="text-xl font-semibold">{{ q['startupP50Ms'] ?? '—' }} ms</dd></div>
              <div><dt class="text-ink-400">Startup p95</dt><dd class="text-xl font-semibold">{{ q['startupP95Ms'] ?? '—' }} ms</dd></div>
              <div><dt class="text-ink-400">Rebuffer ratio</dt><dd class="text-xl font-semibold">{{ q['rebufferRatio'] == null ? '—' : ($any(q['rebufferRatio']) | percent:'1.2-2') }}</dd></div>
            </dl></section>
        }
        @if (jobs(); as j) {
          <section class="card p-5" aria-labelledby="jh"><h2 id="jh" class="font-semibold">Transcoding</h2>
            <pre tabindex="0" aria-label="Job statistics" class="mt-3 overflow-x-auto text-xs text-ink-200">{{ fmtJobs(j) }}</pre>
            @if (delivery()) { <pre tabindex="0" aria-label="Job statistics" class="mt-3 overflow-x-auto text-xs text-ink-200">{{ fmtJobs(delivery()!) }}</pre> }
          </section>
        }
      </div>
    </div>`,
})
export class AdminPage implements OnInit, OnDestroy {
  private api = inject(Api);
  health = signal<{ checkedAt: string; services: Record<string, { status: string; latencyMs: number }> } | null>(null);
  cost = signal<Usage | null>(null);
  qoe = signal<Record<string, unknown> | null>(null);
  jobs = signal<Record<string, unknown> | null>(null);
  delivery = signal<Record<string, unknown> | null>(null);
  error = signal<string | null>(null);
  bytes = formatBytes;
  private timer?: ReturnType<typeof setInterval>;

  ngOnInit() { this.load(); this.timer = setInterval(() => this.load(), 15_000); }
  ngOnDestroy() { clearInterval(this.timer); }

  async load() {
    const results = await Promise.allSettled([this.api.adminHealth(), this.api.adminCost(), this.api.platformQoe(), this.api.jobStats(), this.api.playbackStats()]);
    const [h, c, q, j, d] = results;
    if (h.status === 'fulfilled') this.health.set(h.value);
    if (c.status === 'fulfilled') this.cost.set(c.value);
    if (q.status === 'fulfilled') this.qoe.set(q.value);
    if (j.status === 'fulfilled') this.jobs.set(j.value);
    if (d.status === 'fulfilled') this.delivery.set(d.value);
    const failed = results.find(r => r.status === 'rejected') as PromiseRejectedResult | undefined;
    this.error.set(failed ? problemMessage(failed.reason) : null);
  }

  fmtJobs(j: Record<string, unknown>) { return JSON.stringify(j, null, 2); }
}
