// spanish.js: Spanish text -> IPA phonemes for Kokoro's Spanish voices.
//
// kokoro-js only ships an English phonemizer, so Spanish is converted here. Spanish
// spelling maps almost one to one onto sounds, so a rule set covers it well. The
// output follows the eSpeak NG "es" style the Spanish voices were trained on:
// Castilian θ for c/z, lenited β ð ɣ between vowels, ˈ before the stressed vowel.

/* ------------------------------------------------------------------ */
/* Numbers and abbreviations                                           */
/* ------------------------------------------------------------------ */

const ONES = ['cero', 'uno', 'dos', 'tres', 'cuatro', 'cinco', 'seis', 'siete', 'ocho', 'nueve', 'diez',
  'once', 'doce', 'trece', 'catorce', 'quince', 'dieciséis', 'diecisiete', 'dieciocho', 'diecinueve', 'veinte',
  'veintiuno', 'veintidós', 'veintitrés', 'veinticuatro', 'veinticinco', 'veintiséis', 'veintisiete', 'veintiocho', 'veintinueve'];
const TENS = ['', '', '', 'treinta', 'cuarenta', 'cincuenta', 'sesenta', 'setenta', 'ochenta', 'noventa'];
const HUNDREDS = ['', 'ciento', 'doscientos', 'trescientos', 'cuatrocientos', 'quinientos', 'seiscientos', 'setecientos', 'ochocientos', 'novecientos'];

function below1000(n) {
  if (n === 100) return 'cien';
  const h = Math.floor(n / 100);
  const r = n % 100;
  const parts = h ? [HUNDREDS[h]] : [];
  if (r < 30) {
    if (r || !h) parts.push(ONES[r]);
  } else {
    parts.push(TENS[Math.floor(r / 10)] + (r % 10 ? ' y ' + ONES[r % 10] : ''));
  }
  return parts.join(' ');
}

// "uno" shortens to "un" before mil / millón (veintiún mil, un millón)
const apocope = (s) => s.replace(/veintiuno$/, 'veintiún').replace(/uno$/, 'un');

export function numberToWords(n) {
  if (!Number.isSafeInteger(n) || n < 0) return String(n);
  if (n >= 1e12) return [...String(n)].map((d) => ONES[d]).join(' ');
  if (n < 1000) return below1000(n);
  if (n < 1e6) {
    const th = Math.floor(n / 1000);
    const head = th === 1 ? 'mil' : apocope(below1000(th)) + ' mil';
    return n % 1000 ? head + ' ' + below1000(n % 1000) : head;
  }
  const mi = Math.floor(n / 1e6);
  const head = mi === 1 ? 'un millón' : apocope(numberToWords(mi)) + ' millones';
  return n % 1e6 ? head + ' ' + numberToWords(n % 1e6) : head;
}

const ROMAN = { I: 1, V: 5, X: 10, L: 50, C: 100, D: 500, M: 1000 };
function romanToNumber(s) {
  let total = 0;
  for (let i = 0; i < s.length; i++) {
    const v = ROMAN[s[i]];
    total += v < (ROMAN[s[i + 1]] || 0) ? -v : v;
  }
  return total;
}

const ABBREVIATIONS = [
  [/\bSr\./g, 'señor'], [/\bSra\./g, 'señora'], [/\bSrta\./g, 'señorita'],
  [/\bDr\./g, 'doctor'], [/\bDra\./g, 'doctora'], [/\bUd\./g, 'usted'], [/\bUds\./g, 'ustedes'],
  [/\bD\.(?= [A-ZÁÉÍÓÚÑ])/g, 'don'], [/\bDña\./g, 'doña'], [/\bpág\./gi, 'página'],
  [/\betc\./gi, 'etcétera'], [/\bnº\s?/gi, 'número '], [/%/g, ' por ciento'],
];

function normalize(text) {
  let t = text.normalize('NFC');
  for (const [re, word] of ABBREVIATIONS) t = t.replace(re, word);
  // Roman numerals after words that usually take them: "capítulo IV", "siglo XX"
  t = t.replace(/\b(cap[ií]tulo|parte|libro|tomo|siglo|acto|escena)\s+([IVXLCDM]+)\b/gi, (_, w, r) => `${w} ${numberToWords(romanToNumber(r.toUpperCase()))}`);
  t = t.replace(/\b\d{1,3}(?:\.\d{3})+\b/g, (m) => m.replace(/\./g, '')); // 1.000.000
  t = t.replace(/(\d+)[,.](\d+)/g, (_, a, b) => `${numberToWords(Number(a))} coma ${[...b].length <= 2 ? numberToWords(Number(b)) : [...b].map((d) => ONES[d]).join(' ')}`);
  t = t.replace(/\d+/g, (m) => numberToWords(Number(m)));
  return t
    .replace(/[«»]/g, '"')
    .replace(/[“”„]/g, '"')
    .replace(/[‘’`´']/g, '')
    .replace(/[¿¡]/g, '')
    .replace(/[–―]/g, '—')
    .replace(/\.{3}/g, '…')
    .replace(/[\[{]/g, '(')
    .replace(/[\]}]/g, ')')
    .replace(/&/g, ' y ');
}

/* ------------------------------------------------------------------ */
/* Words                                                               */
/* ------------------------------------------------------------------ */

// Function words that carry no stress of their own (stressed forms carry an accent: él, tú, qué...).
const UNSTRESSED = new Set(('a al con de del e el en la las le les lo los me mi mis nos os por que se si sin ' +
  'su sus te tu tus y o u ni un como cuando donde mientras pero para porque aunque cual cuyo cuya').split(' '));

const ACCENTED = { á: 'a', é: 'e', í: 'i', ó: 'o', ú: 'u' };
const isVowelLetter = (c) => c !== undefined && 'aeiouáéíóúü'.includes(c);
const FRONT = (c) => c !== undefined && 'eiéí'.includes(c);

// letters -> segments: { p: phoneme, v: true if vowel, acc: written accent, weak: i/u that may glide }
function segment(word) {
  const w = word.toLowerCase();
  const out = [];
  const push = (p, extra = {}) => out.push({ p, ...extra });
  for (let i = 0; i < w.length; i++) {
    const c = w[i];
    const n = w[i + 1];
    if (c in ACCENTED) push(ACCENTED[c], { v: true, acc: true });
    else if ('aeo'.includes(c)) push(c, { v: true });
    else if (c === 'i' || c === 'u' || c === 'ü') push(c === 'i' ? 'i' : 'u', { v: true, weak: true });
    else if (c === 'y') {
      // vowel when alone or at the end after a vowel (y, hoy, rey, muy); consonant otherwise
      if (w.length === 1) push('i', { v: true, weak: true });
      else if (i === w.length - 1 && isVowelLetter(w[i - 1])) push('i', { v: true, weak: true, final: true });
      else push('ʝ');
    } else if (c === 'c') {
      if (n === 'h') { push('ʧ'); i++; }
      else push(FRONT(n) ? 'θ' : 'k');
    } else if (c === 'q') {
      push('k');
      if (n === 'u') i++;
    } else if (c === 'g') {
      if (n === 'u' && FRONT(w[i + 2])) { push('ɡ'); i++; }
      else push(FRONT(n) ? 'x' : 'ɡ');
    } else if (c === 'l') {
      if (n === 'l') { push('ʎ'); i++; }
      else push('l');
    } else if (c === 'r') {
      if (n === 'r') { push('r'); i++; }
      else push(i === 0 || 'lns'.includes(w[i - 1]) ? 'r' : 'ɾ');
    } else if (c === 'x') {
      // México, Oaxaca, Texas keep the old /x/ spelling
      if (/^(m[eé]xic|oaxac|texa)/.test(w) && i <= 3) push('x');
      else { push('k'); push('s'); }
    } else if (c === 'h') { /* silent */ }
    else if (c === 'j') push('x');
    else if (c === 'z') push('θ');
    else if (c === 'v' || c === 'b') push('b');
    else if (c === 'ñ') push('ɲ');
    else if (c === 'w') push('w');
    else if (c === 'k') push('k');
    else if (/[a-zàâäçèêëîïôöùûœæ]/.test(c)) push(c.normalize('NFD')[0] === 'ç' ? 's' : c.normalize('NFD')[0]);
  }
  return out;
}

function wordToPhonemes(word) {
  const segs = segment(word);
  if (!segs.length) return '';

  // Vowel runs: unaccented i/u next to another vowel become glides (j, w), except
  // in "iu"/"ui" where the second one keeps the syllable (ciudad, ruido).
  for (let i = 0; i < segs.length; i++) {
    if (!segs[i].v) continue;
    let j = i;
    while (j + 1 < segs.length && segs[j + 1].v) j++;
    const run = segs.slice(i, j + 1);
    if (run.length > 1) {
      run.forEach((s, k) => {
        if (!s.weak) return;
        const prev = run[k - 1];
        const next = run[k + 1];
        const strongNeighbour = (prev && !prev.weak) || (next && !next.weak);
        if (strongNeighbour || (next && next.weak && !next.final) || s.final) s.glide = true;
      });
      if (run.every((s) => s.glide)) run[run.length - 1].glide = false;
    }
    i = j;
  }
  const nuclei = segs.map((s, i) => (s.v && !s.glide ? i : -1)).filter((i) => i >= 0);

  // Stress: written accent wins; otherwise penultimate when the word ends in a vowel, n or s.
  let stressed = -1;
  const lower = word.toLowerCase();
  const accented = segs.findIndex((s) => s.acc);
  if (accented >= 0) stressed = accented;
  else if (nuclei.length && !UNSTRESSED.has(lower)) {
    const penult = /[aeiouns]$/.test(lower) && nuclei.length > 1;
    stressed = nuclei[nuclei.length - (penult ? 2 : 1)];
  }

  let out = '';
  segs.forEach((s, i) => {
    if (i === stressed) out += 'ˈ';
    out += s.glide ? (s.p === 'i' ? 'j' : 'w') : s.p;
  });
  return out;
}

/* ------------------------------------------------------------------ */
/* Phrases                                                             */
/* ------------------------------------------------------------------ */

const PUNCT = new Set([';', ':', ',', '.', '!', '?', '—', '…', '"', '(', ')']);

// b d g are stops after a pause or a nasal (and d after l); between other sounds they soften.
function lenite(s) {
  const LEN = { b: 'β', d: 'ð', ɡ: 'ɣ' };
  let prev = null;
  let out = '';
  for (const ch of s) {
    if (PUNCT.has(ch)) {
      prev = null;
      out += ch;
      continue;
    }
    if (ch === ' ' || ch === 'ˈ') {
      out += ch;
      continue;
    }
    if (ch in LEN && prev !== null && !'mnɲŋ'.includes(prev) && !(ch === 'd' && prev === 'l')) out += LEN[ch];
    else out += ch;
    prev = ch;
  }
  return out;
}

export function phonemizeSpanish(text) {
  const parts = normalize(text).split(/([\p{L}]+)/u);
  let out = '';
  for (const [k, part] of parts.entries()) {
    if (k % 2) {
      out += wordToPhonemes(part);
    } else {
      // keep the punctuation the model knows; anything else becomes a space
      for (const ch of part) out += PUNCT.has(ch) ? ch : ' ';
    }
  }
  out = out
    .replace(/\s+/g, ' ')
    .replace(/ ([,.;:!?…)])/g, '$1')
    .replace(/n(?= ?ˈ?[pbm])/g, 'm') // un beso -> um beso
    .replace(/n(?= ?ˈ?[kɡx])/g, 'ŋ') // tengo, un gato
    .trim();
  return lenite(out);
}

// Rough language check: is this text more likely Spanish than English?
const ES_WORDS = new Set('el la los las que de y en un una es por con para no se del al lo como más pero sus le ya o fue este ha sí porque esta son entre cuando muy sin sobre también me hasta hay donde quien desde todo nos durante todos uno les ni contra otros ese eso ante ellos e esto mí antes algunos qué unos yo otro otras otra él tanto esa estos mucho quienes nada muchos cual poco ella estar estas algunas algo nosotros mi mis tú te ti tu tus ellas nosotras vosotros os mío mía tuyo suyo era había estaba dijo'.split(' '));
const EN_WORDS = new Set('the of and to in is was that he it for on with as his i be at by had not but from or have they which you she her an were are this all their there been one so if we when who would said my me him what no could them into out more up'.split(' '));

export function looksSpanish(text) {
  let es = 0;
  let en = 0;
  for (const w of text.toLowerCase().match(/[\p{L}]+/gu) || []) {
    if (ES_WORDS.has(w)) es++;
    if (EN_WORDS.has(w)) en++;
  }
  if (/[ñ¿¡áéíóú]/i.test(text)) es += 2;
  return es > en * 1.5 && es >= 3;
}
