/*
 * Genkan's lookup popup: renders hoshidicts results (Yomitan dictionaries) on the reader.
 *
 * Adapted from Hoshi Reader's popup.js (HuangAntimony/Hoshi-Reader-Android @ 186c2a5):
 * furigana segmentation, dictionary CSS scoping, structured content and pitch graphs. Those
 * parts follow Yomitan's own code (links below).
 * Copyright © 2026 Manhhao. Copyright © 2023-2025 Yomitan Authors.
 * Copyright © 2021-2022 Yomichan Authors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Kotlin calls genkanShow(hits, styles) and genkanShowKanji(character, entries); taps call back
 * through the GenkanPopup interface (lookUpKanji, lookUp).
 */

const KANJI_RANGE = '一-鿿㐀-䶿豈-﫿々';
const KANJI_PATTERN = new RegExp(`[${KANJI_RANGE}]`);
const KANJI_SEGMENT_PATTERN = new RegExp(`[${KANJI_RANGE}]+|[^${KANJI_RANGE}]+`, 'g');
const SMALL_KANA_SET = new Set('ぁぃぅぇぉゃゅょゎァィゥェォャュョヮ');

let lastTerms = null;
let lastStyles = null;
let lastStates = null;
let lastMarks = [];
let lastCount = null;

const STATE_LABELS = { NEW: '＋ Anki', QUEUED: 'Queued', MINED: 'In Anki' };

function el(tag, props = {}, children = []) {
    const element = document.createElement(tag);
    for (const [key, value] of Object.entries(props)) {
        if (key in element) {
            element[key] = value;
        } else {
            element.setAttribute(key, value);
        }
    }
    if (children.length) {
        element.append(...children);
    }
    return element;
}

function toHiragana(text) {
    return text.replace(/[ァ-ヶ]/g, ch => String.fromCharCode(ch.charCodeAt(0) - 0x60));
}

function toKebabCase(str) {
    return str.replace(/([A-Z])/g, (_, c, i) => (i ? '-' : '') + c.toLowerCase());
}

/** Kanji become tappable: a tap shows the kanji dictionary's entry. */
function wrapKanji(text) {
    const nodes = [];
    for (const character of text || '') {
        if (KANJI_PATTERN.test(character)) {
            const span = el('span', { className: 'kanji-char', textContent: character });
            span.addEventListener('click', () => window.GenkanPopup?.lookUpKanji(character));
            nodes.push(span);
        } else {
            nodes.push(document.createTextNode(character));
        }
    }
    return nodes;
}

// https://github.com/yomidevs/yomitan/blob/c24d4c9b39ceec1b5fd133df774c41972e9ebbdc/ext/js/language/ja/japanese.js#L242
function getFuriganaKanaSegments(text, reading) {
    const segments = [];
    let start = 0;
    let state = (reading[0] === text[0]);
    for (let i = 1; i < text.length; ++i) {
        const newState = (reading[i] === text[i]);
        if (state === newState) { continue; }
        segments.push({ text: text.substring(start, i), reading: state ? '' : reading.substring(start, i) });
        state = newState;
        start = i;
    }
    segments.push({ text: text.substring(start), reading: state ? '' : reading.substring(start) });
    return segments;
}

// https://github.com/yomidevs/yomitan/blob/c24d4c9b39ceec1b5fd133df774c41972e9ebbdc/ext/js/language/ja/japanese.js#L182
function segmentizeFurigana(reading, readingNormalized, groups, groupsStart) {
    const groupCount = groups.length - groupsStart;
    if (groupCount <= 0) {
        return reading.length === 0 ? [] : null;
    }
    const group = groups[groupsStart];
    const { isKana, text } = group;
    if (isKana) {
        if (group.textNormalized !== null && readingNormalized.startsWith(group.textNormalized)) {
            const segments = segmentizeFurigana(
                reading.substring(text.length), readingNormalized.substring(text.length), groups, groupsStart + 1);
            if (segments !== null) {
                if (reading.startsWith(text)) {
                    segments.unshift({ text, reading: '' });
                } else {
                    segments.unshift(...getFuriganaKanaSegments(text, reading));
                }
                return segments;
            }
        }
        return null;
    }
    let result = null;
    for (let i = reading.length; i >= text.length; --i) {
        const segments = segmentizeFurigana(
            reading.substring(i), readingNormalized.substring(i), groups, groupsStart + 1);
        if (segments !== null) {
            if (result !== null) {
                return null; // more than one way to split the tail: ambiguous
            }
            segments.unshift({ text, reading: reading.substring(0, i) });
            result = segments;
        }
        if (groupCount === 1) {
            break;
        }
    }
    return result;
}

function segmentFurigana(expression, reading) {
    if (!reading || reading === expression) {
        return [[expression, '']];
    }
    const groups = (expression.match(KANJI_SEGMENT_PATTERN) || []).map(text => {
        const isKana = !KANJI_PATTERN.test(text[0]);
        return { isKana, text, textNormalized: isKana ? toHiragana(text) : null };
    });
    const segments = segmentizeFurigana(reading, toHiragana(reading), groups, 0);
    return segments !== null ? segments.map(s => [s.text, s.reading]) : [[expression, reading]];
}

function buildFurigana(parent, expression, reading) {
    for (const [text, furigana] of segmentFurigana(expression, reading)) {
        if (furigana) {
            const ruby = el('ruby', {}, wrapKanji(text));
            ruby.appendChild(el('rt', { textContent: furigana }));
            parent.appendChild(ruby);
        } else {
            parent.append(...wrapKanji(text));
        }
    }
}

/** Furigana as Anki's {furigana-plain}: 漢字[かんじ], a space before each bracketed group. */
function furiganaPlain(expression, reading) {
    let out = '';
    for (const [text, furigana] of segmentFurigana(expression, reading)) {
        out += furigana ? ` ${text}[${furigana}]` : text;
    }
    return out.trim();
}

/** Scopes a dictionary's own CSS to its glossaries (Hoshi's preprocessor). */
function constructDictCss(css, dictName) {
    if (!css) {
        return '';
    }
    const prefix = `.yomitan-glossary [data-dictionary="${CSS.escape(dictName)}"]`;
    const parts = [];
    let i = 0;
    while (i < css.length) {
        while (i < css.length && /\s/.test(css[i])) {
            parts.push(css[i++]);
        }
        if (css.slice(i, i + 2) === '/*') {
            const end = css.indexOf('*/', i + 2);
            if (end === -1) break;
            parts.push(css.slice(i, end + 2));
            i = end + 2;
            continue;
        }
        const bracePos = css.indexOf('{', i);
        if (bracePos === -1) break;
        const selectors = css.slice(i, bracePos).split(',').map(s => {
            const trimmed = s.trim();
            if (!trimmed) return '';
            return trimmed.startsWith('&') ? s : `${prefix} ${trimmed}`;
        });
        parts.push(selectors.join(', '), ' {');
        i = bracePos + 1;
        let depth = 1;
        const blockStart = i;
        while (i < css.length && depth > 0) {
            if (css[i] === '{') depth++;
            else if (css[i] === '}') depth--;
            i++;
        }
        const block = css.slice(blockStart, i - 1);
        if (block.includes('{')) {
            let pos = 0;
            let properties = '';
            let nested = '';
            while (pos < block.length) {
                while (pos < block.length && /\s/.test(block[pos])) pos++;
                if (pos >= block.length) break;
                const nextSemi = block.indexOf(';', pos);
                const nextBrace = block.indexOf('{', pos);
                if (nextBrace !== -1 && (nextSemi === -1 || nextBrace < nextSemi)) {
                    let nestedDepth = 1;
                    let end = nextBrace + 1;
                    while (end < block.length && nestedDepth > 0) {
                        if (block[end] === '{') nestedDepth++;
                        else if (block[end] === '}') nestedDepth--;
                        end++;
                    }
                    nested += block.slice(pos, end);
                    pos = end;
                } else if (nextSemi !== -1) {
                    properties += block.slice(pos, nextSemi + 1);
                    pos = nextSemi + 1;
                } else {
                    properties += block.slice(pos);
                    break;
                }
            }
            parts.push(properties);
            if (nested) {
                parts.push(constructDictCss(nested, dictName));
            }
        } else {
            parts.push(block);
        }
        parts.push('}');
    }
    return parts.join('');
}

function setStructuredContentStyle(element, style) {
    for (const [property, value] of Object.entries(style)) {
        if (property.startsWith('margin') && typeof value === 'number') {
            element.style[property] = `${value}em`;
        } else {
            element.style[property] = value;
        }
    }
}

/** A dictionary image, served by Genkan from the dictionary's media. */
function createImage(node, dictionary) {
    const { path, width, height, preferredWidth, preferredHeight, sizeUnits, appearance, title, verticalAlign } = node;
    const unit = sizeUnits === 'em' ? 'em' : 'px';
    const link = el('span', { className: 'gloss-image-link' });
    link.dataset.appearance = typeof appearance === 'string' ? appearance : 'auto';
    const image = el('img', {
        className: 'gloss-image',
        alt: title || '',
        src: `media/${encodeURIComponent(dictionary)}/${encodeURIComponent(path)}`,
    });
    const w = typeof preferredWidth === 'number' ? preferredWidth : width;
    const h = typeof preferredHeight === 'number' ? preferredHeight : height;
    if (typeof w === 'number') image.style.width = `${w}${unit}`;
    if (typeof h === 'number' && typeof w !== 'number') image.style.height = `${h}${unit}`;
    if (verticalAlign) link.style.verticalAlign = verticalAlign;
    link.appendChild(image);
    return link;
}

// https://github.com/yomidevs/yomitan/blob/d810b2f0842536d24ab82b6cd75d00841710e57b/ext/js/display/structured-content-generator.js
function renderStructuredContent(parent, node, dictionary) {
    if (typeof node === 'string') {
        node.split(/\r?\n/).forEach((line, i) => {
            if (i > 0) parent.appendChild(document.createElement('br'));
            if (line) parent.appendChild(document.createTextNode(line));
        });
        return;
    }
    if (Array.isArray(node)) {
        node.forEach(child => renderStructuredContent(parent, child, dictionary));
        return;
    }
    if (!node || typeof node !== 'object') {
        return;
    }
    if (node.type === 'structured-content') {
        const container = el('span', { className: 'structured-content' });
        parent.appendChild(container);
        renderStructuredContent(container, node.content, dictionary);
        return;
    }
    if (node.type === 'text') {
        renderStructuredContent(parent, node.text, dictionary);
        return;
    }
    if (node.type === 'image' || node.tag === 'img') {
        parent.appendChild(createImage(node, dictionary));
        return;
    }
    const tag = node.tag || 'span';
    const element = document.createElement(tag);
    element.classList.add(`gloss-sc-${tag}`);
    if (node.href) {
        element.setAttribute('href', node.href);
        element.addEventListener('click', event => {
            event.preventDefault();
            // Internal links look a word up ("?query=…"); external ones are left alone.
            const i = node.href.indexOf('?');
            const query = i < 0 ? null : new URLSearchParams(node.href.slice(i + 1)).get('query');
            if (query) window.GenkanPopup?.lookUp(query);
        });
    }
    if (node.title) element.setAttribute('title', node.title);
    if (node.lang) element.setAttribute('lang', node.lang);
    if (node.data) {
        for (const [k, v] of Object.entries(node.data)) {
            const cjk = /^[　-鿿豈-﫿]/.test(k);
            element.setAttribute(`data-sc${cjk ? '' : '-'}${toKebabCase(k)}`, v);
        }
    }
    if (node.style) setStructuredContentStyle(element, node.style);
    if (node.content) renderStructuredContent(element, node.content, dictionary);
    if (node.colSpan) element.setAttribute('colspan', node.colSpan);
    if (node.rowSpan) element.setAttribute('rowspan', node.rowSpan);
    if (tag === 'table') {
        parent.appendChild(el('div', { className: 'gloss-sc-table-container' }, [element]));
        return;
    }
    parent.appendChild(element);
}

function getKanaMorae(text) {
    const morae = [];
    for (const c of text) {
        if (SMALL_KANA_SET.has(c) && morae.length > 0) {
            morae[morae.length - 1] += c;
        } else {
            morae.push(c);
        }
    }
    return morae;
}

// https://github.com/yomidevs/yomitan/blob/c24d4c9b39ceec1b5fd133df774c41972e9ebbdc/ext/js/language/ja/japanese.js#L350
function isMoraPitchHigh(moraIndex, downstep) {
    switch (downstep) {
        case 0: return moraIndex > 0;
        case 1: return moraIndex < 1;
        default: return moraIndex > 0 && moraIndex < downstep;
    }
}

/** Yomitan's pitch graph: dots on high and low morae, the drop marked with a ring. */
function pitchGraph(morae, downstep) {
    const points = [];
    const dots = [];
    morae.forEach((_, index) => {
        const high = isMoraPitchHigh(index, downstep);
        const highNext = isMoraPitchHigh(index + 1, downstep);
        const x = index * 50 + 25;
        const y = high ? 25 : 75;
        points.push(`${x} ${y}`);
        dots.push(high && !highNext
            ? `<circle style="fill:none;stroke-width:5;stroke:currentColor;" cx="${x}" cy="${y}" r="15"/><circle style="fill:currentColor;" cx="${x}" cy="${y}" r="5"/>`
            : `<circle style="stroke-width:5;fill:currentColor;stroke:currentColor;" cx="${x}" cy="${y}" r="15"/>`);
    });
    const tailX = morae.length * 50 + 25;
    const tailY = isMoraPitchHigh(morae.length, downstep) ? 25 : 75;
    const last = points[points.length - 1];
    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${50 * (morae.length + 1)} 100"><path style="fill:none;stroke-width:5;stroke:currentColor;" d="M${points.join(' L')}"/><path style="fill:none;stroke-width:5;stroke:currentColor;stroke-dasharray:5 5;" d="M${last} L${tailX} ${tailY}"/>${dots.join('')}<path style="fill:none;stroke-width:5;stroke:currentColor;" d="M0 13 L15 -13 L-15 -13 Z" transform="translate(${tailX},${tailY})"/></svg>`;
}

function tag(text, className = '', label = '') {
    const span = el('span', { className: `tag ${className}`.trim() });
    if (label) span.appendChild(el('span', { className: 'label', textContent: label }));
    span.appendChild(document.createTextNode(text));
    return span;
}

function parseGlossary(text) {
    try {
        const value = JSON.parse(text);
        return Array.isArray(value) ? value : [value];
    } catch (e) {
        return [text];
    }
}

/** One glossary's definitions as HTML, as Yomitan writes them into a card. */
function glossaryItemHtml(g) {
    const item = document.createElement('span');
    renderStructuredContent(item, parseGlossary(g.glossary), g.dictionary);
    const tags = (g.definitionTags || '').split(' ').filter(Boolean);
    const label = [...tags, g.dictionary].join(', ');
    return `<li data-dictionary="${escapeAttr(g.dictionary)}"><i>(${escapeText(label)})</i> ${item.innerHTML}</li>`;
}

/** Yomitan's {glossary}: every definition; {glossary-first}: the first dictionary's. */
function cardGlossaries(hit) {
    const glossaries = hit.glossaries || [];
    const wrap = items => `<div style="text-align: left;" class="yomitan-glossary"><ol>${items.join('')}</ol></div>`;
    const first = glossaries.length ? glossaries.filter(g => g.dictionary === glossaries[0].dictionary) : [];
    return { all: wrap(glossaries.map(glossaryItemHtml)), first: wrap(first.map(glossaryItemHtml)) };
}

function escapeText(text) {
    return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function escapeAttr(text) {
    return escapeText(text).replace(/"/g, '&quot;');
}

function mineButton(index, state) {
    const button = el('span', { className: `mine mine-${(state || 'NEW').toLowerCase()}`, textContent: STATE_LABELS[state || 'NEW'] });
    button.dataset.index = index;
    button.addEventListener('click', event => {
        event.stopPropagation();
        const hit = lastTerms && lastTerms[index];
        if (!hit || !button.classList.contains('mine-new')) return;
        const glossary = cardGlossaries(hit);
        window.GenkanPopup?.mine(index, glossary.all, glossary.first, furiganaPlain(hit.expression, hit.reading));
    });
    return button;
}

/** Hachidori's two hand marks: known, and ignored. Pressing a lit one clears it. */
function markButtons(index, mark) {
    const box = el('span', { className: 'marks' });
    for (const [status, label] of [['KNOWN', '✓ Known'], ['IGNORED', '⊘ Ignore']]) {
        const button = el('span', { className: 'mark' + (mark === status ? ' active' : ''), textContent: label });
        button.dataset.index = index;
        button.dataset.status = status;
        button.addEventListener('click', event => {
            event.stopPropagation();
            window.GenkanPopup?.setMark(index, status);
        });
        box.appendChild(button);
    }
    return box;
}

function renderTerm(hit, index, state, mark, count) {
    const entry = el('div', { className: 'entry' });
    const top = el('div', { className: 'entry-top' });
    const headword = el('div', { className: 'headword', lang: 'ja' });
    buildFurigana(headword, hit.expression, hit.reading);
    top.appendChild(headword);
    const actions = el('div', { className: 'entry-actions' });
    actions.appendChild(markButtons(index, mark));
    actions.appendChild(mineButton(index, state));
    top.appendChild(actions);
    entry.appendChild(top);

    const tags = el('div', { className: 'tags' });
    if (hit.trace && hit.trace.length) {
        tags.appendChild(tag(hit.trace.map(t => t.name).join(' ← '), 'deinflection'));
    }
    for (const freq of hit.frequencies || []) {
        const values = (freq.values || []).map(v => v.display || String(v.value));
        if (values.length) tags.appendChild(tag(values.join(', '), '', freq.dictionary));
    }
    // Hachidori's count chip: on the first result only, after its frequencies.
    if (index === 0 && count) {
        tags.appendChild(tag(`Looked up ${count} time${count === 1 ? '' : 's'}`, 'lookup-count'));
    }
    if (tags.childNodes.length) entry.appendChild(tags);

    const morae = getKanaMorae(hit.reading || hit.expression);
    const seen = new Set();
    const pitch = el('div', { className: 'pitch' });
    for (const group of hit.pitches || []) {
        for (const p of group.pitches || []) {
            if (!Number.isInteger(p.position) || p.position < 0 || seen.has(p.position)) continue;
            seen.add(p.position);
            const item = el('span');
            item.innerHTML = pitchGraph(morae, p.position);
            item.appendChild(document.createTextNode(` [${p.position}]`));
            pitch.appendChild(item);
        }
    }
    if (pitch.childNodes.length) entry.appendChild(pitch);

    // Definitions grouped by dictionary, in the order the engine returned them.
    const groups = new Map();
    for (const g of hit.glossaries || []) {
        if (!groups.has(g.dictionary)) groups.set(g.dictionary, []);
        groups.get(g.dictionary).push(g);
    }
    for (const [dictionary, glossaries] of groups) {
        const section = el('div', { className: 'dictionary yomitan-glossary' });
        section.appendChild(el('div', { className: 'dictionary-name', textContent: dictionary }));
        const scope = el('div', { lang: 'ja' });
        scope.setAttribute('data-dictionary', dictionary);
        const list = glossaries.length > 1 ? el('ol', { className: 'glossary-entries' }) : null;
        for (const g of glossaries) {
            const item = el(list ? 'li' : 'div', { className: 'glossary-item' });
            const defTags = (g.definitionTags || '').split(' ').filter(Boolean);
            if (defTags.length) {
                item.appendChild(el('span', { className: 'definition-tags' }, defTags.map(t => tag(t))));
            }
            renderStructuredContent(item, parseGlossary(g.glossary), dictionary);
            (list || scope).appendChild(item);
        }
        if (list) scope.appendChild(list);
        section.appendChild(scope);
        entry.appendChild(section);
    }
    return entry;
}

function setStyles(styles) {
    document.getElementById('dictionary-styles').textContent =
        (styles || []).map(s => constructDictCss(s.css, s.dictionary)).join('\n');
}

window.genkanShow = function (hits, styles, states, marks, count) {
    setStyles(styles);
    lastTerms = hits;
    lastStyles = styles;
    lastStates = states || [];
    lastMarks = marks || [];
    lastCount = count || null;
    const root = document.getElementById('entries');
    root.replaceChildren();
    if (!hits || !hits.length) {
        root.appendChild(el('div', { className: 'empty', textContent: 'Nothing found.' }));
    } else {
        hits.forEach((hit, i) => root.appendChild(renderTerm(hit, i, lastStates[i], lastMarks[i], lastCount)));
    }
    window.scrollTo(0, 0);
};

window.genkanShowKanji = function (character, entries) {
    const root = document.getElementById('entries');
    root.replaceChildren();
    if (lastTerms) {
        const back = el('span', { className: 'back', textContent: '← Back' });
        back.addEventListener('click', () => window.genkanShow(lastTerms, lastStyles, lastStates, lastMarks, lastCount));
        root.appendChild(back);
    }
    const entry = el('div', { className: 'entry kanji-entry' });
    entry.appendChild(el('div', { className: 'character', lang: 'ja', textContent: character }));
    if (!entries || !entries.length) {
        entry.appendChild(el('div', { className: 'empty', textContent: 'No kanji dictionary entry.' }));
    }
    for (const k of entries || []) {
        entry.appendChild(el('div', { className: 'dictionary-name', textContent: k.dictionary }));
        const dl = el('dl');
        const row = (label, value) => {
            if (!value) return;
            dl.appendChild(el('dt', { textContent: label }));
            dl.appendChild(el('dd', { textContent: value, lang: 'ja' }));
        };
        row('Meaning', (k.definitions || []).join(', '));
        row('On', k.onyomi);
        row('Kun', k.kunyomi);
        for (const s of k.stats || []) row(s.key, s.value);
        entry.appendChild(dl);
    }
    root.appendChild(entry);
    window.scrollTo(0, 0);
};

/** Updates the known and ignore buttons without re-rendering. */
window.genkanSetMarks = function (marks) {
    lastMarks = marks || [];
    document.querySelectorAll('.mark').forEach(button => {
        button.classList.toggle('active', lastMarks[Number(button.dataset.index)] === button.dataset.status);
    });
};

/** Updates the Anki buttons without re-rendering (and losing the scroll position). */
window.genkanSetStates = function (states) {
    lastStates = states || [];
    document.querySelectorAll('.mine').forEach(button => {
        const state = lastStates[Number(button.dataset.index)] || 'NEW';
        button.className = `mine mine-${state.toLowerCase()}`;
        button.textContent = STATE_LABELS[state];
    });
};
