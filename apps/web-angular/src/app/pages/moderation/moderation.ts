import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Api, CaseDetail, ReviewCase } from '../../core/api';
import { problemMessage } from '../../core/http';
import { StateView, Toasts } from '../../ui/ui';

@Component({
  selector: 'ss-moderation',
  imports: [FormsModule, RouterLink, StateView, DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page pt-28">
      <p class="eyebrow">Trust & safety</p>
      <h1 class="mt-2 text-3xl font-bold">Moderation queue</h1>
      <div class="mt-4 flex gap-2" role="group" aria-label="Case type">
        @for (k of kinds; track k.v) { <button class="chip !px-3 !py-1" [class.chip-on]="kind() === k.v" [attr.aria-pressed]="kind() === k.v" (click)="setKind(k.v)">{{ k.label }}</button> }
      </div>
      @if (error()) { <ss-state kind="error" heading="Queue unavailable" [message]="error()" action="Retry" (act)="load()" /> }
      <div class="mt-6 grid gap-6 lg:grid-cols-[1fr_1.4fr]">
        <ul class="space-y-2" aria-label="Open cases">
          @for (c of cases(); track c.id) {
            <li><button class="card w-full p-4 text-left transition hover:ring-aurora-500/60" [class.ring-aurora-500]="selected()?.reviewCase?.id === c.id" (click)="open(c)">
              <div class="flex items-center justify-between gap-2"><span class="font-semibold">{{ c.title }}</span><span class="chip">{{ c.kind }}</span></div>
              <p class="mt-1 text-xs text-ink-400">{{ c.creatorName }} · {{ c.createdAt | date:'medium' }}</p></button></li>
          } @empty { @if (!loading()) { <li><ss-state heading="Queue is clear" message="No open cases." /></li> } }
        </ul>
        @if (selected(); as d) {
          <section class="card p-6" aria-labelledby="case-h">
            <h2 id="case-h" class="text-xl font-semibold">{{ d.reviewCase!.title }}</h2>
            <p class="mt-1 text-sm text-ink-400">{{ d.reviewCase!.kind }} · by {{ d.reviewCase!.creatorName }} · media v{{ d.reviewCase!.mediaVersion }}</p>
            @if (d.reviewCase!.synopsis) { <p class="mt-3 text-sm text-ink-200">{{ d.reviewCase!.synopsis }}</p> }
            @if (d.reviewCase!.kind === 'APPEAL') { <p class="mt-3 text-sm"><strong>Creator's appeal:</strong> {{ d.reviewCase!.reason }}</p> }
            <a [routerLink]="['/watch', d.reviewCase!.titleId]" class="btn-ghost mt-4">▶ Watch full content</a>
            <h3 class="mt-6 text-sm font-semibold uppercase tracking-wider text-ink-300">Rights attestation</h3>
            <ul class="mt-2 text-sm">@for (a of d.rightsAttestations; track $index) { <li class="text-ink-200">{{ a['licenseType'] }} · {{ a['statementVersion'] }} · {{ a['attestedAt'] }} @if (a['evidenceUrl']) { · <a class="text-lagoon-400 underline" [href]="a['evidenceUrl']" target="_blank" rel="noopener noreferrer">evidence</a> }</li> } @empty { <li class="text-rose-400">No attestation on file</li> }</ul>
            @if (d.reports!.length) {
              <h3 class="mt-6 text-sm font-semibold uppercase tracking-wider text-ink-300">Viewer reports ({{ d.reports!.length }})</h3>
              <ul class="mt-2 space-y-1 text-sm">@for (r of d.reports; track $index) { <li>{{ r['category'] }} — {{ r['details'] }}</li> }</ul>
            }
            <h3 class="mt-6 text-sm font-semibold uppercase tracking-wider text-ink-300">Audit trail</h3>
            <ol class="mt-2 space-y-1 text-xs text-ink-400">@for (a of d.audit; track $index) { <li>{{ a['at'] }} · {{ a['action'] }} · {{ a['detail'] }}</li> }</ol>
            <form class="mt-6 space-y-3" (ngSubmit)="decide('APPROVE')">
              <label class="label" for="reason">Reason (required to reject or take down; shown to the creator)</label>
              <textarea id="reason" name="reason" class="field" rows="2" [(ngModel)]="reason" maxlength="1000"></textarea>
              <div class="flex flex-wrap gap-2">
                @if (d.reviewCase!.kind !== 'REPORT') { <button class="btn-accent" type="submit">Approve</button><button class="btn-danger" type="button" (click)="decide('REJECT')" [disabled]="!reason">Reject</button> }
                @else { <button class="btn-ghost" type="button" (click)="decide('DISMISS')">Dismiss report</button> }
                <button class="btn-danger ml-auto" type="button" (click)="takedown(d.reviewCase!.titleId!)" [disabled]="!reason">Take down</button>
              </div>
            </form>
          </section>
        }
      </div>
    </div>`,
})
export class ModerationPage implements OnInit {
  private api = inject(Api);
  private toasts = inject(Toasts);
  kinds = [{ v: '', label: 'All' }, { v: 'SUBMISSION', label: 'Submissions' }, { v: 'REPORT', label: 'Reports' }, { v: 'APPEAL', label: 'Appeals' }];
  kind = signal('');
  cases = signal<ReviewCase[]>([]);
  selected = signal<CaseDetail | null>(null);
  loading = signal(true);
  error = signal<string | null>(null);
  reason = '';

  ngOnInit() { this.load(); }
  setKind(k: string) { this.kind.set(k); this.load(); }
  async load() {
    this.loading.set(true); this.error.set(null);
    try { this.cases.set(await this.api.queue(this.kind() || undefined)); } catch (e) { this.error.set(problemMessage(e)); } finally { this.loading.set(false); }
  }
  async open(c: ReviewCase) { this.reason = ''; try { this.selected.set(await this.api.caseDetail(c.id!)); } catch (e) { this.toasts.show(problemMessage(e), 'error'); } }
  async decide(d: 'APPROVE' | 'REJECT' | 'DISMISS') {
    try { await this.api.decide(this.selected()!.reviewCase!.id!, d, this.reason || undefined); this.toasts.show(`Decision recorded: ${d.toLowerCase()}`, 'success'); this.selected.set(null); this.load(); }
    catch (e) { this.toasts.show(problemMessage(e), 'error'); }
  }
  async takedown(titleId: string) {
    if (!confirm('Take this title down? Playback is revoked immediately and media is deleted.')) return;
    try { await this.api.takedown(titleId, this.reason); this.toasts.show('Takedown issued', 'success'); this.selected.set(null); this.load(); }
    catch (e) { this.toasts.show(problemMessage(e), 'error'); }
  }
}
