// Explicit `null` in the request bodies of the generated Kotlin and Swift clients
// (docs/contracts/DECISIONS.md §114).
//
// Both clients leave a `nil`/`null` property out of the JSON they send: Kotlin's JSON options
// say `explicitNulls = false` (generate-native.mjs) so a PATCH built from a model does not
// clear what it did not mean to touch, and Swift's generated `encode(to:)` says
// `encodeIfPresent`. So a client could never say "set this to null", and a body whose
// contract requires a property that may be null (a schedule's `trigger` and `target`) was
// refused with 400. The contract decides, per request-body schema:
//
// - a property that is REQUIRED and admits `null` is always written — `null` when unset;
// - a property that is OPTIONAL and admits `null` stays absent when unset, and is written as
//   `null` only when the caller lists it in the model's `sendNull` set
//   (`SessionPatch(model = null, sendNull = setOf(SessionPatch.Clearable.MODEL))`,
//   `SessionPatch(model: nil, sendNull: [.model])`); a listed property that holds a value is
//   sent with its value.
//
// Response decoding does not change. The patch reads the generator's output and fails loudly
// when a line it expects is not there, like the multipart and serializer patches.
//
//   import { requestNullPlan, patchKotlinModel, patchSwiftModel } from './explicit-nulls.mjs';

import { admitsNull } from './kotlin-openapi.mjs';

const SCHEMA_REF = '#/components/schemas/';
const METHODS = ['get', 'put', 'post', 'delete', 'options', 'head', 'patch', 'trace'];

/** `notify.markAllRead` → `NotifyMarkAllRead`, `category_id` → `CategoryId`, as the generator names inline models. */
export function pascal(operationId) {
  return String(operationId)
    .split(/[^A-Za-z0-9]+/)
    .filter(Boolean)
    .map((part) => part[0].toUpperCase() + part.slice(1))
    .join('');
}

const refName = (ref) =>
  typeof ref === 'string' && ref.startsWith(SCHEMA_REF) ? ref.slice(SCHEMA_REF.length) : null;

/** The properties and `required` of an object schema, merging `allOf` parts and their `$ref`s. */
function ownShape(schema, components, seen = new Set()) {
  const out = { properties: {}, required: new Set() };
  if (!schema || typeof schema !== 'object') return out;
  const name = refName(schema.$ref);
  if (name) {
    if (seen.has(name)) return out;
    seen.add(name);
    return ownShape(components[name], components, seen);
  }
  for (const part of schema.allOf ?? []) {
    const inner = ownShape(part, components, seen);
    Object.assign(out.properties, inner.properties);
    for (const r of inner.required) out.required.add(r);
  }
  Object.assign(out.properties, schema.properties ?? {});
  for (const r of Array.isArray(schema.required) ? schema.required : []) out.required.add(r);
  return out;
}

/**
 * Every model a JSON request body can hold, with the properties that admit `null`:
 * `Map<modelName, { required: string[], optional: string[] }>` (JSON names). Models reached
 * from a request body without such a property are listed too (with empty lists), so a
 * client can find the models nested inside them. An inline body is named as the generator
 * names it (`<OperationId>Request`). A nested inline object that has a nullable property
 * throws: give it a component name so both clients can carry it.
 *
 * `renames` maps a component to the name the client gives it (Swift's `Task` → `HubTask`).
 */
export function requestNullPlan(doc, { renames = {} } = {}) {
  const components = doc?.components?.schemas ?? {};
  const plan = new Map();
  const seen = new Set();

  // `name` is the client's name for the model: a component's (renamed) name, `<Op>Request`
  // for an inline body, `<Model><Property>` for an inline object property and
  // `<Model><Property>Inner` for the inline items of an array property (as the generator
  // names them).
  const addModel = (name, schema) => {
    if (seen.has(name)) return;
    seen.add(name);
    const { properties, required } = ownShape(schema, components);
    const entry = { required: [], optional: [] };
    for (const [prop, propSchema] of Object.entries(properties)) {
      if (propSchema?.readOnly === true) continue;
      if (admitsNull(propSchema, components))
        (required.has(prop) ? entry.required : entry.optional).push(prop);
    }
    plan.set(name, entry);
    for (const part of schema?.allOf ?? []) walkInto(part, `${name}.allOf`);
    for (const key of ['oneOf', 'anyOf'])
      for (const part of schema?.[key] ?? []) walkInto(part, name);
    for (const [prop, propSchema] of Object.entries(properties)) {
      if (propSchema?.properties && !propSchema.$ref)
        addModel(`${name}${pascal(prop)}`, propSchema);
      else if (propSchema?.items?.properties && !propSchema.items.$ref)
        addModel(`${name}${pascal(prop)}Inner`, propSchema.items);
      else walkInto(propSchema, `${name}.${prop}`);
    }
  };
  const addComponent = (name) => {
    // A component that is only a `$ref` (`SeatWrite: $ref SeatConfig`) is generated as its target.
    const hops = new Set();
    while (components[name] && Object.keys(components[name]).join() === '$ref' && !hops.has(name)) {
      hops.add(name);
      name = refName(components[name].$ref) ?? name;
    }
    if (components[name]) addModel(renames[name] ?? name, components[name]);
  };

  // A schema below a model: a `$ref` is a model of its own; an inline object is part of the
  // model it sits in and may not carry a nullable property.
  function walkInto(schema, where) {
    if (!schema || typeof schema !== 'object') return;
    const name = refName(schema.$ref);
    if (name) return addComponent(name);
    if (schema.properties && !where.endsWith('.allOf')) {
      const { properties } = ownShape(schema, components);
      for (const [prop, propSchema] of Object.entries(properties)) {
        if (propSchema?.readOnly !== true && admitsNull(propSchema, components))
          throw new Error(
            `explicit-nulls: ${where}.${prop} admits null inside an inline object of a request body; ` +
              'make that object a component so the generated clients can send its null',
          );
        walkInto(propSchema, `${where}.${prop}`);
      }
    }
    for (const key of ['oneOf', 'anyOf', 'allOf'])
      for (const part of schema[key] ?? []) walkInto(part, where);
    if (schema.items) walkInto(schema.items, `${where}[]`);
    if (schema.additionalProperties && typeof schema.additionalProperties === 'object')
      walkInto(schema.additionalProperties, `${where}{}`);
  }

  for (const item of Object.values(doc?.paths ?? {})) {
    for (const method of METHODS) {
      const op = item?.[method];
      const content = op?.requestBody?.content ?? {};
      for (const [type, media] of Object.entries(content)) {
        if (!/[/+]json$/.test(type) || !media?.schema) continue;
        const schema = media.schema;
        const name = refName(schema.$ref);
        if (name) addComponent(name);
        else if (schema.properties || schema.allOf)
          addModel(`${pascal(op.operationId)}Request`, schema);
        else walkInto(schema, `${op.operationId} body`);
      }
    }
  }
  return plan;
}

// ------------------------------------------------------------------------------------ Kotlin

const KOTLIN_INTERFACE = 'hub.core.client.infrastructure.ExplicitNulls';
const KOTLIN_JSON_ELEMENT = 'kotlinx.serialization.json.JsonElement';

/** `category_id` → `CATEGORY_ID`. */
export const kotlinCase = (json) => json.replace(/[^A-Za-z0-9]+/g, '_').toUpperCase();

/** The constructor properties of a generated Kotlin model: JSON name → { name, type }. */
export function kotlinProperties(source) {
  const out = new Map();
  const re =
    /@SerialName\(value = "([^"]+)"\)\s*\n\s*val (`?\w+`?): ([^\n]+?)(?:\s+=\s+[^\n]+?)?,?\n/g;
  for (const m of source.matchAll(re)) out.set(m[1], { name: m[2], type: m[3].trim() });
  return out;
}

/** The model named by a property type, and how it is held: `one`, `list` or `map`. */
export function kotlinHeld(type) {
  const bare = type.replace(/\?$/, '');
  let m = /^(?:kotlin\.collections\.)?(?:List|Set)<(\w+)>$/.exec(bare);
  if (m) return { model: m[1], shape: 'list' };
  m = /^(?:kotlin\.collections\.)?Map<kotlin\.String, (\w+)>$/.exec(bare);
  if (m) return { model: m[1], shape: 'map' };
  m = /^(\w+)$/.exec(bare);
  return m ? { model: m[1], shape: 'one' } : null;
}

const CTOR_END = /^\)( : ([^\n{]+))? \{$/m;

/**
 * Adds explicit nulls to one generated model (`name`, source `source`): the model implements
 * `ExplicitNulls`, lists its optional nullable properties in `enum class Clearable`, takes a
 * `sendNull` set, and puts back the nulls its body needs — its own and those of the models it
 * holds (`patched` is every model patched this way). Throws when the source is not shaped as
 * expected.
 */
export function patchKotlinModel(source, name, entry, patched) {
  const where = `explicit-nulls: Kotlin model ${name}`;
  if (!source.includes(`data class ${name} (`))
    throw new Error(`${where}: no "data class ${name} ("`);
  const end = CTOR_END.exec(source);
  if (!end) throw new Error(`${where}: the constructor's closing ") {" moved`);
  const ctor = source.slice(0, end.index);
  const props = kotlinProperties(ctor);
  const need = (json) => {
    const prop = props.get(json);
    if (!prop) throw new Error(`${where}: no constructor property "${json}"`);
    if (!prop.type.endsWith('?'))
      throw new Error(`${where}: "${json}" is not nullable (${prop.type})`);
    return prop;
  };
  if (/\benum class Clearable\b|\bval sendNull\b/.test(source))
    throw new Error(`${where}: already has a Clearable enum or a sendNull property`);

  const lines = [];
  for (const json of entry.required) lines.push(`always("${json}", ${need(json).name})`);
  for (const json of entry.optional)
    lines.push(
      `onRequest("${json}", ${need(json).name}, Clearable.${kotlinCase(json)} in sendNull)`,
    );
  for (const [json, prop] of props) {
    const held = kotlinHeld(prop.type);
    if (!held || !patched.has(held.model)) continue;
    const call = { one: 'nested', list: 'nestedList', map: 'nestedMap' }[held.shape];
    lines.push(`${call}("${json}", ${prop.name})`);
  }

  // The constructor: a `sendNull` parameter after the last property.
  let head = ctor;
  if (entry.optional.length) {
    const close = head.lastIndexOf('\n\n');
    if (close < 0 || head.slice(close).trim() !== '')
      throw new Error(`${where}: the constructor does not end with a blank line`);
    head =
      head.slice(0, close) +
      ',\n\n    /* Optional properties sent as an explicit `null` (contract decision §114). */\n' +
      '    @kotlinx.serialization.Transient\n' +
      `    val sendNull: kotlin.collections.Set<${name}.Clearable> = emptySet()` +
      head.slice(close);
  }
  const supertypes = end[2] ? `${end[2]}, ${KOTLIN_INTERFACE}` : KOTLIN_INTERFACE;
  const body = [
    '',
    ...(entry.optional.length
      ? [
          '    /** The optional properties this body may set to `null`; list them in [sendNull]. */',
          `    enum class Clearable { ${entry.optional.map(kotlinCase).join(', ')} }`,
          '',
        ]
      : []),
    `    override fun withExplicitNulls(json: ${KOTLIN_JSON_ELEMENT}): ${KOTLIN_JSON_ELEMENT} =`,
    `        ${KOTLIN_INTERFACE}.edit(json) {`,
    ...lines.map((line) => `            ${line}`),
    '        }',
  ].join('\n');
  return `${head}) : ${supertypes} {\n${body}\n${source.slice(end.index + end[0].length)}`;
}

/**
 * Which Kotlin models to patch: every request model with a nullable property, and every
 * request model holding one of those (so the nulls reach nested bodies). `sources` maps a
 * model name to its generated source; a request model with properties must have one.
 */
export function kotlinModelsToPatch(plan, sources) {
  const patched = new Set();
  for (const [name, entry] of plan) {
    if (!entry.required.length && !entry.optional.length) continue;
    if (!sources.has(name)) throw new Error(`explicit-nulls: no generated Kotlin model ${name}`);
    patched.add(name);
  }
  for (let grew = true; grew;) {
    grew = false;
    for (const name of plan.keys()) {
      if (patched.has(name) || !sources.has(name)) continue;
      const src = sources.get(name);
      const end = CTOR_END.exec(src);
      if (!src.includes(`data class ${name} (`) || !end) continue;
      for (const prop of kotlinProperties(src.slice(0, end.index)).values()) {
        const held = kotlinHeld(prop.type);
        if (held && patched.has(held.model)) {
          patched.add(name);
          grew = true;
          break;
        }
      }
    }
  }
  return patched;
}

/** `infrastructure/ExplicitNulls.kt`, written next to the generated client. */
export const KOTLIN_EXPLICIT_NULLS_FILE = `package hub.core.client.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.encodeToString

/**
 * Explicit \`null\` in request bodies (contract decision §114; written by
 * packages/contracts/scripts/explicit-nulls.mjs). The JSON options leave every \`null\` out
 * (\`explicitNulls = false\`); a request model implementing this puts back the ones its
 * contract asks for: a required property that may be null is always sent, \`null\` when
 * unset; an optional one only when listed in the model's \`sendNull\`. [ApiClient] sends
 * every JSON body through [encodeToString].
 */
interface ExplicitNulls {
    /** [json] (this model as the JSON options wrote it) with the nulls this body needs. */
    fun withExplicitNulls(json: JsonElement): JsonElement

    class Editor internal constructor(private val fields: MutableMap<String, JsonElement>) {
        fun always(name: String, value: Any?) {
            if (value == null) fields[name] = JsonNull
        }

        fun onRequest(name: String, value: Any?, requested: Boolean) {
            if (value == null && requested) fields[name] = JsonNull
        }

        fun nested(name: String, value: ExplicitNulls?) {
            val json = fields[name] ?: return
            if (value != null) fields[name] = value.withExplicitNulls(json)
        }

        fun nestedList(name: String, value: Collection<ExplicitNulls>?) {
            val json = fields[name] as? JsonArray ?: return
            fields[name] = withNulls(json, value?.toList() ?: return)
        }

        fun nestedMap(name: String, value: Map<String, ExplicitNulls>?) {
            val json = fields[name] as? JsonObject ?: return
            val map = value ?: return
            fields[name] = JsonObject(json.mapValues { (key, item) -> map[key]?.withExplicitNulls(item) ?: item })
        }
    }

    companion object {
        /** [json]'s fields changed by [block]; anything but an object is returned as it is. */
        fun edit(json: JsonElement, block: Editor.() -> Unit): JsonElement {
            val fields = (json as? JsonObject)?.toMutableMap() ?: return json
            Editor(fields).block()
            return JsonObject(fields)
        }

        /** The items of [json] with the nulls of the matching [items]. */
        fun withNulls(json: JsonArray, items: List<*>): JsonArray =
            JsonArray(json.mapIndexed { i, item -> (items.getOrNull(i) as? ExplicitNulls)?.withExplicitNulls(item) ?: item })

        /** [value] as the JSON body the hub receives, with the nulls its contract asks for. */
        inline fun <reified T> encodeToString(json: Json, value: T): String {
            // The type is named: after a smart cast the inferred one would be Any, which has no serializer.
            val nulls = value as? ExplicitNulls
            val items = (value as? List<*>)?.takeIf { list -> list.any { it is ExplicitNulls } }
            if (nulls == null && items == null) return json.encodeToString<T>(value)
            val tree = json.encodeToJsonElement<T>(value)
            val sent = when {
                nulls != null -> nulls.withExplicitNulls(tree)
                tree is JsonArray && items != null -> withNulls(tree, items)
                else -> tree
            }
            return json.encodeToString(JsonElement.serializer(), sent)
        }
    }
}
`;

export const KOTLIN_BODY_ANCHOR = 'Serializer.kotlinxSerializationJson.encodeToString(content)';
export const KOTLIN_BODY =
  'ExplicitNulls.encodeToString(Serializer.kotlinxSerializationJson, content)';

/** `ApiClient.kt` sending JSON bodies through `ExplicitNulls`; throws when the line moved. */
export function withExplicitNullBodies(source) {
  const count = source.split(KOTLIN_BODY_ANCHOR).length - 1;
  if (count !== 1)
    throw new Error(`ApiClient.kt has "${KOTLIN_BODY_ANCHOR}" ${count} times, expected once`);
  return source.replace(KOTLIN_BODY_ANCHOR, KOTLIN_BODY);
}

// ------------------------------------------------------------------------------------- Swift

/** The `CodingKeys` of a generated Swift model: JSON name → case name. */
export function swiftCodingKeys(source) {
  const block =
    /public enum CodingKeys: String, CodingKey, CaseIterable \{\n([\s\S]*?)\n\s*\}/.exec(source);
  if (!block) return null;
  const out = new Map();
  for (const m of block[1].matchAll(/^\s*case (`?\w+`?)(?: = "([^"]+)")?\s*$/gm))
    out.set(m[2] ?? m[1].replaceAll('`', ''), m[1]);
  return out;
}

/**
 * Adds explicit nulls to one generated Swift model: its `encode(to:)` writes `null` for a
 * required nullable property that is `nil`, and for an optional one listed in `sendNull`
 * (`public var sendNull: Set<Clearable>`, also a parameter of `init`). Nested models are
 * encoded through their own `encode(to:)`, so nothing else is needed. Throws when the source
 * is not shaped as expected.
 */
export function patchSwiftModel(source, name, entry) {
  const where = `explicit-nulls: Swift model ${name}`;
  if (!source.includes(`public struct ${name}:`))
    throw new Error(`${where}: no "public struct ${name}:"`);
  const keys = swiftCodingKeys(source);
  if (!keys) throw new Error(`${where}: no CodingKeys`);
  if (/\benum Clearable\b|\bvar sendNull\b/.test(source))
    throw new Error(`${where}: already has a Clearable enum or a sendNull property`);
  let out = source;
  const encodeLine = (json) => {
    const key = keys.get(json);
    if (!key) throw new Error(`${where}: no coding key for "${json}"`);
    const re = new RegExp(
      `^(\\s*)try container\\.encodeIfPresent\\((\`?\\w+\`?), forKey: \\.${key.replaceAll('`', '\\`')}\\)$`,
      'm',
    );
    const m = re.exec(out);
    if (!m) throw new Error(`${where}: no "encodeIfPresent(…, forKey: .${key})" for "${json}"`);
    return { m, key };
  };
  for (const json of entry.required) {
    const { m, key } = encodeLine(json);
    out = out.replace(
      m[0],
      `${m[1]}if let value = ${m[2]} { try container.encode(value, forKey: .${key}) } else { try container.encodeNil(forKey: .${key}) }`,
    );
  }
  for (const json of entry.optional) {
    const { m, key } = encodeLine(json);
    out = out.replace(
      m[0],
      `${m[1]}if let value = ${m[2]} { try container.encode(value, forKey: .${key}) } else if sendNull.contains(.${key}) { try container.encodeNil(forKey: .${key}) }`,
    );
  }
  if (!entry.optional.length) return out;

  // `sendNull` and its `Clearable` cases, before the memberwise init.
  const init = /^([ \t]*)public init\((?!from decoder)(.*)\) \{$/m.exec(out);
  if (!init) throw new Error(`${where}: no memberwise "public init(…) {"`);
  const indent = init[1];
  const cases = entry.optional.map((json) => keys.get(json)).join(', ');
  const params = init[2].trim()
    ? `${init[2]}, sendNull: Set<Clearable> = []`
    : 'sendNull: Set<Clearable> = []';
  const replacement = [
    `${indent}/** The optional properties this body may set to \`null\` (contract decision §114). */`,
    `${indent}public enum Clearable: String, Sendable, Hashable, CaseIterable {`,
    `${indent}    case ${cases}`,
    `${indent}}`,
    `${indent}/** Listed optional properties that are \`nil\` are sent as \`null\` instead of left out. */`,
    `${indent}public var sendNull: Set<Clearable> = []`,
    '',
    `${indent}public init(${params}) {`,
    `${indent}    self.sendNull = sendNull`,
  ].join('\n');
  out = out.slice(0, init.index) + replacement + out.slice(init.index + init[0].length);
  return out;
}
