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
    },
    async flow(symbol, period, limit) {
        const res = await fetch(`${MARKET_BASE}/flow/${encodeURIComponent(symbol)}?period=${encodeURIComponent(period)}&limit=${limit}`);
        if (!res.ok) throw new Error('flow failed');
        return res.json();
    },
    async stage(stage, minMatch, limit) {
        const qs = new URLSearchParams({ minMatch: minMatch, limit: limit });
        if (stage) qs.set('stage', stage);
        const res = await fetch(`${MARKET_BASE}/stage?${qs.toString()}`);
        if (!res.ok) throw new Error('stage failed');
        return res.json();
    },
    async stageOne(symbol) {
        const res = await fetch(`${MARKET_BASE}/stage/${encodeURIComponent(symbol)}`);
        if (!res.ok) throw new Error('stage detail failed');
        return res.json();
    },
    async screenTypes() {
        const res = await fetch(`${MARKET_BASE}/screen/types`);
        if (!res.ok) throw new Error('screen types failed');
        return res.json();
    },
    async screen(type, limit) {
        const res = await fetch(`${MARKET_BASE}/screen?type=${encodeURIComponent(type)}&limit=${limit}`);
        if (!res.ok) throw new Error('screen failed');
        return res.json();
    },
    async alerts(hours) {
        const res = await fetch(`${MARKET_BASE}/alerts?hours=${hours || 6}`);
        if (!res.ok) throw new Error('alerts failed');
        return res.json();
    },
    async hourlyRise(periods, refresh) {
        const res = await fetch(`${MARKET_BASE}/hourly-rise-oi?periods=${periods || 4}&refresh=${refresh ? 'true' : 'false'}`);
        if (!res.ok) throw new Error(res.status === 503 ? '扫描进行中，请稍后' : 'hourly scan failed');
        return res.json();
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
    { view: 'market-hourly-rise', label: '小时连涨OI增' },
    { view: 'market-alert', label: '异动预警' },
    { view: 'market-screen', label: '选币' },
    { view: 'market-stage', label: '阶段扫描' },
    { view: 'market-lsr', label: '多空比' },
    { view: 'market-flow', label: '资金流' },
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
    // 行情视图参数
    lsrType: 'TOP_POSITION',
    marketLimit: 50,
    fundingOrder: 'desc',
    // 资金流视图参数
    flowSymbol: 'BTCUSDT',
    flowPeriod: '1h',
    flowLimit: 72,
    // 阶段扫描参数
    stageFilter: '',
    stageMinMatch: 0,
    stageLimit: 200,
    // 选币参数
    screenType: 'anomaly',
    screenLimit: 50,
    // 预警
    alertHours: 12,
    alertTimer: null,
    // 小时连涨 OI 增
    hourlyRisePeriod: 4,
    hourlyRiseSeq: 0,
    hourlyRiseTimer: null
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
    if (state.alertTimer) { clearTimeout(state.alertTimer); state.alertTimer = null; }
    if (state.hourlyRiseTimer) { clearTimeout(state.hourlyRiseTimer); state.hourlyRiseTimer = null; }
    switch (state.currentView) {
        case 'detail': renderDetail(state.currentAnalyzer); break;
        case 'market-hourly-rise': renderHourlyRise(); break;
        case 'market-lsr': renderMarketLsr(); break;
        case 'market-stage': renderStage(); break;
        case 'market-screen': renderScreen(); break;
        case 'market-alert': renderAlert(); break;
        case 'market-flow': renderFlow(); break;
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

    let paramsHtml = '';
    if (paramSpec && paramSpec.params) {
        for (const [key, spec] of Object.entries(paramSpec.params)) {
            if (spec.type === 'select' && Array.isArray(spec.options)) {
                const options = spec.options.map(o => {
                    const value = String(o);
                    const selected = value === String(spec.default ?? '') ? ' selected' : '';
                    return `<option value="${escHtml(value)}"${selected}>${escHtml(value)}</option>`;
                }).join('');
                paramsHtml += `
                <div class="form-group">
                    <label for="param-${escHtml(key)}">${escHtml(key)}</label>
                    <select id="param-${escHtml(key)}" data-key="${escHtml(key)}" data-type="select">
                        ${options}
                    </select>
                    <span class="form-hint">${escHtml(spec.description || '')}</span>
                </div>`;
                continue;
            }
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
    const isFirstYin = report.analysisType === '首阴日';
    const hasTradePlan = isReversal || isFirstYin;
    const isNDayLow = report.analysisType === 'N日区间';
    const isOiDivergence = report.analysisType === '持仓背离';
    const isOiStreak = report.analysisType === 'OI连续上涨';
    // N日区间: 服务端已按"带标记优先 + 贴近低点优先"排好, 不再按涨跌幅重排
    const sorted = isNDayLow ? [...report.coins] : [...report.coins].sort((a, b) => {
        if (hasScore) return (b.score || 0) - (a.score || 0);
        return (b.changePercent || 0) - (a.changePercent || 0);
    });

    let leopardCount = 0;
    let integerCount = 0;

    let rows = '';
    for (const coin of sorted) {
        const pct = coin.changePercent != null ? coin.changePercent : null;
        let pctHtml = '<span class="neutral">--</span>';
        if (pct != null && isNDayLow) {
            // 区间位置(0=最低,100=最高)不是涨跌幅, 不加正负号与红绿色
            pctHtml = `<span class="neutral">${pct.toFixed(1)}%</span>`;
        } else if (pct != null) {
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

        // 带交易计划的分析器(反转做多/首阴日做空): 高亮入场/止盈/止损/盈亏比
        let detailHtml = escHtml(coin.detail || '');
        if (hasTradePlan && coin.detail) {
            detailHtml = escHtml(coin.detail)
                .replace(/做空/g, '<span class="hl-sl">做空</span>')
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
    const priceHeader = hasTradePlan ? '<th>入场价</th>' : '<th>当前价格</th>';
    const chgHeader = isReversal ? '<th>反转涨幅</th>'
        : isFirstYin ? '<th>首阴跌幅</th>'
        : isNDayLow ? '<th>区间位置</th>'
        : isOiDivergence ? '<th>持仓变化</th>'
        : isOiStreak ? '<th>OI变化</th>'
        : '<th>涨跌幅</th>';
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
                document.querySelectorAll('#param-form [data-key]').forEach(input => {
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
            document.querySelectorAll('#param-form [data-key]').forEach(input => {
                const key = input.dataset.key;
                const spec = paramSpec.params[key];
                if (spec) input.value = String(spec.default ?? '');
            });
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

/* ===== Market: 异动预警 ===== */
const ALERT_TYPES = {
    PUMP: { label: '放量拉升', cls: 'pump' },
    DUMP: { label: '放量下杀', cls: 'dump' },
    VOL: { label: '单纯放量', cls: 'vol' },
    OI: { label: '持仓突变', cls: 'oi' }
};

function renderAlert() {
    const content = document.getElementById('content');
    content.innerHTML = `
        <div class="page-title">异动预警</div>
        <div class="detail-section">
            <div class="flow-hint">
                扫描窗口 26 小时，用<strong>已收盘的小时线</strong>判断（避免半根量误判）。每 60 秒自动刷新。
                规则：拉升/下杀 = 1h 涨跌 ≥8% 且 量比 ≥5；单纯放量 = 量比 ≥10；持仓突变 = OI 1h 变化 ≥15%。
                24h 成交额低于 300 万的币不参与（避免小盘噪声）。
            </div>
            <div class="market-toolbar" style="margin-top:10px">
                <label class="toolbar-label">回溯</label>
                <select id="al-hours" class="toolbar-select">
                    <option value="6">近 6 小时</option>
                    <option value="12">近 12 小时</option>
                    <option value="24">近 24 小时</option>
                </select>
                <button class="btn-primary" id="al-go">立即刷新</button>
            </div>
            <div id="market-result"><div class="loading">扫描中…</div></div>
        </div>`;
    const sel = document.getElementById('al-hours');
    sel.value = String(state.alertHours);
    sel.addEventListener('change', () => {
        state.alertHours = parseInt(sel.value, 10) || 12;
        loadAlerts();
    });
    document.getElementById('al-go').addEventListener('click', loadAlerts);
    loadAlerts();
}

async function loadAlerts() {
    if (state.currentView !== 'market-alert') return;
    const box = document.getElementById('market-result');
    if (!box) return;
    if (state.alertTimer) { clearTimeout(state.alertTimer); state.alertTimer = null; }

    try {
        const rows = await marketApi.alerts(state.alertHours);
        const now = new Date().toLocaleTimeString('zh-CN', { hour12: false });
        if (!rows || rows.length === 0) {
            box.innerHTML = `<div class="result-summary">所选时间范围内没有触发任何信号 · 更新于 ${now}</div>
                <div class="empty-state">市场平静</div>`;
        } else {
            const trs = rows.map(r => {
                const t = ALERT_TYPES[r.type] || { label: r.type, cls: 'vol' };
                const d = new Date(r.time);
                const tm = `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:00`;
                return `<tr class="alert-row" data-symbol="${escHtml(r.symbol)}" style="cursor:pointer">
                    <td>${tm}</td>
                    <td><strong>${escHtml(r.symbol)}</strong></td>
                    <td><span class="alert-tag ${t.cls}">${t.label}</span></td>
                    <td>${fmtNum(r.price)}</td>
                    <td>${pctSpan(r.chg1h * 100, 2)}</td>
                    <td>${r.volRatio.toFixed(1)}x</td>
                    <td>${fmtNum(r.quote24h / 10000)} 万</td>
                    <td>${pctSpan(r.oiChg1h * 100, 1)}</td>
                    <td>${pctSpan(r.funding * 100, 4)}</td>
                </tr>`;
            }).join('');
            box.innerHTML = `
                <div class="result-summary">共 ${rows.length} 条信号 · 更新于 ${now} · 点行看该币资金流</div>
                <div class="result-table-wrap"><table class="result-table">
                    <thead><tr><th>时间</th><th>币种</th><th>类型</th><th>价格</th><th>1h</th>
                    <th>量比</th><th>24h 成交</th><th>OI 1h</th><th>费率</th></tr></thead>
                    <tbody>${trs}</tbody></table></div>`;
            box.querySelectorAll('.alert-row').forEach(tr => tr.addEventListener('click', () => {
                state.flowSymbol = tr.dataset.symbol;
                state.currentView = 'market-flow';
                renderSidebar();
                renderCurrentView();
            }));
        }
    } catch (e) {
        box.innerHTML = `<div class="error-msg">加载失败: ${escHtml(String(e))}</div>`;
    }
    state.alertTimer = setTimeout(loadAlerts, 60000);
}

/* ===== Market: 小时连涨 + OI 净增 ===== */
function renderHourlyRise() {
    const content = document.getElementById('content');
    content.innerHTML = `
        <div class="page-title">小时连涨 OI 增</div>
        <div class="detail-section">
            <div class="flow-hint">
                口径：最近 N 根<strong>已收盘</strong> 1h K 线全部 <code>close &gt; open</code>，
                且同窗口 <code>sum_open_interest</code>（币数）净增。OI 为整点快照，页面展示实际使用的 OI 区间。
                每小时 :10 自动扫描，页面每 60 秒读一次缓存。
            </div>
            <div class="market-toolbar" style="margin-top:10px">
                <label class="toolbar-label">连涨</label>
                <select id="hr-hours" class="toolbar-select">
                    <option value="2">2 小时</option><option value="3">3 小时</option>
                    <option value="4">4 小时</option><option value="5">5 小时</option>
                    <option value="6">6 小时</option>
                </select>
                <button class="btn-primary" id="hr-go">立即扫描</button>
            </div>
            <div id="hr-status" class="hr-status" style="display:none"></div>
            <div id="market-result"><div class="loading">扫描中…</div></div>
        </div>`;
    const sel = document.getElementById('hr-hours');
    sel.value = String(state.hourlyRisePeriod);
    sel.addEventListener('change', () => {
        state.hourlyRisePeriod = parseInt(sel.value, 10) || 4;
        loadHourlyRise(false);
    });
    document.getElementById('hr-go').addEventListener('click', () => loadHourlyRise(true));
    loadHourlyRise(false);
}

/** 扫描期间给出明确反馈：按钮置灰改文案 + 状态行提示（全市场扫描要 10~20 秒）。 */
function setHourlyRiseBusy(busy) {
    const btn = document.getElementById('hr-go');
    if (btn) {
        btn.disabled = busy;
        btn.textContent = busy ? '扫描中…' : '立即扫描';
    }
    const sel = document.getElementById('hr-hours');
    if (sel) sel.disabled = busy;
    const status = document.getElementById('hr-status');
    if (status) {
        status.style.display = busy ? '' : 'none';
        status.textContent = busy ? '正在扫描全市场（约 10~20 秒），完成后自动刷新…' : '';
    }
}

async function loadHourlyRise(refresh, silent) {
    if (state.currentView !== 'market-hourly-rise') return;
    const box = document.getElementById('market-result');
    if (!box) return;
    if (state.hourlyRiseTimer) { clearTimeout(state.hourlyRiseTimer); state.hourlyRiseTimer = null; }
    const periods = state.hourlyRisePeriod;
    // 请求序号：慢响应回来时若已有更新的请求（重复点击/切周期），丢弃旧结果
    const seq = (state.hourlyRiseSeq || 0) + 1;
    state.hourlyRiseSeq = seq;
    if (!silent) setHourlyRiseBusy(true);
    try {
        const r = await marketApi.hourlyRise(periods, refresh);
        // 慢响应回来时用户可能已经切视图、改了周期或又点了扫描：丢弃，别写进别的视图共用的 #market-result
        if (state.currentView !== 'market-hourly-rise' || state.hourlyRisePeriod !== periods
                || seq !== state.hourlyRiseSeq) return;
        const scanned = new Date(r.scannedAt).toLocaleString('zh-CN');
        if (!r.matches || r.matches.length === 0) {
            box.innerHTML = `<div class="result-summary">扫描 ${r.totalSymbols} 个币 · 无命中 · 扫描于 ${scanned}</div>
                <div class="empty-state">没有符合条件的币种</div>`;
        } else {
            const trs = r.matches.map(m => {
                const end = new Date(m.oiEndTime).toLocaleString('zh-CN', { hour12: false });
                const span = ((m.oiEndTime - m.oiStartTime) / 3600000).toFixed(0);
                return `<tr class="hr-row" data-symbol="${escHtml(m.symbol)}" style="cursor:pointer">
                    <td><strong>${escHtml(m.symbol)}</strong></td>
                    <td>${fmtNum(m.price)}</td>
                    <td>${pctSpan(m.priceChangePercent * 100, 2)}</td>
                    <td>${pctSpan(m.oiChangePercent * 100, 2)}</td>
                    <td>${m.periods}h</td>
                    <td>${span}h</td>
                    <td>${fmtVol(m.quoteVolume24h)}</td>
                    <td>${end}</td>
                </tr>`;
            }).join('');
            box.innerHTML = `
                <div class="result-summary">共 ${r.matchedCount} 个命中 · 扫描 ${r.totalSymbols} 个币 ·
                    OI 缺失 ${r.skippedNoOi} 个 · 扫描于 ${scanned} · 点行看资金流</div>
                <div class="result-table-wrap"><table class="result-table">
                    <thead><tr><th>币种</th><th>现价</th><th>窗口涨幅</th><th>OI 变化</th>
                    <th>连涨</th><th>OI 区间</th><th>24h 成交额</th><th>OI 截止</th></tr></thead>
                    <tbody>${trs}</tbody></table></div>`;
            box.querySelectorAll('.hr-row').forEach(tr => tr.addEventListener('click', () => {
                state.flowSymbol = tr.dataset.symbol;
                state.currentView = 'market-flow';
                renderSidebar();
                renderCurrentView();
            }));
        }
    } catch (e) {
        if (state.currentView !== 'market-hourly-rise' || state.hourlyRisePeriod !== periods
                || seq !== state.hourlyRiseSeq) return;
        box.innerHTML = `<div class="error-msg">加载失败: ${escHtml(String(e.message || e))}</div>`;
    } finally {
        if (seq === state.hourlyRiseSeq) setHourlyRiseBusy(false);
    }
    state.hourlyRiseTimer = setTimeout(() => loadHourlyRise(false, true), 60000);
}

/* ===== Market: 选币（六种筛选器） ===== */
function renderScreen() {
    const content = document.getElementById('content');
    content.innerHTML = `
        <div class="page-title">选币</div>
        <div class="detail-section">
            <div id="screen-tabs" class="screen-tabs"><div class="loading">加载筛选器…</div></div>
            <div class="market-toolbar" style="margin-top:10px">
                <label class="toolbar-label">条数</label>
                <input type="number" id="sc-limit" class="toolbar-input" value="${state.screenLimit}" min="5" max="500" step="5">
                <button class="btn-primary" id="sc-go">刷新</button>
                <span id="sc-desc" class="flow-hint" style="margin:0"></span>
            </div>
            <div id="market-result"><div class="loading">加载中...</div></div>
        </div>`;
    document.getElementById('sc-go').addEventListener('click', () => {
        state.screenLimit = clampLimit(document.getElementById('sc-limit').value);
        loadScreen();
    });
    loadScreen();
}

async function loadScreen() {
    const tabs = document.getElementById('screen-tabs');
    const box = document.getElementById('market-result');
    if (!box) return;
    try {
        if (!tabs.dataset.ready) {
            const defs = await marketApi.screenTypes();
            tabs.innerHTML = defs.map(d =>
                `<button class="screen-tab${d.type === state.screenType ? ' active' : ''}" data-type="${escHtml(d.type)}" title="${escHtml(d.desc)}">${escHtml(d.title)}</button>`
            ).join('');
            tabs.dataset.ready = '1';
            tabs.querySelectorAll('.screen-tab').forEach(btn => btn.addEventListener('click', () => {
                state.screenType = btn.dataset.type;
                tabs.querySelectorAll('.screen-tab').forEach(b => b.classList.toggle('active', b === btn));
                const def = defs.find(d => d.type === state.screenType);
                document.getElementById('sc-desc').textContent = def ? def.desc : '';
                loadScreen();
            }));
            const def = defs.find(d => d.type === state.screenType);
            if (def) document.getElementById('sc-desc').textContent = def.desc;
        }

        box.innerHTML = `<div class="loading">筛选中…</div>`;
        const rows = await marketApi.screen(state.screenType, state.screenLimit);
        if (!rows || rows.length === 0) { box.innerHTML = `<div class="empty-state">当前没有符合条件的币</div>`; return; }

        const trs = rows.map(r => `<tr>
            <td><strong>${escHtml(r.symbol)}</strong></td>
            <td>${fmtNum(r.close)}</td>
            <td>${pctSpan(r.ret1 * 100, 1)}</td>
            <td>${pctSpan(r.ret7 * 100, 1)}</td>
            <td>${r.volRatio.toFixed(2)}</td>
            <td>${fmtZ(r.volRatioZ)}</td>
            <td>${r.range20.toFixed(1)}%</td>
            <td>${pctSpan(r.distHigh20 * 100, 1)}</td>
            <td>${pctSpan(r.oiChg1 * 100, 1)}</td>
            <td>${pctSpan(r.oiChg7 * 100, 1)}</td>
            <td>${pctSpan(r.fundingApr * 100, 0)}</td>
            <td>${r.divergence > 0 ? '+' : ''}${r.divergence.toFixed(2)}</td>
            <td class="screen-reason">${escHtml(r.reason)}</td>
        </tr>`).join('');

        box.innerHTML = `
            <div class="result-summary">共 ${rows.length} 个币</div>
            <div class="result-table-wrap"><table class="result-table">
                <thead><tr>
                    <th>币种</th><th>现价</th><th>24h</th><th>7d</th><th>量比</th><th>量比z</th>
                    <th>20d振幅</th><th>距高</th><th>OI 1d</th><th>OI 7d</th><th>费率年化</th><th>大户-散户</th><th>理由</th>
                </tr></thead>
                <tbody>${trs}</tbody></table></div>`;
    } catch (e) {
        box.innerHTML = `<div class="error-msg">加载失败: ${escHtml(String(e))}</div>`;
    }
}

/* ===== Market: 阶段扫描 ===== */
function renderStage() {
    const content = document.getElementById('content');
    const stages = ['', '吸筹', '试盘', '拉升', '派发', '砸盘', '冷却'];
    content.innerHTML = `
        <div class="page-title">阶段扫描</div>
        <div class="detail-section">
            <div class="market-toolbar">
                <select id="st-filter" class="toolbar-select">
                    ${stages.map(s => `<option value="${s}"${state.stageFilter === s ? ' selected' : ''}>${s === '' ? '全部阶段' : s}</option>`).join('')}
                </select>
                <label class="toolbar-label">匹配度 ≥</label>
                <input type="number" id="st-min" class="toolbar-input" value="${state.stageMinMatch}" min="0" max="100" step="5">
                <button class="btn-primary" id="st-go">扫描</button>
            </div>
            <div class="flow-hint">阶段标签是<strong>状态描述</strong>，不是买卖信号——回测显示各阶段后续 7 天收益与基线无显著差异。点某一行看单币详情。</div>
            <div id="market-result"><div class="loading">加载中...</div></div>
        </div>`;
    document.getElementById('st-go').addEventListener('click', () => {
        state.stageFilter = document.getElementById('st-filter').value;
        state.stageMinMatch = parseInt(document.getElementById('st-min').value, 10) || 0;
        loadStage();
    });
    loadStage();
}

async function loadStage() {
    const box = document.getElementById('market-result');
    if (!box) return;
    box.innerHTML = `<div class="loading">扫描全市场…</div>`;
    try {
        const rows = await marketApi.stage(state.stageFilter, state.stageMinMatch, state.stageLimit);
        if (!rows || rows.length === 0) { box.innerHTML = `<div class="empty-state">没有符合条件的币</div>`; return; }
        const trs = rows.map(r => `<tr class="stage-row" data-symbol="${escHtml(r.symbol)}" style="cursor:pointer">
            <td><strong>${escHtml(r.symbol)}</strong></td>
            <td><span class="stage-tag ${stageClass(r.stage)}">${escHtml(r.stage)}</span></td>
            <td>${r.match}</td>
            <td>${r.consistent ? '是' : '<span class="negative">冲突</span>'}</td>
            <td>${pctSpan(r.ret * 100, 1)}</td>
            <td>${pctSpan(r.excess7 * 100, 1)}</td>
            <td>${fmtZ(r.volRatioZ)}</td>
            <td>${fmtZ(r.oiChgZ)}</td>
            <td>${pctSpan(r.funding * 100, 4)}</td>
            <td>${pctSpan(r.drawdown * 100, 1)}</td>
            <td>${r.dataDays}</td>
        </tr>`).join('');
        box.innerHTML = `
            <div class="result-summary">共 ${rows.length} 个币 · 按匹配度降序</div>
            <div class="result-table-wrap"><table class="result-table">
                <thead><tr><th>币种</th><th>阶段</th><th>匹配</th><th>一致</th><th>24h</th><th>超额7d</th>
                <th>量比z</th><th>OI z</th><th>费率</th><th>距高</th><th>天数</th></tr></thead>
                <tbody>${trs}</tbody></table></div>`;
        box.querySelectorAll('.stage-row').forEach(tr =>
            tr.addEventListener('click', () => loadStageDetail(tr.dataset.symbol)));
    } catch (e) {
        box.innerHTML = `<div class="error-msg">加载失败: ${escHtml(String(e))}</div>`;
    }
}

async function loadStageDetail(symbol) {
    const box = document.getElementById('market-result');
    if (!box) return;
    box.innerHTML = `<div class="loading">加载 ${escHtml(symbol)} 详情…</div>`;
    try {
        const d = await marketApi.stageOne(symbol);
        const r = d.row;
        const ev = (d.evidence && d.evidence.length)
            ? d.evidence.map(e => `<li>${escHtml(e)}</li>`).join('')
            : '<li>无明显异常项</li>';
        box.innerHTML = `
            <div class="result-summary">
                <button class="btn-small" id="st-back">← 返回列表</button>
                &nbsp; <strong>${escHtml(r.symbol)}</strong>
                <span class="stage-tag ${stageClass(r.stage)}">${escHtml(r.stage)}</span>
                匹配度 ${r.match}/100 ${r.consistent ? '' : '<span class="negative">（多周期冲突）</span>'}
            </div>
            <div class="stage-detail">
                <div class="stage-kv">
                    <div><span>收盘</span><b>${fmtNum(r.close)}</b></div>
                    <div><span>24h</span><b>${pctSpan(r.ret * 100, 1)}</b></div>
                    <div><span>7d</span><b>${pctSpan(r.ret7 * 100, 1)}</b></div>
                    <div><span>相对 BTC</span><b>${pctSpan(r.excess7 * 100, 1)}</b></div>
                    <div><span>距 30d 高点</span><b>${pctSpan(r.drawdown * 100, 1)}</b></div>
                    <div><span>OI 距峰</span><b>${pctSpan(r.oiFromPeak * 100, 1)}</b></div>
                    <div><span>费率</span><b>${pctSpan(r.funding * 100, 4)}</b></div>
                    <div><span>大户比</span><b>${fmtNum(r.topPos)}</b></div>
                </div>
                <div class="stage-spark">${sparkline(d.closes)}</div>
                <div class="stage-why">
                    <div><b>证据</b><ul>${ev}</ul></div>
                    <div><b>证伪条件</b><p>${escHtml(d.falsify)}</p></div>
                </div>
            </div>`;
        document.getElementById('st-back').addEventListener('click', loadStage);
    } catch (e) {
        box.innerHTML = `<div class="error-msg">加载失败: ${escHtml(String(e))}</div>`;
    }
}

function stageClass(stage) {
    return { '拉升': 'up', '吸筹': 'acc', '试盘': 'test', '派发': 'dist', '砸盘': 'down' }[stage] || 'idle';
}

/** z-score 显示：正负号 + 两位小数。 */
function fmtZ(v) {
    if (v == null || isNaN(v)) return '--';
    const cls = v > 0 ? 'positive' : v < 0 ? 'negative' : 'neutral';
    return `<span class="${cls}">${v > 0 ? '+' : ''}${v.toFixed(2)}</span>`;
}

/** 近 90 天收盘价迷你折线。 */
function sparkline(points) {
    if (!points || points.length < 2) return '';
    const W = 900, H = 120, pad = 6;
    const ys = points.map(p => p[1]);
    const lo = Math.min(...ys), hi = Math.max(...ys);
    const xAt = i => pad + (W - pad * 2) * i / (points.length - 1);
    const yAt = v => H - pad - (hi === lo ? (H - pad * 2) / 2 : (v - lo) / (hi - lo) * (H - pad * 2));
    let path = '';
    points.forEach((p, i) => { path += (i === 0 ? 'M' : 'L') + xAt(i).toFixed(1) + ',' + yAt(p[1]).toFixed(1) + ' '; });
    const up = ys[ys.length - 1] >= ys[0];
    return `<svg viewBox="0 0 ${W} ${H}" class="spark"><path d="${path}" fill="none"
        stroke="${up ? '#52c41a' : '#ff4d4f'}" stroke-width="2"/></svg>`;
}

/* ===== Market: 资金流 (价格 / 主动买占比 / OI) ===== */
function renderFlow() {
    const content = document.getElementById('content');
    content.innerHTML = `
        <div class="page-title">资金流：价格 / 主动买占比 / OI / 大户持仓比</div>
        <div class="detail-section">
            <div class="market-toolbar">
                <input type="text" id="flow-symbol" class="toolbar-input wide" placeholder="币种 如 AINUSDT" value="${escHtml(state.flowSymbol)}">
                <select id="flow-period" class="toolbar-select">
                    ${['15m', '1h', '4h', '1d'].map(p => `<option value="${p}"${state.flowPeriod === p ? ' selected' : ''}>${p}</option>`).join('')}
                </select>
                <input type="number" id="flow-limit" class="toolbar-input" value="${state.flowLimit}" min="10" max="500">
                <button class="btn-primary" id="flow-go">查询</button>
            </div>
            <div class="flow-hint">主动买占比 = 主动买入成交额 ÷ 总成交额（&gt;50% 为主动买占优）；OI 看币数变化判断加仓还是减仓（名义值会随价格膨胀）；大户持仓比 &gt;1 表示大户持仓偏多。</div>
            <div id="market-result"><div class="loading">加载中...</div></div>
        </div>`;
    const doLoad = () => {
        state.flowSymbol = (document.getElementById('flow-symbol').value || '').trim().toUpperCase();
        state.flowPeriod = document.getElementById('flow-period').value;
        state.flowLimit = clampLimit(document.getElementById('flow-limit').value);
        if (state.flowSymbol) loadFlow();
    };
    document.getElementById('flow-go').addEventListener('click', doLoad);
    document.getElementById('flow-symbol').addEventListener('keydown', e => { if (e.key === 'Enter') doLoad(); });
    loadFlow();
}

async function loadFlow() {
    const box = document.getElementById('market-result');
    if (!box) return;
    box.innerHTML = `<div class="loading">加载中...</div>`;
    try {
        const data = await marketApi.flow(state.flowSymbol, state.flowPeriod, state.flowLimit);
        const pts = (data && data.points) || [];
        if (pts.length === 0) { box.innerHTML = `<div class="empty-state">暂无数据（该币种可能未入库）</div>`; return; }

        const last = pts[pts.length - 1];
        const first = pts[0];
        const oiChg = (last.oiCoin != null && first.oiCoin != null && first.oiCoin > 0)
            ? (last.oiCoin - first.oiCoin) / first.oiCoin * 100 : null;
        const buyCls = last.buyPct == null ? 'neutral' : (last.buyPct >= 50 ? 'positive' : 'negative');

        const sum = `<div class="result-summary">
            <strong>${escHtml(data.symbol)}</strong> · ${escHtml(data.period)} · 共 ${pts.length} 根<br>
            最新收盘 ${fmtNum(last.close)} · 主动买 <span class="${buyCls}">${last.buyPct == null ? '--' : last.buyPct.toFixed(1) + '%'}</span>
            · OI ${last.oiCoin == null ? '--' : fmtNum(last.oiCoin) + ' 枚'}
            · 大户持仓比 ${last.topPos == null ? '--' : last.topPos.toFixed(3)}
            · 区间 OI 变化 ${pctSpan(oiChg, 1)}
        </div>`;

        const rows = pts.slice(-12).reverse().map(p => `<tr>
            <td>${escHtml(formatTime(p.t))}</td>
            <td>${p.close == null ? '--' : fmtNum(p.close)}</td>
            <td>${p.buyPct == null ? '--' : `<span class="${p.buyPct >= 50 ? 'positive' : 'negative'}">${p.buyPct.toFixed(1)}%</span>`}</td>
            <td>${p.oiCoin == null ? '--' : fmtNum(p.oiCoin)}</td>
            <td>${p.oiUsd == null ? '--' : fmtNum(p.oiUsd)}</td>
            <td>${p.topPos == null ? '--' : p.topPos.toFixed(3)}</td>
        </tr>`).join('');

        box.innerHTML = sum
            + `<div class="flow-chart-wrap">${flowChart(pts)}</div>`
            + `<div class="result-table-wrap"><table class="result-table">
                 <thead><tr><th>时间</th><th>收盘价</th><th>主动买占比</th><th>OI(币)</th><th>OI(USDT)</th><th>大户持仓比</th></tr></thead>
                 <tbody>${rows}</tbody></table></div>`;
    } catch (e) {
        box.innerHTML = `<div class="error-msg">加载失败: ${escHtml(String(e))}</div>`;
    }
}

/** 四面板 SVG 图: 价格折线 / 主动买占比柱(50% 基准) / OI 币数折线 / 大户持仓比折线。 */
function flowChart(pts) {
    const W = 980, H = 560, padL = 72, padR = 18, padT = 14, padB = 22, gap = 28;
    const n = pts.length;
    const plotW = W - padL - padR;
    const panelH = (H - padT - padB - gap * 3) / 4;
    const xAt = i => padL + (n <= 1 ? plotW / 2 : plotW * i / (n - 1));

    const panels = [
        { key: 'close', label: '收盘价', color: '#1890ff', kind: 'line' },
        { key: 'buyPct', label: '主动买占比 %', color: '#52c41a', kind: 'bar', min: 0, max: 100, base: 50 },
        { key: 'oiCoin', label: 'OI（币数）', color: '#fa8c16', kind: 'line' },
        { key: 'topPos', label: '大户持仓多空比', color: '#722ed1', kind: 'line', base: 1 }
    ];

    let svg = `<svg class="flow-chart" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none">`;
    panels.forEach((s, si) => {
        const top = padT + si * (panelH + gap);
        const vals = pts.map(p => p[s.key]).filter(v => v != null && !isNaN(v));
        if (!vals.length) {
            svg += `<rect x="${padL}" y="${top}" width="${plotW}" height="${panelH}" fill="#fafafa" stroke="#eee"/>`
                 + `<text x="${padL + 8}" y="${top + 16}" font-size="12" fill="#bbb">${s.label}（无数据）</text>`;
            return;
        }
        let lo = s.min != null ? s.min : Math.min(...vals);
        let hi = s.max != null ? s.max : Math.max(...vals);
        if (s.min == null) { const pad = (hi - lo) * 0.08 || Math.abs(hi) * 0.01 || 1; lo -= pad; hi += pad; }
        const yAt = v => top + panelH - (hi === lo ? panelH / 2 : (v - lo) / (hi - lo) * panelH);

        svg += `<rect x="${padL}" y="${top}" width="${plotW}" height="${panelH}" fill="#fafafa" stroke="#eee"/>`;
        svg += `<text x="8" y="${top + 13}" font-size="12" fill="#666">${s.label}</text>`;
        svg += `<text x="${padL - 6}" y="${top + 10}" font-size="10" fill="#999" text-anchor="end">${fmtNum(hi)}</text>`;
        svg += `<text x="${padL - 6}" y="${top + panelH}" font-size="10" fill="#999" text-anchor="end">${fmtNum(lo)}</text>`;

        if (s.base != null) {
            svg += `<line x1="${padL}" y1="${yAt(s.base)}" x2="${padL + plotW}" y2="${yAt(s.base)}" stroke="#bbb" stroke-dasharray="3 3"/>`;
        }

        if (s.kind === 'bar') {
            const bw = Math.max(1, plotW / n * 0.6);
            pts.forEach((p, i) => {
                const v = p[s.key];
                if (v == null || isNaN(v)) return;
                const y = yAt(v), y0 = yAt(s.base);
                svg += `<rect x="${(xAt(i) - bw / 2).toFixed(1)}" y="${Math.min(y, y0).toFixed(1)}" width="${bw.toFixed(1)}"`
                     + ` height="${Math.max(1, Math.abs(y0 - y)).toFixed(1)}" fill="${v >= s.base ? '#52c41a' : '#ff4d4f'}" opacity="0.7"/>`;
            });
        } else {
            let path = '', seg = '';
            pts.forEach((p, i) => {
                const v = p[s.key];
                if (v == null || isNaN(v)) { if (seg) { path += seg; seg = ''; } return; }
                seg += (seg === '' ? 'M' : 'L') + xAt(i).toFixed(1) + ',' + yAt(v).toFixed(1) + ' ';
            });
            if (seg) path += seg;
            svg += `<path d="${path}" fill="none" stroke="${s.color}" stroke-width="1.8"/>`;
        }
    });

    svg += `<text x="${padL}" y="${H - 6}" font-size="10" fill="#999">${escHtml(formatTime(pts[0].t))}</text>`;
    svg += `<text x="${W - padR}" y="${H - 6}" font-size="10" fill="#999" text-anchor="end">${escHtml(formatTime(pts[n - 1].t))}</text>`;
    return svg + '</svg>';
}

/** 紧凑数字: 亿 / 万 / 两位小数 / 四位有效数字。 */
function fmtNum(v) {
    if (v == null || isNaN(v)) return '--';
    const a = Math.abs(v);
    if (a >= 1e8) return (v / 1e8).toFixed(2) + '亿';
    if (a >= 1e4) return (v / 1e4).toFixed(2) + '万';
    if (a >= 1) return v.toFixed(2);
    return Number(v.toPrecision(4)).toString();
}

/* ===== Bootstrap ===== */
document.addEventListener('DOMContentLoaded', init);

