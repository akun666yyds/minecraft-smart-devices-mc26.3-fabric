#!/usr/bin/env node
/**
 * Electron 浏览器 SDK（Node.js 运行时）
 *
 * 由 Java 客户端启动：node browser.mjs，标准输入输出走一行一条 JSON 的协议。
 * 这里负责真正的浏览器后端：HTTP(S) 抓取、Cookie、缓存、HTML/CSS 解析、
 * 文字与块的排版、表单、图片下载、页内查找与阅读模式；Java 只负责把返回的
 * 显示列表画到虚拟机屏幕上，所以离线也能完整工作。
 *
 * 请求：{id, op, ...}
 *  - hello      {font:{codepoint:width}}        注册字体度量（一次）
 *  - navigate   {session,url,width,zoom,reader,html,base,cookies}
 *  - submit     {session,pageUrl,action,method,values,enctype,cookies}
 *  - find       {session,query}
 *  - cookies    {session}
 *  - setCookies {session,data}
 *  - quit
 * 响应：{id, ok, ...}，错误为 {id, ok:false, error}
 */

import readline from 'node:readline';

const ENGINE = 'electron-sdk/1.0 (node ' + process.version + ')';
const USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
    + '(KHTML, like Gecko) Chrome/126.0 Safari/537.36 ElectronSdk/1.0';
const MAX_HTML = 3 * 1024 * 1024;
const FETCH_TIMEOUT = 15000;
const IMAGE_TIMEOUT = 8000;
const MAX_ITEMS = 6000;
const MAX_HEIGHT = 60000;
const MAX_IMAGES = 14;

let FONT = {};
let FONT_DEFAULT = 6;
let FONT_CJK = 9;
const sessions = new Map();
const pageCache = new Map();
/** 图片按绝对地址缓存，重排与缩放时不用重新下载。 */
const imageCache = new Map();

// ------------------------------------------------------------------ 基础工具

function session(name) {
    const key = name || 'default';
    let s = sessions.get(key);
    if (!s) {
        s = {id: key, cookies: new Map(), last: null, history: []};
        sessions.set(key, s);
    }
    return s;
}

function cjk(cp) {
    return (cp >= 0x2E80 && cp <= 0x9FFF) || (cp >= 0xAC00 && cp <= 0xD7AF)
        || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0xFF00 && cp <= 0xFFEF);
}

function charWidth(ch) {
    const cp = ch.codePointAt(0);
    if (cp === 9) return 16;
    if (cp === 0xA0) return 3;
    const w = FONT[cp];
    if (w != null) return w;
    return cjk(cp) ? FONT_CJK : FONT_DEFAULT;
}

function measure(text) {
    let w = 0;
    if (!text) return 0;
    for (const ch of String(text)) w += charWidth(ch);
    return w;
}

function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
}

function escapeHtml(text) {
    return String(text == null ? '' : text)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function decodeEntities(text) {
    if (!text || text.indexOf('&') < 0) return text;
    const named = {amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: '\u00A0',
        copy: '\u00A9', reg: '\u00AE', hellip: '\u2026', mdash: '\u2014', ndash: '\u2013',
        laquo: '\u00AB', raquo: '\u00BB', middot: '\u00B7', times: '\u00D7', divide: '\u00F7',
        deg: '\u00B0', plusmn: '\u00B1', bull: '\u2022', dagger: '\u2020', euro: '\u20AC',
        pound: '\u00A3', yen: '\u00A5', cent: '\u00A2', sect: '\u00A7', para: '\u00B6'};
    return text.replace(/&(#x?[0-9a-fA-F]+|[a-zA-Z]+);/g, (all, body) => {
        if (body[0] === '#') {
            const hex = body[1] === 'x' || body[1] === 'X';
            const code = parseInt(body.slice(hex ? 2 : 1), hex ? 16 : 10);
            return Number.isFinite(code) && code > 0 && code < 0x110000
                ? String.fromCodePoint(code) : all;
        }
        const value = named[body.toLowerCase()];
        return value == null ? all : value;
    });
}

function normalizeUrl(raw, base) {
    if (!raw) return '';
    const value = String(raw).trim();
    if (!value) return '';
    try {
        return new URL(value, base || undefined).href;
    } catch (ignored) {
        return value;
    }
}

function hostOf(url) {
    try {
        return new URL(url).host;
    } catch (ignored) {
        return url || '';
    }
}

function stripHtml(text) {
    return String(text == null ? '' : text)
        .replace(/<script[\s\S]*?<\/script>/gi, ' ')
        .replace(/<style[\s\S]*?<\/style>/gi, ' ')
        .replace(/<[^>]+>/g, ' ')
        .replace(/&nbsp;/gi, ' ')
        .replace(/\s+/g, ' ')
        .trim();
}

// ------------------------------------------------------------------ 颜色与 CSS

const NAMED_COLORS = {
    white: '#FFFFFF', black: '#000000', red: '#FF0000', green: '#008000', blue: '#0000FF',
    yellow: '#FFFF00', gray: '#808080', grey: '#808080', silver: '#C0C0C0', maroon: '#800000',
    purple: '#800080', fuchsia: '#FF00FF', lime: '#00FF00', olive: '#808000', navy: '#000080',
    teal: '#008080', aqua: '#00FFFF', orange: '#FFA500', pink: '#FFC0CB', brown: '#A52A2A',
    gold: '#FFD700', transparent: 'transparent', whitesmoke: '#F5F5F5', lightgray: '#D3D3D3',
    lightgrey: '#D3D3D3', darkgray: '#A9A9A9', darkgrey: '#A9A9A9', dimgray: '#696969',
    lightblue: '#ADD8E6', lightgreen: '#90EE90', darkblue: '#00008B', darkred: '#8B0000',
    darkgreen: '#006400', skyblue: '#87CEEB', dodgerblue: '#1E90FF', midnightblue: '#191970',
    steelblue: '#4682B4', royalblue: '#4169E1', cornflowerblue: '#6495ED', beige: '#F5F5DC',
    ivory: '#FFFFF0', lavender: '#E6E6FA', lightyellow: '#FFFFE0', lightcyan: '#E0FFFF'
};

function parseColor(raw) {
    if (!raw) return null;
    let value = String(raw).trim().toLowerCase();
    if (!value || value === 'inherit' || value === 'initial') return null;
    if (value === 'transparent') return 'transparent';
    if (NAMED_COLORS[value]) value = NAMED_COLORS[value];
    if (value[0] === '#') {
        let hex = value.slice(1);
        if (hex.length === 3 || hex.length === 4) {
            hex = hex.split('').map(c => c + c).join('');
        }
        if (hex.length === 6) hex = 'FF' + hex;
        if (hex.length === 8) return '#' + hex.toUpperCase();
        return null;
    }
    const rgb = value.match(/^rgba?\(\s*([\d.]+%?)[\s,]+([\d.]+%?)[\s,]+([\d.]+%?)(?:[\s,/]+([\d.]+%?))?\s*\)$/);
    if (rgb) {
        const part = (s, max) => {
            if (s == null) return max;
            if (s.endsWith('%')) return Math.round(parseFloat(s) * max / 100);
            return Math.round(parseFloat(s));
        };
        const a = part(rgb[4], 1);
        const r = clamp(part(rgb[1], 255), 0, 255);
        const g = clamp(part(rgb[2], 255), 0, 255);
        const b = clamp(part(rgb[3], 255), 0, 255);
        return '#' + [a, r, g, b].map(v => v.toString(16).padStart(2, '0')).join('').toUpperCase();
    }
    return null;
}

function parsePx(raw, fallback) {
    if (raw == null) return fallback;
    const text = String(raw).trim().toLowerCase();
    if (text === 'auto' || text === '') return fallback;
    const match = text.match(/^(-?[\d.]+)(px|pt|em|rem|%|vh|vw)?$/);
    if (!match) return fallback;
    const value = parseFloat(match[1]);
    if (!Number.isFinite(value)) return fallback;
    const unit = match[2] || 'px';
    if (unit === 'pt') return value * 96 / 72;
    if (unit === 'em' || unit === 'rem') return value * 12;
    if (unit === '%') return fallback == null ? value : fallback * value / 100;
    if (unit === 'vh') return value * 6;
    if (unit === 'vw') return value * 3.2;
    return value;
}

const CSS_PROPS = new Set(['color', 'background', 'background-color', 'font-weight', 'font-style',
    'text-decoration', 'text-align', 'margin', 'margin-top', 'margin-bottom', 'margin-left',
    'margin-right', 'padding', 'padding-top', 'padding-bottom', 'padding-left', 'padding-right',
    'border', 'border-top', 'border-bottom', 'border-left', 'border-right', 'border-color',
    'display', 'width', 'max-width', 'height', 'line-height', 'white-space', 'list-style',
    'list-style-type', 'text-transform', 'font-size', 'opacity', 'font-family']);

function parseDeclarations(text) {
    const props = {};
    if (!text) return props;
    for (const piece of String(text).split(';')) {
        const colon = piece.indexOf(':');
        if (colon <= 0) continue;
        const name = piece.slice(0, colon).trim().toLowerCase();
        const value = piece.slice(colon + 1).replace(/!important/i, '').trim();
        if (!name || !value) continue;
        if (CSS_PROPS.has(name)) props[name] = value;
    }
    // margin/padding 简写
    for (const base of ['margin', 'padding']) {
        if (props[base] == null) continue;
        const parts = props[base].split(/\s+/);
        const pick = [parts[0], parts[1] ?? parts[0], parts[2] ?? parts[0], parts[3] ?? parts[1] ?? parts[0]];
        props[base + '-top'] = pick[0];
        props[base + '-right'] = pick[1];
        props[base + '-bottom'] = pick[2];
        props[base + '-left'] = pick[3];
        delete props[base];
    }
    if (props['border'] != null) {
        const parts = props['border'].split(/\s+/);
        const color = parts.find(p => parseColor(p)) || '#999999';
        props['border-color'] = color;
        props['border-width'] = parts.find(p => /^[\d.]+px$/.test(p)) || '1px';
        delete props['border'];
    }
    if (props['font-size'] != null && !props['size']) {
        const px = parsePx(props['font-size'], 9);
        props['_size'] = clamp(px / 9, 0.8, 2.2);
    }
    if (props['background-color'] != null && props['background'] == null) {
        props['background'] = props['background-color'];
    }
    return props;
}

function parseCssRules(css) {
    const rules = [];
    if (!css) return rules;
    const cleaned = String(css).replace(/\/\*[\s\S]*?\*\//g, '');
    const re = /([^{}]+)\{([^{}]*)\}/g;
    let match;
    while ((match = re.exec(cleaned))) {
        const selectors = match[1].split(',').map(s => s.trim()).filter(Boolean);
        const props = parseDeclarations(match[2]);
        for (const selector of selectors) {
            const simple = parseSelector(selector);
            if (simple) rules.push({selector: simple, props, order: rules.length});
        }
    }
    return rules;
}

function parseSelector(selector) {
    const value = selector.trim();
    if (!value || /[\s>+~]/.test(value)) return null; // 不支持后代与兄弟选择器
    const out = {tag: null, id: null, classes: [], order: 0};
    const id = value.match(/#([\w-]+)/);
    if (id) out.id = id[1];
    const classes = [...value.matchAll(/\.([\w-]+)/g)].map(m => m[1]);
    out.classes = classes;
    const tag = value.match(/^([a-zA-Z][\w-]*)/);
    if (tag) out.tag = tag[1].toLowerCase();
    if (!tag && !id && !classes.length && value !== '*') return null;
    return out;
}

function selectorMatches(node, selector) {
    if (!node || !node.tag) return false;
    if (selector.tag && node.tag !== selector.tag) return false;
    if (selector.id && node.attrs.id !== selector.id) return false;
    if (selector.classes.length) {
        const has = (node.attrs.class || '').split(/\s+/);
        for (const c of selector.classes) if (!has.includes(c)) return false;
    }
    return true;
}

// ------------------------------------------------------------------ HTML 解析

const VOID_TAGS = new Set(['br', 'hr', 'img', 'input', 'meta', 'link', 'area', 'base', 'col',
    'embed', 'param', 'source', 'track', 'wbr']);
const BLOCK_TAGS = new Set(['html', 'body', 'div', 'p', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
    'ul', 'ol', 'li', 'header', 'footer', 'nav', 'main', 'section', 'article', 'aside',
    'blockquote', 'pre', 'table', 'thead', 'tbody', 'tfoot', 'tr', 'td', 'th', 'form',
    'fieldset', 'figure', 'figcaption', 'address', 'hr', 'dl', 'dt', 'dd']);
const INLINE_TAGS = new Set(['a', 'span', 'b', 'strong', 'i', 'em', 'u', 'code', 'small',
    'label', 'sub', 'sup', 'abbr', 'cite', 'mark', 'font', 'big', 'tt', 'kbd', 'samp', 'var',
    'time', 'bdi', 'bdo', 'q', 's', 'strike', 'del', 'ins', 'img', 'input', 'button',
    'select', 'textarea', 'br', 'wbr', 'nobr', 'canvas', 'svg', 'video', 'audio', 'iframe']);

function makeNode(tag, attrs) {
    return {tag, attrs: attrs || {}, kids: []};
}

function parseHtml(html, baseUrl) {
    const root = makeNode('#document', {});
    const stack = [root];
    const cssText = [];
    let i = 0;
    const length = html.length;
    const body = () => stack[stack.length - 1];

    while (i < length) {
        const lt = html.indexOf('<', i);
        if (lt < 0) {
            appendText(html.slice(i));
            break;
        }
        if (lt > i) appendText(html.slice(i, lt));
        if (html.startsWith('<!--', lt)) {
            const end = html.indexOf('-->', lt + 4);
            i = end < 0 ? length : end + 3;
            continue;
        }
        if (html.startsWith('<!', lt) || html.startsWith('<?', lt)) {
            const end = html.indexOf('>', lt);
            i = end < 0 ? length : end + 1;
            continue;
        }
        const close = html.indexOf('>', lt);
        if (close < 0) {
            appendText(html.slice(lt));
            break;
        }
        const raw = html.slice(lt + 1, close);
        i = close + 1;
        if (raw[0] === '/') {
            const name = raw.slice(1).trim().toLowerCase().split(/\s+/)[0];
            for (let s = stack.length - 1; s > 0; s--) {
                if (stack[s].tag === name) {
                    stack.length = s;
                    break;
                }
            }
            continue;
        }
        const selfClose = /\/\s*$/.test(raw);
        const nameMatch = raw.match(/^([a-zA-Z][\w:-]*)/);
        if (!nameMatch) continue;
        const tag = nameMatch[1].toLowerCase();
        const attrs = parseAttributes(raw.slice(nameMatch[0].length));
        if (tag === 'style') {
            const end = html.toLowerCase().indexOf('</style', i);
            if (end >= 0) {
                cssText.push(html.slice(i, end));
                i = html.indexOf('>', end) + 1;
            }
            continue;
        }
        if (tag === 'script' || tag === 'title' || tag === 'textarea' || tag === 'noscript') {
            const end = html.toLowerCase().indexOf('</' + tag, i);
            const text = end < 0 ? html.slice(i) : html.slice(i, end);
            i = end < 0 ? length : html.indexOf('>', end) + 1;
            if (tag === 'title') {
                root.title = decodeEntities(text.trim().replace(/\s+/g, ' '));
            } else if (tag === 'textarea') {
                const node = makeNode('textarea', attrs);
                node.textValue = decodeEntities(text);
                body().kids.push(node);
                if (!selfClose) stack.push(node);
            }
            continue;
        }
        const node = makeNode(tag, attrs);
        body().kids.push(node);
        if (!selfClose && !VOID_TAGS.has(tag)) stack.push(node);
        if (html.startsWith('<', i)) continue;
    }

    function appendText(text) {
        if (!text) return;
        const decoded = decodeEntities(text);
        if (!decoded) return;
        const parent = body();
        const last = parent.kids[parent.kids.length - 1];
        if (typeof last === 'string') parent.kids[parent.kids.length - 1] = last + decoded;
        else parent.kids.push(decoded);
    }

    root.css = parseCssRules(cssText.join('\n'));
    root.baseUrl = baseUrl;
    return root;
}

function parseAttributes(text) {
    const attrs = {};
    const re = /([^\s=/>]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g;
    let match;
    while ((match = re.exec(text))) {
        const name = match[1].toLowerCase();
        attrs[name] = decodeEntities(match[2] ?? match[3] ?? match[4] ?? '');
    }
    return attrs;
}

function findFirst(node, tag) {
    if (!node) return null;
    if (node.tag === tag) return node;
    for (const kid of node.kids || []) {
        if (typeof kid === 'string') continue;
        const hit = findFirst(kid, tag);
        if (hit) return hit;
    }
    return null;
}

function collect(node, tag, out) {
    if (!node) return out;
    if (node.tag === tag) out.push(node);
    for (const kid of node.kids || []) {
        if (typeof kid === 'string') continue;
        collect(kid, tag, out);
    }
    return out;
}

// ------------------------------------------------------------------ 样式解析

const DEFAULT_DISPLAY = {
    div: 'block', p: 'block', h1: 'block', h2: 'block', h3: 'block', h4: 'block', h5: 'block',
    h6: 'block', ul: 'block', ol: 'block', li: 'block', table: 'block', tr: 'block',
    td: 'inline', th: 'inline', form: 'block', header: 'block', footer: 'block',
    nav: 'block', main: 'block', section: 'block', article: 'block', aside: 'block',
    blockquote: 'block', pre: 'block', figure: 'block', figcaption: 'block', hr: 'block',
    address: 'block', dl: 'block', dt: 'block', dd: 'block', fieldset: 'block'
};

const HEADING_SIZE = {h1: 1.7, h2: 1.45, h3: 1.25, h4: 1.12, h5: 1.05, h6: 1};

function baseStyle() {
    return {color: '#222222', bg: 'transparent', size: 1, bold: false, italic: false,
        underline: false, align: 'left', display: 'inline', mt: 0, mb: 0, ml: 0, mr: 0,
        pt: 0, pb: 0, pl: 0, pr: 0, borderColor: null, borderTop: false, borderBottom: false,
        width: null, maxWidth: null, whiteSpace: 'normal', pre: false, list: null,
        opacity: 1, fixed: false};
}

function bodyStyle() {
    const s = baseStyle();
    s.display = 'block';
    return s;
}

function resolveStyle(node, parent, root) {
    const st = Object.assign({}, parent);
    const tag = node.tag;
    if (tag === 'body' || tag === 'html' || tag === '#document') st.display = 'block';
    const defaultDisplay = DEFAULT_DISPLAY[tag];
    if (defaultDisplay) st.display = defaultDisplay;
    if (HEADING_SIZE[tag]) {
        st.size = HEADING_SIZE[tag];
    }
    if (tag === 'b' || tag === 'strong' || tag === 'th') st.bold = true;
    if (tag === 'i' || tag === 'em' || tag === 'cite' || tag === 'var') st.italic = true;
    if (tag === 'u' || tag === 'ins') st.underline = true;
    if (tag === 'a') {
        st.color = '#1A4FA0';
        st.underline = true;
    }
    if (tag === 'pre' || tag === 'code') {
        st.pre = tag === 'pre';
        st.whiteSpace = tag === 'pre' ? 'pre' : 'normal';
        st.color = '#2A2A2A';
    }
    if (tag === 'small') st.size = Math.max(0.8, st.size * 0.85);
    if (tag === 'blockquote') {
        st.ml += 8;
        st.borderColor = '#C8C4B8';
        st.borderLeft = true;
        pl: st.pl += 4;
    }
    if (tag === 'ul' || tag === 'ol') {
        st.ml += 10;
    }
    if (tag === 'li') {
        st.list = 'item';
    }
    if (tag === 'hr') {
        st.mt += 4;
        st.mb += 4;
    }
    if (tag === 'p') {
        st.mt += 3;
        st.mb += 3;
    }
    if (HEADING_SIZE[tag]) {
        st.mt += tag === 'h1' ? 6 : 4;
        st.mb += 3;
        st.bold = true;
    }

    // 内联 style
    applyProps(st, parseDeclarations(node.attrs.style || ''));
    // CSS 规则
    if (root && root.css && root.css.length) {
        const matched = [];
        for (const rule of root.css) {
            if (selectorMatches(node, rule.selector)) {
                const specificity = (rule.selector.id ? 100 : 0)
                    + rule.selector.classes.length * 10
                    + (rule.selector.tag ? 1 : 0);
                matched.push({rule, specificity});
            }
        }
        matched.sort((a, b) => a.specificity - b.specificity || a.rule.order - b.rule.order);
        for (const {rule} of matched) applyProps(st, rule.props);
    }
    // 按钮与输入框
    if (tag === 'input' || tag === 'button' || tag === 'select' || tag === 'textarea') {
        st.display = 'inline';
        st.color = '#1F1F1F';
        st.bg = st.bg === 'transparent' ? '#F4F3EC' : st.bg;
        st.borderColor = st.borderColor || '#8C8878';
        st.borderTop = st.borderBottom = true;
        st.pt += 1;
        st.pb += 1;
        st.pl += 3;
        st.pr += 3;
    }
    if (tag === 'img' || tag === 'video' || tag === 'iframe' || tag === 'canvas' || tag === 'svg') {
        st.display = 'block';
        st.mt += 2;
        st.mb += 2;
    }
    return st;
}

function applyProps(st, props) {
    if (!props) return;
    const color = props.color && parseColor(props.color);
    if (color && color !== 'transparent') st.color = color;
    const bg = props.background && parseColor(props.background);
    if (bg) st.bg = bg;
    if (props['font-weight']) {
        const v = props['font-weight'];
        st.bold = v === 'bold' || v === 'bolder' || parseInt(v, 10) >= 600;
    }
    if (props['font-style']) st.italic = props['font-style'] === 'italic';
    if (props['text-decoration']) {
        st.underline = /underline/.test(props['text-decoration']);
    }
    if (props['text-align'] && /^(left|right|center|justify)$/.test(props['text-align'])) {
        st.align = props['text-align'] === 'justify' ? 'left' : props['text-align'];
    }
    if (props['display']) {
        const d = props['display'];
        if (d === 'none') st.display = 'none';
        else if (d === 'block' || d === 'flex' || d === 'grid' || d === 'list-item') st.display = 'block';
        else if (d === 'inline-block') st.display = 'inline';
        else if (d === 'inline') st.display = 'inline';
    }
    for (const side of ['top', 'bottom', 'left', 'right']) {
        const m = parsePx(props['margin-' + side], null);
        if (m != null && Number.isFinite(m)) st['m' + side[0]] = m;
        const p = parsePx(props['padding-' + side], null);
        if (p != null && Number.isFinite(p)) st['p' + side[0]] = p;
    }
    if (props.width != null) {
        const v = parsePx(props.width, null);
        if (v != null) st.width = v;
        else if (/%$/.test(props.width)) st.widthPct = parseFloat(props.width) / 100;
    }
    if (props['max-width'] != null) {
        const v = parsePx(props['max-width'], null);
        if (v != null) st.maxWidth = v;
    }
    if (props['white-space']) st.whiteSpace = props['white-space'];
    if (props['border-color']) {
        const bc = parseColor(props['border-color']);
        if (bc) st.borderColor = bc;
    }
    if (props['border-width'] != null && st.borderColor == null) st.borderColor = '#A8A496';
    if (props['border-top'] || props['border-bottom'] || props['border-left'] || props['border-right']) {
        st.borderColor = st.borderColor || '#A8A496';
    }
    if (props['border-top'] || props['border-width']) st.borderTop = true;
    if (props['border-bottom'] || props['border-width']) st.borderBottom = true;
    if (props['border-left']) st.borderLeft = true;
    if (props['_size'] != null) st.size = Math.max(st.size, props['_size']);
    if (props['line-height'] != null) {
        const v = parsePx(props['line-height'], null);
        if (v != null) st.lineHeight = v;
        else if (/^[\d.]+$/.test(props['line-height'])) st.lineHeight = parseFloat(props['line-height']) * 11;
    }
    if (props['text-transform']) st.transform = props['text-transform'];
    if (props['list-style-type']) st.listStyle = props['list-style-type'];
    if (props.opacity != null) st.opacity = clamp(parseFloat(props.opacity) || 1, 0.2, 1);
}

// ------------------------------------------------------------------ 排版

class Layout {
    constructor(width, zoom, base) {
        this.width = width;
        this.zoom = zoom || 1;
        this.base = base || '';
        this.items = [];
        this.images = [];
        this.height = 0;
        this.formSeq = 0;
        this.currentForm = null;
        this.listStack = [];
        this.reader = false;
    }

    add(item) {
        if (this.items.length >= MAX_ITEMS) return;
        item.zy = item.y;
        this.items.push(item);
    }

    color(value) {
        if (!value || value === 'transparent') return 'transparent';
        return value;
    }

    lineHeight(size) {
        return Math.max(11, Math.round(11 * (size || 1)));
    }

    async imageFor(node, style) {
        let src = node.attrs.src || node.attrs['data-src'] || node.attrs['data-original'] || '';
        if (!src) return null;
        src = normalizeUrl(src, this.base);
        let w = parsePx(node.attrs.width, null);
        let h = parsePx(node.attrs.height, null);
        if (style.width != null) w = style.width;
        if (node.attrs.style && /width\s*:/.test(node.attrs.style)) {
            const m = node.attrs.style.match(/width\s*:\s*([\d.]+px)/);
            if (m) w = parsePx(m[1], w);
        }
        w = w == null ? 120 : clamp(w, 8, this.width);
        h = h == null ? Math.round(w * 0.75) : clamp(h, 8, 240);
        const entry = {src, w, h, data: null, mime: ''};
        this.images.push(entry);
        return entry;
    }
}

function isInlineNode(node, style) {
    if (typeof node === 'string') return true;
    if (!node || !node.tag) return true;
    return style.display === 'inline' || INLINE_TAGS.has(node.tag);
}

function layoutDocument(root, width, zoom, reader) {
    const lay = new Layout(width, zoom, root.baseUrl);
    lay.reader = reader;
    let body = findFirst(root, 'body') || root;
    if (reader) {
        const article = findFirst(root, 'article') || findFirst(root, 'main') || body;
        body = sanitizeReader(article);
    }
    const pageStyle = bodyStyle();
    const bodyStyleResolved = resolveStyle(body, pageStyle, root);
    bodyStyleResolved.display = 'block';

    const end = litLayoutChildren(body, lay, body.kids, 0, 0, width, bodyStyleResolved, root, true);
    lay.height = Math.max(0, end);
    return lay;
}

// 兼容占位：真实逻辑在下面通过闭包函数
let activeRoot = null;

function litLayoutChildren(node, lay, kids, x, y, width, style, root, top) {
    activeRoot = root;
    let cy = y;
    const buf = [];
    const flush = () => {
        if (!buf.length) return;
        cy = layoutInline(buf.splice(0), lay, x, cy, width, style, root);
    };
    for (const kid of kids) {
        if (typeof kid === 'string') {
            buf.push(kid);
            continue;
        }
        const kidStyle = resolveStyle(kid, style, root);
        if (kidStyle.display === 'none') continue;
        if (isInlineNode(kid, kidStyle) && kid.tag !== 'textarea') {
            buf.push(kid);
        } else {
            flush();
            cy = layoutBlockNode(kid, kidStyle, lay, x, cy, width, style, root);
        }
        if (cy > MAX_HEIGHT) break;
    }
    flush();
    return cy;
}

function layoutBlockNode(node, style, lay, x, y, width, parentStyle, root) {
    const tag = node.tag;
    if (tag === 'hr') {
        lay.add({t: 'rect', x, y: y + 3, w: width, h: 1, c: '#C8C4B8'});
        return y + 8;
    }
    if (tag === 'img' || tag === 'video' || tag === 'iframe' || tag === 'canvas' || tag === 'svg') {
        return layoutMedia(node, style, lay, x, y, width);
    }
    if (tag === 'table') {
        return layoutTable(node, style, lay, x, y, width, root);
    }
    if (tag === 'input' || tag === 'button' || tag === 'select' || tag === 'textarea') {
        return layoutField(node, style, lay, x, y, width, parentStyle, root, true);
    }

    let mx = x + style.ml;
    let my = y + style.mt;
    let mw = Math.max(12, width - style.ml - style.mr);
    if (style.width != null) mw = Math.min(mw, style.width);
    if (style.maxWidth != null) mw = Math.min(mw, style.maxWidth);

    const padX = style.pl + style.pr;
    const innerX = mx + style.pl;
    const innerY = my + style.pt;
    const innerW = Math.max(8, mw - padX);

    const itemIndex = lay.items.length;
    let endY;
    if (tag === 'pre') {
        endY = layoutPre(node, style, lay, innerX, innerY, innerW);
    } else {
        endY = litLayoutChildren(node, lay, node.kids, innerX, innerY, innerW, style, root, false);
    }
    endY += style.pb;
    const boxH = Math.max(0, endY - my);
    if (style.bg && style.bg !== 'transparent') {
        lay.items.splice(itemIndex, 0, {t: 'rect', x: mx, y: my, w: mw, h: boxH, c: style.bg});
    }
    if (style.borderTop) {
        lay.items.splice(itemIndex, 0, {t: 'rect', x: mx, y: my, w: mw, h: 1, c: style.borderColor || '#A8A496'});
    }
    if (style.borderBottom) {
        lay.add({t: 'rect', x: mx, y: endY, w: mw, h: 1, c: style.borderColor || '#A8A496'});
    }
    if (style.borderLeft) {
        lay.items.splice(itemIndex, 0, {t: 'rect', x: mx, y: my, w: 1, h: boxH, c: style.borderColor || '#A8A496'});
    }
    if (tag === 'li' && style.list === 'item') {
        // 列表符号在文本前已经作为 inline 文本输出，这里只补左缩进。
    }
    return endY + style.mb;
}

function layoutMedia(node, style, lay, x, y, width) {
    const tag = node.tag;
    let w = style.width != null ? style.width : parsePx(node.attrs.width, null);
    w = w == null ? Math.min(width, 160) : clamp(w, 8, width);
    let h = parsePx(node.attrs.height, null);
    h = h == null ? Math.round(w * 0.75) : clamp(h, 8, 260);
    if (tag === 'video' || tag === 'iframe') {
        h = parsePx(node.attrs.height, Math.round(w * 0.56));
    }
    if (tag === 'img') {
        const entry = layoutImageEntry(node, lay, w, h);
        lay.add({t: 'image', x: x + style.ml, y: y + style.mt, w, h, src: entry ? entry.src : '', alt: node.attrs.alt || ''});
    } else {
        const label = tag === 'video' ? '视频' : tag === 'iframe' ? '内嵌页面' : 'SVG 图形';
        lay.add({t: 'rect', x: x + style.ml, y: y + style.mt, w, h, c: '#101418'});
        lay.add({t: 'text', x: x + style.ml + 4, y: y + style.mt + 4, s: label + '（离线不播放）', c: '#B8D8F8', z: 1});
        if (node.attrs.src) {
            lay.add({t: 'text', x: x + style.ml + 4, y: y + style.mt + 15, s: truncate(node.attrs.src, width - 8), c: '#7FA8C8', z: 1});
        }
    }
    return y + style.mt + h + style.mb + 3;
}

function layoutImageEntry(node, lay, w, h) {
    let src = node.attrs.src || '';
    if (!src) return null;
    src = normalizeUrl(src, lay.base);
    if (lay.images.length >= MAX_IMAGES) return {src, w, h};
    const entry = {src, w, h, data: null, mime: ''};
    lay.images.push(entry);
    return entry;
}

function layoutPre(node, style, lay, x, y, width) {
    const text = flattenText(node);
    const lines = text.split('\n');
    const index = lay.items.length;
    let cy = y;
    for (const line of lines) {
        lay.add({t: 'text', x, y: cy, s: line, c: style.color, z: style.size});
        cy += 11;
    }
    lay.items.splice(index, 0,
        {t: 'rect', x: x - 4, y: y - 2, w: width + 8, h: Math.max(11, cy - y + 4), c: '#F0EEE4'});
    return cy + 2;
}

function layoutTable(node, style, lay, x, y, width, root) {
    const rows = collect(node, 'tr', []);
    if (!rows.length) {
        return litLayoutChildren(node, lay, node.kids, x, y, width, style, root, false);
    }
    let cols = 1;
    for (const row of rows) cols = Math.max(cols, row.kids.filter(k => typeof k !== 'string' && (k.tag === 'td' || k.tag === 'th')).length);
    cols = Math.max(1, Math.min(cols, 6));
    const colW = Math.floor(width / cols);
    let cy = y + 2;
    for (const row of rows) {
        const cells = row.kids.filter(k => typeof k !== 'string' && (k.tag === 'td' || k.tag === 'th'));
        const rowY = cy;
        let rowH = 12;
        cells.forEach((cell, index) => {
            const cellStyle = resolveStyle(cell, style, root);
            cellStyle.mt = 0;
            cellStyle.mb = 0;
            const cx = x + index * colW;
            const end = litLayoutChildren(cell, lay, cell.kids, cx + 3, rowY + 2, colW - 6, cellStyle, root, false);
            rowH = Math.max(rowH, end - rowY + 2);
            lay.items.push({t: 'rect', x: cx, y: rowY, w: 1, h: rowH, c: '#C8C4B8'});
        });
        lay.items.push({t: 'rect', x, y: rowY + rowH, w: colW * cols, h: 1, c: '#C8C4B8'});
        cy = rowY + rowH + 1;
    }
    return cy + style.mb + 2;
}

// ------------------------------------------------------------------ 行内排版

function flattenText(node) {
    let out = '';
    for (const kid of node.kids || []) {
        if (typeof kid === 'string') out += kid;
        else if (kid.tag === 'br') out += '\n';
        else if (kid.tag === 'img') out += ' ';
        else out += flattenText(kid);
    }
    return out;
}

function styleForText(node, parent, root) {
    return resolveStyle(node, parent, root);
}

function layoutInline(nodes, lay, x, y, width, parentStyle, root) {
    const atoms = [];
    collectInline(nodes, parentStyle, root, lay, atoms, null);
    return flowAtoms(atoms, lay, x, y, width, parentStyle.align);
}

function collectInline(nodes, parentStyle, root, lay, out, href) {
    for (const node of nodes) {
        if (typeof node === 'string') {
            out.push({kind: 'text', text: node, style: parentStyle, href});
            continue;
        }
        if (!node.tag) continue;
        const style = styleForText(node, parentStyle, root);
        if (style.display === 'none') continue;
        switch (node.tag) {
            case 'br':
                out.push({kind: 'br'});
                break;
            case 'img':
            case 'video':
            case 'iframe':
            case 'canvas':
            case 'svg':
                out.push({kind: 'break'});
                out.push({kind: 'media', node, style});
                out.push({kind: 'break'});
                break;
            case 'input':
            case 'button':
            case 'select':
            case 'textarea':
                out.push({kind: 'field', node, style});
                break;
            case 'a': {
                const target = normalizeUrl(node.attrs.href || '', lay.base);
                const nextHref = target || href;
                collectInline(node.kids, style, root, lay, out, nextHref);
                break;
            }
            default:
                collectInline(node.kids, style, root, lay, out, href);
                break;
        }
    }
}

function flowAtoms(atoms, lay, x, y, width, align) {
    let cy = y;
    let lineX = x;
    let lineItems = [];
    let lineH = 11;
    let lineStart = 0;
    const flush = (alignRight) => {
        if (!lineItems.length) {
            cy += lineH;
            return;
        }
        const used = lineX - x;
        let offset = 0;
        if (align === 'center') offset = Math.max(0, Math.floor((width - used) / 2));
        else if (align === 'right') offset = Math.max(0, width - used);
        for (const item of lineItems) {
            item.x += offset;
            if (item.href) {
                const hit = {t: 'link', x: item.x, y: item.y - 1, w: item.w, h: item.h + 1, href: item.href};
                lay.add(hit);
            }
            lay.add(item.output);
        }
        cy += lineH;
        lineItems = [];
        lineX = x;
        lineH = 11;
    };

    for (const atom of atoms) {
        if (atom.kind === 'br') {
            flush();
            continue;
        }
        if (atom.kind === 'break') {
            flush();
            continue;
        }
        if (atom.kind === 'media') {
            flush();
            cy = layoutMedia(atom.node, atom.style, lay, x, cy, width);
            continue;
        }
        if (atom.kind === 'field') {
            flush();
            const end = layoutField(atom.node, atom.style, lay, x, cy, width, atom.style, lay.root || null, true);
            cy = end;
            continue;
        }
        if (atom.kind !== 'text') continue;
        const style = atom.style;
        let text = atom.text.replace(/\r/g, '');
        if (style.transform === 'uppercase') text = text.toUpperCase();
        if (style.transform === 'lowercase') text = text.toLowerCase();
        const pre = style.whiteSpace === 'pre' || style.whiteSpace === 'pre-wrap';
        if (!pre) text = text.replace(/\s+/g, ' ');
        if (!text) continue;
        if (!pre && text.trim() === '' && lineX === x) continue;
        const size = style.size || 1;
        const lh = Math.max(11, Math.round(11 * size));
        lineH = Math.max(lineH, lh);

        if (pre) {
            const parts = text.split('\n');
            parts.forEach((part, index) => {
                if (index > 0) flush();
                if (part) addRun(part);
            });
        } else {
            const words = splitWords(text);
            for (const word of words) {
                const wordW = measure(word) * size;
                if (lineX > x && lineX + wordW > x + width) flush();
                addRun(word);
            }
        }

        function addRun(run) {
            if (!run) return;
            const w = measure(run) * size;
            if (lineX > x && lineX + w > x + width + 1) flush();
            const item = {t: 'text', x: Math.round(lineX), y: Math.round(cy), s: run, c: style.color, z: size};
            if (style.bold) item.b = 1;
            if (style.underline) {
                lay.add({t: 'rect', x: Math.round(lineX), y: Math.round(cy + lh - 2), w: Math.round(w), h: 1, c: style.color});
            }
            lineItems.push({x: Math.round(lineX), y: Math.round(cy), w: Math.ceil(w), h: lh, href: atom.href, output: item});
            lineX += w;
        }
    }
    flush();
    return cy + 2;
}

function splitWords(text) {
    const out = [];
    let buf = '';
    for (const ch of text) {
        if (/\s/.test(ch)) {
            buf += ch;
            out.push(buf);
            buf = '';
        } else if (cjk(ch.codePointAt(0))) {
            if (buf) out.push(buf);
            buf = '';
            out.push(ch);
        } else {
            buf += ch;
        }
    }
    if (buf) out.push(buf);
    return out;
}

function truncate(text, width) {
    if (measure(text) <= width) return text;
    let out = '';
    for (const ch of text) {
        if (measure(out + ch) > width - 7) break;
        out += ch;
    }
    return out + '…';
}

// ------------------------------------------------------------------ 表单控件

let formCounter = 0;

function findForm(node) {
    let cur = node;
    while (cur) {
        if (cur.tag === 'form') return cur;
        cur = cur.parent;
    }
    return null;
}

function fieldBox(node, style, lay, x, y, width, blockLayout) {
    const tag = node.tag;
    const type = (node.attrs.type || (tag === 'button' ? 'submit' : 'text')).toLowerCase();
    const form = node.formNode || null;
    const formId = form ? (form.attrs.id || form.attrs.name || form._id) : '';
    const action = form ? normalizeUrl(form.attrs.action || lay.base, lay.base) : '';
    const method = form ? (form.attrs.method || 'get').toLowerCase() : 'get';
    const name = node.attrs.name || '';
    if (type === 'hidden') return null;
    if (type === 'checkbox' || type === 'radio') {
        const label = name || node.attrs.value || '';
        const w = Math.min(width, 12 + measure(label) + 6);
        return {t: 'check', x, y, w, h: 12, name, value: node.attrs.value || 'on',
            checked: node.attrs.checked != null, form: formId};
    }
    if (type === 'submit' || type === 'button' || type === 'reset' || type === 'image') {
        const label = node.attrs.value || (tag === 'button' ? flattenText(node).trim() : '') || '提交';
        const w = Math.min(width, measure(label) + 12);
        return {t: 'button', x, y, w, h: 13, label, form: formId, action, method};
    }
    if (tag === 'select') {
        const options = collect(node, 'option', []);
        let selected = '';
        for (const option of options) {
            if (option.attrs.selected != null || !selected) selected = flattenText(option).trim();
        }
        const w = Math.min(width, Math.max(60, measure(selected) + 18));
        return {t: 'field', x, y, w, h: 13, name, value: selected, form: formId,
            placeholder: '', password: 0, action, method};
    }
    if (tag === 'textarea') {
        const value = node.textValue != null ? node.textValue : flattenText(node);
        return {t: 'field', x, y, w: Math.min(width, Math.max(80, width - 4)), h: 34, name,
            value: value.trim(), form: formId, placeholder: node.attrs.placeholder || '',
            password: 0, action, method};
    }
    const value = node.attrs.value || '';
    const password = type === 'password' ? 1 : 0;
    const size = parseInt(node.attrs.size || '0', 10) || 0;
    let w = size > 0 ? size * 7 : 120;
    if (style.width != null) w = style.width;
    if (node.attrs.style) {
        const m = node.attrs.style.match(/width\s*:\s*([\d.]+)(px|%)/);
        if (m) w = m[2] === '%' ? width * parseFloat(m[1]) / 100 : parseFloat(m[1]);
    }
    w = clamp(w, 40, width);
    return {t: 'field', x, y, w, h: 13, name, value, form: formId,
        placeholder: node.attrs.placeholder || '', password, action, method};
}

function layoutField(node, style, lay, x, y, width, parentStyle, root, blockLayout) {
    node.formNode = findForm(node);
    if (node.formNode && !node.formNode._id) {
        node.formNode._id = 'form' + (++formCounter);
        lay.formSeq = formCounter;
    }
    const box = fieldBox(node, style, lay, 0, y, width, blockLayout);
    if (!box) return y;
    if (node.tag === 'select') {
        const selected = box.value || '';
        lay.add({t: 'rect', x, y, w: Math.min(width, Math.max(60, 120)), h: 13, c: '#F4F3EC'});
        lay.add({t: 'rect', x, y, w: Math.min(width, Math.max(60, 120)), h: 1, c: '#8C8878'});
        lay.add({t: 'field', x, y, w: Math.min(width, Math.max(60, 120)), h: 13, name: box.name, value: selected, form: box.form, placeholder: '', password: 0, action: box.action, method: box.method});
        lay.add({t: 'text', x: x + Math.min(width, Math.max(60, 120)) - 9, y: y + 2, s: '▾', c: '#4A4A4A'});
        return y + 16;
    }
    if (box.t === 'button') {
        lay.add({t: 'rect', x, y, w: box.w, h: 13, c: '#E6E4D8'});
        lay.add({t: 'rect', x, y, w: box.w, h: 1, c: '#8C8878'});
        lay.add({t: 'text', x: x + 4, y: y + 3, s: truncate(box.label, box.w - 8), c: '#1F1F1F'});
        lay.add({t: 'button', x, y, w: box.w, h: 13, label: box.label, form: box.form, action: box.action, method: box.method});
        return y + 16;
    }
    if (box.t === 'check') {
        lay.add({t: 'rect', x, y, w: 9, h: 9, c: '#FFFFFF'});
        lay.add({t: 'rect', x, y, w: 9, h: 1, c: '#7F7F7F'});
        if (box.checked) lay.add({t: 'text', x: x + 1, y: y, s: '✓', c: '#1F7A1F'});
        lay.add({t: 'text', x: x + 12, y: y + 1, s: truncate(box.value || box.name, width - 14), c: '#222222'});
        lay.add({t: 'field', x, y, w: 9, h: 9, name: box.name, value: box.value, form: box.form, placeholder: '', password: 0, action: box.action, method: box.method, check: 1, checked: box.checked ? 1 : 0});
        return y + 13;
    }
    const boxW = Math.min(width, Math.max(40, box.w));
    lay.add({t: 'rect', x, y, w: boxW, h: box.h, c: '#FFFFFF'});
    lay.add({t: 'rect', x, y, w: boxW, h: 1, c: '#7F9DB9'});
    lay.add({t: 'rect', x, y: y + box.h - 1, w: boxW, h: 1, c: '#7F9DB9'});
    const shown = box.password ? '•'.repeat(Math.min(32, (box.value || '').length)) : box.value;
    if (shown) {
        lay.add({t: 'text', x: x + 3, y: y + 3, s: truncate(shown, boxW - 6), c: '#222222'});
    } else if (box.placeholder) {
        lay.add({t: 'text', x: x + 3, y: y + 3, s: truncate(box.placeholder, boxW - 6), c: '#9A9A9A'});
    }
    lay.add({t: 'field', x, y, w: boxW, h: box.h, name: box.name, value: box.value, form: box.form,
        placeholder: box.placeholder || '', password: box.password ? 1 : 0, action: box.action, method: box.method});
    return y + box.h + 3;
}

// ------------------------------------------------------------------ 阅读模式

function sanitizeReader(node) {
    const copy = {tag: node.tag, attrs: node.attrs, kids: []};
    const strip = new Set(['script', 'style', 'nav', 'aside', 'header', 'footer', 'form',
        'iframe', 'video', 'audio', 'button', 'input', 'select', 'textarea', 'noscript']);
    const walk = (src, dst) => {
        for (const kid of src.kids || []) {
            if (typeof kid === 'string') {
                if (kid.trim()) dst.kids.push(kid);
                continue;
            }
            if (strip.has(kid.tag)) continue;
            if (/menu|sidebar|comment|advert|footer|share/i.test((kid.attrs.class || '') + ' ' + (kid.attrs.id || ''))) continue;
            const copyKid = {tag: kid.tag, attrs: kid.attrs, kids: []};
            dst.kids.push(copyKid);
            walk(kid, copyKid);
        }
    };
    walk(node, copy);
    return copy;
}

// ------------------------------------------------------------------ 网络

function getCookieHeader(sess, url) {
    let host = '';
    try {
        host = new URL(url).hostname;
    } catch (ignored) {
        return '';
    }
    const now = Date.now();
    const pairs = [];
    for (const [key, cookie] of sess.cookies) {
        if (cookie.expires && cookie.expires < now) {
            sess.cookies.delete(key);
            continue;
        }
        if (cookie.domain && host.endsWith(cookie.domain.replace(/^\./, ''))) {
            pairs.push(cookie.name + '=' + cookie.value);
        }
    }
    return pairs.join('; ');
}

function storeCookies(sess, url, headers) {
    let host = '';
    try {
        host = new URL(url).hostname;
    } catch (ignored) {
        return;
    }
    const lines = typeof headers.getSetCookie === 'function'
        ? headers.getSetCookie() : (headers.get('set-cookie') ? [headers.get('set-cookie')] : []);
    for (const line of lines) {
        const first = String(line).split(';')[0];
        const eq = first.indexOf('=');
        if (eq <= 0) continue;
        const name = first.slice(0, eq).trim();
        const value = first.slice(eq + 1).trim();
        const domainMatch = String(line).match(/domain=([^;]+)/i);
        const domain = domainMatch ? domainMatch[1].trim() : host;
        const expiresMatch = String(line).match(/expires=([^;]+)/i);
        const expires = expiresMatch ? Date.parse(expiresMatch[1]) : 0;
        sess.cookies.set(name + '|' + domain, {name, value, domain, expires: Number.isFinite(expires) ? expires : 0});
    }
}

async function fetchDocument(url, options) {
    const sess = options.session;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), FETCH_TIMEOUT);
    try {
        const headers = {
            'User-Agent': USER_AGENT,
            'Accept': 'text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8',
            'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8'
        };
        const cookie = getCookieHeader(sess, url);
        if (cookie) headers.Cookie = cookie;
        let body = undefined;
        if (options.method === 'post') {
            headers['Content-Type'] = options.enctype || 'application/x-www-form-urlencoded';
            body = options.body || '';
        }
        const response = await fetch(url, {
            method: options.method === 'post' ? 'POST' : 'GET',
            headers,
            body,
            redirect: 'follow',
            signal: controller.signal
        });
        storeCookies(sess, response.url || url, response.headers);
        const type = (response.headers.get('content-type') || 'text/html').toLowerCase();
        const buffer = Buffer.from(await response.arrayBuffer());
        if (buffer.length > MAX_HTML) {
            throw new Error('页面过大（' + Math.round(buffer.length / 1024) + ' KB）');
        }
        const latin = buffer.toString('latin1');
        let charset = (type.match(/charset=([\w-]+)/) || [])[1]
            || (latin.match(/<meta[^>]+charset=["']?([\w-]+)/i) || [])[1] || 'utf-8';
        let text;
        try {
            text = new TextDecoder(charset).decode(buffer);
        } catch (ignored) {
            text = buffer.toString('utf8');
        }
        return {ok: response.ok, status: response.status, url: response.url || url, type, text};
    } finally {
        clearTimeout(timer);
    }
}

async function fetchImageBytes(src, sess) {
    if (!src) return null;
    if (src.startsWith('data:')) {
        const mime = (src.match(/^data:([^;,]+)/) || [])[1] || 'image/png';
        const base64 = src.slice(src.indexOf(',') + 1);
        return {data: base64, mime};
    }
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), IMAGE_TIMEOUT);
    try {
        const headers = {'User-Agent': USER_AGENT, 'Accept': 'image/*,*/*;q=0.5'};
        const cookie = getCookieHeader(sess, src);
        if (cookie) headers.Cookie = cookie;
        const response = await fetch(src, {headers, redirect: 'follow', signal: controller.signal});
        if (!response.ok) return null;
        const buffer = Buffer.from(await response.arrayBuffer());
        if (!buffer.length || buffer.length > 900 * 1024) return null;
        const mime = (response.headers.get('content-type') || 'image/png').split(';')[0];
        if (!/^image\//.test(mime) && !/^data:image/.test(mime)) return null;
        return {data: buffer.toString('base64'), mime};
    } catch (ignored) {
        return null;
    } finally {
        clearTimeout(timer);
    }
}

// ------------------------------------------------------------------ 页面构建

function localPageHtml(url) {
    // Java 端负责 electron:// 页面；这里只兜底一个简单占位。
    return '<!doctype html><html><head><title>Electron</title></head><body style="font-family:sans-serif">'
        + '<h1>Electron</h1><p>地址：' + escapeHtml(url) + '</p></body></html>';
}

function buildPage(request, html, baseUrl, finalUrl) {
    const width = Math.max(80, Math.min(2048, parseInt(request.width, 10) || 300));
    const zoom = Math.min(3, Math.max(0.5, parseFloat(request.zoom) || 1));
    const reader = !!request.reader;
    const cssWidth = Math.max(60, Math.floor(width / zoom));
    const root = parseHtml(html, baseUrl || finalUrl);
    if (!root.title) {
        const h1 = findFirst(root, 'h1');
        root.title = h1 ? stripHtml(flattenText(h1)) : '';
    }
    root.title = root.title || hostOf(finalUrl) || '新标签页';
    if (reader) {
        root.title = root.title || '阅读模式';
    }
    const body = findFirst(root, 'body') || root;
    // 表单节点补父指针
    const walk = (node, parent) => {
        node.parent = parent;
        for (const kid of node.kids || []) if (typeof kid !== 'string') walk(kid, node);
    };
    walk(root, null);

    const lay = layoutDocument(root, cssWidth, zoom, reader);
    const scaleItems = lay.items.map(item => {
        const copy = Object.assign({}, item);
        for (const key of ['x', 'y', 'w', 'h', 'zy']) {
            if (copy[key] != null) copy[key] = Math.round(copy[key] * zoom);
        }
        if (copy.z != null) copy.z = copy.z; // 字体倍数本身已由尺寸控制
        return copy;
    });
    const height = Math.max(40, Math.round(lay.height * zoom));
    const bg = lay.reader ? '#FBFAF4' : (findFirst(root, 'body')?.attrs?.bgcolor ? parseColor(findFirst(root, 'body').attrs.bgcolor) : null);
    return {
        ok: true,
        url: finalUrl,
        title: stripHtml(root.title).slice(0, 120) || hostOf(finalUrl),
        items: scaleItems,
        height,
        background: bg || '#FFFFFF',
        images: lay.images,
        zoom,
        reader: lay.reader,
        engine: ENGINE
    };
}

async function attachImages(page, sess) {
    if (!page.images || !page.images.length) return;
    const pending = page.images.map(async image => {
        const cached = imageCache.get(image.src);
        if (cached) {
            image.data = cached.data;
            image.mime = cached.mime;
            return;
        }
        const bytes = await fetchImageBytes(image.src, sess);
        if (bytes) {
            image.data = bytes.data;
            image.mime = bytes.mime;
            if (imageCache.size > 64) imageCache.delete(imageCache.keys().next().value);
            imageCache.set(image.src, bytes);
        }
    });
    await Promise.allSettled(pending);
}

/** 重新排版当前页面：窗口缩放、最大化或切换阅读模式时不用重新联网。 */
async function relayout(request) {
    const sess = session(request.session);
    const doc = sess.lastDoc;
    if (!doc) throw new Error('当前页面还没有加载完成');
    const page = buildPage(request, doc.html, doc.base, doc.url);
    page.status = doc.status;
    page.cookies = serializeCookies(sess);
    page.reader = !!request.reader;
    await attachImages(page, sess);
    sess.last = {url: page.url, items: page.items, title: page.title};
    return page;
}

function filenameFor(url, mime) {
    let name = '';
    try {
        const path = new URL(url).pathname;
        name = decodeURIComponent(path.split('/').filter(Boolean).pop() || '');
    } catch (ignored) {
    }
    if (!name) {
        const ext = (mime.split('/')[1] || 'bin').split('+')[0].replace(/[^a-z0-9]/gi, '');
        name = '下载-' + Date.now() + '.' + (ext || 'bin');
    }
    return name.replace(/[\\/:*?"<>|]/g, '_').slice(0, 80);
}

/** 下载一个资源，交给 Java 写进虚拟机磁盘。 */
async function download(request) {
    const url = String(request.url || '');
    if (!/^https?:/i.test(url)) throw new Error('只支持 http/https 下载');
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 20000);
    try {
        const response = await fetch(url, {
            headers: {'User-Agent': USER_AGENT, 'Accept': '*/*'},
            redirect: 'follow',
            signal: controller.signal
        });
        if (!response.ok) throw new Error('HTTP ' + response.status);
        const buffer = Buffer.from(await response.arrayBuffer());
        if (!buffer.length) throw new Error('文件为空');
        if (buffer.length > 6 * 1024 * 1024) throw new Error('文件超过 6 MiB');
        const mime = (response.headers.get('content-type') || 'application/octet-stream')
            .split(';')[0].trim();
        return {ok: true, data: buffer.toString('base64'), mime,
            name: filenameFor(url, mime), size: buffer.length};
    } finally {
        clearTimeout(timer);
    }
}

// ------------------------------------------------------------------ 指令处理

async function navigate(request) {
    const sess = session(request.session);
    if (request.cookies) mergeCookies(sess, request.cookies);
    let url = request.url || '';
    if (!url && request.html) url = 'about:blank';
    let html = request.html || null;
    let baseUrl = request.base || url;
    let finalUrl = url;
    let fromCache = false;
    let status = 200;
    let type = 'text/html';

    if (!html && /^https?:/i.test(url)) {
        const cached = pageCache.get(url);
        try {
            const doc = await fetchDocument(url, {session: sess, method: 'get'});
            html = doc.text;
            finalUrl = doc.url;
            baseUrl = doc.url;
            status = doc.status;
            type = doc.type;
            if (/text\/html|application\/xhtml/.test(type) || /<html|<body/i.test(html)) {
                pageCache.delete(url);
                pageCache.set(url, {text: html, url: finalUrl, at: Date.now(), type});
                while (pageCache.size > 30) pageCache.delete(pageCache.keys().next().value);
            }
        } catch (error) {
            if (cached && Date.now() - cached.at < 60 * 60 * 1000) {
                html = cached.text;
                finalUrl = cached.url;
                baseUrl = cached.url;
                fromCache = true;
                status = 200;
                type = cached.type || 'text/html';
            } else {
                throw error;
            }
        }
    } else if (!html && /^file:/i.test(url)) {
        throw new Error('file:// 页面由 Java 虚拟机提供');
    } else if (!html && /^electron:/i.test(url)) {
        html = localPageHtml(url);
    } else if (!html) {
        html = localPageHtml(url);
    }

    if (!/html/i.test(type) && !/<html|<body|<div|<p[ >]/i.test(html)) {
        // 纯文本页面
        const safe = escapeHtml(html).replace(/\n/g, '<br>');
        html = '<!doctype html><html><head><title>' + escapeHtml(finalUrl) + '</title></head>'
            + '<body><pre style="white-space:pre-wrap;font-family:monospace">' + safe + '</pre></body></html>';
    }

    const page = buildPage(request, html, baseUrl, finalUrl);
    page.status = status;
    page.fromCache = fromCache;
    page.cookies = serializeCookies(sess);
    await attachImages(page, sess);
    sess.last = {url: finalUrl, items: page.items, title: page.title};
    sess.lastDoc = {html, base: baseUrl, url: finalUrl, status};
    return page;
}

async function submit(request) {
    const sess = session(request.session);
    if (request.cookies) mergeCookies(sess, request.cookies);
    const pageUrl = request.pageUrl || '';
    const action = normalizeUrl(request.action || pageUrl, pageUrl) || pageUrl;
    const method = (request.method || 'get').toLowerCase();
    const values = request.values || {};
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(values)) {
        if (key) params.append(key, value == null ? '' : String(value));
    }
    let url;
    let body = null;
    let enctype = 'application/x-www-form-urlencoded';
    if (method === 'post') {
        url = action;
        body = params.toString();
    } else {
        const query = params.toString();
        url = action + (action.includes('?') ? '&' : '?') + query;
    }
    const doc = await fetchDocument(url, {session: sess, method, body, enctype});
    let html = doc.text;
    if (!/html/i.test(doc.type) && !/<html|<body/i.test(html)) {
        html = '<!doctype html><html><head><title>' + escapeHtml(doc.url) + '</title></head><body><pre>'
            + escapeHtml(html) + '</pre></body></html>';
    }
    pageCache.set(doc.url, {text: html, url: doc.url, at: Date.now(), type: doc.type});
    const page = buildPage(request, html, doc.url, doc.url);
    page.status = doc.status;
    page.cookies = serializeCookies(sess);
    await attachImages(page, sess);
    sess.last = {url: doc.url, items: page.items, title: page.title};
    sess.lastDoc = {html, base: doc.url, url: doc.url, status: doc.status};
    return page;
}

function findInPage(request) {
    const sess = session(request.session);
    const query = String(request.query || '');
    if (!query.trim() || !sess.last) return {ok: true, matches: [], count: 0};
    const needle = query.toLowerCase();
    const matches = [];
    // 文字在排版时按字/词拆成多个 item，查找时先按行拼回字符序列再匹配。
    const lines = new Map();
    for (const item of sess.last.items) {
        if (item.t !== 'text' || typeof item.s !== 'string' || !item.s) continue;
        const key = Math.round(item.y) + '|' + (item.z || 1);
        if (!lines.has(key)) lines.set(key, []);
        lines.get(key).push(item);
    }
    for (const group of lines.values()) {
        group.sort((a, b) => a.x - b.x);
        const chars = [];
        for (const item of group) {
            const size = item.z || 1;
            let cx = item.x;
            for (const ch of item.s) {
                const w = measure(ch) * size;
                chars.push({ch, x: cx, w, y: item.y, z: size});
                cx += w;
            }
        }
        if (!chars.length) continue;
        const plain = chars.map(c => c.ch).join('').toLowerCase();
        let from = 0;
        while (matches.length < 300) {
            const at = plain.indexOf(needle, from);
            if (at < 0) break;
            const first = chars[at];
            const last = chars[Math.min(chars.length - 1, at + Math.max(1, needle.length) - 1)];
            matches.push({
                x: Math.round(first.x),
                y: Math.round(first.y),
                w: Math.max(4, Math.round(last.x + last.w - first.x)),
                h: Math.max(10, Math.round(11 * first.z))
            });
            from = at + Math.max(1, needle.length);
        }
        if (matches.length >= 300) break;
    }
    return {ok: true, matches, count: matches.length};
}

function serializeCookies(sess) {
    const out = [];
    for (const cookie of sess.cookies.values()) {
        out.push({name: cookie.name, value: cookie.value, domain: cookie.domain, expires: cookie.expires});
    }
    return out;
}

function mergeCookies(sess, list) {
    if (!Array.isArray(list)) return;
    for (const cookie of list) {
        if (!cookie || !cookie.name) continue;
        const domain = cookie.domain || '';
        sess.cookies.set(cookie.name + '|' + domain, {
            name: cookie.name, value: cookie.value == null ? '' : String(cookie.value),
            domain, expires: Number(cookie.expires) || 0
        });
    }
}

// ------------------------------------------------------------------ 主循环

const rl = readline.createInterface({input: process.stdin, terminal: false});
const queue = [];
let busy = false;

function reply(payload) {
    process.stdout.write(JSON.stringify(payload) + '\n');
}

async function handle(line) {
    let request;
    try {
        request = JSON.parse(line);
    } catch (error) {
        reply({ok: false, error: 'JSON 解析失败：' + error.message});
        return;
    }
    const id = request.id;
    try {
        switch (request.op) {
            case 'hello': {
                const font = request.font || {};
                FONT = {};
                for (const [key, value] of Object.entries(font)) {
                    const cp = Number(key);
                    const w = Number(value);
                    if (Number.isFinite(cp) && Number.isFinite(w) && w > 0) FONT[cp] = w;
                }
                FONT_DEFAULT = Number(request.defaultWidth) || 6;
                FONT_CJK = Number(request.cjkWidth) || 9;
                reply({id, ok: true, engine: ENGINE, font: Object.keys(FONT).length});
                break;
            }
            case 'navigate':
                reply({id, ...(await navigate(request))});
                break;
            case 'submit':
                reply({id, ...(await submit(request))});
                break;
            case 'relayout':
                reply({id, ...(await relayout(request))});
                break;
            case 'download':
                reply({id, ...(await download(request))});
                break;
            case 'find':
                reply({id, ...findInPage(request)});
                break;
            case 'cookies':
                reply({id, ok: true, cookies: serializeCookies(session(request.session))});
                break;
            case 'setCookies':
                mergeCookies(session(request.session), request.data);
                reply({id, ok: true});
                break;
            case 'ping':
                reply({id, ok: true, engine: ENGINE, sessions: sessions.size});
                break;
            case 'quit':
                reply({id, ok: true});
                process.exit(0);
                break;
            default:
                reply({id, ok: false, error: '未知指令：' + request.op});
                break;
        }
    } catch (error) {
        reply({id, ok: false, error: (error && error.message) || String(error)});
    }
}

function pump() {
    if (busy) return;
    const next = queue.shift();
    if (!next) return;
    busy = true;
    handle(next).finally(() => {
        busy = false;
        pump();
    });
}

rl.on('line', line => {
    const text = line.trim();
    if (!text) return;
    queue.push(text);
    pump();
});

rl.on('close', () => {
    process.exit(0);
});

process.on('uncaughtException', error => {
    reply({ok: false, error: 'SDK 异常：' + (error && error.message ? error.message : String(error))});
});
process.on('unhandledRejection', error => {
    reply({ok: false, error: 'SDK 异步异常：' + (error && error.message ? error.message : String(error))});
});

reply({ok: true, event: 'ready', engine: ENGINE});
