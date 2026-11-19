import { newQuickJSWASMModuleFromVariant, newVariant } from 'quickjs-emscripten-core';
import releaseSyncVariant from '@jitl/quickjs-wasmfile-release-sync';

// Wire protocol (unchanged):
//   stdin  {"source":string,"payloadHex":string,"attrs":object}
//   stdout {"ok":boolean,"events":[{method:string,params:any}],"error":{code,message}|null}
//
// Isolation invariants:
// - The guest is a fresh QuickJS runtime per request. No host function, Node
//   object, process/module/require/fs/network/timer binding is ever installed.
//   Only primitive strings cross the boundary (serialized JSON input and decoder
//   source in, serialized JSON response out).
// - Decoder source, invocation, event validation and response serialization all
//   run inside the same QuickJS eval under one interrupt deadline, so a proxy,
//   getter or toJSON trap cannot be traversed by the Node host.
// - Guest memory is hard-capped at 32 MiB by the WebAssembly heap maximum.
//   QuickJS's own runtime limit is retained, but its Emscripten allocator
//   under-counts large allocations (usable_size is 0), so the WASM heap cap is
//   the effective bound.
// - The runtime has a 32 MiB QuickJS heap limit; the interrupt handler aborts
//   guest execution after 200 ms.

const MAX_INPUT_BYTES = 1_048_576;
const MAX_OUTPUT_BYTES = 262_144;
const EXECUTION_TIMEOUT_MS = 200;
const MEMORY_LIMIT_BYTES = 33_554_432;
const WASM_MEMORY_INITIAL_BYTES = 16_777_216;
const WASM_PAGE_BYTES = 65_536;
const MAX_METHOD_CHARS = 64;

const INPUT_LIMIT_RESPONSE = '{"ok":false,"events":[],"error":{"code":"INPUT_LIMIT","message":"Input exceeds limit"}}';
const OUTPUT_LIMIT_RESPONSE = '{"ok":false,"events":[],"error":{"code":"OUTPUT_LIMIT","message":"Output exceeds limit"}}';

// Trusted guest setup: reconstructs the legacy prelude from the serialized
// request. No decoder code runs here.
const SETUP_SOURCE = `"use strict";
(function () {
  var parse = JSON.parse;
  var input = parse(globalThis.__codecInput);
  delete globalThis.__codecInput;

  var payload;
  if (input.payloadIsJson) {
    payload = input.payloadJson;
  } else {
    var hex = input.payloadHex;
    var bytes = new Uint8Array(hex.length >> 1);
    for (var index = 0; index < bytes.length; index++) {
      var high = hex.charCodeAt(index * 2);
      var low = hex.charCodeAt(index * 2 + 1);
      high = high <= 57 ? high - 48 : (high | 32) - 87;
      low = low <= 57 ? low - 48 : (low | 32) - 87;
      bytes[index] = (high << 4) | low;
    }
    payload = bytes;
  }

  globalThis.__payload = payload;
  globalThis.payloadHex = input.payloadHex;
  globalThis.attrs = input.attrs;

  globalThis.JSON = JSON;
  globalThis.Math = Math;
  globalThis.parseInt = parseInt;
  globalThis.parseFloat = parseFloat;

  globalThis.TTL_EVENT_ALARMS = 'thing.event.alarms';
  globalThis.TTL_EVENT_FAULTS = 'thing.event.faults';
  globalThis.TTL_EVENT_PARAMS = 'thing.event.params';
  globalThis.TTL_EVENT_HEARTBEAT = 'thing.event.heartbeat';
  globalThis.TTL_EVENT_OTHER = 'thing.event.other';

  globalThis.dec2Hex = function(value) {
    let out = '';
    for (const byte of value) out += Number(byte).toString(16).padStart(2, '0');
    return out;
  };

  globalThis.hex2Dec = function(value) {
    if (typeof value === 'number') return value;
    return Number.parseInt(String(value).replace(/^0x/i, ''), 16);
  };

  globalThis.parseData2KeyValue = function(uint8Array) {
    const data = uint8Array.subarray(12, -3);
    const map = Object.create(null);
    let begin = 0;
    for (let index = 0; index < data.length; index++) {
      if (begin + 1 >= data.length) break;
      let key = data[begin].toString(16);
      if (key.length < 2) key = '0' + key;
      const end = begin + 2 + data[begin + 1];
      map[key.toUpperCase()] = dec2Hex(data.subarray(begin + 2, end));
      begin = end;
      if (begin >= data.length) break;
    }
    return map;
  };
})();`;

// Untrusted phase: evaluate the decoder, call it, validate the event list and
// serialize the wire response. The primordials used for serialization are
// captured before decoder code can replace them. Envelope JSON is assembled
// from primitive strings so prototype pollution in the guest cannot rewrite
// the response shape. The interrupt is uncatchable in QuickJS, so a decoder
// cannot swallow the 200 ms deadline.
const DECODE_SOURCE = `"use strict";
(function () {
  var parse = JSON.parse;
  var stringify = JSON.stringify;
  var isArray = Array.isArray;
  var MAX_OUTPUT_CHARS = ${MAX_OUTPUT_BYTES};
  var MAX_METHOD_CHARS = ${MAX_METHOD_CHARS};

  function safeMessage(error) {
    try {
      if (error && typeof error === 'object' && typeof error.message === 'string') return error.message;
      var text = String(error);
      return typeof text === 'string' ? text : '';
    } catch (_) {
      return '';
    }
  }

  function fail(code, message) {
    var text = typeof message === 'string' ? message : '';
    if (text.length > 512) text = text.slice(0, 512);
    return '{"ok":false,"events":[],"error":{"code":' + stringify(code) + ',"message":' + stringify(text) + '}}';
  }

  try {
    var source = globalThis.__codecSource;
    delete globalThis.__codecSource;
    (0, eval)(source);

    var result = typeof rawDataToProtocol === 'function'
      ? rawDataToProtocol(globalThis.__payload)
      : (typeof decode === 'function' ? decode(globalThis.__payload, globalThis.attrs) : undefined);

    if (result === undefined || result === null) throw new Error('Decoder returned no result');
    var decoded = typeof result === 'string' ? parse(result) : result;
    var events = isArray(decoded) ? decoded : [decoded];
    if (!events.length) throw new Error('Decoder returned an invalid event list');

    var eventJson = '';
    var tooLarge = false;
    for (var index = 0; index < events.length; index++) {
      var event = events[index];
      if (!event || typeof event !== 'object') {
        throw new Error('Decoder returned an invalid event list');
      }
      var method = event.method;
      if (typeof method !== 'string') {
        throw new Error('Decoder returned an invalid event list');
      }
      if (method.length > MAX_METHOD_CHARS) {
        return fail('CODEC_INVALID_OUTPUT', 'Decoder event method exceeds 64 characters');
      }
    }
    for (index = 0; index < events.length; index++) {
      var serialized = stringify(events[index]);
      if (typeof serialized !== 'string') serialized = 'null';
      if (eventJson.length + serialized.length > MAX_OUTPUT_CHARS) {
        tooLarge = true;
        break;
      }
      if (index > 0) eventJson += ',';
      eventJson += serialized;
    }
    if (tooLarge) {
      return '{"ok":false,"events":[],"error":{"code":"OUTPUT_LIMIT","message":"Output exceeds limit"}}';
    }

    var envelope = '{"ok":true,"events":[' + eventJson + '],"error":null}';
    if (envelope.length > MAX_OUTPUT_CHARS) {
      return '{"ok":false,"events":[],"error":{"code":"OUTPUT_LIMIT","message":"Output exceeds limit"}}';
    }
    return envelope;
  } catch (error) {
    return fail('CODEC_EXECUTION_FAILED', safeMessage(error) || 'Decoder execution failed');
  }
})();`;

function errorResponse(code, message) {
  return JSON.stringify({
    ok: false,
    events: [],
    error: { code, message: String(message ?? code).slice(0, 512) }
  });
}

async function readRequest() {
  let text = '';
  let bytes = 0;
  process.stdin.setEncoding('utf8');
  for await (const chunk of process.stdin) {
    bytes += Buffer.byteLength(chunk, 'utf8');
    if (bytes > MAX_INPUT_BYTES) return { tooLarge: true };
    text += chunk;
  }
  return { tooLarge: false, text };
}

// Mirrors the legacy payloadBytes vs JSON heuristic. This only parses data
// (TextDecoder + JSON.parse); no decoder or guest code runs on the host.
function decodePayload(payloadHex) {
  const bytes = new Uint8Array(payloadHex.length / 2);
  for (let index = 0; index < bytes.length; index++) {
    bytes[index] = Number.parseInt(payloadHex.slice(index * 2, index * 2 + 2), 16);
  }
  try {
    const decoded = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    const parsed = JSON.parse(decoded);
    if (parsed && typeof parsed === 'object') {
      return { payloadIsJson: true, payloadJson: parsed };
    }
  } catch {
    // Binary codecs receive the raw bytes.
  }
  return { payloadIsJson: false, payloadJson: null };
}

async function main() {
  const request = await readRequest();
  if (request.tooLarge) {
    process.stdout.write(INPUT_LIMIT_RESPONSE);
    return;
  }

  let input;
  try {
    input = JSON.parse(request.text);
  } catch {
    process.stdout.write(errorResponse('INVALID_JSON', 'Invalid JSON'));
    return;
  }

  if (!input
      || typeof input !== 'object'
      || typeof input.source !== 'string'
      || typeof input.payloadHex !== 'string'
      || !input.payloadHex.length
      || input.payloadHex.length % 2
      || !/^[0-9a-f]+$/i.test(input.payloadHex)) {
    process.stdout.write(errorResponse('INVALID_INPUT', 'source and even-length hexadecimal payloadHex are required'));
    return;
  }

  const attrs = input.attrs && typeof input.attrs === 'object' ? input.attrs : {};
  const { payloadIsJson, payloadJson } = decodePayload(input.payloadHex);

  let guestInput;
  try {
    guestInput = JSON.stringify({
      payloadHex: input.payloadHex,
      payloadIsJson,
      payloadJson,
      attrs
    });
  } catch {
    process.stdout.write(errorResponse('INVALID_INPUT', 'Invalid payload or attrs'));
    return;
  }

  let QuickJS;
  try {
    // QuickJS's Emscripten allocator returns usable_size 0, so its own
    // JS_SetMemoryLimit under-counts large allocations. Cap the entire WASM
    // heap at the 32 MiB guest budget: the runtime cannot grow past this, so
    // QuickJS malloc fails and throws a catchable OOM instead of growing RSS
    // without bound. The runtime memory limit below is retained as the
    // documented per-runtime limit.
    const boundedVariant = newVariant(releaseSyncVariant, {
      wasmMemory: new WebAssembly.Memory({
        initial: WASM_MEMORY_INITIAL_BYTES / WASM_PAGE_BYTES,
        maximum: MEMORY_LIMIT_BYTES / WASM_PAGE_BYTES
      })
    });
    QuickJS = await newQuickJSWASMModuleFromVariant(boundedVariant);
  } catch {
    process.stdout.write(errorResponse('CODEC_ENGINE_FAILED', 'Codec engine unavailable'));
    return;
  }

  let runtime;
  let context;
  let deadline = 0;
  let interrupted = false;
  let responseText;
  try {
    runtime = QuickJS.newRuntime();
    runtime.setMemoryLimit(MEMORY_LIMIT_BYTES);
    runtime.setInterruptHandler(() => {
      if (Date.now() > deadline) {
        interrupted = true;
        return true;
      }
      return false;
    });

    context = runtime.newContext();

    const inputHandle = context.newString(guestInput);
    try {
      context.setProp(context.global, '__codecInput', inputHandle);
    } finally {
      inputHandle.dispose();
    }

    deadline = Date.now() + EXECUTION_TIMEOUT_MS;
    const setupResult = context.evalCode(SETUP_SOURCE, 'legacy-prelude.js', { type: 'global' });
    const setupFailed = Boolean(setupResult.error);
    const setupInterrupted = interrupted;
    setupResult.dispose();
    if (setupFailed) {
      responseText = setupInterrupted
        ? errorResponse('CODEC_TIMEOUT', 'Decoder setup exceeded 200ms')
        : errorResponse('CODEC_EXECUTION_FAILED', 'Decoder setup failed');
    } else {
      const sourceHandle = context.newString(input.source);
      try {
        context.setProp(context.global, '__codecSource', sourceHandle);
      } finally {
        sourceHandle.dispose();
      }

      interrupted = false;
      deadline = Date.now() + EXECUTION_TIMEOUT_MS;
      const decodeResult = context.evalCode(DECODE_SOURCE, 'legacy-decoder.js', { type: 'global' });
      const decodeFailed = Boolean(decodeResult.error);
      const decodeInterrupted = interrupted;
      if (!decodeFailed) {
        try {
          if (context.typeof(decodeResult.value) === 'string') {
            responseText = context.getString(decodeResult.value);
          } else {
            responseText = errorResponse('CODEC_EXECUTION_FAILED', 'Decoder returned a non-string response');
          }
        } finally {
          decodeResult.dispose();
        }
      } else {
        decodeResult.dispose();
        responseText = decodeInterrupted
          ? errorResponse('CODEC_TIMEOUT', 'Decoder execution exceeded 200ms')
          : errorResponse('CODEC_EXECUTION_FAILED', 'Decoder execution failed');
      }
    }
  } catch {
    responseText = interrupted
      ? errorResponse('CODEC_TIMEOUT', 'Decoder execution exceeded 200ms')
      : errorResponse('CODEC_EXECUTION_FAILED', 'Decoder execution failed');
  } finally {
    // Dispose guest handles and the runtime even when an exception escaped.
    // Cleanup failure must not replace the protocol response.
    try {
      context?.dispose();
    } catch {
      // The process is exiting; the guest heap cannot be observed further.
    }
    try {
      runtime?.dispose();
    } catch {
      // Ignore teardown assertions after a failed guest run.
    }
  }

  // The guest produced a primitive JSON string; the host never traverses guest
  // objects. Enforce the byte limit before writing.
  if (typeof responseText !== 'string' || Buffer.byteLength(responseText, 'utf8') > MAX_OUTPUT_BYTES) {
    process.stdout.write(OUTPUT_LIMIT_RESPONSE);
    return;
  }
  process.stdout.write(responseText);
}

await main();
