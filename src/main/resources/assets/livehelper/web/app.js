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
    getPose: () => request('/api/pose'),
    getTemplates: () => request('/api/templates'),
    getTriggers: () => request('/api/triggers'),
    getTriggerSchema: () => request('/api/trigger-schema'),
    createTrigger: data => request('/api/triggers', jsonRequest('POST', data)),
    updateTrigger: (id, data) => request(`/api/triggers/${id}`, jsonRequest('PUT', data)),
    deleteTrigger: id => request(`/api/triggers/${id}`, {method: 'DELETE'})
};

/**
 * 模板参数 schema，来自 /api/templates（Java 侧 TemplateSchemas 为唯一权威）。
 * 结构：{ [templateName]: FieldDef[] }，FieldDef 见 FieldDef.java。
 *
 * 取代原先散落在此处的四张硬编码表（TEMPLATE_FIELDS / STRING_FIELDS / FIELD_LABELS / FIELD_HELP）
 * 以及 defaultParam() 里的默认值分支：新增模板或改默认值只需改 Java 一处。
 */
let templateSchema = {};
let schemaLoaded = false;

/** 关键帧字段无默认值，新建 Clip 时给一段可用的起止点作为起点（仅 UI 种子，不属于 schema）。 */
const KEYFRAME_STARTER = [
    {t: 0, x: 0, y: 80, z: 0, rx: 0, ry: 0, rz: 0, fov: 70},
    {t: 1, x: 10, y: 80, z: 10, rx: 0, ry: 90, rz: 0, fov: 55}
];

/** 缓动候选值：从 schema 里任一模板的 easing 字段取，避免前端再写一份列表。 */
function easingValues() {
    for (const fields of Object.values(templateSchema)) {
        const easing = fields.find(f => f.key === 'easing');
        if (easing && Array.isArray(easing.values) && easing.values.length) {
            return easing.values;
        }
    }
    return ['linear'];
}

function templateNames() {
    return Object.keys(templateSchema);
}

function fieldsFor(template) {
    return templateSchema[template] || [];
}

function fieldDef(template, key) {
    return fieldsFor(template).find(f => f.key === key) || null;
}

/** 取参数值：优先用已存配置，其次用 schema 默认值。 */
function paramValue(template, key, params) {
    if (params && Object.prototype.hasOwnProperty.call(params, key)) return params[key];
    const def = fieldDef(template, key);
    return def && def.def !== undefined ? def.def : '';
}

async function loadTemplateSchema() {
    try {
        const payload = await API.getTemplates();
        const list = payload.templates || [];
        templateSchema = {};
        list.forEach(entry => {
            templateSchema[entry.template] = entry.fields || [];
        });
        schemaLoaded = true;
        if (payload.missingSchema) {
            console.warn('Templates without schema:', payload.missingSchema);
        }
    } catch (error) {
        schemaLoaded = false;
        console.error('Failed to load template schema', error);
    }
}

/** 触发器条件 schema，结构与 templateSchema 同构，额外带 label。 */
let triggerSchema = {};
let triggerSchemaLoaded = false;

async function loadTriggerSchema() {
    try {
        const payload = await API.getTriggerSchema();
        triggerSchema = {};
        (payload.types || []).forEach(entry => {
            triggerSchema[entry.type] = {label: entry.label, fields: entry.fields || []};
        });
        triggerSchemaLoaded = true;
        if (payload.missingSchema) {
            console.warn('Trigger types without schema:', payload.missingSchema);
        }
    } catch (error) {
        triggerSchemaLoaded = false;
        console.error('Failed to load trigger schema', error);
    }
}

function triggerTypeNames() {
    return Object.keys(triggerSchema);
}

function triggerFields(type) {
    return (triggerSchema[type] || {}).fields || [];
}

function triggerLabel(type) {
    return (triggerSchema[type] || {}).label || type;
}

let clips = [];
let managers = [];
let triggers = [];
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
        if (!schemaLoaded) await loadTemplateSchema();
        if (!triggerSchemaLoaded) await loadTriggerSchema();
        clips = await API.getClips();
        managers = await API.getManagers();
        try {
            triggers = await API.getTriggers();
        } catch (_) {
            triggers = [];
        }
        try {
            worldState = {ready: true, pose: await API.getPose()};
        } catch (_) {
            worldState = {ready: false, pose: null};
        }
        statuses = new Map(await Promise.all(managers.map(async manager => {
            try {
                return [manager.id, await API.getManagerStatus(manager.id)];
            } catch (_) {
                return [manager.id, {status: 'unknown'}];
            }
        })));
        renderWorldStatus();
        renderOverview();
        renderClips();
        renderManagers();
        renderTriggers();
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

/**
 * 单 sender 架构下「在跑」不等于「正在推送」：OBS 里只有一路输出（cue 优先、其次 base）。
 * 其余在跑的机位只是待命，把它们显示成独立推流会让人误以为 OBS 里有多个画面。
 */
function streamRole(manager) {
    const info = statuses.get(manager.id) || {status: 'stopped'};
    const running = info.status === 'running';
    const onAir = running && info.outputOwnerId === manager.id;
    let note = '';
    if (onAir) {
        note = info.cueManagerId === manager.id ? '正在推送 · 切机位' : '正在推送 · 常驻机位';
    } else if (running) {
        note = '待命，未进 OBS';
    }
    return {running, onAir, note};
}

function roleBadge(role) {
    if (role.onAir) return '<span class="badge good">ON AIR</span>';
    return role.running ? '<span class="badge warn">待命</span>' : '<span class="badge stopped">已停止</span>';
}

function renderOverview() {
    const root = byId('overview-content');
    root.innerHTML = '';
    if (!managers.length) {
        root.innerHTML = '<div class="empty">还没有 Manager。先创建 Clip，再创建 Manager 编排时间线。</div>';
        return;
    }
    managers.forEach(manager => {
        const role = streamRole(manager);
        const totalDuration = managerDuration(manager);
        root.appendChild(card(`
            <h3>${escapeHtml(manager.name)}</h3>
            <div class="badge-row">
                <span class="badge id">Manager #${manager.id}</span>
                ${roleBadge(role)}
                <span class="badge">${manager.width}x${manager.height}</span>
                <span class="badge">${manager.fps}fps</span>
                ${manager.loop ? '<span class="badge good">Loop</span>' : ''}
                ${manager.locked ? '<span class="badge warn">Locked</span>' : ''}
                <span class="badge">${totalDuration}ms</span>
            </div>
            ${role.note ? `<p class="role-line">${role.note}</p>` : ''}
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

function renderTriggers() {
    const root = byId('triggers-list');
    root.innerHTML = '';
    if (!triggerSchemaLoaded) {
        root.innerHTML = '<div class="empty">未能读取触发器 schema，请确认游戏内 Mod 已加载后刷新页面。</div>';
        return;
    }
    if (!triggers.length) {
        root.innerHTML = '<div class="empty">暂无触发规则。条件命中时会把推流自动切到指定 Manager。</div>';
        return;
    }
    triggers.forEach(rule => {
        const manager = managers.find(m => m.id === rule.targetManager);
        const managerLabel = manager ? manager.name : '(缺失)';
        const badges = [
            `<span class="badge">${escapeHtml(rule.type)}</span>`,
            `<span class="badge">${escapeHtml(triggerLabel(rule.type))}</span>`,
            `<span class="badge">delay ${rule.delayMs || 0}ms</span>`,
            `<span class="badge">cooldown ${rule.cooldownMs || 0}ms</span>`
        ];
        if (rule.onEnter) badges.push('<span class="badge">仅进入时</span>');
        if (rule.exitBuffer > 0) badges.push(`<span class="badge">缓冲 ${rule.exitBuffer}</span>`);
        if (!rule.repeatable) badges.push('<span class="badge">一次性</span>');

        root.appendChild(card(`
            <h3>${escapeHtml(rule.name || `(规则 #${rule.id})`)}</h3>
            <div class="badge-row">
                <button class="badge id copy-badge" data-copy="${rule.id}" title="复制规则 ID">规则 #${rule.id}</button>
                ${badges.join('')}
                ${rule.enabled ? '' : '<span class="badge">已禁用</span>'}
            </div>
            <p class="help">命中后切到 Manager #${rule.targetManager} · ${escapeHtml(managerLabel)}</p>
            <pre class="params">${escapeHtml(JSON.stringify(rule.conditions || {}, null, 2))}</pre>
            <div class="card-actions">
                <button data-edit-trigger="${rule.id}">编辑</button>
                <button data-toggle-trigger="${rule.id}" data-enabled="${rule.enabled}">${rule.enabled ? '禁用' : '启用'}</button>
                <button class="danger" data-delete-trigger="${rule.id}">删除</button>
            </div>
        `));
    });
}

function openTriggerEditor(rule = null) {
    const isEdit = !!rule;
    const data = clone(rule || {
        name: '', type: triggerTypeNames()[0] || 'damage', conditions: {},
        targetManager: managers[0]?.id || 1, enabled: true, repeatable: true,
        delayMs: 0, cooldownMs: 0, onEnter: false, exitBuffer: 0
    });
    byId('editor-title').textContent = isEdit ? `编辑触发规则 #${data.id}` : '新建触发规则';
    byId('editor-fields').innerHTML = `
        <div class="form-grid">
            <label>名称<input data-field="name" value="${escapeAttr(data.name)}" placeholder="例如 Boss 击杀切 kill cam"></label>
            <label>类型<select data-field="type">${triggerTypeNames().map(t => `
                <option value="${t}" ${t === data.type ? 'selected' : ''}>${escapeHtml(t)} · ${escapeHtml(triggerLabel(t))}</option>`).join('')}</select></label>
            <label class="full">目标 Manager<select data-field="targetManager">${managers.map(m => `
                <option value="${m.id}" ${m.id === data.targetManager ? 'selected' : ''}>#${m.id} ${escapeHtml(m.name)}</option>`).join('')}</select></label>
            <label>延迟(ms)<input data-field="delayMs" type="number" min="0" step="50" value="${data.delayMs || 0}"></label>
            <label>冷却(ms)<input data-field="cooldownMs" type="number" min="0" step="100" value="${data.cooldownMs || 0}"></label>
            <label class="checkline"><input data-field="enabled" type="checkbox" ${data.enabled !== false ? 'checked' : ''}>启用</label>
            <label class="checkline"><input data-field="repeatable" type="checkbox" ${data.repeatable !== false ? 'checked' : ''}>可重复触发</label>
            <label class="checkline"><input data-field="onEnter" type="checkbox" ${data.onEnter ? 'checked' : ''}>仅进入区域时触发</label>
            <label>离开缓冲(格)<input data-field="exitBuffer" type="number" min="0" step="0.5" value="${data.exitBuffer || 0}"></label>
            <div id="trigger-conditions" class="full"></div>
        </div>
    `;

    const typeSelect = qs('[data-field="type"]');
    const renderConditions = () => {
        renderTriggerConditions(typeSelect.value, data.conditions || {});
    };
    typeSelect.addEventListener('change', renderConditions);
    renderConditions();

    showEditor(async () => {
        const payload = {
            id: isEdit ? data.id : 0,
            name: val('[data-field="name"]') || '(未命名)',
            type: val('[data-field="type"]'),
            conditions: collectTriggerConditions(),
            targetManager: positiveNumber('[data-field="targetManager"]', 1),
            enabled: checked('[data-field="enabled"]'),
            repeatable: checked('[data-field="repeatable"]'),
            delayMs: positiveNumber('[data-field="delayMs"]', 0),
            cooldownMs: positiveNumber('[data-field="cooldownMs"]', 0),
            onEnter: checked('[data-field="onEnter"]'),
            exitBuffer: Number(val('[data-field="exitBuffer"]')) || 0
        };
        if (isEdit) await API.updateTrigger(data.id, payload);
        else await API.createTrigger(payload);
        toast('触发规则已保存', 'good');
        await refreshAll();
    });
}

/** 条件表单完全由 trigger-schema 驱动，新增触发类型无需改前端。 */
function renderTriggerConditions(type, conditions) {
    const root = byId('trigger-conditions');
    const fields = triggerFields(type);
    if (!fields.length) {
        root.innerHTML = '<p class="help">该触发类型没有可配置条件，命中即触发。</p>';
        return;
    }
    root.innerHTML = `<div class="form-grid">${fields.map(field => {
        const value = conditions[field.key] !== undefined && conditions[field.key] !== null
            ? conditions[field.key] : (field.def !== undefined ? field.def : '');
        const head = `<span class="field-title">${escapeHtml(field.label)}<small>${escapeHtml(field.key)}</small></span>`;
        const foot = `<span class="help">${escapeHtml(field.help)}</span>`;
        if (field.type === 'enum') {
            const values = field.values || [];
            return `<label title="${escapeAttr(field.help)}">${head}<select data-cond="${field.key}" data-cond-type="enum">${
                values.map(v => `<option ${String(v) === String(value) ? 'selected' : ''}>${escapeHtml(v)}</option>`).join('')
            }</select>${foot}</label>`;
        }
        if (field.type === 'boolean') {
            return `<label class="checkline">${head}<input data-cond="${field.key}" data-cond-type="boolean" type="checkbox" ${
                value ? 'checked' : ''}>${foot}</label>`;
        }
        if (field.type === 'number') {
            return `<label title="${escapeAttr(field.help)}">${head}<input data-cond="${field.key}" data-cond-type="number" type="number" step="${
                field.step || 0.1}" value="${escapeAttr(value)}">${foot}</label>`;
        }
        return `<label title="${escapeAttr(field.help)}">${head}<input data-cond="${field.key}" data-cond-type="string" value="${escapeAttr(value)}">${foot}</label>`;
    }).join('')}</div>`;
}

function collectTriggerConditions() {
    const conditions = {};
    document.querySelectorAll('[data-cond]').forEach(input => {
        const key = input.dataset.cond;
        const type = input.dataset.condType || 'string';
        if (type === 'boolean') {
            conditions[key] = input.checked;
        } else if (type === 'number') {
            const n = Number(input.value);
            if (Number.isFinite(n)) conditions[key] = n;
        } else {
            conditions[key] = input.value;
        }
    });
    return conditions;
}

function renderManagers() {
    const root = byId('managers-list');
    root.innerHTML = '';
    if (!managers.length) {
        root.innerHTML = '<div class="empty">暂无 Manager。创建后启动任一机位，即会在 OBS 中建立唯一的 <strong>LiveHelper</strong> sender。</div>';
        return;
    }
    managers.forEach(manager => {
        const role = streamRole(manager);
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
                ${roleBadge(role)}
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
            <label class="full">模板<select data-field="template">${templateNames().map(t => `<option ${t === data.template ? 'selected' : ''}>${t}</option>`).join('')}</select></label>
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
    if (!schemaLoaded) {
        root.innerHTML = `<div class="tool-card"><div><strong>无法读取模板 schema</strong>
            <p class="help">未能从 /api/templates 取得字段定义。请确认游戏内 Mod 已加载，然后刷新页面。</p></div></div>`;
        return;
    }

    const fields = fieldsFor(template);
    if (!fields.length) {
        root.innerHTML = `<div class="tool-card"><div><strong>该模板没有可配置参数</strong>
            <p class="help">${escapeHtml(template)} 不接受额外参数。</p></div></div>`;
        return;
    }

    const fallbackFov = paramValue(template, 'fov', params) || 70;
    root.innerHTML = fields.map(field => renderParamField(template, field, params, fallbackFov)).join('');
}

function renderParamField(template, field, params, fallbackFov) {
    const {key, label, help} = field;
    const value = paramValue(template, key, params);
    const head = `<span class="field-title">${escapeHtml(label)}<small>${escapeHtml(key)}</small></span>`;
    const foot = `<span class="help">${escapeHtml(help)}</span>`;
    const title = ` title="${escapeAttr(help)}"`;

    if (field.type === 'keyframes') {
        return renderPathEditor(value || KEYFRAME_STARTER, fallbackFov, label, help);
    }
    if (field.type === 'enum') {
        const values = field.values || [];
        return `<label${title}>${head}<select data-param="${key}" data-param-type="enum">${
            values.map(v => `<option ${String(v) === String(value) ? 'selected' : ''}>${escapeHtml(v)}</option>`).join('')
        }</select>${foot}</label>`;
    }
    if (field.type === 'string') {
        return `<label${title}>${head}<input data-param="${key}" data-param-type="string" value="${escapeAttr(value)}">${foot}</label>`;
    }
    if (field.type === 'boolean') {
        return `<label class="checkline"${title}>${head}<input data-param="${key}" data-param-type="boolean" type="checkbox" ${
            value ? 'checked' : ''
        }>${foot}</label>`;
    }

    // number
    const step = field.step || 0.1;
    const min = field.min !== undefined && field.min !== null ? ` min="${field.min}"` : '';
    const max = field.max !== undefined && field.max !== null ? ` max="${field.max}"` : '';
    return `<label${title}>${head}<input data-param="${key}" data-param-type="number" type="number" step="${step}"${
        min}${max} value="${escapeAttr(value)}">${foot}</label>`;
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
    const data = clone(manager || {name: '', width: 1280, height: 720, fps: 30, renderDistance: 12, loop: false, loopMode: 'repeat', locked: false, clips: [{clipId: clips[0].id, startOffset: 0, transitionDuration: 0, transitionEasing: 'linear'}]});
    byId('editor-title').textContent = isEdit ? `编辑 Manager #${data.id}` : '新建 Manager';
    byId('editor-fields').innerHTML = `
        <div class="form-grid">
            <label>名称<input data-field="name" value="${escapeAttr(data.name)}" placeholder="仅用于区分机位，不影响 OBS sender 名"></label>
            <label>FPS<input data-field="fps" type="number" min="1" max="240" value="${data.fps || 30}"></label>
            <label>宽度<input data-field="width" type="number" min="16" value="${data.width || 1280}"></label>
            <label>高度<input data-field="height" type="number" min="16" value="${data.height || 720}"></label>
            <label>渲染距离<input data-field="renderDistance" type="number" min="2" value="${data.renderDistance || 12}"></label>
            <label class="checkline"><input data-field="loop" type="checkbox" ${data.loop ? 'checked' : ''}>循环播放</label>
            <label>循环方式<select data-field="loopMode">${['repeat', 'pingpong'].map(mode => `
                <option value="${mode}" ${(data.loopMode || 'repeat') === mode ? 'selected' : ''}>${
                    mode === 'pingpong' ? 'pingpong（往复折返）' : 'repeat（从头重播）'
                }</option>`).join('')}</select>
                <span class="help">pingpong 会把整条时间线折返播放，首尾姿态接近时观感最好，适合来回扫摇的监控机位。</span>
            </label>
            <label class="checkline"><input data-field="locked" type="checkbox" ${data.locked ? 'checked' : ''}>锁定（启动别的机位时不被停掉，切机位也不会自动返回）</label>
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
            loopMode: val('[data-field="loopMode"]') || 'repeat',
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
            <label>缓动<select data-slot-transition-easing>${easingValues().map(easing => `<option ${easing === (slot.transitionEasing || 'linear') ? 'selected' : ''}>${easing}</option>`).join('')}</select></label>
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
        keyframes = KEYFRAME_STARTER;
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
        const type = input.dataset.paramType || 'number';
        if (type === 'boolean') {
            params[key] = input.checked;
        } else if (type === 'number') {
            const n = Number(input.value);
            // 空输入不写进 params，交给后端按 schema 默认值兜底，避免写入 NaN。
            if (Number.isFinite(n)) params[key] = n;
        } else {
            params[key] = input.value;
        }
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
        if (target.id === 'new-trigger') openTriggerEditor();
        if (target.dataset.editTrigger) openTriggerEditor(triggers.find(t => t.id === Number(target.dataset.editTrigger)));
        if (target.dataset.toggleTrigger) {
            const id = Number(target.dataset.toggleTrigger);
            const enable = target.dataset.enabled !== 'true';
            const current = triggers.find(t => t.id === id);
            if (current) {
                await API.updateTrigger(id, {...current, enabled: enable});
                toast(enable ? '触发规则已启用' : '触发规则已禁用', 'good');
                await refreshAll();
            }
        }
        if (target.dataset.deleteTrigger && confirm('删除这个触发规则？')) {
            await API.deleteTrigger(Number(target.dataset.deleteTrigger));
            toast('触发规则已删除', 'good');
            await refreshAll();
        }
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
