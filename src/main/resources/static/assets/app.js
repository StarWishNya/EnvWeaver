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
var DEPENDENCIES = ['mysql', 'redis', 'postgres', 'mongodb', 'rabbitmq', 'nginx'];
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

/* ---------- 路由与计时 ---------- */

function disarm(name) {
  clearTimeout(clocks[name]);
  clocks[name] = null;
}

function disarmAll() {
  Object.keys(clocks).forEach(disarm);
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
  disarmAll();
  generation += 1;
  return generation;
}

function parseRoute() {
  var path = (location.hash || '#/').split('?')[0];
  var matched = path.match(/^#\/(tasks|environments)\/(\d+)$/);
  if (matched) return { name: matched[1] === 'tasks' ? 'task' : 'environment', id: matched[2] };
  if (path === '#/' || path === '#' || path === '') return { name: 'home' };
  if ({ '#/login': 1, '#/tasks': 1, '#/environments': 1, '#/metrics': 1 }[path]) {
    return { name: path.slice(2) };
  }
  return { name: 'missing' };
}

function onRoute() {
  var route = parseRoute();
  var authed = !!token();
  if (route.name === 'home') { location.hash = authed ? '#/tasks' : '#/login'; return; }
  if (route.name !== 'login' && !authed) { location.hash = '#/login'; return; }
  if (route.name === 'login' && authed) { location.hash = '#/tasks'; return; }
  render(route, beginRoute());
}

function syncNav() {
  var hash = location.hash || '';
  ui.side.querySelectorAll('a[data-nav]').forEach(function (link) {
    var href = link.getAttribute('href');
    var on = href === '#/metrics' ? hash === href : hash === href || hash.indexOf(href + '/') === 0;
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

function renderTasks(gen) {
  setHeading('任务');
  var state = { page: 0, size: 20, status: '', testType: '' };
  var reload = function (fromTimer) { return loadTasks(gen, state, hosts, fromTimer); };
  var status = selectBox('status', [['', '全部状态']].concat(TASK_STATUSES), '');
  var testType = selectBox('testType', [['', '全部类型']].concat(TEST_TYPES), '');
  var built = dataTable(['ID', '任务编码', '服务', '测试类型', '状态', '优先级', '创建时间', '环境', '操作']);
  built.table.classList.add('wide-table');
  var hint = text('p', '', { class: 'hint' });
  var pager = el('div', { class: 'pager' });
  var hosts = { body: built.body, hint: hint, pager: pager };
  function reset(control, key) {
    state[key] = control.value;
    state.page = 0;
    reload(false);
  }
  add(ui.view,
    buildCreateForm(gen, function () { return reload(false); }),
    add(el('form', { class: 'card toolbar' }),
      field('状态', on(status, 'change', function () { reset(status, 'status'); })),
      field('测试类型', on(testType, 'change', function () { reset(testType, 'testType'); })),
      btn('刷新', 'btn', function () { reload(false); })),
    hint,
    add(el('div', { class: 'table-wrap card' }), built.table),
    pager);
  reload(false);
}

function buildCreateForm(gen, reload) {
  var taskCode = el('input', { name: 'taskCode', maxlength: '64', autocomplete: 'off' });
  var serviceId = el('input', { name: 'serviceId', maxlength: '64', autocomplete: 'off' });
  var testType = selectBox('testType', TEST_TYPES, 'UNIT_TEST');
  var priority = el('input', { name: 'priority', type: 'number', min: '1', max: '10', step: '1', value: '5' });
  var depText = el('input', { name: 'depText', placeholder: '例如 mysql:8.0，逗号分隔', autocomplete: 'off' });
  var checks = el('div', { class: 'checks' });
  DEPENDENCIES.forEach(function (name) {
    add(checks, add(el('label'), el('input', { type: 'checkbox', name: 'dep', value: name }), document.createTextNode(name)));
  });
  var submit = text('button', '创建任务', { type: 'submit', class: 'btn btn-primary' });
  var form = add(el('form', { class: 'card' }), text('h2', '创建任务'), add(el('div', { class: 'form-grid' }),
    field('任务编码', taskCode), field('服务标识', serviceId), field('测试类型', testType),
    field('优先级（1 到 10）', priority),
    add(el('div', { class: 'field span-all' }), text('span', '依赖'), checks),
    field('其他依赖', depText, 'field span-all'), submit));
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
    submit.disabled = true;
    api('/api/tasks', {
      method: 'POST',
      body: { taskCode: code, serviceId: service, testType: testType.value, dependencies: collectDeps(form), priority: level }
    }).then(function () {
      if (stale(gen)) return;
      taskCode.value = '';
      showOk('任务已创建');
      return reload();
    }).catch(function (error) { if (!stale(gen)) report(error); })
      .then(function () { submit.disabled = false; });
  });
  return form;
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
  form.querySelectorAll('input[name="dep"]:checked').forEach(function (input) { addDep(input.value); });
  form.querySelector('input[name="depText"]').value.split(/[,，;]+/).forEach(addDep);
  return list;
}

function loadTasks(gen, state, hosts, fromTimer) {
  disarm('tasks');
  var seq = ++taskSeq;
  var params = new URLSearchParams({ page: String(state.page), size: String(state.size) });
  if (state.status) params.set('status', state.status);
  if (state.testType) params.set('testType', state.testType);
  if (!fromTimer) hosts.hint.textContent = '加载中…';
  var reload = function () { return loadTasks(gen, state, hosts, false); };
  return api('/api/tasks?' + params.toString()).then(function (res) {
    if (stale(gen) || seq !== taskSeq) return;
    var page = res.data || {};
    var rows = Array.isArray(page.content) ? page.content : [];
    paintTaskRows(hosts.body, rows, gen, reload);
    paintPager(hosts.pager, page, state, reload);
    var hot = rows.some(function (task) { return task && ACTIVE_TASK[task.status]; });
    hosts.hint.textContent = hot ? '有进行中的任务，每 5 秒自动刷新' : '';
    if (hot) arm('tasks', 5000, function () { return loadTasks(gen, state, hosts, true); });
  }).catch(function (error) {
    if (stale(gen) || seq !== taskSeq) return;
    report(error);
    if (!error.handled && fromTimer) arm('tasks', 5000, function () { return loadTasks(gen, state, hosts, true); });
  });
}

function paintTaskRows(body, rows, gen, reload) {
  if (!rows.length) { emptyRow(body, 9, '暂无任务'); return; }
  body.replaceChildren.apply(body, rows.map(function (task) { return taskRow(task, gen, reload); }));
}

function taskRow(task, gen, reload) {
  var statusCell = add(el('td'), badge(TASK_STATUSES, task.status));
  return add(el('tr'),
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
    box.appendChild(btn('取消', 'btn', function () {
      runAction(function () {
        return confirmDialog('取消任务「' + name + '」？', '确认取消').then(function (ok) {
          return ok ? api('/api/tasks/' + encodeURIComponent(task.id), { method: 'DELETE' }) : null;
        });
      }, gen, reload, '任务已取消');
    }));
  }
  if (task.status === 'RUNNING') {
    box.appendChild(btn('回报成功', 'btn', function () {
      runAction(function () { return confirmStatus(task, 'SUCCESS', '成功'); }, gen, reload, '已回报成功');
    }));
    box.appendChild(btn('回报失败', 'btn btn-danger', function () {
      runAction(function () { return confirmStatus(task, 'FAILED', '失败'); }, gen, reload, '已回报失败');
    }));
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
  var prev = btn('上一页', 'btn', function () { if (state.page > 0) { state.page -= 1; reload(); } });
  var next = btn('下一页', 'btn', function () { if (state.page + 1 < pages) { state.page += 1; reload(); } });
  var size = selectBox('size', [['10', '10 条/页'], ['20', '20 条/页'], ['50', '50 条/页'], ['100', '100 条/页']], String(state.size));
  prev.disabled = state.page <= 0;
  next.disabled = pages === 0 || state.page + 1 >= pages;
  on(size, 'change', function () { state.size = Number(size.value); state.page = 0; reload(); });
  host.replaceChildren(
    text('span', '第 ' + (pages ? state.page + 1 : 0) + ' / ' + pages + ' 页，共 ' + Number(page.totalElements || 0) + ' 条'),
    prev, next, size);
}

/* ---------- 任务详情、日志、诊断 ---------- */

function renderTask(gen, id) {
  setHeading('任务详情');
  var meta = el('div', { class: 'card' });
  var side = el('div');
  var logs = buildLogs(gen, id);
  var diagnosis = buildDiagnosis(gen, id);
  add(ui.view, routeLink('#/tasks', '返回任务列表'),
    add(el('div', { class: 'split' }), meta, side), logs.panel, diagnosis.panel);
  loadTask(gen, id, meta, side, false);
  logs.load();
  diagnosis.load();
}

function loadTask(gen, id, meta, side, fromTimer) {
  disarm('tasks');
  return api('/api/tasks/' + encodeURIComponent(id)).then(function (res) {
    if (stale(gen)) return null;
    var task = res.data || {};
    paintTaskDetail(meta, task, gen, function () { return loadTask(gen, id, meta, side, false); });
    if (ACTIVE_TASK[task.status]) arm('tasks', 5000, function () { return loadTask(gen, id, meta, side, true); });
    if (task.environmentId == null) {
      side.replaceChildren(text('div', '尚未关联环境', { class: 'card muted' }));
      return null;
    }
    return api('/api/environments/' + encodeURIComponent(task.environmentId)).then(function (envRes) {
      if (!stale(gen)) side.replaceChildren(environmentSummary(envRes.data || {}, true));
    });
  }).catch(function (error) {
    if (stale(gen)) return null;
    report(error);
    if (!fromTimer) meta.replaceChildren(text('p', '任务加载失败'));
    if (!error.handled && fromTimer) arm('tasks', 5000, function () { return loadTask(gen, id, meta, side, true); });
    return null;
  });
}

function paintTaskDetail(host, task, gen, reload) {
  var deps = Array.isArray(task.dependencies) ? task.dependencies.filter(function (item) {
    return item != null && String(item).trim() !== '';
  }) : [];
  var pairs = [['ID', task.id == null ? '—' : String(task.id)]];
  if (task.serviceId != null && String(task.serviceId).trim() !== '') pairs.push(['服务', String(task.serviceId)]);
  if (task.testType) pairs.push(['测试类型', labelOf(TEST_TYPES, task.testType)]);
  pairs.push(['状态', badge(TASK_STATUSES, task.status)]);
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
  var meta = text('p', '', { class: 'hint' });
  var pre = text('pre', '加载中…', { class: 'logs' });
  function load() {
    pre.textContent = '加载中…';
    meta.textContent = '';
    return api('/api/tasks/' + encodeURIComponent(id) + '/logs?tail=' + encodeURIComponent(tail.value)).then(function (res) {
      if (stale(gen)) return;
      var data = res.data || {};
      pre.textContent = data.logs ? String(data.logs) : '（无日志）';
      meta.textContent = data.hasMore ? '还有更早的日志未显示' : '';
    }).catch(function (error) {
      if (stale(gen)) return;
      pre.textContent = '日志加载失败';
      report(error);
    });
  }
  return {
    panel: add(el('section', { class: 'card' }), text('h2', '日志'),
      add(el('div', { class: 'toolbar' }), field('行数', on(tail, 'change', load)), btn('刷新日志', 'btn', load)),
      meta, pre),
    load: load
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

  function load() {
    disarm('diagnosis');
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

  return { panel: panel, load: load };
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

function renderEnvironments(gen) {
  setHeading('环境');
  var state = { status: '' };
  var status = selectBox('status', [['', '全部状态']].concat(ENV_STATUSES), '');
  var built = dataTable(['环境编码', '任务', '状态', '端口', '资源', '过期时间', '操作']);
  var meta = text('p', '', { class: 'hint' });
  function load() {
    var params = new URLSearchParams();
    if (state.status) params.set('status', state.status);
    var suffix = params.toString();
    meta.textContent = '加载中…';
    return api('/api/environments' + (suffix ? '?' + suffix : '')).then(function (res) {
      if (stale(gen)) return;
      var rows = Array.isArray(res.data) ? res.data : [];
      paintEnvRows(built.body, rows, gen, load);
      meta.textContent = '共 ' + rows.length + ' 个环境';
    }).catch(function (error) { if (!stale(gen)) report(error); });
  }
  add(ui.view,
    add(el('form', { class: 'card toolbar' }),
      field('状态', on(status, 'change', function () { state.status = status.value; load(); })),
      btn('刷新', 'btn', load)),
    meta, add(el('div', { class: 'table-wrap card' }), built.table));
  load();
}

function paintEnvRows(body, rows, gen, reload) {
  if (!rows.length) { emptyRow(body, 7, '暂无环境'); return; }
  body.replaceChildren.apply(body, rows.map(function (env) {
    return add(el('tr'),
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
  return btn('释放', 'btn btn-danger', function () { releaseEnv(env, gen, reload); });
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
  add(ui.view, text('p', '每 10 秒刷新', { class: 'hint' }), metrics,
    add(el('section', { class: 'card' }), text('h2', '依赖健康'), healthBody));
  loadMetrics(gen, metrics, healthBody);
}

function loadMetrics(gen, metrics, healthBody) {
  disarm('metrics');
  return Promise.allSettled([api('/api/metrics'), fetchHealth()]).then(function (settled) {
    if (stale(gen)) return;
    var authFailed = settled.some(function (item) {
      return item.status === 'rejected' && item.reason && item.reason.handled;
    });
    if (settled[0].status === 'fulfilled') paintMetrics(metrics, settled[0].value.data || {});
    else if (!authFailed) report(settled[0].reason);
    if (settled[1].status === 'fulfilled') paintHealth(healthBody, settled[1].value);
    else if (!authFailed) report(settled[1].reason);
    if (!authFailed) arm('metrics', 10000, function () { return loadMetrics(gen, metrics, healthBody); });
  });
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

function paintMetrics(host, data) {
  var cpu = data.cpu || {};
  var memory = data.memory || {};
  var disk = data.disk || {};
  var containers = data.containers || {};
  host.replaceChildren(
    metricCard('CPU', formatPercent(cpu.usage), (cpu.cores == null ? '—' : String(cpu.cores)) + ' 核', cpu.usage),
    metricCard('内存', formatPercent(memory.usage), formatBytes(memory.used) + ' / ' + formatBytes(memory.total), memory.usage),
    metricCard('磁盘', formatPercent(disk.usage), formatBytes(disk.used) + ' / ' + formatBytes(disk.total), disk.usage),
    metricCard('容器', String(containers.running == null ? '—' : containers.running),
      '运行 ' + (containers.running || 0) + ' · 暂停 ' + (containers.paused || 0) + ' · 停止 ' + (containers.stopped || 0)));
  host.appendChild(text('p', '采样 ' + formatTime(data.timestamp), { class: 'hint span-all' }));
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
