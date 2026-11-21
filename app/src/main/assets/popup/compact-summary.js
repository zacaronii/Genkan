/*
 * Hachidori's "first meanings" (compact definition summary): the few short senses shown beside
 * a headword. Copied unmodified from Hachidori (https://github.com/bee-san/hachidori, commit
 * 5a5b5e2), extension/render/popup.js, lines 185-225 and 1701-2159, inside a wrapper that
 * exposes only extractCompactDefinitionSummary as window.genkanCompactSummary.
 *
 * Copyright (C) 2026 Manhhao and Hachidori's contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
(function () {
  "use strict";
  const DEFAULT_COMPACT_DEFINITION_SUMMARY_COUNT = 3;
  const MIN_COMPACT_DEFINITION_SUMMARY_COUNT = 1;
  const MAX_COMPACT_DEFINITION_SUMMARY_COUNT = 6;
  const COMPACT_DEFINITION_MAX_CHARACTERS = 240;
  const COMPACT_DEFINITION_MAX_NODES = 512;
  const COMPACT_DEFINITION_LETTER = /[A-Za-zぁ-ゟァ-ヿ㐀-鿿Ａ-Ｚａ-ｚ]/u;
  const COMPACT_DEFINITION_JAPANESE = /[ぁ-ゟァ-ヿ㐀-鿿]/u;
  const COMPACT_DEFINITION_BLOCK_TAGS = new Set([
    "article",
    "blockquote",
    "dd",
    "div",
    "dt",
    "h1",
    "h2",
    "h3",
    "h4",
    "h5",
    "h6",
    "li",
    "p",
    "section",
    "td",
    "th",
    "tr",
  ]);
  const COMPACT_DEFINITION_IGNORED_TAGS = new Set([
    "audio",
    "button",
    "canvas",
    "iframe",
    "img",
    "input",
    "rp",
    "rt",
    "script",
    "source",
    "style",
    "svg",
    "video",
  ]);

  function isRecord(value) {
    return value !== null && typeof value === "object" && !Array.isArray(value);
  }

  function parseCompactDefinitionValue(rawGlossary) {
    if (typeof rawGlossary !== "string" || !rawGlossary) {
      return null;
    }
    try {
      return JSON.parse(rawGlossary);
    } catch {
      return rawGlossary;
    }
  }

  function getCompactDefinitionMarker(value) {
    return isRecord(value?.data) && typeof value.data.content === "string"
      ? value.data.content.trim().toLowerCase()
      : "";
  }

  // Sanseido-style dictionaries (三省堂国語辞典, 新明解国語辞典 and their
  // bilingual conversions) name sections with data.name instead of markers.
  function getCompactDefinitionName(value) {
    return isRecord(value?.data) && typeof value.data.name === "string" ? value.data.name : "";
  }

  function isIgnoredCompactDefinitionSection(value) {
    // ルビG is Sanseido furigana, left out like rt.
    if (getCompactDefinitionName(value) === "ルビG") return true;
    const marker = getCompactDefinitionMarker(value);
    return marker && marker !== "glossary" && (
      marker.startsWith("part-of-speech") ||
      marker === "redirect-glossary" ||
      marker === "source" || marker.startsWith("source-") ||
      marker === "attribution" || marker.startsWith("attribution-") ||
      marker === "example" || marker === "examples" ||
      marker.startsWith("example-") ||
      marker === "form" || marker === "forms" ||
      marker.startsWith("forms-")
    );
  }

  function* compactDefinitionItemsFromText(parts) {
    const textRun = `[^\\s\\u2022]{1,${COMPACT_DEFINITION_MAX_CHARACTERS + 1}}`;
    const firstText = new RegExp(textRun, "gu");
    const nextText = new RegExp(`\\u2022|${textRun}`, "gu");
    const buffer = { text: "", characters: 0 };
    let pendingSpace = false;
    for (const text of parts) {
      let index = 0;
      while (index < text.length) {
        const matcher = buffer.characters > 0 ? nextText : firstText;
        matcher.lastIndex = index;
        const match = matcher.exec(text);
        if (!match) {
          pendingSpace = buffer.characters > 0;
          break;
        }
        pendingSpace ||= buffer.characters > 0 && match.index > index;
        index = matcher.lastIndex;
        if (match[0] === "\u2022") {
          yield buffer.text;
          buffer.text = "";
          buffer.characters = 0;
          pendingSpace = false;
          continue;
        }
        if (pendingSpace) {
          buffer.text += " ";
          buffer.characters += 1;
        }
        pendingSpace = false;
        if (buffer.characters <= COMPACT_DEFINITION_MAX_CHARACTERS) {
          appendCompactDefinitionText(buffer, match[0]);
        }
        // One extra normalized point proves truncation. Earlier accepted items
        // are at most 240 points, so this cannot falsely match a seen duplicate.
        if (buffer.characters > COMPACT_DEFINITION_MAX_CHARACTERS) {
          yield buffer.text;
          return;
        }
      }
    }
    if (buffer.characters > 0) yield buffer.text;
  }

  function appendCompactDefinitionText(buffer, text) {
    // The native matcher already bounds this run to 241 points. Count internal
    // surrogate pairs without building a point array unless truncation needs it.
    const characters = text.length - (text.match(/[\ud800-\udbff][\udc00-\udfff]/g)?.length || 0);
    const previous = buffer.text.charCodeAt(buffer.text.length - 1);
    const first = text.charCodeAt(0);
    const joined = previous >= 0xd800 && previous <= 0xdbff && first >= 0xdc00 && first <= 0xdfff ? 1 : 0;
    const available = COMPACT_DEFINITION_MAX_CHARACTERS + 1 - buffer.characters + joined;
    buffer.text += characters > available ? Array.from(text).slice(0, available).join("") : text;
    buffer.characters += Math.min(characters, available) - joined;
  }

  // Typed wrappers select their payload before incidental tags, as in the full
  // renderer. Discovery, collection and leading-image selection share this rule.
  function compactDefinitionTag(value) {
    if (value.type === "text" || value.type === "structured-content") return "";
    if (value.type === "image") return "img";
    return typeof value.tag === "string" ? value.tag.toLowerCase() : "";
  }

  function compactDefinitionContent(value) {
    return value.type === "text" && Object.hasOwn(value, "text") ? value.text : value.content;
  }

  function isCompactDefinitionBlock(value) {
    return isRecord(value) && COMPACT_DEFINITION_BLOCK_TAGS.has(compactDefinitionTag(value));
  }

  // The compact walkers use explicit frames like the glossary renderer, so
  // nesting depth is never a failure condition; the node budget bounds the work.
  function* collectCompactDefinitionText(root, state) {
    const stack = [{ kind: "value", value: root }];
    // Enclosing arrays, outermost first. A child's first text decides its
    // block separator, so empty inline children never need a block check.
    const arrays = [];
    const separators = () => {
      let count = 0;
      for (let index = arrays.length - 1; index >= 0 && !arrays[index].childHasText; index -= 1) {
        const array = arrays[index];
        array.childIsBlock = isCompactDefinitionBlock(array.value[array.index - 1]);
        if (array.hasText && (array.previousWasBlock || array.childIsBlock)) count += 1;
        array.childHasText = true;
      }
      return count;
    };
    while (stack.length > 0) {
      const frame = stack.pop();
      if (frame.kind === "array") {
        if (frame.childHasText) {
          frame.hasText = true;
          frame.previousWasBlock = frame.childIsBlock;
        }
        if (frame.index < frame.value.length && state.nodes < COMPACT_DEFINITION_MAX_NODES) {
          frame.childHasText = false;
          stack.push(frame, { kind: "value", value: frame.value[frame.index] });
          frame.index += 1;
        } else {
          arrays.pop();
        }
        continue;
      }
      if (state.nodes >= COMPACT_DEFINITION_MAX_NODES) continue;
      state.nodes += 1;
      const { value } = frame;
      let text = "";
      if (typeof value === "string" || typeof value === "number" ||
          typeof value === "boolean") {
        text = String(value);
      } else if (Array.isArray(value)) {
        const array = { kind: "array", value, index: 0,
          hasText: false, previousWasBlock: false, childHasText: false, childIsBlock: false };
        arrays.push(array);
        stack.push(array);
        continue;
      } else {
        if (!isRecord(value) || isIgnoredCompactDefinitionSection(value)) continue;
        const tag = compactDefinitionTag(value);
        if (COMPACT_DEFINITION_IGNORED_TAGS.has(tag)) continue;
        if (tag !== "br") {
          const content = compactDefinitionContent(value);
          if (content !== undefined) stack.push({ kind: "value", value: content });
          continue;
        }
        text = " ";
      }
      if (!text) continue;
      for (let count = separators(); count > 0; count -= 1) yield " ";
      yield text;
    }
  }

  function findCompactDefinitionNodes(root, predicate, state) {
    const matches = [];
    const stack = [{ kind: "value", value: root }];
    while (stack.length > 0 && state.nodes < COMPACT_DEFINITION_MAX_NODES) {
      const frame = stack.pop();
      if (frame.kind === "array") {
        if (frame.index < frame.value.length) {
          stack.push(frame, { kind: "value", value: frame.value[frame.index] });
          frame.index += 1;
        }
        continue;
      }
      state.nodes += 1;
      const { value } = frame;
      if (Array.isArray(value)) {
        stack.push({ kind: "array", value, index: 0 });
        continue;
      }
      if (!isRecord(value) || isIgnoredCompactDefinitionSection(value)) continue;
      const tag = compactDefinitionTag(value);
      if (tag === "br" || COMPACT_DEFINITION_IGNORED_TAGS.has(tag)) continue;
      if (predicate(value, tag)) {
        matches.push(value);
        continue;
      }
      const content = compactDefinitionContent(value);
      if (content !== undefined) stack.push({ kind: "value", value: content });
    }
    return matches;
  }

  function isCompactDefinitionList(_value, tag) {
    return tag === "ul" || tag === "ol";
  }

  /** Block nodes with no block descendants: the smallest sense-sized chunks. */
  function findCompactDefinitionLeafBlocks(root, state = { nodes: 0 }) {
    return findCompactDefinitionNodes(
      root,
      (value, tag) => COMPACT_DEFINITION_BLOCK_TAGS.has(tag) && findCompactDefinitionNodes(
        value.content,
        (_child, childTag) => COMPACT_DEFINITION_BLOCK_TAGS.has(childTag),
        { nodes: 0 }
      ).length === 0,
      state
    );
  }

  function* compactDefinitionItemsFromNodes(nodes) {
    let found = false;
    let inspected = 0;
    for (const node of nodes) {
      if (inspected >= COMPACT_DEFINITION_MAX_NODES) break;
      inspected += 1;
      for (const item of compactDefinitionItemsFromText(collectCompactDefinitionText(node, { nodes: 0 }))) {
        found = true;
        yield item;
      }
    }
    // The first nonempty semantic list owns the preview, even if deduplication
    // leaves fewer snippets than requested. Empty lists still fall through.
    return found;
  }

  function compactDefinitionItemsFromList(list) {
    const rawChildren = Array.isArray(list.content)
      ? list.content
      : [list.content];
    const children = rawChildren.slice(0, COMPACT_DEFINITION_MAX_NODES);
    const listItems = [];
    for (const child of children) {
      if (isRecord(child) && compactDefinitionTag(child) === "li") {
        listItems.push(child);
      }
    }
    return compactDefinitionItemsFromNodes(
      listItems.length > 0 ? listItems : children
    );
  }

  function* compactDefinitionItemsFromMarkedNode(node) {
    const tag = compactDefinitionTag(node);
    if (tag === "ul" || tag === "ol") {
      yield* compactDefinitionItemsFromList(node);
      return;
    }
    const content = compactDefinitionContent(node);
    const nestedLists = findCompactDefinitionNodes(
      content,
      isCompactDefinitionList,
      { nodes: 0 }
    );
    if (nestedLists.length > 0) {
      for (const list of nestedLists) yield* compactDefinitionItemsFromList(list);
      return;
    }
    const leafBlocks = findCompactDefinitionLeafBlocks(content);
    yield* leafBlocks.length > 0
      ? compactDefinitionItemsFromNodes(leafBlocks)
      : compactDefinitionItemsFromNodes([node]);
  }

  // Sanseido index entries list sub-headwords as bare links, not definitions.
  function isCompactDefinitionLinkOnly(sense) {
    const node = isRecord(sense) && sense.type === "structured-content" ? sense.content : sense;
    // The raw tag test spares ordinary senses a tag normalization.
    return isRecord(node) && /^a$/i.test(node.tag) && compactDefinitionTag(node) === "a";
  }

  function isCompactDefinitionSense(value) {
    const name = getCompactDefinitionName(value);
    return name === "語義" || name === "語釈";
  }

  // Bilingual Sanseido conversions write a 語釈 as English, a bare " " child,
  // then the original Japanese. English-only and monolingual glosses have no
  // such child, and a bare space between English nodes is followed by ASCII
  // letters, so only a space that opens Japanese text ends the English half.
  function compactDefinitionEnglishHalf(gloss) {
    const parts = Array.isArray(gloss.content) ? gloss.content : [gloss.content];
    const separator = parts.lastIndexOf(" ");
    if (separator < 0) return parts;
    let rest = "";
    for (const text of collectCompactDefinitionText(parts.slice(separator + 1), { nodes: 0 })) {
      rest += text;
      if (rest.length > 64) break;
    }
    const lead = COMPACT_DEFINITION_LETTER.exec(rest)?.[0];
    return lead && !/[A-Za-z]/.test(lead) && COMPACT_DEFINITION_JAPANESE.test(rest)
      ? parts.slice(0, separator)
      : parts;
  }

  function* compactDefinitionFallbackNodes(parsed) {
    // Top-level glossary-array entries are separate senses, unlike inline
    // content arrays. Expand blocks within each sense without dropping siblings.
    const discovery = { nodes: 0 };
    for (const sense of Array.isArray(parsed) ? parsed : [parsed]) {
      if (isCompactDefinitionLinkOnly(sense)) continue;
      const leafBlocks = findCompactDefinitionLeafBlocks(sense, discovery);
      if (leafBlocks.length > 0) yield* leafBlocks;
      else yield sense;
    }
  }

  function* extractCompactDefinitionItems(parsed) {
    if (parsed === null) return;

    // One discovery pass finds marked glossary sections and Sanseido senses.
    const marked = findCompactDefinitionNodes(
      parsed,
      (value) => getCompactDefinitionMarker(value) === "glossary" || isCompactDefinitionSense(value),
      { nodes: 0 }
    );
    const glossaryNodes = marked.filter((node) => getCompactDefinitionMarker(node) === "glossary");
    if (glossaryNodes.length > 0) {
      for (const node of glossaryNodes) yield* compactDefinitionItemsFromMarkedNode(node);
      return;
    }

    // A Sanseido 語義 sense holds its number, labels, 語釈 gloss and examples;
    // 副義 sub-senses nest their own 語釈. A sense without one is a ⇨ reference.
    if (marked.length > 0) {
      for (const sense of marked) {
        const glosses = getCompactDefinitionName(sense) === "語釈"
          ? [sense]
          : findCompactDefinitionNodes(
            compactDefinitionContent(sense),
            (value) => getCompactDefinitionName(value) === "語釈",
            { nodes: 0 }
          );
        yield* compactDefinitionItemsFromNodes(glosses.map(compactDefinitionEnglishHalf));
      }
      return;
    }

    const semanticLists = findCompactDefinitionNodes(
      parsed,
      isCompactDefinitionList,
      { nodes: 0 }
    );
    for (const list of semanticLists) {
      if (yield* compactDefinitionItemsFromList(list)) return;
    }

    yield* compactDefinitionItemsFromNodes(compactDefinitionFallbackNodes(parsed));
  }

  // null means no visible content; false means text or another non-image lead.
  // Stop at that first meaningful token, not at an image later in a definition.
  function leadingCompactDefinitionImage(root, state) {
    const stack = [{ kind: "value", value: root }];
    while (stack.length > 0) {
      const frame = stack.pop();
      if (frame.kind === "array") {
        if (frame.index < frame.value.length) {
          stack.push(frame, { kind: "value", value: frame.value[frame.index] });
          frame.index += 1;
        }
        continue;
      }
      if (state.nodes >= COMPACT_DEFINITION_MAX_NODES) return false;
      state.nodes += 1;
      const { value } = frame;
      if (Array.isArray(value)) {
        stack.push({ kind: "array", value, index: 0 });
        continue;
      }
      if (!isRecord(value)) {
        if (value != null && /\S/u.test(String(value))) return false;
        continue;
      }
      if (isIgnoredCompactDefinitionSection(value)) continue;
      const tag = compactDefinitionTag(value);
      if (tag === "img") return value;
      if (tag === "br" || COMPACT_DEFINITION_IGNORED_TAGS.has(tag)) continue;
      stack.push({ kind: "value", value: compactDefinitionContent(value) });
    }
    return null;
  }

  function extractCompactDefinitionSummary(
    glossaries,
    preferredDictionary = null,
    maximumItems = DEFAULT_COMPACT_DEFINITION_SUMMARY_COUNT
  ) {
    const itemLimit = Number.isInteger(maximumItems) &&
      maximumItems >= MIN_COMPACT_DEFINITION_SUMMARY_COUNT &&
      maximumItems <= MAX_COMPACT_DEFINITION_SUMMARY_COUNT
      ? maximumItems
      : DEFAULT_COMPACT_DEFINITION_SUMMARY_COUNT;
    const byDictionary = new Map();
    for (const glossary of Array.isArray(glossaries) ? glossaries : []) {
      if (!byDictionary.has(glossary.dictionary)) {
        byDictionary.set(glossary.dictionary, []);
      }
      byDictionary.get(glossary.dictionary).push(glossary.glossary);
    }
    const dictionaries = [...byDictionary.keys()];
    if (preferredDictionary !== null && byDictionary.has(preferredDictionary)) {
      dictionaries.splice(dictionaries.indexOf(preferredDictionary), 1);
      dictionaries.unshift(preferredDictionary);
    }
    for (const dictionary of dictionaries) {
      const rawGlossaries = byDictionary.get(dictionary);
      const items = [];
      const seen = new Set();
      let characterCount = 0;
      let leading = null;
      for (const rawGlossary of rawGlossaries) {
        const parsed = parseCompactDefinitionValue(rawGlossary);
        if (leading === null) leading = leadingCompactDefinitionImage(parsed, { nodes: 0 });
        for (const item of extractCompactDefinitionItems(parsed)) {
          if (!item || seen.has(item)) continue;
          const remaining = COMPACT_DEFINITION_MAX_CHARACTERS - characterCount;
          const codePoints = [];
          for (const character of item) {
            codePoints.push(character);
            if (codePoints.length > remaining) break;
          }
          const bounded = codePoints.length <= remaining
            ? item
            : remaining === 1
              ? "\u2026"
              : `${codePoints.slice(0, remaining - 1).join("")}\u2026`;
          items.push(bounded);
          seen.add(item);
          characterCount += Math.min(codePoints.length, remaining);
          if (bounded !== item || items.length >= itemLimit || characterCount >= COMPACT_DEFINITION_MAX_CHARACTERS) break;
        }
        if (
          items.length >= itemLimit ||
          characterCount >= COMPACT_DEFINITION_MAX_CHARACTERS
        ) {
          break;
        }
      }
      if (items.length > 0) return { dictionary, items, image: leading || null };
    }
    return null;
  }

  window.genkanCompactSummary = extractCompactDefinitionSummary;
})();
