// The contract compatibility guard (scripts/compat.mjs, ADR 0027). Each case changes one thing
// in the released fixture and checks the guard names exactly that break — or, for additions,
// nothing at all.
import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { parse as parseYaml } from 'yaml';

// @ts-expect-error — a plain .mjs script without type declarations
import { compareEvents, compareOpenApi, run } from '../scripts/compat.mjs';
// @ts-expect-error — a plain .mjs script without type declarations
import { loadDocument } from '../scripts/lib.mjs';
// @ts-expect-error — a plain .mjs script without type declarations
import { latestReleaseTag, resolveBase } from '../../../scripts/compat-base.mjs';

type Json = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any
type Break = { id: string; kind: string; loc: string; message: string };

const fixtures = path.join(__dirname, 'fixtures', 'compat');
const openapiText = readFileSync(path.join(fixtures, 'openapi.yaml'), 'utf8');
const eventText = readFileSync(path.join(fixtures, 'note.created.schema.json'), 'utf8');
const base = (): Json => parseYaml(openapiText);
const event = (): Json => JSON.parse(eventText);
const schemas = (doc: Json) => doc.components.schemas;
const noteProps = (doc: Json) => doc.components.schemas.Note.allOf[1];

/** Ids of the breaks after `change` is applied to a copy of the fixture. */
function breaksAfter(change: (doc: Json) => void): string[] {
  const now = base();
  change(now);
  return (compareOpenApi(base(), now) as Break[]).map((b) => b.id);
}

function eventBreaksAfter(change: (events: Record<string, Json>) => void): string[] {
  const now: Record<string, Json> = { 'notes/note.created': event() };
  change(now);
  return (compareEvents({ 'notes/note.created': event() }, now) as Break[]).map((b) => b.id);
}

describe('contract guard: what breaks', () => {
  const cases: Array<[string, (doc: Json) => void, string]> = [
    [
      'a removed operation',
      (d) => delete d.paths['/notes/{note_id}'].delete,
      'contract operation-removed DELETE /notes/{note_id}',
    ],
    ['a removed path', (d) => delete d.paths['/health'], 'contract operation-removed GET /health'],
    [
      'a renamed path',
      (d) => {
        d.paths['/memos'] = d.paths['/notes'];
        delete d.paths['/notes'];
      },
      'contract operation-removed POST /notes',
    ],
    [
      'a removed response property',
      (d) => delete noteProps(d).properties.pinned,
      'contract property-removed Note.pinned',
    ],
    [
      'a removed property inherited through allOf',
      (d) => delete schemas(d).Scoped.properties.created_at,
      'contract property-removed Scoped.created_at',
    ],
    [
      'a response property that is no longer always there',
      (d) => (noteProps(d).required = ['status', 'pinned', 'body']),
      'contract response-property-optional Note.title',
    ],
    [
      'a removed enum value',
      (d) => (schemas(d).NoteStatus.enum = ['draft', 'published']),
      'contract enum-value-removed NoteStatus="archived"',
    ],
    [
      'an existing request property made required',
      (d) => schemas(d).NoteCreate.required.push('status'),
      'contract request-property-required NoteCreate.status',
    ],
    [
      'a new required request property',
      (d) => {
        schemas(d).NoteCreate.properties.due = { type: 'string' };
        schemas(d).NoteCreate.required.push('due');
      },
      'contract request-property-required NoteCreate.due',
    ],
    [
      'an optional parameter made required',
      (d) => (d.paths['/notes'].get.parameters[1].required = true),
      'contract parameter-required GET /notes query.limit',
    ],
    [
      'a new required parameter',
      (d) =>
        d.paths['/notes'].get.parameters.push({
          name: 'sort',
          in: 'query',
          required: true,
          schema: { type: 'string' },
        }),
      'contract parameter-required GET /notes query.sort',
    ],
    [
      'a removed parameter',
      (d) => d.paths['/notes'].get.parameters.splice(1, 1),
      'contract parameter-removed GET /notes query.limit',
    ],
    [
      'a narrowed request type',
      (d) => (schemas(d).NoteCreate.properties.tags.items = { type: 'integer' }),
      'contract request-type-narrowed NoteCreate.tags[]',
    ],
    [
      'a tighter request limit',
      (d) => (schemas(d).NoteCreate.properties.title.maxLength = 100),
      'contract request-constraint-tightened NoteCreate.title:maxLength',
    ],
    [
      'a changed request default',
      (d) => (schemas(d).NoteCreate.properties.color.default = 'blue'),
      'contract request-default-changed NoteCreate.color',
    ],
    [
      'a request that stops accepting unlisted fields',
      (d) => (schemas(d).NoteCreate.additionalProperties = false),
      'contract request-closed NoteCreate',
    ],
    [
      'a request enum where any value was taken',
      (d) => (schemas(d).NoteCreate.properties.tags.items.enum = ['a', 'b']),
      'contract request-enum-restricted NoteCreate.tags[]',
    ],
    [
      'a response field that may now be null',
      (d) => (noteProps(d).properties.title.type = ['string', 'null']),
      'contract response-type-widened Note.title',
    ],
    [
      'a response field that changed type',
      (d) => (noteProps(d).properties.pinned = { type: 'string' }),
      'contract response-type-widened Note.pinned',
    ],
    [
      'a removed success status',
      (d) => {
        const post = d.paths['/notes'].post;
        post.responses['200'] = post.responses['201'];
        delete post.responses['201'];
      },
      'contract response-status-removed POST /notes 201',
    ],
    [
      'a removed response media type',
      (d) => {
        const res = d.paths['/health'].get.responses['200'];
        res.content = { 'text/plain': res.content['application/json'] };
      },
      'contract response-media-type-removed GET /health 200 application/json',
    ],
    [
      'a removed variant of a union',
      (d) => (schemas(d).Block.oneOf = [{ $ref: '#/components/schemas/TextBlock' }]),
      'contract variant-removed Block|ImageBlock',
    ],
    [
      'a public operation that now needs a token',
      (d) => delete d.paths['/health'].get.security,
      'contract operation-auth-required GET /health',
    ],
    [
      'an operation closed to a role',
      (d) => (d.paths['/notes'].post['x-roles'] = ['owner', 'admin']),
      'contract operation-roles-narrowed POST /notes',
    ],
    [
      'a global operation that now needs a profile',
      (d) => (d.paths['/health'].get['x-scope'] = 'profile'),
      'contract operation-profile-required GET /health',
    ],
    [
      'a new required request body',
      (d) =>
        (d.paths['/notes/{note_id}'].delete.requestBody = {
          required: true,
          content: { 'application/json': { schema: { type: 'object' } } },
        }),
      'contract request-body-required DELETE /notes/{note_id}',
    ],
    [
      'a removed request body',
      (d) => delete d.paths['/notes'].post.requestBody,
      'contract request-body-removed POST /notes',
    ],
    [
      'a removed webhook',
      (d) => delete d.webhooks.noteEvent,
      'contract webhook-removed webhook noteEvent',
    ],
    [
      'a removed webhook header',
      (d) => (d.webhooks.noteEvent.post.parameters = []),
      'contract parameter-removed webhook noteEvent header.X-Fixture-Event',
    ],
  ];

  for (const [name, change, id] of cases) {
    it(`catches ${name}`, () => {
      expect(breaksAfter(change)).toContain(id);
    });
  }

  it('catches a request that no longer takes null', () => {
    const before = base();
    before.components.schemas.NoteCreate.properties.status = {
      oneOf: [{ $ref: '#/components/schemas/NoteStatus' }, { type: 'null' }],
    };
    const ids = (compareOpenApi(before, base()) as Break[]).map((b) => b.id);
    expect(ids).toContain('contract request-type-narrowed NoteCreate.status');
  });
});

describe('contract guard: what passes', () => {
  const additions: Array<[string, (doc: Json) => void]> = [
    ['nothing changed', () => {}],
    [
      'a new operation',
      (d) =>
        (d.paths['/notes/{note_id}'].get = {
          operationId: 'notes.get',
          responses: { '200': { description: 'The note.' } },
        }),
    ],
    [
      'a renamed path parameter (the wire is the same)',
      (d) => {
        const item = d.paths['/notes/{note_id}'];
        item.delete.parameters[1].name = 'id';
        d.paths['/notes/{id}'] = item;
        delete d.paths['/notes/{note_id}'];
      },
    ],
    [
      'a new optional parameter',
      (d) =>
        d.paths['/notes'].get.parameters.push({
          name: 'q',
          in: 'query',
          schema: { type: 'string' },
        }),
    ],
    [
      'a new optional request property',
      (d) => (schemas(d).NoteCreate.properties.emoji = { type: 'string' }),
    ],
    ['a new response property', (d) => (noteProps(d).properties.word_count = { type: 'integer' })],
    ['a new enum value', (d) => schemas(d).NoteStatus.enum.push('deleted')],
    [
      'a new variant of a union',
      (d) => {
        schemas(d).FileBlock = {
          type: 'object',
          required: ['type'],
          properties: { type: { type: 'string', enum: ['file'] } },
        };
        schemas(d).Block.oneOf.push({ $ref: '#/components/schemas/FileBlock' });
      },
    ],
    [
      'a request field that now also takes null',
      (d) => (schemas(d).NoteCreate.properties.color.type = ['string', 'null']),
    ],
    ['a looser request limit', (d) => (schemas(d).NoteCreate.properties.title.maxLength = 500)],
    [
      'a required parameter made optional',
      (d) =>
        (d.paths['/notes'].get.parameters[0] = {
          name: 'X-Hub-Profile',
          in: 'header',
          required: false,
          schema: { type: 'string' },
        }),
    ],
    ['an optional response property made required', (d) => noteProps(d).required.push('author')],
    [
      'a new status code',
      (d) => (d.paths['/notes'].post.responses['409'] = { description: 'Taken.' }),
    ],
    [
      'a new webhook',
      (d) => (d.webhooks.other = { post: { responses: { '200': { description: 'ok' } } } }),
    ],
    [
      'a request integer widened to number',
      (d) => (d.paths['/notes'].get.parameters[1].schema.type = 'number'),
    ],
    ['an operation opened to another role', (d) => d.paths['/notes'].post['x-roles'].push('guest')],
    [
      'a schema moved into its own component',
      (d) => {
        schemas(d).NoteTitle = { type: 'string' };
        noteProps(d).properties.title = { $ref: '#/components/schemas/NoteTitle' };
      },
    ],
  ];
  for (const [name, change] of additions) {
    it(`passes ${name}`, () => {
      expect(breaksAfter(change)).toEqual([]);
    });
  }

  it('passes the real contract against itself', () => {
    const doc = loadDocument();
    expect(compareOpenApi(doc, loadDocument())).toEqual([]);
  });
});

describe('contract guard: realtime events', () => {
  it('catches a removed event', () => {
    expect(eventBreaksAfter((e) => delete e['notes/note.created'])).toEqual([
      'contract event-removed notes/note.created',
    ]);
  });
  it('catches a removed event property', () => {
    expect(
      eventBreaksAfter((e) => delete e['notes/note.created'].properties.payload.properties.reason),
    ).toContain('contract property-removed event notes/note.created.payload.reason');
  });
  it('catches a removed property of a shared shape', () => {
    expect(
      eventBreaksAfter((e) => delete e['notes/note.created'].$defs.Note.properties.title),
    ).toContain('contract property-removed Note.title');
  });
  it('catches a removed event enum value', () => {
    expect(
      eventBreaksAfter(
        (e) => (e['notes/note.created'].properties.payload.properties.reason.enum = ['created']),
      ),
    ).toContain('contract enum-value-removed event notes/note.created.payload.reason="imported"');
  });
  it('catches an event field that may now be null', () => {
    expect(
      eventBreaksAfter(
        (e) => (e['notes/note.created'].$defs.Note.properties.id.type = ['string', 'null']),
      ),
    ).toContain('contract response-type-widened Note.id');
  });
  it('catches a renamed event', () => {
    expect(
      eventBreaksAfter((e) => {
        e['notes/note.added'] = e['notes/note.created'];
        delete e['notes/note.created'];
      }),
    ).toContain('contract event-removed notes/note.created');
  });
  it('passes a new event and a new event property', () => {
    expect(
      eventBreaksAfter((e) => {
        e['notes/note.pinned'] = event();
        e['notes/note.created'].properties.payload.properties.source = { type: 'string' };
      }),
    ).toEqual([]);
  });
});

describe('contract guard: base tag and approvals (a real git repository)', () => {
  let root: string;
  const git = (...args: string[]) =>
    execFileSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@example.invalid', ...args], {
      cwd: root,
      stdio: 'pipe',
    });
  const write = (file: string, text: string) => {
    mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
    writeFileSync(path.join(root, file), text);
  };
  const approvals = (entries: Json[]) =>
    write('docs/contracts/breaking-approved.json', JSON.stringify({ approvals: entries }));
  const removeDelete = () => {
    const doc = base();
    delete doc.paths['/notes/{note_id}'].delete;
    write('packages/contracts/openapi.yaml', JSON.stringify(doc));
  };
  const approval = {
    id: 'contract operation-removed DELETE /notes/{note_id}',
    base: 'v1.0.0',
    decision: 'ADR 0027',
    approved_by: 'twuijri',
    reason: 'test',
  };
  let errors: string[];

  beforeEach(() => {
    root = mkdtempSync(path.join(tmpdir(), 'compat-'));
    git('init', '-q');
    write('packages/contracts/openapi.yaml', openapiText);
    write('packages/contracts/events/notes/note.created.schema.json', eventText);
    write('docs/adr/0027-compatibility-no-breaking-changes.md', '# ADR 0027\n');
    write('docs/contracts/DECISIONS.md', '# Decisions\n\n## 1. One\n');
    git('add', '-A');
    git('commit', '-qm', 'v0.9');
    git('tag', 'v0.9.0');
    git('tag', 'v1.0.0-beta.1');
    git('commit', '-q', '--allow-empty', '-m', 'v1');
    git('tag', 'v1.0.0');
    errors = [];
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(console, 'error').mockImplementation((...a: unknown[]) => {
      errors.push(a.join(' '));
    });
  });
  afterEach(() => {
    vi.restoreAllMocks();
    rmSync(root, { recursive: true, force: true });
  });

  it('compares with the newest release tag by semver', () => {
    expect(resolveBase(root)).toBe('v1.0.0');
    expect(latestReleaseTag(['v1.10.0', 'v1.9.2', 'v1.10.0-rc.1', 'nightly'])).toBe('v1.10.0');
    expect(latestReleaseTag(['v2.0.0-beta.2', 'v2.0.0-beta.10', 'v1.9.0'])).toBe('v2.0.0-beta.10');
  });

  it('passes an unchanged contract and fails a break', () => {
    expect(run(root, 'v1.0.0')).toBe(0);
    removeDelete();
    write('packages/contracts/events/notes/.keep', '');
    rmSync(path.join(root, 'packages/contracts/events/notes/note.created.schema.json'));
    expect(run(root, 'v1.0.0')).toBe(1);
    expect(errors.join('\n')).toContain('contract operation-removed DELETE /notes/{note_id}');
    expect(errors.join('\n')).toContain('contract event-removed notes/note.created');
  });

  it('passes a break the owner approved for this base', () => {
    removeDelete();
    approvals([approval]);
    expect(run(root, 'v1.0.0')).toBe(0);
  });

  it('ignores an approval made against an older release', () => {
    removeDelete();
    approvals([{ ...approval, base: 'v0.9.0' }]);
    expect(run(root, 'v1.0.0')).toBe(1);
  });

  it('refuses an approval without a real decision or not by the owner', () => {
    removeDelete();
    approvals([{ ...approval, decision: 'ADR 0999' }]);
    expect(run(root, 'v1.0.0')).toBe(1);
    expect(errors.join('\n')).toContain('ADR 0999 does not exist');

    approvals([{ ...approval, decision: 'DECISIONS §1', approved_by: 'someone' }]);
    expect(run(root, 'v1.0.0')).toBe(1);
    expect(errors.join('\n')).toContain('approved_by must be the owner');

    approvals([{ ...approval, decision: 'DECISIONS §1' }]);
    expect(run(root, 'v1.0.0')).toBe(0);
  });
});
