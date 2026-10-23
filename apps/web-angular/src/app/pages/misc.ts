import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../core/auth.service';
import { StateView } from '../ui/ui';

@Component({
  selector: 'ss-auth-callback',
  imports: [StateView],
  template: `<div class="page pt-32">@if (error()) { <ss-state kind="error" heading="Sign-in failed" [message]="error()" /> } @else { <p class="text-center text-ink-300" role="status">Signing you in…</p> }</div>`,
})
export class AuthCallbackPage implements OnInit {
  private auth = inject(AuthService);
  private router = inject(Router);
  error = signal<string | null>(null);
  async ngOnInit() {
    try { this.router.navigateByUrl(await this.auth.completeLogin(), { replaceUrl: true }); }
    catch (e) { this.error.set(e instanceof Error ? e.message : 'Unknown error'); }
  }
}

@Component({
  selector: 'ss-not-found', imports: [StateView, RouterLink], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="page pt-32"><ss-state heading="We couldn't find that page" message="The title may have been removed, or the link is mistyped.">
    <a routerLink="/" class="btn-primary mt-5">Back to home</a></ss-state></div>`,
})
export class NotFoundPage {}

@Component({
  selector: 'ss-forbidden', imports: [StateView, RouterLink], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="page pt-32"><ss-state kind="error" heading="You don't have access" message="This area is limited to moderators and administrators.">
    <a routerLink="/" class="btn-primary mt-5">Back to home</a></ss-state></div>`,
})
export class ForbiddenPage {}
