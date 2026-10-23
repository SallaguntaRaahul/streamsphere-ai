import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { env } from '../env';
import type { components as Catalog } from '../api/generated/catalog';
import type { components as Creator } from '../api/generated/creator';
import type { components as Upload } from '../api/generated/upload';
import type { components as Media } from '../api/generated/media';
import type { components as Playback } from '../api/generated/playback';
import type { components as History } from '../api/generated/history';
import type { components as Search } from '../api/generated/search';
import type { components as Moderation } from '../api/generated/moderation';
import type { components as Analytics } from '../api/generated/analytics';
import type { components as Notifications } from '../api/generated/notifications';
import type { components as Profile } from '../api/generated/profile';

// Types generated from each service's OpenAPI document (contracts/openapi → npm run gen:api).
export type TitleView = Catalog['schemas']['TitleView'];
export type Title = Catalog['schemas']['Title'];
export type TitleRequest = Catalog['schemas']['TitleRequest'];
export type GenreCount = Catalog['schemas']['GenreCount'];
export type Page<T> = { items: T[]; nextCursor?: string | null };
export type CreatorAccount = Creator['schemas']['Creator'];
export type Attestation = Creator['schemas']['Attestation'];
export type UploadSession = Upload['schemas']['UploadSession'];
export type SessionView = Upload['schemas']['SessionView'];
export type Usage = Upload['schemas']['Usage'];
export type AssetDetail = Media['schemas']['AssetDetail'];
export type PlaybackSession = Playback['schemas']['Session'];
export type Progress = History['schemas']['Progress'];
export type WatchlistItem = History['schemas']['WatchlistItem'];
export type SearchResults = Search['schemas']['Results'];
export type SearchHit = Search['schemas']['Hit'];
export type ReviewCase = Moderation['schemas']['Case'];
export type CaseDetail = Moderation['schemas']['CaseDetail'];
export type TitleStats = Analytics['schemas']['TitleStats'];
export type Notification = Notifications['schemas']['Notification'];
export type ProfileModel = Profile['schemas']['Profile'];
export interface Recommendation { titleId: string; title: string; genres: string[]; score: number; reasons: string[]; strategy: string }
export interface Recommendations { strategy: string; embeddingModel: string; items: Recommendation[]; latencyMs: number }

/** Thin typed client over the gateway. All calls are relative to env.apiBase. */
@Injectable({ providedIn: 'root' })
export class Api {
  private readonly http = inject(HttpClient);
  private u(p: string) { return env.apiBase + p; }
  private get<T>(p: string, params?: Record<string, string | number | undefined | null>) {
    const clean = Object.fromEntries(Object.entries(params ?? {}).filter(([, v]) => v !== undefined && v !== null && v !== '')) as Record<string, string>;
    return firstValueFrom(this.http.get<T>(this.u(p), { params: clean }));
  }
  private send<T>(method: 'POST' | 'PUT' | 'DELETE', p: string, body?: unknown, headers?: Record<string, string>) {
    return firstValueFrom(this.http.request<T>(method, this.u(p), { body, headers: new HttpHeaders(headers ?? {}) }));
  }

  posterUrl(path?: string | null) { return path ? this.u(path) : null; }

  // catalog
  titles(genre?: string, cursor?: string, limit = 24) { return this.get<Page<TitleView>>('/api/v1/titles', { genre, cursor, limit }); }
  title(id: string) { return this.get<TitleView>(`/api/v1/titles/${id}`); }
  titlesBatch(ids: string[]) { return ids.length ? this.get<TitleView[]>('/api/v1/titles/batch', { ids: ids.join(',') }) : Promise.resolve([]); }
  genres() { return this.get<GenreCount[]>('/api/v1/genres'); }
  myTitles() { return this.get<Title[]>('/api/v1/creator/titles'); }
  myTitle(id: string) { return this.get<Title>(`/api/v1/creator/titles/${id}`); }
  createTitle(r: TitleRequest) { return this.send<Title>('POST', '/api/v1/creator/titles', r); }
  updateTitle(id: string, r: TitleRequest, version: number) { return this.send<Title>('PUT', `/api/v1/creator/titles/${id}`, r, { 'If-Match': `"${version}"` }); }
  submitTitle(id: string) { return this.send<Title>('POST', `/api/v1/creator/titles/${id}/submit`); }
  publishTitle(id: string) { return this.send<Title>('POST', `/api/v1/creator/titles/${id}/publish`); }
  deleteTitle(id: string) { return this.send<void>('DELETE', `/api/v1/creator/titles/${id}`); }
  adminTitles(state: string) { return this.get<Title[]>('/api/v1/catalog/admin/titles', { state }); }

  // creator
  me() { return this.get<CreatorAccount>('/api/v1/creators/me'); }
  registerCreator(displayName: string, bio: string, country: string) {
    return this.send<CreatorAccount>('POST', '/api/v1/creators', { displayName, bio, country: country || null, acceptTerms: true });
  }
  attest(titleId: string, licenseType: string, evidenceUrl: string | null) {
    return this.send<Attestation>('POST', '/api/v1/creators/me/attestations', { titleId, licenseType, evidenceUrl, confirmRights: true });
  }
  attestations(titleId: string) { return this.get<Attestation[]>('/api/v1/creators/me/attestations', { titleId }); }

  // uploads (bytes go straight to object storage via presigned URLs — never through these APIs)
  createUpload(body: { titleId: string; fileName: string; contentType: string; sizeBytes: number; sha256: string }, idempotencyKey: string) {
    return this.send<UploadSession>('POST', '/api/v1/uploads', body, { 'Idempotency-Key': idempotencyKey });
  }
  uploadSession(id: string) { return this.get<SessionView>(`/api/v1/uploads/${id}`); }
  presignPart(id: string, n: number) { return this.send<{ partNumber: number; url: string; expiresAt: string }>('POST', `/api/v1/uploads/${id}/parts/${n}/presign`); }
  completeUpload(id: string, parts: { partNumber: number; etag: string }[]) { return this.send<UploadSession>('POST', `/api/v1/uploads/${id}/complete`, { parts }); }
  cancelUpload(id: string) { return this.send<UploadSession>('DELETE', `/api/v1/uploads/${id}`); }
  myUploads() { return this.get<UploadSession[]>('/api/v1/uploads'); }
  quota() { return this.get<Usage>('/api/v1/uploads/quota'); }
  adminCost() { return this.get<Usage>('/api/v1/uploads/admin/cost'); }

  // media
  assets(titleId: string) { return this.get<AssetDetail[]>(`/api/v1/media/titles/${titleId}/assets`); }
  addCaptions(assetId: string, language: string, label: string, webvtt: string, isDefault: boolean) {
    return this.send<unknown>('POST', `/api/v1/media/assets/${assetId}/captions`, { language, label, webvtt, isDefault });
  }
  jobStats() { return this.get<Record<string, unknown>>('/api/v1/media/admin/jobs'); }

  // playback
  startPlayback(titleId: string) { return this.send<PlaybackSession>('POST', '/api/v1/playback/sessions', { titleId }); }
  playbackStats() { return this.get<Record<string, unknown>>('/api/v1/playback/admin/stats'); }

  // history
  progress(titleId: string) { return this.get<Progress>(`/api/v1/history/progress/${titleId}`); }
  saveProgress(titleId: string, positionSeconds: number, durationSeconds: number) {
    return this.send<Progress>('PUT', '/api/v1/history/progress', { titleId, positionSeconds, durationSeconds });
  }
  continueWatching() { return this.get<Progress[]>('/api/v1/history/continue-watching'); }
  history() { return this.get<Progress[]>('/api/v1/history'); }
  clearHistory() { return this.send<void>('DELETE', '/api/v1/history'); }
  watchlist() { return this.get<WatchlistItem[]>('/api/v1/watchlist'); }
  addToWatchlist(id: string) { return this.send<void>('PUT', `/api/v1/watchlist/${id}`); }
  removeFromWatchlist(id: string) { return this.send<void>('DELETE', `/api/v1/watchlist/${id}`); }

  // discovery
  search(q: string, mode: string, genre?: string, cursor?: string) { return this.get<SearchResults>('/api/v1/search', { q, mode, genre, cursor, limit: 24 }); }
  suggest(q: string) { return this.get<string[]>('/api/v1/search/suggest', { q }); }
  recommendations(limit = 20) { return this.get<Recommendations>('/api/v1/recommendations', { limit }); }
  similar(id: string) { return this.get<Recommendations>(`/api/v1/recommendations/similar/${id}`); }

  // moderation
  queue(kind?: string) { return this.get<ReviewCase[]>('/api/v1/moderation/queue', { kind }); }
  caseDetail(id: string) { return this.get<CaseDetail>(`/api/v1/moderation/cases/${id}`); }
  decide(id: string, decision: 'APPROVE' | 'REJECT' | 'DISMISS', reason?: string) { return this.send<ReviewCase>('POST', `/api/v1/moderation/cases/${id}/decision`, { decision, reason }); }
  takedown(titleId: string, reason: string) { return this.send<unknown>('POST', '/api/v1/moderation/takedowns', { titleId, reason }); }
  report(titleId: string, category: string, details: string) { return this.send<unknown>('POST', '/api/v1/moderation/reports', { titleId, category, details }); }
  appeal(titleId: string, statement: string) { return this.send<ReviewCase>('POST', '/api/v1/moderation/appeals', { titleId, statement }); }

  // analytics
  creatorStats(days = 30) { return this.get<TitleStats[]>('/api/v1/analytics/creator/me/titles', { days }); }
  sendQoe(beacons: unknown[]) { return this.send<unknown>('POST', '/api/v1/analytics/qoe', { beacons }); }
  platformQoe() { return this.get<Record<string, unknown>>('/api/v1/analytics/admin/qoe'); }

  // notifications
  notifications() { return this.get<Page<Notification>>('/api/v1/notifications', { limit: 20 }); }
  unread() { return this.get<{ unread: number }>('/api/v1/notifications/unread-count'); }
  markRead(id: string) { return this.send<void>('POST', `/api/v1/notifications/${id}/read`); }
  markAllRead() { return this.send<void>('POST', '/api/v1/notifications/read-all'); }

  // profile
  profile() { return firstValueFrom(this.http.get<ProfileModel>(this.u('/api/v1/profiles/me'), { observe: 'response' })); }
  saveProfile(p: Omit<ProfileModel, 'userId' | 'createdAt' | 'version'>, version: number) {
    return this.send<ProfileModel>('PUT', '/api/v1/profiles/me', p, { 'If-Match': `"${version}"` });
  }

  adminHealth() { return this.get<{ checkedAt: string; services: Record<string, { status: string; latencyMs: number }> }>('/api/v1/admin/health'); }
}
