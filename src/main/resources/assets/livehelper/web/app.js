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
    STATIC_TRACK: ['posX', 'posY', 'posZ', 'entityId', 'entityUuid', 'entityName', 'targetYOffset', 'trackSpeed', 'fov'],
    ORBIT: ['targetX', 'targetY', 'targetZ', 'radius', 'speed', 'startAngle', 'elevation', 'fov'],
    DOLLY: ['fromX', 'fromY', 'fromZ', 'toX', 'toY', 'toZ', 'easing', 'fov'],
    TRUCK: ['fromX', 'fromY', 'fromZ', 'toX', 'toY', 'toZ', 'easing', 'fov'],
    PEDESTAL: ['fromHeight', 'toHeight', 'centerX', 'centerZ', 'easing', 'fov', 'rotX', 'rotY'],
    PAN_TILT: ['startPan', 'endPan', 'startTilt', 'endTilt', 'posX', 'posY', 'posZ', 'fov'],
    PATH: ['keyframes']
};

const STRING_FIELDS = new Set(['entityUuid', 'entityName']);

const FIELD_LABELS = {
    posX: '位置 X', posY: '位置 Y', posZ: '位置 Z',
    rotX: '俯仰 Pitch', rotY: '偏航 Yaw', rotZ: '滚转 Roll',
    fov: '视场角 FOV',
    entityId: '实体 ID', entityUuid: '实体 UUID', entityName: '实体名称', targetYOffset: '目标 Y 偏移', trackSpeed: '追踪平滑速度',
    targetX: '目标 X', targetY: '目标 Y', targetZ: '目标 Z',
    radius: '环绕半径', speed: '环绕速度', startAngle: '起始角度', elevation: '仰角',
    fromX: '起点 X', fromY: '起点 Y', fromZ: '起点 Z',
    toX: '终点 X', toY: '终点 Y', toZ: '终点 Z',
    easing: '缓动',
    fromHeight: '起始高度', toHeight: '结束高度', centerX: '中心 X', centerZ: '中心 Z',
    startPan: '起始水平角', endPan: '结束水平角', startTilt: '起始俯仰角', endTilt: '结束俯仰角',
    keyframes: '关键帧 JSON'
};

const FIELD_HELP = {
    posX: '摄像机所在的世界 X 坐标。', posY: '摄像机所在的世界 Y 坐标，通常用玩家眼睛高度。', posZ: '摄像机所在的世界 Z 坐标。',
    rotX: '上下看，正值向下，负值向上。', rotY: '水平朝向，使用 Minecraft yaw。', rotZ: '画面滚转角，一般保持 0。',
    fov: '镜头视场角，数值越大越广角。',
    entityId: 'Minecraft 运行时实体 ID，优先级最高；可用 /livehelper entities 查看附近实体。', entityUuid: '实体 UUID，适合长期锁定同一个实体。',
    entityName: '实体显示名称，entityId/UUID 为空或找不到时按名称精确匹配。', targetYOffset: '在实体眼睛高度基础上额外增加的 Y 偏移。', trackSpeed:
    '镜头追踪实体的平滑速度。0 为即时锁定；数值越大越跟手，越小越丝滑但延迟越明显。推荐 5-25。',
    targetX: '环绕时始终看向的目标 X 坐标。', targetY: '环绕时始终看向的目标 Y 坐标。', targetZ: '环绕时始终看向的目标 Z 坐标。',
    radius: '摄像机到目标点的水平距离。', speed: 'Clip 播放期间绕目标旋转的圈数。', startAngle: '环绕起始角度，单位度。', elevation: '摄像机相对目标点的仰角，单位度。',
    fromX: '移动起点 X 坐标。', fromY: '移动起点 Y 坐标。', fromZ: '移动起点 Z 坐标。',
    toX: '移动终点 X 坐标。', toY: '移动终点 Y 坐标。', toZ: '移动终点 Z 坐标。',
    easing: '控制运动速度曲线。',
    fromHeight: '升降镜头起始 Y 高度。', toHeight: '升降镜头结束 Y 高度。', centerX: '升降镜头固定 X 坐标。', centerZ: '升降镜头固定 Z 坐标。',
    startPan: '水平旋转起始角度。', endPan: '水平旋转结束角度。', startTilt: '俯仰起始角度。', endTilt: '俯仰结束角度。',
    keyframes: '路径点列表。每个点包含 t、x、y、z、rx、ry、rz、fov。t 范围为 0 到 1。'
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
                ${manager.loop ? '<span class="badge good">Loop</span>' : ''}
                ${manager.locked ? '<span class="badge warn">Locked</span>' : ''}
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
            const transition = Number(slot.transitionDuration || 0) > 0 ? `, 转场 ${slot.transitionDuration}ms ${slot.transitionEasing || 'linear'}` : '';
            return `<button class="inline-copy" data-copy="${slot.clipId}" title="复制 Clip ID">#${slot.clipId}</button> ${label} @ ${slot.startOffset}ms${transition}`;
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
                ${manager.loop ? '<span class="badge good">Loop</span>' : ''}
                ${manager.locked ? '<span class="badge warn">Locked</span>' : ''}
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
            <div id="pose-tools" class="pose-tools full"></div>
            <div id="param-fields" class="full"></div>
        </div>
    `;

    const templateSelect = qs('[data-field="template"]');
    const renderParams = () => {
        renderPoseTools(templateSelect.value);
        renderParamFields(templateSelect.value, data.params || {});
    };
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

function renderPoseTools(template) {
    const root = byId('pose-tools');
    const pose = worldState.pose;
    const current = pose ? `当前玩家：${formatNumber(pose.x)}, ${formatNumber(pose.y)}, ${formatNumber(pose.z)} | pitch ${formatNumber(pose.rotX || 0)}, yaw ${formatNumber(pose.rotY || 0)}` : '进入世界后可读取当前玩家坐标。';
    const buttons = [
        ['camera', '填入机位位置/朝向'],
        ['target', '填入目标点'],
        ['from', '填入起点'],
        ['to', '填入终点'],
        ['path', '追加 PATH 关键帧']
    ];
    root.innerHTML = `
        <div class="tool-card">
            <div><strong>玩家坐标辅助</strong><p class="help">${escapeHtml(current)}</p></div>
            <div class="tool-row">
                ${buttons.map(([action, label]) => `<button type="button" data-pose-action="${action}" ${pose ? '' : 'disabled'}>${label}</button>`).join('')}
            </div>
            <p class="help">当前模板：${escapeHtml(template)}。按钮会尽量填充当前模板存在的参数；不适用的字段会自动跳过。</p>
        </div>
    `;
}

function renderParamFields(template, params) {
    const root = byId('param-fields');
    root.innerHTML = TEMPLATE_FIELDS[template].map(key => {
        const value = params[key] ?? defaultParam(key);
        const label = FIELD_LABELS[key] || key;
        const help = FIELD_HELP[key] || key;
        if (key === 'easing') {
            return `<label title="${escapeAttr(help)}"><span class="field-title">${label}<small>${key}</small></span><select data-param="${key}">${['linear', 'easeIn', 'easeOut', 'easeInOut'].map(v => `<option ${v === value ? 'selected' : ''}>${v}</option>`).join('')}</select><span class="help">${escapeHtml(help)}</span></label>`;
        }
        if (key === 'keyframes') {
            return renderPathEditor(value, params.fov ?? defaultParam('fov'), label, help);
        }
        if (STRING_FIELDS.has(key)) {
            return `<label title="${escapeAttr(help)}"><span class="field-title">${label}<small>${key}</small></span><input data-param="${key}" value="${escapeAttr(value)}"><span class="help">${escapeHtml(help)}</span></label>`;
        }
        return `<label title="${escapeAttr(help)}"><span class="field-title">${label}<small>${key}</small></span><input data-param="${key}" type="number" step="0.1" value="${escapeAttr(value)}"><span class="help">${escapeHtml(help)}</span></label>`;
    }).join('');
}

function renderPathEditor(value, fallbackFov, label, help) {
    const keyframes = normalizePathKeyframes(value, fallbackFov);
    return `
        <div class="path-editor full" data-path-editor title="${escapeAttr(help)}">
            <div class="field-title"><span>${escapeHtml(label)}</span><small>keyframes</small></div>
            <div class="path-head">
                <span>t</span><span>x</span><span>y</span><span>z</span><span>rx</span><span>ry</span><span>rz</span><span>fov</span><span></span>
            </div>
            <div data-path-rows>${renderPathRows(keyframes)}</div>
            <div class="tool-row">
                <button type="button" data-path-add>添加路径点</button>
                <button type="button" data-path-even>均分 t</button>
            </div>
            <p class="help">${escapeHtml(help)}。“追加 PATH 关键帧”会把玩家当前坐标、视角和当前 FOV 写入新路径点。</p>
        </div>
    `;
}

function renderPathRows(keyframes) {
    return keyframes.map((frame, index) => `
        <div class="path-row" data-path-index="${index}">
            ${['t', 'x', 'y', 'z', 'rx', 'ry', 'rz', 'fov'].map(field => `<input data-path-field="${field}" type="number" step="0.01" value="${escapeAttr(frame[field] ?? 0)}">`).join('')}
            <div class="path-actions">
                <button type="button" data-path-up="${index}">↑</button>
                <button type="button" data-path-down="${index}">↓</button>
                <button type="button" class="danger" data-path-remove="${index}">删除</button>
            </div>
        </div>
    `).join('');
}

function openManagerEditor(manager = null) {
    if (!clips.length) {
        toast('请先创建至少一个 Clip，再创建 Manager。', 'bad');
        return;
    }
    const isEdit = !!manager;
    const data = clone(manager || {name: '', width: 1280, height: 720, fps: 30, renderDistance: 12, loop: false, locked: false, clips: [{clipId: clips[0].id, startOffset: 0, transitionDuration: 0, transitionEasing: 'linear'}]});
    byId('editor-title').textContent = isEdit ? `编辑 Manager #${data.id}` : '新建 Manager';
    byId('editor-fields').innerHTML = `
        <div class="form-grid">
            <label>名称<input data-field="name" value="${escapeAttr(data.name)}" placeholder="OBS Sender 会显示为 LiveHelper-名称"></label>
            <label>FPS<input data-field="fps" type="number" min="1" max="240" value="${data.fps || 30}"></label>
            <label>宽度<input data-field="width" type="number" min="16" value="${data.width || 1280}"></label>
            <label>高度<input data-field="height" type="number" min="16" value="${data.height || 720}"></label>
            <label>渲染距离<input data-field="renderDistance" type="number" min="2" value="${data.renderDistance || 12}"></label>
            <label class="checkline"><input data-field="loop" type="checkbox" ${data.loop ? 'checked' : ''}>循环播放</label>
            <label class="checkline"><input data-field="locked" type="checkbox" ${data.locked ? 'checked' : ''}>锁定推流</label>
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
        slots.push({clipId: clips[0].id, startOffset: last ? last.startOffset + (lastClip?.duration || 1000) : 0, transitionDuration: 800, transitionEasing: 'easeInOut'});
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
            renderDistance: positiveNumber('[data-field="renderDistance"]', 12),
            loop: checked('[data-field="loop"]'),
            locked: checked('[data-field="locked"]')
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
            <label>转场(ms)<input data-slot-transition-duration type="number" min="0" value="${slot.transitionDuration || 0}"></label>
            <label>缓动<select data-slot-transition-easing>${['linear', 'easeIn', 'easeOut', 'easeInOut'].map(easing => `<option ${easing === (slot.transitionEasing || 'linear') ? 'selected' : ''}>${easing}</option>`).join('')}</select></label>
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
        startOffset: Number(row.querySelector('[data-slot-offset]').value || 0),
        transitionDuration: Number(row.querySelector('[data-slot-transition-duration]').value || 0),
        transitionEasing: row.querySelector('[data-slot-transition-easing]').value || 'linear'
    })).sort((a, b) => a.startOffset - b.startOffset);
}

function collectPathKeyframes() {
    return [...document.querySelectorAll('.path-row')].map(row => ({
        t: Number(row.querySelector('[data-path-field="t"]').value || 0),
        x: Number(row.querySelector('[data-path-field="x"]').value || 0),
        y: Number(row.querySelector('[data-path-field="y"]').value || 0),
        z: Number(row.querySelector('[data-path-field="z"]').value || 0),
        rx: Number(row.querySelector('[data-path-field="rx"]').value || 0),
        ry: Number(row.querySelector('[data-path-field="ry"]').value || 0),
        rz: Number(row.querySelector('[data-path-field="rz"]').value || 0),
        fov: Number(row.querySelector('[data-path-field="fov"]').value || 70)
    })).sort((a, b) => a.t - b.t);
}

function normalizePathKeyframes(value, fallbackFov = 70) {
    let keyframes = value;
    if (typeof keyframes === 'string') {
        try {
            keyframes = JSON.parse(keyframes || '[]');
        } catch (_) {
            keyframes = [];
        }
    }
    if (!Array.isArray(keyframes) || !keyframes.length) {
        keyframes = defaultParam('keyframes');
    }
    return keyframes.map(frame => ({
        t: roundParam(frame.t ?? 0),
        x: roundParam(frame.x ?? 0),
        y: roundParam(frame.y ?? 0),
        z: roundParam(frame.z ?? 0),
        rx: roundParam(frame.rx ?? 0),
        ry: roundParam(frame.ry ?? 0),
        rz: roundParam(frame.rz ?? 0),
        fov: roundParam(frame.fov ?? fallbackFov)
    })).sort((a, b) => a.t - b.t);
}

function renderPathRowsFromData(keyframes) {
    const rows = qs('[data-path-rows]');
    if (rows) rows.innerHTML = renderPathRows(keyframes);
}

function evenPathTimes(keyframes) {
    keyframes.forEach((frame, index) => {
        frame.t = keyframes.length === 1 ? 0 : roundParam(index / (keyframes.length - 1));
    });
    return keyframes;
}

async function applyPoseToParams(action) {
    const pose = await API.getPose();
    worldState = {ready: true, pose};
    renderWorldStatus();

    if (action === 'camera') {
        setParam('posX', pose.x);
        setParam('posY', pose.y);
        setParam('posZ', pose.z);
        setParam('rotX', pose.rotX);
        setParam('rotY', pose.rotY);
        setParam('rotZ', 0);
    } else if (action === 'target') {
        setParam('targetX', pose.x);
        setParam('targetY', pose.y);
        setParam('targetZ', pose.z);
        setParam('centerX', pose.x);
        setParam('centerZ', pose.z);
    } else if (action === 'from') {
        setParam('fromX', pose.x);
        setParam('fromY', pose.y);
        setParam('fromZ', pose.z);
        setParam('fromHeight', pose.y);
        setParam('startPan', pose.rotY);
        setParam('startTilt', pose.rotX);
    } else if (action === 'to') {
        setParam('toX', pose.x);
        setParam('toY', pose.y);
        setParam('toZ', pose.z);
        setParam('toHeight', pose.y);
        setParam('endPan', pose.rotY);
        setParam('endTilt', pose.rotX);
    } else if (action === 'path') {
        appendPathKeyframe(pose);
    }
    renderPoseTools(val('[data-field="template"]'));
    toast('已应用当前玩家坐标', 'good');
}

function setParam(key, value) {
    const input = qs(`[data-param="${key}"]`);
    if (input) input.value = roundParam(value);
}

function appendPathKeyframe(pose) {
    if (!qs('[data-path-editor]')) return;
    const keyframes = collectPathKeyframes();
    keyframes.push({
        t: 1,
        x: roundParam(pose.x),
        y: roundParam(pose.y),
        z: roundParam(pose.z),
        rx: roundParam(pose.rotX),
        ry: roundParam(pose.rotY),
        rz: 0,
        fov: Number(qs('[data-param="fov"]')?.value || 70)
    });
    renderPathRowsFromData(evenPathTimes(keyframes));
}

function roundParam(value) {
    return Math.round(Number(value) * 100) / 100;
}

function collectParams() {
    const params = {};
    document.querySelectorAll('[data-param]').forEach(input => {
        const key = input.dataset.param;
        if (key === 'easing' || STRING_FIELDS.has(key)) params[key] = input.value;
        else params[key] = Number(input.value);
    });
    if (qs('[data-path-editor]')) {
        params.keyframes = collectPathKeyframes();
    }
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
    if (key === 'trackSpeed') return 8;
    if (STRING_FIELDS.has(key)) return '';
    if (key === 'speed') return 1;
    if (key === 'radius') return 10;
    if (key === 'easing') return 'linear';
    if (key === 'keyframes') return [
        {t: 0, x: 0, y: 80, z: 0, rx: 0, ry: 0, rz: 0, fov: 70},
        {t: 1, x: 10, y: 80, z: 10, rx: 0, ry: 90, rz: 0, fov: 55}
    ];
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

function checked(selector) {
    const el = qs(selector);
    return !!el?.checked;
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
        if (target.dataset.pathAdd !== undefined) {
            const keyframes = collectPathKeyframes();
            const last = keyframes.at(-1) || {x: 0, y: 80, z: 0, rx: 0, ry: 0, rz: 0, fov: 70};
            keyframes.push({...last, t: 1});
            renderPathRowsFromData(evenPathTimes(keyframes));
        }
        if (target.dataset.pathEven !== undefined) {
            renderPathRowsFromData(evenPathTimes(collectPathKeyframes()));
        }
        if (target.dataset.pathRemove !== undefined) {
            const keyframes = collectPathKeyframes();
            keyframes.splice(Number(target.dataset.pathRemove), 1);
            renderPathRowsFromData(evenPathTimes(keyframes));
        }
        if (target.dataset.pathUp !== undefined || target.dataset.pathDown !== undefined) {
            const keyframes = collectPathKeyframes();
            const from = Number(target.dataset.pathUp ?? target.dataset.pathDown);
            const to = from + (target.dataset.pathUp !== undefined ? -1 : 1);
            if (to >= 0 && to < keyframes.length) {
                const [frame] = keyframes.splice(from, 1);
                keyframes.splice(to, 0, frame);
                renderPathRowsFromData(evenPathTimes(keyframes));
            }
        }
        if (target.id === 'refresh-overview') await refreshAll();
        if (target.dataset.copy) await copyText(target.dataset.copy);
        if (target.dataset.poseAction) await applyPoseToParams(target.dataset.poseAction);
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
            if (!worldState.ready) {
                toast('玩家尚未进入世界，无法启动推流', 'bad');
                return;
            }
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
