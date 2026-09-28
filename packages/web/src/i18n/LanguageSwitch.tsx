// The language control on the sign-in and setup screens and in the sidebar footer. Every
// language comes from the registry (locales/languages.json, ADR 0028) and is shown in its own
// name. With the two languages Core Hub ships, it is the same one-press switch it always was;
// once a third is registered, it opens a menu of every language instead.
import type { ReactNode } from 'react';
import { Button, Menu, MenuChoice, MenuItem, MenuSeparator } from '../ui/index.js';
import { IconGlobe } from '../ui/icons.js';
import { LANGUAGE_CHOICES, type Language } from './index.js';

/** Whether the control is the old switch (two languages) rather than a menu. */
export const LANGUAGE_SWITCH_TOGGLES = LANGUAGE_CHOICES.length <= 2;

export function languageName(code: Language): string {
  return LANGUAGE_CHOICES.find((each) => each.code === code)?.nativeName ?? code;
}

/** With two languages: the one that is not `current`. */
export function otherLanguage(current: Language): Language {
  return (LANGUAGE_CHOICES.find((each) => each.code !== current) ?? LANGUAGE_CHOICES[0]!).code;
}

export function LanguageSwitch({
  language,
  onChoose,
  shows,
  label,
  iconSize = 14,
  testId,
}: {
  language: Language;
  onChoose(language: Language): void;
  /** What the two-language switch reads: the language it switches to, or the current one. */
  shows: 'other' | 'current';
  /** Accessible name of the control. */
  label: string;
  iconSize?: number;
  testId?: string;
}) {
  if (LANGUAGE_SWITCH_TOGGLES) {
    const other = otherLanguage(language);
    return (
      <Button
        variant="ghost"
        size="sm"
        icon={<IconGlobe size={iconSize} />}
        aria-label={label}
        data-testid={testId}
        onClick={() => onChoose(other)}
      >
        {languageName(shows === 'other' ? other : language)}
      </Button>
    );
  }
  return (
    <Menu
      side="bottom"
      align="end"
      trigger={
        <Button
          variant="ghost"
          size="sm"
          icon={<IconGlobe size={iconSize} />}
          aria-label={label}
          data-testid={testId}
        >
          <span lang={language}>{languageName(language)}</span>
        </Button>
      }
    >
      <LanguageChoices language={language} onChoose={onChoose} />
    </Menu>
  );
}

/** Every language as a checked row, for a menu. */
export function LanguageChoices({
  language,
  onChoose,
}: {
  language: Language;
  onChoose(language: Language): void;
}) {
  return (
    <>
      {LANGUAGE_CHOICES.map((each) => (
        <MenuChoice
          key={each.code}
          checked={each.code === language}
          onSelect={() => onChoose(each.code)}
        >
          <span lang={each.code} dir={each.direction}>
            {each.nativeName}
          </span>
        </MenuChoice>
      ))}
    </>
  );
}

/**
 * The language rows of a menu that holds other things (the folded sidebar's person menu):
 * one row that switches with two languages, a separated list of every language with more.
 */
export function LanguageMenuItems({
  language,
  onChoose,
  icon,
}: {
  language: Language;
  onChoose(language: Language): void;
  icon: ReactNode;
}) {
  if (LANGUAGE_SWITCH_TOGGLES) {
    const other = otherLanguage(language);
    return (
      <MenuItem icon={icon} onSelect={() => onChoose(other)}>
        {languageName(other)}
      </MenuItem>
    );
  }
  return (
    <>
      <MenuSeparator />
      <LanguageChoices language={language} onChoose={onChoose} />
      <MenuSeparator />
    </>
  );
}
