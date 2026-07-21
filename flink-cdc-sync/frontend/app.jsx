const { useState, useEffect, useRef, useCallback } = React;

/* ============ 轻量提示 Toast ============ */
const TOAST_EVENT = 'rx-toast';
let toastSeq = 0;
function toast(type, text) { window.dispatchEvent(new CustomEvent(TOAST_EVENT, { detail: { type, text } })); }
function ToastHost() {
  const [items, setItems] = useState([]);
  useEffect(() => {
    const h = (e) => {
      const { type, text } = e.detail;
      const id = ++toastSeq;
      setItems(a => [...a, { id, type, text }]);
      setTimeout(() => setItems(a => a.filter(x => x.id !== id)), 3000);
    };
    window.addEventListener(TOAST_EVENT, h);
    return () => window.removeEventListener(TOAST_EVENT, h);
  }, []);
  return (
    <div className="rx-toasts">
      {items.map(i => <div key={i.id} className={'rx-toast rx-' + i.type}>{i.text}</div>)}
    </div>
  );
}

/* ============ 多选/可新建 Select ============ */
function Select({ value, options = [], multiple = false, allowCreate = false, filterable = true, placeholder = '', loading = false, disabled = false, onChange, style }) {
  const [open, setOpen] = useState(false);
  const [text, setText] = useState('');
  const boxRef = useRef(null);
  const vals = multiple ? (value || []) : (value ? [value] : []);
  useEffect(() => {
    const h = (e) => { if (boxRef.current && !boxRef.current.contains(e.target)) setOpen(false); };
    document.addEventListener('mousedown', h);
    return () => document.removeEventListener('mousedown', h);
  }, []);
  const filtered = (options || []).filter(o => String(o || '').toLowerCase().includes(String(text || '').toLowerCase()));
  const showCreate = allowCreate && !!text && !options.includes(text) && !vals.includes(text);
  const toggle = (v) => {
    if (multiple) {
      const next = vals.includes(v) ? vals.filter(x => x !== v) : [...vals, v];
      onChange(next);
    } else { onChange(v); setOpen(false); }
  };
  const create = () => {
    if (!text) return;
    const next = multiple ? [...vals, text] : [text];
    onChange(next); setText(''); if (!multiple) setOpen(false);
  };
  return (
    <div ref={boxRef} className={'rx-select' + (disabled ? ' rx-disabled' : '')} style={style}>
      <div className="rx-select-box" onClick={() => { if (!disabled) setOpen(o => !o); }}>
        {vals.length === 0 && !open && <span className="rx-ph">{placeholder}</span>}
        {vals.map(v => (
          <span key={v} className="rx-tag">
            {v}
            {multiple && <span className="rx-x" onClick={(e) => { e.stopPropagation(); toggle(v); }}>×</span>}
          </span>
        ))}
        {open && filterable && (
          <input className="rx-input" autoFocus value={text}
            placeholder={placeholder}
            onClick={(e) => e.stopPropagation()}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') { if (showCreate) create(); else if (filtered[0]) toggle(filtered[0]); }
              if (e.key === 'Backspace' && !text && multiple && vals.length) onChange(vals.slice(0, -1));
            }} />
        )}
      </div>
      {open && (
        <div className="rx-dropdown">
          {loading && <div className="rx-loading">加载中...</div>}
          {filtered.map(o => (
            <div key={o} className={'rx-opt' + (vals.includes(o) ? ' rx-sel' : '')} onClick={() => toggle(o)}>{o}</div>
          ))}
          {showCreate && <div className="rx-opt rx-create" onClick={create}>创建: “{text}”</div>}
          {!loading && filtered.length === 0 && !showCreate && <div className="rx-empty">无匹配</div>}
        </div>
      )}
    </div>
  );
}

/* ============ 抽屉 ============ */
function Drawer({ open, title, onClose, children }) {
  if (!open) return null;
  return (
    <div className="rx-mask" onClick={onClose}>
      <div className="rx-drawer" onClick={(e) => e.stopPropagation()}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14 }}>
          <h3 style={{ margin: 0 }}>{title}</h3>
          <span className="rx-link" onClick={onClose}>关闭</span>
        </div>
        {children}
      </div>
    </div>
  );
}

/* ============ 分页 ============ */
function Pager({ total, page, pageSize, onChange }) {
  const pages = Math.max(1, Math.ceil(total / pageSize));
  const nums = [];
  const start = Math.max(1, Math.min(page - 2, pages - 4));
  const end = Math.min(pages, start + 4);
  for (let i = start; i <= end; i++) nums.push(i);
  return (
    <span className="rx-pager">
      <button disabled={page <= 1} onClick={() => onChange(page - 1)}>上一页</button>
      {start > 1 && <button onClick={() => onChange(1)}>1</button>}
      {start > 2 && <span style={{ padding: '0 4px' }}>…</span>}
      {nums.map(n => <button key={n} className={n === page ? 'active' : ''} onClick={() => onChange(n)}>{n}</button>)}
      {end < pages - 1 && <span style={{ padding: '0 4px' }}>…</span>}
      {end < pages && <button onClick={() => onChange(pages)}>{pages}</button>}
      <button disabled={page >= pages} onClick={() => onChange(page + 1)}>下一页</button>
    </span>
  );
}

/* ============ 工具函数 ============ */
function isConcreteDb(db) { return !!db && !String(db).startsWith('re:') && db.indexOf('*') < 0 && db.indexOf('?') < 0; }
function stateDesc(s) { return ['失效', '全量同步', '同步中', '中止'][s] ?? '未知'; }
function stateTag(s) { return ['rx-info', 'rx-warning', 'rx-success', 'rx-danger'][s] ?? 'rx-info'; }
function fmtDbIgnore(list) {
  if (!list || !list.length) return '—';
  return list.map(e => (e.database || '?') + ':[' + ((e.tables && e.tables.length) ? e.tables.join(',') : '—') + ']').join('; ');
}
function blankPair() {
  return {
    sourceHost: '127.0.0.1', sourcePort: 3306, sourceUser: 'root', sourcePassword: '',
    targetHost: '192.168.88.88', targetPort: 3306, targetUser: 'root', targetPassword: '',
    sourceDatabases: [], loadingDbs: false, testResult: null, testing: false,
    tableCache: {}, loadingTablesDb: null,
    ignoreDatabases: [],
    ignoreTablesByDb: [], ignoreDdlTablesByDb: [],
    commonIgnoreTables: [], commonDdlIgnoreTables: [],
    advActive: false, transformRules: [{ dbName: '*', tableName: '*', fieldName: '', sourceValue: '', targetValue: '' }],
    adding: false
  };
}
function validRules(pair) {
  const out = [];
  for (const r of (pair.transformRules || [])) {
    if (!r.fieldName || !r.sourceValue) continue;
    out.push({ dbName: r.dbName || '*', tableName: r.tableName || '*', fieldName: r.fieldName, sourceValue: r.sourceValue, targetValue: r.targetValue || '' });
  }
  return out;
}
function buildPairConfig(pair) {
  return {
    sourceHost: pair.sourceHost, sourcePort: pair.sourcePort, sourceUser: pair.sourceUser, sourcePassword: pair.sourcePassword,
    targetHost: pair.targetHost, targetPort: pair.targetPort, targetUser: pair.targetUser, targetPassword: pair.targetPassword,
    ignoreDatabases: pair.ignoreDatabases || [],
    ignoreTablesByDb: pair.ignoreTablesByDb || [],
    ignoreDdlTablesByDb: pair.ignoreDdlTablesByDb || [],
    commonIgnoreTables: pair.commonIgnoreTables || [],
    commonDdlIgnoreTables: pair.commonDdlIgnoreTables || [],
    transformRules: validRules(pair)
  };
}

/* ============ 单个主机对配置卡 ============ */
function PairCard(props) {
  const { pair, idx, onUpdatePair, onUpdateEntry, onUpdateRule, onTest, onLoadDbs, onLoadTables,
    onAddDbIgnore, onRemoveDbIgnore, onAddRule, onRemoveRule, onSave, onRemovePair, canRemove } = props;

  const renderDbIgnore = (kind) => {
    const listName = kind === 'dml' ? 'ignoreTablesByDb' : 'ignoreDdlTablesByDb';
    const list = pair[listName] || [];
    const title = kind === 'dml' ? '按库忽略表（DML + DDL 均不同步）' : '按库忽略表（仅忽略 DDL，数据仍同步）';
    return (
      <div className="sub-block">
        <div className="sub-title">{title}</div>
        {list.map((entry, i) => (
          <div className="db-ignore-row" key={kind + i}>
            <Select
              value={entry.database || ''}
              options={pair.sourceDatabases || []}
              allowCreate filterable
              placeholder="选择/输入库名(支持正则)"
              style={{ width: 200 }}
              onChange={(v) => onUpdateEntry(idx, kind, i, { database: v })} />
            <Select
              value={entry.tables || []}
              multiple allowCreate filterable
              loading={pair.loadingTablesDb === entry.database}
              options={pair.tableCache[entry.database] || []}
              placeholder={isConcreteDb(entry.database) ? '选表或手填正则(如 re:^tmp_.*)' : '库为正则时请手填表规则'}
              style={{ flex: 1, minWidth: 280 }}
              onChange={(v) => onUpdateEntry(idx, kind, i, { tables: v })} />
            <button className="rx-btn" disabled={!isConcreteDb(entry.database)} onClick={() => onLoadTables(idx, kind, i)}>加载该库表</button>
            <button className="rx-btn rx-btn-danger" onClick={() => onRemoveDbIgnore(idx, kind, i)}>删除</button>
          </div>
        ))}
        <button className="rx-btn" onClick={() => onAddDbIgnore(idx, kind)}>+ 添加库忽略</button>
      </div>
    );
  };

  return (
    <div className="card host-pair">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 8 }}>
        <h3 style={{ margin: 0 }}>主机对 #{idx + 1}　<span className="hp-key">{pair.sourceHost} → {pair.targetHost}</span></h3>
        {canRemove && <button className="rx-btn rx-btn-danger" onClick={() => onRemovePair(idx)}>删除此主机对</button>}
      </div>

      <p className="desc-cell" style={{ margin: '8px 0 12px' }}>
        填写<b>源主机</b>与<b>目标主机</b>的 IP / 端口 / 账号 / 密码。「忽略整库」与「按库忽略表」均可从源库动态拉取选项。
      </p>

      <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap' }}>
        <div className="rx-box">
          <h4 style={{ color: '#f56c6c' }}>源主机（生产中心）</h4>
          <div className="rx-field"><label className="rx-label">主机</label><input className="rx-input" style={{ border: '1px solid #dcdfe6', borderRadius: 4, padding: '6px 8px', width: '100%', boxSizing: 'border-box' }} value={pair.sourceHost} onChange={e => onUpdatePair(idx, { sourceHost: e.target.value })} placeholder="如 127.0.0.1" /></div>
          <div className="rx-field"><label className="rx-label">端口</label><input className="rx-input" style={{ border: '1px solid #dcdfe6', borderRadius: 4, padding: '6px 8px', width: '100%', boxSizing: 'border-box' }} value={pair.sourcePort} onChange={e => onUpdatePair(idx, { sourcePort: e.target.value })} placeholder="3306" /></div>
          <div className="rx-field"><label className="rx-label">账号</label><input className="rx-input" style={{ border: '1px solid #dcdfe6', borderRadius: 4, padding: '6px 8px', width: '100%', boxSizing: 'border-box' }} value={pair.sourceUser} onChange={e => onUpdatePair(idx, { sourceUser: e.target.value })} placeholder="如 root" /></div>
          <div className="rx-field"><label className="rx-label">密码</label><input className="rx-input" type="password" style={{ border: '1px solid #dcdfe6', borderRadius: 4, padding: '6px 8px', width: '100%', boxSizing: 'border-box' }} value={pair.sourcePassword} onChange={e => onUpdatePair(idx, { sourcePassword: e.target.value })} placeholder="数据库密码" /></div>
        </div>
        <div className="rx-box">
          <h4 style={{ color: '#67c23a' }}>目标主机（灾备中心）</h4>
          <div className="rx-field"><label className="rx-label">主机</label><input className="rx-input" style={{ border: '1px solid #dcdfe6', borderRadius: 4, padding: '6px 8px', width: '100%', boxSizing: 'border-box' }} value={pair.targetHost} onChange={e => onUpdatePair(idx, { targetHost: e.target.value })} placeholder="如 192.168.88.88" /></div>
          <div className="rx-field"><label className="rx-label">端口</label><input className="rx-input" style={{ border: '1px solid #dcdfe6', borderRadius: 4, padding: '6px 8px', width: '100%', boxSizing: 'border-box' }} value={pair.targetPort} onChange={e => onUpdatePair(idx, { targetPort: e.target.value })} placeholder="3306" /></div>
          <div className="rx-field"><label className="rx-label">账号</label><input className="rx-input" style={{ border: '1px solid #dcdfe6', borderRadius: 4, padding: '6px 8px', width: '100%', boxSizing: 'border-box' }} value={pair.targetUser} onChange={e => onUpdatePair(idx, { targetUser: e.target.value })} placeholder="如 root" /></div>
          <div className="rx-field"><label className="rx-label">密码</label><input className="rx-input" type="password" style={{ border: '1px solid #dcdfe6', borderRadius: 4, padding: '6px 8px', width: '100%', boxSizing: 'border-box' }} value={pair.targetPassword} onChange={e => onUpdatePair(idx, { targetPassword: e.target.value })} placeholder="数据库密码" /></div>
        </div>
      </div>

      <div className="toolbar" style={{ marginTop: 6 }}>
        <button className="rx-btn" disabled={pair.testing} onClick={() => onTest(idx)}>{pair.testing ? '测试中...' : '测试连接'}</button>
        <button className="rx-btn" disabled={pair.loadingDbs} onClick={() => onLoadDbs(idx)}>{pair.loadingDbs ? '加载中...' : '加载源库列表'}</button>
        {pair.testResult && (
          <span className="desc-cell">
            <span className={'rx-tag-static ' + (pair.testResult.source.ok ? 'rx-success' : 'rx-danger')} style={{ marginRight: 6 }}>源主机: {pair.testResult.source.ok ? 'OK' : '失败'}</span>
            <span className={'rx-tag-static ' + (pair.testResult.target.ok ? 'rx-success' : 'rx-danger')}>目标主机: {pair.testResult.target.ok ? 'OK' : '失败'}</span>
            {(!pair.testResult.source.ok || !pair.testResult.target.ok) && <span className="rx-err" style={{ marginLeft: 8 }}>{(!pair.testResult.source.ok ? '源:' + pair.testResult.source.message : '') + ' ' + (!pair.testResult.target.ok ? '目标:' + pair.testResult.target.message : '')}</span>}
          </span>
        )}
      </div>
      {pair.sourceDatabases.length > 0 && (
        <div className="desc-cell" style={{ marginTop: 8 }}>
          源主机下 <b>{pair.sourceDatabases.length}</b> 个用户库（已排除系统库）：
          {pair.sourceDatabases.map(db => <span key={db} className="rx-tag-static rx-info" style={{ margin: '0 5px 5px 0' }}>{db}</span>)}
        </div>
      )}

      <h4 className="block-h">② 忽略配置（只需配置“忽略哪些”）</h4>
      <p className="desc-cell" style={{ margin: '0 0 10px' }}>
        <b>库、表均支持正则 / 通配</b>：精确名直接填；通配用 <code className="k">log_*</code>；正则以 <code className="k">re:</code> 开头（如 <code className="k">re:^tmp_.*</code>）。<br />
        忽略表采用<b>按库层级</b>：每个库可配置不同的忽略表；整库忽略、通用忽略对所有库生效。
      </p>
      <div className="rx-field">
        <label className="rx-label">忽略整库</label>
        <Select value={pair.ignoreDatabases} multiple allowCreate filterable loading={pair.loadingDbs}
          options={pair.sourceDatabases} placeholder="从源库动态选择要跳过的库（也可手填正则/通配），回车添加"
          onChange={(v) => onUpdatePair(idx, { ignoreDatabases: v })} />
      </div>
      {renderDbIgnore('dml')}
      {renderDbIgnore('ddl')}

      <h4 className="block-h">②-c 通用忽略（所有库）</h4>
      <div className="rx-field">
        <label className="rx-label">通用忽略(DML+DDL)</label>
        <Select value={pair.commonIgnoreTables} multiple allowCreate filterable
          options={[]} placeholder="所有库生效，精确名或 re: 正则（如 re:^tmp_.*），回车添加"
          onChange={(v) => onUpdatePair(idx, { commonIgnoreTables: v })} />
      </div>
      <div className="rx-field">
        <label className="rx-label">通用忽略(仅DDL)</label>
        <Select value={pair.commonDdlIgnoreTables} multiple allowCreate filterable
          options={[]} placeholder="所有库生效的 DDL 通用忽略，精确名或 re: 正则，回车添加"
          onChange={(v) => onUpdatePair(idx, { commonDdlIgnoreTables: v })} />
      </div>

      <h4 className="block-h">③ 字段转换规则（源值 → 目标值，如 IP 替换）</h4>
      <div className="rx-collapse" style={{ border: '1px solid #ebeef5', borderRadius: 6, padding: '0 12px' }}>
        <div className="rx-collapse-head" style={{ padding: '10px 0' }} onClick={() => onUpdatePair(idx, { advActive: !pair.advActive })}>
          {pair.advActive ? '▾' : '▸'} 字段转换规则（库-表-字段，支持 * 通配；主键列不参与转换）
        </div>
        {pair.advActive && (
          <div className="rx-collapse-body">
            {(pair.transformRules || []).map((r, ridx) => (
              <div className="rule-row" key={ridx}>
                <input className="rx-input" style={{ width: 130, border: '1px solid #dcdfe6', borderRadius: 4, padding: '5px 8px' }} value={r.dbName} onChange={e => onUpdateRule(idx, ridx, { dbName: e.target.value })} placeholder="库名(*)" />
                <input className="rx-input" style={{ width: 130, border: '1px solid #dcdfe6', borderRadius: 4, padding: '5px 8px' }} value={r.tableName} onChange={e => onUpdateRule(idx, ridx, { tableName: e.target.value })} placeholder="表名(*)" />
                <input className="rx-input" style={{ width: 130, border: '1px solid #dcdfe6', borderRadius: 4, padding: '5px 8px' }} value={r.fieldName} onChange={e => onUpdateRule(idx, ridx, { fieldName: e.target.value })} placeholder="字段名" />
                <input className="rx-input" style={{ width: 130, border: '1px solid #dcdfe6', borderRadius: 4, padding: '5px 8px' }} value={r.sourceValue} onChange={e => onUpdateRule(idx, ridx, { sourceValue: e.target.value })} placeholder="源值" />
                <input className="rx-input" style={{ width: 130, border: '1px solid #dcdfe6', borderRadius: 4, padding: '5px 8px' }} value={r.targetValue} onChange={e => onUpdateRule(idx, ridx, { targetValue: e.target.value })} placeholder="目标值" />
                <button className="rx-btn rx-btn-danger" onClick={() => onRemoveRule(idx, ridx)}>删除</button>
              </div>
            ))}
            <button className="rx-btn" onClick={() => onAddRule(idx)}>+ 添加规则</button>
          </div>
        )}
      </div>

      <div className="toolbar" style={{ marginTop: 10 }}>
        <button className="rx-btn rx-btn-primary" disabled={pair.adding} onClick={() => onSave(idx)}>{pair.adding ? '保存中...' : '保存并开始同步'}</button>
        <span className="desc-cell">将按源/目标主机对创建同步作业，自动同步该源主机下所有用户库与表（忽略项除外）。</span>
      </div>
    </div>
  );
}

/* ============ 主应用 ============ */
function App() {
  const defaultBaseUrl = (window.location.protocol === 'http:' || window.location.protocol === 'https:') ? window.location.origin : 'http://127.0.0.1:8080';
  const [baseUrl, setBaseUrl] = useState(defaultBaseUrl);
  const [loading, setLoading] = useState(false);
  const [activeTab, setActiveTab] = useState('stats');
  const [authed, setAuthed] = useState(false);
  const [authMode, setAuthMode] = useState('login');
  const [authLoading, setAuthLoading] = useState(false);
  const [authForm, setAuthForm] = useState({ username: '', password: '' });
  const [currentUser, setCurrentUser] = useState('');

  const [hostPairs, setHostPairs] = useState([blankPair()]);
  const [mappingsList, setMappingsList] = useState([]);
  const [configMode, setConfigMode] = useState('form');
  const [configJson, setConfigJson] = useState('');
  const [jsonError, setJsonError] = useState('');
  const [adding, setAdding] = useState(false);

  /* ---- 操作日志 ---- */
  const [logs, setLogs] = useState([]);
  const [logsTotal, setLogsTotal] = useState(0);
  const [logsPage, setLogsPage] = useState(1);
  const [logsPageSize] = useState(20);
  const [logTypes, setLogTypes] = useState([]);
  const nowStr = (() => { const d = new Date(); const p = (n) => String(n).padStart(2, '0'); return `${d.getFullYear()}-${p(d.getMonth()+1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`; })();
  const todayStr = (() => { const d = new Date(); const p = (n) => String(n).padStart(2, '0'); return `${d.getFullYear()}-${p(d.getMonth()+1)}-${p(d.getDate())} 00:00:00`; })();
  const [logFilter, setLogFilter] = useState({ username: '', operationType: '', resultStatus: '', clientIp: '', startTime: todayStr, endTime: nowStr });
  const [logDetail, setLogDetail] = useState(null);
  /* datetime-local 需要 yyyy-MM-ddTHH:mm 格式，内部状态用 yyyy-MM-dd HH:mm:ss */
  const toDtl = (s) => s ? s.replace(' ', 'T').substring(0, 16) : '';
  const fromDtl = (s) => s ? s.replace('T', ' ') + ':00' : '';

  const [status, setStatus] = useState({ status: 1, desc: '', firstExceptionTime: '' });
  const [ipList, setIpList] = useState([]);
  const [databases, setDatabases] = useState([]);
  const [currentIp, setCurrentIp] = useState('');
  const [list, setList] = useState([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize] = useState(10);
  const [query, setQuery] = useState({ ip: '', sourceDbName: '', state: null });
  const [selectedRows, setSelectedRows] = useState([]);
  const [drawer, setDrawer] = useState(false);
  const [current, setCurrent] = useState(null);
  const timerRef = useRef(null);

  /* http 包装：返回后端 JSON body（Result 包裹或裸数据） */
  const http = useCallback(async ({ url, method = 'GET', data }) => {
    const opt = { method, headers: {}, credentials: 'include' };
    if (data !== undefined) { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(data); }
    const res = await fetch(baseUrl + url, opt);
    let json = {};
    try { json = await res.json(); } catch (e) { /* ignore */ }
    if (res.status === 401) { setAuthed(false); toast('warning', '登录已过期，请重新登录'); throw new Error('unauth'); }
    if (!res.ok) { const detail = json.message || json.msg || res.statusText; toast('error', '请求失败: ' + detail); throw new Error('http ' + res.status); }
    /* HTTP 200 但业务失败（如登录密码错误）—— 显示后端返回的错误消息 */
    if (json && json.success === false) { const detail = json.message || '操作失败'; toast('error', detail); throw new Error('business ' + json.code); }
    return json;
  }, [baseUrl]);

  /* ---- 鉴权 ---- */
  const checkAuth = useCallback(async () => {
    try {
      const res = await fetch(baseUrl + '/auth/me', { credentials: 'include' });
      const json = await res.json().catch(() => ({}));
      // /auth/me 永远返回 200，用 data.loggedIn 表达登录态，不再靠 401 探测（避免控制台 401 噪声）
      const d = json.data || {};
      if (d.loggedIn && d.username) { setAuthed(true); setCurrentUser(d.username); await loadAll(); }
      else { setAuthed(false); setCurrentUser(''); }
    } catch (e) { setAuthed(false); setCurrentUser(''); }
    // eslint-disable-next-line
  }, [baseUrl]);

  const doLogin = async () => {
    if (!authForm.username || !authForm.password) { toast('warning', '请输入用户名和密码'); return; }
    setAuthLoading(true);
    try {
      const r = await http({ url: '/auth/login', method: 'POST', data: { ...authForm } });
      setAuthed(true); setCurrentUser((r.data && r.data.username) || authForm.username); toast('success', '登录成功'); await loadAll();
    } catch (e) { /* toast handled in http */ } finally { setAuthLoading(false); }
  };
  const doRegister = async () => {
    if (!authForm.username || !authForm.password) { toast('warning', '请输入用户名和密码'); return; }
    setAuthLoading(true);
    try {
      await http({ url: '/auth/register', method: 'POST', data: { ...authForm } });
      toast('success', '注册成功，正在登录...'); await doLogin();
    } catch (e) { /* */ } finally { setAuthLoading(false); }
  };
  const doLogout = async () => {
    try { await http({ url: '/auth/logout', method: 'POST' }); } catch (e) { /* */ }
    setAuthed(false); setCurrentUser('');
  };

  /* ---- 统计监控 ---- */
  const loadStatus = async () => { try { const r = await http({ url: '/sync/status' }); setStatus(s => ({ ...s, ...r })); } catch (e) { /* */ } };
  const loadIpList = async () => { try { const r = await http({ url: '/sync/ipList' }); setIpList(Array.isArray(r) ? r : []); } catch (e) { /* */ } };
  const loadDatabases = async (ip) => {
    setCurrentIp(ip);
    try { const r = await http({ url: '/sync/databases/' + encodeURIComponent(ip) }); setDatabases(Array.isArray(r) ? r : []); } catch (e) { /* */ }
  };
  const loadList = async () => {
    const body = { page, pageSize, condition: { ...query } };
    try { const r = await http({ url: '/sync/db/list', method: 'POST', data: body }); setList(r.results || []); setTotal(r.total || 0); } catch (e) { /* */ }
  };
  /* ---- 操作日志加载 ---- */
  const loadLogTypes = async () => { try { const r = await http({ url: '/operation-log/types' }); setLogTypes(r.types || []); } catch (e) { /* */ } };
  const loadLogs = async (p) => {
    const pg = p || logsPage;
    const body = { page: pg, pageSize: logsPageSize, ...logFilter };
    try { const r = await http({ url: '/operation-log/list', method: 'POST', data: body }); setLogs(r.results || []); setLogsTotal(r.total || 0); } catch (e) { /* */ }
  };
  const searchLogs = () => { setLogsPage(1); loadLogs(1); };
  const resetLogFilter = () => { const d = new Date(); const p = (n) => String(n).padStart(2, '0'); const ts = `${d.getFullYear()}-${p(d.getMonth()+1)}-${p(d.getDate())} 00:00:00`; const ns = `${d.getFullYear()}-${p(d.getMonth()+1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`; setLogFilter({ username: '', operationType: '', resultStatus: '', clientIp: '', startTime: ts, endTime: ns }); setLogsPage(1); loadLogs(1); };
  const onLogsPage = (p) => { setLogsPage(p); loadLogs(p); };
  const loadAll = useCallback(async () => {
    setLoading(true);
    try { await Promise.all([loadStatus(), loadIpList(), loadList(), loadMappings()]); } catch (e) { /* */ }
    setLoading(false);
    // eslint-disable-next-line
  }, [page, pageSize, query]);
  const search = () => { setPage(1); loadList(); };
  const resetQuery = () => { setQuery({ ip: '', sourceDbName: '', state: null }); setPage(1); loadList(); };
  const onPage = (p) => { setPage(p); loadList(); };
  const toggleRow = (row, checked) => {
    setSelectedRows(prev => checked ? [...prev.filter(r => r.id !== row.id), row] : prev.filter(r => r.id !== row.id));
  };
  const toggleAll = (checked) => { setSelectedRows(checked ? [...list] : []); };
  const detail = async (row) => { try { const r = await http({ url: '/sync/' + row.id }); setCurrent(r.data); setDrawer(true); } catch (e) { /* */ } };
  const resync = async () => {
    if (!selectedRows.length) { toast('warning', '请先勾选要重新同步的记录'); return; }
    const items = selectedRows.map(r => ({ ip: r.sourceIp, sourceDbName: r.sourceDbName, dbName: r.sourceDbName }));
    try {
      const r = await http({ url: '/sync/resyncDatabases', method: 'POST', data: items });
      const ok = (r.data && r.data.success) ? r.data.success.length : 0;
      const fail = (r.data && r.data.failed) ? r.data.failed.length : 0;
      toast('success', '已提交重新同步：成功 ' + ok + '，失败 ' + fail);
      setTimeout(loadAll, 1500);
    } catch (e) { /* */ }
  };

  /* ---- 主机对配置 ---- */
  const updatePair = (idx, patch) => setHostPairs(ps => ps.map((p, i) => (i === idx ? { ...p, ...patch } : p)));
  const updateEntry = (idx, kind, eIdx, patch) => {
    const listName = kind === 'dml' ? 'ignoreTablesByDb' : 'ignoreDdlTablesByDb';
    setHostPairs(ps => ps.map((p, i) => {
      if (i !== idx) return p;
      const list = p[listName].map((e, j) => (j === eIdx ? { ...e, ...patch } : e));
      return { ...p, [listName]: list };
    }));
  };
  const updateRule = (idx, ridx, patch) => {
    setHostPairs(ps => ps.map((p, i) => {
      if (i !== idx) return p;
      const rules = p.transformRules.map((r, j) => (j === ridx ? { ...r, ...patch } : r));
      return { ...p, transformRules: rules };
    }));
  };
  const addHostPair = () => setHostPairs(ps => [...ps, blankPair()]);
  const removeHostPair = (idx) => { if (hostPairs.length > 1) setHostPairs(ps => ps.filter((_, i) => i !== idx)); };

  const testConnection = async (idx) => {
    const pair = hostPairs[idx];
    if (!pair.sourceHost || !pair.sourceUser || !pair.sourcePassword || !pair.targetHost || !pair.targetUser || !pair.targetPassword) { toast('warning', '请完整填写源主机与目标主机的连接信息'); return; }
    updatePair(idx, { testing: true, testResult: null });
    try {
      const r = await http({ url: '/sync/mapping/test', method: 'POST', data: { ...pair } });
      updatePair(idx, { testResult: r.data });
      if (r.data.source.ok && r.data.target.ok) toast('success', '源主机与目标主机连接测试均通过');
      else toast('error', '连接测试未通过');
    } catch (e) { /* */ } finally { updatePair(idx, { testing: false }); }
  };
  const loadSourceDatabases = async (idx) => {
    const pair = hostPairs[idx];
    if (!pair.sourceHost || !pair.sourceUser || !pair.sourcePassword) { toast('warning', '请先填写源主机的主机、账号、密码'); return; }
    updatePair(idx, { loadingDbs: true });
    try {
      const r = await http({ url: '/sync/sourceDatabases', method: 'POST', data: { ...pair } });
      updatePair(idx, { sourceDatabases: r.data || [] });
      toast('success', '已加载源主机库列表（' + (r.data || []).length + ' 个，已排除系统库）');
    } catch (e) { /* */ } finally { updatePair(idx, { loadingDbs: false }); }
  };
  const loadSourceTables = async (idx, kind, eIdx) => {
    const pair = hostPairs[idx];
    const listName = kind === 'dml' ? 'ignoreTablesByDb' : 'ignoreDdlTablesByDb';
    const entry = pair[listName][eIdx];
    if (!isConcreteDb(entry.database)) { toast('warning', '库名为正则/通配时无法动态拉取表，请手动填写表规则'); return; }
    if (!pair.sourceHost || !pair.sourceUser || !pair.sourcePassword) { toast('warning', '请先填写源主机连接信息'); return; }
    updatePair(idx, { loadingTablesDb: entry.database });
    try {
      const r = await http({ url: '/sync/sourceTables', method: 'POST', data: { sourceHost: pair.sourceHost, sourcePort: pair.sourcePort, sourceUser: pair.sourceUser, sourcePassword: pair.sourcePassword, database: entry.database } });
      const tableCache = { ...pair.tableCache, [entry.database]: (r.data || []) };
      updatePair(idx, { tableCache });
      toast('success', '已加载库 ' + entry.database + ' 的表清单（' + (r.data || []).length + ' 张）');
    } catch (e) { /* */ } finally { updatePair(idx, { loadingTablesDb: null }); }
  };
  const addDbTableIgnore = (idx, kind) => {
    const listName = kind === 'dml' ? 'ignoreTablesByDb' : 'ignoreDdlTablesByDb';
    setHostPairs(ps => ps.map((p, i) => (i === idx ? { ...p, [listName]: [...p[listName], { database: '', tables: [] }] } : p)));
  };
  const removeDbTableIgnore = (idx, kind, eIdx) => {
    const listName = kind === 'dml' ? 'ignoreTablesByDb' : 'ignoreDdlTablesByDb';
    setHostPairs(ps => ps.map((p, i) => {
      if (i !== idx) return p;
      const list = p[listName].filter((_, j) => j !== eIdx);
      return { ...p, [listName]: list };
    }));
  };
  const onAddRule = (idx) => setHostPairs(ps => ps.map((p, i) => (i === idx ? { ...p, transformRules: [...p.transformRules, { dbName: '*', tableName: '*', fieldName: '', sourceValue: '', targetValue: '' }] } : p)));
  const onRemoveRule = (idx, ridx) => setHostPairs(ps => ps.map((p, i) => (i === idx ? { ...p, transformRules: p.transformRules.filter((_, j) => j !== ridx) } : p)));

  const reportAddResult = (res) => {
    const createdN = (res.created || []).length;
    const skippedN = (res.skipped || []).length;
    const failedN = (res.failed || []).length;
    if (createdN > 0) toast('success', '已开始同步主机对 ' + createdN + ' 个' + (skippedN ? '，跳过 ' + skippedN + ' 个已存在' : '') + (failedN ? '，失败 ' + failedN : ''));
    else if (skippedN > 0 && failedN === 0) toast('warning', '该主机对已存在同步映射（已跳过）');
    else toast('error', '保存失败：' + (res.failed || []).join('; '));
  };
  const savePair = async (idx) => {
    const pair = hostPairs[idx];
    if (!pair.sourceHost || !pair.sourceUser || !pair.sourcePassword || !pair.targetHost || !pair.targetUser || !pair.targetPassword) { toast('warning', '请完整填写源主机与目标主机的连接信息'); return; }
    updatePair(idx, { adding: true });
    try {
      const r = await http({ url: '/sync/mapping/add', method: 'POST', data: buildPairConfig(pair) });
      reportAddResult(r.data || {});
      await loadMappings(); await loadAll();
    } catch (e) { /* */ } finally { updatePair(idx, { adding: false }); }
  };

  /* ---- JSON 双向互转 ---- */
  const parseJson = () => {
    try { const obj = JSON.parse(configJson); setJsonError(''); return obj; }
    catch (e) { setJsonError(e.message); toast('error', 'JSON 解析失败：' + e.message); return null; }
  };
  const formatJson = () => { const obj = parseJson(); if (obj) { setConfigJson(JSON.stringify(obj, null, 2)); toast('success', 'JSON 格式正确'); } };
  const formToJson = () => {
    const arr = hostPairs.map(buildPairConfig);
    setConfigJson(JSON.stringify(arr.length === 1 ? arr[0] : arr, null, 2));
    setJsonError('');
    toast('success', '已根据表单生成 JSON');
  };
  const jsonToForm = () => {
    const obj = parseJson();
    if (!obj) return;
    const arr = Array.isArray(obj) ? obj : [obj];
    const pairs = arr.map(o => {
      const p = blankPair();
      if (o.sourceHost !== undefined) p.sourceHost = o.sourceHost;
      if (o.sourcePort !== undefined) p.sourcePort = o.sourcePort;
      if (o.sourceUser !== undefined) p.sourceUser = o.sourceUser;
      if (o.sourcePassword !== undefined) p.sourcePassword = o.sourcePassword;
      if (o.targetHost !== undefined) p.targetHost = o.targetHost;
      if (o.targetPort !== undefined) p.targetPort = o.targetPort;
      if (o.targetUser !== undefined) p.targetUser = o.targetUser;
      if (o.targetPassword !== undefined) p.targetPassword = o.targetPassword;
      p.ignoreDatabases = o.ignoreDatabases || [];
      p.ignoreTablesByDb = (o.ignoreTablesByDb || []).map(e => ({ database: e.database || '', tables: e.tables || [] }));
      p.ignoreDdlTablesByDb = (o.ignoreDdlTablesByDb || []).map(e => ({ database: e.database || '', tables: e.tables || [] }));
      p.commonIgnoreTables = o.commonIgnoreTables || [];
      p.commonDdlIgnoreTables = o.commonDdlIgnoreTables || [];
      if (Array.isArray(o.transformRules) && o.transformRules.length) {
        p.transformRules = o.transformRules.map(r => ({ dbName: r.dbName || '*', tableName: r.tableName || '*', fieldName: r.fieldName || '', sourceValue: r.sourceValue || '', targetValue: r.targetValue || '' }));
      }
      return p;
    });
    setHostPairs(pairs);
    toast('success', '已将 JSON 应用到表单（' + pairs.length + ' 个主机对）');
  };
  const saveAndSyncJson = async () => {
    const obj = parseJson();
    if (!obj) return;
    const arr = Array.isArray(obj) ? obj : [obj];
    for (const o of arr) {
      if (!o.sourceHost || !o.sourceUser || !o.sourcePassword || !o.targetHost || !o.targetUser || !o.targetPassword) { toast('warning', 'JSON 中源主机与目标主机的连接信息必须完整'); return; }
    }
    setAdding(true);
    try {
      let anyCreated = false, anyFail = false;
      for (const o of arr) {
        const r = await http({ url: '/sync/mapping/add', method: 'POST', data: o });
        const res = r.data || {};
        if ((res.created || []).length > 0) anyCreated = true;
        if ((res.failed || []).length > 0) anyFail = true;
      }
      if (anyCreated) toast('success', '已开始同步主机对（JSON）');
      else if (anyFail) toast('error', '部分主机对保存失败');
      else toast('warning', '所有主机对已存在（已跳过）');
      await loadMappings(); await loadAll();
    } catch (e) { /* */ } finally { setAdding(false); }
  };
  const onConfigModeChange = (mode) => { if (mode === 'json' && !configJson.trim()) formToJson(); };

  /* ---- 已配置主机对 ---- */
  const loadMappings = async () => { try { const r = await http({ url: '/sync/mappings' }); setMappingsList(r.data || []); } catch (e) { /* */ } };
  const removeMapping = async (row) => {
    try {
      await http({ url: '/sync/mapping/remove', method: 'POST', data: { instanceKey: row.instanceKey } });
      toast('success', '已移除主机对：' + row.instanceKey);
      await loadMappings(); await loadAll();
    } catch (e) { /* */ }
  };

  /* ---- 生命周期：自动刷新 ---- */
  useEffect(() => { checkAuth(); }, [checkAuth]);
  useEffect(() => {
    if (timerRef.current) clearInterval(timerRef.current);
    if (authed && activeTab === 'stats') {
      timerRef.current = setInterval(() => { if (authed && activeTab === 'stats') loadAll(); }, 5000);
    }
    return () => { if (timerRef.current) clearInterval(timerRef.current); };
  }, [authed, activeTab, loadAll]);

  /* ---- 登录页 ---- */
  if (!authed) {
    return (
      <div className="login-wrap">
        <div className="login-card">
          <h2>GeoDRSync 管理后台</h2>
          <div className="tip">请登录后使用同步配置与监控功能</div>
          {authMode === 'login' ? (
            <div>
              <div className="rx-field"><label className="rx-label">用户名</label><input className="rx-input" style={{ border: '1px solid #dcdfe6', borderRadius: 4, padding: '8px 10px', width: '100%', boxSizing: 'border-box' }} value={authForm.username} onChange={e => setAuthForm(f => ({ ...f, username: e.target.value }))} placeholder="用户名" onKeyDown={e => e.key === 'Enter' && doLogin()} /></div>
              <div className="rx-field"><label className="rx-label">密码</label><input className="rx-input" type="password" style={{ border: '1px solid #dcdfe6', borderRadius: 4, padding: '8px 10px', width: '100%', boxSizing: 'border-box' }} value={authForm.password} onChange={e => setAuthForm(f => ({ ...f, password: e.target.value }))} placeholder="密码" onKeyDown={e => e.key === 'Enter' && doLogin()} /></div>
              <button className="rx-btn rx-btn-primary login-submit" style={{ width: '100%' }} disabled={authLoading} onClick={doLogin}>{authLoading ? '登录中...' : '登 录'}</button>
              <div style={{ marginTop: 12, textAlign: 'right' }}><span className="rx-link" onClick={() => setAuthMode('register')}>没有账号？注册</span></div>
            </div>
          ) : (
            <div>
              <div className="rx-field"><label className="rx-label">用户名</label><input className="rx-input" style={{ border: '1px solid #dcdfe6', borderRadius: 4, padding: '8px 10px', width: '100%', boxSizing: 'border-box' }} value={authForm.username} onChange={e => setAuthForm(f => ({ ...f, username: e.target.value }))} placeholder="3-32 位" /></div>
              <div className="rx-field"><label className="rx-label">密码</label><input className="rx-input" type="password" style={{ border: '1px solid #dcdfe6', borderRadius: 4, padding: '8px 10px', width: '100%', boxSizing: 'border-box' }} value={authForm.password} onChange={e => setAuthForm(f => ({ ...f, password: e.target.value }))} placeholder="至少 6 位" /></div>
              <button className="rx-btn rx-btn-primary login-submit" style={{ width: '100%' }} disabled={authLoading} onClick={doRegister}>{authLoading ? '注册中...' : '注 册'}</button>
              <div style={{ marginTop: 12, textAlign: 'right' }}><span className="rx-link" onClick={() => setAuthMode('login')}>已有账号？登录</span></div>
            </div>
          )}
        </div>
        <div className="login-viz" aria-hidden="true">
          <div className="viz-header">
            <span className="viz-dot"></span>
            <span className="viz-title">REAL-TIME SYNC TOPOLOGY</span>
          </div>
          <svg className="sync-topo" viewBox="0 0 560 440" preserveAspectRatio="xMidYMid meet">
            <defs>
              <filter id="glow" x="-50%" y="-50%" width="200%" height="200%">
                <feGaussianBlur stdDeviation="2.5" result="blur" />
                <feMerge>
                  <feMergeNode in="blur" />
                  <feMergeNode in="SourceGraphic" />
                </feMerge>
              </filter>
            </defs>

            {/* ===== 3 条独立的一对一同步管道（源库 → 目标库） ===== */}
            <path id="lk1" d="M92,80 L468,80" className="link" />
            <path id="lk2" d="M92,220 L468,220" className="link" />
            <path id="lk3" d="M92,360 L468,360" className="link" />

            {/* ===== 流动数据光点（每条管道 2 颗，错峰） ===== */}
            <circle r="3.5" className="flow-dot" filter="url(#glow)">
              <animateMotion dur="2.4s" repeatCount="indefinite" begin="0s"><mpath href="#lk1" /></animateMotion>
            </circle>
            <circle r="3.5" className="flow-dot" filter="url(#glow)">
              <animateMotion dur="2.4s" repeatCount="indefinite" begin="1.2s"><mpath href="#lk1" /></animateMotion>
            </circle>
            <circle r="3.5" className="flow-dot" filter="url(#glow)">
              <animateMotion dur="2.8s" repeatCount="indefinite" begin="0.3s"><mpath href="#lk2" /></animateMotion>
            </circle>
            <circle r="3.5" className="flow-dot" filter="url(#glow)">
              <animateMotion dur="2.8s" repeatCount="indefinite" begin="1.7s"><mpath href="#lk2" /></animateMotion>
            </circle>
            <circle r="3.5" className="flow-dot" filter="url(#glow)">
              <animateMotion dur="2.6s" repeatCount="indefinite" begin="0.6s"><mpath href="#lk3" /></animateMotion>
            </circle>
            <circle r="3.5" className="flow-dot" filter="url(#glow)">
              <animateMotion dur="2.6s" repeatCount="indefinite" begin="1.9s"><mpath href="#lk3" /></animateMotion>
            </circle>

            {/* ===== 管道 1：10.0.1.10 / db_auth → 10.8.8.1 / db_auth ===== */}
            <g className="node node-src" transform="translate(92,80)">
              <rect x="-52" y="-18" width="104" height="36" rx="5" className="node-box" />
              <circle cx="-38" cy="0" r="3" className="node-led" />
              <text x="-28" y="-2" className="node-ip">10.0.1.10</text>
              <text x="-28" y="11" className="node-db">db_auth</text>
            </g>
            <g className="node node-dst" transform="translate(468,80)">
              <rect x="-52" y="-18" width="104" height="36" rx="5" className="node-box" />
              <circle cx="-38" cy="0" r="3" className="node-led node-led-dst" />
              <text x="-28" y="-2" className="node-ip">10.8.8.1</text>
              <text x="-28" y="11" className="node-db">db_auth</text>
            </g>

            {/* ===== 管道 2：10.0.1.20 / db_log → 10.8.8.2 / db_log ===== */}
            <g className="node node-src" transform="translate(92,220)">
              <rect x="-52" y="-18" width="104" height="36" rx="5" className="node-box" />
              <circle cx="-38" cy="0" r="3" className="node-led" />
              <text x="-28" y="-2" className="node-ip">10.0.1.20</text>
              <text x="-28" y="11" className="node-db">db_log</text>
            </g>
            <g className="node node-dst" transform="translate(468,220)">
              <rect x="-52" y="-18" width="104" height="36" rx="5" className="node-box" />
              <circle cx="-38" cy="0" r="3" className="node-led node-led-dst" />
              <text x="-28" y="-2" className="node-ip">10.8.8.2</text>
              <text x="-28" y="11" className="node-db">db_log</text>
            </g>

            {/* ===== 管道 3：10.0.1.30 / db_pay → 10.8.8.3 / db_pay ===== */}
            <g className="node node-src" transform="translate(92,360)">
              <rect x="-52" y="-18" width="104" height="36" rx="5" className="node-box" />
              <circle cx="-38" cy="0" r="3" className="node-led" />
              <text x="-28" y="-2" className="node-ip">10.0.1.30</text>
              <text x="-28" y="11" className="node-db">db_pay</text>
            </g>
            <g className="node node-dst" transform="translate(468,360)">
              <rect x="-52" y="-18" width="104" height="36" rx="5" className="node-box" />
              <circle cx="-38" cy="0" r="3" className="node-led node-led-dst" />
              <text x="-28" y="-2" className="node-ip">10.8.8.3</text>
              <text x="-28" y="11" className="node-db">db_pay</text>
            </g>

            {/* ===== 列标题 ===== */}
            <text x="92" y="30" className="col-label">SOURCE · ACTIVE</text>
            <text x="468" y="30" className="col-label">TARGET · STANDBY</text>
          </svg>
          <div className="viz-footer">
            <span className="vf-item"><span className="vf-led vf-led-link"></span>3 sync channels</span>
            <span className="vf-item">1:1 replication</span>
          </div>
        </div>
        <ToastHost />
      </div>
    );
  }

  /* ---- 主界面 ---- */
  const selectedIds = new Set(selectedRows.map(r => r.id));
  const allChecked = list.length > 0 && list.every(r => selectedIds.has(r.id));

  return (
    <div>
      <div className="topbar">
        <div>
          <div className="title">GeoDRSync · 地理数据库灾备同步服务</div>
          <div className="sub">Flink CDC 方向 · 内嵌同步引擎（本地可运行版） · 实时灾备一致性保障</div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <input className="rx-input" style={{ width: 240, border: '1px solid rgba(255,255,255,.4)', borderRadius: 4, padding: '5px 8px', background: 'rgba(255,255,255,.1)', color: '#fff' }} value={baseUrl} onChange={e => setBaseUrl(e.target.value)} placeholder="后端地址" />
          <button className="rx-btn" onClick={loadAll}>刷新</button>
          <span className="desc-cell" style={{ opacity: .85 }}>Hi, {currentUser}</span>
          <button className="rx-btn" onClick={doLogout}>退出</button>
        </div>
      </div>

      <div className="wrap">
        <div className="rx-tabs">
          <div className={'rx-tab' + (activeTab === 'stats' ? ' rx-tab-active' : '')} onClick={() => setActiveTab('stats')}>统计监控</div>
          <div className={'rx-tab' + (activeTab === 'config' ? ' rx-tab-active' : '')} onClick={() => setActiveTab('config')}>配置中心</div>
          <div className={'rx-tab' + (activeTab === 'logs' ? ' rx-tab-active' : '')} onClick={() => { setActiveTab('logs'); loadLogTypes(); loadLogs(1); }}>操作日志</div>
        </div>

        {activeTab === 'stats' && (
          <div>
            <div className="card">
              <h3>同步总览</h3>
              <div className="stat-row">
                <div className="stat"><div className="k">整体状态</div><div className={'v ' + ((status.status === 'NORMAL' || status.status === 1) ? 'ok' : 'bad')}>{status.desc || '加载中...'}</div></div>
                <div className="stat"><div className="k">主机对 / 运行作业</div><div className="v info">{mappingsList.length} / {mappingsList.filter(m => m.running).length}</div></div>
                <div className="stat"><div className="k">最近异常时间</div><div className="v warn">{status.firstExceptionTime || '—'}</div></div>
                <div className="stat"><div className="k">元数据库</div><div className="v info">geodrsync</div></div>
              </div>
            </div>

            <div className="card">
              <h3>节点与数据库</h3>
              <div className="rx-tablescroll">
              <table className="rx-table">
                <thead><tr><th style={{ width: 200 }}>IP</th><th style={{ width: 140 }}>角色</th><th>操作</th></tr></thead>
                <tbody>
                  {ipList.map((row, i) => (
                    <tr key={i}>
                      <td>{row.ip}</td>
                      <td><span className={'rx-tag-static ' + (row.type === '生产中心' ? 'rx-danger' : 'rx-success')}>{row.type}</span></td>
                      <td><button className="rx-btn rx-btn-primary" onClick={() => loadDatabases(row.ip)}>查看其下数据库</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
              </div>
              {databases.length > 0 && (
                <div style={{ marginTop: 12 }}>
                  <div className="desc-cell" style={{ marginBottom: 8 }}>IP <code className="k">{currentIp}</code> 下用户数据库（{databases.length}）：</div>
                  {databases.map(db => <span key={db} className="rx-tag-static rx-info" style={{ margin: '0 6px 6px 0' }}>{db}</span>)}
                </div>
              )}
            </div>

            <div className="card">
              <h3>同步进度列表</h3>
              <div className="toolbar">
                <input className="rx-input" style={{ width: 150, border: '1px solid #dcdfe6', borderRadius: 4, padding: '6px 8px' }} value={query.ip} onChange={e => setQuery(q => ({ ...q, ip: e.target.value }))} placeholder="源IP筛选" />
                <input className="rx-input" style={{ width: 160, border: '1px solid #dcdfe6', borderRadius: 4, padding: '6px 8px' }} value={query.sourceDbName} onChange={e => setQuery(q => ({ ...q, sourceDbName: e.target.value }))} placeholder="源库名(模糊)" />
                <select style={{ width: 130, border: '1px solid #dcdfe6', borderRadius: 4, padding: '6px 8px' }} value={query.state ?? ''} onChange={e => setQuery(q => ({ ...q, state: e.target.value === '' ? null : Number(e.target.value) }))}>
                  <option value="">状态</option>
                  <option value="0">失效</option>
                  <option value="1">全量同步</option>
                  <option value="2">同步中</option>
                  <option value="3">中止</option>
                </select>
                <button className="rx-btn rx-btn-primary" onClick={search}>查询</button>
                <button className="rx-btn" onClick={resetQuery}>重置</button>
                <button className="rx-btn rx-btn-success" disabled={!selectedRows.length} onClick={resync}>重新同步选中({selectedRows.length})</button>
              </div>
              <div className="rx-tablescroll">
              <table className="rx-table">
                <thead>
                  <tr>
                    <th style={{ width: 46 }}><input type="checkbox" checked={allChecked} onChange={e => toggleAll(e.target.checked)} /></th>
                    <th style={{ width: 70 }}>ID</th>
                    <th style={{ width: 200 }}>源(生产中心)</th>
                    <th style={{ width: 200 }}>目标(灾备中心)</th>
                    <th style={{ width: 100 }}>状态</th>
                    <th style={{ width: 100 }}>偏差状态</th>
                    <th>处理/统计信息</th>
                    <th style={{ width: 170 }}>更新时间</th>
                    <th style={{ width: 90 }}>操作</th>
                  </tr>
                </thead>
                <tbody>
                  {list.map(row => (
                    <tr key={row.id}>
                      <td><input type="checkbox" checked={selectedIds.has(row.id)} onChange={e => toggleRow(row, e.target.checked)} /></td>
                      <td>{row.id}</td>
                      <td>{row.sourceIp} / {row.sourceDbName}</td>
                      <td>{row.targetIp} / {row.targetDbName}</td>
                      <td><span className={'rx-tag-static ' + stateTag(row.state)}>{stateDesc(row.state)}</span></td>
                      <td>{row.deviationStatus === 1 ? <span className="rx-tag-static rx-success">正常</span> : row.deviationStatus === 2 ? <span className="rx-tag-static rx-danger">异常</span> : '—'}</td>
                      <td>{row.processingMethod}</td>
                      <td>{row.updateTime}</td>
                      <td><button className="rx-btn rx-btn-primary" onClick={() => detail(row)}>详情</button></td>
                    </tr>
                  ))}
                  {list.length === 0 && <tr><td colSpan={9} style={{ textAlign: 'center', color: '#909399' }}>暂无数据</td></tr>}
                </tbody>
              </table>
              </div>
              <div style={{ marginTop: 12, display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: 14 }}>
                <span className="desc-cell">共 {total} 条</span>
                <Pager total={total} page={page} pageSize={pageSize} onChange={onPage} />
              </div>
            </div>
          </div>
        )}

        {activeTab === 'config' && (
          <div>
            <div className="card">
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10 }}>
                <div>
                  <span className="collapse-title">配置方式</span>
                  <span className="desc-cell" style={{ marginLeft: 10 }}>表单适合快速录入；JSON 适合批量 / 复制粘贴 / 精细控制（两者双向同步）。</span>
                </div>
                <div className="rx-radio">
                  <button className={configMode === 'form' ? 'active' : ''} onClick={() => { setConfigMode('form'); onConfigModeChange('form'); }}>表单方式</button>
                  <button className={configMode === 'json' ? 'active' : ''} onClick={() => { setConfigMode('json'); onConfigModeChange('json'); }}>JSON 方式</button>
                </div>
              </div>
            </div>

            {configMode === 'form' && (
              <div>
                <div className="hp-actions">
                  <button className="rx-btn rx-btn-primary" onClick={addHostPair}>+ 添加主机对</button>
                  <span className="desc-cell">可配置<b>多对主机</b>（微服务异地多机房备份），每对独立保存与运行。该源主机下<b>所有用户库与表自动同步</b>（系统库与忽略项除外），新增库/表实时纳入。</span>
                </div>
                {hostPairs.map((pair, idx) => (
                  <PairCard key={idx} pair={pair} idx={idx} canRemove={hostPairs.length > 1}
                    onUpdatePair={updatePair} onUpdateEntry={updateEntry} onUpdateRule={updateRule}
                    onTest={testConnection} onLoadDbs={loadSourceDatabases} onLoadTables={loadSourceTables}
                    onAddDbIgnore={addDbTableIgnore} onRemoveDbIgnore={removeDbTableIgnore}
                    onAddRule={onAddRule} onRemoveRule={onRemoveRule}
                    onSave={savePair} onRemovePair={removeHostPair} />
                ))}
              </div>
            )}

            {configMode === 'json' && (
              <div className="card">
                <h3>JSON 配置（整份主机对配置：连接 + 忽略项 + 转换规则）</h3>
                <p className="desc-cell" style={{ margin: '-6px 0 12px' }}>
                  可直接编辑或粘贴一份 JSON 后保存；<b>库、表均支持正则</b>。支持单对象或对象数组（多主机对）。
                  忽略表采用<b>层级结构</b> <code className="k">{'ignoreTablesByDb:[{"database":"sales","tables":["t_log","re:^tmp_.*"]}]'}</code>，
                  并提供 <code className="k">commonIgnoreTables</code> / <code className="k">commonDdlIgnoreTables</code>（所有库生效）。也可用下方按钮与表单相互转换。
                </p>
                <textarea className="rx-textarea" rows={20} value={configJson} onChange={e => setConfigJson(e.target.value)} placeholder='{ "sourceHost": "127.0.0.1", ... }' />
                <div className="toolbar" style={{ marginTop: 12 }}>
                  <button className="rx-btn" onClick={formToJson}>↑ 从表单生成 JSON</button>
                  <button className="rx-btn" onClick={jsonToForm}>↓ 应用 JSON 到表单</button>
                  <button className="rx-btn" onClick={formatJson}>格式化 / 校验</button>
                  <button className="rx-btn rx-btn-primary" disabled={adding} onClick={saveAndSyncJson}>保存并开始同步（JSON）</button>
                  {jsonError && <span className="rx-err">JSON 错误：{jsonError}</span>}
                </div>
              </div>
            )}

            <div className="card">
              <h3>已配置同步主机对</h3>
              <div className="rx-tablescroll">
              <table className="rx-table">
                <thead>
                  <tr>
                    <th style={{ width: 140 }}>源主机</th>
                    <th style={{ width: 140 }}>目标主机</th>
                    <th style={{ width: 150 }}>账号</th>
                    <th style={{ width: 130 }}>忽略库</th>
                    <th>按库忽略(DML+DDL)</th>
                    <th>按库忽略(仅DDL)</th>
                    <th>通用忽略(所有库)</th>
                    <th style={{ width: 90 }}>转换规则</th>
                    <th style={{ width: 80 }}>来源</th>
                    <th style={{ width: 84 }}>状态</th>
                    <th style={{ width: 84 }}>操作</th>
                  </tr>
                </thead>
                <tbody>
                  {mappingsList.map((row, i) => (
                    <tr key={i}>
                      <td>{row.sourceHost}</td>
                      <td>{row.targetHost}</td>
                      <td>{row.sourceUser} → {row.targetUser}</td>
                      <td>{(row.ignoreDatabases && row.ignoreDatabases.length) ? row.ignoreDatabases.join(', ') : '—'}</td>
                      <td>{fmtDbIgnore(row.ignoreTablesByDb)}</td>
                      <td>{fmtDbIgnore(row.ignoreDdlTablesByDb)}</td>
                      <td>{(row.commonIgnoreTables && row.commonIgnoreTables.length) || (row.commonDdlIgnoreTables && row.commonDdlIgnoreTables.length) ? ('DML:' + ((row.commonIgnoreTables || []).join(',') || '—') + ' | DDL:' + ((row.commonDdlIgnoreTables || []).join(',') || '—')) : '—'}</td>
                      <td>{(row.transformRules && row.transformRules.length) ? (row.transformRules.length + ' 条') : '—'}</td>
                      <td><span className={'rx-tag-static ' + (row.source === 'dynamic' ? 'rx-warning' : 'rx-info')}>{row.source === 'dynamic' ? '页面' : '配置'}</span></td>
                      <td><span className={'rx-tag-static ' + (row.running ? 'rx-success' : 'rx-info')}>{row.running ? '运行中' : '已停止'}</span></td>
                      <td><button className="rx-btn rx-btn-danger" disabled={row.source !== 'dynamic'} onClick={() => removeMapping(row)}>移除</button></td>
                    </tr>
                  ))}
                  {mappingsList.length === 0 && <tr><td colSpan={11} style={{ textAlign: 'center', color: '#909399' }}>暂无主机对配置</td></tr>}
                </tbody>
              </table>
              </div>
              <div className="desc-cell">提示：仅页面动态添加的主机对可移除；配置文件（application.yml）中的主机对需手动改配置。</div>
            </div>
          </div>
        )}

        {activeTab === 'logs' && (
          <div className="card">
            <h3>操作审计日志</h3>
            <div className="filter-bar" style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'flex-end', marginBottom: 16 }}>
              <div className="rx-field" style={{ minWidth: 120 }}>
                <label className="rx-label">用户名</label>
                <input className="rx-input" style={{ border: '1px solid #dcdfe6', borderRadius: 4, padding: '6px 10px', width: '100%', boxSizing: 'border-box' }}
                  value={logFilter.username} placeholder="精确匹配"
                  onChange={e => setLogFilter(f => ({ ...f, username: e.target.value }))} />
              </div>
              <div className="rx-field" style={{ minWidth: 120 }}>
                <label className="rx-label">客户端 IP</label>
                <input className="rx-input" style={{ border: '1px solid #dcdfe6', borderRadius: 4, padding: '6px 10px', width: '100%', boxSizing: 'border-box' }}
                  value={logFilter.clientIp} placeholder="模糊匹配"
                  onChange={e => setLogFilter(f => ({ ...f, clientIp: e.target.value }))} />
              </div>
              <div className="rx-field" style={{ minWidth: 140 }}>
                <label className="rx-label">操作类型</label>
                <select className="rx-input" style={{ border: '1px solid #dcdfe6', borderRadius: 4, padding: '6px 10px', width: '100%', boxSizing: 'border-box' }}
                  value={logFilter.operationType}
                  onChange={e => setLogFilter(f => ({ ...f, operationType: e.target.value }))}>
                  <option value="">全部</option>
                  {logTypes.map(t => <option key={t} value={t}>{t}</option>)}
                </select>
              </div>
              <div className="rx-field" style={{ minWidth: 100 }}>
                <label className="rx-label">结果</label>
                <select className="rx-input" style={{ border: '1px solid #dcdfe6', borderRadius: 4, padding: '6px 10px', width: '100%', boxSizing: 'border-box' }}
                  value={logFilter.resultStatus}
                  onChange={e => setLogFilter(f => ({ ...f, resultStatus: e.target.value }))}>
                  <option value="">全部</option>
                  <option value="SUCCESS">成功</option>
                  <option value="FAILURE">失败</option>
                </select>
              </div>
              <div className="rx-field" style={{ minWidth: 150 }}>
                <label className="rx-label">开始时间</label>
                <input type="datetime-local" className="rx-input" style={{ border: '1px solid #dcdfe6', borderRadius: 4, padding: '6px 10px', width: '100%', boxSizing: 'border-box' }}
                  value={toDtl(logFilter.startTime)}
                  onChange={e => setLogFilter(f => ({ ...f, startTime: fromDtl(e.target.value) }))} />
              </div>
              <div className="rx-field" style={{ minWidth: 150 }}>
                <label className="rx-label">结束时间</label>
                <input type="datetime-local" className="rx-input" style={{ border: '1px solid #dcdfe6', borderRadius: 4, padding: '6px 10px', width: '100%', boxSizing: 'border-box' }}
                  value={toDtl(logFilter.endTime)}
                  onChange={e => setLogFilter(f => ({ ...f, endTime: fromDtl(e.target.value) }))} />
              </div>
              <div style={{ display: 'flex', gap: 8 }}>
                <button className="rx-btn rx-btn-primary" onClick={searchLogs}>查询</button>
                <button className="rx-btn" onClick={resetLogFilter}>重置</button>
              </div>
            </div>
            <div className="rx-tablescroll">
              <table className="rx-table">
                <thead>
                  <tr>
                    <th style={{ width: 60 }}>ID</th>
                    <th style={{ width: 100 }}>用户名</th>
                    <th style={{ width: 120 }}>客户端 IP</th>
                    <th style={{ width: 140 }}>操作类型</th>
                    <th>操作描述</th>
                    <th style={{ width: 80 }}>结果</th>
                    <th style={{ width: 80 }}>耗时(ms)</th>
                    <th style={{ width: 160 }}>操作时间</th>
                    <th style={{ width: 60 }}>详情</th>
                  </tr>
                </thead>
                <tbody>
                  {logs.length === 0 && <tr><td colSpan={9} style={{ textAlign: 'center', padding: 24, color: '#999' }}>暂无日志记录</td></tr>}
                  {logs.map((row) => (
                    <tr key={row.id}>
                      <td style={{ fontFamily: 'monospace' }}>{row.id}</td>
                      <td>{row.username || '—'}</td>
                      <td style={{ fontFamily: 'monospace' }}>{row.clientIp || '—'}</td>
                      <td><span style={{ fontFamily: 'monospace', fontSize: 12, background: 'var(--bg-2)', padding: '2px 6px', borderRadius: 3 }}>{row.operationType}</span></td>
                      <td style={{ maxWidth: 300, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={row.operationDesc}>{row.operationDesc || '—'}</td>
                      <td>{row.resultStatus === 'SUCCESS' ? <span style={{ color: 'var(--accent)' }}>成功</span> : <span style={{ color: 'var(--danger)' }}>失败</span>}</td>
                      <td style={{ fontFamily: 'monospace', fontVariantNumeric: 'tabular-nums' }}>{row.durationMs ?? '—'}</td>
                      <td style={{ fontFamily: 'monospace', fontSize: 12 }}>{row.createTime}</td>
                      <td><span className="rx-link" onClick={() => setLogDetail(row)}>查看</span></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 12 }}>
              <span style={{ color: 'var(--text-2)', fontSize: 13 }}>共 {logsTotal} 条</span>
              <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                <button className="rx-btn" disabled={logsPage <= 1} onClick={() => onLogsPage(logsPage - 1)}>上一页</button>
                <span style={{ fontFamily: 'monospace', fontSize: 13 }}>{logsPage} / {Math.max(1, Math.ceil(logsTotal / logsPageSize))}</span>
                <button className="rx-btn" disabled={logsPage >= Math.ceil(logsTotal / logsPageSize)} onClick={() => onLogsPage(logsPage + 1)}>下一页</button>
              </div>
            </div>
          </div>
        )}

        <div className="footer">GeoDRSync · 本地内嵌同步引擎演示 · 主机对自动同步 + 忽略式配置 + 实时新库新表监测</div>
      </div>

      <Drawer open={drawer} title="同步详情" onClose={() => setDrawer(false)}>
        {current && (
          <div className="rx-tablescroll">
          <table className="rx-dlist">
            <tbody>
              <tr><th>ID</th><td>{current.id}</td></tr>
              <tr><th>源IP</th><td>{current.sourceIp}</td></tr>
              <tr><th>源库</th><td>{current.sourceDbName}</td></tr>
              <tr><th>目标IP</th><td>{current.targetIp}</td></tr>
              <tr><th>目标库</th><td>{current.targetDbName}</td></tr>
              <tr><th>状态</th><td>{stateDesc(current.state)}</td></tr>
              <tr><th>偏差状态</th><td>{current.deviationStatus === 1 ? '正常' : current.deviationStatus === 2 ? '异常' : '—'}</td></tr>
              <tr><th>偏差次数</th><td>{current.deviationTimes}</td></tr>
              <tr><th>源Binlog</th><td>{current.sourceBinlogFile} @ {current.sourceBinlogTime}</td></tr>
              <tr><th>已同步Binlog</th><td>{current.syncBinlogFile} @ {current.syncBinlogTime}</td></tr>
              <tr><th>中止原因</th><td>{current.suspensionReason || '—'}</td></tr>
              <tr><th>处理信息</th><td>{current.processingMethod || '—'}</td></tr>
              <tr><th>更新时间</th><td>{current.updateTime}</td></tr>
            </tbody>
          </table>
          </div>
        )}
      </Drawer>

      <Drawer open={!!logDetail} title="日志详情" onClose={() => setLogDetail(null)}>
        {logDetail && (
          <div className="rx-tablescroll">
          <table className="rx-dlist">
            <tbody>
              <tr><th>ID</th><td style={{ fontFamily: 'monospace' }}>{logDetail.id}</td></tr>
              <tr><th>用户名</th><td>{logDetail.username || '—'}</td></tr>
              <tr><th>用户 ID</th><td style={{ fontFamily: 'monospace' }}>{logDetail.userId || '—'}</td></tr>
              <tr><th>客户端 IP</th><td style={{ fontFamily: 'monospace' }}>{logDetail.clientIp || '—'}</td></tr>
              <tr><th>操作类型</th><td><span style={{ fontFamily: 'monospace', fontSize: 12, background: 'var(--bg-2)', padding: '2px 6px', borderRadius: 3 }}>{logDetail.operationType}</span></td></tr>
              <tr><th>操作描述</th><td>{logDetail.operationDesc || '—'}</td></tr>
              <tr><th>目标资源</th><td>{logDetail.targetResource || '—'}</td></tr>
              <tr><th>请求方法</th><td style={{ fontFamily: 'monospace' }}>{logDetail.requestMethod || '—'}</td></tr>
              <tr><th>请求 URL</th><td style={{ fontFamily: 'monospace', fontSize: 12, wordBreak: 'break-all' }}>{logDetail.requestUrl || '—'}</td></tr>
              <tr><th>请求参数</th><td><pre style={{ margin: 0, maxHeight: 200, overflow: 'auto', fontSize: 12, fontFamily: 'monospace', background: 'var(--bg-2)', padding: 8, borderRadius: 4, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{logDetail.requestParams || '—'}</pre></td></tr>
              <tr><th>执行结果</th><td>{logDetail.resultStatus === 'SUCCESS' ? <span style={{ color: 'var(--accent)' }}>成功</span> : <span style={{ color: 'var(--danger)' }}>失败</span>}</td></tr>
              <tr><th>错误信息</th><td style={{ color: 'var(--danger)', fontSize: 12, wordBreak: 'break-all' }}>{logDetail.errorMsg || '—'}</td></tr>
              <tr><th>耗时</th><td style={{ fontFamily: 'monospace', fontVariantNumeric: 'tabular-nums' }}>{logDetail.durationMs != null ? logDetail.durationMs + ' ms' : '—'}</td></tr>
              <tr><th>操作时间</th><td style={{ fontFamily: 'monospace' }}>{logDetail.createTime}</td></tr>
            </tbody>
          </table>
          </div>
        )}
      </Drawer>

      <ToastHost />
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App />);
