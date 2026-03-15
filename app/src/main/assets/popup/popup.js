/**
 * Yomidroid Popup — DOM-based entry renderer (Yomitan architecture).
 *
 * Entry data is passed as JSON from Kotlin via `setEntries(json)`.
 * The renderer builds real DOM elements (not innerHTML strings) for
 * each entry, supporting structured content, pitch accent SVG,
 * dictionary-scoped CSS, and Anki export.
 */

'use strict';

// ============================================================
//  State
// ============================================================
var currentEntryIndex = 0;
var totalEntries = 0;
var entriesData = [];
var imageBasePath = '';

// Safe HTML tags for structured content
var ALLOWED_TAGS = new Set([
    'div','span','p','ruby','rt','rp','ul','ol','li',
    'table','thead','tbody','tfoot','tr','td','th',
    'details','summary','a','br','img',
    'b','i','em','strong','u','s','sub','sup',
    'h1','h2','h3','h4','h5','h6'
]);

// Small kana for mora splitting
var SMALL_KANA = '\u3083\u3085\u3087\u3041\u3043\u3045\u3047\u3049\u30E3\u30E5\u30E7\u30A1\u30A3\u30A5\u30A7\u30A9';

// ============================================================
//  Public API — called from Kotlin
// ============================================================

/**
 * Main entry point. Receives JSON string of entries + config.
 */
function setEntries(json) {
    var data = JSON.parse(json);
    entriesData = data.entries || [];
    totalEntries = entriesData.length;
    currentEntryIndex = 0;

    imageBasePath = data.imageBasePath || '';
    document.documentElement.setAttribute('data-theme', data.theme || 'dark');
    injectCustomCss(data.customCss || null);
    injectDictionaryCss(data.dictionaryCss || {});

    renderAll();
    reportHeight();
}

function scrollContent(px) {
    window.scrollBy(0, px);
}

function reportHeight() {
    requestAnimationFrame(function() {
        setTimeout(function() {
            if (window.YomidroidPopup) {
                window.YomidroidPopup.reportContentHeight(document.body.scrollHeight);
            }
        }, 30);
    });
}

// ============================================================
//  CSS injection
// ============================================================

function injectCustomCss(css) {
    var existing = document.getElementById('custom-css');
    if (existing) existing.remove();
    if (!css) return;
    var style = document.createElement('style');
    style.id = 'custom-css';
    style.textContent = css;
    document.head.appendChild(style);
}

function injectDictionaryCss(dictCssMap) {
    document.querySelectorAll('.dict-css').forEach(function(el) { el.remove(); });
    for (var dictName in dictCssMap) {
        if (!dictCssMap.hasOwnProperty(dictName)) continue;
        var rawCss = dictCssMap[dictName];
        if (!rawCss) continue;
        var scoped = scopeCss(rawCss, dictName);
        var style = document.createElement('style');
        style.className = 'dict-css';
        style.setAttribute('data-dictionary', dictName);
        style.textContent = scoped;
        document.head.appendChild(style);
    }
}

function scopeCss(css, dictName) {
    var scope = '[data-dictionary="' + dictName.replace(/"/g, '\\"') + '"]';
    return css.replace(/([^{}]+)\{/g, function(match, selector) {
        if (selector.trim().charAt(0) === '@') return match;
        return scope + ' ' + selector.trim() + ' {';
    });
}

// ============================================================
//  Render all entries
// ============================================================

function renderAll() {
    var container = document.getElementById('dictionary-entries');
    container.innerHTML = '';

    // Group entries by expression|reading
    var groups = [];
    var groupMap = {};
    for (var i = 0; i < entriesData.length; i++) {
        var e = entriesData[i];
        var key = (e.expression || '') + '|' + (e.reading || '');
        if (groupMap[key] == null) {
            groupMap[key] = groups.length;
            groups.push([]);
        }
        groups[groupMap[key]].push({ entry: e, originalIndex: i });
    }

    totalEntries = groups.length;

    for (var g = 0; g < groups.length; g++) {
        var el = createGroupedEntry(groups[g], g);
        container.appendChild(el);
    }
}

// ============================================================
//  Navigation (called from Kotlin via jumpEntry)
// ============================================================

function selectEntry(index) {
    if (index < 0 || index >= totalEntries) return;
    var entries = document.querySelectorAll('.entry');
    if (entries[currentEntryIndex]) entries[currentEntryIndex].classList.remove('entry-current');
    if (entries[index]) entries[index].classList.add('entry-current');
    currentEntryIndex = index;
}

function jumpEntry(delta) {
    var n = currentEntryIndex + delta;
    if (n < 0) n = 0;
    if (n >= totalEntries) n = totalEntries - 1;
    if (n === currentEntryIndex) return;
    selectEntry(n);
    var el = document.getElementById('entry-' + n);
    if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

// ============================================================
//  Helpers for star badge and definition tag filtering
// ============================================================

function hasStarTag(tags) {
    if (!tags) return false;
    for (var i = 0; i < tags.length; i++) {
        if (tags[i].name === '\u2605') return true;
    }
    return false;
}

function filterDefTags(tags) {
    if (!tags) return [];
    var out = [];
    for (var i = 0; i < tags.length; i++) {
        // Skip star badge (rendered separately) and POS tags (covered by posDisplayLabel)
        if (tags[i].name === '\u2605') continue;
        if (tags[i].category === 'partOfSpeech') continue;
        out.push(tags[i]);
    }
    return out;
}

// ============================================================
//  Entry builder — grouped
// ============================================================

function createGroupedEntry(group, groupIndex) {
    var first = group[0].entry;
    var el = createElement('div', 'entry' + (groupIndex === 0 ? ' entry-current' : ''));
    el.id = 'entry-' + groupIndex;
    el.setAttribute('data-index', groupIndex);
    el.onclick = function() { selectEntry(groupIndex); };

    // --- Row 1: Expression (with furigana) + star + tags + pitch + freq + Anki ---
    var topRow = createElement('div', 'entry-top-row');

    var hwTerm = createElement('span', 'headword-term');
    var hasReading = first.reading && first.reading !== first.expression;
    var hasFurigana = hasReading && hasKanji(first.expression);
    if (hasFurigana) {
        hwTerm.appendChild(buildFurigana(first.expression, first.reading));
    } else {
        hwTerm.textContent = first.expression;
    }
    topRow.appendChild(hwTerm);

    if (hasStarTag(first.definitionTags)) {
        var starBadge = createElement('span', 'star-badge');
        starBadge.textContent = '\u2605';
        topRow.appendChild(starBadge);
    }

    // Tags inline (POS, name type, definition tags — no frequency here)
    var defTagsFiltered = filterDefTags(first.definitionTags);
    if (first.posDisplayLabel) topRow.appendChild(createTag(first.posDisplayLabel, 'partOfSpeech'));
    if (first.nameTypeLabel) topRow.appendChild(createTag(first.nameTypeLabel, 'name'));
    for (var dt = 0; dt < defTagsFiltered.length; dt++) {
        var dtag = defTagsFiltered[dt];
        topRow.appendChild(createTag(dtag.notes || dtag.name, dtag.category || 'default'));
    }

    // Frequency badges inline
    if (first.frequencyBadge) topRow.appendChild(createFrequencyTag('Freq', first.frequencyBadge, first.frequencyBadgeColor));
    if (first.jpdbBadge) topRow.appendChild(createFrequencyTag('JPDB', first.jpdbBadge, first.jpdbBadgeColor));

    // Pitch accent inline (collect from all entries in group)
    var allPitch = [];
    var pitchSeen = {};
    for (var d = 0; d < group.length; d++) {
        var de = group[d].entry;
        var pList = de.pitchDownsteps || (de.pitchDownstep != null ? [de.pitchDownstep] : []);
        for (var p = 0; p < pList.length; p++) {
            if (!pitchSeen[pList[p]]) {
                pitchSeen[pList[p]] = true;
                allPitch.push(pList[p]);
            }
        }
    }

    if (allPitch.length > 0) {
        var reading = first.reading || first.expression;
        var morae = splitMorae(reading);
        if (morae.length > 0) {
            var pronSection = createElement('span', 'pronunciation-section');
            var svgHtml = '';
            for (var p = 0; p < allPitch.length; p++) {
                svgHtml += renderPitchSvg(morae, allPitch[p]);
            }
            pronSection.innerHTML = svgHtml;
            var dsText = createElement('span', 'pitch-downstep-num');
            dsText.textContent = '[' + allPitch.join(', ') + ']';
            pronSection.appendChild(dsText);
            topRow.appendChild(pronSection);
        }
    }

    // Anki button (rightmost)
    topRow.appendChild(createAnkiButton(group[0].originalIndex));

    el.appendChild(topRow);

    // --- Row 2: Reading (only if no furigana and reading differs) ---
    if (hasReading && !hasFurigana) {
        var readingRow = createElement('div', 'reading-row');
        var hwRead = createElement('span', 'headword-reading');
        hwRead.textContent = first.reading;
        readingRow.appendChild(hwRead);
        el.appendChild(readingRow);
    }

    // --- Row 3: Inflection rules ---
    if (first.deinflectionPath) {
        var inflection = createElement('div', 'inflection-rule-chain');
        inflection.textContent = '\u00AB ' + first.deinflectionPath;
        el.appendChild(inflection);
    }

    // --- Row 5: Definition sections (collapsible) ---
    var body = createElement('div', 'entry-body');

    for (var d = 0; d < group.length; d++) {
        var dictEntry = group[d].entry;
        var section = createElement('div', 'definition-section');
        section.setAttribute('data-dictionary', dictEntry.dictionaryTitle || dictEntry.sourceLabel || '');

        // Collapsible source label
        var dictLabel = createElement('div', 'definition-source-label');
        var toggle = createElement('span', 'section-toggle');
        toggle.textContent = '\u25BE';
        dictLabel.appendChild(toggle);
        dictLabel.appendChild(document.createTextNode(' ' + (dictEntry.dictionaryTitle || dictEntry.sourceLabel || '')));
        section.appendChild(dictLabel);

        // Per-definition tags (specific to this dictionary entry)
        var entryDefTags = filterDefTags(dictEntry.definitionTags);
        if (entryDefTags.length > 0 && d > 0) {
            var perDefTagRow = createElement('div', 'per-def-tags');
            for (var pdt = 0; pdt < entryDefTags.length; pdt++) {
                var pdtag = entryDefTags[pdt];
                perDefTagRow.appendChild(createTag(pdtag.notes || pdtag.name, pdtag.category || 'default'));
            }
            section.appendChild(perDefTagRow);
        }

        // Definitions wrapper
        var defContent = createElement('div', 'section-content');
        var defCount = countDefinitions(dictEntry);
        if (dictEntry.glossaryRich) {
            defContent.appendChild(renderRichDefinitions(dictEntry.glossaryRich, defCount, dictEntry.sourceDictId));
        } else {
            defContent.appendChild(renderPlainDefinitions(dictEntry.glossary, defCount));
        }
        section.appendChild(defContent);

        // Toggle click handler
        (function(label, content, arrow) {
            label.onclick = function(e) {
                e.stopPropagation();
                var collapsed = content.classList.toggle('collapsed');
                arrow.textContent = collapsed ? '\u25B8' : '\u25BE';
                reportHeight();
            };
        })(dictLabel, defContent, toggle);

        body.appendChild(section);
    }

    el.appendChild(body);
    return el;
}

// ============================================================
//  Anki button — Anki logo icon
// ============================================================

function createAnkiButton(index) {
    var btn = createElement('button', 'action-btn anki-btn');
    btn.title = 'Add to Anki';
    // Anki star/diamond icon (outline)
    btn.innerHTML = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M12 2l3.09 6.26L22 9.27l-5 4.87L18.18 22 12 18.56 5.82 22 7 14.14l-5-4.87 6.91-1.01L12 2z"/></svg>';
    btn.onclick = function(e) {
        e.stopPropagation();
        if (window.YomidroidPopup) {
            window.YomidroidPopup.ankiExport(index);
            btn.classList.add('exported');
            // Filled star
            btn.innerHTML = '<svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor" stroke="currentColor" stroke-width="1" stroke-linejoin="round"><path d="M12 2l3.09 6.26L22 9.27l-5 4.87L18.18 22 12 18.56 5.82 22 7 14.14l-5-4.87 6.91-1.01L12 2z"/></svg>';
        }
    };
    return btn;
}

// ============================================================
//  Tags
// ============================================================

// ============================================================
//  Furigana / Ruby helpers
// ============================================================

function hasKanji(text) {
    for (var i = 0; i < text.length; i++) {
        var code = text.charCodeAt(i);
        // CJK Unified Ideographs (4E00-9FFF) + Extension A (3400-4DBF)
        if ((code >= 0x4E00 && code <= 0x9FFF) || (code >= 0x3400 && code <= 0x4DBF)) return true;
    }
    return false;
}

function buildFurigana(expression, reading) {
    var frag = document.createDocumentFragment();
    // Simple approach: wrap entire expression in ruby with reading
    // For compound kanji, try to distribute reading
    var segments = segmentKanjiKana(expression, reading);
    for (var i = 0; i < segments.length; i++) {
        var seg = segments[i];
        if (seg.ruby) {
            var ruby = document.createElement('ruby');
            ruby.appendChild(document.createTextNode(seg.text));
            var rt = document.createElement('rt');
            rt.textContent = seg.ruby;
            ruby.appendChild(rt);
            frag.appendChild(ruby);
        } else {
            frag.appendChild(document.createTextNode(seg.text));
        }
    }
    return frag;
}

function segmentKanjiKana(expression, reading) {
    // Split expression into kanji runs and kana runs
    var parts = [];
    var current = '';
    var currentIsKanji = false;
    for (var i = 0; i < expression.length; i++) {
        var ch = expression[i];
        var isK = isKanjiChar(ch);
        if (i === 0) {
            currentIsKanji = isK;
            current = ch;
        } else if (isK === currentIsKanji) {
            current += ch;
        } else {
            parts.push({ text: current, isKanji: currentIsKanji });
            current = ch;
            currentIsKanji = isK;
        }
    }
    if (current) parts.push({ text: current, isKanji: currentIsKanji });

    // If only one part (all kanji or all kana), simple case
    if (parts.length <= 1) {
        if (parts.length === 1 && parts[0].isKanji) {
            return [{ text: expression, ruby: reading }];
        }
        return [{ text: expression, ruby: null }];
    }

    // Try to match kana runs in reading to distribute furigana (forward-only)
    var segments = [];
    var readingPos = 0;
    for (var i = 0; i < parts.length; i++) {
        var part = parts[i];
        if (!part.isKanji) {
            // Verify kana matches at current reading position (forward-only, no indexOf)
            if (reading.startsWith(part.text, readingPos)) {
                // Assign furigana to preceding kanji segment
                if (segments.length > 0 && segments[segments.length - 1].ruby === '') {
                    segments[segments.length - 1].ruby = reading.substring(
                        segments[segments.length - 1]._rStart, readingPos
                    );
                }
                segments.push({ text: part.text, ruby: null });
                readingPos += part.text.length;
            } else {
                // Kana doesn't match at expected position — fallback to whole-word furigana
                return [{ text: expression, ruby: reading }];
            }
        } else {
            // Mark kanji segment with its reading start position
            segments.push({ text: part.text, ruby: '', _rStart: readingPos });
        }
    }
    // Assign remaining reading to last kanji segment
    if (segments.length > 0 && segments[segments.length - 1].ruby === '') {
        segments[segments.length - 1].ruby = reading.substring(
            segments[segments.length - 1]._rStart
        );
    }
    // Clean up: remove temp _rStart and empty rubies
    for (var i = 0; i < segments.length; i++) {
        delete segments[i]._rStart;
        if (segments[i].ruby === '') segments[i].ruby = null;
    }
    return segments;
}

function isKanjiChar(ch) {
    var code = ch.charCodeAt(0);
    return (code >= 0x4E00 && code <= 0x9FFF) || (code >= 0x3400 && code <= 0x4DBF);
}

// ============================================================
//  Frequency tag
// ============================================================

function createFrequencyTag(label, value, color) {
    var tag = createElement('span', 'frequency-tag');
    if (color) {
        tag.style.background = color;
        tag.style.color = '#fff';
    }
    var lbl = createElement('span', 'frequency-label');
    lbl.textContent = label;
    tag.appendChild(lbl);
    tag.appendChild(document.createTextNode(value));
    return tag;
}

// ============================================================
//  Tags
// ============================================================

function createTag(text, category) {
    var tag = createElement('span', 'tag');
    tag.setAttribute('data-category', category);
    tag.textContent = text;
    return tag;
}

function createColorTag(text, color) {
    var tag = createElement('span', 'tag');
    if (color) {
        tag.style.background = hexToRgba(color, 0.15);
        tag.style.color = color;
    }
    tag.textContent = text;
    return tag;
}

function hexToRgba(hex, alpha) {
    if (!hex || hex.charAt(0) !== '#') return hex;
    var r = parseInt(hex.substring(1, 3), 16);
    var g = parseInt(hex.substring(3, 5), 16);
    var b = parseInt(hex.substring(5, 7), 16);
    return 'rgba(' + r + ',' + g + ',' + b + ',' + alpha + ')';
}

// ============================================================
//  Definitions — rich (structured content)
// ============================================================

function renderRichDefinitions(glossaryRichJson, count, dictId) {
    try {
        var definitions = JSON.parse(glossaryRichJson);
        var ol = createElement('ol', 'definition-list');
        ol.setAttribute('data-count', definitions.length);

        for (var i = 0; i < definitions.length; i++) {
            var item = definitions[i];
            var li = createElement('li', 'definition-item');
            var gloss = createElement('span', 'gloss-content');

            if (typeof item === 'string') {
                gloss.textContent = item;
            } else if (item && typeof item === 'object') {
                if (item.type === 'structured-content') {
                    gloss.appendChild(renderStructuredContent(item.content, dictId));
                } else if (item.type === 'text') {
                    gloss.textContent = item.text || '';
                } else {
                    gloss.appendChild(renderStructuredContent(item, dictId));
                }
            }

            li.appendChild(gloss);
            ol.appendChild(li);
        }
        return ol;
    } catch (e) {
        var div = createElement('div', 'definition-list');
        div.textContent = '(render error)';
        return div;
    }
}

// ============================================================
//  Definitions — plain text
// ============================================================

function renderPlainDefinitions(glossary, count) {
    var container = createElement('div', 'definition-list');
    container.setAttribute('data-count', '1');

    var item = createElement('div', 'definition-item');
    var content = createElement('span', 'gloss-content gloss-content-inline');

    for (var i = 0; i < glossary.length; i++) {
        if (i > 0) {
            var sep = createElement('span', 'gloss-separator');
            sep.textContent = ' \u00B7 ';
            content.appendChild(sep);
        }
        var span = createElement('span', 'gloss-item');
        span.textContent = glossary[i];
        content.appendChild(span);
    }

    item.appendChild(content);
    container.appendChild(item);
    return container;
}

// ============================================================
//  Structured Content Renderer
// ============================================================

function renderStructuredContent(node, dictId) {
    if (node == null) return document.createTextNode('');
    if (typeof node === 'string') return document.createTextNode(node);
    if (typeof node === 'number') return document.createTextNode(String(node));

    if (Array.isArray(node)) {
        var frag = document.createDocumentFragment();
        for (var i = 0; i < node.length; i++) {
            frag.appendChild(renderStructuredContent(node[i], dictId));
        }
        return frag;
    }

    if (typeof node === 'object') {
        var tag = (node.tag || '').toLowerCase();

        if (!tag) {
            if (node.content != null) return renderStructuredContent(node.content, dictId);
            if (node.text) return document.createTextNode(node.text);
            return document.createTextNode('');
        }

        if (!ALLOWED_TAGS.has(tag)) tag = 'span';

        if (tag === 'br') return document.createElement('br');
        if (tag === 'img') {
            var imgPath = node.path || node.src || '';
            if (imgPath && imageBasePath) {
                var imgEl = document.createElement('img');
                imgEl.className = 'sc-img';
                var fileName = imgPath.split('/').pop();
                imgEl.src = imageBasePath + '/' + (dictId || '_') + '/images/' + fileName;
                imgEl.alt = node.alt || node.title || '';
                if (node.width) imgEl.style.width = node.width + 'px';
                if (node.height) imgEl.style.height = node.height + 'px';
                if (node.sizeUnits === 'em') {
                    if (node.width) imgEl.style.width = node.width + 'em';
                    if (node.height) imgEl.style.height = node.height + 'em';
                }
                imgEl.onerror = function() {
                    var ph = createElement('span', 'img-placeholder');
                    ph.textContent = '[' + (node.alt || node.title || 'image') + ']';
                    imgEl.replaceWith(ph);
                };
                return imgEl;
            }
            var title = node.title || node.alt || '';
            if (title) {
                var ph = createElement('span', 'img-placeholder');
                ph.textContent = '[' + title + ']';
                return ph;
            }
            return document.createTextNode('');
        }

        var el;
        if (tag === 'table') {
            var wrapper = createElement('div', 'sc-table-container');
            el = document.createElement('table');
            el.className = 'sc-' + tag;
            applyNodeAttributes(el, node);
            if (node.content != null) el.appendChild(renderStructuredContent(node.content, dictId));
            wrapper.appendChild(el);
            return wrapper;
        }

        if (tag === 'a') {
            el = document.createElement('a');
            if (node.href) el.href = node.href;
            el.className = 'sc-a';
        } else {
            el = document.createElement(tag);
            el.className = 'sc-' + tag;
        }

        applyNodeAttributes(el, node);
        if (tag === 'details' && node.open) el.open = true;
        if (node.content != null) {
            el.appendChild(renderStructuredContent(node.content, dictId));
        }

        return el;
    }

    return document.createTextNode(String(node));
}

function applyNodeAttributes(el, node) {
    if (node.style && typeof node.style === 'object') {
        for (var key in node.style) {
            if (!node.style.hasOwnProperty(key)) continue;
            var cssKey = key.replace(/([A-Z])/g, '-$1').toLowerCase();
            el.style.setProperty(cssKey, node.style[key]);
        }
    }
    if (node.data && typeof node.data === 'object') {
        for (var dkey in node.data) {
            if (!node.data.hasOwnProperty(dkey)) continue;
            el.setAttribute('data-' + dkey, node.data[dkey]);
        }
    }
    if (node['class']) el.className += ' sc-' + node['class'];
    if (node.lang) el.lang = node.lang;
    if (node.colSpan) el.colSpan = node.colSpan;
    if (node.rowSpan) el.rowSpan = node.rowSpan;
}

// ============================================================
//  Pitch accent SVG
// ============================================================

function splitMorae(reading) {
    var morae = [];
    for (var i = 0; i < reading.length; i++) {
        var mora = reading[i];
        while (i + 1 < reading.length && SMALL_KANA.indexOf(reading[i + 1]) !== -1) {
            i++;
            mora += reading[i];
        }
        morae.push(mora);
    }
    return morae;
}

function renderPitchSvg(morae, downstep) {
    var moraWidth = 21;
    var highY = 6, lowY = 21, dotR = 3, textY = 35;
    var svgWidth = morae.length * moraWidth + 12;
    var svgHeight = 42;

    var svg = '<svg class="pitch-graph" width="' + svgWidth + '" height="' + svgHeight + '" viewBox="0 0 ' + svgWidth + ' ' + svgHeight + '">';

    var isHigh = [];
    for (var i = 0; i < morae.length; i++) {
        if (downstep === 0) isHigh.push(i > 0);
        else if (downstep === 1) isHigh.push(i === 0);
        else isHigh.push(i >= 1 && i < downstep);
    }

    for (var i = 0; i < morae.length - 1; i++) {
        var x1 = 6 + i * moraWidth + moraWidth / 2;
        var y1 = isHigh[i] ? highY : lowY;
        var x2 = 6 + (i + 1) * moraWidth + moraWidth / 2;
        var y2 = isHigh[i + 1] ? highY : lowY;
        svg += '<line x1="' + x1 + '" y1="' + y1 + '" x2="' + x2 + '" y2="' + y2 + '" stroke="var(--accent-color)" stroke-width="1.2" opacity="0.6"/>';
    }

    for (var i = 0; i < morae.length; i++) {
        var cx = 6 + i * moraWidth + moraWidth / 2;
        var cy = isHigh[i] ? highY : lowY;
        svg += '<circle cx="' + cx + '" cy="' + cy + '" r="' + dotR + '" fill="var(--accent-color)"/>';
        if (downstep > 0 && i === downstep - 1) {
            svg += '<circle cx="' + cx + '" cy="' + cy + '" r="' + (dotR + 2) + '" fill="none" stroke="#f44336" stroke-width="1.2"/>';
        }
        svg += '<text x="' + cx + '" y="' + textY + '" text-anchor="middle" fill="var(--rt-color)" font-size="8">' + escapeHtml(morae[i]) + '</text>';
    }

    svg += '</svg>';
    return svg;
}

// ============================================================
//  Scroll tracking
// ============================================================

var scrollTimer = null;
window.addEventListener('scroll', function() {
    clearTimeout(scrollTimer);
    scrollTimer = setTimeout(function() {
        var entries = document.querySelectorAll('.entry');
        var scrollTop = document.documentElement.scrollTop || document.body.scrollTop;
        var closest = 0, closestDist = Infinity;
        for (var i = 0; i < entries.length; i++) {
            var dist = Math.abs(entries[i].offsetTop - scrollTop);
            if (dist < closestDist) { closestDist = dist; closest = i; }
        }
        if (closest !== currentEntryIndex) selectEntry(closest);
    }, 100);
}, { passive: true });

// ============================================================
//  Helpers
// ============================================================

function createElement(tag, className) {
    var el = document.createElement(tag);
    if (className) el.className = className;
    return el;
}

function countDefinitions(entry) {
    if (entry.glossaryRich) {
        try { return JSON.parse(entry.glossaryRich).length; } catch (e) {}
    }
    return entry.glossary ? entry.glossary.length : 0;
}

function escapeHtml(text) {
    var div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
