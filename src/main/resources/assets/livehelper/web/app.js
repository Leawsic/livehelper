const API = {
    getClips: () => fetch('/api/clips').then(r => r.json()),
    createClip: data => fetch('/api/clips', jsonRequest('POST', data)).then(r => r.json()),
    updateClip: (id, data) => fetch(`/api/clips/${id}`, jsonRequest('PUT', data)),
    deleteClip: id => fetch(`/api/clips/${id}`, {method: 'DELETE'}),
    getManagers: () => fetch('/api/managers').then(r => r.json()),
    createManager: data => fetch('/api/managers', jsonRequest('POST', data)).then(r => r.json()),
    updateManager: (id, data) => fetch(`/api/managers/${id}`, jsonRequest('PUT', data)),
    deleteManager: id => fetch(`/api/managers/${id}`, {method: 'DELETE'}),
    startManager: id => fetch(`/api/manager/${id}/start`, {method: 'POST'}),
    stopManager: id => fetch(`/api/manager/${id}/stop`, {method: 'POST'}),
    getManagerStatus: id => fetch(`/api/manager/${id}/status`).then(r => r.json()),
    getPose: () => fetch('/api/pose').then(r => r.json())
};

const TEMPLATE_FIELDS = {
    STATIC: ['posX', 'posY', 'posZ', 'rotX', 'rotY', 'rotZ', 'fov'],
    ORBIT: ['targetX', 'targetY', 'targetZ', 'radius', 'speed', 'startAngle', 'elevation', 'fov'],
    DOLLY: ['fromX', 'fromY', 'fromZ', 'toX', 'toY', 'toZ', 'fov'],
    TRUCK: ['fromX', 'fromY', 'fromZ', 'toX', 'toY', 'toZ', 'fov'],
    PEDESTAL: ['fromHeight', 'toHeight', 'centerX', 'centerZ', 'fov', 'rotX', 'rotY'],
    PAN_TILT: ['startPan', 'endPan', 'startTilt', 'endTilt', 'posX', 'posY', 'posZ', 'fov'],
    PATH: ['fov']
};

let clips = [];
let managers = [];

function jsonRequest(method, data) {
    return {
        method,
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(data)
    };
}

function card(html) {
    const div = document.createElement('div');
    div.className = 'card';
    div.innerHTML = html;
    return div;
}

async function refreshAll() {
    clips = await API.getClips();
    managers = await API.getManagers();
    renderOverview();
    renderClips();
    renderManagers();
}

function renderOverview() {
    const root = document.getElementById('overview-content');
    root.innerHTML = '';
    if (!managers.length) {
        root.textContent = '暂无 Manager';
        return;
    }
    managers.forEach(manager => {
        const node = card(`<strong>${escapeHtml(manager.name)}</strong><p>${manager.width}×${manager.height} @ ${manager.fps}fps</p><p id="status-${manager.id}">状态: ...</p>`);
        root.appendChild(node);
        API.getManagerStatus(manager.id).then(s => {
            const el = document.getElementById(`status-${manager.id}`);
            if (el) el.textContent = `状态: ${s.status}`;
        });
    });
}

function renderClips() {
    const root = document.getElementById('clips-list');
    root.innerHTML = '';
    clips.forEach(clip => {
        const node = card(`
            <strong>${escapeHtml(clip.name)}</strong>
            <p>模板: ${clip.template} | 时长: ${clip.duration}ms</p>
            <p>${escapeHtml(JSON.stringify(clip.params || {}))}</p>
            <button data-edit-clip="${clip.id}">编辑</button>
            <button data-delete-clip="${clip.id}">删除</button>
        `);
        root.appendChild(node);
    });
}

function renderManagers() {
    const root = document.getElementById('managers-list');
    root.innerHTML = '';
    managers.forEach(manager => {
        const slots = (manager.clips || []).map(slot => {
            const clip = clips.find(c => c.id === slot.clipId);
            return `${clip ? escapeHtml(clip.name) : `Clip ${slot.clipId}`} @ ${slot.startOffset}ms`;
        }).join('<br>');
        const node = card(`
            <strong>${escapeHtml(manager.name)}</strong>
            <p>${manager.width}×${manager.height} @ ${manager.fps}fps | RD ${manager.renderDistance}</p>
            <p>${slots || '暂无片段'}</p>
            <button data-start-manager="${manager.id}">启动</button>
            <button data-stop-manager="${manager.id}">停止</button>
            <button data-edit-manager="${manager.id}">编辑</button>
            <button data-delete-manager="${manager.id}">删除</button>
        `);
        root.appendChild(node);
    });
}

function openClipEditor(clip = null) {
    const isEdit = !!clip;
    const data = clip || {name: '', duration: 5000, template: 'STATIC', params: {fov: 70}};
    const fields = document.getElementById('editor-fields');
    document.getElementById('editor-title').textContent = isEdit ? '编辑 Clip' : '新建 Clip';
    fields.innerHTML = `
        <label>名称<input name="name" value="${escapeAttr(data.name)}"></label>
        <label>时长(ms)<input name="duration" type="number" value="${data.duration || 5000}"></label>
        <label>模板<select name="template">${Object.keys(TEMPLATE_FIELDS).map(t => `<option ${t === data.template ? 'selected' : ''}>${t}</option>`).join('')}</select></label>
        <div id="param-fields"></div>
    `;

    const templateSelect = fields.querySelector('[name=template]');
    const renderParams = () => {
        const template = templateSelect.value;
        const paramsRoot = document.getElementById('param-fields');
        paramsRoot.innerHTML = TEMPLATE_FIELDS[template].map(key => {
            const value = data.params && data.params[key] != null ? data.params[key] : defaultParam(key);
            return `<label>${key}<input name="param:${key}" type="number" step="0.1" value="${value}"></label>`;
        }).join('');
    };
    templateSelect.addEventListener('change', renderParams);
    renderParams();

    showEditor(async () => {
        const form = document.getElementById('editor-form');
        const params = {};
        form.querySelectorAll('[name^="param:"]').forEach(input => {
            params[input.name.substring(6)] = Number(input.value);
        });
        const payload = {
            id: isEdit ? data.id : 0,
            name: form.name.value,
            duration: Number(form.duration.value),
            template: form.template.value,
            params
        };
        if (isEdit) await API.updateClip(data.id, payload);
        else await API.createClip(payload);
        await refreshAll();
    });
}

function openManagerEditor(manager = null) {
    const isEdit = !!manager;
    const data = manager || {name: '', width: 1920, height: 1080, fps: 60, renderDistance: 12, clips: []};
    const fields = document.getElementById('editor-fields');
    document.getElementById('editor-title').textContent = isEdit ? '编辑 Manager' : '新建 Manager';
    fields.innerHTML = `
        <label>名称<input name="name" value="${escapeAttr(data.name)}"></label>
        <label>宽度<input name="width" type="number" value="${data.width || 1920}"></label>
        <label>高度<input name="height" type="number" value="${data.height || 1080}"></label>
        <label>FPS<input name="fps" type="number" value="${data.fps || 60}"></label>
        <label>渲染距离<input name="renderDistance" type="number" value="${data.renderDistance || 12}"></label>
        <label>片段 JSON<textarea name="clipsJson" rows="6">${escapeHtml(JSON.stringify(data.clips || [], null, 2))}</textarea></label>
        <p>格式: [{"clipId":1,"startOffset":0}]</p>
    `;
    showEditor(async () => {
        const form = document.getElementById('editor-form');
        const payload = {
            id: isEdit ? data.id : 0,
            name: form.name.value,
            clips: JSON.parse(form.clipsJson.value || '[]'),
            width: Number(form.width.value),
            height: Number(form.height.value),
            fps: Number(form.fps.value),
            renderDistance: Number(form.renderDistance.value)
        };
        if (isEdit) await API.updateManager(data.id, payload);
        else await API.createManager(payload);
        await refreshAll();
    });
}

function showEditor(onSave) {
    const dialog = document.getElementById('editor-dialog');
    const save = document.getElementById('editor-save');
    const handler = async event => {
        event.preventDefault();
        save.removeEventListener('click', handler);
        await onSave();
        dialog.close();
    };
    save.addEventListener('click', handler);
    dialog.showModal();
}

function defaultParam(key) {
    if (key === 'fov') return 70;
    if (key === 'speed') return 1;
    if (key === 'radius') return 10;
    return 0;
}

function escapeHtml(value) {
    return String(value).replace(/[&<>"]/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'}[c]));
}

function escapeAttr(value) {
    return escapeHtml(value).replace(/'/g, '&#39;');
}

document.querySelectorAll('nav button').forEach(button => {
    button.addEventListener('click', () => {
        document.querySelectorAll('nav button, .tab').forEach(el => el.classList.remove('active'));
        button.classList.add('active');
        document.getElementById(button.dataset.tab).classList.add('active');
    });
});

document.addEventListener('click', async event => {
    const target = event.target;
    if (target.id === 'refresh-overview') await refreshAll();
    if (target.id === 'new-clip') openClipEditor();
    if (target.id === 'new-manager') openManagerEditor();
    if (target.dataset.editClip) openClipEditor(clips.find(c => c.id === Number(target.dataset.editClip)));
    if (target.dataset.editManager) openManagerEditor(managers.find(m => m.id === Number(target.dataset.editManager)));
    if (target.dataset.deleteClip && confirm('删除这个 Clip？')) {
        await API.deleteClip(Number(target.dataset.deleteClip));
        await refreshAll();
    }
    if (target.dataset.deleteManager && confirm('删除这个 Manager？')) {
        await API.deleteManager(Number(target.dataset.deleteManager));
        await refreshAll();
    }
    if (target.dataset.startManager) {
        await API.startManager(Number(target.dataset.startManager));
        await refreshAll();
    }
    if (target.dataset.stopManager) {
        await API.stopManager(Number(target.dataset.stopManager));
        await refreshAll();
    }
});

refreshAll().catch(error => {
    document.body.insertAdjacentHTML('beforeend', `<pre class="card">${escapeHtml(error.stack || error.message)}</pre>`);
});
