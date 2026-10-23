/* ===== API Layer ===== */
const API_BASE = '/api/analysis';

const api = {
    async health() {
        const ctrl = new AbortController();
        const tid = setTimeout(() => ctrl.abort(), 180000);
        try {
            const res = await fetch('/api/health', { signal: ctrl.signal });
            return res.ok;
        } catch {
            return false;
        } finally {
            clearTimeout(tid);
        }
    },

    async list() {
        const res = await fetch(`${API_BASE}/list`);
        if (!res.ok) throw new Error('list failed');
        return res.json();
    },

    async latest() {
        const res = await fetch(`${API_BASE}/latest`);
        if (!res.ok) throw new Error('latest failed');
        return res.json();
    },

    async latestByName(name) {
        try {
            const res = await fetch(`${API_BASE}/latest/${encodeURIComponent(name)}`);
            if (!res.ok) return null;
            return res.json();
        } catch {
            return null;
        }
    },

    async params(name) {
        const res = await fetch(`${API_BASE}/params/${encodeURIComponent(name)}`);
        if (!res.ok) throw new Error('params failed');
        return res.json();
    },

    async sync(name, params) {
        const ctrl = new AbortController();
        const tid = setTimeout(() => ctrl.abort(), 180000);
        try {
            const res = await fetch(`${API_BASE}/sync/${encodeURIComponent(name)}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(params),
                signal: ctrl.signal
            });
            if (res.status === 429) throw new Error('rate_limited');
            if (!res.ok) throw new Error(`sync failed: ${res.status}`);
            return res.json();
        } finally {
            clearTimeout(tid);
        }
    },

    async symbols() {
        const res = await fetch(`${API_BASE}/symbols`);
        if (!res.ok) throw new Error('symbols failed');
        return res.json();
    }
};

/* ===== Market API Layer (/api/market) ===== */
const MARKET_BASE = '/api/market';
const marketApi = {
    async lsrRanking(type, limit) {
        const res = await fetch(`${MARKET_BASE}/lsr/ranking?type=${encodeURIComponent(type)}&period=1h&limit=${limit}`);
        if (!res.ok) throw new Error('lsr ranking failed');
        return res.json();
    },
    async lsr(symbol) {
        const res = await fetch(`${MARKET_BASE}/lsr/${encodeURIComponent(symbol)}?period=1h&limit=1`);
        if (!res.ok) throw new Error('lsr failed');
        return res.json();
    },
    async ticker(kind, limit) {
        const res = await fetch(`${MARKET_BASE}/ticker/${kind}?limit=${limit}`);
        if (!res.ok) throw new Error('ticker failed');
        return res.json();
    },
    async tickerOne(symbol) {
        try {
            const res = await fetch(`${MARKET_BASE}/ticker/${encodeURIComponent(symbol)}`);
            return res.ok ? res.json() : null;
        } catch { return null; }
    },
    async funding(limit, order) {
        const res = await fetch(`${MARKET_BASE}/premium/funding-ranking?limit=${limit}&order=${order}`);
        if (!res.ok) throw new Error('funding failed');
        return res.json();
    },
    async premiumOne(symbol) {
        try {
            const res = await fetch(`${MARKET_BASE}/premium/${encodeURIComponent(symbol)}`);
            return res.ok ? res.json() : null;
        } catch { return null; }
    }
};

/* ===== Market constants ===== */
const RATIO_LABELS = {
    TOP_POSITION: '大户持仓多空比',
    TOP_ACCOUNT: '大户账户多空比',
    GLOBAL_ACCOUNT: '散户账户多空比',
    TAKER: '主动买卖比'
};
const TICKER_TITLES = { gainers: '24h 涨幅榜', losers: '24h 跌幅榜', volume: '24h 成交额榜' };
const MARKET_ITEMS = [
    { view: 'market-lsr', label: '多空比' },
    { view: 'market-gainers', label: '涨幅榜' },
    { view: 'market-losers', label: '跌幅榜' },
    { view: 'market-volume', label: '成交额榜' },
    { view: 'market-funding', label: '资金费率' }
];

/* ===== State ===== */
const state = {
    analyzers: [],
    latestReports: {},
    currentView: 'overview',
    currentAnalyzer: null,
    pollTimer: null,
    pollEnabled: true,
    // 行情视图参数
    lsrType: 'TOP_POSITION',
    marketLimit: 50,
    fundingOrder: 'desc'
};

/* ===== Sidebar ===== */
function renderSidebar() {
    const nav = document.querySelector('.sidebar-nav');
    let html = `<button class="nav-item${state.currentView === 'overview' ? ' active' : ''}" data-view="overview">总览</button>`;

    html += `<div class="nav-group-label">分析器</div>`;
    for (const name of state.analyzers) {
        const report = state.latestReports[name];
        const count = report ? report.matchedCount : 0;
        const badgeClass = count === 0 ? 'nav-badge zero' : 'nav-badge';
        const isActive = state.currentView === 'detail' && state.currentAnalyzer === name;
        html += `<button class="nav-item${isActive ? ' active' : ''}" data-view="detail" data-name="${escHtml(name)}">
            <span>${escHtml(name)}</span>
            <span class="${badgeClass}">${count}</span>
        </button>`;
    }

    html += `<div class="nav-group-label">行情</div>`;
    for (const m of MARKET_ITEMS) {
        const isActive = state.currentView === m.view;
        html += `<button class="nav-item${isActive ? ' active' : ''}" data-view="${m.view}">
            <span>${escHtml(m.label)}</span>
        </button>`;
    }

    nav.innerHTML = html;
    nav.querySelectorAll('.nav-item').forEach(el => {
        el.addEventListener('click', () => {
            const view = el.dataset.view;
            if (view === 'overview') {
                state.currentView = 'overview';
                state.currentAnalyzer = null;
            } else if (view === 'detail') {
                state.currentView = 'detail';
                state.currentAnalyzer = el.dataset.name;
            } else {
                state.currentView = view; // market-*
                state.currentAnalyzer = null;
            }
            renderSidebar();
            renderCurrentView();
        });
    });
}

/* ===== View Router ===== */
function renderCurrentView() {
    switch (state.currentView) {
        case 'detail': renderDetail(state.currentAnalyzer); break;
        case 'market-lsr': renderMarketLsr(); break;
        case 'market-gainers': renderTickerBoard('gainers'); break;
        case 'market-losers': renderTickerBoard('losers'); break;
        case 'market-volume': renderTickerBoard('volume'); break;
        case 'market-funding': renderFunding(); break;
        default: renderOverview();
    }
}

/* ===== Health Check ===== */
async function checkHealth() {
    const dot = document.getElementById('health-indicator');
    const txt = document.getElementById('health-text');
    const ok = await api.health();
    if (ok) {
        dot.className = 'status-dot online';
        txt.textContent = '在线';
    } else {
        dot.className = 'status-dot offline';
        txt.textContent = '离线';
    }
}

/* ===== Symbol Count ===== */
async function loadSymbolCount() {
    try {
        const data = await api.symbols();
        const count = Array.isArray(data) ? data.length : (data.count || '--');
        document.getElementById('symbol-count').textContent = `合约: ${count}`;
    } catch {
        document.getElementById('symbol-count').textContent = '合约: --';
    }
}

/* ===== Helpers ===== */
function escHtml(str) {
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function formatTime(t) {
    if (!t) return '--';
    try { return new Date(t).toLocaleString('zh-CN'); } catch { return t; }
}

function clampLimit(v) {
    let n = parseInt(v, 10);
    if (isNaN(n)) n = 50;
    return Math.min(Math.max(n, 1), 500);
}

/** 大额成交量 → 亿/万 简写。 */
function fmtVol(n) {
    if (n == null || isNaN(n)) return '--';
    if (n >= 1e8) return (n / 1e8).toFixed(2) + '亿';
    if (n >= 1e4) return (n / 1e4).toFixed(2) + '万';
    return n.toFixed(0);
}

/** 带正负色的百分比 span (v 已是百分比数值)。 */
function pctSpan(v, digits = 2) {
    if (v == null || isNaN(v)) return '<span class="neutral">--</span>';
    const cls = v > 0 ? 'positive' : v < 0 ? 'negative' : 'neutral';
    const sign = v > 0 ? '+' : '';
    return `<span class="${cls}">${sign}${v.toFixed(digits)}%</span>`;
}

/* ===== Init ===== */
async function init() {
    checkHealth();
    loadSymbolCount();

    try {
        const [analyzerList, latestMap] = await Promise.all([api.list(), api.latest()]);
        state.analyzers = analyzerList || [];
        state.latestReports = latestMap || {};
    } catch (e) {
        console.error('Init load failed', e);
        state.analyzers = [];
        state.latestReports = {};
    }

    renderSidebar();
    renderOverview();
    startPolling();

    document.getElementById('btn-refresh-symbols').addEventListener('click', async () => {
        const btn = document.getElementById('btn-refresh-symbols');
        btn.disabled = true;
        btn.textContent = '刷新中...';
        try {
            // GET /symbols 本身就是每次从MySQL实时读取, 无需单独的 refresh 接口
            await loadSymbolCount();
        } catch (e) {
            console.error('Refresh symbols failed', e);
        } finally {
            btn.disabled = false;
            btn.textContent = '刷新合约列表';
        }
    });
}

/* ===== Overview ===== */
function renderOverview() {
    const content = document.getElementById('content');
    if (state.analyzers.length === 0) {
        content.innerHTML = `<div class="empty-state">暂无分析器数据</div>`;
        return;
    }
    let cards = '';
    for (const name of state.analyzers) {
        const report = state.latestReports[name];
        const count = report ? report.matchedCount : 0;
        const badgeClass = count === 0 ? 'card-badge zero' : 'card-badge';
        const time = report ? formatTime(report.analysisTime) : '--';
        let coinsHtml = '';
        if (report && report.coins && report.coins.length > 0) {
            const show = report.coins.slice(0, 3);
            for (const c of show) coinsHtml += `<span class="coin-tag">${escHtml(c.symbol)}</span>`;
            if (report.coins.length > 3) {
                coinsHtml += `<span class="coin-tag-more">+${report.coins.length - 3}</span>`;
            }
        } else {
            coinsHtml = `<span style="color:#aaa;font-size:12px;">无符合条件</span>`;
        }
        cards += `
        <div class="card" data-name="${escHtml(name)}">
            <div class="card-header">
                <span class="card-title">${escHtml(name)}</span>
                <span class="${badgeClass}">${count}</span>
            </div>
            <div class="card-meta">更新: ${escHtml(time)}</div>
            <div class="card-coins">${coinsHtml}</div>
        </div>`;
    }
    content.innerHTML = `<div class="page-title">分析器总览</div><div class="overview-grid">${cards}</div>`;
    content.querySelectorAll('.card').forEach(card => {
        card.addEventListener('click', () => {
            const name = card.dataset.name;
            state.currentView = 'detail';
            state.currentAnalyzer = name;
            renderSidebar();
            renderDetail(name);
        });
    });
}

/* ===== Detail View ===== */
async function renderDetail(name) {
    const content = document.getElementById('content');
    content.innerHTML = `<div class="loading">加载中...</div>`;

    let paramSpec = null;
    let report = null;
    try {
        [paramSpec, report] = await Promise.all([api.params(name), api.latestByName(name)]);
    } catch (e) {
        content.innerHTML = `<div class="error-msg">加载失败: ${escHtml(String(e))}</div>`;
        return;
    }

    const isShortTerm = name === '短期急涨';
    const pollChecked = state.pollEnabled ? 'checked' : '';
    const pollSection = isShortTerm ? `
        <label class="poll-toggle">
            <input type="checkbox" id="poll-checkbox" ${pollChecked}>
            自动刷新 (30s)
        </label>` : '';

    let paramsHtml = '';
    if (paramSpec && paramSpec.params) {
        for (const [key, spec] of Object.entries(paramSpec.params)) {
            const inputType = (spec.type === 'int' || spec.type === 'integer' || spec.type === 'double' || spec.type === 'float' || spec.type === 'number') ? 'number' : 'text';
            const step = (spec.type === 'double' || spec.type === 'float') ? 'step="any"' : '';
            paramsHtml += `
            <div class="form-group">
                <label for="param-${escHtml(key)}">${escHtml(key)}</label>
                <input type="${inputType}" id="param-${escHtml(key)}" data-key="${escHtml(key)}"
                    data-type="${escHtml(spec.type || 'string')}"
                    value="${escHtml(String(spec.default ?? ''))}"
                    placeholder="${escHtml(String(spec.default ?? ''))}" ${step}>
                <span class="form-hint">${escHtml(spec.description || '')}</span>
            </div>`;
        }
    }

    const desc = paramSpec ? escHtml(paramSpec.description || '') : '';

    content.innerHTML = `
        <div class="page-title">${escHtml(name)}</div>
        <div class="detail-section">
            <div class="detail-title">参数配置${desc ? ' — ' + desc : ''}</div>
            <div class="param-form" id="param-form">${paramsHtml || '<span style="color:#aaa">无可配置参数</span>'}</div>
            <div class="detail-actions">
                <button class="btn-primary" id="btn-execute">执行分析</button>
                <button class="btn-default" id="btn-reset">重置参数</button>
                ${pollSection}
            </div>
            <div id="execute-error"></div>
        </div>
        <div class="detail-section" id="result-section">
            <div class="detail-title">分析结果</div>
            <div id="result-content"></div>
        </div>`;

    renderResultTable(report);
    bindDetailEvents(name, paramSpec);
}

/* ===== Result Table ===== */
function renderResultTable(report) {
    const container = document.getElementById('result-content');
    if (!container) return;
    if (!report || !report.coins || report.coins.length === 0) {
        container.innerHTML = `<div class="empty-state">暂无符合条件的交易对</div>`;
        return;
    }

    const hasScore = report.coins.some(c => c.score != null && c.score !== undefined);
    const isReversal = report.analysisType === '反转做多';
    const isNDayLow = report.analysisType === 'N日最低';
    const sorted = [...report.coins].sort((a, b) => {
        if (hasScore) return (b.score || 0) - (a.score || 0);
        return (b.changePercent || 0) - (a.changePercent || 0);
    });

    let leopardCount = 0;
    let integerCount = 0;

    let rows = '';
    for (const coin of sorted) {
        const pct = coin.changePercent != null ? coin.changePercent : null;
        let pctHtml = '<span class="neutral">--</span>';
        if (pct != null) {
            const cls = pct > 0 ? 'positive' : pct < 0 ? 'negative' : 'neutral';
            const sign = pct > 0 ? '+' : '';
            pctHtml = `<span class="${cls}">${sign}${pct.toFixed(2)}%</span>`;
        }
        let scoreCell = '';
        if (hasScore) {
            const s = coin.score || 0;
            const cls = s >= 80 ? 'score-high' : s >= 60 ? 'score-mid' : 'score-low';
            scoreCell = `<td><span class="score-badge ${cls}">${s}</span></td>`;
        }

        // N日最低: parse detail for low price and date
        let lowPriceCell = '';
        let lowDateCell = '';
        let rowClass = '';
        if (isNDayLow && coin.detail) {
            const d = coin.detail;
            const lowMatch = d.match(/最低([\d.]+)\((\d{2}-\d{2})\)/);
            const lowPrice = lowMatch ? lowMatch[1] : '';
            const lowDate = lowMatch ? lowMatch[2] : '';
            const isLeopard = d.includes('[豹子号]');
            const isInteger = d.includes('[整数关口]');

            if (isLeopard) { rowClass = ' row-leopard'; leopardCount++; }
            else if (isInteger) { rowClass = ' row-integer'; integerCount++; }

            let lpClass = '';
            if (isLeopard) lpClass = ' class="price-leopard"';
            else if (isInteger) lpClass = ' class="price-integer"';
            lowPriceCell = `<td${lpClass}>${lowPrice}</td>`;
            lowDateCell = `<td>${lowDate}</td>`;
        }

        // For reversal analyzer: highlight key prices in detail
        let detailHtml = escHtml(coin.detail || '');
        if (isReversal && coin.detail) {
            detailHtml = coin.detail
                .replace(/入场([\d.]+)/g, '<span class="hl-entry">入场$1</span>')
                .replace(/止盈([\d.]+)\(([^)]+)\)/g, '<span class="hl-tp">止盈$1($2)</span>')
                .replace(/止损([\d.]+)\(([^)]+)\)/g, '<span class="hl-sl">止损$1($2)</span>')
                .replace(/盈亏比([\d.]+)/g, '<span class="hl-rr">盈亏比$1</span>')
                .replace(/\[([^\]]+)\]/, '<span class="hl-date">[$1]</span>');
        }

        rows += `<tr class="${rowClass}">
            <td><strong>${escHtml(coin.symbol)}</strong></td>
            <td>${coin.currentPrice != null ? escHtml(String(coin.currentPrice)) : '--'}</td>
            <td>${pctHtml}</td>
            ${scoreCell}
            ${lowPriceCell}
            ${lowDateCell}
            <td class="detail-cell">${detailHtml}</td>
        </tr>`;
    }

    const scoreHeader = hasScore ? '<th>评分</th>' : '';
    const priceHeader = isReversal ? '<th>入场价</th>' : '<th>当前价格</th>';
    const chgHeader = isReversal ? '<th>反转涨幅</th>' : (isNDayLow ? '<th>距最低</th>' : '<th>涨跌幅</th>');
    const nDayHeaders = isNDayLow ? '<th>N日最低</th><th>最低日期</th>' : '';

    let specialSummary = '';
    if (isNDayLow && (leopardCount > 0 || integerCount > 0)) {
        let parts = [];
        if (leopardCount > 0) parts.push(`<span class="tag-leopard">豹子号: ${leopardCount}</span>`);
        if (integerCount > 0) parts.push(`<span class="tag-integer">整数关口: ${integerCount}</span>`);
        specialSummary = `&nbsp;|&nbsp; ${parts.join(' &nbsp; ')}`;
    }

    container.innerHTML = `
        <div class="result-summary">
            分析时间: ${escHtml(formatTime(report.analysisTime))} &nbsp;|&nbsp;
            共分析: ${escHtml(String(report.totalAnalyzed || '--'))} &nbsp;|&nbsp;
            符合条件: <strong>${escHtml(String(report.matchedCount || 0))}</strong>${specialSummary}
        </div>
        <div class="result-table-wrap">
            <table class="result-table">
                <thead><tr>
                    <th>币种</th>${priceHeader}${chgHeader}${scoreHeader}${nDayHeaders}<th>详情</th>
                </tr></thead>
                <tbody>${rows}</tbody>
            </table>
        </div>`;
}

/* ===== Bind Detail Events ===== */
function bindDetailEvents(name, paramSpec) {
    const btnExecute = document.getElementById('btn-execute');
    const btnReset = document.getElementById('btn-reset');
    const errDiv = document.getElementById('execute-error');

    if (btnExecute) {
        btnExecute.addEventListener('click', async () => {
            errDiv.innerHTML = '';
            const params = {};
            if (paramSpec && paramSpec.params) {
                document.querySelectorAll('#param-form input[data-key]').forEach(input => {
                    const key = input.dataset.key;
                    const type = input.dataset.type;
                    const val = input.value.trim();
                    if (val === '') return;
                    if (type === 'integer' || type === 'int') {
                        params[key] = parseInt(val, 10);
                    } else if (type === 'number' || type === 'double' || type === 'float') {
                        params[key] = parseFloat(val);
                    } else if (type === 'boolean') {
                        params[key] = val === 'true';
                    } else {
                        params[key] = val;
                    }
                });
            }
            btnExecute.disabled = true;
            btnExecute.textContent = '执行中...';
            let rateLimited = false;
            try {
                const report = await api.sync(name, params);
                state.latestReports[name] = report;
                renderSidebar();
                renderResultTable(report);
                btnExecute.disabled = false;
                btnExecute.textContent = '执行分析';
            } catch (e) {
                rateLimited = e.message === 'rate_limited';
                if (rateLimited) {
                    errDiv.innerHTML = `<div class="error-msg">请求过于频繁，请稍后再试 (10秒冷却)</div>`;
                } else {
                    errDiv.innerHTML = `<div class="error-msg">执行失败: ${escHtml(String(e))}</div>`;
                }
                setTimeout(() => {
                    if (btnExecute) {
                        btnExecute.disabled = false;
                        btnExecute.textContent = '执行分析';
                    }
                }, 10000);
            }
        });
    }

    if (btnReset && paramSpec && paramSpec.params) {
        btnReset.addEventListener('click', () => {
            document.querySelectorAll('#param-form input[data-key]').forEach(input => {
                const key = input.dataset.key;
                const spec = paramSpec.params[key];
                if (spec) input.value = String(spec.default ?? '');
            });
        });
    }

    const pollCheckbox = document.getElementById('poll-checkbox');
    if (pollCheckbox) {
        pollCheckbox.addEventListener('change', () => {
            state.pollEnabled = pollCheckbox.checked;
            if (state.pollEnabled) {
                startPolling();
            } else {
                stopPolling();
            }
        });
    }
}

/* ===== Market: 多空比 (排名 + 单币快照) ===== */
function renderMarketLsr() {
    const content = document.getElementById('content');
    const typeOptions = Object.entries(RATIO_LABELS)
        .map(([k, v]) => `<option value="${k}"${state.lsrType === k ? ' selected' : ''}>${escHtml(v)}</option>`)
        .join('');
    content.innerHTML = `
        <div class="page-title">多空比</div>
        <div class="detail-section">
            <div class="market-toolbar">
                <select id="lsr-type" class="toolbar-select">${typeOptions}</select>
                <input type="number" id="lsr-limit" class="toolbar-input" value="${state.marketLimit}" min="1" max="500">
                <button class="btn-primary" id="lsr-go">查询排名</button>
                <span class="toolbar-sep"></span>
                <input type="text" id="lsr-symbol" class="toolbar-input wide" placeholder="单币查询 如 BTCUSDT">
                <button class="btn-default" id="lsr-symbol-go">查询单币</button>
            </div>
            <div id="market-result"><div class="loading">加载中...</div></div>
        </div>`;
    document.getElementById('lsr-go').addEventListener('click', () => {
        state.lsrType = document.getElementById('lsr-type').value;
        state.marketLimit = clampLimit(document.getElementById('lsr-limit').value);
        loadLsrRanking();
    });
    const symBox = document.getElementById('lsr-symbol');
    const doSymbol = () => {
        const sym = symBox.value.trim().toUpperCase();
        if (sym) loadSymbolSnapshot(sym);
    };
    document.getElementById('lsr-symbol-go').addEventListener('click', doSymbol);
    symBox.addEventListener('keydown', e => { if (e.key === 'Enter') doSymbol(); });
    loadLsrRanking();
}

async function loadLsrRanking() {
    const box = document.getElementById('market-result');
    if (!box) return;
    box.innerHTML = `<div class="loading">加载中...</div>`;
    try {
        const rows = await marketApi.lsrRanking(state.lsrType, state.marketLimit);
        if (!rows || rows.length === 0) { box.innerHTML = `<div class="empty-state">暂无数据</div>`; return; }
        let trs = '';
        rows.forEach((r, i) => {
            trs += `<tr>
                <td>${i + 1}</td>
                <td><strong>${escHtml(r.symbol)}</strong></td>
                <td>${(r.ratioValue ?? 0).toFixed(4)}</td>
                <td>${escHtml(String(r.longValue ?? '--'))}</td>
                <td>${escHtml(String(r.shortValue ?? '--'))}</td>
                <td>${escHtml(formatTime(r.timestamp))}</td>
            </tr>`;
        });
        box.innerHTML = `
            <div class="result-summary">${escHtml(RATIO_LABELS[state.lsrType])} 排名(降序) · 共 ${rows.length} 条</div>
            <div class="result-table-wrap"><table class="result-table">
                <thead><tr><th>#</th><th>币种</th><th>多空比</th><th>多头侧</th><th>空头侧</th><th>时间</th></tr></thead>
                <tbody>${trs}</tbody></table></div>`;
    } catch (e) {
        box.innerHTML = `<div class="error-msg">加载失败: ${escHtml(String(e))}</div>`;
    }
}

async function loadSymbolSnapshot(symbol) {
    const box = document.getElementById('market-result');
    if (!box) return;
    box.innerHTML = `<div class="loading">加载中...</div>`;
    try {
        const [lsrMap, tk, pm] = await Promise.all([
            marketApi.lsr(symbol), marketApi.tickerOne(symbol), marketApi.premiumOne(symbol)
        ]);
        let lsrRows = '';
        for (const [type, label] of Object.entries(RATIO_LABELS)) {
            const arr = (lsrMap && lsrMap[type]) || [];
            const latest = arr.length ? arr[arr.length - 1] : null;
            lsrRows += `<tr>
                <td>${escHtml(label)}</td>
                <td>${latest ? (latest.ratioValue ?? 0).toFixed(4) : '--'}</td>
                <td>${latest ? escHtml(String(latest.longValue)) : '--'}</td>
                <td>${latest ? escHtml(String(latest.shortValue)) : '--'}</td>
            </tr>`;
        }
        const tkHtml = tk
            ? `现价 ${escHtml(tk.lastPrice)} · 24h ${pctSpan(tk.priceChangePercentNum)} · 成交额 ${fmtVol(tk.quoteVolumeNum)}`
            : '无 24h 行情';
        const pmHtml = pm
            ? `标记价 ${escHtml(pm.markPrice)} · 资金费率 ${pctSpan((pm.lastFundingRateNum || 0) * 100, 4)} · 下次结算 ${escHtml(formatTime(pm.nextFundingTime))}`
            : '无标记价数据';
        box.innerHTML = `
            <div class="result-summary"><strong>${escHtml(symbol)}</strong> 单币快照<br>${tkHtml}<br>${pmHtml}</div>
            <div class="result-table-wrap"><table class="result-table">
                <thead><tr><th>多空比类型</th><th>最新值</th><th>多头侧</th><th>空头侧</th></tr></thead>
                <tbody>${lsrRows}</tbody></table></div>`;
    } catch (e) {
        box.innerHTML = `<div class="error-msg">加载失败: ${escHtml(String(e))}</div>`;
    }
}

/* ===== Market: 24h 行情榜 (涨幅/跌幅/成交额) ===== */
function renderTickerBoard(kind) {
    const content = document.getElementById('content');
    content.innerHTML = `
        <div class="page-title">${escHtml(TICKER_TITLES[kind])}</div>
        <div class="detail-section">
            <div class="market-toolbar">
                <label class="toolbar-label">条数</label>
                <input type="number" id="tk-limit" class="toolbar-input" value="${state.marketLimit}" min="1" max="500">
                <button class="btn-primary" id="tk-go">刷新</button>
            </div>
            <div id="market-result"><div class="loading">加载中...</div></div>
        </div>`;
    document.getElementById('tk-go').addEventListener('click', () => {
        state.marketLimit = clampLimit(document.getElementById('tk-limit').value);
        loadTicker(kind);
    });
    loadTicker(kind);
}

async function loadTicker(kind) {
    const box = document.getElementById('market-result');
    if (!box) return;
    box.innerHTML = `<div class="loading">加载中...</div>`;
    try {
        const rows = await marketApi.ticker(kind, state.marketLimit);
        if (!rows || rows.length === 0) { box.innerHTML = `<div class="empty-state">暂无数据</div>`; return; }
        let trs = '';
        rows.forEach((r, i) => {
            trs += `<tr>
                <td>${i + 1}</td>
                <td><strong>${escHtml(r.symbol)}</strong></td>
                <td>${escHtml(r.lastPrice)}</td>
                <td>${pctSpan(r.priceChangePercentNum)}</td>
                <td>${fmtVol(r.quoteVolumeNum)}</td>
            </tr>`;
        });
        box.innerHTML = `
            <div class="result-summary">共 ${rows.length} 条</div>
            <div class="result-table-wrap"><table class="result-table">
                <thead><tr><th>#</th><th>币种</th><th>现价</th><th>24h涨跌</th><th>成交额(USDT)</th></tr></thead>
                <tbody>${trs}</tbody></table></div>`;
    } catch (e) {
        box.innerHTML = `<div class="error-msg">加载失败: ${escHtml(String(e))}</div>`;
    }
}

/* ===== Market: 资金费率榜 ===== */
function renderFunding() {
    const content = document.getElementById('content');
    content.innerHTML = `
        <div class="page-title">资金费率榜</div>
        <div class="detail-section">
            <div class="market-toolbar">
                <select id="fd-order" class="toolbar-select">
                    <option value="desc"${state.fundingOrder === 'desc' ? ' selected' : ''}>最高在前</option>
                    <option value="asc"${state.fundingOrder === 'asc' ? ' selected' : ''}>最低/最负在前</option>
                </select>
                <input type="number" id="fd-limit" class="toolbar-input" value="${state.marketLimit}" min="1" max="500">
                <button class="btn-primary" id="fd-go">刷新</button>
            </div>
            <div id="market-result"><div class="loading">加载中...</div></div>
        </div>`;
    document.getElementById('fd-go').addEventListener('click', () => {
        state.fundingOrder = document.getElementById('fd-order').value;
        state.marketLimit = clampLimit(document.getElementById('fd-limit').value);
        loadFunding();
    });
    loadFunding();
}

async function loadFunding() {
    const box = document.getElementById('market-result');
    if (!box) return;
    box.innerHTML = `<div class="loading">加载中...</div>`;
    try {
        const rows = await marketApi.funding(state.marketLimit, state.fundingOrder);
        if (!rows || rows.length === 0) { box.innerHTML = `<div class="empty-state">暂无数据</div>`; return; }
        let trs = '';
        rows.forEach((r, i) => {
            trs += `<tr>
                <td>${i + 1}</td>
                <td><strong>${escHtml(r.symbol)}</strong></td>
                <td>${pctSpan((r.lastFundingRateNum || 0) * 100, 4)}</td>
                <td>${escHtml(r.markPrice)}</td>
                <td>${escHtml(formatTime(r.nextFundingTime))}</td>
            </tr>`;
        });
        box.innerHTML = `
            <div class="result-summary">共 ${rows.length} 条 · 资金费每 8 小时结算</div>
            <div class="result-table-wrap"><table class="result-table">
                <thead><tr><th>#</th><th>币种</th><th>资金费率</th><th>标记价</th><th>下次结算</th></tr></thead>
                <tbody>${trs}</tbody></table></div>`;
    } catch (e) {
        box.innerHTML = `<div class="error-msg">加载失败: ${escHtml(String(e))}</div>`;
    }
}

/* ===== Polling ===== */
function startPolling() {
    stopPolling();
    state.pollTimer = setInterval(async () => {
        if (!state.pollEnabled) return;
        try {
            const report = await api.latestByName('短期急涨');
            if (report) {
                state.latestReports['短期急涨'] = report;
                renderSidebar();
                if (state.currentView === 'detail' && state.currentAnalyzer === '短期急涨') {
                    renderResultTable(report);
                }
                if (state.currentView === 'overview') {
                    renderOverview();
                }
            }
        } catch (e) {
            console.warn('Poll failed', e);
        }
    }, 30000);
}

function stopPolling() {
    if (state.pollTimer) {
        clearInterval(state.pollTimer);
        state.pollTimer = null;
    }
}

/* ===== Bootstrap ===== */
document.addEventListener('DOMContentLoaded', init);

