/*
 * A tap on a word inside a definition looks it up, as in Hachidori and Amatsubame: the text from
 * the tapped character to the end of its block goes to GenkanPopup.lookUp, whose result replaces
 * the popup's as a dictionary link's does. Only a tap: a scroll or drag sends no click.
 * SPDX-License-Identifier: GPL-3.0-only
 */
(function (root) {
    /** Elements that end the text a tap takes: the next sense, row or line starts there. */
    const BLOCKS = new Set(['LI', 'DIV', 'P', 'TD', 'TH', 'TR', 'OL', 'UL', 'TABLE', 'DD', 'DT', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'BR']);
    /** Elements whose text isn't the definition's: furigana and images. */
    const SKIPPED = new Set(['RT', 'RP', 'IMG']);
    /** Text taps aren't taken from: controls, links, the headword (its kanji look up on their own), labels. */
    const NOT_TEXT = 'a, button, img, rt, rp, .entry-actions, .headword, .tags, .definition-tags, .dictionary-name, .back';
    /** More than any word: the engine reads up to its scan length. */
    const MAX_LENGTH = 48;

    const isText = node => node && node.nodeType === 3;

    /** The node after [node] in document order, not going into it, inside [block]. */
    function nextOutside(node, block) {
        while (node && node !== block) {
            if (node.nextSibling) return node.nextSibling;
            node = node.parentNode;
        }
        return null;
    }

    /** The nearest block (or [stop]) holding [node]. */
    function blockOf(node, stop) {
        let at = node.parentNode;
        while (at && at !== stop && !BLOCKS.has(at.nodeName)) at = at.parentNode;
        return at || stop;
    }

    /**
     * The text from [offset] in text node [node] to the end of its block: across inline elements,
     * leaving out furigana and images, ending at a nested block or a line break. Null when [node]
     * isn't text or nothing is left.
     */
    function textFrom(node, offset, stop) {
        if (!isText(node)) return null;
        const block = blockOf(node, stop);
        let text = node.nodeValue.slice(offset);
        let at = nextOutside(node, block);
        while (at && text.length < MAX_LENGTH) {
            if (isText(at)) {
                text += at.nodeValue;
                at = nextOutside(at, block);
            } else if (BLOCKS.has(at.nodeName)) {
                break;
            } else if (SKIPPED.has(at.nodeName) || !at.firstChild) {
                at = nextOutside(at, block);
            } else {
                at = at.firstChild;
            }
        }
        text = text.slice(0, MAX_LENGTH).replace(/^\s+/, '');
        return text.length ? text : null;
    }

    /** The text node and offset under (x, y): caretPositionFromPoint, else caretRangeFromPoint (WebView before 128). */
    function caretAt(doc, x, y) {
        if (doc.caretPositionFromPoint) {
            const position = doc.caretPositionFromPoint(x, y);
            return position ? { node: position.offsetNode, offset: position.offset } : null;
        }
        if (doc.caretRangeFromPoint) {
            const range = doc.caretRangeFromPoint(x, y);
            return range ? { node: range.startContainer, offset: range.startOffset } : null;
        }
        return null;
    }

    /** What a tap on [target] at (x, y) looks up, or null: only the definitions' own text. */
    function tapText(doc, target, x, y) {
        if (!target || !target.closest || target.closest(NOT_TEXT)) return null;
        const definition = target.closest('.glossary-item, .compact-summary');
        if (!definition) return null;
        const caret = caretAt(doc, x, y);
        if (!caret || !definition.contains(caret.node)) return null;
        const parent = caret.node.parentNode;
        if (parent && parent.closest && parent.closest(NOT_TEXT)) return null;
        return textFrom(caret.node, caret.offset, definition);
    }

    function listen(doc, lookUp) {
        doc.addEventListener('click', event => {
            if (event.defaultPrevented) return;
            const text = tapText(doc, event.target, event.clientX, event.clientY);
            if (text) lookUp(text);
        });
    }

    const api = { textFrom, caretAt, tapText, listen };
    root.genkanGlossTap = api;
    if (typeof module !== 'undefined') module.exports = api;
    if (root.document && root.document.addEventListener) {
        root.document.addEventListener('DOMContentLoaded', () => listen(root.document, text => root.GenkanPopup?.lookUp(text)));
    }
})(typeof window !== 'undefined' ? window : globalThis);
