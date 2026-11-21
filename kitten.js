// kitten.js: KittenTTS 2 nano on this device, with ONNX Runtime Web.
//
// KittenTTS 2 nano is one small model per language (about 17 MB int8, or 9 MB int4).
// Input is phonemes in the model's 119-symbol IPA vocab; this module follows the
// official Python pipeline (kittenml/kittentts2_tiny/model.py):
//   tokens = [0] + phoneme ids + [10, 0]
//   style  = the voice pack's row for that token count (pack: float32 [510, 256])
//   model(input_ids, style, speed) -> waveform (24 kHz), durations per token
//   trim the end token's frames (and the start pause, except on the first chunk),
//   5 ms fades, 0.15 s of silence between chunks.

// Versions are pinned so a CDN or model update can't break the app.
// 1.29 and later fail to build this model's WebGPU session ("Provider type for Cast node ... is not set").
const ORT_VERSION = '1.22.0';
const ORT_BASE = `https://cdn.jsdelivr.net/npm/onnxruntime-web@${ORT_VERSION}/dist/`;
const REPO = 'https://huggingface.co/KittenML/kitten-tts-2-nano/resolve/0843ae614780b04e4260c91b601e374c4b81250d/';
const CACHE_NAME = 'lectern-kitten-v1';

export const SAMPLE_RATE = 24000;
const GAP_S = 0.15;
const END_TOKEN = 10;
const MAX_PHON = 350; // longest chunk the model trained on (p90)

/** Approximate download sizes, for progress when the server sends no length. */
export const SIZES = { int8: 17.4e6, int4: 8.8e6, voices: 2.1e6 };

const modelFile = (lang, weights) => `kitten_tts_2_nano_${lang}${weights === 'int4' ? '_int4' : ''}.onnx`;

/* ------------------------------------------------------------------ */
/* Downloads, kept in the Cache API so they happen once                */
/* ------------------------------------------------------------------ */

async function openCache() {
  try {
    return await caches.open(CACHE_NAME);
  } catch {
    return null; // private mode or blocked storage: download every time
  }
}

/** url -> Uint8Array, from the cache when present. onProgress(loaded, total). */
export async function fetchBytes(url, onProgress, expected = 0) {
  const cache = await openCache();
  try {
    const hit = await cache?.match(url);
    if (hit) return new Uint8Array(await hit.arrayBuffer());
  } catch {}
  const res = await fetch(url);
  if (!res.ok) throw new Error(`Download failed (${res.status}) for ${url.split('/').pop()}`);
  const total = Number(res.headers.get('content-length')) || expected;
  let bytes;
  if (res.body && onProgress) {
    const reader = res.body.getReader();
    const parts = [];
    let loaded = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      parts.push(value);
      loaded += value.length;
      onProgress(loaded, Math.max(total, loaded));
    }
    bytes = new Uint8Array(loaded);
    let o = 0;
    for (const p of parts) {
      bytes.set(p, o);
      o += p.length;
    }
  } else bytes = new Uint8Array(await res.arrayBuffer());
  try {
    await cache?.put(url, new Response(bytes, { headers: { 'content-type': 'application/octet-stream' } }));
  } catch {
    /* storage full or blocked; fine */
  }
  return bytes;
}

/* ------------------------------------------------------------------ */
/* Voice packs: .npz (a zip of .npy arrays)                            */
/* ------------------------------------------------------------------ */

function parseNpy(bytes) {
  const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  if (v.getUint8(0) !== 0x93 || String.fromCharCode(...bytes.subarray(1, 6)) !== 'NUMPY') throw new Error('Not an .npy file');
  const major = v.getUint8(6);
  const headerLen = major === 1 ? v.getUint16(8, true) : v.getUint32(8, true);
  const start = major === 1 ? 10 : 12;
  const header = new TextDecoder().decode(bytes.subarray(start, start + headerLen));
  if (!/'descr':\s*'<f4'/.test(header)) throw new Error('Voice pack is not float32: ' + header);
  const shape = (header.match(/'shape':\s*\(([^)]*)\)/)?.[1] || '').split(',').map((s) => s.trim()).filter(Boolean).map(Number);
  const off = start + headerLen;
  const data = new Float32Array(bytes.slice(off, off + shape.reduce((a, b) => a * b, 1) * 4).buffer);
  return { shape, data };
}

async function loadVoices(bytes, JSZip) {
  const zip = await JSZip.loadAsync(bytes);
  const packs = {};
  for (const name of Object.keys(zip.files)) {
    if (!name.endsWith('.npy')) continue;
    packs[name.slice(0, -4)] = parseNpy(await zip.files[name].async('uint8array'));
  }
  return packs;
}

/* ------------------------------------------------------------------ */
/* The engine                                                          */
/* ------------------------------------------------------------------ */

const ortPromises = {};
// 'wasm' is the CPU-only runtime; 'webgpu' is the larger one that also has the GPU backend.
function loadOrt(device) {
  const file = device === 'webgpu' ? 'ort.webgpu.min.mjs' : 'ort.wasm.min.mjs';
  return (ortPromises[file] ||= import(ORT_BASE + file)
    .then((ort) => {
      ort.env.wasm.wasmPaths = ORT_BASE;
      // Threads need a cross-origin-isolated page; otherwise ORT runs single-threaded.
      ort.env.wasm.numThreads = self.crossOriginIsolated ? Math.min(4, navigator.hardwareConcurrency || 1) : 1;
      return ort;
    })
    .catch((err) => {
      delete ortPromises[file];
      throw err;
    }));
}

export class Kitten {
  /**
   * weights: 'int8' (default) or 'int4'.
   * device: 'auto' (GPU when the browser has WebGPU, else CPU), 'gpu' or 'cpu'.
   * hasWebGPU: whether navigator.gpu gave an adapter. JSZip: the JSZip class (voice packs are zips).
   */
  constructor({ weights = 'int8', device = 'auto', hasWebGPU = false, JSZip }) {
    this.weights = weights;
    this.devices = device === 'cpu' || !hasWebGPU ? ['wasm'] : ['webgpu', 'wasm'];
    this.JSZip = JSZip;
    this.vocab = null;
    this.langs = new Map(); // lang -> { ort, session, packs, device }
    this.loading = new Map(); // lang -> Promise
  }

  /** 'GPU' or 'CPU': where a loaded language runs. */
  label(lang) {
    return this.langs.get(lang)?.device === 'webgpu' ? 'GPU' : 'CPU';
  }

  /** Downloads (once) and starts the model and voices of a language. onProgress(loaded, total). */
  load(lang, onProgress) {
    if (this.langs.has(lang)) return Promise.resolve();
    if (!this.loading.has(lang)) {
      const p = this.#load(lang, onProgress).finally(() => this.loading.delete(lang));
      this.loading.set(lang, p);
    }
    return this.loading.get(lang);
  }

  async #load(lang, onProgress) {
    const files = [
      [REPO + modelFile(lang, this.weights), SIZES[this.weights]],
      [REPO + `voices_${lang}.npz`, SIZES.voices],
    ];
    const seen = files.map(([, size]) => ({ loaded: 0, total: size }));
    const report = (i) => (loaded, total) => {
      seen[i] = { loaded, total };
      onProgress?.(seen.reduce((a, s) => a + s.loaded, 0), seen.reduce((a, s) => a + s.total, 0));
    };
    const [vocab, model, voices] = await Promise.all([
      this.vocab || fetchBytes(REPO + 'vocab.json').then((b) => JSON.parse(new TextDecoder().decode(b))),
      fetchBytes(files[0][0], report(0), files[0][1]),
      fetchBytes(files[1][0], report(1), files[1][1]),
    ]);
    this.vocab = vocab;
    const packs = await loadVoices(voices, this.JSZip);
    const errors = [];
    for (const device of this.devices) {
      try {
        const ort = await loadOrt(device);
        const session = await ort.InferenceSession.create(model.slice(), {
          executionProviders: [device],
          graphOptimizationLevel: 'all',
        });
        const entry = { ort, session, packs, device };
        // One short run compiles the GPU shaders now, and proves the backend really works.
        await this.#infer(entry, this.tokens('ˈA.'), Object.values(packs)[0], 1);
        this.langs.set(lang, entry);
        return;
      } catch (err) {
        console.warn(`KittenTTS ${device} backend failed`, err);
        errors.push(`${device === 'webgpu' ? 'GPU' : 'CPU'}: ${err?.message || err}`);
      }
    }
    throw new Error(errors.join(' | '));
  }

  async #infer({ ort, session }, tok, pack, speed) {
    const [rows, dim] = pack.shape;
    const row = Math.min(tok.length - 1, rows - 1);
    const out = await session.run({
      input_ids: new ort.Tensor('int64', BigInt64Array.from(tok, BigInt), [1, tok.length]),
      style: new ort.Tensor('float32', pack.data.slice(row * dim, (row + 1) * dim), [1, dim]),
      speed: new ort.Tensor('float32', new Float32Array([speed]), [1]),
    });
    return { wav: out[session.outputNames[0]].data, dur: Array.from(out[session.outputNames[1]].data, Number) };
  }

  /** Phonemes -> token ids, dropping symbols the model has no row for. */
  tokens(phonemes) {
    const ids = [0];
    for (const ch of phonemes) {
      if (ch in this.vocab) ids.push(this.vocab[ch]);
      else if (ch === '(' || ch === ')') ids.push(this.vocab[',']);
    }
    ids.push(END_TOKEN, 0);
    return ids;
  }

  async #chunk(lang, voice, phonemes, speed, keepStart) {
    const entry = this.langs.get(lang);
    const pack = entry.packs[voice];
    if (!pack) throw new Error(`No voice "${voice}" for ${lang}`);
    const { wav, dur } = await this.#infer(entry, this.tokens(phonemes), pack, speed);

    const total = dur.reduce((a, b) => a + b, 0);
    const spf = Math.max(1, Math.round(wav.length / Math.max(total, 1))); // samples per duration frame
    const lead = keepStart ? 0 : Math.max(0, dur[0] - 1) * spf;
    const trail = Math.max(0, dur.at(-1) - 1) * spf;
    let w = lead + trail + spf < wav.length ? wav.slice(lead, wav.length - trail) : wav.slice();
    for (let i = 0; i < w.length; i++) w[i] = Math.max(-1, Math.min(1, w[i]));
    const f = Math.min(120, Math.floor(w.length / 8)); // ~5 ms fades
    for (let i = 0; i < f; i++) {
      w[i] *= i / (f - 1 || 1);
      w[w.length - 1 - i] *= i / (f - 1 || 1);
    }
    return w;
  }

  /** Phonemes of one sentence -> Float32Array at 24 kHz. Long input is cut at spaces. */
  async generate(phonemes, { lang, voice, speed = 1 }) {
    await this.load(lang);
    const chunks = [];
    let ps = phonemes.trim();
    while (ps.length > MAX_PHON) {
      let cut = ps.lastIndexOf(' ', MAX_PHON);
      if (cut <= 0) cut = MAX_PHON;
      chunks.push(ps.slice(0, cut));
      ps = ps.slice(cut).trim();
    }
    if (ps) chunks.push(ps);
    if (!chunks.length) return new Float32Array(0);
    const parts = [];
    for (const [i, c] of chunks.entries()) parts.push(await this.#chunk(lang, voice, c, speed, i === 0));
    const gap = Math.round(GAP_S * SAMPLE_RATE);
    const out = new Float32Array(parts.reduce((n, p) => n + p.length, 0) + gap * (parts.length - 1));
    let o = 0;
    for (const p of parts) {
      out.set(p, o);
      o += p.length + gap;
    }
    return out;
  }
}
