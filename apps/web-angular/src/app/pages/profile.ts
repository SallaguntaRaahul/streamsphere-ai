import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Api, ProfileModel } from '../core/api';
import { problemMessage } from '../core/http';
import { StateView, Toasts } from '../ui/ui';

@Component({
  selector: 'ss-profile',
  imports: [FormsModule, StateView],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page max-w-2xl pt-28">
      <h1 class="text-3xl font-bold">Profile & preferences</h1>
      @if (error()) { <ss-state kind="error" heading="Couldn't load your profile" [message]="error()" action="Retry" (act)="ngOnInit()" /> }
      @if (p(); as p) {
        <form class="card mt-8 space-y-6 p-6" (ngSubmit)="save()">
          <div><label class="label" for="dn">Display name</label><input id="dn" name="dn" class="field" [(ngModel)]="p.displayName" required maxlength="80" /></div>
          <div><label class="label" for="lang">Preferred language</label>
            <select id="lang" name="lang" class="field" [(ngModel)]="p.preferredLanguage"><option value="en">English</option><option value="es">Español</option><option value="fr">Français</option><option value="hi">हिन्दी</option></select></div>
          <fieldset class="space-y-3">
            <legend class="label">Playback & accessibility</legend>
            <label class="flex items-center gap-3"><input type="checkbox" name="cc" [(ngModel)]="p.captionsDefault" class="h-4 w-4 accent-aurora-500" /> Turn on captions by default</label>
            <label class="flex items-center gap-3"><input type="checkbox" name="ap" [(ngModel)]="p.autoplay" class="h-4 w-4 accent-aurora-500" /> Autoplay next</label>
            <label class="flex items-center gap-3"><input type="checkbox" name="rm" [(ngModel)]="p.reducedMotion" class="h-4 w-4 accent-aurora-500" /> Reduce motion</label>
          </fieldset>
          <div class="flex gap-3"><button class="btn-accent" type="submit" [disabled]="saving()">{{ saving() ? 'Saving…' : 'Save' }}</button></div>
        </form>
      }
    </div>`,
})
export class ProfilePage implements OnInit {
  private api = inject(Api);
  private toasts = inject(Toasts);
  p = signal<ProfileModel | null>(null);
  version = 0;
  error = signal<string | null>(null);
  saving = signal(false);

  async ngOnInit() {
    try {
      const r = await this.api.profile();
      this.p.set({ ...r.body! });
      this.version = Number((r.headers.get('ETag') ?? '"0"').replace(/"/g, ''));
    } catch (e) { this.error.set(problemMessage(e)); }
  }

  async save() {
    const p = this.p()!;
    this.saving.set(true);
    try {
      const saved = await this.api.saveProfile({ displayName: p.displayName, preferredLanguage: p.preferredLanguage, captionsDefault: p.captionsDefault,
        autoplay: p.autoplay, reducedMotion: p.reducedMotion }, this.version);
      this.version = saved.version!;
      document.documentElement.classList.toggle('reduce-motion', !!saved.reducedMotion);
      this.toasts.show('Preferences saved', 'success');
    } catch (e) { this.toasts.show(problemMessage(e), 'error'); } finally { this.saving.set(false); }
  }
}
