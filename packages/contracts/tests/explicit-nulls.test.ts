// Explicit `null` in the request bodies of the generated Kotlin and Swift clients
// (scripts/explicit-nulls.mjs, contract decision §114).
import { describe, expect, it } from 'vitest';
import {
  kotlinModelsToPatch,
  patchKotlinModel,
  patchSwiftModel,
  requestNullPlan,
  withExplicitNullBodies,
  // @ts-expect-error — a plain .mjs script without type declarations
} from '../scripts/explicit-nulls.mjs';
// @ts-expect-error — a plain .mjs script without type declarations
import { loadDocument } from '../scripts/lib.mjs';

type Entry = { required: string[]; optional: string[] };
const plan = () => requestNullPlan(loadDocument()) as Map<string, Entry>;

// The shapes openapi-generator 7.10 writes (kotlin jvm-okhttp4 + kotlinx, swift6).
const kotlinModel = (name: string, props: string, body = '') => `package hub.core.client.model

@Serializable

data class ${name} (

${props}

) {
${body}
}
`;

const SESSION_PATCH_KT = kotlinModel(
  'SessionPatch',
  `    @SerialName(value = "title")
    val title: kotlin.String? = null,

    @SerialName(value = "pinned")
    val pinned: kotlin.Boolean? = null,

    @SerialName(value = "category_id")
    val categoryId: kotlin.String? = null`,
);

const TRIGGER_KT = kotlinModel(
  'ScheduleTrigger',
  `    @SerialName(value = "kind")
    val kind: ScheduleTrigger.Kind,

    @SerialName(value = "every_minutes")
    val everyMinutes: kotlin.Int? = null`,
  `
    @Serializable
    enum class Kind(val value: kotlin.String) {
        @SerialName(value = "cron") CRON("cron");
    }
`,
);

const WRITE_KT = kotlinModel(
  'ScheduleWrite',
  `    @SerialName(value = "name")
    val name: kotlin.String? = null,

    @SerialName(value = "trigger")
    val trigger: ScheduleTrigger? = null,

    @SerialName(value = "nodes")
    val nodes: kotlin.collections.List<ScheduleTrigger>? = null`,
);

const SESSION_PATCH_SWIFT = `import Foundation

public struct SessionPatch: Sendable, Codable, JSONEncodable, Hashable {

    public var title: String?
    public var pinned: Bool?
    public var categoryId: String?

    public init(title: String? = nil, pinned: Bool? = nil, categoryId: String? = nil) {
        self.title = title
        self.pinned = pinned
        self.categoryId = categoryId
    }

    public enum CodingKeys: String, CodingKey, CaseIterable {
        case title
        case pinned
        case categoryId = "category_id"
    }

    // Encodable protocol methods

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(title, forKey: .title)
        try container.encodeIfPresent(pinned, forKey: .pinned)
        try container.encodeIfPresent(categoryId, forKey: .categoryId)
    }
}
`;

describe('requestNullPlan', () => {
  it('splits the nullable properties of every request body into required and optional', () => {
    const p = plan();
    expect(p.get('SessionPatch')).toEqual({
      required: [],
      optional: ['title', 'category_id', 'model', 'provider', 'reasoning_effort', 'working_dir'],
    });
    expect(p.get('TaskPatch')?.optional).toContain('description');
    expect(p.get('ScheduleTrigger')).toEqual({
      required: ['expression', 'every_minutes', 'run_at'],
      optional: [],
    });
    expect(p.get('ScheduleTarget')?.required).toEqual(
      expect.arrayContaining(['model', 'provider', 'workflow_id', 'input']),
    );
    // A model that only holds one of them is listed, so Kotlin can reach the nested nulls.
    expect(p.get('ScheduleWrite')).toEqual({ required: [], optional: [] });
    expect(p.has('Session')).toBe(false);
  });

  it('names inline bodies and inline objects as the generator does, and follows aliases', () => {
    const p = plan();
    expect(p.get('NotifyMarkAllReadRequest')?.optional).toEqual(['before']);
    expect(p.get('SessionBulkUpdatePatch')?.optional).toEqual(['category_id']);
    expect(p.get('SpeechSettingsPatchProvidersInner')?.optional).toEqual(['api_key']);
    expect(p.has('SeatWrite')).toBe(false);
    expect(p.get('SeatConfig')?.optional).toContain('model');
  });

  it('refuses a nullable property it could not reach in a client', () => {
    const doc = {
      paths: {
        '/x': {
          post: {
            operationId: 'x.make',
            requestBody: {
              content: {
                'application/json': {
                  schema: { $ref: '#/components/schemas/Make' },
                },
              },
            },
          },
        },
      },
      components: {
        schemas: {
          Make: {
            type: 'object',
            properties: {
              deep: {
                type: 'array',
                items: {
                  type: 'array',
                  items: { type: 'object', properties: { note: { type: ['string', 'null'] } } },
                },
              },
            },
          },
        },
      },
    };
    expect(() => requestNullPlan(doc)).toThrow(/Make\.deep\[\]\[\]\.note admits null/);
  });

  it('applies the Swift names', () => {
    const doc = {
      paths: {
        '/t': {
          patch: {
            operationId: 't.update',
            requestBody: {
              content: { 'application/json': { schema: { $ref: '#/components/schemas/Task' } } },
            },
          },
        },
      },
      components: {
        schemas: { Task: { type: 'object', properties: { note: { type: ['string', 'null'] } } } },
      },
    };
    const p = requestNullPlan(doc, { renames: { Task: 'HubTask' } }) as Map<string, Entry>;
    expect([...p.keys()]).toEqual(['HubTask']);
  });
});

describe('Kotlin', () => {
  const entries = new Map<string, Entry>([
    ['SessionPatch', { required: [], optional: ['title', 'category_id'] }],
    ['ScheduleTrigger', { required: ['every_minutes'], optional: [] }],
    ['ScheduleWrite', { required: [], optional: [] }],
  ]);
  const sources = new Map([
    ['SessionPatch', SESSION_PATCH_KT],
    ['ScheduleTrigger', TRIGGER_KT],
    ['ScheduleWrite', WRITE_KT],
  ]);

  it('patches the models with nulls and the ones holding them', () => {
    expect([...kotlinModelsToPatch(entries, sources)].sort()).toEqual([
      'ScheduleTrigger',
      'ScheduleWrite',
      'SessionPatch',
    ]);
    expect(() =>
      kotlinModelsToPatch(new Map([['Gone', { required: ['x'], optional: [] }]]), new Map()),
    ).toThrow(/no generated Kotlin model Gone/);
  });

  it('adds sendNull, the Clearable cases and the nulls a body needs', () => {
    const patched = new Set(['SessionPatch', 'ScheduleTrigger', 'ScheduleWrite']);
    const session = patchKotlinModel(
      SESSION_PATCH_KT,
      'SessionPatch',
      entries.get('SessionPatch'),
      patched,
    ) as string;
    expect(session).toContain(
      'val categoryId: kotlin.String? = null,\n\n    /* Optional properties sent as an explicit `null` (contract decision §114). */\n' +
        '    @kotlinx.serialization.Transient\n' +
        '    val sendNull: kotlin.collections.Set<SessionPatch.Clearable> = emptySet()\n\n' +
        ') : hub.core.client.infrastructure.ExplicitNulls {',
    );
    expect(session).toContain('enum class Clearable { TITLE, CATEGORY_ID }');
    expect(session).toContain('onRequest("title", title, Clearable.TITLE in sendNull)');
    expect(session).toContain(
      'onRequest("category_id", categoryId, Clearable.CATEGORY_ID in sendNull)',
    );
    expect(session).not.toContain('("pinned"'); // a property that cannot be null is untouched

    const trigger = patchKotlinModel(
      TRIGGER_KT,
      'ScheduleTrigger',
      entries.get('ScheduleTrigger'),
      patched,
    ) as string;
    expect(trigger).toContain('always("every_minutes", everyMinutes)');
    expect(trigger).not.toContain('sendNull');
    expect(trigger).toContain('enum class Kind'); // the generated body is kept

    const write = patchKotlinModel(
      WRITE_KT,
      'ScheduleWrite',
      entries.get('ScheduleWrite'),
      patched,
    ) as string;
    expect(write).toContain('nested("trigger", trigger)');
    expect(write).toContain('nestedList("nodes", nodes)');
  });

  it('fails loudly when the generator output changes shape', () => {
    const patched = new Set(['SessionPatch']);
    expect(() =>
      patchKotlinModel(
        SESSION_PATCH_KT,
        'SessionPatch',
        { required: [], optional: ['model'] },
        patched,
      ),
    ).toThrow(/no constructor property "model"/);
    expect(() =>
      patchKotlinModel(
        SESSION_PATCH_KT.replace('\n) {', '\n)'),
        'SessionPatch',
        entries.get('SessionPatch'),
        patched,
      ),
    ).toThrow(/closing/);
    expect(() =>
      patchKotlinModel(
        SESSION_PATCH_KT.replace('kotlin.String? = null,', 'kotlin.String,'),
        'SessionPatch',
        entries.get('SessionPatch'),
        patched,
      ),
    ).toThrow(/"title" is not nullable/);
  });

  it('sends every JSON body of ApiClient through ExplicitNulls, once', () => {
    const line = 'Serializer.kotlinxSerializationJson.encodeToString(content)';
    expect(withExplicitNullBodies(`a\n${line}\nb`)).toBe(
      'a\nExplicitNulls.encodeToString(Serializer.kotlinxSerializationJson, content)\nb',
    );
    expect(() => withExplicitNullBodies('nothing')).toThrow(/0 times/);
    expect(() => withExplicitNullBodies(`${line}\n${line}`)).toThrow(/2 times/);
  });
});

describe('Swift', () => {
  it('writes null for a listed optional field and adds sendNull to init', () => {
    const out = patchSwiftModel(SESSION_PATCH_SWIFT, 'SessionPatch', {
      required: [],
      optional: ['title', 'category_id'],
    }) as string;
    expect(out).toContain('case title, categoryId');
    expect(out).toContain('public var sendNull: Set<Clearable> = []');
    expect(out).toContain(
      'public init(title: String? = nil, pinned: Bool? = nil, categoryId: String? = nil, sendNull: Set<Clearable> = []) {\n        self.sendNull = sendNull\n        self.title = title',
    );
    expect(out).toContain(
      'if let value = categoryId { try container.encode(value, forKey: .categoryId) } else if sendNull.contains(.categoryId) { try container.encodeNil(forKey: .categoryId) }',
    );
    expect(out).toContain('try container.encodeIfPresent(pinned, forKey: .pinned)');
  });

  it('always writes a required nullable field', () => {
    const out = patchSwiftModel(SESSION_PATCH_SWIFT, 'SessionPatch', {
      required: ['title'],
      optional: [],
    }) as string;
    expect(out).toContain(
      'if let value = title { try container.encode(value, forKey: .title) } else { try container.encodeNil(forKey: .title) }',
    );
    expect(out).not.toContain('sendNull');
  });

  it('fails loudly when the generator output changes shape', () => {
    expect(() =>
      patchSwiftModel(SESSION_PATCH_SWIFT, 'SessionPatch', { required: [], optional: ['model'] }),
    ).toThrow(/no coding key for "model"/);
    expect(() =>
      patchSwiftModel(
        SESSION_PATCH_SWIFT.replace('encodeIfPresent(title', 'encode(title'),
        'SessionPatch',
        { required: [], optional: ['title'] },
      ),
    ).toThrow(/encodeIfPresent/);
  });
});
