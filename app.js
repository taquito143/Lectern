// app.js: Lectern. Upload a book, hear it read aloud, all in the browser.
//
// Flow: file -> parseFile() -> sections of sentences -> Kokoro TTS (on this
// device) -> Web Audio queue that plays while the next sentences are generated.

import { parseFile, splitIntoUnits, countWords, formatDuration, WORDS_PER_MINUTE } from './parsers.js';
import { phonemizeSpanish, looksSpanish } from './spanish.js';

/* ------------------------------------------------------------------ */
/* Config. Versions are pinned so a CDN update can't break the app.    */
/* ------------------------------------------------------------------ */

const KOKORO_URL = 'https://cdn.jsdelivr.net/npm/kokoro-js@1.2.1/dist/kokoro.web.js';
const PDFJS_BASE = 'https://cdn.jsdelivr.net/npm/pdfjs-dist@4.10.38/legacy/build/';
const JSZIP_URL = 'https://cdn.jsdelivr.net/npm/jszip@3.10.1/+esm';
const MODEL_ID = 'onnx-community/Kokoro-82M-v1.0-ONNX';
// ONNX Runtime files that kokoro-js loads at runtime (must match its bundled transformers version).
const ORT_BASE = 'https://cdn.jsdelivr.net/npm/@huggingface/transformers@3.5.1/dist/';

const LOOKAHEAD_SECONDS = 40; // how far ahead of the playhead to keep audio generated
const GAP_SENTENCE = 0.06; // seconds of silence between sentences
const GAP_PARAGRAPH = 0.45; // ...and between paragraphs

const VOICES = [
  ['Spanish', [['ef_dora', 'Dora'], ['em_alex', 'Alex'], ['em_santa', 'Santa']]],
  ['American English, female', [['af_heart', 'Heart'], ['af_bella', 'Bella'], ['af_nicole', 'Nicole'], ['af_sarah', 'Sarah'], ['af_sky', 'Sky'], ['af_nova', 'Nova'], ['af_aoede', 'Aoede'], ['af_kore', 'Kore'], ['af_alloy', 'Alloy'], ['af_jessica', 'Jessica'], ['af_river', 'River']]],
  ['American English, male', [['am_adam', 'Adam'], ['am_michael', 'Michael'], ['am_fenrir', 'Fenrir'], ['am_puck', 'Puck'], ['am_echo', 'Echo'], ['am_eric', 'Eric'], ['am_liam', 'Liam'], ['am_onyx', 'Onyx']]],
  ['British English, female', [['bf_emma', 'Emma'], ['bf_isabella', 'Isabella'], ['bf_alice', 'Alice'], ['bf_lily', 'Lily']]],
  ['British English, male', [['bm_george', 'George'], ['bm_lewis', 'Lewis'], ['bm_daniel', 'Daniel'], ['bm_fable', 'Fable']]],
];
const SPEEDS = [0.8, 0.9, 1, 1.1, 1.25, 1.5];
const isSpanishVoice = (id) => id.startsWith('e');
const DEFAULT_VOICE = { en: 'af_heart', es: 'ef_dora' };

// Public domain (Miguel de Cervantes, 1605)
const SAMPLE_TEXT_ES = `Capítulo I

En un lugar de la Mancha, de cuyo nombre no quiero acordarme, no ha mucho tiempo que vivía un hidalgo de los de lanza en astillero, adarga antigua, rocín flaco y galgo corredor.

Una olla de algo más vaca que carnero, salpicón las más noches, duelos y quebrantos los sábados, lantejas los viernes, algún palomino de añadidura los domingos, consumían las tres partes de su hacienda.

Frisaba la edad de nuestro hidalgo con los cincuenta años; era de complexión recia, seco de carnes, enjuto de rostro, gran madrugador y amigo de la caza.`;

// Public domain (Jane Austen, 1813)
const SAMPLE_TEXT = `Chapter 1

It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife.

However little known the feelings or views of such a man may be on his first entering a neighbourhood, this truth is so well fixed in the minds of the surrounding families, that he is considered the rightful property of some one or other of their daughters.

"My dear Mr. Bennet," said his lady to him one day, "have you heard that Netherfield Park is let at last?"

Mr. Bennet replied that he had not.`;

/* ------------------------------------------------------------------ */
/* Helpers                                                             */
/* ------------------------------------------------------------------ */

const $ = (id) => document.getElementById(id);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const clamp = (n, lo, hi) => Math.max(lo, Math.min(hi, n));
const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)').matches;

const store = {
  get(key, fallback) {
    try {
      const v = localStorage.getItem('lectern:' + key);
      return v === null ? fallback : JSON.parse(v);
    } catch {
      return fallback;
    }
  },
  set(key, value) {
    try {
      localStorage.setItem('lectern:' + key, JSON.stringify(value));
    } catch {
      /* storage can be blocked; the app still works */
    }
  },
};

// Heavy libraries load only when needed.
let pdfjsPromise;
let jszipPromise;
const libs = {
  pdfjs: () =>
    (pdfjsPromise ||= (async () => {
      const mod = await import(PDFJS_BASE + 'pdf.min.mjs');
      // Browsers refuse cross-origin workers, so hand pdf.js a same-origin blob URL.
      const blob = await (await fetch(PDFJS_BASE + 'pdf.worker.min.mjs')).blob();
      mod.GlobalWorkerOptions.workerSrc = URL.createObjectURL(blob);
      return mod;
    })()),
  JSZip: () => (jszipPromise ||= import(JSZIP_URL).then((m) => m.default)),
};

/* ------------------------------------------------------------------ */
/* State                                                               */
/* ------------------------------------------------------------------ */

const state = {
  book: null, // { title, sections: [{ title, paras, units, words, selected }] }
  bookKey: '',
  playlist: [], // [[sectionIndex, unitIndex], ...] for the selected sections
  wordPrefix: [0], // cumulative words along the playlist, for "time left"
  pos: 0, // playlist index of the sentence being heard
  status: 'idle', // idle | playing | paused | done
  voice: store.get('voice', 'af_heart'),
  speed: store.get('speed', 1),
  engine: store.get('engine', 'auto'), // auto | cpu
};

let statusOverride = ''; // model download / export messages
let hasWebGPU = false;
const IS_MOBILE =
  /Android|iPhone|iPad|iPod/i.test(navigator.userAgent) ||
  !!navigator.userAgentData?.mobile ||
  (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1); // iPad in desktop mode

/* ------------------------------------------------------------------ */
/* Elements                                                            */
/* ------------------------------------------------------------------ */

const el = {
  shell: $('shell'),
  fileInput: $('file'),
  busy: $('busy'),
  alert: $('alert'),
  alertText: $('alertText'),
  hero: $('hero'),
  stage: $('stage'),
  reader: $('reader'),
  readerHead: $('readerHead'),
  text: $('text'),
  bookTitle: $('bookTitle'),
  bookMeta: $('bookMeta'),
  list: $('sectionList'),
  play: $('play'),
  prev: $('prev'),
  next: $('next'),
  scrub: $('scrub'),
  scrubLabel: $('scrubLabel'),
  timeLeft: $('timeLeft'),
  voice: $('voice'),
  speed: $('speed'),
  engine: $('engine'),
  engineHint: $('engineHint'),
  status: $('status'),
  load: $('load'),
  cancel: $('cancel'),
  player: $('player'),
  rail: $('rail'),
  volume: $('volume'),
  volumeValue: $('volumeValue'),
  themeToggle: $('themeToggle'),
};

/* ------------------------------------------------------------------ */
/* Alerts and busy state                                               */
/* ------------------------------------------------------------------ */

function showAlert(message) {
  el.alertText.textContent = message;
  el.alert.hidden = false;
}
function clearAlert() {
  el.alert.hidden = true;
}
function showBusy(message) {
  el.busy.textContent = message;
  el.busy.hidden = false;
}
function hideBusy() {
  el.busy.hidden = true;
}

/* ------------------------------------------------------------------ */
/* Opening a file                                                      */
/* ------------------------------------------------------------------ */

async function openFile(file) {
  clearAlert();
  stopAll();
  showBusy(`Reading ${file.name}…`);
  try {
    const book = await parseFile(file, libs, (page, total) => showBusy(`Reading page ${page} of ${total}…`));
    for (const s of book.sections) {
      s.units = splitIntoUnits(s.paras);
      s.words = countWords(s.paras);
      s.selected = true;
    }
    book.sections = book.sections.filter((s) => s.units.length);
    if (!book.sections.length) throw new Error('No readable text found in this file.');
    matchVoiceToBook(book);
    loadBook(book, `${file.name}:${file.size}`);
  } catch (err) {
    console.error(err);
    showAlert(err?.message || 'That file could not be read.');
  } finally {
    hideBusy();
  }
}

// A Spanish book read by an English voice (or the reverse) is unintelligible, so switch
// to the last voice used for the book's language.
function matchVoiceToBook(book) {
  const sample = book.sections.flatMap((s) => s.paras).join(' ').slice(0, 20000);
  const lang = looksSpanish(sample) ? 'es' : 'en';
  const voiceLang = isSpanishVoice(state.voice) ? 'es' : 'en';
  if (lang === voiceLang) return;
  setVoice(store.get('voice:' + lang, DEFAULT_VOICE[lang]));
}

function setVoice(id) {
  state.voice = id;
  el.voice.value = id;
  store.set('voice', id);
  store.set('voice:' + (isSpanishVoice(id) ? 'es' : 'en'), id);
}

function loadBook(book, key) {
  state.book = book;
  state.bookKey = key;
  state.pos = 0;
  state.status = 'idle';
  shownSection = -1;
  rebuildPlaylist();

  const saved = store.get('pos:' + key, null);
  if (saved) {
    const idx = state.playlist.findIndex(([s, u]) => s === saved.sec && u === saved.u);
    if (idx > 0) state.pos = idx;
  }

  el.shell.classList.remove('empty');
  document.body.classList.add('has-book');
  el.hero.hidden = true;
  el.reader.hidden = false;
  el.rail.hidden = false;
  el.player.classList.remove('off');
  el.bookTitle.textContent = book.title;
  document.title = `${book.title} – Lectern`;
  renderRail();
  updateBookMeta();
  setActive(state.pos, { scroll: false });
  el.stage.scrollTop = 0;
  renderControls();
  refreshStatus();
}

/* ------------------------------------------------------------------ */
/* Playlist                                                            */
/* ------------------------------------------------------------------ */

function rebuildPlaylist(keepPosition = false) {
  const current = state.playlist[state.pos];
  state.playlist = [];
  state.wordPrefix = [0];
  state.book.sections.forEach((s, si) => {
    if (!s.selected) return;
    s.units.forEach((u, ui) => {
      state.playlist.push([si, ui]);
      state.wordPrefix.push(state.wordPrefix[state.wordPrefix.length - 1] + u.text.split(/\s+/).length);
    });
  });
  if (keepPosition && current) {
    let idx = state.playlist.findIndex(([s, u]) => s === current[0] && u === current[1]);
    if (idx < 0) idx = state.playlist.findIndex(([s]) => s >= current[0]);
    state.pos = idx < 0 ? 0 : idx;
  } else {
    state.pos = 0;
  }
  updateScrubber();
}

const unitAt = (idx) => {
  const entry = state.playlist[idx];
  return entry ? state.book.sections[entry[0]].units[entry[1]] : null;
};

function indexOfUnit(si, ui) {
  return state.playlist.findIndex(([s, u]) => s === si && u === ui);
}

/* ------------------------------------------------------------------ */
/* Rail: sections                                                      */
/* ------------------------------------------------------------------ */

const downloadIcon =
  '<svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path d="M12 3v12m0 0l-4.5-4.5M12 15l4.5-4.5M5 20h14" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>';

function renderRail() {
  const frag = document.createDocumentFragment();
  state.book.sections.forEach((s, i) => {
    const li = document.createElement('li');
    li.dataset.i = i;

    const box = document.createElement('input');
    box.type = 'checkbox';
    box.checked = s.selected;
    box.setAttribute('aria-label', `Include “${s.title}”`);
    box.addEventListener('change', () => {
      s.selected = box.checked;
      onSelectionChanged();
    });

    const go = document.createElement('button');
    go.type = 'button';
    go.className = 'sec';
    const t = document.createElement('span');
    t.className = 't';
    t.textContent = s.title;
    const d = document.createElement('span');
    d.className = 'd';
    d.textContent = formatDuration((s.words / WORDS_PER_MINUTE) * 60);
    go.append(t, d);
    go.addEventListener('click', () => goToSection(i));

    const save = document.createElement('button');
    save.type = 'button';
    save.className = 'icon';
    save.title = 'Save this section as a WAV file';
    save.setAttribute('aria-label', `Save “${s.title}” as a WAV file`);
    save.innerHTML = downloadIcon;
    save.addEventListener('click', () => exportSections([i]));

    li.append(box, go, save);
    frag.append(li);
  });
  el.list.replaceChildren(frag);
}

function markCurrentSection(si) {
  for (const li of el.list.children) li.classList.toggle('current', Number(li.dataset.i) === si);
  const cur = el.list.children[si];
  if (cur) {
    const r = cur.getBoundingClientRect();
    const c = el.rail.getBoundingClientRect();
    if (r.top < c.top + 120 || r.bottom > c.bottom - 60) cur.scrollIntoView({ block: 'center', behavior: reduceMotion ? 'auto' : 'smooth' });
  }
}

function updateBookMeta() {
  const picked = state.book.sections.filter((s) => s.selected);
  const words = picked.reduce((n, s) => n + s.words, 0);
  const n = state.book.sections.length;
  el.bookMeta.textContent = picked.length
    ? `${picked.length} of ${n} ${n === 1 ? 'section' : 'sections'} selected, about ${formatDuration((words / WORDS_PER_MINUTE) * 60)} of audio`
    : 'No sections selected';
}

function onSelectionChanged() {
  const wasActive = state.status === 'playing';
  const current = state.playlist[state.pos];
  rebuildPlaylist(true);
  updateBookMeta();
  if (!state.playlist.length) {
    stopAll();
    renderControls();
    return;
  }
  if (!current || state.playlist[state.pos]?.[0] !== current[0] || state.playlist[state.pos]?.[1] !== current[1]) {
    setActive(state.pos);
  }
  if (wasActive) startFrom(state.pos);
  renderControls();
}

function goToSection(si) {
  const s = state.book.sections[si];
  if (!s.selected) {
    s.selected = true;
    el.list.children[si].querySelector('input').checked = true;
    rebuildPlaylist(true);
    updateBookMeta();
  }
  const idx = indexOfUnit(si, 0);
  if (idx < 0) return;
  closeRailOnMobile();
  if (state.status === 'playing' || state.status === 'paused') playAt(idx);
  else setActive(idx);
}

/* ------------------------------------------------------------------ */
/* Reader: the text and the highlighted sentence                       */
/* ------------------------------------------------------------------ */

let shownSection = -1;
let unitEls = [];
let activeEl = null;

function renderSection(si) {
  const s = state.book.sections[si];
  shownSection = si;
  el.readerHead.textContent = s.title;
  const frag = document.createDocumentFragment();
  let p = null;
  let lastPara = -1;
  s.units.forEach((u, ui) => {
    if (!p || u.para !== lastPara) {
      p = document.createElement('p');
      if (s.paras[u.para] === s.title) p.className = 'h';
      frag.append(p);
      lastPara = u.para;
    }
    const span = document.createElement('span');
    span.className = 'u';
    span.dataset.u = ui;
    span.textContent = u.text;
    p.append(span, ' ');
  });
  el.text.replaceChildren(frag);
  unitEls = [...el.text.querySelectorAll('.u')];
  activeEl = null;
}

function setActive(idx, { scroll = true } = {}) {
  const entry = state.playlist[idx];
  if (!entry) return;
  state.pos = idx;
  const [si, ui] = entry;
  if (si !== shownSection) {
    renderSection(si);
    el.stage.scrollTop = 0;
  }
  activeEl?.classList.remove('on');
  activeEl = unitEls[ui] || null;
  if (activeEl) {
    activeEl.classList.add('on');
    if (scroll) keepInView(activeEl);
  }
  markCurrentSection(si);
  updateScrubber();
  store.set('pos:' + state.bookKey, { sec: si, u: ui });
}

function keepInView(node) {
  const r = node.getBoundingClientRect();
  const c = el.stage.getBoundingClientRect();
  if (r.top < c.top + 70 || r.bottom > c.bottom - 70) {
    el.stage.scrollTo({
      top: el.stage.scrollTop + (r.top - c.top) - c.height * 0.33,
      behavior: reduceMotion ? 'auto' : 'smooth',
    });
  }
}

el.text.addEventListener('click', (e) => {
  const node = e.target.closest('.u');
  if (!node) return;
  const idx = indexOfUnit(shownSection, Number(node.dataset.u));
  if (idx >= 0) playAt(idx);
});

/* ------------------------------------------------------------------ */
/* Scrubber                                                            */
/* ------------------------------------------------------------------ */

function updateScrubber() {
  const n = state.playlist.length;
  el.scrub.max = Math.max(0, n - 1);
  el.scrub.value = state.pos;
  el.scrub.disabled = n === 0;
  el.scrubLabel.textContent = n ? `Sentence ${state.pos + 1} of ${n}` : '';
  if (n) {
    const wordsLeft = state.wordPrefix[n] - state.wordPrefix[state.pos];
    el.timeLeft.textContent = `about ${formatDuration((wordsLeft / WORDS_PER_MINUTE / state.speed) * 60)} left`;
  } else {
    el.timeLeft.textContent = '';
  }
}

el.scrub.addEventListener('input', () => {
  const idx = Number(el.scrub.value);
  el.scrubLabel.textContent = `Sentence ${idx + 1} of ${state.playlist.length}`;
});
el.scrub.addEventListener('change', () => {
  const idx = Number(el.scrub.value);
  if (state.status === 'playing' || state.status === 'paused') playAt(idx);
  else setActive(idx);
});

/* ------------------------------------------------------------------ */
/* Model                                                               */
/* ------------------------------------------------------------------ */

let tts = null;
let ttsInfo = null;
let loadPromise = null;

async function detectWebGPU() {
  try {
    return !!(navigator.gpu && (await navigator.gpu.requestAdapter()));
  } catch {
    return false;
  }
}

function ensureModel() {
  if (tts) return Promise.resolve(tts);
  if (!loadPromise) {
    loadPromise = loadModel().finally(() => {
      loadPromise = null;
      renderControls();
    });
    renderControls();
  }
  return loadPromise;
}

let loadCount = 0; // every load after the first needs fresh module copies (see below)

async function loadModel() {
  setOverride('Loading the voice model…');
  el.load.hidden = false;
  el.load.removeAttribute('value');
  try {
    const attempts = [];
    // Phones run out of memory with the 330 MB fp32 GPU model (the tab is killed, which no
    // try/catch can catch), so 'auto' means CPU on mobile. 'gpu' forces WebGPU anywhere.
    const useGPU = hasWebGPU && (state.engine === 'gpu' || (state.engine === 'auto' && !IS_MOBILE));
    if (useGPU) attempts.push({ device: 'webgpu', dtype: 'fp32', label: 'GPU' });
    attempts.push({ device: 'wasm', dtype: 'q8', label: 'CPU' });
    // One extra CPU try covers a flaky mobile connection dropping the runtime download.
    attempts.push({ device: 'wasm', dtype: 'q8', label: 'CPU' });

    let lastError;
    const errors = [];
    for (const [i, opt] of attempts.entries()) {
      const n = loadCount++;
      try {
        // A failed load poisons the module for the rest of the page: transformers.js reuses
        // the first rejected session, and the browser caches the failed runtime import.
        // So every retry gets a fresh copy of kokoro-js and cache-busted runtime URLs.
        const { KokoroTTS, env } = await import(n ? `${KOKORO_URL}?attempt=${n}` : KOKORO_URL);
        if (n) {
          // kokoro-js's `env` is a thin wrapper whose setter throws "reading 'onnx'" when the
          // runtime isn't initialised yet. That error used to mask the real first failure,
          // so a failed cache-bust is ignored rather than allowed to abort the retry.
          try {
            env.wasmPaths = {
              mjs: `${ORT_BASE}ort-wasm-simd-threaded.jsep.mjs?attempt=${n}-${Date.now()}`,
              wasm: `${ORT_BASE}ort-wasm-simd-threaded.jsep.wasm`,
            };
          } catch (e) {
            console.warn('Could not set wasmPaths', e);
          }
        }
        const files = new Map();
        const model = await KokoroTTS.from_pretrained(MODEL_ID, {
          dtype: opt.dtype,
          device: opt.device,
          progress_callback: (p) => {
            if (p.status !== 'progress' || !p.total) return;
            files.set(p.file, { loaded: p.loaded, total: p.total });
            let loaded = 0;
            let total = 0;
            for (const f of files.values()) {
              loaded += f.loaded;
              total += f.total;
            }
            el.load.max = total;
            el.load.value = loaded;
            setOverride(
              `Downloading the voice model (${opt.label}): ${Math.round((loaded / total) * 100)}% of ${Math.round(total / 1e6)} MB. This only happens once.`
            );
          },
        });
        await speak(model, isSpanishVoice(state.voice) ? 'Listo.' : 'Ready.');
        tts = model;
        ttsInfo = opt;
        return tts;
      } catch (err) {
        lastError = err;
        errors.push(`${opt.label}: ${err?.message || err}`);
        console.warn(`Engine ${opt.label} failed`, err);
        setOverride(i < attempts.length - 1 ? `${opt.label} engine failed. Trying the CPU engine…` : '');
      }
    }
    throw new Error(`The voice model could not start. ${errors.length ? errors.join(' | ') : lastError?.message || 'Check your connection and try again.'}`);
  } finally {
    el.load.hidden = true;
    setOverride('');
  }
}

// kokoro-js only knows English voices and phonemes, so Spanish text is phonemized
// here and handed to the model as token ids (generate_from_ids skips the voice check).
function speak(model, text) {
  const opts = { voice: state.voice, speed: state.speed };
  if (!isSpanishVoice(state.voice)) return model.generate(text, opts);
  const { input_ids } = model.tokenizer(phonemizeSpanish(text), { truncation: true });
  return model.generate_from_ids(input_ids, opts);
}

// ONNX sessions can't run two inferences at once, so every call goes through one chain.
let chain = Promise.resolve();
function synth(text) {
  const run = chain.then(() => speak(tts, text));
  chain = run.catch(() => {});
  return run;
}

/* ------------------------------------------------------------------ */
/* Playback                                                            */
/* ------------------------------------------------------------------ */

let ctx = null;
let masterGain = null;
let epoch = 0; // bumps whenever playback is restarted, so stale work can bail out
let genIdx = 0; // next playlist index to synthesize
let nextStart = 0; // audio-clock time where the next chunk begins
let scheduled = []; // [{ i, start, end, src }]
let pumpDone = false;
let timer = null;
let genRate = 0; // how many times faster than real time we are generating
let wakeLock = null;

function ensureCtx() {
  ctx ||= new (window.AudioContext || window.webkitAudioContext)();
  if (!masterGain) { masterGain = ctx.createGain(); masterGain.gain.value = Number(store.get('volume', 1)); masterGain.connect(ctx.destination); }
  if (ctx.state === 'suspended' && state.status !== 'paused') ctx.resume();
}

async function togglePlay() {
  ensureCtx();
  if (state.status === 'playing') return pause();
  if (state.status === 'paused') return resume();
  if (!state.playlist.length) return;
  const atEnd = state.status === 'done';
  await playAt(atEnd ? 0 : state.pos);
}

async function playAt(idx) {
  ensureCtx();
  if (!state.playlist.length) return;
  clearAlert();
  const target = clamp(idx, 0, state.playlist.length - 1);
  setActive(target);
  if (!tts) {
    stopSources();
    state.status = 'idle';
    renderControls();
    try {
      await ensureModel();
    } catch (err) {
      showAlert(err.message);
      renderControls();
      return;
    }
  }
  startFrom(state.pos);
}

function startFrom(idx) {
  stopSources();
  epoch++;
  genIdx = idx;
  nextStart = 0;
  pumpDone = false;
  state.pos = idx;
  ctx.resume();
  state.status = 'playing';
  renderControls();
  clearInterval(timer);
  timer = setInterval(tick, 150);
  holdScreenAwake();
  pump(epoch);
  refreshStatus();
}

async function pump(myEpoch) {
  let failures = 0;
  while (myEpoch === epoch && genIdx < state.playlist.length) {
    const ahead = scheduled.length ? nextStart - ctx.currentTime : 0;
    if (ahead > LOOKAHEAD_SECONDS) {
      await sleep(300);
      continue;
    }
    const i = genIdx;
    const unit = unitAt(i);
    const t0 = performance.now();
    let out;
    try {
      out = await synth(unit.text);
    } catch (err) {
      if (myEpoch !== epoch) return;
      console.warn('Could not synthesize a sentence', err);
      if (++failures >= 3) {
        stopAll();
        showAlert(`Speech generation failed. ${err?.message || ''}`);
        return;
      }
      genIdx++;
      continue;
    }
    if (myEpoch !== epoch) return;
    failures = 0;

    const data = out.audio ?? out.data;
    const rate = out.sampling_rate || 24000;
    const seconds = data.length / rate;
    const took = (performance.now() - t0) / 1000;
    const instant = seconds / Math.max(took, 0.001);
    genRate = genRate ? genRate * 0.7 + instant * 0.3 : instant;

    const buffer = ctx.createBuffer(1, data.length, rate);
    buffer.copyToChannel(data instanceof Float32Array ? data : new Float32Array(data), 0);
    const src = ctx.createBufferSource();
    src.buffer = buffer;
    src.connect(masterGain || ctx.destination);
    const start = Math.max(nextStart, ctx.currentTime + 0.04);
    src.start(start);
    const end = start + buffer.duration;
    scheduled.push({ i, start, end, src });
    nextStart = end + (unit.last ? GAP_PARAGRAPH : GAP_SENTENCE);
    genIdx++;
  }
  if (myEpoch === epoch) pumpDone = true;
}

function tick() {
  if (!ctx) return;
  const t = ctx.currentTime;
  let current = null;
  for (const s of scheduled) {
    if (s.start <= t + 0.02) current = s;
    else break;
  }
  if (current && current.i !== state.pos) setActive(current.i);
  while (scheduled.length > 2 && scheduled[0].end < t - 0.5) {
    try {
      scheduled[0].src.disconnect();
    } catch {}
    scheduled.shift();
  }
  if (state.status === 'playing' && pumpDone && scheduled.length && t >= scheduled[scheduled.length - 1].end) finish();
  refreshStatus();
}

function finish() {
  stopSources();
  clearInterval(timer);
  state.status = 'done';
  releaseScreen();
  setActive(0);
  renderControls();
  refreshStatus();
}

function pause() {
  if (state.status !== 'playing') return;
  ctx.suspend();
  state.status = 'paused';
  releaseScreen();
  renderControls();
  refreshStatus();
}

function resume() {
  if (state.status !== 'paused') return;
  ctx.resume();
  state.status = 'playing';
  holdScreenAwake();
  renderControls();
  refreshStatus();
}

function stopSources() {
  for (const s of scheduled) {
    try {
      s.src.stop();
    } catch {}
    try {
      s.src.disconnect();
    } catch {}
  }
  scheduled = [];
}

function stopAll() {
  epoch++;
  stopSources();
  clearInterval(timer);
  releaseScreen();
  if (state.status !== 'idle') state.status = 'idle';
  if (ctx && ctx.state === 'suspended') ctx.resume();
  renderControls();
  refreshStatus();
}

// Voice or speed changed: throw away audio that hasn't started and regenerate it.
function regenerateUpcoming() {
  if (state.status !== 'playing' && state.status !== 'paused') return;
  const t = ctx.currentTime;
  const kept = [];
  let restartAt = null;
  for (const s of scheduled) {
    if (s.start > t + 0.05) {
      try {
        s.src.stop();
        s.src.disconnect();
      } catch {}
      if (restartAt === null) restartAt = s.i;
    } else kept.push(s);
  }
  scheduled = kept;
  epoch++;
  if (restartAt !== null) genIdx = restartAt;
  nextStart = kept.length ? kept[kept.length - 1].end + GAP_SENTENCE : 0;
  pumpDone = false;
  pump(epoch);
}

async function holdScreenAwake() {
  try {
    if (navigator.wakeLock && !wakeLock) {
      wakeLock = await navigator.wakeLock.request('screen');
      wakeLock.addEventListener('release', () => (wakeLock = null));
    }
  } catch {
    /* not available; fine */
  }
}
function releaseScreen() {
  wakeLock?.release?.().catch?.(() => {});
  wakeLock = null;
}
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && state.status === 'playing') holdScreenAwake();
});

/* ------------------------------------------------------------------ */
/* Controls and status line                                            */
/* ------------------------------------------------------------------ */

function renderControls() {
  const playing = state.status === 'playing';
  const loading = !!loadPromise;
  el.play.querySelector('.i-play').hidden = playing;
  el.play.querySelector('.i-pause').hidden = !playing;
  el.play.setAttribute('aria-label', playing ? 'Pause' : state.status === 'paused' ? 'Resume' : 'Play');
  el.play.disabled = loading || !state.playlist.length || !!exporting;
  el.prev.disabled = !state.playlist.length || state.pos <= 0;
  el.next.disabled = !state.playlist.length || state.pos >= state.playlist.length - 1;
  el.play.setAttribute('aria-busy', loading ? 'true' : 'false');
}

function setOverride(text) {
  statusOverride = text;
  refreshStatus();
  renderControls();
}

function refreshStatus() {
  let text = statusOverride;
  if (!text) {
    if (state.status === 'paused') text = 'Paused';
    else if (state.status === 'done') text = 'Finished. Press play to start again.';
    else if (state.status === 'playing') {
      const starving =
        ctx && !pumpDone && ctx.state === 'running' && (scheduled.length ? nextStart - ctx.currentTime < 0.05 : true);
      if (starving) text = scheduled.length ? 'Buffering…' : 'Preparing the first sentence…';
      else if (genRate && genRate < 1.2)
        text = `This device generates speech at ${genRate.toFixed(1)}× playback speed, so expect pauses. A browser with WebGPU, or saving audio first, will help.`;
      else if (genRate) text = `Generating ${genRate.toFixed(1)}× faster than playback${ttsInfo ? ` (${ttsInfo.label})` : ''}`;
    }
  }
  el.status.textContent = text;
}

function skip(delta) {
  if (!state.playlist.length) return;
  const idx = clamp(state.pos + delta, 0, state.playlist.length - 1);
  if (state.status === 'playing' || state.status === 'paused') playAt(idx);
  else setActive(idx);
}

el.play.addEventListener('click', togglePlay);
el.prev.addEventListener('click', () => skip(-1));
el.next.addEventListener('click', () => skip(1));

for (const [group, voices] of VOICES) {
  const og = document.createElement('optgroup');
  og.label = group;
  for (const [id, name] of voices) og.append(new Option(name, id));
  el.voice.append(og);
}
el.voice.value = state.voice;
for (const s of SPEEDS) el.speed.append(new Option(`${s}×`, s));
el.speed.value = String(state.speed);
el.engine.value = state.engine;

el.voice.addEventListener('change', () => {
  setVoice(el.voice.value);
  regenerateUpcoming();
});
el.speed.addEventListener('change', () => {
  state.speed = Number(el.speed.value);
  store.set('speed', state.speed);
  updateScrubber();
  regenerateUpcoming();
});
el.engine.addEventListener('change', () => {
  state.engine = el.engine.value;
  store.set('engine', state.engine);
  stopAll();
  tts = null;
  ttsInfo = null;
  genRate = 0;
});

// Playback volume is a Web Audio gain control. Hidden on mobile by CSS so the
// device's physical volume remains the primary mobile control.
if (el.volume) {
  el.volume.value = String(store.get('volume', 1));
  el.volumeValue.textContent = `${Math.round(Number(el.volume.value) * 100)}%`;
  el.volume.addEventListener('input', () => {
    const value = Number(el.volume.value);
    store.set('volume', value);
    el.volumeValue.textContent = `${Math.round(value * 100)}%`;
    if (masterGain && ctx) masterGain.gain.setTargetAtTime(value, ctx.currentTime, 0.015);
  });
}
const savedTheme = store.get('theme', 'light');
document.documentElement.dataset.theme = savedTheme;
if (el.themeToggle) {
  const updateThemeLabel = () => { el.themeToggle.textContent = document.documentElement.dataset.theme === 'dark' ? 'Light mode' : 'Dark mode'; };
  updateThemeLabel();
  el.themeToggle.addEventListener('click', () => {
    const theme = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = theme;
    store.set('theme', theme);
    updateThemeLabel();
  });
}

detectWebGPU().then((ok) => {
  hasWebGPU = ok;
  el.engineHint.textContent = !ok
    ? "This browser doesn't offer WebGPU, so the CPU engine will be used (about 90 MB, downloaded once)."
    : IS_MOBILE
      ? 'On phones, Automatic uses the CPU engine (about 90 MB, slower but stable). GPU needs about 330 MB plus a lot of RAM and can crash the tab.'
      : 'Automatic uses your GPU when it can: fastest, with a one-time download of about 330 MB. CPU is a lighter download of about 90 MB but slower.';
});

/* ------------------------------------------------------------------ */
/* Keyboard                                                            */
/* ------------------------------------------------------------------ */

document.addEventListener('keydown', (e) => {
  if (!state.book || e.metaKey || e.ctrlKey || e.altKey) return;
  const tag = e.target.tagName;
  if (['INPUT', 'SELECT', 'TEXTAREA', 'BUTTON', 'SUMMARY'].includes(tag)) return;
  if (e.code === 'Space') {
    e.preventDefault();
    togglePlay();
  } else if (e.key === 'ArrowLeft') skip(-1);
  else if (e.key === 'ArrowRight') skip(1);
});

/* ------------------------------------------------------------------ */
/* Saving audio                                                        */
/* ------------------------------------------------------------------ */

let exporting = null; // { cancel: boolean }

function encodeWav(chunks, sampleRate) {
  let n = 0;
  for (const c of chunks) n += c.length;
  const buf = new ArrayBuffer(44 + n * 2);
  const v = new DataView(buf);
  const str = (o, s) => [...s].forEach((ch, i) => v.setUint8(o + i, ch.charCodeAt(0)));
  str(0, 'RIFF');
  v.setUint32(4, 36 + n * 2, true);
  str(8, 'WAVE');
  str(12, 'fmt ');
  v.setUint32(16, 16, true);
  v.setUint16(20, 1, true); // PCM
  v.setUint16(22, 1, true); // mono
  v.setUint32(24, sampleRate, true);
  v.setUint32(28, sampleRate * 2, true);
  v.setUint16(32, 2, true);
  v.setUint16(34, 16, true);
  str(36, 'data');
  v.setUint32(40, n * 2, true);
  let o = 44;
  for (const c of chunks) {
    for (let i = 0; i < c.length; i++) {
      const x = Math.max(-1, Math.min(1, c[i]));
      v.setInt16(o, x < 0 ? x * 0x8000 : x * 0x7fff, true);
      o += 2;
    }
  }
  return new Blob([buf], { type: 'audio/wav' });
}

async function synthSectionWav(si, onProgress) {
  const s = state.book.sections[si];
  const chunks = [];
  let rate = 24000;
  for (let i = 0; i < s.units.length; i++) {
    if (exporting.cancel) throw new Error('cancelled');
    const out = await synth(s.units[i].text);
    rate = out.sampling_rate || 24000;
    chunks.push(out.audio ?? out.data);
    chunks.push(new Float32Array(Math.round(rate * (s.units[i].last ? GAP_PARAGRAPH : GAP_SENTENCE))));
    onProgress(i + 1, s.units.length);
  }
  return encodeWav(chunks, rate);
}

const safeName = (s) => s.replace(/[\\/:*?"<>|]+/g, '').replace(/\s+/g, ' ').replace(/[. ]+$/, '').trim().slice(0, 80) || 'audio';

function download(blob, name) {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = name;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 60000);
}

async function exportSections(indexes) {
  if (!indexes.length || exporting) return;
  const words = indexes.reduce((n, i) => n + state.book.sections[i].words, 0);
  const seconds = (words / WORDS_PER_MINUTE) * 60;
  if (seconds > 3 * 3600) {
    const gb = ((seconds * 48000) / 1e9).toFixed(1);
    if (!confirm(`This is about ${formatDuration(seconds)} of audio, roughly ${gb} GB, and it can take a long time to generate. Continue?`)) return;
  }
  stopAll();
  clearAlert();
  try {
    await ensureModel();
  } catch (err) {
    showAlert(err.message);
    return;
  }
  exporting = { cancel: false };
  el.cancel.hidden = false;
  renderControls();
  const base = safeName(state.book.title);
  try {
    const zip = indexes.length > 1 ? new (await libs.JSZip())() : null;
    for (let k = 0; k < indexes.length; k++) {
      const si = indexes[k];
      const s = state.book.sections[si];
      const wav = await synthSectionWav(si, (done, total) =>
        setOverride(`Saving audio, section ${k + 1} of ${indexes.length}: ${Math.round((done / total) * 100)}%`)
      );
      const name = `${base} - ${String(si + 1).padStart(2, '0')} ${safeName(s.title)}.wav`;
      if (zip) zip.file(name, wav);
      else download(wav, name);
    }
    if (zip) {
      setOverride('Packing the files…');
      download(await zip.generateAsync({ type: 'blob', compression: 'STORE' }), `${base}.zip`);
    }
  } catch (err) {
    if (err.message !== 'cancelled') {
      console.error(err);
      showAlert(`Saving audio failed. ${err.message}`);
    }
  } finally {
    exporting = null;
    el.cancel.hidden = true;
    setOverride('');
  }
}

el.cancel.addEventListener('click', () => {
  if (exporting) exporting.cancel = true;
});
$('saveSelected').addEventListener('click', () =>
  exportSections(state.book.sections.map((s, i) => (s.selected ? i : -1)).filter((i) => i >= 0))
);

/* ------------------------------------------------------------------ */
/* File picking, drag and drop, rail buttons                           */
/* ------------------------------------------------------------------ */

for (const id of ['choose', 'replace']) $(id).addEventListener('click', () => el.fileInput.click());
el.fileInput.addEventListener('change', () => {
  const f = el.fileInput.files[0];
  if (f) openFile(f);
  el.fileInput.value = '';
});
$('sample').addEventListener('click', () =>
  openFile(new File([SAMPLE_TEXT], 'Pride and Prejudice (sample).txt', { type: 'text/plain' }))
);
$('sampleEs').addEventListener('click', () =>
  openFile(new File([SAMPLE_TEXT_ES], 'Don Quijote (muestra).txt', { type: 'text/plain' }))
);
$('alertClose').addEventListener('click', clearAlert);

const setAll = (value) => {
  state.book.sections.forEach((s) => (s.selected = value));
  el.list.querySelectorAll('input[type=checkbox]').forEach((b) => (b.checked = value));
  onSelectionChanged();
};
$('selectAll').addEventListener('click', () => setAll(true));
$('selectNone').addEventListener('click', () => setAll(false));

let dragDepth = 0;
addEventListener('dragenter', (e) => {
  e.preventDefault();
  dragDepth++;
  document.body.classList.add('dragging');
});
addEventListener('dragleave', () => {
  if (--dragDepth <= 0) {
    dragDepth = 0;
    document.body.classList.remove('dragging');
  }
});
addEventListener('dragover', (e) => e.preventDefault());
addEventListener('drop', (e) => {
  e.preventDefault();
  dragDepth = 0;
  document.body.classList.remove('dragging');
  const f = e.dataTransfer?.files?.[0];
  if (f) openFile(f);
});

const toggleRail = $('toggleRail');
function closeRailOnMobile() {
  document.body.classList.remove('rail-open');
  toggleRail.setAttribute('aria-expanded', 'false');
}
toggleRail.addEventListener('click', () => {
  const open = document.body.classList.toggle('rail-open');
  toggleRail.setAttribute('aria-expanded', String(open));
});

/* ------------------------------------------------------------------ */
/* Start                                                               */
/* ------------------------------------------------------------------ */

renderControls();
