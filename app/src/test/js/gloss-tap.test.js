// The popup's tap-to-look-up text (assets/popup/gloss-tap.js), on made-up glossary DOMs.
// Run with `node --test`; the nodes are a minimal stand-in for the DOM's (no browser needed).
const test = require('node:test');
const assert = require('node:assert');
const { textFrom, tapText } = require('../../main/assets/popup/gloss-tap.js');

/** An element: its tag, classes, and children (strings are text nodes). */
function el(tag, className, ...children) {
    const node = { nodeType: 1, nodeName: tag.toUpperCase(), className: className || '', childNodes: [], parentNode: null };
    for (const child of children) append(node, typeof child === 'string' ? { nodeType: 3, nodeName: '#text', nodeValue: child } : child);
    node.closest = selector => closest(node, selector);
    node.contains = other => { for (let at = other; at; at = at.parentNode) if (at === node) return true; return false; };
    return node;
}

function append(parent, child) {
    const last = parent.childNodes[parent.childNodes.length - 1];
    child.parentNode = parent;
    child.nextSibling = null;
    if (last) last.nextSibling = child;
    parent.firstChild = parent.childNodes[0] || child;
    parent.childNodes.push(child);
}

/** Enough of Element.closest for the selectors gloss-tap uses: tags and single classes. */
function closest(node, selector) {
    const parts = selector.split(',').map(s => s.trim());
    for (let at = node; at; at = at.parentNode) {
        if (at.nodeType !== 1) continue;
        const classes = at.className.split(' ');
        if (parts.some(p => (p.startsWith('.') ? classes.includes(p.slice(1)) : at.nodeName === p.toUpperCase()))) return at;
    }
    return null;
}

const textOf = node => node.childNodes.find(c => c.nodeType === 3);

test('from the tapped character to the end of the gloss', () => {
    const gloss = el('li', '', 'to hit the ball hard');
    assert.strictEqual(textFrom(textOf(gloss), 7, gloss), 'the ball hard');
});

test('across inline elements, without furigana', () => {
    const ruby = el('ruby', '', '漢', el('rt', '', 'かん'));
    const gloss = el('li', '', '例えば', ruby, '字を', el('span', '', '書く'));
    assert.strictEqual(textFrom(textOf(gloss), 0, gloss), '例えば漢字を書く');
});

test('ends where the next block starts', () => {
    const sense = el('div', '', el('span', '', '強い'), el('div', '', 'next sense'));
    const first = sense.childNodes[0];
    assert.strictEqual(textFrom(textOf(first), 0, sense), '強い');
});

test('ends at a line break', () => {
    const gloss = el('div', '', 'line one', el('br'), 'line two');
    assert.strictEqual(textFrom(textOf(gloss), 5, gloss), 'one');
});

test('leading spaces are dropped, and nothing left is no lookup', () => {
    const gloss = el('li', '', 'a  word');
    assert.strictEqual(textFrom(textOf(gloss), 1, gloss), 'word');
    assert.strictEqual(textFrom(textOf(gloss), 7, gloss), null);
});

test('is capped at a lookup\'s length', () => {
    const gloss = el('li', '', 'x'.repeat(200));
    assert.strictEqual(textFrom(textOf(gloss), 0, gloss).length, 48);
});

/** A document whose caret is always at ([node], [offset]), as caretRangeFromPoint gives it (WebView 109). */
const docAt = (node, offset) => ({ caretRangeFromPoint: () => ({ startContainer: node, startOffset: offset }) });

test('a tap in a definition takes its text; caretPositionFromPoint first when there', () => {
    const item = el('div', 'glossary-item', el('li', '', 'deep red'));
    const text = textOf(item.childNodes[0]);
    assert.strictEqual(tapText(docAt(text, 5), item.childNodes[0], 0, 0), 'red');
    const modern = { caretPositionFromPoint: () => ({ offsetNode: text, offset: 0 }), caretRangeFromPoint: () => null };
    assert.strictEqual(tapText(modern, item.childNodes[0], 0, 0), 'deep red');
});

test('links, buttons, tags, the headword and text outside definitions are left alone', () => {
    const link = el('a', '', 'see 強い');
    const tags = el('span', 'definition-tags', 'adj-i');
    const item = el('div', 'glossary-item', tags, el('li', '', 'deep', link));
    assert.strictEqual(tapText(docAt(textOf(link), 0), link, 0, 0), null);
    assert.strictEqual(tapText(docAt(textOf(tags), 0), tags, 0, 0), null);
    const headword = el('div', 'headword', '強い');
    assert.strictEqual(tapText(docAt(textOf(headword), 0), headword, 0, 0), null);
    const elsewhere = el('div', 'tags', 'JPDB 13060');
    assert.strictEqual(tapText(docAt(textOf(elsewhere), 0), elsewhere, 0, 0), null);
    const button = el('button', '', '＋ Anki');
    assert.strictEqual(tapText(docAt(textOf(button), 0), button, 0, 0), null);
    assert.ok(item);
});

test('a caret outside the tapped definition, or on no text, is no lookup', () => {
    const item = el('div', 'glossary-item', el('li', '', 'deep'));
    const other = el('div', 'glossary-item', el('li', '', 'far'));
    assert.strictEqual(tapText(docAt(textOf(other.childNodes[0]), 0), item.childNodes[0], 0, 0), null);
    assert.strictEqual(tapText(docAt(item, 0), item.childNodes[0], 0, 0), null);
});
