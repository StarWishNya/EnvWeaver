'use strict';

/* 哨兵控制台。哈希路由；令牌只进 sessionStorage；服务端文本只用 textContent。 */

var ACCESS_KEY = 'sentinel.accessToken';
var REFRESH_KEY = 'sentinel.refreshToken';

var TASK_STATUSES = [
  ['PENDING', '等待中'], ['QUEUED', '排队中'], ['RUNNING', '运行中'],
  ['SUCCESS', '成功'], ['FAILED', '失败'], ['CANCELLED', '已取消']
];
/* RETAINED：任务失败后保留到 expiresAt，到期由后台释放。筛选会请求 ?status=RETAINED。 */
var ENV_STATUSES = [
  ['CREATING', '创建中'], ['READY', '就绪'], ['BUSY', '占用'], ['RELEASING', '释放中'],
  ['IDLE', '空闲'], ['ERROR', '错误'], ['RETAINED', '失败保留']
];
/* IDLE、RELEASING 释放会 400，按钮改成破折号。 */
var RELEASABLE_ENV = { CREATING: 1, READY: 1, BUSY: 1, ERROR: 1, RETAINED: 1 };
var TEST_TYPES = [
  ['UNIT_TEST', '单元测试'], ['INTEGRATION_TEST', '集成测试'], ['E2E_TEST', '端到端测试']
];
var DIAG_STATUSES = [
  ['PENDING', '排队'], ['PROCESSING', '分析中'], ['COMPLETED', '完成'], ['FAILED', '失败']
];
var ERROR_TYPES = [
  ['RESOURCE_EXHAUSTED', '资源耗尽'], ['NETWORK_ERROR', '网络错误'], ['DEPENDENCY_FAILURE', '依赖失败'],
  ['TIMEOUT', '超时'], ['CONFIG_ERROR', '配置错误'], ['UNKNOWN', '未知']
];
var SEVERITIES = [['LOW', '低'], ['MEDIUM', '中'], ['HIGH', '高'], ['CRITICAL', '严重']];
var ACTIVE_TASK = { PENDING: 1, QUEUED: 1, RUNNING: 1 };
var TERMINAL_TASK = { SUCCESS: 1, FAILED: 1, CANCELLED: 1 };

var ui = {};
var currentUser = null;
var generation = 0;
var refreshTask = null;
var closeDialog = null;
var actionBusy = false;
var taskSeq = 0;
var clocks = { tasks: null, diagnosis: null, metrics: null };
var pollers = [];
var hashEcho = '';
var mountedKey = '';
var rendering = false;
var PAGE_SIZES = [20, 50, 100];
var LOG_LINE_CAP = 5000;
var LOG_ORIGIN = '1970-01-01T00:00:00Z';
var FOCUS_ACTIONS = { cancel: 1, success: 1, fail: 1, prev: 1, next: 1, size: 1, release: 1 };

/* ---------- DOM ---------- */

function el(tag, attrs) {
  var node = document.createElement(tag);
  if (!attrs) return node;
  Object.keys(attrs).forEach(function (key) {
    var value = attrs[key];
    if (value == null || value === false) return;
    if (key === 'class') node.className = String(value);
    else node.setAttribute(key, value === true ? '' : String(value));
  });
  return node;
}

function text(tag, value, attrs) {
  var node = el(tag, attrs);
  node.textContent = value == null ? '' : String(value);
  return node;
}

function add(parent) {
  for (var i = 1; i < arguments.length; i += 1) {
    if (arguments[i]) parent.appendChild(arguments[i]);
  }
  return parent;
}

function on(node, type, fn) {
  node.addEventListener(type, fn);
  return node;
}

function btn(label, className, fn) {
  return on(text('button', label, { type: 'button', class: className || 'btn' }), 'click', fn);
}

function labelOf(list, value) {
  for (var i = 0; i < list.length; i += 1) {
    if (list[i][0] === value) return list[i][1];
  }
  return value == null || value === '' ? '—' : String(value);
}

function badge(list, status) {
  var safe = String(status || 'unknown').toLowerCase().replace(/[^a-z0-9_-]/g, '') || 'unknown';
  var node = add(el('span', { class: 'badge badge-' + safe }), el('i', { class: 'lamp' }));
  node.appendChild(document.createTextNode(labelOf(list, status)));
  if (status === 'RETAINED') node.title = '任务失败后保留到过期时间，到期自动释放';
  return node;
}

function field(label, control, className) {
  return add(el('label', { class: className || 'field' }), text('span', label), control);
}

function selectBox(name, options, current) {
  var select = el('select', { name: name });
  options.forEach(function (item) {
    var opt = text('option', item[1], { value: item[0] });
    if (item[0] === current) opt.selected = true;
    select.appendChild(opt);
  });
  return select;
}

function routeLink(hash, label) {
  return text('a', label, { href: hash, class: 'mono' });
}

function idLink(section, id, label) {
  if (id == null) return text('span', '—');
  return routeLink('#/' + section + '/' + encodeURIComponent(id), label == null ? String(id) : String(label));
}

function facts(pairs) {
  var dl = el('dl', { class: 'facts' });
  pairs.forEach(function (pair) {
    if (!pair || pair[0] == null || String(pair[0]).trim() === '') return;
    var dd = el('dd');
    if (pair[1] instanceof Node) dd.appendChild(pair[1]);
    else dd.textContent = pair[1] == null || pair[1] === '' ? '—' : String(pair[1]);
    add(dl, text('dt', pair[0]), dd);
  });
  return dl;
}

function callout(title, message) {
  return add(el('div', { class: 'callout' }), text('strong', title), text('p', message));
}

function dataTable(headers) {
  var head = el('tr');
  headers.forEach(function (label) { head.appendChild(text('th', label)); });
  var body = el('tbody');
  var table = add(el('table'), add(el('thead'), head), body);
  return { table: table, body: body };
}

function emptyRow(body, cols, message) {
  body.replaceChildren(add(el('tr'), text('td', message, { colspan: String(cols) })));
}

function clipCell(value) {
  var cell = text('td', value || '—', { class: 'clip' });
  if (value) cell.title = String(value);
  return cell;
}

function showBanner(message, kind) {
  if (!ui.banner) return;
  ui.banner.hidden = false;
  ui.banner.className = 'banner' + (kind === 'ok' ? ' is-ok' : '');
  ui.bannerText.textContent = message || '请求失败';
}

function showError(message) { showBanner(message, 'error'); }
function showOk(message) { showBanner(message, 'ok'); }

function setHeading(title) {
  ui.title.textContent = title;
  document.title = title + ' · 哨兵';
}

/* ---------- 时间与数量 ---------- */

function pad(n) { return String(n).padStart(2, '0'); }

function formatTime(value) {
  if (value == null || value === '') return '—';
  if (Array.isArray(value)) {
    if (!value[0] || !value[1] || !value[2]) return '—';
    return value[0] + '-' + pad(value[1]) + '-' + pad(value[2]) + ' '
      + pad(value[3] || 0) + ':' + pad(value[4] || 0) + ':' + pad(value[5] || 0);
  }
  return String(value).replace('T', ' ').replace(/\.\d+/, '').replace(/Z$/, '').replace(/[+-]\d{2}:\d{2}$/, '') || '—';
}

function finiteNumber(value) {
  return typeof value === 'number' && isFinite(value) ? value : null;
}

function formatCpu(value) {
  var n = finiteNumber(value);
  if (n == null) return '—';
  if (n > 0 && n < 0.005) return '<0.01 核';
  return n.toFixed(2) + ' 核';
}

function formatMb(value) {
  var n = finiteNumber(value);
  return n == null ? '—' : Math.round(n) + ' MB';
}

function formatPercent(value) {
  var n = finiteNumber(value);
  return n == null || n < 0 ? '不可用' : (Math.round(n * 10) / 10).toFixed(1) + '%';
}

function formatBytes(value) {
  var n = Number(value);
  if (!isFinite(n) || n < 0) return '—';
  var units = ['B', 'KB', 'MB', 'GB', 'TB'];
  var i = 0;
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i += 1; }
  return n.toFixed(i ? 1 : 0) + ' ' + units[i];
}

function formatConfidence(value) {
  var n = finiteNumber(value);
  return n == null ? '未提供' : String(Math.round(n * 100) / 100);
}

function roleLabel(role) {
  if (role === 'ADMIN') return '管理员';
  if (role === 'USER') return '用户';
  return role ? String(role) : '';
}

/* ---------- 会话与请求 ---------- */

function token() { return sessionStorage.getItem(ACCESS_KEY) || ''; }

function saveSession(data) {
  if (!data || !data.accessToken) return false;
  sessionStorage.setItem(ACCESS_KEY, data.accessToken);
  if (data.refreshToken) sessionStorage.setItem(REFRESH_KEY, data.refreshToken);
  return true;
}

function clearSession() {
  sessionStorage.removeItem(ACCESS_KEY);
  sessionStorage.removeItem(REFRESH_KEY);
  currentUser = null;
}

function messageOf(payload, status) {
  var message = payload && typeof payload.message === 'string' && payload.message.trim()
    ? payload.message.trim() : ('请求失败（' + status + '）');
  var data = payload && payload.data;
  if (!data || typeof data !== 'object' || Array.isArray(data)) return message;
  var parts = Object.keys(data).filter(function (key) {
    return typeof data[key] === 'string' && data[key].trim();
  }).map(function (key) { return data[key].trim(); });
  return parts.length ? message + '：' + parts.join('；') : message;
}

function codeOf(payload) {
  if (!payload || payload.code == null || payload.code === '') return null;
  var n = Number(payload.code);
  return isFinite(n) ? n : null;
}

function ApiError(message, status, code) {
  this.name = 'ApiError';
  this.message = message;
  this.status = status;
  this.code = code;
  this.handled = false;
}
ApiError.prototype = Object.create(Error.prototype);

function readPayload(response) {
  return response.text().then(function (body) {
    if (!body) return null;
    try { return JSON.parse(body); } catch (ignore) { return { message: '响应不是合法的 JSON' }; }
  });
}

function goLogin() {
  paintUser();
  if (location.hash !== '#/login') location.hash = '#/login';
  else onRoute();
}

/* 刷新与登录响应同形：POST /api/auth/refresh，正文 { refreshToken }，不附带过期访问令牌。 */
function doRefresh(refreshToken) {
  return fetch('/api/auth/refresh', {
    method: 'POST',
    cache: 'no-store',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken: refreshToken })
  }).then(function (response) {
    if (!response.ok) return false;
    return readPayload(response).then(function (payload) { return saveSession(payload && payload.data); });
  }).catch(function () { return false; });
}

function tryRefresh() {
  var refreshToken = sessionStorage.getItem(REFRESH_KEY);
  if (!refreshToken) return Promise.resolve(false);
  if (!refreshTask) {
    refreshTask = doRefresh(refreshToken).then(function (ok) {
      refreshTask = null;
      return ok;
    }, function () {
      refreshTask = null;
      return false;
    });
  }
  return refreshTask;
}

function request(path, options) {
  var opts = options || {};
  var headers = new Headers({ Accept: 'application/json' });
  if (token() && !opts.anonymous) headers.set('Authorization', 'Bearer ' + token());
  var init = { method: opts.method || 'GET', headers: headers, cache: 'no-store' };
  if (opts.body !== undefined) {
    headers.set('Content-Type', 'application/json');
    init.body = JSON.stringify(opts.body);
  }
  return fetch(path, init).then(function (response) {
    if (response.status !== 401 || opts.anonymous || opts.retried || path === '/api/auth/refresh') return response;
    return tryRefresh().then(function (ok) {
      if (ok) return request(path, Object.assign({}, opts, { retried: true }));
      return readPayload(response).then(function (payload) {
        var error = new ApiError(messageOf(payload, response.status), response.status, codeOf(payload));
        error.handled = true;
        clearSession();
        showError(error.message);
        goLogin();
        throw error;
      });
    });
  }).catch(function (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError('网络异常，请稍后重试', 0, null);
  });
}

function api(path, options) {
  return request(path, options).then(function (response) {
    return readPayload(response).then(function (payload) {
      if (!response.ok) throw new ApiError(messageOf(payload, response.status), response.status, codeOf(payload));
      return {
        data: payload ? payload.data : null,
        fromCache: !!(payload && payload.fromCache),
        message: payload && typeof payload.message === 'string' ? payload.message : ''
      };
    });
  });
}

function fetchHealth() {
  return request('/api/metrics/health', {}).then(function (response) {
    return readPayload(response).then(function (payload) {
      if ((response.status === 200 || response.status === 503)
          && payload && payload.components && typeof payload.components === 'object') return payload;
      throw new ApiError(messageOf(payload, response.status), response.status, codeOf(payload));
    });
  });
}

function report(error) {
  if (error && !error.handled) showError(error.message || '请求失败');
}

function stale(gen) { return gen !== generation; }

/* ---------- 轮询 ---------- */

function readAuto(name) {
  try {
    var value = localStorage.getItem('sentinel.ui.' + name);
    if (value === '0') return false;
    if (value === '1') return true;
  } catch (ignore) { /* 隐私模式没有本地存储 */ }
  return true;
}

function writeAuto(name, on) {
  try { localStorage.setItem('sentinel.ui.' + name, on ? '1' : '0'); } catch (ignore) { /* 忽略 */ }
}

function formatClock(date) {
  return pad(date.getHours()) + ':' + pad(date.getMinutes()) + ':' + pad(date.getSeconds());
}

function markUpdated(node) {
  if (node) node.textContent = '更新于 ' + formatClock(new Date());
}

function autoToggle(prefName, onChange) {
  var box = el('input', { type: 'checkbox' });
  box.checked = readAuto(prefName);
  var label = add(el('label', { class: 'checks auto-toggle' }), box, text('span', '自动刷新'));
  on(box, 'change', function () {
    writeAuto(prefName, box.checked);
    if (onChange) onChange(box.checked);
  });
  return { label: label, box: box };
}

function stopPollers() {
  var list = pollers.slice();
  pollers = [];
  list.forEach(function (item) { item.stop(); });
}

/* 当前路由未变、页面可见时每 intervalMs 调一次。隐藏时停表，重新可见立刻补一次。
   上一次还没回来就跳过这一拍，避免重叠。连续失败则间隔翻倍，最多 60 秒，成功后恢复。 */
function startPoll(gen, intervalMs, refresh) {
  var state = { stopped: false, timer: null, inFlight: false, delay: intervalMs, base: intervalMs };
  function stop() {
    if (state.stopped) return;
    state.stopped = true;
    if (state.timer != null) {
      clearTimeout(state.timer);
      state.timer = null;
    }
    document.removeEventListener('visibilitychange', onVis);
    var index = pollers.indexOf(handle);
    if (index >= 0) pollers.splice(index, 1);
  }
  function schedule() {
    if (state.stopped || stale(gen)) { stop(); return; }
    if (state.timer != null) return;
    if (document.visibilityState !== 'visible') return;
    state.timer = setTimeout(onTick, state.delay);
  }
  function onTick() {
    state.timer = null;
    if (state.stopped || stale(gen)) { stop(); return; }
    if (document.visibilityState !== 'visible') return;
    if (state.inFlight) return;
    run();
  }
  function run() {
    if (state.stopped || stale(gen) || state.inFlight) return;
    if (document.visibilityState !== 'visible') return;
    state.inFlight = true;
    Promise.resolve().then(refresh).then(function () {
      state.delay = state.base;
    }, function () {
      state.delay = Math.min(60000, state.delay * 2);
    }).then(function () {
      state.inFlight = false;
      if (state.stopped || stale(gen)) { stop(); return; }
      schedule();
    });
  }
  function onVis() {
    if (state.stopped || stale(gen)) { stop(); return; }
    if (state.timer != null) {
      clearTimeout(state.timer);
      state.timer = null;
    }
    if (document.visibilityState !== 'visible') return;
    if (!state.inFlight) run();
  }
  var handle = { stop: stop };
  document.addEventListener('visibilitychange', onVis);
  pollers.push(handle);
  schedule();
  return handle;
}

function captureFocus(scope) {
  var active = document.activeElement;
  if (!active || !scope || !scope.contains(active)) return null;
  var action = active.getAttribute('data-action');
  if (!action || !FOCUS_ACTIONS[action]) return null;
  var row = active.closest('[data-id]');
  var id = row ? row.getAttribute('data-id') || '' : '';
  if (id && !/^\d+$/.test(id) && id !== 'pager') return null;
  return { id: id, action: action };
}

function restoreFocus(scope, saved) {
  if (!saved || !scope || !FOCUS_ACTIONS[saved.action]) return;
  if (saved.id && !/^\d+$/.test(saved.id) && saved.id !== 'pager') return;
  var selector = '[data-action="' + saved.action + '"]';
  var target = null;
  if (saved.id) {
    var host = scope.getAttribute && scope.getAttribute('data-id') === saved.id ? scope : null;
    if (!host) {
      var rows = scope.querySelectorAll('[data-id]');
      for (var i = 0; i < rows.length; i += 1) {
        if (rows[i].getAttribute('data-id') === saved.id) { host = rows[i]; break; }
      }
    }
    if (host) target = host.getAttribute('data-action') === saved.action ? host : host.querySelector(selector);
  }
  if (target && typeof target.focus === 'function') target.focus();
}

function repaintIfChanged(node, stampSlot, stamp, paint) {
  if (stampSlot.value === stamp) return false;
  var x = window.scrollX;
  var y = window.scrollY;
  var wrap = node.closest('.table-wrap');
  var left = wrap ? wrap.scrollLeft : 0;
  var saved = captureFocus(node);
  paint();
  stampSlot.value = stamp;
  if (wrap) wrap.scrollLeft = left;
  window.scrollTo(x, y);
  restoreFocus(node, saved);
  return true;
}

/* ---------- 路由与计时 ---------- */

function disarm(name) {
  clearTimeout(clocks[name]);
  clocks[name] = null;
}

function disarmAll() {
  Object.keys(clocks).forEach(disarm);
  stopPollers();
  if (closeDialog) closeDialog();
}

function arm(name, ms, fn) {
  disarm(name);
  var id = setTimeout(function () {
    if (clocks[name] === id) clocks[name] = null;
    Promise.resolve().then(fn).catch(report);
  }, ms);
  clocks[name] = id;
}

function beginRoute() {
  generation += 1;
  ui.applyQuery = null;
  disarmAll();
  return generation;
}

function parseRoute() {
  var raw = location.hash || '#/';
  var q = raw.indexOf('?');
  var path = q === -1 ? raw : raw.slice(0, q);
  var query = new URLSearchParams(q === -1 ? '' : raw.slice(q + 1));
  var matched = path.match(/^#\/(tasks|environments)\/(\d+)$/);
  if (matched) return { name: matched[1] === 'tasks' ? 'task' : 'environment', id: matched[2], query: query };
  if (path === '#/' || path === '#' || path === '') return { name: 'home', query: query };
  if ({ '#/login': 1, '#/tasks': 1, '#/environments': 1, '#/metrics': 1 }[path]) {
    return { name: path.slice(2), query: query };
  }
  return { name: 'missing', query: query };
}

function routeKey(route) {
  return route.name + (route.id ? ':' + route.id : '');
}

function sameHash(left, right) {
  if (left === right) return true;
  var a = left || '';
  var b = right || '';
  if (a.split('?')[0] !== b.split('?')[0]) return false;
  var qa = new URLSearchParams(a.indexOf('?') === -1 ? '' : a.slice(a.indexOf('?') + 1));
  var qb = new URLSearchParams(b.indexOf('?') === -1 ? '' : b.slice(b.indexOf('?') + 1));
  var keys = ['status', 'page', 'size'];
  for (var i = 0; i < keys.length; i += 1) {
    if ((qa.get(keys[i]) || '') !== (qb.get(keys[i]) || '')) return false;
  }
  return true;
}

function onRoute() {
  var route = parseRoute();
  var authed = !!token();
  if (route.name === 'home') { location.hash = authed ? '#/tasks' : '#/login'; return; }
  if (route.name !== 'login' && !authed) { location.hash = '#/login'; return; }
  if (route.name === 'login' && authed) { location.hash = '#/tasks'; return; }
  if (hashEcho) {
    var echoed = hashEcho;
    hashEcho = '';
    if (sameHash(location.hash, echoed)) return;
  }
  if (rendering) return;
  var key = routeKey(route);
  if (key === mountedKey && ui.applyQuery) {
    syncNav();
    ui.applyQuery(route);
    return;
  }
  rendering = true;
  try {
    render(route, beginRoute());
    mountedKey = key;
  } finally {
    rendering = false;
  }
}

function syncNav() {
  var path = (location.hash || '').split('?')[0];
  ui.side.querySelectorAll('a[data-nav]').forEach(function (link) {
    var href = link.getAttribute('href');
    var on = path === href || path.indexOf(href + '/') === 0;
    link.classList.toggle('is-active', on);
    if (on) link.setAttribute('aria-current', 'page');
    else link.removeAttribute('aria-current');
  });
}

function paintUser() {
  if (!ui.user) return;
  if (!currentUser) { ui.user.textContent = '未登录'; return; }
  var role = roleLabel(currentUser.role);
  ui.user.textContent = (currentUser.username || '') + (role ? ' · ' + role : '');
}

function refreshUser(gen) {
  api('/api/auth/me').then(function (res) {
    if (stale(gen)) return;
    currentUser = res.data || null;
    paintUser();
  }).catch(function (error) { if (!stale(gen)) report(error); });
}

/* ---------- 确认框 ---------- */

function confirmDialog(message, confirmLabel) {
  return new Promise(function (resolve) {
    var settled = false;
    var overlay = el('div', { class: 'modal-backdrop' });
    var dialog = el('div', { class: 'modal', role: 'dialog', 'aria-modal': 'true' });
    var actions = el('div', { class: 'modal-actions' });
    function finish(value) {
      if (settled) return;
      settled = true;
      if (closeDialog === finish) closeDialog = null;
      document.removeEventListener('keydown', onKey);
      overlay.remove();
      resolve(value);
    }
    function onKey(event) { if (event.key === 'Escape') finish(false); }
    closeDialog = function () { finish(false); };
    add(dialog, text('h2', '请确认'), text('p', message), add(actions,
      btn('取消', 'btn', function () { finish(false); }),
      btn(confirmLabel || '确认', 'btn btn-danger', function () { finish(true); })));
    add(overlay, dialog);
    on(dialog, 'click', function (event) { event.stopPropagation(); });
    on(overlay, 'click', function () { finish(false); });
    document.addEventListener('keydown', onKey);
    document.body.appendChild(overlay);
    actions.firstChild.focus();
  });
}

function withAction(work) {
  if (actionBusy) return Promise.resolve(null);
  actionBusy = true;
  return Promise.resolve().then(work).then(function (value) {
    actionBusy = false;
    return value;
  }, function (error) {
    actionBusy = false;
    throw error;
  });
}

function runAction(work, gen, reload, okMessage) {
  withAction(function () {
    return work().then(function (result) {
      if (result == null || stale(gen)) return null;
      showOk(okMessage);
      return reload();
    });
  }).catch(function (error) { if (!stale(gen)) report(error); });
}

/* ---------- 任务 ---------- */

function knownTaskStatus(status) {
  for (var i = 0; i < TASK_STATUSES.length; i += 1) {
    if (TASK_STATUSES[i][0] === status) return true;
  }
  return false;
}

function readTaskQuery(query) {
  var status = query && query.get ? (query.get('status') || '') : '';
  if (!knownTaskStatus(status)) status = '';
  var size = Number(query && query.get ? query.get('size') : NaN);
  if (PAGE_SIZES.indexOf(size) < 0) size = 20;
  var page = Number(query && query.get ? query.get('page') : NaN);
  if (!isFinite(page) || page < 1) page = 1;
  return { status: status, size: size, page: page - 1 };
}

function taskListHash(state) {
  var params = new URLSearchParams();
  if (state.status) params.set('status', state.status);
  if (state.page > 0) params.set('page', String(state.page + 1));
  if (state.size !== 20) params.set('size', String(state.size));
  var query = params.toString();
  return '#/tasks' + (query ? '?' + query : '');
}

function writeTaskHash(state) {
  var next = taskListHash(state);
  if (sameHash(location.hash || '#/', next)) return;
  hashEcho = next;
  location.hash = next;
}

function renderTasks(gen) {
  setHeading('任务');
  var state = readTaskQuery(parseRoute().query);
  state.testType = '';
  var createForm = buildCreateForm(gen, function () { return reload(false); });
  var reload = function (fromTimer) { return loadTasks(gen, state, hosts, fromTimer); };
  var status = selectBox('status', [['', '全部状态']].concat(TASK_STATUSES), state.status);
  var testType = selectBox('testType', [['', '全部类型']].concat(TEST_TYPES), '');
  var built = dataTable(['ID', '任务编码', '服务', '测试类型', '状态', '优先级', '创建时间', '环境', '操作']);
  built.table.classList.add('wide-table');
  var pager = el('div', { class: 'pager' });
  pager.setAttribute('data-id', 'pager');
  var updated = text('span', '', { class: 'hint' });
  var toggle = autoToggle('tasks.autoRefresh');
  var hosts = { body: built.body, pager: pager, updated: updated, rowStamp: { value: null }, pageStamp: { value: null } };
  var listPoll = null;
  function stopListPoll() {
    if (listPoll) { listPoll.stop(); listPoll = null; }
  }
  function startListPoll() {
    stopListPoll();
    if (!toggle.box.checked) return;
    listPoll = startPoll(gen, 5000, function () {
      if (!toggle.box.checked) return Promise.resolve();
      if (createForm.isBusy && createForm.isBusy()) return Promise.resolve();
      return reload(true);
    });
  }
  function reset(control, key) {
    state[key] = control.value;
    state.page = 0;
    writeTaskHash(state);
    reload(false);
  }
  on(toggle.box, 'change', function () {
    if (toggle.box.checked) {
      reload(false);
      startListPoll();
    } else stopListPoll();
  });
  ui.applyQuery = function (route) {
    var next = readTaskQuery(route.query);
    var same = next.page === state.page && next.size === state.size && next.status === state.status;
    state.page = next.page;
    state.size = next.size;
    state.status = next.status;
    if (status.value !== state.status) status.value = state.status;
    if (!same) reload(false);
  };
  var filters = add(el('form', { class: 'card toolbar' }),
    field('状态', on(status, 'change', function () { reset(status, 'status'); })),
    field('测试类型', on(testType, 'change', function () { reset(testType, 'testType'); })),
    add(el('div', { class: 'toolbar-check' }), toggle.label, updated),
    btn('刷新', 'btn', function () { reload(false); }));
  on(filters, 'submit', function (event) { event.preventDefault(); reload(false); });
  add(ui.view, createForm, filters, add(el('div', { class: 'table-wrap card' }), built.table), pager);
  writeTaskHash(state);
  reload(false).then(function () { if (!stale(gen)) startListPoll(); });
}

function buildCreateForm(gen, reload) {
  var taskCode = el('input', { name: 'taskCode', maxlength: '64', autocomplete: 'off' });
  var serviceId = el('input', { name: 'serviceId', maxlength: '64', autocomplete: 'off' });
  var testType = selectBox('testType', TEST_TYPES, 'UNIT_TEST');
  var priority = el('input', { name: 'priority', type: 'number', min: '1', max: '10', step: '1', value: '5' });
  var template = selectBox('template', [['', '不使用模板']], '');
  var templateHint = text('p', '', { class: 'hint span-all' });
  var depText = el('input', { name: 'depText', placeholder: '例如 mysql:8.0，逗号分隔；与模板同种类时以这里为准', autocomplete: 'off' });
  var checks = el('div', { class: 'checks' });
  var versionHint = text('p', '正在加载中间件版本…', { class: 'hint span-all' });
  var submit = text('button', '创建任务', { type: 'submit', class: 'btn btn-primary' });
  var form = add(el('form', { class: 'card' }), text('h2', '创建任务'), add(el('div', { class: 'form-grid' }),
    field('任务编码', taskCode), field('服务标识', serviceId), field('测试类型', testType),
    field('优先级（1 到 10）', priority),
    field('环境模板', template, 'field span-all'),
    templateHint,
    add(el('div', { class: 'field span-all' }), text('span', '依赖'), checks),
    add(el('div', { class: 'field span-all' }), text('span', '可用版本'), versionHint),
    field('其他依赖', depText, 'field span-all'), submit));
  var dirty = false;
  on(form, 'input', function () { dirty = true; });
  on(form, 'change', function () { dirty = true; });
  form.isBusy = function () {
    var active = document.activeElement;
    if (active && form.contains(active)) return true;
    return dirty;
  };
  loadCreateCatalog(gen, template, checks, versionHint, templateHint);
  on(form, 'submit', function (event) {
    event.preventDefault();
    var code = taskCode.value.trim();
    var service = serviceId.value.trim();
    if (!code || !service) { showError('请填写任务编码和服务标识'); return; }
    var raw = priority.value.trim();
    var level = Number(raw || '5');
    if (!raw || !isFinite(level) || Math.round(level) !== level || level < 1 || level > 10) {
      showError('优先级必须是 1 到 10 的整数');
      return;
    }
    var payload = {
      taskCode: code,
      serviceId: service,
      testType: testType.value,
      dependencies: collectDeps(form),
      priority: level
    };
    if (template.value) payload.template = template.value;
    submit.disabled = true;
    api('/api/tasks', { method: 'POST', body: payload }).then(function () {
      if (stale(gen)) return;
      taskCode.value = '';
      depText.value = '';
      template.selectedIndex = 0;
      form.querySelectorAll('input[name="dep"]').forEach(function (input) { input.checked = false; });
      form.querySelectorAll('select[name="depVersion"]').forEach(function (select) {
        var fallback = select.getAttribute('data-default');
        if (fallback) select.value = fallback;
      });
      dirty = false;
      showOk('任务已创建');
      return reload();
    }).catch(function (error) { if (!stale(gen)) report(error); })
      .then(function () { submit.disabled = false; });
  });
  return form;
}

function loadCreateCatalog(gen, templateSelect, checks, versionHint, templateHint) {
  api('/api/catalog/templates').then(function (res) {
    if (stale(gen)) return;
    var rows = res.data && Array.isArray(res.data.templates) ? res.data.templates : [];
    rows.forEach(function (item) {
      if (!item || typeof item.name !== 'string' || !item.name.trim()) return;
      var name = item.name.trim();
      var display = typeof item.displayName === 'string' && item.displayName.trim() ? item.displayName.trim() : name;
      var deps = Array.isArray(item.dependencies) ? item.dependencies.filter(function (dep) {
        return dep != null && String(dep).trim() !== '';
      }).map(function (dep) { return String(dep); }) : [];
      var label = display + '（' + name + '）';
      if (deps.length) label += '：' + deps.join('、');
      templateSelect.appendChild(text('option', label, { value: name }));
    });
    if (!rows.length) templateHint.textContent = '没有可用模板';
  }).catch(function () {
    if (!stale(gen)) templateHint.textContent = '模板列表加载失败，仍可手写依赖';
  });

  api('/api/catalog/middleware').then(function (res) {
    if (stale(gen)) return;
    var kinds = res.data && Array.isArray(res.data.kinds) ? res.data.kinds : [];
    var boxes = [];
    var lines = [];
    kinds.forEach(function (kind) {
      if (!kind || typeof kind.kind !== 'string' || !kind.kind.trim()) return;
      boxes.push(depChoice(kind));
      var line = formatKindHint(kind);
      if (line) lines.push(line);
    });
    if (boxes.length) checks.replaceChildren.apply(checks, boxes);
    versionHint.textContent = lines.length ? lines.join('；') : '目录里没有中间件';
  }).catch(function () {
    if (!stale(gen)) versionHint.textContent = '中间件版本加载失败，请在下方手写依赖';
  });
}

function depChoice(kind) {
  var name = kind.kind.trim();
  var box = el('input', { type: 'checkbox', name: 'dep', value: name });
  var versions = Array.isArray(kind.versions) ? kind.versions : [];
  var fallback = kind.defaultVersion == null ? '' : String(kind.defaultVersion).trim();
  var ids = [];
  versions.forEach(function (version) {
    if (!version || version.version == null || String(version.version).trim() === '') return;
    ids.push(String(version.version).trim());
  });
  var preset = '';
  for (var i = 0; i < ids.length; i += 1) {
    if (ids[i] === fallback) { preset = ids[i]; break; }
  }
  if (!preset && ids.length) preset = ids[0];
  var choice = add(el('div', { class: 'dep-choice' }), add(el('label'), box, text('span', name)));
  if (!ids.length) return choice;
  var options = ids.map(function (id) {
    return [id, id === fallback ? id + '（默认）' : id];
  });
  var select = selectBox('depVersion', options, preset);
  select.setAttribute('aria-label', name + ' 版本');
  select.setAttribute('data-default', preset);
  choice.appendChild(select);
  return choice;
}

function formatKindHint(kind) {
  if (!kind || typeof kind.kind !== 'string' || !kind.kind.trim()) return '';
  var versions = Array.isArray(kind.versions) ? kind.versions : [];
  var parts = [];
  versions.forEach(function (version) {
    if (!version || version.version == null || String(version.version).trim() === '') return;
    var piece = String(version.version);
    var extras = [];
    if (Array.isArray(version.aliases) && version.aliases.length) {
      var aliases = [];
      version.aliases.forEach(function (alias) {
        if (alias != null && String(alias).trim() !== '') aliases.push(String(alias));
      });
      if (aliases.length) extras.push('别名 ' + aliases.join('、'));
    }
    if (version.image) extras.push(String(version.image));
    if (version.containerPort != null && version.containerPort !== '') extras.push('端口 ' + version.containerPort);
    if (extras.length) piece += '（' + extras.join('，') + '）';
    parts.push(piece);
  });
  var line = kind.kind.trim() + ' 默认 ' + (kind.defaultVersion == null || kind.defaultVersion === '' ? '—' : String(kind.defaultVersion));
  if (parts.length) line += '：' + parts.join('、');
  return line;
}

function collectDeps(form) {
  var seen = Object.create(null);
  var list = [];
  function addDep(raw) {
    var tokenText = String(raw || '').trim();
    var key = tokenText.toLowerCase();
    if (!tokenText || seen[key]) return;
    seen[key] = true;
    list.push(tokenText);
  }
  form.querySelectorAll('input[name="dep"]:checked').forEach(function (input) {
    var token = input.value;
    var choice = input.closest('.dep-choice');
    var select = choice ? choice.querySelector('select') : null;
    if (select && String(select.value || '').trim() !== '') token = input.value + ':' + select.value;
    addDep(token);
  });
  form.querySelector('input[name="depText"]').value.split(/[,，;]+/).forEach(addDep);
  return list;
}

function taskRowStamp(task) {
  if (!task) return null;
  return [task.id, task.taskCode, task.serviceId, task.testType, task.status, task.priority,
    task.queuePosition, task.environmentId, task.createdAt, task.errorMessage];
}

function loadTasks(gen, state, hosts, fromTimer, hops) {
  var seq = ++taskSeq;
  var step = hops || 0;
  var params = new URLSearchParams({ page: String(state.page), size: String(state.size) });
  if (state.status) params.set('status', state.status);
  if (state.testType) params.set('testType', state.testType);
  var reload = function () { return loadTasks(gen, state, hosts, false); };
  return api('/api/tasks?' + params.toString()).then(function (res) {
    if (stale(gen) || seq !== taskSeq) return;
    var page = res.data || {};
    var rows = Array.isArray(page.content) ? page.content : [];
    var total = Number(page.totalElements || 0);
    var size = state.size > 0 ? state.size : 20;
    var last = total <= 0 ? 0 : Math.ceil(total / size) - 1;
    if (step < 3 && state.page > last) {
      state.page = last;
      writeTaskHash(state);
      return loadTasks(gen, state, hosts, fromTimer, step + 1);
    }
    if (step < 3 && !rows.length && state.page > 0) {
      state.page -= 1;
      writeTaskHash(state);
      return loadTasks(gen, state, hosts, fromTimer, step + 1);
    }
    var rowStamp = JSON.stringify(rows.map(taskRowStamp));
    var pageStamp = JSON.stringify([state.page, state.size, Number(page.totalPages || 0), total]);
    repaintIfChanged(hosts.body, hosts.rowStamp, rowStamp, function () {
      paintTaskRows(hosts.body, rows, gen, reload);
    });
    repaintIfChanged(hosts.pager, hosts.pageStamp, pageStamp, function () {
      paintPager(hosts.pager, page, state, function () {
        writeTaskHash(state);
        return reload();
      });
    });
    markUpdated(hosts.updated);
  }).catch(function (error) {
    if (stale(gen) || seq !== taskSeq) return;
    report(error);
    if (fromTimer) throw error;
  });
}

function paintTaskRows(body, rows, gen, reload) {
  if (!rows.length) { emptyRow(body, 9, '暂无任务'); return; }
  body.replaceChildren.apply(body, rows.map(function (task) { return taskRow(task, gen, reload); }));
}

function queuePositionText(task) {
  if (!task || task.status !== 'QUEUED' || task.queuePosition == null || task.queuePosition === '') return '';
  var position = Number(task.queuePosition);
  if (!isFinite(position) || position < 1) return '';
  return '第 ' + Math.floor(position) + ' 位';
}

/* QUEUED 没有排队位置时是已出队、正在创建环境。 */
function taskStatusBadge(task) {
  if (task && task.status === 'QUEUED' && !queuePositionText(task)) {
    return badge([['QUEUED', '供给中']], 'QUEUED');
  }
  return badge(TASK_STATUSES, task && task.status);
}

function taskRow(task, gen, reload) {
  var statusCell = add(el('td'), taskStatusBadge(task));
  var queueText = queuePositionText(task);
  if (queueText) statusCell.appendChild(text('div', queueText, { class: 'hint' }));
  var row = el('tr');
  if (task && task.id != null && /^\d+$/.test(String(task.id))) row.setAttribute('data-id', String(task.id));
  return add(row,
    text('td', task.id == null ? '—' : String(task.id), { class: 'num' }),
    clipCell(task.taskCode), clipCell(task.serviceId),
    text('td', labelOf(TEST_TYPES, task.testType)), statusCell,
    text('td', task.priority == null ? '—' : String(task.priority), { class: 'num' }),
    text('td', formatTime(task.createdAt), { class: 'mono' }),
    add(el('td'), idLink('environments', task.environmentId)),
    add(el('td', { class: 'col-actions' }), taskActions(task, gen, reload) || text('span', '—')));
}

function taskActions(task, gen, reload) {
  var box = el('div', { class: 'actions' });
  var name = task.taskCode || String(task.id);
  if (!TERMINAL_TASK[task.status]) {
    var cancel = btn('取消', 'btn', function () {
      runAction(function () {
        return confirmDialog('取消任务「' + name + '」？', '确认取消').then(function (ok) {
          return ok ? api('/api/tasks/' + encodeURIComponent(task.id), { method: 'DELETE' }) : null;
        });
      }, gen, reload, '任务已取消');
    });
    cancel.setAttribute('data-action', 'cancel');
    box.appendChild(cancel);
  }
  if (task.status === 'RUNNING') {
    var okBtn = btn('回报成功', 'btn', function () {
      runAction(function () { return confirmStatus(task, 'SUCCESS', '成功'); }, gen, reload, '已回报成功');
    });
    var badBtn = btn('回报失败', 'btn btn-danger', function () {
      runAction(function () { return confirmStatus(task, 'FAILED', '失败'); }, gen, reload, '已回报失败');
    });
    okBtn.setAttribute('data-action', 'success');
    badBtn.setAttribute('data-action', 'fail');
    box.appendChild(okBtn);
    box.appendChild(badBtn);
  }
  return box.childNodes.length ? box : null;
}

function confirmStatus(task, status, label) {
  return confirmDialog('将任务「' + (task.taskCode || task.id) + '」回报为' + label + '？', '确认').then(function (ok) {
    if (!ok) return null;
    return api('/api/tasks/' + encodeURIComponent(task.id) + '/status?status=' + status, { method: 'PUT' });
  });
}

function paintPager(host, page, state, reload) {
  var pages = Number(page.totalPages || 0);
  var total = Number(page.totalElements || 0);
  var current = pages ? state.page + 1 : 0;
  var prev = btn('上一页', 'btn', function () {
    if (state.page > 0) { state.page -= 1; reload(); }
  });
  var next = btn('下一页', 'btn', function () {
    if (state.page + 1 < pages) { state.page += 1; reload(); }
  });
  var size = selectBox('size', [['20', '20 条/页'], ['50', '50 条/页'], ['100', '100 条/页']], String(state.size));
  prev.disabled = state.page <= 0;
  next.disabled = pages === 0 || state.page + 1 >= pages;
  prev.setAttribute('data-action', 'prev');
  next.setAttribute('data-action', 'next');
  size.setAttribute('data-action', 'size');
  on(size, 'change', function () {
    var picked = Number(size.value);
    state.size = PAGE_SIZES.indexOf(picked) < 0 ? 20 : picked;
    state.page = 0;
    reload();
  });
  host.replaceChildren(
    text('span', '第 ' + current + ' / ' + pages + ' 页，共 ' + total + ' 条'),
    prev, next, size);
}

/* ---------- 任务详情、日志、诊断 ---------- */

function renderTask(gen, id) {
  setHeading('任务详情');
  var meta = el('div', { class: 'card' });
  var side = el('div');
  var logs = buildLogs(gen, id);
  var diagnosis = buildDiagnosis(gen, id);
  var seenActive = false;
  var statusPoll = null;
  var detailStamp = '';
  var envStamp = '';

  function stopStatus() {
    if (statusPoll) { statusPoll.stop(); statusPoll = null; }
  }

  function applyTask(task) {
    var stamp = JSON.stringify(task || {});
    var active = !!(task && ACTIVE_TASK[task.status]);
    var terminal = !!(task && TERMINAL_TASK[task.status]);
    var becameTerminal = seenActive && terminal;
    if (stamp !== detailStamp) {
      detailStamp = stamp;
      paintTaskDetail(meta, task || {}, gen, function () { return refresh(false); });
    }
    logs.noteStatus(task && task.status);
    if (active) seenActive = true;
    if (becameTerminal) {
      stopStatus();
      diagnosis.load();
      logs.finish();
    }
    if (!task || task.environmentId == null || task.environmentId === '') {
      if (envStamp !== 'none') {
        envStamp = 'none';
        side.replaceChildren(text('div', '尚未关联环境', { class: 'card muted' }));
      }
      return task;
    }
    return api('/api/environments/' + encodeURIComponent(task.environmentId)).then(function (envRes) {
      if (stale(gen)) return task;
      var env = envRes.data || {};
      var nextStamp = JSON.stringify(env);
      if (nextStamp !== envStamp) {
        envStamp = nextStamp;
        side.replaceChildren(environmentSummary(env, true));
      }
      return task;
    }).catch(function (error) {
      if (!stale(gen)) report(error);
      return task;
    });
  }

  function refresh(fromTimer) {
    return api('/api/tasks/' + encodeURIComponent(id)).then(function (res) {
      if (stale(gen)) return null;
      return applyTask(res.data || {});
    }).catch(function (error) {
      if (stale(gen)) return null;
      report(error);
      if (!fromTimer) meta.replaceChildren(text('p', '任务加载失败'));
      if (fromTimer) throw error;
      return null;
    });
  }

  add(ui.view, routeLink('#/tasks', '返回任务列表'),
    add(el('div', { class: 'split' }), meta, side), logs.panel, diagnosis.panel);
  logs.load();
  refresh(false).then(function (task) {
    if (stale(gen)) return;
    // 进行中的任务还没有诊断。先查会 404，浏览器会把它记成控制台错误。
    if (task && ACTIVE_TASK[task.status]) {
      diagnosis.hold();
      statusPoll = startPoll(gen, 3000, function () { return refresh(true); });
      return;
    }
    diagnosis.load();
  });
}

function paintTaskDetail(host, task, gen, reload) {
  var deps = Array.isArray(task.dependencies) ? task.dependencies.filter(function (item) {
    return item != null && String(item).trim() !== '';
  }) : [];
  var pairs = [['ID', task.id == null ? '—' : String(task.id)]];
  if (task.serviceId != null && String(task.serviceId).trim() !== '') pairs.push(['服务', String(task.serviceId)]);
  if (task.testType) pairs.push(['测试类型', labelOf(TEST_TYPES, task.testType)]);
  pairs.push(['状态', taskStatusBadge(task)]);
  var queueText = queuePositionText(task);
  if (queueText) pairs.push(['排队位置', queueText]);
  if (task.priority != null && task.priority !== '') pairs.push(['优先级', String(task.priority)]);
  if (deps.length) pairs.push(['依赖', deps.join('、')]);
  if (task.environmentId != null && task.environmentId !== '') {
    pairs.push(['环境', idLink('environments', task.environmentId)]);
  }
  pairs.push(
    ['创建时间', formatTime(task.createdAt)],
    ['开始时间', formatTime(task.startedAt)],
    ['完成时间', formatTime(task.completedAt)]
  );
  var errorText = task.errorMessage == null ? '' : String(task.errorMessage).trim();
  if (errorText) pairs.push(['错误信息', text('span', errorText, { class: 'error-text' })]);
  host.replaceChildren(text('h2', task.taskCode || ('任务 ' + task.id)), facts(pairs));
  var actions = taskActions(task, gen, reload);
  if (actions) host.appendChild(actions);
}

function buildLogs(gen, id) {
  var tail = selectBox('tail', [['100', '最后 100 行'], ['500', '最后 500 行'], ['2000', '最后 2000 行']], '100');
  var liveBox = el('input', { type: 'checkbox' });
  liveBox.disabled = true;
  var liveLabel = add(el('label', { class: 'checks auto-toggle' }), liveBox, text('span', '实时'));
  var pauseBtn = btn('暂停', 'btn', function () { setLive(false); });
  pauseBtn.disabled = true;
  var meta = text('p', '', { class: 'hint' });
  var pre = text('pre', '加载中…', { class: 'logs' });
  var jump = btn('有新日志 ↓', 'btn jump-logs', function () {
    pre.scrollTop = pre.scrollHeight;
    jump.hidden = true;
  });
  jump.hidden = true;
  var lines = [];
  var cursor = null;
  var livePoll = null;
  var taskActive = false;
  var userOff = false;
  var chain = Promise.resolve();

  function nearBottom() {
    return pre.scrollHeight - pre.scrollTop - pre.clientHeight <= 16;
  }

  function splitLog(value) {
    if (value == null || value === '') return [];
    var parts = String(value).split('\n');
    if (parts.length && parts[parts.length - 1] === '') parts.pop();
    return parts;
  }

  function renderLines(stick) {
    var dropped = 0;
    if (lines.length > LOG_LINE_CAP) {
      dropped = lines.length - LOG_LINE_CAP;
      lines = lines.slice(lines.length - LOG_LINE_CAP);
    }
    var previousTop = pre.scrollTop;
    var previousHeight = pre.scrollHeight;
    pre.textContent = lines.length ? lines.join('\n') : '（无日志）';
    if (stick) {
      pre.scrollTop = pre.scrollHeight;
      jump.hidden = true;
    } else if (dropped > 0 && previousHeight > 0) {
      pre.scrollTop = Math.max(0, previousTop - (dropped / (dropped + lines.length)) * previousHeight);
    }
  }

  function enqueue(work) {
    var next = chain.then(function () {
      if (stale(gen)) return null;
      return work();
    }, function () {
      if (stale(gen)) return null;
      return work();
    });
    chain = next.then(function () { return null; }, function () { return null; });
    return next;
  }

  function logUrl(tailLines, since) {
    var params = new URLSearchParams();
    params.set('tail', String(tailLines));
    if (since) params.set('since', since);
    return '/api/tasks/' + encodeURIComponent(id) + '/logs?' + params.toString();
  }

  function showSnapshot(data) {
    lines = splitLog(data && data.logs);
    renderLines(true);
    cursor = data && data.nextSince ? String(data.nextSince) : null;
    meta.textContent = data && data.hasMore ? '还有更早的日志未显示' : '';
    jump.hidden = true;
  }

  function appendSnapshot(data) {
    var extra = splitLog(data && data.logs);
    if (lines.length && extra.length && extra[0].indexOf('--- container ') === 0) {
      var lastHeader = '';
      for (var i = lines.length - 1; i >= 0; i -= 1) {
        if (lines[i].indexOf('--- container ') === 0) { lastHeader = lines[i]; break; }
      }
      if (lastHeader === extra[0]) extra.shift();
    }
    if (!extra.length) return false;
    var stick = nearBottom();
    lines = lines.concat(extra);
    renderLines(stick);
    if (!stick) jump.hidden = false;
    return true;
  }

  function loadSnapshot() {
    return api(logUrl(tail.value, LOG_ORIGIN)).then(function (res) {
      if (stale(gen)) return;
      showSnapshot(res.data || {});
    });
  }

  function loadFollow() {
    return api(logUrl(LOG_LINE_CAP, cursor)).then(function (res) {
      if (stale(gen)) return;
      var data = res.data || {};
      if (!data.nextSince) return loadSnapshot();
      var added = appendSnapshot(data);
      cursor = String(data.nextSince);
      if (data.hasMore && added) meta.textContent = '新增日志较多，更早的部分可能未显示';
      else if (!data.hasMore && meta.textContent.indexOf('新增日志较多') === 0) meta.textContent = '';
    });
  }

  function pull(replace) {
    return enqueue(function () {
      if (replace || !cursor) return loadSnapshot();
      return loadFollow();
    });
  }

  function stopLive() {
    if (livePoll) { livePoll.stop(); livePoll = null; }
  }

  function startLive() {
    stopLive();
    if (!liveBox.checked || !taskActive) return;
    livePoll = startPoll(gen, 2000, function () {
      return pull(false).catch(function (error) {
        if (!stale(gen) && !(error && error.handled)) report(error);
        throw error;
      });
    });
  }

  function setLive(on) {
    userOff = !on;
    if (on && taskActive) {
      liveBox.checked = true;
      pauseBtn.disabled = false;
      if (!livePoll) startLive();
    } else {
      liveBox.checked = false;
      pauseBtn.disabled = true;
      stopLive();
    }
  }

  function load() {
    if (!lines.length) pre.textContent = '加载中…';
    return pull(true).catch(function (error) {
      if (stale(gen)) return;
      if (!lines.length) pre.textContent = '日志加载失败';
      report(error);
    });
  }

  function noteStatus(status) {
    var active = !!ACTIVE_TASK[status];
    taskActive = active;
    liveBox.disabled = !active;
    if (!active) return;
    if (!userOff && !liveBox.checked) liveBox.checked = true;
    pauseBtn.disabled = !liveBox.checked;
    if (liveBox.checked && !livePoll) startLive();
  }

  function finish() {
    stopLive();
    taskActive = false;
    liveBox.checked = false;
    liveBox.disabled = true;
    pauseBtn.disabled = true;
    return pull(false).catch(function (error) {
      if (!stale(gen)) report(error);
    });
  }

  on(liveBox, 'change', function () { setLive(liveBox.checked); });
  on(tail, 'change', load);
  on(pre, 'scroll', function () { if (nearBottom()) jump.hidden = true; });
  return {
    panel: add(el('section', { class: 'card' }), text('h2', '日志'),
      add(el('div', { class: 'log-toolbar' }), field('行数', tail), liveLabel, pauseBtn, btn('刷新日志', 'btn', load)),
      meta, pre, jump),
    load: load,
    noteStatus: noteStatus,
    finish: finish
  };
}

function buildDiagnosis(gen, id) {
  var result = text('div', '正在读取诊断…');
  var formHost = el('div');
  var panel = add(el('section', { class: 'card' }), text('h2', '诊断'), result, formHost);

  function paint(diag, outerCache) {
    var busy = diag && (diag.status === 'PENDING' || diag.status === 'PROCESSING');
    result.replaceChildren(diag ? diagnosisBody(diag, outerCache) : text('p', '尚未诊断', { class: 'muted' }));
    formHost.hidden = !!busy;
    if (!busy && !formHost.firstChild) formHost.appendChild(diagnosisForm());
    if (busy) arm('diagnosis', 3000, load);
    else disarm('diagnosis');
  }

  function diagnosisForm() {
    var log = el('textarea', { name: 'log', placeholder: '可选。留空则使用任务错误信息和容器日志', spellcheck: 'false' });
    var force = el('input', { type: 'checkbox', name: 'forceRefresh' });
    var submit = text('button', '请求诊断', { type: 'submit', class: 'btn btn-primary' });
    var form = add(el('form', { class: 'form-grid' }),
      field('自定义日志', log, 'field span-all'),
      add(el('label', { class: 'checks' }), force, document.createTextNode('忽略缓存，强制重新诊断')),
      submit);
    on(form, 'submit', function (event) {
      event.preventDefault();
      var body = {};
      if (log.value.trim()) body.log = log.value.trim();
      if (force.checked) body.forceRefresh = true;
      submit.disabled = true;
      var options = { method: 'POST' };
      if (Object.keys(body).length) options.body = body;
      api('/api/diagnosis/' + encodeURIComponent(id), options).then(function () {
        if (stale(gen)) return null;
        showOk('已提交诊断');
        return load();
      }).catch(function (error) { if (!stale(gen)) report(error); })
        .then(function () { submit.disabled = false; });
    });
    return form;
  }

  function hold() {
    disarm('diagnosis');
    result.textContent = '任务进行中，结束后自动刷新诊断';
    formHost.hidden = true;
  }

  function load() {
    disarm('diagnosis');
    result.textContent = '正在读取诊断…';
    return api('/api/diagnosis/' + encodeURIComponent(id)).then(function (res) {
      if (stale(gen)) return;
      var data = res.data || null;
      paint(data, data && typeof data.fromCache === 'boolean' ? data.fromCache : res.fromCache);
    }).catch(function (error) {
      if (stale(gen)) return;
      if (error.status === 404 || error.code === 4002) { paint(null, false); return; }
      result.replaceChildren(text('p', '诊断加载失败'));
      report(error);
    });
  }

  return { panel: panel, load: load, hold: hold };
}

function diagnosisBody(diag, outerCache) {
  var cached = typeof diag.fromCache === 'boolean' ? diag.fromCache : outerCache;
  var provider = diag.provider ? String(diag.provider) : '';
  var model = diag.model ? String(diag.model) : '';
  var box = add(el('div'), facts([
    ['状态', badge(DIAG_STATUSES, diag.status)],
    ['错误类型', labelOf(ERROR_TYPES, diag.errorType)],
    ['严重程度', diag.severity ? badge(SEVERITIES, diag.severity) : '—'],
    ['摘要', diag.summary], ['根因', diag.rootCause],
    ['置信度', formatConfidence(diag.confidence)],
    ['模型', provider && model ? provider + ' / ' + model : (provider || model || '—')],
    ['缓存', cached ? '是' : '否'], ['时间', formatTime(diag.createdAt)]
  ]));
  if (Array.isArray(diag.suggestions) && diag.suggestions.length) {
    var list = el('ol', { class: 'suggest' });
    diag.suggestions.forEach(function (item) { list.appendChild(text('li', item == null ? '' : String(item))); });
    add(box, add(el('div'), text('h3', '建议'), list));
  }
  if (diag.error) box.appendChild(callout('诊断失败', diag.error));
  if (diag.status === 'PENDING' || diag.status === 'PROCESSING') {
    box.appendChild(text('p', '正在诊断，每 3 秒刷新', { class: 'hint' }));
  }
  return box;
}

/* ---------- 环境 ---------- */

function linesBlock(className, rows) {
  var box = el('div', { class: className });
  if (!rows.length) { box.textContent = '—'; return box; }
  rows.forEach(function (pair) {
    add(box, add(el('div'), text('span', pair[0], { class: 'k' }), pair[1] instanceof Node ? pair[1] : text('span', pair[1], { class: 'mono' })));
  });
  return box;
}

function portLines(env) {
  var host = typeof env.accessUrl === 'string' ? env.accessUrl.trim() : '';
  var mappings = env.portMappings && typeof env.portMappings === 'object' ? env.portMappings : null;
  if (!mappings) return [];
  return Object.keys(mappings).map(function (name) {
    var port = mappings[name] == null ? '' : String(mappings[name]);
    return [String(name), host ? host + ':' + port : port];
  });
}

/* 释放后端口映射还在库里。列表用破折号；详情变淡并注明已释放。 */
function portsBlock(env, place) {
  var source = env || {};
  var rows = portLines(source);
  if (source.status !== 'IDLE') return linesBlock('ports', rows);
  if (place !== 'detail') return text('span', '—');
  if (!rows.length) return text('div', '—（已释放）', { class: 'ports is-released' });
  return linesBlock('ports is-released', rows.map(function (pair) {
    return [pair[0], pair[1] + '（已释放）'];
  }));
}

function usageBlock(usage) {
  if (!usage || typeof usage !== 'object') return linesBlock('usage', []);
  return linesBlock('usage', [
    ['CPU', formatCpu(usage.cpu)],
    ['内存', formatMb(usage.memory)],
    ['采样', formatTime(usage.sampledAt)]
  ]);
}

function environmentSummary(env, linked) {
  var title = env.envCode || (env.id == null ? '环境' : '环境 ' + env.id);
  var code = linked ? idLink('environments', env.id, env.envCode || String(env.id)) : (env.envCode || '—');
  return add(el('section', { class: 'card' }), text('h2', linked ? '关联环境' : title), facts([
    ['编码', code],
    ['状态', badge(ENV_STATUSES, env.status)],
    ['任务', idLink('tasks', env.taskId)],
    ['端口', portsBlock(env, 'detail')],
    ['资源', usageBlock(env.resourceUsage)],
    ['过期时间', formatTime(env.expiresAt)],
    ['创建时间', formatTime(env.createdAt)]
  ]));
}

function envRowStamp(env) {
  if (!env) return null;
  return [env.id, env.envCode, env.taskId, env.status, env.expiresAt, env.portMappings, env.accessUrl, env.resourceUsage];
}

function renderEnvironments(gen) {
  setHeading('环境');
  var state = { status: '' };
  var status = selectBox('status', [['', '全部状态']].concat(ENV_STATUSES), '');
  var built = dataTable(['环境编码', '任务', '状态', '端口', '资源', '过期时间', '操作']);
  var meta = text('p', '', { class: 'hint' });
  var updated = text('span', '', { class: 'hint' });
  var toggle = autoToggle('environments.autoRefresh');
  var rowStamp = { value: null };
  var envPoll = null;
  function stopEnvPoll() {
    if (envPoll) { envPoll.stop(); envPoll = null; }
  }
  function load(fromTimer) {
    var params = new URLSearchParams();
    if (state.status) params.set('status', state.status);
    var suffix = params.toString();
    return api('/api/environments' + (suffix ? '?' + suffix : '')).then(function (res) {
      if (stale(gen)) return;
      var rows = Array.isArray(res.data) ? res.data : [];
      repaintIfChanged(built.body, rowStamp, JSON.stringify(rows.map(envRowStamp)), function () {
        paintEnvRows(built.body, rows, gen, function () { return load(false); });
      });
      meta.textContent = '共 ' + rows.length + ' 个环境';
      markUpdated(updated);
    }).catch(function (error) {
      if (stale(gen)) return;
      report(error);
      if (fromTimer) throw error;
    });
  }
  function startEnvPoll() {
    stopEnvPoll();
    if (!toggle.box.checked) return;
    envPoll = startPoll(gen, 10000, function () { return load(true); });
  }
  on(toggle.box, 'change', function () {
    if (toggle.box.checked) {
      load(false);
      startEnvPoll();
    } else stopEnvPoll();
  });
  var filters = add(el('form', { class: 'card toolbar' }),
    field('状态', on(status, 'change', function () { state.status = status.value; rowStamp.value = null; load(false); })),
    add(el('div', { class: 'toolbar-check' }), toggle.label, updated),
    btn('刷新', 'btn', function () { load(false); }));
  on(filters, 'submit', function (event) { event.preventDefault(); load(false); });
  add(ui.view, filters, meta, add(el('div', { class: 'table-wrap card' }), built.table));
  load(false).then(function () { if (!stale(gen)) startEnvPoll(); });
}

function paintEnvRows(body, rows, gen, reload) {
  if (!rows.length) { emptyRow(body, 7, '暂无环境'); return; }
  body.replaceChildren.apply(body, rows.map(function (env) {
    var row = el('tr');
    if (env && env.id != null && /^\d+$/.test(String(env.id))) row.setAttribute('data-id', String(env.id));
    return add(row,
      add(el('td'), idLink('environments', env.id, env.envCode || String(env.id))),
      add(el('td'), idLink('tasks', env.taskId)),
      add(el('td'), badge(ENV_STATUSES, env.status)),
      add(el('td'), portsBlock(env, 'list')),
      add(el('td'), usageBlock(env.resourceUsage)),
      text('td', formatTime(env.expiresAt), { class: 'mono' }),
      add(el('td', { class: 'col-actions' }), releaseButton(env, gen, reload)));
  }));
}

function releaseButton(env, gen, reload) {
  if (!RELEASABLE_ENV[env.status]) return text('span', '—');
  var button = btn('释放', 'btn btn-danger', function () { releaseEnv(env, gen, reload); });
  button.setAttribute('data-action', 'release');
  return button;
}

function releaseEnv(env, gen, reload) {
  confirmDialog('确认释放环境「' + (env.envCode || ('#' + env.id)) + '」？容器会停止并删除。', '确认释放').then(function (ok) {
    if (!ok) return null;
    return withAction(function () {
      return api('/api/environments/' + encodeURIComponent(env.id) + '/release', { method: 'POST' });
    });
  }).then(function (result) {
    if (result == null || stale(gen)) return;
    showOk('环境已释放');
    reload();
  }).catch(function (error) {
    if (stale(gen)) return;
    report(error);
    // 400 不改状态；503 已写成 ERROR。都重拉，徽章跟上服务端。
    if (error && (error.status === 400 || error.status === 503)) reload();
  });
}

function renderEnvironment(gen, id) {
  setHeading('环境详情');
  ui.view.replaceChildren(routeLink('#/environments', '返回环境列表'));
  var host = el('div');
  var pre = text('pre', '加载中…', { class: 'code' });
  add(ui.view, host, add(el('section', { class: 'card' }), text('h2', '编排内容'), pre));
  api('/api/environments/' + encodeURIComponent(id)).then(function (res) {
    if (stale(gen)) return;
    var env = res.data || {};
    var card = environmentSummary(env, false);
    var releaseDd = el('dd');
    releaseDd.appendChild(releaseButton(env, gen, function () { renderEnvironment(gen, id); }));
    var releaseList = card.querySelector('.facts');
    if (releaseList) add(releaseList, text('dt', '操作'), releaseDd);
    else card.appendChild(releaseDd);
    host.replaceChildren(card);
    pre.textContent = env.composeContent ? String(env.composeContent) : '（无编排内容）';
  }).catch(function (error) {
    if (stale(gen)) return;
    pre.textContent = '';
    report(error);
  });
}

/* ---------- 指标 ---------- */

function renderMetrics(gen) {
  setHeading('指标');
  var metrics = el('div', { class: 'metric-grid' });
  var healthBody = el('div', { class: 'actions' });
  var updated = text('span', '', { class: 'hint' });
  var toggle = autoToggle('metrics.autoRefresh');
  var metricStamp = '';
  var healthStamp = '';
  var metricPoll = null;

  function load(fromTimer) {
    return Promise.allSettled([api('/api/metrics'), fetchHealth()]).then(function (settled) {
      if (stale(gen)) return;
      var authFailed = settled.some(function (item) {
        return item.status === 'rejected' && item.reason && item.reason.handled;
      });
      if (settled[0].status === 'fulfilled') {
        var data = settled[0].value.data || {};
        var stamp = JSON.stringify(data);
        if (stamp !== metricStamp) {
          metricStamp = stamp;
          paintMetrics(metrics, data);
        }
      } else if (!authFailed) report(settled[0].reason);
      if (settled[1].status === 'fulfilled') {
        var health = JSON.stringify(settled[1].value);
        if (health !== healthStamp) {
          healthStamp = health;
          paintHealth(healthBody, settled[1].value);
        }
      } else if (!authFailed) report(settled[1].reason);
      if (!authFailed) markUpdated(updated);
      if (authFailed) return;
      var failed = settled.some(function (item) { return item.status === 'rejected'; });
      if (failed && fromTimer) throw settled[0].status === 'rejected' ? settled[0].reason : settled[1].reason;
    });
  }

  function stopMetricPoll() {
    if (metricPoll) { metricPoll.stop(); metricPoll = null; }
  }
  function startMetricPoll() {
    stopMetricPoll();
    if (!toggle.box.checked) return;
    metricPoll = startPoll(gen, 10000, function () { return load(true); });
  }
  on(toggle.box, 'change', function () {
    if (toggle.box.checked) {
      load(false);
      startMetricPoll();
    } else stopMetricPoll();
  });
  add(ui.view,
    add(el('div', { class: 'toolbar-check' }), toggle.label, updated, btn('刷新', 'btn', function () { load(false); })),
    metrics,
    add(el('section', { class: 'card' }), text('h2', '依赖健康'), healthBody));
  load(false).then(function () { if (!stale(gen)) startMetricPoll(); });
}

function metricCard(title, value, detail, percent) {
  var card = add(el('article', { class: 'card' }), text('h2', title),
    text('p', value, { class: 'metric-value' }), text('p', detail, { class: 'muted' }));
  if (finiteNumber(percent) != null && percent >= 0) {
    var bar = el('div', { class: 'meter-bar' });
    bar.style.width = Math.max(0, Math.min(100, percent)).toFixed(1) + '%';
    card.appendChild(add(el('div', { class: 'meter' }), bar));
  }
  return card;
}

function limitLine(used, max) {
  var amount = finiteNumber(used);
  var shown = amount == null ? '—' : String(Math.round(amount));
  var cap = finiteNumber(max);
  if (cap == null) return shown;
  if (cap <= 0) return shown + ' / 不限制';
  return shown + ' / ' + String(Math.round(cap));
}

function limitPercent(used, max) {
  var amount = finiteNumber(used);
  var cap = finiteNumber(max);
  if (amount == null || cap == null || cap <= 0) return null;
  return Math.max(0, Math.min(100, amount * 100 / cap));
}

function paintMetrics(host, data) {
  var cpu = data.cpu || {};
  var memory = data.memory || {};
  var disk = data.disk || {};
  var containers = data.containers || {};
  var limits = data.limits || {};
  var queued = finiteNumber(data.queuedTasks);
  host.replaceChildren(
    metricCard('CPU', formatPercent(cpu.usage), (cpu.cores == null ? '—' : String(cpu.cores)) + ' 核', cpu.usage),
    metricCard('内存', formatPercent(memory.usage), formatBytes(memory.used) + ' / ' + formatBytes(memory.total), memory.usage),
    metricCard('磁盘', formatPercent(disk.usage), formatBytes(disk.used) + ' / ' + formatBytes(disk.total), disk.usage),
    metricCard('容器', String(containers.running == null ? '—' : containers.running),
      '运行 ' + (containers.running || 0) + ' · 暂停 ' + (containers.paused || 0) + ' · 停止 ' + (containers.stopped || 0)),
    metricCard('活跃环境', limitLine(data.activeEnvironments, limits.maxActiveEnvironments),
      'CREATING、READY、BUSY、RETAINED、RELEASING', limitPercent(data.activeEnvironments, limits.maxActiveEnvironments)),
    metricCard('活跃容器', limitLine(data.activeContainers, limits.maxContainers),
      '占用环境中的容器', limitPercent(data.activeContainers, limits.maxContainers)),
    metricCard('排队任务', queued == null ? '—' : String(Math.round(queued)), '等待供给'));
  host.appendChild(text('p', '采样 ' + formatTime(data.timestamp) + '。上限为 0 表示不限制。', { class: 'hint span-all' }));
}

function healthBadge(label, value) {
  var up = value === 'UP';
  var state = up ? '正常' : (value === 'DOWN' ? '异常' : String(value || '未知'));
  return add(el('span', { class: up ? 'badge badge-success' : 'badge badge-failed' }),
    el('i', { class: 'lamp' }), document.createTextNode(label + ' ' + state));
}

function paintHealth(host, payload) {
  var parts = payload.components || {};
  host.replaceChildren(
    healthBadge('总体', payload.status), healthBadge('数据库', parts.db),
    healthBadge('Redis', parts.redis), healthBadge('Docker', parts.docker));
}

/* ---------- 登录与启动 ---------- */

function renderLogin() {
  setHeading('登录');
  var username = el('input', { name: 'username', autocomplete: 'username', maxlength: '64' });
  var password = el('input', { name: 'password', type: 'password', autocomplete: 'current-password' });
  var submit = text('button', '登录', { type: 'submit', class: 'btn btn-primary' });
  var card = add(el('form', { class: 'card login-card' }), text('h2', '登录'),
    field('用户名', username), field('密码', password), submit);
  on(card, 'submit', function (event) {
    event.preventDefault();
    var name = username.value.trim();
    if (!name || !password.value) { showError('请输入用户名和密码'); return; }
    submit.disabled = true;
    api('/api/auth/login', {
      method: 'POST', anonymous: true, body: { username: name, password: password.value }
    }).then(function (res) {
      if (!saveSession(res.data)) { showError('登录响应缺少访问令牌'); return; }
      currentUser = null;
      password.value = '';
      location.hash = '#/tasks';
    }).catch(report).then(function () { submit.disabled = false; });
  });
  ui.view.appendChild(add(el('div', { class: 'login-screen' }),
    add(el('section', { class: 'mast' }), text('p', 'SENTINEL WATCH', { class: 'eyebrow' }),
      text('h1', '哨兵'), text('p', '测试环境值班台。任务、容器和诊断都留在这面墙上。')),
    add(el('section', { class: 'login-panel' }), card)));
}

function renderMissing() {
  setHeading('未找到');
  ui.view.appendChild(add(el('section', { class: 'card' }),
    text('h2', '页面不存在'), text('p', '请从左侧进入任务、环境或指标。'),
    text('a', '返回任务', { href: '#/tasks', class: 'btn btn-primary' })));
}

function render(route, gen) {
  ui.shell.classList.toggle('is-login', route.name === 'login');
  syncNav();
  ui.view.replaceChildren();
  if (route.name === 'login') { paintUser(); renderLogin(); return; }
  refreshUser(gen);
  if (route.name === 'tasks') renderTasks(gen);
  else if (route.name === 'task') renderTask(gen, route.id);
  else if (route.name === 'environments') renderEnvironments(gen);
  else if (route.name === 'environment') renderEnvironment(gen, route.id);
  else if (route.name === 'metrics') renderMetrics(gen);
  else renderMissing();
}

function logout() {
  ui.logout.disabled = true;
  function done(error) {
    clearSession();
    paintUser();
    if (error && !error.handled) showError(error.message || '登出请求失败，本地登录状态已清除');
    else if (!error) showOk('已登出');
    if (location.hash !== '#/login') location.hash = '#/login';
    else onRoute();
    ui.logout.disabled = false;
  }
  api('/api/auth/logout', { method: 'POST' }).then(function () { done(null); }).catch(done);
}

function buildChrome() {
  var bannerText = el('p', { class: 'banner-text' });
  var banner = add(el('div', { class: 'banner', role: 'alert' }), bannerText,
    btn('关闭', 'btn btn-ghost', function () { banner.hidden = true; }));
  banner.hidden = true;
  var nav = el('nav', { class: 'nav', 'aria-label': '主导航' });
  [['#/tasks', '任务'], ['#/environments', '环境'], ['#/metrics', '指标']].forEach(function (item) {
    nav.appendChild(text('a', item[1], { href: item[0], 'data-nav': 'true' }));
  });
  var title = text('h1', '哨兵');
  var user = text('span', '未登录');
  var logoutBtn = btn('退出', 'btn', logout);
  var view = el('main', { id: 'view', class: 'view' });
  var shell = add(el('div', { class: 'shell' }),
    add(el('aside', { class: 'side' }),
      add(el('div', { class: 'brand' }), el('span', { class: 'mark' }),
        add(el('span'), text('strong', '哨兵'), text('small', 'SENTINEL'))),
      nav, text('p', '失败的环境会保留到过期时间，供排查。', { class: 'side-note' })),
    add(el('div', { class: 'workspace' }),
      add(el('header', { class: 'top' }), title, add(el('div', { class: 'who' }), user, logoutBtn)),
      view));
  document.getElementById('app').replaceChildren(banner, shell);
  ui.banner = banner;
  ui.bannerText = bannerText;
  ui.shell = shell;
  ui.side = shell.querySelector('.side');
  ui.title = title;
  ui.user = user;
  ui.logout = logoutBtn;
  ui.view = view;
}

function boot() {
  buildChrome();
  window.addEventListener('hashchange', onRoute);
  if (!location.hash || location.hash === '#') location.hash = token() ? '#/tasks' : '#/login';
  else onRoute();
}

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
else boot();
