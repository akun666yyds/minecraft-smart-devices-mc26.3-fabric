'use strict';
/*
 * 哈基坤 OS 的 Chromium 主机。
 *
 * Java 启动 electron.exe 并把这个脚本作为入口，双方用「一行一条 JSON」通信：
 *   Java -> 这里：create / load / html / resize / mouse / wheel / key / text / find ...
 *   这里 -> Java：paint（JPEG 帧）、state（地址、标题、加载状态）、nav（内部链接）、
 *                 open（新窗口）、download、find、ready
 *
 * 每个标签页是一个 offscreen BrowserWindow，画面由 paint 事件拿到 NativeImage，
 * 压成 JPEG 再走标准输出。所有页面共享 persist:hajikun 会话，登录状态存在
 * 用户数据目录里，关掉游戏也不会丢。
 */

const { app, BrowserWindow, session, shell } = require('electron');
const fs = require('fs');
const path = require('path');
const readline = require('readline');

const PROFILE_DIR = process.env.HAJIKUN_PROFILE
    || path.join(app.getPath('appData'), 'hajikun-electron');
const DOWNLOAD_DIR = process.env.HAJIKUN_DOWNLOAD_DIR
    || path.join(PROFILE_DIR, 'downloads');

try {
    fs.mkdirSync(PROFILE_DIR, { recursive: true });
    fs.mkdirSync(DOWNLOAD_DIR, { recursive: true });
} catch (ignored) {
}

// 必须先于 ready：软件渲染比 GPU 合成在 offscreen 下更稳，也更省驱动兼容问题。
app.disableHardwareAcceleration();
app.setPath('userData', PROFILE_DIR);
app.setPath('sessionData', PROFILE_DIR);
app.commandLine.appendSwitch('force-device-scale-factor', '1');
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');
app.commandLine.appendSwitch('lang', 'zh-CN');
app.commandLine.appendSwitch('disable-features',
    'CalculateNativeWinOcclusion,HardwareMediaKeyHandling');
app.commandLine.appendSwitch('enable-features', 'OverlayScrollbar');

const USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
    + '(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';

const tabs = new Map();
let nextTab = 1;
let sharedSession = null;
let ready = false;
const queue = [];

function send(message) {
    try {
        process.stdout.write(JSON.stringify(message) + '\n');
    } catch (ignored) {
    }
}

function reply(id, extra) {
    if (id === undefined || id === null) {
        return;
    }
    send(Object.assign({ id: id, ok: true }, extra || {}));
}

function fail(id, error) {
    if (id === undefined || id === null) {
        return;
    }
    send({ id: id, ok: false, error: String(error || '未知错误') });
}

function report(tab, message) {
    send(Object.assign({ event: 'log', tab: tab ? tab.id : 0 }, message));
}

function emitState(tab) {
    const wc = tab.win.webContents;
    let url = '';
    let title = '';
    let loading = false;
    let canBack = false;
    let canForward = false;
    try {
        url = wc.getURL();
        title = wc.getTitle();
        loading = wc.isLoading();
        canBack = wc.canGoBack();
        canForward = wc.canGoForward();
    } catch (ignored) {
    }
    send({
        event: 'state', tab: tab.id, url: url, title: title, loading: loading,
        canBack: canBack, canForward: canForward,
        muted: tab.muted === true,
        zoom: tab.zoom || 1.0,
        internal: tab.internal === true,
        error: tab.error || ''
    });
}

function isInternal(url) {
    return typeof url === 'string'
        && (url.startsWith('electron://') || url.startsWith('hajikun://'));
}

function isVirtualFile(url) {
    if (typeof url !== 'string' || !url.startsWith('file://')) {
        return false;
    }
    // 虚拟机里的路径形如 file:///home/user；宿主盘符是 file:///C:/...
    const rest = url.slice('file://'.length);
    return !/^\/[A-Za-z]:[\\/]/.test(rest);
}

function attach(tab) {
    const wc = tab.win.webContents;
    wc.setFrameRate(24);
    wc.setAudioMuted(false);
    tab.zoom = 1.0;
    tab.internal = false;
    tab.error = '';

    wc.on('paint', (event, dirty, image) => {
        if (tab.closed || image == null || image.isEmpty()) {
            return;
        }
        try {
            const size = image.getSize();
            const jpeg = image.toJPEG(tab.internal ? 82 : 72);
            if (!jpeg || jpeg.length === 0) {
                return;
            }
            send({
                event: 'paint', tab: tab.id, w: size.width, h: size.height,
                data: jpeg.toString('base64')
            });
        } catch (ignored) {
        }
    });

    wc.on('did-start-loading', () => {
        tab.error = '';
        emitState(tab);
    });
    wc.on('did-stop-loading', () => emitState(tab));
    wc.on('did-navigate', (event, url, code) => {
        if (!tab.internal) {
            tab.url = url;
        }
        tab.status = code;
        emitState(tab);
    });
    wc.on('did-navigate-in-page', (event, url, isMainFrame) => {
        if (!isMainFrame) {
            return;
        }
        const mark = '#hajikun-nav=';
        const at = url.indexOf(mark);
        if (at >= 0) {
            let target = '';
            try {
                target = decodeURIComponent(url.slice(at + mark.length));
            } catch (ignored) {
                target = url.slice(at + mark.length);
            }
            if (target.length > 0) {
                send({ event: 'nav', tab: tab.id, url: target });
            }
            // 把哨兵哈希从地址里去掉，页面本身不滚动也不重新加载。
            const clean = url.slice(0, at);
            wc.executeJavaScript(
                'try{history.replaceState(null,"",' + JSON.stringify(clean) + ')}catch(e){}'
            ).catch(() => { });
            return;
        }
        if (!tab.internal) {
            tab.url = url;
            emitState(tab);
        }
    });
    wc.on('page-title-updated', () => emitState(tab));
    wc.on('did-fail-load', (event, code, description, url, isMainFrame) => {
        // -3 是正常的中断（点击链接/停止加载时很常见），不当成错误。
        if (!isMainFrame || code === -3) {
            return;
        }
        tab.error = description + ' (' + code + ')';
        emitState(tab);
    });
    wc.on('found-in-page', (event, result) => {
        send({
            event: 'find', tab: tab.id,
            matches: result.matches || 0,
            active: result.activeMatchOrdinal || 0,
            final: result.finalUpdate === true
        });
    });
    wc.on('will-navigate', (event, url) => {
        if (isInternal(url) || isVirtualFile(url)) {
            event.preventDefault();
            send({ event: 'nav', tab: tab.id, url: url });
        }
    });
    wc.on('will-redirect', (event, url) => {
        if (isInternal(url) || isVirtualFile(url)) {
            event.preventDefault();
            send({ event: 'nav', tab: tab.id, url: url });
        }
    });
    wc.setWindowOpenHandler(({ url }) => {
        if (isInternal(url) || isVirtualFile(url)) {
            send({ event: 'nav', tab: tab.id, url: url });
        } else {
            send({ event: 'open', tab: tab.id, url: url });
        }
        return { action: 'deny' };
    });
    wc.on('destroyed', () => {
        tab.closed = true;
        emitState(tab);
    });

    tab.win.on('closed', () => {
        tab.closed = true;
    });
}

function createTab(id, width, height) {
    const win = new BrowserWindow({
        show: false,
        frame: false,
        width: Math.max(80, width | 0),
        height: Math.max(60, height | 0),
        useContentSize: true,
        skipTaskbar: true,
        backgroundColor: '#ffffff',
        webPreferences: {
            partition: 'persist:hajikun',
            offscreen: true,
            backgroundThrottling: false,
            contextIsolation: true,
            sandbox: true,
            nodeIntegration: false,
            webSecurity: true,
            spellcheck: false,
            autoplayPolicy: 'no-user-gesture-required',
            webgl: true
        }
    });
    win.setMenu(null);
    win.setMenuBarVisibility(false);
    if (typeof win.setContentProtection === 'function') {
        win.setContentProtection(false);
    }
    const tab = {
        id: id, win: win, url: '', status: 0, error: '', muted: false,
        zoom: 1.0, internal: false, closed: false, reader: false, readerUrl: ''
    };
    tabs.set(id, tab);
    attach(tab);
    return tab;
}

function tabOf(req) {
    const tab = tabs.get(req.tab);
    if (!tab || tab.closed) {
        throw new Error('标签页不存在：' + req.tab);
    }
    return tab;
}

function mouseEvent(tab, req) {
    const wc = tab.win.webContents;
    const type = req.type;
    const x = Math.round(req.x || 0);
    const y = Math.round(req.y || 0);
    const base = { x: x, y: y, modifiers: req.modifiers || [] };
    if (type === 'move') {
        wc.sendInputEvent(Object.assign({ type: 'mouseMove' }, base));
    } else if (type === 'down' || type === 'up') {
        wc.sendInputEvent(Object.assign({
            type: type === 'down' ? 'mouseDown' : 'mouseUp',
            button: req.button === 2 ? 'right' : 'left',
            clickCount: req.clicks || 1
        }, base));
    } else if (type === 'leave') {
        wc.sendInputEvent({ type: 'mouseLeave' });
    }
}

function applyZoom(tab) {
    try {
        tab.win.webContents.setZoomFactor(tab.zoom);
    } catch (ignored) {
    }
}

// 内置页面里的 electron:// 与虚拟机 file:// 链接不能交给 Chromium 自己导航，
// 注入一小段脚本改走页面哈希，由主进程转给 Java 处理。
const NAV_BRIDGE = '<script>(function () {'
    + 'function go(u) { location.hash = "hajikun-nav=" + encodeURIComponent(u); }'
    + 'document.addEventListener("click", function (e) {'
    + '  var a = e.target && e.target.closest ? e.target.closest("a[href]") : null;'
    + '  if (!a) { return; }'
    + '  var href = a.getAttribute("href") || "";'
    + '  if (href.indexOf("electron://") !== 0 && href.indexOf("file://") !== 0) { return; }'
    + '  e.preventDefault(); go(href);'
    + '}, true);'
    + 'document.addEventListener("submit", function (e) {'
    + '  var f = e.target;'
    + '  if (!f || !f.getAttribute) { return; }'
    + '  var action = f.getAttribute("action") || "";'
    + '  if (action.indexOf("electron://") !== 0) { return; }'
    + '  e.preventDefault();'
    + '  var q = "";'
    + '  try { q = new URLSearchParams(new FormData(f)).toString(); } catch (err) { q = ""; }'
    + '  go(action + (action.indexOf("?") >= 0 ? "&" : "?") + q);'
    + '}, true);'
    + '})();</script>';

const READER_SCRIPT = `(function () {    if (window.__hajikunReader) { return 'already'; }
    window.__hajikunReader = true;
    var nodes = Array.prototype.slice.call(
        document.querySelectorAll('article, main, [role="main"], .article, .post, .content'));
    nodes.sort(function (a, b) { return (b.innerText || '').length - (a.innerText || '').length; });
    var source = nodes[0] || document.body;
    if (!source) { return 'empty'; }
    var clone = source.cloneNode(true);
    Array.prototype.forEach.call(clone.querySelectorAll(
        'script,style,nav,aside,footer,header,form,iframe,video,audio,button,input'), function (n) {
        n.remove();
    });
    document.body.innerHTML = '<div id="hajikun-reader">' + clone.innerHTML + '</div>';
    document.body.style.margin = '0';
    document.body.style.background = '#fbfaf6';
    var style = document.createElement('style');
    style.textContent = '#hajikun-reader{max-width:680px;margin:0 auto;padding:18px 16px 48px;'
        + 'font:16px/1.7 -apple-system,"Segoe UI","Microsoft YaHei",sans-serif;color:#222}'
        + '#hajikun-reader h1{font-size:26px;line-height:1.3}'
        + '#hajikun-reader img{max-width:100%;height:auto}#hajikun-reader a{color:#1a5fb4}';
    document.head.appendChild(style);
    window.scrollTo(0, 0);
    return 'ok';
})()`;

function handle(req) {
    switch (req.op) {
        case 'create': {
            const tab = createTab(req.tab, req.w, req.h);
            if (req.zoom) {
                tab.zoom = Math.max(0.25, Math.min(3.5, req.zoom));
                applyZoom(tab);
            }
            reply(req.id);
            emitState(tab);
            return;
        }
        case 'load': {
            const tab = tabOf(req);
            tab.internal = req.internal === true;
            tab.url = req.url || '';
            tab.error = '';
            tab.win.webContents.loadURL(req.url, req.headers ? { httpReferrer: req.headers } : undefined)
                .catch((error) => report(tab, { message: String(error) }));
            reply(req.id);
            return;
        }
        case 'html': {
            const tab = tabOf(req);
            tab.internal = true;
            tab.url = req.url || '';
            tab.error = '';
            let html = req.html || '';
            html = /<\/body>/i.test(html)
                ? html.replace(/<\/body>/i, NAV_BRIDGE + '</body>')
                : html + NAV_BRIDGE;
            const base64 = Buffer.from(html, 'utf8').toString('base64');
            const url = 'data:text/html;charset=utf-8;base64,' + base64;
            tab.win.webContents.loadURL(url, { baseURLForDataURL: req.base || undefined })
                .catch((error) => report(tab, { message: String(error) }));
            reply(req.id);
            return;
        }
        case 'resize': {
            const tab = tabOf(req);
            const w = Math.max(80, req.w | 0);
            const h = Math.max(60, req.h | 0);
            const size = tab.win.getContentSize();
            if (size[0] !== w || size[1] !== h) {
                tab.win.setContentSize(w, h);
            }
            reply(req.id);
            return;
        }
        case 'mouse': {
            mouseEvent(tabOf(req), req);
            reply(req.id);
            return;
        }
        case 'wheel': {
            const tab = tabOf(req);
            const dy = Number(req.dy || 0);
            const dx = Number(req.dx || 0);
            tab.win.webContents.sendInputEvent({
                type: 'mouseWheel',
                x: Math.round(req.x || 0),
                y: Math.round(req.y || 0),
                deltaX: Math.round(dx),
                deltaY: Math.round(dy),
                wheelTicksX: dx / 100,
                wheelTicksY: dy / 100,
                canScroll: true,
                modifiers: req.modifiers || []
            });
            reply(req.id);
            return;
        }
        case 'key': {
            const tab = tabOf(req);
            tab.win.webContents.sendInputEvent({
                type: req.type === 'up' ? 'keyUp' : 'keyDown',
                keyCode: req.key || '',
                modifiers: req.modifiers || []
            });
            reply(req.id);
            return;
        }
        case 'text': {
            const tab = tabOf(req);
            const text = req.text || '';
            if (text.length > 0) {
                tab.win.webContents.insertText(text);
            }
            reply(req.id);
            return;
        }
        case 'nav': {
            const tab = tabOf(req);
            const wc = tab.win.webContents;
            if (req.dir === 'back' && wc.canGoBack()) {
                wc.goBack();
            } else if (req.dir === 'forward' && wc.canGoForward()) {
                wc.goForward();
            } else if (req.dir === 'reload') {
                wc.reload();
            } else if (req.dir === 'stop') {
                wc.stop();
            }
            reply(req.id);
            return;
        }
        case 'zoom': {
            const tab = tabOf(req);
            tab.zoom = Math.max(0.25, Math.min(3.5, Number(req.zoom) || 1));
            applyZoom(tab);
            reply(req.id);
            return;
        }
        case 'mute': {
            const tab = tabOf(req);
            tab.muted = req.mute === true;
            tab.win.webContents.setAudioMuted(tab.muted);
            reply(req.id);
            return;
        }
        case 'find': {
            const tab = tabOf(req);
            const query = String(req.query || '');
            if (query.length === 0) {
                tab.win.webContents.stopFindInPage('clearSelection');
                send({ event: 'find', tab: tab.id, matches: 0, active: 0, final: true });
            } else {
                tab.win.webContents.findInPage(query, {
                    forward: req.forward !== false,
                    findNext: req.next === true
                });
            }
            reply(req.id);
            return;
        }
        case 'readermode': {
            const tab = tabOf(req);
            const wc = tab.win.webContents;
            if (req.on === true && !tab.reader) {
                tab.reader = true;
                tab.readerUrl = tab.url;
                wc.executeJavaScript(READER_SCRIPT, true).catch(() => { });
            } else if (req.on !== true && tab.reader) {
                tab.reader = false;
                wc.reload();
            }
            reply(req.id);
            return;
        }
        case 'close': {
            const tab = tabs.get(req.tab);
            if (tab) {
                tab.closed = true;
                tabs.delete(req.tab);
                try {
                    tab.win.destroy();
                } catch (ignored) {
                }
            }
            reply(req.id);
            return;
        }
        case 'shutdown': {
            reply(req.id);
            setTimeout(() => {
                for (const tab of tabs.values()) {
                    try {
                        tab.win.destroy();
                    } catch (ignored) {
                    }
                }
                tabs.clear();
                app.quit();
            }, 60);
            return;
        }
        default:
            fail(req.id, '未知操作：' + req.op);
    }
}

function setupSession() {
    sharedSession = session.fromPartition('persist:hajikun');
    try {
        sharedSession.setUserAgent(USER_AGENT);
    } catch (ignored) {
    }
    sharedSession.setPermissionRequestHandler((wc, permission, callback) => {
        const allowed = ['media', 'mediaKeySystem', 'geolocation', 'notifications',
            'clipboard-read', 'clipboard-sanitized-write', 'fullscreen', 'pointerLock',
            'display-capture'];
        callback(allowed.indexOf(permission) >= 0);
    });
    sharedSession.setPermissionCheckHandler((wc, permission) => {
        return ['media', 'geolocation', 'clipboard-read', 'clipboard-sanitized-write',
            'fullscreen', 'pointerLock'].indexOf(permission) >= 0;
    });
    sharedSession.on('will-download', (event, item, wc) => {
        const rawName = item.getFilename() || 'download.bin';
        const name = rawName.replace(/[<>:"|?*\u0000-\u001F]/g, '_').slice(0, 80) || 'download.bin';
        const savePath = path.join(DOWNLOAD_DIR, name);
        item.setSavePath(savePath);
        const info = {
            url: item.getURL(), name: name, path: savePath,
            bytes: item.getTotalBytes(), state: 'progress'
        };
        send(Object.assign({ event: 'download' }, info));
        item.on('updated', (e, state) => {
            info.state = state;
            info.bytes = item.getReceivedBytes();
            send(Object.assign({ event: 'download' }, info));
        });
        item.once('done', (e, state) => {
            info.state = state;
            info.bytes = item.getReceivedBytes();
            info.path = item.getSavePath() || savePath;
            send(Object.assign({ event: 'download', done: true }, info));
        });
    });
    app.on('web-contents-created', (event, contents) => {
        contents.setWindowOpenHandler(({ url }) => {
            if (isInternal(url) || isVirtualFile(url)) {
                send({ event: 'nav', tab: 0, url: url });
            } else {
                send({ event: 'open', tab: 0, url: url });
            }
            return { action: 'deny' };
        });
    });
    ready = true;
    send({
        event: 'ready',
        engine: 'Electron ' + process.versions.electron + ' · Chromium ' + process.versions.chrome,
        profile: PROFILE_DIR
    });
    while (queue.length > 0) {
        const line = queue.shift();
        consume(line);
    }
}

function consume(line) {
    let req;
    try {
        req = JSON.parse(line);
    } catch (error) {
        return;
    }
    if (!req || typeof req.op !== 'string') {
        return;
    }
    try {
        handle(req);
    } catch (error) {
        fail(req.id, error && error.message ? error.message : error);
    }
}

const rl = readline.createInterface({ input: process.stdin });
rl.on('line', (line) => {
    if (!line || !line.trim()) {
        return;
    }
    if (!ready) {
        queue.push(line);
        return;
    }
    consume(line);
});
rl.on('close', () => {
    for (const tab of tabs.values()) {
        try {
            tab.win.destroy();
        } catch (ignored) {
        }
    }
    app.quit();
});

process.on('uncaughtException', (error) => {
    send({ event: 'log', tab: 0, message: '主进程异常：' + (error && error.message) });
});
process.on('unhandledRejection', (error) => {
    send({ event: 'log', tab: 0, message: '未处理的异步错误：' + error });
});

app.whenReady().then(setupSession).catch((error) => {
    send({ event: 'log', tab: 0, message: '初始化失败：' + error });
    app.quit();
});

app.on('window-all-closed', () => {
    // 保持主进程存活，Java 通过 stdin 控制生命周期。
});
