import { signal } from '@angular/core';
import { Api, UploadSession } from './api';
import { sha256File } from './sha256';
import { problemMessage } from './http';

export type UploadPhase = 'idle' | 'hashing' | 'uploading' | 'verifying' | 'done' | 'error' | 'cancelled';

/**
 * Browser → object storage multipart upload with presigned part URLs. Resumable: on retry/reload the
 * uploader asks the server which parts actually exist in storage and only sends the rest.
 * Session id is kept in localStorage per title so a page reload can resume the same multipart upload.
 */
export class MultipartUploader {
  readonly phase = signal<UploadPhase>('idle');
  readonly progress = signal(0);        // 0..1 bytes uploaded
  readonly hashProgress = signal(0);
  readonly error = signal<string | null>(null);
  readonly session = signal<UploadSession | null>(null);
  readonly speed = signal(0);           // bytes / s
  private cancelled = false;
  private controllers = new Set<AbortController>();

  constructor(private readonly api: Api, private readonly titleId: string) {}

  private key() { return `ss-upload:${this.titleId}`; }

  async start(file: File) {
    this.cancelled = false;
    this.error.set(null);
    try {
      this.phase.set('hashing');
      const sha256 = await sha256File(file, f => this.hashProgress.set(f));
      const saved = localStorage.getItem(this.key());
      const idem = saved && saved.startsWith(sha256) ? saved : `${sha256}:${crypto.randomUUID()}`;
      localStorage.setItem(this.key(), idem);
      const s = await this.api.createUpload({ titleId: this.titleId, fileName: file.name, contentType: file.type || guessType(file.name),
        sizeBytes: file.size, sha256 }, idem.slice(0, 100));
      this.session.set(s);
      await this.send(file, s);
    } catch (e) {
      if (!this.cancelled) { this.phase.set('error'); this.error.set(problemMessage(e)); }
    }
  }

  /** Resume after a failure: only parts missing from storage are uploaded. */
  async retry(file: File) {
    const s = this.session();
    if (!s) return this.start(file);
    this.cancelled = false;
    this.error.set(null);
    try { await this.send(file, s); } catch (e) { if (!this.cancelled) { this.phase.set('error'); this.error.set(problemMessage(e)); } }
  }

  async cancel() {
    this.cancelled = true;
    this.controllers.forEach(c => c.abort());
    const s = this.session();
    if (s) { try { await this.api.cancelUpload(s.id!); } catch { /* best effort */ } }
    localStorage.removeItem(this.key());
    this.phase.set('cancelled');
  }

  private async send(file: File, s: UploadSession) {
    this.phase.set('uploading');
    const view = await this.api.uploadSession(s.id!);
    const done = new Map<number, string>((view.uploadedParts ?? []).map(p => [p.partNumber!, p.etag!]));
    const partSize = s.partSize!, count = s.partCount!;
    let sent = [...done.keys()].reduce((acc, n) => acc + Math.min(partSize, file.size - (n - 1) * partSize), 0);
    this.progress.set(sent / file.size);
    const todo = Array.from({ length: count }, (_, i) => i + 1).filter(n => !done.has(n));
    const started = performance.now();
    let sentThisRun = 0;
    const worker = async () => {
      while (todo.length && !this.cancelled) {
        const n = todo.shift()!;
        const blob = file.slice((n - 1) * partSize, Math.min(file.size, n * partSize));
        const etag = await this.putPart(s.id!, n, blob);
        done.set(n, etag);
        sent += blob.size; sentThisRun += blob.size;
        this.progress.set(sent / file.size);
        this.speed.set(sentThisRun / ((performance.now() - started) / 1000));
      }
    };
    await Promise.all([worker(), worker(), worker()]); // 3 parallel parts
    if (this.cancelled) return;
    this.phase.set('verifying');
    const parts = [...done.entries()].sort((a, b) => a[0] - b[0]).map(([partNumber, etag]) => ({ partNumber, etag }));
    const completed = await this.api.completeUpload(s.id!, parts);
    this.session.set(completed);
    localStorage.removeItem(this.key());
    this.phase.set('done');
  }

  private async putPart(id: string, n: number, blob: Blob, attempt = 1): Promise<string> {
    const { url } = await this.api.presignPart(id, n);
    const ctl = new AbortController();
    this.controllers.add(ctl);
    try {
      const r = await fetch(url, { method: 'PUT', body: blob, signal: ctl.signal });
      if (!r.ok) throw new Error(`storage rejected part ${n} (${r.status})`);
      const etag = r.headers.get('ETag');
      if (!etag) throw new Error('storage did not expose ETag (CORS)');
      return etag;
    } catch (e) {
      if (this.cancelled || attempt >= 4) throw e;
      await new Promise(r => setTimeout(r, 500 * 2 ** attempt * (0.5 + Math.random())));
      return this.putPart(id, n, blob, attempt + 1);
    } finally {
      this.controllers.delete(ctl);
    }
  }
}

function guessType(name: string) {
  const ext = name.toLowerCase().split('.').pop();
  return ext === 'mov' ? 'video/quicktime' : ext === 'webm' ? 'video/webm' : ext === 'mkv' ? 'video/x-matroska' : 'video/mp4';
}
