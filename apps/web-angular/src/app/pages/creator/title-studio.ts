import { ChangeDetectionStrategy, Component, OnDestroy, computed, effect, inject, input, signal } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Api, AssetDetail, Attestation, Title } from '../../core/api';
import { problemMessage } from '../../core/http';
import { MultipartUploader } from '../../core/uploader';
import { StateView, Toasts, formatBytes } from '../../ui/ui';

@Component({
  selector: 'ss-title-studio',
  imports: [FormsModule, RouterLink, StateView, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page max-w-5xl pt-28">
      <a routerLink="/creator" class="text-sm text-ink-400 hover:text-white">← Creator studio</a>
      @if (error()) { <ss-state kind="error" heading="Title unavailable" [message]="error()" /> }
      @if (t(); as t) {
        <div class="mt-3 flex flex-wrap items-center gap-3">
          <h1 class="text-3xl font-bold">{{ t.title }}</h1>
          <span class="chip">{{ t.editorialState }}</span><span class="chip">media: {{ t.mediaState }}</span>
        </div>
        @if (t.editorialState === 'REJECTED' && t.rejectionReason) {
          <div class="card mt-4 p-4 ring-rose-400/40" role="alert"><p class="font-semibold text-rose-400">Changes requested</p><p class="mt-1 text-sm">{{ t.rejectionReason }}</p></div>
        }

        <ol class="mt-8 space-y-6">
          <!-- 1. details -->
          <li class="card p-6" aria-labelledby="s1">
            <h2 id="s1" class="text-lg font-semibold"><span class="mr-2 text-aurora-300">1</span>Details</h2>
            <form class="mt-4 grid gap-4 sm:grid-cols-2" (ngSubmit)="saveDetails()">
              <fieldset class="contents" [disabled]="!editable()">
                <div class="sm:col-span-2"><label class="label" for="tt">Title</label><input id="tt" name="tt" class="field" [(ngModel)]="form.title" required maxlength="160" /></div>
                <div class="sm:col-span-2"><label class="label" for="syn">Synopsis</label><textarea id="syn" name="syn" class="field" rows="3" [(ngModel)]="form.synopsis" maxlength="4000"></textarea></div>
                <div><label class="label" for="gen">Genres (comma-separated, max 5)</label><input id="gen" name="gen" class="field" [(ngModel)]="genres" placeholder="drama, sci-fi" /></div>
                <div><label class="label" for="yr">Release year</label><input id="yr" name="yr" type="number" class="field" [(ngModel)]="form.releaseYear" min="1888" max="2100" /></div>
                <div><label class="label" for="lg">Language</label><input id="lg" name="lg" class="field" [(ngModel)]="form.language" maxlength="5" /></div>
                <div><label class="label" for="mr">Rating</label><select id="mr" name="mr" class="field" [(ngModel)]="form.maturityRating">@for (r of ratings; track r) {<option [value]="r">{{ r }}</option>}</select></div>
                <div class="sm:col-span-2"><button class="btn-accent" type="submit">Save details</button></div>
              </fieldset>
            </form>
          </li>

          <!-- 2. rights -->
          <li class="card p-6" aria-labelledby="s2">
            <h2 id="s2" class="text-lg font-semibold"><span class="mr-2 text-aurora-300">2</span>Rights attestation</h2>
            @if (attestations().length) {
              <p class="mt-3 text-sm text-lagoon-300">✓ Attested as {{ attestations()[0].licenseType }} on {{ attestations()[0].attestedAt?.slice(0, 10) }} (statement {{ attestations()[0].statementVersion }})</p>
            } @else {
              <form class="mt-4 space-y-3" (ngSubmit)="attest()">
                <div><label class="label" for="lic">Basis for distribution</label>
                  <select id="lic" name="lic" class="field" [(ngModel)]="license">
                    <option value="ORIGINAL">Original work — I own all rights</option><option value="LICENSED">Licensed to me for distribution</option>
                    <option value="PUBLIC_DOMAIN">Public domain</option><option value="CC_BY">Creative Commons BY</option><option value="CC_BY_SA">Creative Commons BY-SA</option><option value="CC0">CC0</option>
                  </select></div>
                <div><label class="label" for="ev">Evidence URL (license, archive record) — optional</label><input id="ev" name="ev" class="field" [(ngModel)]="evidence" placeholder="https://" /></div>
                <label class="flex items-start gap-3 text-sm"><input type="checkbox" name="cr" [(ngModel)]="confirmRights" class="mt-1 h-4 w-4 accent-aurora-500" />
                  I confirm I hold all rights (including music, footage and performers' releases) needed to distribute this work worldwide.</label>
                <button class="btn-accent" [disabled]="!confirmRights">Record attestation</button>
              </form>
            }
          </li>

          <!-- 3. upload -->
          <li class="card p-6" aria-labelledby="s3">
            <h2 id="s3" class="text-lg font-semibold"><span class="mr-2 text-aurora-300">3</span>Upload video</h2>
            @if (!attestations().length) { <p class="mt-2 text-sm text-ink-400">Complete the rights attestation first.</p> }
            @else if (!editable()) { <p class="mt-2 text-sm text-ink-400">Uploads are locked while the title is {{ t.editorialState }}.</p> }
            @else {
              <label class="mt-4 flex cursor-pointer flex-col items-center justify-center gap-2 rounded-2xl border-2 border-dashed border-white/15 p-8 text-center hover:border-aurora-500"
                     (dragover)="$event.preventDefault()" (drop)="drop($event)">
                <span class="text-3xl" aria-hidden="true">⇪</span>
                <span class="font-medium">Drop a video or choose a file</span>
                <span class="text-xs text-ink-400">MP4, MOV, WebM or MKV · up to 2 GB · uploaded directly to storage, resumable</span>
                <input type="file" accept="video/mp4,video/quicktime,video/webm,video/x-matroska" class="sr-only" (change)="pick($event)" />
              </label>
              @if (uploader(); as u) {
                <div class="mt-4 space-y-2 text-sm" aria-live="polite">
                  <p>{{ file()?.name }} · {{ bytes(file()?.size) }} · <strong>{{ u.phase() }}</strong>
                    @if (u.phase() === 'uploading') { · {{ bytes(u.speed()) }}/s }</p>
                  @if (u.phase() === 'hashing') { <progress class="w-full" [value]="u.hashProgress()" max="1" aria-label="Computing checksum"></progress> }
                  <progress class="w-full accent-lagoon-400" [value]="u.progress()" max="1" aria-label="Upload progress"></progress>
                  <p class="text-ink-400">{{ (u.progress() * 100) | number:'1.0-0' }}% uploaded</p>
                  @if (u.error()) { <p class="text-rose-400" role="alert">{{ u.error() }}</p> }
                  <div class="flex gap-2">
                    @if (u.phase() === 'error') { <button class="btn-ghost" (click)="u.retry(file()!)">Resume upload</button> }
                    @if (u.phase() === 'uploading' || u.phase() === 'hashing') { <button class="btn-danger" (click)="u.cancel()">Cancel</button> }
                  </div>
                </div>
              }
            }
          </li>

          <!-- 4. processing -->
          <li class="card p-6" aria-labelledby="s4">
            <h2 id="s4" class="text-lg font-semibold"><span class="mr-2 text-aurora-300">4</span>Processing</h2>
            @if (asset(); as a) {
              <p class="mt-2 text-sm" aria-live="polite">Asset v{{ a.asset!.assetVersion }} · <strong>{{ a.asset!.state }}</strong> · {{ a.asset!.provider }}
                @if (a.asset!.durationSeconds) { · {{ a.asset!.durationSeconds }} s · {{ a.asset!.width }}×{{ a.asset!.height }} }</p>
              @if (a.asset!.error) { <p class="mt-1 text-sm text-rose-400" role="alert">{{ a.asset!.error }}</p> }
              <ul class="mt-3 space-y-1 text-xs text-ink-300">
                @for (j of a.jobs; track j.id) { <li>Attempt {{ j.attempt }} · {{ j.state }} via {{ j.launcher }} @if (j.wallSeconds) { · {{ j.wallSeconds | number:'1.0-1' }} s wall · {{ j.cpuSeconds | number:'1.0-1' }} CPU-s } @if (j.error) { · {{ j.error }} }</li> }
              </ul>
              @if (a.renditions!.length) {
                <table class="mt-4 w-full text-left text-xs"><caption class="sr-only">Renditions</caption>
                  <thead class="text-ink-400"><tr><th class="py-1">Rendition</th><th>Resolution</th><th>Video kbps</th><th>Segments</th><th>Size</th></tr></thead>
                  <tbody>@for (r of a.renditions; track r.name) { <tr class="border-t border-white/5"><td class="py-1.5">{{ r.name }}</td><td>{{ r.width }}×{{ r.height }}</td><td>{{ r.videoKbps }}</td><td>{{ r.segmentCount }}</td><td>{{ bytes(r.bytes) }}</td></tr> }</tbody>
                </table>
              }
            } @else { <p class="mt-2 text-sm text-ink-400">No upload yet.</p> }
          </li>

          <!-- 5. captions -->
          <li class="card p-6" aria-labelledby="s5">
            <h2 id="s5" class="text-lg font-semibold"><span class="mr-2 text-aurora-300">5</span>Captions</h2>
            @if (asset()?.asset?.state === 'READY') {
              <ul class="mt-2 text-sm text-ink-300">@for (c of asset()!.captions; track c.id) { <li>✓ {{ c.label }} ({{ c.language }}) @if (c.isDefault) { · default }</li> } @empty { <li>No captions yet — they make your film accessible.</li> }</ul>
              <form class="mt-4 grid gap-3 sm:grid-cols-4" (ngSubmit)="addCaptions()">
                <div><label class="label" for="cl">Language</label><input id="cl" name="cl" class="field" [(ngModel)]="capLang" maxlength="5" /></div>
                <div><label class="label" for="cb">Label</label><input id="cb" name="cb" class="field" [(ngModel)]="capLabel" maxlength="40" /></div>
                <div class="sm:col-span-2"><label class="label" for="cf">WebVTT file</label><input id="cf" type="file" accept=".vtt,text/vtt" class="field" (change)="pickVtt($event)" /></div>
                <label class="flex items-center gap-2 text-sm sm:col-span-2"><input type="checkbox" name="cd" [(ngModel)]="capDefault" class="accent-aurora-500" /> Default track</label>
                <div class="sm:col-span-2 sm:text-right"><button class="btn-ghost" [disabled]="!vtt">Add captions</button></div>
              </form>
            } @else { <p class="mt-2 text-sm text-ink-400">Available once processing is complete.</p> }
          </li>

          <!-- 6. review & publish -->
          <li class="card p-6" aria-labelledby="s6">
            <h2 id="s6" class="text-lg font-semibold"><span class="mr-2 text-aurora-300">6</span>Review & publish</h2>
            <div class="mt-4 flex flex-wrap gap-3">
              @if (t.mediaState === 'READY') { <a [routerLink]="['/watch', t.id]" class="btn-ghost">▶ Preview</a> }
              @if (t.editorialState === 'DRAFT' || t.editorialState === 'REJECTED') { <button class="btn-accent" (click)="submit()" [disabled]="t.mediaState !== 'READY' || !attestations().length">Submit for review</button> }
              @if (t.editorialState === 'APPROVED') { <button class="btn-primary" (click)="publish()">Publish now</button> }
              @if (t.editorialState === 'PUBLISHED') { <a [routerLink]="['/title', t.id]" class="btn-primary">View public page</a> }
              @if (t.editorialState !== 'REMOVED') { <button class="btn-danger ml-auto" (click)="remove()">Delete title</button> }
            </div>
            @if (t.editorialState === 'IN_REVIEW') { <p class="mt-3 text-sm text-ember-400">In review — you'll be notified of the decision.</p> }
            @if (t.editorialState === 'REJECTED') {
              <form class="mt-4 space-y-2" (ngSubmit)="appeal()">
                <label class="label" for="ap">Appeal the decision</label>
                <textarea id="ap" name="ap" class="field" rows="2" [(ngModel)]="appealText" maxlength="2000"></textarea>
                <button class="btn-ghost" [disabled]="!appealText">Send appeal</button>
              </form>
            }
          </li>
        </ol>
      }
    </div>`,
})
export class TitleStudioPage implements OnDestroy {
  private api = inject(Api);
  private router = inject(Router);
  private toasts = inject(Toasts);
  id = input.required<string>();
  t = signal<Title | null>(null);
  asset = signal<AssetDetail | null>(null);
  attestations = signal<Attestation[]>([]);
  uploader = signal<MultipartUploader | null>(null);
  file = signal<File | null>(null);
  error = signal<string | null>(null);
  editable = computed(() => ['DRAFT', 'REJECTED'].includes(this.t()?.editorialState ?? ''));
  form: { title: string; synopsis: string; language: string; maturityRating: string; releaseYear: number | null } = { title: '', synopsis: '', language: 'en', maturityRating: 'NR', releaseYear: null };
  genres = '';
  ratings = ['G', 'PG', 'PG-13', 'R', 'NR'];
  license = 'ORIGINAL'; evidence = ''; confirmRights = false;
  capLang = 'en'; capLabel = 'English'; capDefault = true; vtt = '';
  appealText = '';
  bytes = formatBytes;
  private poll?: ReturnType<typeof setInterval>;

  constructor() {
    effect(() => { this.id(); this.load(); });
    effect(() => { if (this.uploader()?.phase() === 'done') { this.toasts.show('Upload verified — processing started', 'success'); setTimeout(() => this.refresh(), 800); } });
    this.poll = setInterval(() => {
      const st = this.asset()?.asset?.state;
      if (st === 'QUEUED' || st === 'PROCESSING' || this.uploader()?.phase() === 'done' || this.t()?.editorialState === 'IN_REVIEW') this.refresh();
    }, 3000);
  }

  ngOnDestroy() { clearInterval(this.poll); }

  async load() {
    try {
      await this.refresh();
      const t = this.t()!;
      this.form = { title: t.title!, synopsis: t.synopsis ?? '', language: t.language!, maturityRating: t.maturityRating!, releaseYear: t.releaseYear ?? null };
      this.genres = (t.genres ?? []).join(', ');
      this.attestations.set(await this.api.attestations(this.id()));
    } catch (e) { this.error.set(problemMessage(e)); }
  }

  async refresh() {
    this.t.set(await this.api.myTitle(this.id()));
    const a = await this.api.assets(this.id()).catch(() => []);
    this.asset.set(a[0] ?? null);
  }

  async saveDetails() {
    try {
      const t = await this.api.updateTitle(this.id(), { ...this.form, releaseYear: this.form.releaseYear ?? undefined,
        genres: this.genres.split(',').map(g => g.trim().toLowerCase().replace(/\s+/g, '-')).filter(Boolean).slice(0, 5) }, this.t()!.version!);
      this.t.set(t); this.toasts.show('Details saved', 'success');
    } catch (e) { this.toasts.show(problemMessage(e), 'error'); }
  }

  async attest() {
    try { await this.api.attest(this.id(), this.license, this.evidence || null); this.attestations.set(await this.api.attestations(this.id())); this.toasts.show('Rights attestation recorded', 'success'); }
    catch (e) { this.toasts.show(problemMessage(e), 'error'); }
  }

  pick(e: Event) { const f = (e.target as HTMLInputElement).files?.[0]; if (f) this.startUpload(f); }
  drop(e: DragEvent) { e.preventDefault(); const f = e.dataTransfer?.files?.[0]; if (f) this.startUpload(f); }
  private startUpload(f: File) {
    this.file.set(f);
    const u = new MultipartUploader(this.api, this.id());
    this.uploader.set(u);
    u.start(f);
  }

  async pickVtt(e: Event) { const f = (e.target as HTMLInputElement).files?.[0]; this.vtt = f ? await f.text() : ''; }
  async addCaptions() {
    try { await this.api.addCaptions(this.asset()!.asset!.id!, this.capLang, this.capLabel, this.vtt, this.capDefault); this.vtt = ''; await this.refresh(); this.toasts.show('Captions added', 'success'); }
    catch (e) { this.toasts.show(problemMessage(e), 'error'); }
  }

  async submit() { await this.act(() => this.api.submitTitle(this.id()), 'Submitted for review'); }
  async publish() { await this.act(() => this.api.publishTitle(this.id()), 'Published! 🎬'); }
  async appeal() { await this.act(() => this.api.appeal(this.id(), this.appealText), 'Appeal sent'); this.appealText = ''; }
  async remove() {
    if (!confirm('Delete this title? It will be removed from StreamSphere and its video files deleted.')) return;
    await this.act(() => this.api.deleteTitle(this.id()), 'Title deleted');
    this.router.navigate(['/creator']);
  }

  private async act(fn: () => Promise<unknown>, ok: string) {
    try { await fn(); this.toasts.show(ok, 'success'); await this.refresh(); } catch (e) { this.toasts.show(problemMessage(e), 'error'); }
  }
}
