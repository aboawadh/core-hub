/**
 * The hub's Hermes home on the person's own Hermes install (`hermes-shared-install.ts`), over
 * real folders shaped like what Hermes's installer leaves behind (checked against a real
 * install on 2026-09-27): `~/.hermes/installs/<key>/facts.json` and `~/.hermes/tools/`.
 */
import {
  existsSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  realpathSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { personalHermesRoot, shareHermesInstall } from './hermes-shared-install.js';

const dirs: string[] = [];
function tempDir(): string {
  const dir = realpathSync(mkdtempSync(path.join(tmpdir(), 'corehub-shared-install-')));
  dirs.push(dir);
  return dir;
}
afterEach(() => {
  for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true });
});

/** A person's machine: `$HOME/.hermes` from Hermes's current installer, and the hub's data. */
function machine(options: { pm?: boolean } = {}) {
  const home = tempDir();
  const root = path.join(home, '.hermes');
  mkdirSync(path.join(root, 'hermes-agent'), { recursive: true });
  writeFileSync(path.join(root, 'config.yaml'), 'model: my-own-model\n');
  writeFileSync(path.join(root, '.env'), 'OPENROUTER_API_KEY=mine\n');
  if (options.pm !== false) {
    mkdirSync(path.join(root, 'installs', '8f4d41295e258a07', 'environments'), {
      recursive: true,
    });
    writeFileSync(path.join(root, 'installs', '8f4d41295e258a07', 'facts.json'), '{}\n');
    mkdirSync(path.join(root, 'tools', 'python-3.14.7-linux-x64', 'bin'), { recursive: true });
  }
  const hubHome = path.join(tempDir(), 'local-hub', 'hermes');
  return { home, root, hubHome, env: { HOME: home } as NodeJS.ProcessEnv };
}

/** Every path under `dir` with its contents, to show nothing there changed. */
function snapshot(dir: string): string[] {
  const out: string[] = [];
  const walk = (at: string) => {
    for (const name of readdirSync(at).sort()) {
      const file = path.join(at, name);
      const stat = lstatSync(file);
      if (stat.isDirectory()) walk(file);
      else out.push(`${path.relative(dir, file)}:${readFileSync(file, 'utf8')}`);
    }
  };
  walk(dir);
  return out;
}

describe("the person's Hermes root", () => {
  it('is ~/.hermes, HERMES_HOME when set, and the root above a profile', () => {
    expect(personalHermesRoot({ HOME: '/Users/sam' }, 'darwin')).toBe('/Users/sam/.hermes');
    expect(personalHermesRoot({ HOME: '/u', HERMES_HOME: '/srv/hermes' }, 'linux')).toBe(
      '/srv/hermes',
    );
    expect(
      personalHermesRoot({ HOME: '/u', HERMES_HOME: '/u/.hermes/profiles/work' }, 'linux'),
    ).toBe('/u/.hermes');
    expect(personalHermesRoot({ LOCALAPPDATA: 'C:\\Users\\sam\\AppData\\Local' }, 'win32')).toBe(
      'C:\\Users\\sam\\AppData\\Local\\hermes',
    );
    expect(personalHermesRoot({}, 'linux')).toBeNull();
  });
});

describe("the hub's Hermes home on the person's install", () => {
  it("links the install state, points Hermes at the person's tools, and writes nothing in ~/.hermes", () => {
    const { root, hubHome, env } = machine();
    const before = snapshot(root);
    const shared = shareHermesInstall({ home: hubHome, env });
    expect(shared).toMatchObject({
      outcome: 'linked',
      root,
      runtimeDir: path.join(root, 'tools'),
      movedAside: null,
      error: null,
    });
    const link = path.join(hubHome, 'installs');
    expect(lstatSync(link).isSymbolicLink()).toBe(true);
    expect(realpathSync(link)).toBe(path.join(root, 'installs'));
    // The person's configuration and keys, and everything else there, are as they were.
    expect(snapshot(root)).toEqual(before);
    // Only the link in the hub's home: no configuration of the person's is copied.
    expect(readdirSync(hubHome)).toEqual(['installs']);
  });

  it('finds the link it made on the next start', () => {
    const { hubHome, env, root } = machine();
    shareHermesInstall({ home: hubHome, env });
    expect(shareHermesInstall({ home: hubHome, env })).toMatchObject({
      outcome: 'already',
      runtimeDir: path.join(root, 'tools'),
    });
  });

  it('leaves an older Hermes (packages in its venv, no installs/) alone', () => {
    const { hubHome, env } = machine({ pm: false });
    expect(shareHermesInstall({ home: hubHome, env })).toMatchObject({
      outcome: 'none',
      runtimeDir: null,
    });
    expect(existsSync(hubHome)).toBe(false);
  });

  it('does nothing when the hub runs Hermes in its own root (the image: HERMES_HOME is the hub home)', () => {
    const { root, env } = machine();
    expect(shareHermesInstall({ home: root, env: { ...env, HERMES_HOME: root } })).toMatchObject({
      outcome: 'none',
    });
    expect(lstatSync(path.join(root, 'installs')).isDirectory()).toBe(true);
  });

  it('can be switched off', () => {
    const { hubHome, env } = machine();
    expect(
      shareHermesInstall({ home: hubHome, env: { ...env, COREHUB_HERMES_SHARED_INSTALL: 'off' } }),
    ).toMatchObject({ outcome: 'none' });
    expect(existsSync(path.join(hubHome, 'installs'))).toBe(false);
  });

  it('keeps a runtime Hermes already finished in the hub home (what 1.1.3 let it build)', () => {
    const { hubHome, env } = machine();
    const own = path.join(hubHome, 'installs', 'abc', 'facts.json');
    mkdirSync(path.dirname(own), { recursive: true });
    writeFileSync(own, '{"packages":{}}\n');
    expect(shareHermesInstall({ home: hubHome, env })).toMatchObject({
      outcome: 'own',
      runtimeDir: null,
    });
    expect(readFileSync(own, 'utf8')).toBe('{"packages":{}}\n');
    expect(lstatSync(path.join(hubHome, 'installs')).isDirectory()).toBe(true);
  });

  it('moves an unfinished download aside (renamed, kept) and links', () => {
    const { hubHome, env, root } = machine();
    const partial = path.join(hubHome, 'installs', 'abc', 'pm-runtime', 'half');
    mkdirSync(path.dirname(partial), { recursive: true });
    writeFileSync(partial, 'x');
    const shared = shareHermesInstall({ home: hubHome, env, now: () => 1234 });
    expect(shared).toMatchObject({
      outcome: 'linked',
      movedAside: path.join(hubHome, 'installs.unfinished-1234'),
    });
    expect(
      readFileSync(
        path.join(hubHome, 'installs.unfinished-1234', 'abc', 'pm-runtime', 'half'),
        'utf8',
      ),
    ).toBe('x');
    expect(realpathSync(path.join(hubHome, 'installs'))).toBe(path.join(root, 'installs'));
  });

  it("points a link to a Hermes that moved at the person's current one", () => {
    const { hubHome, env, root } = machine();
    const old = tempDir();
    mkdirSync(hubHome, { recursive: true });
    symlinkSync(old, path.join(hubHome, 'installs'));
    const shared = shareHermesInstall({ home: hubHome, env, now: () => 7 });
    expect(shared).toMatchObject({
      outcome: 'linked',
      movedAside: path.join(hubHome, 'installs.previous-7'),
    });
    expect(realpathSync(path.join(hubHome, 'installs'))).toBe(path.join(root, 'installs'));
    expect(existsSync(old)).toBe(true);
  });
});
