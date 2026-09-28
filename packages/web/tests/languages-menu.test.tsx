// With a third language in the registry (ADR 0028) the switch becomes a menu of every language,
// each in its own name, and Display lists them in a select. The registry here is a stand-in with
// French added; the real one ships Arabic and English only.
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type * as I18n from '../src/i18n/index.js';

vi.mock('../src/i18n/index.js', async (original) => {
  const real: typeof I18n = await original();
  const fr = {
    code: 'fr',
    englishName: 'French',
    nativeName: 'Français',
    direction: 'ltr',
    status: 'partial',
    required: false,
    fallback: [],
    numerals: 'latn',
  } as const;
  return {
    ...real,
    LANGUAGES: [...real.LANGUAGES, 'fr'],
    LANGUAGE_CHOICES: [...real.LANGUAGE_CHOICES, fr],
  };
});

const { LanguageSwitch, LANGUAGE_SWITCH_TOGGLES } = await import('../src/i18n/LanguageSwitch.js');
const { I18nProvider } = await import('../src/i18n/context.js');

afterEach(cleanup);

describe('three languages', () => {
  it('open a menu of every language, the current one checked', async () => {
    expect(LANGUAGE_SWITCH_TOGGLES).toBe(false);
    const choose = vi.fn();
    render(
      <I18nProvider language="fr">
        <LanguageSwitch language="fr" shows="other" label="Language" onChoose={choose} />
      </I18nProvider>,
    );
    const trigger = screen.getByRole('button', { name: 'Language' });
    expect(trigger.textContent).toBe('Français');
    await userEvent.click(trigger);
    const items = await screen.findAllByRole('menuitemcheckbox');
    expect(items.map((item) => item.textContent)).toEqual(['العربية', 'English', 'Français']);
    expect(items[2]).toHaveAttribute('aria-checked', 'true');
    await userEvent.click(items[0]!);
    expect(choose).toHaveBeenCalledWith('ar');
  });
});
