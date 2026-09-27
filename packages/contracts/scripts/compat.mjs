#!/usr/bin/env node
// The contract compatibility guard (ADR 0027): compares `openapi.yaml` and the realtime event
// schemas with the latest release tag `v*` and fails on a change that would break an app or a
// script already talking to a released hub. Additions pass; removals, renames, newly required
// input, narrowed input and widened output do not.
//
//   node packages/contracts/scripts/compat.mjs                 # against the latest v* tag
//   node packages/contracts/scripts/compat.mjs --base v1.1.0   # against any git ref
//   node packages/contracts/scripts/compat.mjs --root <repo>   # another checkout (tests)
//
// Direction matters. What a client SENDS (parameters, request bodies) may only accept more;
// what a client RECEIVES (responses, realtime events, webhook payloads) may only promise more.
// A break the owner approved is listed by its id in docs/contracts/breaking-approved.json.
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parse as parseYaml } from 'yaml';

import {
  argValue,
  listAt,
  listHere,
  loadApprovals,
  report,
  resolveBase,
  showAt,
} from '../../../scripts/compat-base.mjs';

export const PREFIX = 'contract ';
const OPENAPI = 'packages/contracts/openapi.yaml';
const EVENTS = 'packages/contracts/events';
const METHODS = ['get', 'put', 'post', 'delete', 'patch', 'head', 'options', 'trace'];

// ------------------------------------------------------------------ schema helpers

function pointer(root, ref) {
  if (typeof ref !== 'string' || !ref.startsWith('#/')) return undefined;
  return ref
    .slice(2)
    .split('/')
    .map((p) => p.replace(/~1/g, '/').replace(/~0/g, '~'))
    .reduce((node, key) => (node == null ? undefined : node[key]), root);
}

const refName = (ref) => ref.split('/').pop();

/** Follows `$ref` chains; returns `{}` for a dangling reference (the linter reports those). */
function deref(root, schema) {
  let s = schema;
  for (let i = 0; s && typeof s === 'object' && s.$ref && i < 32; i += 1) s = pointer(root, s.$ref);
  return s && typeof s === 'object' ? s : {};
}

const isNullOnly = (root, s) => {
  const d = deref(root, s);
  return (
    d.type === 'null' || (Array.isArray(d.type) && d.type.length === 1 && d.type[0] === 'null')
  );
};

/** The members of `oneOf` / `anyOf` other than a plain `null`. */
function variants(root, s) {
  const list = s.oneOf ?? s.anyOf;
  return Array.isArray(list) ? list.filter((b) => !isNullOnly(root, b)) : [];
}

const jsonType = (v) =>
  v === null
    ? 'null'
    : Array.isArray(v)
      ? 'array'
      : typeof v === 'number'
        ? Number.isInteger(v)
          ? 'integer'
          : 'number'
        : typeof v;

/** The JSON types a schema admits, or null when it admits anything. */
function typesOf(root, schema, depth = 0) {
  const s = deref(root, schema);
  if (depth > 16) return null;
  let out = null;
  if (s.const !== undefined) out = new Set([jsonType(s.const)]);
  else if (Array.isArray(s.enum)) out = new Set(s.enum.map(jsonType));
  else if (s.type) out = new Set(Array.isArray(s.type) ? s.type : [s.type]);
  else if (Array.isArray(s.oneOf ?? s.anyOf)) {
    out = new Set();
    for (const b of s.oneOf ?? s.anyOf) {
      const t = typesOf(root, b, depth + 1);
      if (t === null) return null;
      t.forEach((x) => out.add(x));
    }
  } else if (Array.isArray(s.allOf)) {
    for (const b of s.allOf) {
      const t = typesOf(root, b, depth + 1);
      if (t !== null) {
        out = t;
        break;
      }
    }
    if (out === null && s.allOf.length) out = new Set(['object']);
  } else if (s.properties || s.additionalProperties !== undefined || s.required) {
    out = new Set(['object']);
  } else if (s.items) out = new Set(['array']);
  if (out && s.nullable === true) out.add('null');
  return out;
}

const admits = (types, t) =>
  types === null || types.has(t) || (t === 'integer' && types.has('number'));

/** The allowed values when a schema is a closed set (`enum`, `const`, or a union of them). */
function enumOf(root, schema, depth = 0) {
  const s = deref(root, schema);
  if (depth > 16) return null;
  if (Array.isArray(s.enum)) return s.enum.filter((v) => v !== null);
  if (s.const !== undefined) return s.const === null ? [] : [s.const];
  const list = s.oneOf ?? s.anyOf;
  if (Array.isArray(list)) {
    const vals = [];
    for (const b of list) {
      if (isNullOnly(root, b)) continue;
      const e = enumOf(root, b, depth + 1);
      if (e === null) return null;
      vals.push(...e);
    }
    return vals.length ? vals : null;
  }
  return null;
}

/** An object schema with its `allOf` parts merged; each property remembers where it is declared. */
function flatten(root, schema, loc, depth = 0) {
  const s = deref(root, schema);
  const out = { props: new Map(), required: new Set(), additional: s.additionalProperties };
  if (depth > 16) return out;
  for (const part of s.allOf ?? []) {
    const partLoc = part?.$ref ? refName(part.$ref) : loc;
    const f = flatten(root, part, partLoc, depth + 1);
    f.props.forEach((v, k) => out.props.set(k, v));
    f.required.forEach((k) => out.required.add(k));
    if (f.additional !== undefined && out.additional === undefined) out.additional = f.additional;
  }
  for (const [name, prop] of Object.entries(s.properties ?? {}))
    out.props.set(name, { schema: prop, loc: `${loc}.${name}` });
  for (const name of s.required ?? []) out.required.add(name);
  return out;
}

function variantKey(root, branch) {
  if (branch?.$ref) return refName(branch.$ref);
  const s = deref(root, branch);
  for (const [name, prop] of Object.entries(s.properties ?? {})) {
    const e = enumOf(root, prop);
    if (e && e.length === 1) return `${name}=${JSON.stringify(e[0])}`;
  }
  const t = typesOf(root, s);
  return t ? [...t].sort().join('|') : 'any';
}

const show = (v) => JSON.stringify(v);

// ------------------------------------------------------------------ comparison

class Diff {
  constructor(oldRoot, newRoot) {
    this.oldRoot = oldRoot;
    this.newRoot = newRoot;
    this.breaks = new Map();
    this.seen = new Set();
  }

  add(kind, loc, message) {
    const id = `${PREFIX}${kind} ${loc}`;
    if (!this.breaks.has(id)) this.breaks.set(id, { id, kind, loc, message });
  }

  /** Compare two schemas read by the side named in `dir`: 'request' (hub reads) or 'response'. */
  schema(oldS, newS, loc, dir) {
    if (!oldS || !newS || typeof oldS !== 'object' || typeof newS !== 'object') return;
    if (oldS.$ref || newS.$ref) {
      const key = `${dir}|${oldS.$ref ?? loc}|${newS.$ref ?? loc}`;
      if (this.seen.has(key)) return;
      this.seen.add(key);
      // Both sides named: report at the shared shape, once for every place that uses it.
      const next = oldS.$ref && newS.$ref ? refName(newS.$ref) : loc;
      this.schema(deref(this.oldRoot, oldS), deref(this.newRoot, newS), next, dir);
      return;
    }
    const o = oldS;
    const n = newS;
    this.types(o, n, loc, dir);
    this.enums(o, n, loc, dir);
    if (dir === 'request') this.constraints(o, n, loc);

    const ov = variants(this.oldRoot, o);
    const nv = variants(this.newRoot, n);
    if (ov.length > 1 || nv.length > 1) {
      // A closed set of constants is an enum and was compared above.
      if (enumOf(this.oldRoot, o) && enumOf(this.newRoot, n)) return;
      this.variants(ov, nv, loc, dir);
      return;
    }
    if (ov.length === 1 || nv.length === 1) {
      // `X` ⇄ `oneOf: [X, null]`: nullability was judged above; compare X with X.
      this.schema(ov[0] ?? stripUnion(o), nv[0] ?? stripUnion(n), loc, dir);
      return;
    }
    const ot = typesOf(this.oldRoot, o);
    const nt = typesOf(this.newRoot, n);
    if (ot?.has('object') && nt?.has('object')) this.object(o, n, loc, dir);
    if (o.items && n.items) this.schema(o.items, n.items, `${loc}[]`, dir);
  }

  types(o, n, loc, dir) {
    const ot = typesOf(this.oldRoot, o);
    const nt = typesOf(this.newRoot, n);
    if (dir === 'request') {
      if (nt === null) return;
      const lost = ot === null ? ['any'] : [...ot].filter((t) => !admits(nt, t));
      if (lost.length)
        this.add(
          'request-type-narrowed',
          loc,
          `the hub no longer accepts ${lost.join(', ')} here (now ${[...nt].join(', ')})`,
        );
    } else {
      if (nt === null) {
        if (ot !== null)
          this.add('response-type-widened', loc, 'may now be any type; clients expect a known one');
        return;
      }
      const gained = [...nt].filter((t) => !admits(ot, t));
      if (gained.length)
        this.add(
          'response-type-widened',
          loc,
          `may now be ${gained.join(', ')}, which clients built for the release do not expect`,
        );
    }
  }

  enums(o, n, loc, dir) {
    const oe = enumOf(this.oldRoot, o);
    const ne = enumOf(this.newRoot, n);
    if (oe && ne) {
      const keep = new Set(ne.map(show));
      for (const v of oe)
        if (!keep.has(show(v)))
          this.add('enum-value-removed', `${loc}=${show(v)}`, `the value ${show(v)} was removed`);
    } else if (!oe && ne && dir === 'request') {
      this.add('request-enum-restricted', loc, `now accepts only ${ne.map(show).join(', ')}`);
    }
  }

  constraints(o, n, loc) {
    const upper = ['maxLength', 'maxItems', 'maxProperties', 'maximum', 'exclusiveMaximum'];
    const lower = ['minLength', 'minItems', 'minProperties', 'minimum', 'exclusiveMinimum'];
    for (const k of upper)
      if (typeof n[k] === 'number' && !(typeof o[k] === 'number' && n[k] >= o[k]))
        this.add(
          'request-constraint-tightened',
          `${loc}:${k}`,
          `${k} is now ${n[k]} (was ${o[k] ?? 'none'})`,
        );
    for (const k of lower)
      if (typeof n[k] === 'number' && !(typeof o[k] === 'number' && n[k] <= o[k]))
        this.add(
          'request-constraint-tightened',
          `${loc}:${k}`,
          `${k} is now ${n[k]} (was ${o[k] ?? 'none'})`,
        );
    if (n.pattern !== undefined && n.pattern !== o.pattern)
      this.add(
        'request-constraint-tightened',
        `${loc}:pattern`,
        `pattern is now ${n.pattern} (was ${o.pattern ?? 'none'})`,
      );
    if (o.default !== undefined && show(o.default) !== show(n.default))
      this.add(
        'request-default-changed',
        loc,
        `the default changed from ${show(o.default)} to ${show(n.default)}: an unchanged client now gets other behaviour`,
      );
  }

  variants(ov, nv, loc, dir) {
    const oKeys = new Map(ov.map((b) => [variantKey(this.oldRoot, b), b]));
    const nKeys = new Map(nv.map((b) => [variantKey(this.newRoot, b), b]));
    const oLeft = [...oKeys.keys()].filter((k) => !nKeys.has(k));
    const nLeft = [...nKeys.keys()].filter((k) => !oKeys.has(k));
    // One variant each side unmatched: the same variant under a new name; compare the shapes.
    if (oLeft.length === 1 && nLeft.length === 1) {
      this.schema(oKeys.get(oLeft[0]), nKeys.get(nLeft[0]), `${loc}|${nLeft[0]}`, dir);
    } else {
      for (const k of oLeft)
        this.add('variant-removed', `${loc}|${k}`, `the variant ${k} was removed`);
    }
    for (const [k, b] of oKeys) if (nKeys.has(k)) this.schema(b, nKeys.get(k), `${loc}|${k}`, dir);
  }

  object(o, n, loc, dir) {
    const of = flatten(this.oldRoot, o, loc);
    const nf = flatten(this.newRoot, n, loc);
    for (const [name, op] of of.props) {
      const np = nf.props.get(name);
      if (!np) {
        this.add(
          'property-removed',
          op.loc,
          dir === 'request'
            ? 'the hub no longer reads this field; clients that send it change behaviour'
            : 'clients built for the release read this field',
        );
        continue;
      }
      if (dir === 'response' && of.required.has(name) && !nf.required.has(name))
        this.add('response-property-optional', np.loc, 'was always present; clients rely on it');
      this.schema(op.schema, np.schema, np.loc, dir);
    }
    if (dir === 'request') {
      for (const name of nf.required)
        if (!of.required.has(name))
          this.add(
            'request-property-required',
            nf.props.get(name)?.loc ?? `${loc}.${name}`,
            'is now required; clients built for the release do not send it',
          );
      if (of.additional !== false && nf.additional === false)
        this.add('request-closed', loc, 'no longer accepts fields it did not list');
    }
    if (isSchema(of.additional) && isSchema(nf.additional))
      this.schema(of.additional, nf.additional, `${loc}{}`, dir);
  }

  // ---------------------------------------------------------------- operations

  document(oldDoc, newDoc) {
    const oldOps = operations(oldDoc);
    const newOps = operations(newDoc);
    for (const [key, old] of oldOps) {
      const now = newOps.get(key);
      const label = `${old.method.toUpperCase()} ${old.path}`;
      if (!now) {
        this.add('operation-removed', label, `${old.op.operationId ?? label} was removed`);
        continue;
      }
      this.operation(old, now, `${now.method.toUpperCase()} ${now.path}`);
    }
    const oldHooks = oldDoc.webhooks ?? {};
    const newHooks = newDoc.webhooks ?? {};
    for (const [name, item] of Object.entries(oldHooks)) {
      if (!newHooks[name]) {
        this.add('webhook-removed', `webhook ${name}`, 'receivers built for the release expect it');
        continue;
      }
      for (const method of METHODS) {
        if (!item[method]) continue;
        const now = newHooks[name][method];
        if (!now) {
          this.add('webhook-removed', `webhook ${name} ${method}`, 'receivers expect it');
          continue;
        }
        // The hub sends webhooks: their headers and body are read by the receiver.
        const op = parameters(oldDoc, item, item[method], '');
        const np = parameters(newDoc, newHooks[name], now, '');
        for (const [k, p] of op)
          if (!np.has(k))
            this.add('parameter-removed', `webhook ${name} ${p.in}.${p.name}`, 'receivers read it');
        this.bodies(
          oldDoc,
          newDoc,
          item[method].requestBody,
          now.requestBody,
          `webhook ${name}`,
          'response',
        );
      }
    }
  }

  operation(old, now, label) {
    const oo = old.op;
    const no = now.op;
    if (isPublic(old.doc, oo) && !isPublic(now.doc, no))
      this.add('operation-auth-required', label, 'now needs a signed-in caller');
    const oRoles = oo['x-roles'];
    const nRoles = no['x-roles'];
    if (Array.isArray(nRoles)) {
      const lost = Array.isArray(oRoles) ? oRoles.filter((r) => !nRoles.includes(r)) : ['any role'];
      if (lost.length)
        this.add('operation-roles-narrowed', label, `no longer allowed for ${lost.join(', ')}`);
    }
    if (oo['x-scope'] === 'global' && no['x-scope'] !== 'global')
      this.add('operation-profile-required', label, 'now needs X-Hub-Profile');

    const op = parameters(old.doc, old.item, oo, old.path);
    const np = parameters(now.doc, now.item, no, now.path);
    for (const [key, p] of op) {
      const q = np.get(key);
      if (!q) {
        this.add(
          'parameter-removed',
          `${label} ${p.in}.${p.name}`,
          'clients built for the release send it',
        );
        continue;
      }
      this.schema(p.schema, q.schema, `${label} ${q.in}.${q.name}`, 'request');
    }
    for (const [key, q] of np) {
      const p = op.get(key);
      if (q.required && !p?.required)
        this.add(
          'parameter-required',
          `${label} ${q.in}.${q.name}`,
          'clients built for the release do not send it',
        );
    }

    const ob = oo.requestBody ? deref(old.doc, oo.requestBody) : null;
    const nb = no.requestBody ? deref(now.doc, no.requestBody) : null;
    if (nb?.required && !ob?.required)
      this.add('request-body-required', label, 'a body is now required');
    if (ob && !nb)
      this.add('request-body-removed', label, 'the hub no longer reads the body clients send');
    if (ob && nb) this.bodies(old.doc, now.doc, ob, nb, `${label} body`, 'request');

    const oRes = oo.responses ?? {};
    const nRes = no.responses ?? {};
    for (const [code, res] of Object.entries(oRes)) {
      const now2 = nRes[code];
      if (!now2) {
        if (/^[23]/.test(code))
          this.add(
            'response-status-removed',
            `${label} ${code}`,
            'clients built for the release handle this answer',
          );
        continue;
      }
      this.bodies(old.doc, now.doc, res, now2, `${label} ${code}`, 'response');
    }
  }

  bodies(oldDoc, newDoc, oldBody, newBody, loc, dir) {
    const ob = deref(oldDoc, oldBody);
    const nb = deref(newDoc, newBody);
    for (const [media, content] of Object.entries(ob.content ?? {})) {
      const now = nb.content?.[media];
      if (!now) {
        this.add(
          `${dir}-media-type-removed`,
          `${loc} ${media}`,
          `${media} is no longer ${dir === 'request' ? 'accepted' : 'returned'}`,
        );
        continue;
      }
      if (content.schema && now.schema) this.schema(content.schema, now.schema, loc, dir);
    }
  }
}

const isSchema = (v) => v && typeof v === 'object';
const stripUnion = (s) => {
  const rest = { ...s };
  delete rest.oneOf;
  delete rest.anyOf;
  return rest;
};

function isPublic(doc, op) {
  const sec = op.security ?? doc.security ?? [];
  return sec.length === 0 || sec.some((req) => req && Object.keys(req).length === 0);
}

/** `METHOD /path/{}` → operation; path parameter names do not change the wire. */
function operations(doc) {
  const out = new Map();
  for (const [p, item] of Object.entries(doc.paths ?? {})) {
    for (const method of METHODS) {
      if (!item?.[method]) continue;
      out.set(`${method} ${p.replace(/\{[^}]+\}/g, '{}')}`, {
        doc,
        item,
        op: item[method],
        method,
        path: p,
      });
    }
  }
  return out;
}

/** Parameters of an operation by wire identity: path parameters by position, others by name. */
function parameters(doc, item, op, pathTemplate) {
  const slots = [...pathTemplate.matchAll(/\{([^}]+)\}/g)].map((m) => m[1]);
  const byName = new Map();
  for (const raw of [...(item.parameters ?? []), ...(op.parameters ?? [])]) {
    const p = deref(doc, raw);
    if (!p.name || !p.in) continue;
    byName.set(`${p.in}:${p.name}`, p);
  }
  const out = new Map();
  for (const p of byName.values()) {
    const key =
      p.in === 'path'
        ? `path#${slots.indexOf(p.name)}`
        : `${p.in}:${p.in === 'header' ? p.name.toLowerCase() : p.name}`;
    out.set(key, {
      in: p.in,
      name: p.name,
      required: p.in === 'path' || p.required === true,
      schema: p.schema,
    });
  }
  return out;
}

// ------------------------------------------------------------------ entry points

/** Breaks between two OpenAPI documents. */
export function compareOpenApi(oldDoc, newDoc) {
  const d = new Diff(oldDoc, newDoc);
  d.document(oldDoc, newDoc);
  return [...d.breaks.values()];
}

/** Breaks between two sets of realtime event schemas, keyed `<namespace>/<event>`. */
export function compareEvents(oldEvents, newEvents) {
  const out = new Map();
  for (const [key, oldSchema] of Object.entries(oldEvents)) {
    const newSchema = newEvents[key];
    if (!newSchema) {
      const id = `${PREFIX}event-removed ${key}`;
      out.set(id, {
        id,
        kind: 'event-removed',
        loc: key,
        message: 'clients built for the release listen for it',
      });
      continue;
    }
    const d = new Diff(oldSchema, newSchema);
    d.schema(oldSchema, newSchema, `event ${key}`, 'response');
    d.breaks.forEach((b, id) => out.set(id, b));
  }
  return [...out.values()];
}

const eventKey = (file) => {
  const m = /^packages\/contracts\/events\/(.+)\.schema\.json$/.exec(file);
  return m && m[1] !== 'common' ? m[1] : null;
};

function loadEvents(files, read) {
  const out = {};
  for (const file of files) {
    const key = eventKey(file);
    if (key) out[key] = JSON.parse(read(file));
  }
  return out;
}

/** Runs the guard against `base` in the checkout at `root`; returns the exit code. */
export function run(root, base) {
  const oldText = showAt(root, base, OPENAPI);
  if (oldText === null) {
    console.log(`contracts:compat  OK — ${base} has no ${OPENAPI}; nothing to compare`);
    return 0;
  }
  const oldDoc = parseYaml(oldText);
  const newDoc = parseYaml(readFileSync(path.join(root, OPENAPI), 'utf8'));
  const oldEvents = loadEvents(listAt(root, base, EVENTS), (f) => showAt(root, base, f));
  const newEvents = loadEvents(listHere(root, EVENTS), (f) =>
    readFileSync(path.join(root, f), 'utf8'),
  );
  const breaks = [...compareOpenApi(oldDoc, newDoc), ...compareEvents(oldEvents, newEvents)];
  console.log(
    `contracts:compat  compared ${OPENAPI} and ${Object.keys(oldEvents).length} event schemas with ${base}`,
  );
  return report('contracts:compat', base, breaks, loadApprovals(root), PREFIX);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const root = path.resolve(
    argValue(args, '--root') ??
      path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..'),
  );
  if (!existsSync(path.join(root, OPENAPI))) {
    console.error(`contracts:compat  FAILED: ${OPENAPI} not found under ${root}`);
    process.exit(1);
  }
  try {
    process.exit(run(root, resolveBase(root, argValue(args, '--base'))));
  } catch (err) {
    console.error(`contracts:compat  FAILED: ${err.message}`);
    process.exit(1);
  }
}
