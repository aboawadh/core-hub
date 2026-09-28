// Display: theme, glass intensity, UI language and text size. Local first (they must work
// before sign-in), mirrored to `auth.setPreferences` for the fields the contract knows.
import { usePreferences, useSavePreferences } from '../hub/queries.js';
import { describeError } from '../auth/client.js';
import {
  GLASS_CHOICES,
  TEXT_SCALES,
  THEME_CHOICES,
  themeIcon,
  useTheme,
  type DisplayPrefs,
} from '../design/theme.js';
import { useI18n } from '../i18n/context.js';
import { LANGUAGE_CHOICES, serverLocale } from '../i18n/index.js';
import { Notice, Segmented, Select, Switch } from '../ui/index.js';

/** Up to this many languages fit a segmented control; more are a list (ADR 0028). */
const SEGMENTED_LANGUAGES = 3;

export function DisplayTab({ only }: { only?: Array<keyof DisplayPrefs> }) {
  const { t } = useI18n();
  const { prefs, update } = useTheme();
  const preferences = usePreferences();
  const save = useSavePreferences();
  const show = (key: keyof DisplayPrefs) => !only || only.includes(key);

  const change = (patch: Partial<DisplayPrefs>) => {
    update(patch);
    const server = preferences.data;
    if (!server) return;
    const next = { ...prefs, ...patch };
    save.mutate({
      ...server,
      theme: next.theme,
      // The contract's `locale` is Arabic or English; any other UI language stores the nearest
      // of the two for the hub's own notices, and stays this browser's choice (ADR 0028).
      locale: serverLocale(next.language),
      text_scale: next.textScale,
    });
  };

  return (
    <div className="flex flex-col gap-4">
      {show('theme') && (
        <fieldset className="flex flex-col gap-1">
          <legend className="text-sm font-medium">{t('display.theme.label')}</legend>
          <Segmented
            className="self-start"
            label={t('display.theme.label')}
            value={prefs.theme}
            onChange={(choice) => change({ theme: choice as DisplayPrefs['theme'] })}
            options={THEME_CHOICES.map((choice) => ({
              value: choice,
              label: t(`display.theme.${choice}`),
              icon: themeIcon(choice),
              itemProps: { 'data-testid': `theme-${choice}` },
            }))}
          />
        </fieldset>
      )}
      {show('glass') && (
        <fieldset className="flex flex-col gap-1">
          <legend className="text-sm font-medium">{t('display.glass.label')}</legend>
          <p className="text-xs text-muted">{t('display.glass.hint')}</p>
          <Segmented
            className="self-start"
            label={t('display.glass.label')}
            value={prefs.glass}
            onChange={(level) => change({ glass: level as DisplayPrefs['glass'] })}
            options={GLASS_CHOICES.map((level) => ({
              value: level,
              label: t(`display.glass.${level}`),
              itemProps: { 'data-testid': `glass-${level}` },
            }))}
          />
        </fieldset>
      )}
      {show('language') && (
        <fieldset className="flex flex-col gap-1">
          <legend className="text-sm font-medium">{t('display.language')}</legend>
          {LANGUAGE_CHOICES.length <= SEGMENTED_LANGUAGES ? (
            <Segmented
              className="self-start"
              label={t('display.language')}
              value={prefs.language}
              onChange={(language) => change({ language: language as DisplayPrefs['language'] })}
              options={LANGUAGE_CHOICES.map((language) => ({
                value: language.code,
                label: language.nativeName,
                itemProps: { 'data-testid': `language-${language.code}` },
              }))}
            />
          ) : (
            <div className="self-start">
              <Select
                label={t('display.language')}
                title={null}
                value={prefs.language}
                onValueChange={(language) => language && change({ language })}
                options={LANGUAGE_CHOICES.map((language) => ({
                  value: language.code,
                  label: language.nativeName,
                }))}
                testId="language-select"
              />
            </div>
          )}
        </fieldset>
      )}
      {show('textScale') && (
        <fieldset className="flex flex-col gap-1">
          <legend className="text-sm font-medium">{t('display.text_scale')}</legend>
          <Segmented
            className="self-start"
            label={t('display.text_scale')}
            value={String(prefs.textScale)}
            onChange={(scale) => change({ textScale: Number(scale) })}
            options={TEXT_SCALES.map((scale) => ({
              value: String(scale),
              label: `${Math.round(scale * 100)}%`,
              itemProps: { 'data-testid': `text-scale-${Math.round(scale * 100)}` },
            }))}
          />
        </fieldset>
      )}
      {/* A setting that takes effect the instant it is flipped: a switch, not a tick box.
          It decides whether a finished turn shows "thought for …" above the reply
          (chat/Reasoning.tsx); the live indicator while a run is alive is not optional. */}
      {only === undefined && preferences.data && (
        <Switch
          checked={preferences.data.show_reasoning}
          onChange={(next) => save.mutate({ ...preferences.data, show_reasoning: next })}
          label={t('display.reasoning.label')}
          hint={t('display.reasoning.hint')}
          testId="show-reasoning"
        />
      )}
      {save.isError && <Notice tone="danger">{describeError(save.error, t)}</Notice>}
      {preferences.isError && <Notice tone="warning">{t('display.local_only')}</Notice>}
    </div>
  );
}
