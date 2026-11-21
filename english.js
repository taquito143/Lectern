// english.js: English text -> misaki phonemes for KittenTTS's English model.
//
// KittenTTS 2 learned English from misaki's lexicon (us_gold + us_silver), with CMUdict
// for the words misaki lacks (mostly names). Both are looked up here as they are; words
// in neither are tried without common suffixes, then spelled out letter by letter.
// Same approach as the Android app's EnglishPhonemizer.kt.

// espeak-ng (WASM) for words in neither dictionary, as in KittenTTS's training data.
const ESPEAK_URL = 'https://cdn.jsdelivr.net/npm/phonemizer@1.2.1/dist/phonemizer.js';
const DICT_URL = new URL('./g2p/en_dict.tsv.gz', import.meta.url);
const CMU_URL = new URL('./g2p/cmu_misaki_us.tsv.gz', import.meta.url);

let dict = null; // Map word -> phonemes (misaki, case-sensitive)
let cmu = null; // Map lowercase word -> phonemes (CMUdict in misaki's alphabet)
let loading = null;
let espeak = null; // Promise of phonemizer's phonemize(), loaded with the dictionaries

async function readTsvGz(url, fetchBytes) {
  const bytes = await fetchBytes(url.href);
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('gzip'));
  const text = await new Response(stream).text();
  const map = new Map();
  for (const line of text.split('\n')) {
    const tab = line.indexOf('\t');
    if (tab > 0) map.set(line.slice(0, tab), line.slice(tab + 1));
  }
  return map;
}

/** Loads both dictionaries (about 2 MB). `fetchBytes(url) -> Uint8Array` may cache. */
export function loadEnglish(fetchBytes) {
  espeak ||= import(ESPEAK_URL)
    .then((m) => m.phonemize)
    .catch((err) => {
      console.warn('espeak-ng did not load; unknown words will be spelled out', err);
      espeak = null;
      return null;
    });
  loading ||= Promise.all([readTsvGz(DICT_URL, fetchBytes), readTsvGz(CMU_URL, fetchBytes)])
    .then(([d, c]) => {
      dict = d;
      cmu = c;
    })
    .catch((err) => {
      loading = null;
      throw err;
    });
  return loading;
}

const lookup = (w) =>
  dict.get(w) ?? dict.get(w.toLowerCase()) ?? dict.get(w[0].toUpperCase() + w.slice(1).toLowerCase());

function sibilantS(base) {
  const last = base.at(-1);
  if ('szʃʒʧʤ'.includes(last)) return 'ᵻz';
  if ('ptkfθ'.includes(last)) return 's';
  return 'z';
}

function stem(word) {
  const hit = lookup(word);
  if (hit) return hit;
  const w = word.toLowerCase();
  let b;
  if (w.endsWith("'s") && (b = lookup(w.slice(0, -2)) ?? cmu.get(w.slice(0, -2)))) return b + sibilantS(b);
  if (w.endsWith("s'") && (b = lookup(w.slice(0, -1)))) return b;
  if (w.endsWith('ies') && (b = lookup(w.slice(0, -3) + 'y'))) return b.slice(0, -1) + 'iz';
  if (w.endsWith('es') && (b = lookup(w.slice(0, -2)))) return b + 'ɪz';
  if (w.endsWith('s') && (b = lookup(w.slice(0, -1)))) return b + sibilantS(b);
  if (w.endsWith('ed') && (b = lookup(w.slice(0, -2)) ?? lookup(w.slice(0, -1)))) {
    const last = b.at(-1);
    return b + ('td'.includes(last) ? 'ᵻd' : 'pkfsʃʧθ'.includes(last) ? 't' : 'd');
  }
  if (w.endsWith('ing') && (b = lookup(w.slice(0, -3)) ?? lookup(w.slice(0, -3) + 'e'))) return b + 'ɪŋ';
  if (w.endsWith('ly') && (b = lookup(w.slice(0, -2)))) return b + 'li';
  return null;
}

const spell = (word) =>
  [...word].filter((c) => /\p{L}/u.test(c)).map((c) => lookup(c.toUpperCase()) ?? '').filter(Boolean).join(' ');

const known = (w) => stem(w) ?? cmu.get(w.toLowerCase()) ?? null;

// Compounds missing from the dictionaries ("Netherfield", "lamplighter") read as their
// parts, two or three dictionary words of 4+ letters (shorter pieces like "ley" make
// wrong splits of names), longest first part first. The parts are joined into one word
// and only the first keeps primary stress, as in a spoken compound.
function compound(word, depth = 0) {
  const w = word.toLowerCase();
  if (w.length < 8) return null;
  for (let i = w.length - 4; i >= 4; i--) {
    const head = known(w.slice(0, i));
    if (!head) continue;
    const tail = known(w.slice(i)) ?? (depth < 1 ? compound(w.slice(i), depth + 1) : null);
    if (tail) return head.trim() + tail.trim().replace(/ˈ/g, 'ˌ');
  }
  return null;
}

// misaki's EspeakFallback: espeak's IPA -> misaki's alphabet (US). phonemizer.js
// writes no tie bars, so the diphthongs are matched as plain pairs.
const E2M = Object.entries({
  'ʔˌn̩': 'ʔn', 'ʔn̩': 'ʔn', aɪ: 'I', aʊ: 'W', dʒ: 'ʤ', eɪ: 'A', e: 'A', tʃ: 'ʧ', ɔɪ: 'Y',
  'ʲo': 'jo', 'ʲə': 'jə', ʲ: '', ɚ: 'əɹ', r: 'ɹ', x: 'k', ç: 'k', ɐ: 'ə', ɬ: 'l', '̃': '',
}).sort((a, b) => b[0].length - a[0].length);

function espeakToMisaki(ps) {
  for (const [a, b] of E2M) ps = ps.replaceAll(a, b);
  ps = ps.replace(/(\S)̩/g, 'ᵊ$1').replaceAll('̩', '');
  ps = ps.replaceAll('oʊ', 'O').replaceAll('ɜːɹ', 'ɜɹ').replaceAll('ɜː', 'ɜɹ').replaceAll('ɪə', 'iə').replaceAll('ː', '');
  return ps.replaceAll('o', 'ɔ').replaceAll('ɾ', 'T').replaceAll('ʔ', 't').trim();
}

const guessed = new Map(); // word -> phonemes from espeak

function sayWord(word) {
  const ps = known(word);
  if (ps) return ps;
  if (word.includes('-')) return word.split('-').filter(Boolean).map(sayWord).join(' ');
  return compound(word) || guessed.get(word.toLowerCase()) || spell(word);
}

// Asks espeak once for every word the dictionaries can't say.
async function guessUnknown(words) {
  const unknown = [...new Set(words.map((w) => w.toLowerCase()))].filter(
    (w) => !guessed.has(w) && !known(w) && !w.includes('-') && !compound(w) && w.length > 1
  );
  const phonemize = unknown.length && (await espeak);
  if (!phonemize) return;
  for (const w of unknown) {
    try {
      const ps = espeakToMisaki((await phonemize(w, 'en-us')).join(' '));
      if (ps) guessed.set(w, ps);
    } catch {
      /* keep spelling this one */
    }
  }
}

/* ------------------------------------------------------------------ */
/* Numbers and symbols                                                 */
/* ------------------------------------------------------------------ */

const ONES = ['zero', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten', 'eleven', 'twelve',
  'thirteen', 'fourteen', 'fifteen', 'sixteen', 'seventeen', 'eighteen', 'nineteen'];
const TENS = ['', '', 'twenty', 'thirty', 'forty', 'fifty', 'sixty', 'seventy', 'eighty', 'ninety'];

function numberToWords(n) {
  if (n < 20) return ONES[n];
  if (n < 100) return TENS[Math.floor(n / 10)] + (n % 10 ? ' ' + ONES[n % 10] : '');
  if (n < 1000) return ONES[Math.floor(n / 100)] + ' hundred' + (n % 100 ? ' ' + numberToWords(n % 100) : '');
  for (const [size, name] of [[1e9, 'billion'], [1e6, 'million'], [1e3, 'thousand']]) {
    if (n >= size) return numberToWords(Math.floor(n / size)) + ' ' + name + (n % size ? ' ' + numberToWords(n % size) : '');
  }
  return String(n);
}

// Read out, and without the period, which the model would say as a sentence end.
const ABBREVIATIONS = { Mr: 'Mister', Mrs: 'Missus', Ms: 'Miz', Dr: 'Doctor', St: 'Saint', Jr: 'Junior', Sr: 'Senior', Prof: 'Professor' };

function normalize(text) {
  let t = text
    .normalize('NFKC')
    .replace(/\b(Mr|Mrs|Ms|Dr|St|Jr|Sr|Prof)\.(?=\s)/g, (_, a) => ABBREVIATIONS[a]);
  // Years read as pairs: 1813 -> eighteen thirteen
  t = t.replace(/\b(1[1-9])(\d{2})\b/g, (_, hi, lo) => {
    const l = Number(lo);
    return numberToWords(Number(hi)) + ' ' + (l === 0 ? 'hundred' : l < 10 ? 'oh ' + numberToWords(l) : numberToWords(l));
  });
  t = t.replace(/(\d),(\d{3})/g, '$1$2');
  t = t.replace(/(\d+)\.(\d+)/g, (_, a, b) => numberToWords(Number(a)) + ' point ' + [...b].map((d) => ONES[d]).join(' '));
  t = t.replace(/\d+/g, (m) => (m.length < 13 ? numberToWords(Number(m)) : [...m].map((d) => ONES[d]).join(' ')));
  return t
    .replace(/&/g, ' and ')
    .replace(/%/g, ' percent')
    .replace(/[–―]/g, '—')
    .replace(/\.\.\./g, '…')
    .replace(/[«»„]/g, '"')
    .replace(/[[{]/g, '(')
    .replace(/[\]}]/g, ')');
}

const TOKEN = /\p{L}[\p{L}'’-]*\p{L}|\p{L}/gu;
const PUNCT = ';:,.!?—…"()“”';
const VOWEL_START = /^[ˈˌ]?[AIOWYaeiouæɑɐɒɔəɛɜɪʊʌᵊᵻ]/;

/** English text -> misaki phoneme string. Call loadEnglish() first. */
export async function phonemizeEnglish(text) {
  const t = normalize(text);
  const parts = []; // [kind, value]: 'w' word phonemes, 'g' gap text
  let last = 0;
  for (const m of t.matchAll(TOKEN)) {
    parts.push(['g', t.slice(last, m.index)]);
    parts.push(['w', m[0].replace(/’/g, "'")]);
    last = m.index + m[0].length;
  }
  parts.push(['g', t.slice(last)]);
  await guessUnknown(parts.filter(([k]) => k === 'w').map(([, v]) => v));

  let out = '';
  for (let i = 0; i < parts.length; i++) {
    const [kind, v] = parts[i];
    if (kind === 'g') {
      for (const ch of v) out += PUNCT.includes(ch) ? ch : ' ';
      continue;
    }
    const lw = v.toLowerCase();
    // misaki's function-word rules: "a" is ɐ, "the" is ði before a vowel and ðə otherwise.
    if (lw === 'a' && v === 'a') out += 'ɐ';
    else if (lw === 'the') {
      const next = parts.slice(i + 1).find(([k]) => k === 'w');
      out += next && VOWEL_START.test(sayWord(next[1])) ? 'ði' : 'ðə';
    } else out += sayWord(v);
    out += ' ';
  }
  return out.replace(/\s+/g, ' ').replace(/ ([,.;:!?…)”])/g, '$1').trim();
}
