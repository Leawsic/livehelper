const API = {
    getClips: () => request('/api/clips'),
    createClip: data => request('/api/clips', jsonRequest('POST', data)),
    updateClip: (id, data) => request(`/api/clips/${id}`, jsonRequest('PUT', data)),
    deleteClip: id => request(`/api/clips/${id}`, {method: 'DELETE'}),
    getManagers: () => request('/api/managers'),
    createManager: data => request('/api/managers', jsonRequest('POST', data)),
    updateManager: (id, data) => request(`/api/managers/${id}`, jsonRequest('PUT', data)),
    deleteManager: id => request(`/api/managers/${id}`, {method: 'DELETE'}),
    startManager: id => request(`/api/managers/${id}/start`, {method: 'POST'}),
    stopManager: id => request(`/api/managers/${id}/stop`, {method: 'POST'}),
    getManagerStatus: id => request(`/api/managers/${id}/status`),
    getPose: () => request('/api/pose')
};

const TEMPLATE_FIELDS = {
    STATIC: ['posX', 'posY', 'posZ', 'rotX', 'rotY', 'rotZ', 'fov'],
    ORBIT: ['targetX', 'targetY', 'targetZ', 'radius', 'speed', 'startAngle', 'elevation', 'fov'],
    DOLLY: ['fromX', 'fromY', 'fromZ', 'toX', 'toY', 'toZ', 'easing', 'fov'],
    TRUCK: ['fromX', 'fromY', 'fromZ', 'toX', 'toY', 'toZ', 'easing', 'fov'],
    PEDESTAL: ['fromHeight', 'toHeight', 'centerX', 'centerZ', 'easing', 'fov', 'rotX', 'rotY'],
    PAN_TILT: ['startPan', 'endPan', 'startTilt', 'endTilt', 'posX', 'posY', 'posZ', 'fov'],
    PATH: ['keyframes', 'fov']
};

let clips = [];
let managers = [];
let statuses = new Map();
let worldState = {ready: false, pose: null};
let refreshing = false;

function jsonRequest(method, data) {
    return {method, headers: {'Content-Type': 'application/json'}, body: JSON.stringify(data)};
}

async function request(url, options = {}) {
    const response = await fetch(url, options);
    const text = await response.text();
    const data = text ? JSON.parse(text) : {};
    if (!response.ok) {
        throw new Error(data.error || `${response.status} ${response.statusText}`);
    }
    return data;
}

async function refreshAll() {
    if (refreshing) return;
    refreshing = true;
    setBusy(true);
    try {
        clips = await API.getClips();
        managers = await API.getManagers();
        try {
            worldState = {ready: true, pose: await API.getPose()};
        } catch (_) {
            worldState = {ready: false, pose: null};
        }
        statuses = new Map(await Promise.all(managers.map(async manager => {
            try {
                return [manager.id, (await API.getManagerStatus(manager.id)).status];
            } catch (_) {
                return [manager.id, 'unknown'];
            }
        })));
        renderWorldStatus();
        renderOverview();
        renderClips();
        renderManagers();
    } catch (error) {
        toast(error.message, 'bad');
    } finally {
        refreshing = false;
        setBusy(false);
    }
}

function renderWorldStatus() {
    const root = byId('world-status');
    if (worldState.ready) {
        const pose = worldState.pose;
        root.className = 'status-banner ready';
        root.innerHTML = `已连接世界 <span>${formatNumber(pose.x)}, ${formatNumber(pose.y)}, ${formatNumber(pose.z)}</span>`;
    } else {
        root.className = 'status-banner warn';
        root.textContent = 'Minecraft 客户端已连接，尚未进入世界。进入世界后可读取玩家位姿并启动推流。';
    }
}

function setBusy(busy) {
    document.querySelectorAll('button').forEach(button => button.disabled = busy && !button.closest('dialog'));
}

function renderOverview() {
    const root = byId('overview-content');
    root.innerHTML = '';
    if (!managers.length) {
        root.innerHTML = '<div class="empty">还没有 Manager。先创建 Clip，再创建 Manager 编排时间线。</div>';
        return;
    }
    managers.forEach(manager => {
        const status = statuses.get(manager.id) || 'stopped';
        const totalDuration = managerDuration(manager);
        root.appendChild(card(`
            <h3>${escapeHtml(manager.name)}</h3>
            <div class="badge-row">
                <span class="badge id">Manager #${manager.id}</span>
                <span class="badge ${status}">${status}</span>
                <span class="badge">${manager.width}x${manager.height}</span>
                <span class="badge">${manager.fps}fps</span>
                <span class="badge">${totalDuration}ms</span>
            </div>
            <p>OBS Sender: <strong>LiveHelper-${escapeHtml(manager.name)}</strong></p>
            <p>${manager.clips?.length || 0} clips, render distance ${manager.renderDistance}</p>
            <div class="card-actions">
                <button class="primary" data-start-manager="${manager.id}">启动</button>
                <button data-stop-manager="${manager.id}">停止</button>
            </div>
        `));
    });
}

function renderClips() {
    const root = byId('clips-list');
    root.innerHTML = '';
    if (!clips.length) {
        root.innerHTML = '<div class="empty">暂无 Clip。点击右上角“新建 Clip”开始。</div>';
        return;
    }
    clips.forEach(clip => {
        root.appendChild(card(`
            <h3>${escapeHtml(clip.name)}</h3>
            <div class="badge-row">
                <button class="badge id copy-badge" data-copy="${clip.id}" title="复制 Clip ID">Clip #${clip.id}</button>
                <span class="badge">${clip.template}</span>
                <span class="badge">${clip.duration}ms</span>
                <span class="badge">${Object.keys(clip.params || {}).length} params</span>
            </div>
            <pre class="params">${escapeHtml(JSON.stringify(clip.params || {}, null, 2))}</pre>
            <div class="card-actions">
                <button data-edit-clip="${clip.id}">编辑</button>
                <button class="danger" data-delete-clip="${clip.id}">删除</button>
            </div>
        `));
    });
}

function renderManagers() {
    const root = byId('managers-list');
    root.innerHTML = '';
    if (!managers.length) {
        root.innerHTML = '<div class="empty">暂无 Manager。创建后即可启动 Spout Sender。</div>';
        return;
    }
    managers.forEach(manager => {
        const status = statuses.get(manager.id) || 'stopped';
        const slots = (manager.clips || []).map(slot => {
            const clip = clips.find(c => c.id === slot.clipId);
            const label = clip ? `${escapeHtml(clip.name)} (${clip.template}, ${clip.duration}ms)` : '(missing)';
            return `<button class="inline-copy" data-copy="${slot.clipId}" title="复制 Clip ID">#${slot.clipId}</button> ${label} @ ${slot.startOffset}ms`;
        }).join('<br>');
        const totalDuration = managerDuration(manager);
        root.appendChild(card(`
            <h3>${escapeHtml(manager.name)}</h3>
            <div class="badge-row">
                <span class="badge id">Manager #${manager.id}</span>
                <span class="badge ${status}">${status}</span>
                <span class="badge">${manager.width}x${manager.height}</span>
                <span class="badge">${manager.fps}fps</span>
                <span class="badge">RD ${manager.renderDistance}</span>
                <span class="badge">${totalDuration}ms</span>
            </div>
            <p>${slots || 'No clips in timeline'}</p>
            <div class="card-actions">
                <button class="primary" data-start-manager="${manager.id}">启动</button>
                <button data-stop-manager="${manager.id}">停止</button>
                <button data-edit-manager="${manager.id}">编辑</button>
                <button class="danger" data-delete-manager="${manager.id}">删除</button>
            </div>
        `));
    });
}

function openClipEditor(clip = null) {
    const isEdit = !!clip;
    const data = clone(clip || {name: '', duration: 5000, template: 'STATIC', params: {fov: 70}});
    byId('editor-title').textContent = isEdit ? `编辑 Clip #${data.id}` : '新建 Clip';
    byId('editor-fields').innerHTML = `
        <div class="form-grid">
            <label>名称<input data-field="name" value="${escapeAttr(data.name)}" placeholder="例如 Orbit 主舞台"></label>
            <label>时长(ms)<input data-field="duration" type="number" min="1" value="${data.duration || 5000}"></label>
            <label class="full">模板<select data-field="template">${Object.keys(TEMPLATE_FIELDS).map(t => `<option ${t === data.template ? 'selected' : ''}>${t}</option>`).join('')}</select></label>
            <div id="param-fields" class="full"></div>
        </div>
    `;

    const templateSelect = qs('[data-field="template"]');
    const renderParams = () => renderParamFields(templateSelect.value, data.params || {});
    templateSelect.addEventListener('change', renderParams);
    renderParams();

    showEditor(async () => {
        const payload = {
            id: isEdit ? data.id : 0,
            name: val('[data-field="name"]') || 'Untitled Clip',
            duration: positiveNumber('[data-field="duration"]', 5000),
            template: val('[data-field="template"]'),
            params: collectParams()
        };
        if (isEdit) await API.updateClip(data.id, payload);
        else await API.createClip(payload);
        toast('Clip 已保存', 'good');
        await refreshAll();
    });
}

function renderParamFields(template, params) {
    const root = byId('param-fields');
    root.innerHTML = TEMPLATE_FIELDS[template].map(key => {
        const value = params[key] ?? defaultParam(key);
        if (key === 'easing') {
            return `<label>${key}<select data-param="${key}">${['linear', 'easeIn', 'easeOut', 'easeInOut'].map(v => `<option ${v === value ? 'selected' : ''}>${v}</option>`).join('')}</select></label>`;
        }
        if (key === 'keyframes') {
            const text = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
            return `<label class="full">${key}<textarea data-param="${key}" rows="8">${escapeHtml(text)}</textarea><span class="help">支持数组 JSON，例如 [{"t":0,"x":0,"y":80,"z":0,"rx":0,"ry":0,"rz":0}]</span></label>`;
        }
        return `<label>${key}<input data-param="${key}" type="number" step="0.1" value="${escapeAttr(value)}"></label>`;
    }).join('');
}

function openManagerEditor(manager = null) {
    if (!clips.length) {
        toast('请先创建至少一个 Clip，再创建 Manager。', 'bad');
        return;
    }
    const isEdit = !!manager;
    const data = clone(manager || {name: '', width: 1280, height: 720, fps: 30, renderDistance: 12, clips: [{clipId: clips[0].id, startOffset: 0}]});
    byId('editor-title').textContent = isEdit ? `编辑 Manager #${data.id}` : '新建 Manager';
    byId('editor-fields').innerHTML = `
        <div class="form-grid">
            <label>名称<input data-field="name" value="${escapeAttr(data.name)}" placeholder="OBS Sender 会显示为 LiveHelper-名称"></label>
            <label>FPS<input data-field="fps" type="number" min="1" max="240" value="${data.fps || 30}"></label>
            <label>宽度<input data-field="width" type="number" min="16" value="${data.width || 1280}"></label>
            <label>高度<input data-field="height" type="number" min="16" value="${data.height || 720}"></label>
            <label>渲染距离<input data-field="renderDistance" type="number" min="2" value="${data.renderDistance || 12}"></label>
            <div class="timeline-builder full">
                <div class="section-head"><div><h3>时间线片段</h3><p class="help">无需记 Clip ID，直接从下拉框选择。</p></div><button type="button" id="add-slot">添加片段</button></div>
                <div id="slot-list"></div>
            </div>
        </div>
    `;
    renderSlots(data.clips || []);
    byId('add-slot').addEventListener('click', () => {
        const slots = collectSlots();
        const last = slots.at(-1);
        const lastClip = last ? clips.find(c => c.id === last.clipId) : null;
        slots.push({clipId: clips[0].id, startOffset: last ? last.startOffset + (lastClip?.duration || 1000) : 0});
        renderSlots(slots);
    });

    showEditor(async () => {
        const payload = {
            id: isEdit ? data.id : 0,
            name: val('[data-field="name"]') || 'Untitled Manager',
            clips: collectSlots(),
            width: positiveNumber('[data-field="width"]', 1280),
            height: positiveNumber('[data-field="height"]', 720),
            fps: positiveNumber('[data-field="fps"]', 30),
            renderDistance: positiveNumber('[data-field="renderDistance"]', 12)
        };
        if (!payload.clips.length) throw new Error('Manager 至少需要一个 Clip');
        if (isEdit) await API.updateManager(data.id, payload);
        else await API.createManager(payload);
        toast('Manager 已保存', 'good');
        await refreshAll();
    });
}

function renderSlots(slots) {
    const root = byId('slot-list');
    root.innerHTML = '';
    slots.forEach((slot, index) => {
        const row = document.createElement('div');
        row.className = 'slot-row';
        row.innerHTML = `
            <label>Clip<select data-slot-clip>${clips.map(clip => `<option value="${clip.id}" ${clip.id === slot.clipId ? 'selected' : ''}>#${clip.id} ${escapeHtml(clip.name)} (${clip.template}, ${clip.duration}ms)</option>`).join('')}</select></label>
            <label>开始(ms)<input data-slot-offset type="number" min="0" value="${slot.startOffset || 0}"></label>
            <div class="card-actions">
                <button type="button" data-slot-up>↑</button>
                <button type="button" data-slot-down>↓</button>
                <button type="button" class="danger" data-slot-remove>删除</button>
            </div>
        `;
        row.querySelector('[data-slot-up]').addEventListener('click', () => moveSlot(index, -1));
        row.querySelector('[data-slot-down]').addEventListener('click', () => moveSlot(index, 1));
        row.querySelector('[data-slot-remove]').addEventListener('click', () => {
            const next = collectSlots();
            next.splice(index, 1);
            renderSlots(next);
        });
        root.appendChild(row);
    });
}

function moveSlot(index, delta) {
    const slots = collectSlots();
    const to = index + delta;
    if (to < 0 || to >= slots.length) return;
    const [slot] = slots.splice(index, 1);
    slots.splice(to, 0, slot);
    renderSlots(slots);
}

function collectSlots() {
    return [...document.querySelectorAll('.slot-row')].map(row => ({
        clipId: Number(row.querySelector('[data-slot-clip]').value),
        startOffset: Number(row.querySelector('[data-slot-offset]').value || 0)
    })).sort((a, b) => a.startOffset - b.startOffset);
}

function collectParams() {
    const params = {};
    document.querySelectorAll('[data-param]').forEach(input => {
        const key = input.dataset.param;
        if (key === 'keyframes') params[key] = JSON.parse(input.value || '[]');
        else if (key === 'easing') params[key] = input.value;
        else params[key] = Number(input.value);
    });
    return params;
}

function showEditor(onSave) {
    const dialog = byId('editor-dialog');
    const save = byId('editor-save');
    const cancel = byId('editor-cancel');
    const close = byId('editor-close');
    const cleanup = () => {
        save.onclick = null;
        cancel.onclick = null;
        close.onclick = null;
    };
    cancel.onclick = close.onclick = () => { cleanup(); dialog.close(); };
    save.onclick = async () => {
        save.disabled = true;
        try {
            await onSave();
            cleanup();
            dialog.close();
        } catch (error) {
            toast(error.message, 'bad');
        } finally {
            save.disabled = false;
        }
    };
    dialog.showModal();
}

function defaultParam(key) {
    if (key === 'fov') return 70;
    if (key === 'speed') return 1;
    if (key === 'radius') return 10;
    if (key === 'easing') return 'linear';
    if (key === 'keyframes') return [{t: 0, x: 0, y: 80, z: 0, rx: 0, ry: 0, rz: 0}, {t: 1, x: 10, y: 80, z: 10, rx: 0, ry: 90, rz: 0}];
    return 0;
}

function managerDuration(manager) {
    return (manager.clips || []).reduce((max, slot) => {
        const clip = clips.find(c => c.id === slot.clipId);
        return Math.max(max, Number(slot.startOffset || 0) + Number(clip?.duration || 0));
    }, 0);
}

function formatNumber(value) {
    return Number(value).toFixed(2);
}

async function copyText(text) {
    try {
        await navigator.clipboard.writeText(String(text));
    } catch (_) {
        const input = document.createElement('input');
        input.value = String(text);
        document.body.appendChild(input);
        input.select();
        document.execCommand('copy');
        input.remove();
    }
    toast(`已复制 ${text}`, 'good');
}

function positiveNumber(selector, fallback) {
    const value = Number(val(selector));
    return Number.isFinite(value) && value > 0 ? value : fallback;
}

function val(selector) {
    const el = qs(selector);
    return el ? el.value : '';
}

function qs(selector) { return document.querySelector(selector); }
function byId(id) { return document.getElementById(id); }
function clone(value) { return JSON.parse(JSON.stringify(value)); }
function card(html) { const div = document.createElement('div'); div.className = 'card'; div.innerHTML = html; return div; }
function escapeHtml(value) { return String(value ?? '').replace(/[&<>"]/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'}[c])); }
function escapeAttr(value) { return escapeHtml(value).replace(/'/g, '&#39;'); }

function toast(message, type = '') {
    const node = document.createElement('div');
    node.className = `toast ${type}`;
    node.textContent = message;
    byId('toast-root').appendChild(node);
    setTimeout(() => node.remove(), 3600);
}

document.querySelectorAll('nav button').forEach(button => {
    button.addEventListener('click', () => {
        document.querySelectorAll('nav button, .tab').forEach(el => el.classList.remove('active'));
        button.classList.add('active');
        byId(button.dataset.tab).classList.add('active');
    });
});

window.addEventListener('focus', () => refreshAll());
document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') refreshAll();
});

document.addEventListener('click', async event => {
    const target = event.target;
    try {
        if (target.id === 'refresh-overview') await refreshAll();
        if (target.dataset.copy) await copyText(target.dataset.copy);
        if (target.id === 'new-clip') openClipEditor();
        if (target.id === 'new-manager') openManagerEditor();
        if (target.dataset.editClip) openClipEditor(clips.find(c => c.id === Number(target.dataset.editClip)));
        if (target.dataset.editManager) openManagerEditor(managers.find(m => m.id === Number(target.dataset.editManager)));
        if (target.dataset.deleteClip && confirm('删除这个 Clip？')) {
            await API.deleteClip(Number(target.dataset.deleteClip));
            toast('Clip 已删除', 'good');
            await refreshAll();
        }
        if (target.dataset.deleteManager && confirm('删除这个 Manager？')) {
            await API.deleteManager(Number(target.dataset.deleteManager));
            toast('Manager 已删除', 'good');
            await refreshAll();
        }
        if (target.dataset.startManager) {
            await API.startManager(Number(target.dataset.startManager));
            toast('启动请求已发送', 'good');
            await refreshAll();
        }
        if (target.dataset.stopManager) {
            await API.stopManager(Number(target.dataset.stopManager));
            toast('停止请求已发送', 'good');
            await refreshAll();
        }
    } catch (error) {
        toast(error.message, 'bad');
    }
});

refreshAll();
