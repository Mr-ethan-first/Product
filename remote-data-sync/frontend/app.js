/* AUTO-GENERATED from app.jsx by build_app.js. DO NOT EDIT. */
const {
  useState,
  useEffect,
  useRef,
  useCallback
} = React;
const TOAST_EVENT = 'rx-toast';
let toastSeq = 0;
function toast(type, text) {
  window.dispatchEvent(new CustomEvent(TOAST_EVENT, {
    detail: {
      type,
      text
    }
  }));
}
function ToastHost() {
  const [items, setItems] = useState([]);
  useEffect(() => {
    const h = e => {
      const {
        type,
        text
      } = e.detail;
      const id = ++toastSeq;
      setItems(a => [...a, {
        id,
        type,
        text
      }]);
      setTimeout(() => setItems(a => a.filter(x => x.id !== id)), 3000);
    };
    window.addEventListener(TOAST_EVENT, h);
    return () => window.removeEventListener(TOAST_EVENT, h);
  }, []);
  return React.createElement("div", {
    className: "rx-toasts"
  }, items.map(i => React.createElement("div", {
    key: i.id,
    className: 'rx-toast rx-' + i.type
  }, i.text)));
}
function Select({
  value,
  options = [],
  multiple = false,
  allowCreate = false,
  filterable = true,
  placeholder = '',
  loading = false,
  disabled = false,
  onChange,
  style
}) {
  const [open, setOpen] = useState(false);
  const [text, setText] = useState('');
  const boxRef = useRef(null);
  const norm = o => o && typeof o === 'object' ? {
    value: o.value,
    label: o.label != null ? String(o.label) : String(o.value)
  } : {
    value: o,
    label: String(o)
  };
  const normList = (options || []).map(norm);
  const vals = multiple ? value || [] : value ? [value] : [];
  useEffect(() => {
    const h = e => {
      if (boxRef.current && !boxRef.current.contains(e.target)) setOpen(false);
    };
    document.addEventListener('mousedown', h);
    return () => document.removeEventListener('mousedown', h);
  }, []);
  const filtered = normList.filter(o => String(o.label || '').toLowerCase().includes(String(text || '').toLowerCase()) || String(o.value || '').toLowerCase().includes(String(text || '').toLowerCase()));
  const showCreate = allowCreate && !!text && !normList.some(o => o.value === text || o.label === text) && !vals.includes(text);
  const toggle = v => {
    if (multiple) {
      const next = vals.includes(v) ? vals.filter(x => x !== v) : [...vals, v];
      onChange(next);
    } else {
      onChange(v);
      setOpen(false);
    }
  };
  const create = () => {
    if (!text) return;
    const next = multiple ? [...vals, text] : [text];
    onChange(next);
    setText('');
    if (!multiple) setOpen(false);
  };
  return React.createElement("div", {
    ref: boxRef,
    className: 'rx-select' + (disabled ? ' rx-disabled' : ''),
    style: style
  }, React.createElement("div", {
    className: "rx-select-box",
    onClick: () => {
      if (!disabled) setOpen(o => !o);
    }
  }, vals.length === 0 && !open && React.createElement("span", {
    className: "rx-ph"
  }, placeholder), vals.map(v => {
    const it = normList.find(x => x.value === v);
    return React.createElement("span", {
      key: v,
      className: "rx-tag"
    }, it ? it.label : v, multiple && React.createElement("span", {
      className: "rx-x",
      onClick: e => {
        e.stopPropagation();
        toggle(v);
      }
    }, "\xD7"), !multiple && React.createElement("span", {
      className: "rx-x",
      onClick: e => {
        e.stopPropagation();
        onChange('');
      }
    }, "\xD7"));
  }), open && filterable && React.createElement("input", {
    className: "rx-input",
    autoFocus: true,
    value: text,
    placeholder: placeholder,
    onClick: e => e.stopPropagation(),
    onChange: e => setText(e.target.value),
    onKeyDown: e => {
      if (e.key === 'Enter') {
        if (showCreate) create();else if (filtered[0]) toggle(filtered[0].value);
      }
      if (e.key === 'Backspace' && !text && multiple && vals.length) onChange(vals.slice(0, -1));
    }
  })), open && React.createElement("div", {
    className: "rx-dropdown"
  }, loading && React.createElement("div", {
    className: "rx-loading"
  }, "\u52A0\u8F7D\u4E2D..."), filtered.map(o => React.createElement("div", {
    key: o.value,
    className: 'rx-opt' + (vals.includes(o.value) ? ' rx-sel' : ''),
    onClick: () => toggle(o.value)
  }, o.label)), showCreate && React.createElement("div", {
    className: "rx-opt rx-create",
    onClick: create
  }, "\u521B\u5EFA: \u201C", text, "\u201D"), !loading && filtered.length === 0 && !showCreate && React.createElement("div", {
    className: "rx-empty"
  }, "\u65E0\u5339\u914D")));
}
function DateTimePicker({
  value,
  onChange,
  placeholder = '选择日期时间',
  disabled = false,
  style
}) {
  const pad = n => String(n).padStart(2, '0');
  const ref = useRef(null);
  const [open, setOpen] = useState(false);
  const parse = s => {
    if (!s) return null;
    const m = String(s).match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{1,2}):(\d{1,2})/);
    if (!m) return null;
    return {
      y: +m[1],
      mo: +m[2] - 1,
      d: +m[3],
      h: +m[4],
      mi: +m[5]
    };
  };
  const now = new Date();
  const init = parse(value) || {
    y: now.getFullYear(),
    mo: now.getMonth(),
    d: now.getDate(),
    h: now.getHours(),
    mi: now.getMinutes()
  };
  const [sel, setSel] = useState(init);
  const [view, setView] = useState({
    y: init.y,
    mo: init.mo
  });
  useEffect(() => {
    const p = parse(value);
    if (p) {
      setSel(p);
      setView({
        y: p.y,
        mo: p.mo
      });
    }
  }, [value]);
  useEffect(() => {
    const h = e => {
      if (ref.current && !ref.current.contains(e.target)) setOpen(false);
    };
    document.addEventListener('mousedown', h);
    return () => document.removeEventListener('mousedown', h);
  }, []);
  const weekDays = ['日', '一', '二', '三', '四', '五', '六'];
  const firstDay = new Date(view.y, view.mo, 1).getDay();
  const days = new Date(view.y, view.mo + 1, 0).getDate();
  const cells = [];
  for (let i = 0; i < firstDay; i++) cells.push(null);
  for (let d = 1; d <= days; d++) cells.push(d);
  const isSame = d => sel.y === view.y && sel.mo === view.mo && sel.d === d;
  const isToday = d => {
    const n = new Date();
    return n.getFullYear() === view.y && n.getMonth() === view.mo && n.getDate() === d;
  };
  const move = delta => {
    let mo = view.mo + delta,
      y = view.y;
    if (mo < 0) {
      mo = 11;
      y--;
    }
    if (mo > 11) {
      mo = 0;
      y++;
    }
    setView({
      y,
      mo
    });
  };
  const pickDay = d => setSel(s => ({
    ...s,
    d
  }));
  const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, isNaN(v) ? lo : v));
  const confirm = () => {
    onChange(`${sel.y}-${pad(sel.mo + 1)}-${pad(sel.d)} ${pad(sel.h)}:${pad(sel.mi)}:00`);
    setOpen(false);
  };
  return React.createElement("div", {
    ref: ref,
    className: 'rx-dtpicker' + (disabled ? ' rx-disabled' : ''),
    style: style
  }, React.createElement("div", {
    className: "rx-dtpicker-box",
    onClick: () => {
      if (!disabled) setOpen(o => !o);
    }
  }, value ? React.createElement("span", {
    className: "rx-dtp-value"
  }, String(value).replace(':00', '')) : React.createElement("span", {
    className: "rx-ph"
  }, placeholder), React.createElement("span", {
    className: "rx-cal-ico",
    "aria-hidden": "true"
  }, React.createElement("svg", {
    width: "16",
    height: "16",
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: "2",
    strokeLinecap: "round",
    strokeLinejoin: "round"
  }, React.createElement("rect", {
    x: "3",
    y: "4",
    width: "18",
    height: "17",
    rx: "2"
  }), React.createElement("path", {
    d: "M3 9h18M8 2v4M16 2v4"
  })))), open && React.createElement("div", {
    className: "rx-cal"
  }, React.createElement("div", {
    className: "rx-cal-head"
  }, React.createElement("button", {
    type: "button",
    className: "rx-cal-nav",
    onClick: () => move(-1)
  }, "\u2039"), React.createElement("span", {
    className: "rx-cal-title"
  }, view.y, " \u5E74 ", view.mo + 1, " \u6708"), React.createElement("button", {
    type: "button",
    className: "rx-cal-nav",
    onClick: () => move(1)
  }, "\u203A")), React.createElement("div", {
    className: "rx-cal-week"
  }, weekDays.map(w => React.createElement("span", {
    key: w
  }, w))), React.createElement("div", {
    className: "rx-cal-grid"
  }, cells.map((d, i) => d === null ? React.createElement("span", {
    key: i,
    className: "rx-cal-cell empty"
  }) : React.createElement("button", {
    type: "button",
    key: i,
    className: 'rx-cal-cell' + (isSame(d) ? ' sel' : '') + (isToday(d) ? ' today' : ''),
    onClick: () => pickDay(d)
  }, d))), React.createElement("div", {
    className: "rx-cal-time"
  }, React.createElement("span", {
    className: "rx-cal-time-lbl"
  }, "\u65F6\u95F4"), React.createElement("input", {
    type: "number",
    className: "rx-cal-num",
    min: "0",
    max: "23",
    value: sel.h,
    onChange: e => setSel(s => ({
      ...s,
      h: clamp(+e.target.value, 0, 23)
    }))
  }), React.createElement("span", {
    className: "rx-cal-colon"
  }, ":"), React.createElement("input", {
    type: "number",
    className: "rx-cal-num",
    min: "0",
    max: "59",
    value: sel.mi,
    onChange: e => setSel(s => ({
      ...s,
      mi: clamp(+e.target.value, 0, 59)
    }))
  })), React.createElement("div", {
    className: "rx-cal-foot"
  }, React.createElement("button", {
    type: "button",
    className: "rx-btn",
    onClick: () => setOpen(false)
  }, "\u53D6\u6D88"), React.createElement("button", {
    type: "button",
    className: "rx-btn rx-btn-primary",
    onClick: confirm
  }, "\u786E\u5B9A"))));
}
function Drawer({
  open,
  title,
  onClose,
  children
}) {
  if (!open) return null;
  return React.createElement("div", {
    className: "rx-mask",
    onClick: onClose
  }, React.createElement("div", {
    className: "rx-drawer",
    onClick: e => e.stopPropagation()
  }, React.createElement("div", {
    style: {
      display: 'flex',
      justifyContent: 'space-between',
      alignItems: 'center',
      marginBottom: 14
    }
  }, React.createElement("h3", {
    style: {
      margin: 0
    }
  }, title), React.createElement("span", {
    className: "rx-link",
    onClick: onClose
  }, "\u5173\u95ED")), children));
}
function Pager({
  total,
  page,
  pageSize,
  onChange
}) {
  const pages = Math.max(1, Math.ceil(total / pageSize));
  const nums = [];
  const start = Math.max(1, Math.min(page - 2, pages - 4));
  const end = Math.min(pages, start + 4);
  for (let i = start; i <= end; i++) nums.push(i);
  return React.createElement("span", {
    className: "rx-pager"
  }, React.createElement("button", {
    disabled: page <= 1,
    onClick: () => onChange(page - 1)
  }, "\u4E0A\u4E00\u9875"), start > 1 && React.createElement("button", {
    onClick: () => onChange(1)
  }, "1"), start > 2 && React.createElement("span", {
    style: {
      padding: '0 4px'
    }
  }, "\u2026"), nums.map(n => React.createElement("button", {
    key: n,
    className: n === page ? 'active' : '',
    onClick: () => onChange(n)
  }, n)), end < pages - 1 && React.createElement("span", {
    style: {
      padding: '0 4px'
    }
  }, "\u2026"), end < pages && React.createElement("button", {
    onClick: () => onChange(pages)
  }, pages), React.createElement("button", {
    disabled: page >= pages,
    onClick: () => onChange(page + 1)
  }, "\u4E0B\u4E00\u9875"));
}
function isConcreteDb(db) {
  return !!db && !String(db).startsWith('re:') && db.indexOf('*') < 0 && db.indexOf('?') < 0;
}
function stateDesc(s) {
  return ['失效', '全量同步', '同步中', '中止'][s] || '未知';
}
function stateTag(s) {
  return ['rx-info', 'rx-warning', 'rx-success', 'rx-danger'][s] || 'rx-info';
}
function fmtDbIgnore(list) {
  if (!list || !list.length) return '—';
  return list.map(e => (e.database || '?') + ':[' + (e.tables && e.tables.length ? e.tables.join(',') : '—') + ']').join('; ');
}
function blankPair() {
  return {
    sourceHost: '127.0.0.1',
    sourcePort: 3306,
    sourceUser: 'root',
    sourcePassword: '',
    targetHost: '192.168.88.88',
    targetPort: 3306,
    targetUser: 'root',
    targetPassword: '',
    sourceDatabases: [],
    loadingDbs: false,
    testResult: null,
    testing: false,
    tableCache: {},
    loadingTablesDb: null,
    ignoreDatabases: [],
    ignoreTablesByDb: [],
    ignoreDdlTablesByDb: [],
    commonIgnoreTables: [],
    commonDdlIgnoreTables: [],
    advActive: false,
    transformRules: [{
      dbName: '*',
      tableName: '*',
      fieldName: '',
      sourceValue: '',
      targetValue: ''
    }],
    adding: false
  };
}
function validRules(pair) {
  const out = [];
  for (const r of pair.transformRules || []) {
    if (!r.fieldName || !r.sourceValue) continue;
    out.push({
      dbName: r.dbName || '*',
      tableName: r.tableName || '*',
      fieldName: r.fieldName,
      sourceValue: r.sourceValue,
      targetValue: r.targetValue || ''
    });
  }
  return out;
}
function buildPairConfig(pair) {
  return {
    sourceHost: pair.sourceHost,
    sourcePort: pair.sourcePort,
    sourceUser: pair.sourceUser,
    sourcePassword: pair.sourcePassword,
    targetHost: pair.targetHost,
    targetPort: pair.targetPort,
    targetUser: pair.targetUser,
    targetPassword: pair.targetPassword,
    ignoreDatabases: pair.ignoreDatabases || [],
    ignoreTablesByDb: pair.ignoreTablesByDb || [],
    ignoreDdlTablesByDb: pair.ignoreDdlTablesByDb || [],
    commonIgnoreTables: pair.commonIgnoreTables || [],
    commonDdlIgnoreTables: pair.commonDdlIgnoreTables || [],
    transformRules: validRules(pair)
  };
}
function PairCard(props) {
  const {
    pair,
    idx,
    onUpdatePair,
    onUpdateEntry,
    onUpdateRule,
    onTest,
    onLoadDbs,
    onLoadTables,
    onAddDbIgnore,
    onRemoveDbIgnore,
    onAddRule,
    onRemoveRule,
    onSave,
    onRemovePair,
    canRemove
  } = props;
  const renderDbIgnore = kind => {
    const listName = kind === 'dml' ? 'ignoreTablesByDb' : 'ignoreDdlTablesByDb';
    const list = pair[listName] || [];
    const title = kind === 'dml' ? '按库忽略表（DML + DDL 均不同步）' : '按库忽略表（仅忽略 DDL，数据仍同步）';
    return React.createElement("div", {
      className: "sub-block"
    }, React.createElement("div", {
      className: "sub-title"
    }, title), list.map((entry, i) => React.createElement("div", {
      className: "db-ignore-row",
      key: kind + i
    }, React.createElement(Select, {
      value: entry.database || '',
      options: pair.sourceDatabases || [],
      allowCreate: true,
      filterable: true,
      placeholder: "\u9009\u62E9/\u8F93\u5165\u5E93\u540D(\u652F\u6301\u6B63\u5219)",
      style: {
        width: 200
      },
      onChange: v => onUpdateEntry(idx, kind, i, {
        database: v
      })
    }), React.createElement(Select, {
      value: entry.tables || [],
      multiple: true,
      allowCreate: true,
      filterable: true,
      loading: pair.loadingTablesDb === entry.database,
      options: pair.tableCache[entry.database] || [],
      placeholder: isConcreteDb(entry.database) ? '选表或手填正则(如 re:^tmp_.*)' : '库为正则时请手填表规则',
      style: {
        flex: 1,
        minWidth: 280
      },
      onChange: v => onUpdateEntry(idx, kind, i, {
        tables: v
      })
    }), React.createElement("button", {
      className: "rx-btn",
      disabled: !isConcreteDb(entry.database),
      onClick: () => onLoadTables(idx, kind, i)
    }, "\u52A0\u8F7D\u8BE5\u5E93\u8868"), React.createElement("button", {
      className: "rx-btn rx-btn-danger",
      onClick: () => onRemoveDbIgnore(idx, kind, i)
    }, "\u5220\u9664"))), React.createElement("button", {
      className: "rx-btn",
      onClick: () => onAddDbIgnore(idx, kind)
    }, "+ \u6DFB\u52A0\u5E93\u5FFD\u7565"));
  };
  return React.createElement("div", {
    className: "card host-pair"
  }, React.createElement("div", {
    style: {
      display: 'flex',
      justifyContent: 'space-between',
      alignItems: 'center',
      flexWrap: 'wrap',
      gap: 8
    }
  }, React.createElement("h3", {
    style: {
      margin: 0
    }
  }, "\u4E3B\u673A\u5BF9 #", idx + 1, "\u3000", React.createElement("span", {
    className: "hp-key"
  }, pair.sourceHost, " \u2192 ", pair.targetHost)), canRemove && React.createElement("button", {
    className: "rx-btn rx-btn-danger",
    onClick: () => onRemovePair(idx)
  }, "\u5220\u9664\u6B64\u4E3B\u673A\u5BF9")), React.createElement("p", {
    className: "desc-cell",
    style: {
      margin: '8px 0 12px'
    }
  }, "\u586B\u5199", React.createElement("b", null, "\u6E90\u4E3B\u673A"), "\u4E0E", React.createElement("b", null, "\u76EE\u6807\u4E3B\u673A"), "\u7684 IP / \u7AEF\u53E3 / \u8D26\u53F7 / \u5BC6\u7801\u3002\u300C\u5FFD\u7565\u6574\u5E93\u300D\u4E0E\u300C\u6309\u5E93\u5FFD\u7565\u8868\u300D\u5747\u53EF\u4ECE\u6E90\u5E93\u52A8\u6001\u62C9\u53D6\u9009\u9879\u3002"), React.createElement("div", {
    style: {
      display: 'flex',
      gap: 24,
      flexWrap: 'wrap'
    }
  }, React.createElement("div", {
    className: "rx-box"
  }, React.createElement("h4", {
    style: {
      color: 'var(--danger)'
    }
  }, "\u6E90\u4E3B\u673A\uFF08\u751F\u4EA7\u4E2D\u5FC3\uFF09"), React.createElement("div", {
    className: "rx-field"
  }, React.createElement("label", {
    className: "rx-label"
  }, "\u4E3B\u673A"), React.createElement("input", {
    className: "rx-input",
    style: {
      border: '1px solid rgba(255,255,255,0.16)',
      borderRadius: 4,
      padding: '6px 8px',
      width: '100%',
      boxSizing: 'border-box'
    },
    value: pair.sourceHost,
    onChange: e => onUpdatePair(idx, {
      sourceHost: e.target.value
    }),
    placeholder: "\u5982 127.0.0.1"
  })), React.createElement("div", {
    className: "rx-field"
  }, React.createElement("label", {
    className: "rx-label"
  }, "\u7AEF\u53E3"), React.createElement("input", {
    className: "rx-input",
    style: {
      border: '1px solid rgba(255,255,255,0.16)',
      borderRadius: 4,
      padding: '6px 8px',
      width: '100%',
      boxSizing: 'border-box'
    },
    value: pair.sourcePort,
    onChange: e => onUpdatePair(idx, {
      sourcePort: e.target.value
    }),
    placeholder: "3306"
  })), React.createElement("div", {
    className: "rx-field"
  }, React.createElement("label", {
    className: "rx-label"
  }, "\u8D26\u53F7"), React.createElement("input", {
    className: "rx-input",
    style: {
      border: '1px solid rgba(255,255,255,0.16)',
      borderRadius: 4,
      padding: '6px 8px',
      width: '100%',
      boxSizing: 'border-box'
    },
    value: pair.sourceUser,
    onChange: e => onUpdatePair(idx, {
      sourceUser: e.target.value
    }),
    placeholder: "\u5982 root"
  })), React.createElement("div", {
    className: "rx-field"
  }, React.createElement("label", {
    className: "rx-label"
  }, "\u5BC6\u7801"), React.createElement("input", {
    className: "rx-input",
    type: "password",
    style: {
      border: '1px solid rgba(255,255,255,0.16)',
      borderRadius: 4,
      padding: '6px 8px',
      width: '100%',
      boxSizing: 'border-box'
    },
    value: pair.sourcePassword,
    onChange: e => onUpdatePair(idx, {
      sourcePassword: e.target.value
    }),
    placeholder: "\u6570\u636E\u5E93\u5BC6\u7801"
  }))), React.createElement("div", {
    className: "rx-box"
  }, React.createElement("h4", {
    style: {
      color: 'var(--accent)'
    }
  }, "\u76EE\u6807\u4E3B\u673A\uFF08\u707E\u5907\u4E2D\u5FC3\uFF09"), React.createElement("div", {
    className: "rx-field"
  }, React.createElement("label", {
    className: "rx-label"
  }, "\u4E3B\u673A"), React.createElement("input", {
    className: "rx-input",
    style: {
      border: '1px solid rgba(255,255,255,0.16)',
      borderRadius: 4,
      padding: '6px 8px',
      width: '100%',
      boxSizing: 'border-box'
    },
    value: pair.targetHost,
    onChange: e => onUpdatePair(idx, {
      targetHost: e.target.value
    }),
    placeholder: "\u5982 192.168.88.88"
  })), React.createElement("div", {
    className: "rx-field"
  }, React.createElement("label", {
    className: "rx-label"
  }, "\u7AEF\u53E3"), React.createElement("input", {
    className: "rx-input",
    style: {
      border: '1px solid rgba(255,255,255,0.16)',
      borderRadius: 4,
      padding: '6px 8px',
      width: '100%',
      boxSizing: 'border-box'
    },
    value: pair.targetPort,
    onChange: e => onUpdatePair(idx, {
      targetPort: e.target.value
    }),
    placeholder: "3306"
  })), React.createElement("div", {
    className: "rx-field"
  }, React.createElement("label", {
    className: "rx-label"
  }, "\u8D26\u53F7"), React.createElement("input", {
    className: "rx-input",
    style: {
      border: '1px solid rgba(255,255,255,0.16)',
      borderRadius: 4,
      padding: '6px 8px',
      width: '100%',
      boxSizing: 'border-box'
    },
    value: pair.targetUser,
    onChange: e => onUpdatePair(idx, {
      targetUser: e.target.value
    }),
    placeholder: "\u5982 root"
  })), React.createElement("div", {
    className: "rx-field"
  }, React.createElement("label", {
    className: "rx-label"
  }, "\u5BC6\u7801"), React.createElement("input", {
    className: "rx-input",
    type: "password",
    style: {
      border: '1px solid rgba(255,255,255,0.16)',
      borderRadius: 4,
      padding: '6px 8px',
      width: '100%',
      boxSizing: 'border-box'
    },
    value: pair.targetPassword,
    onChange: e => onUpdatePair(idx, {
      targetPassword: e.target.value
    }),
    placeholder: "\u6570\u636E\u5E93\u5BC6\u7801"
  })))), React.createElement("div", {
    className: "toolbar",
    style: {
      marginTop: 6
    }
  }, React.createElement("button", {
    className: "rx-btn",
    disabled: pair.testing,
    onClick: () => onTest(idx)
  }, pair.testing ? '测试中...' : '测试连接'), React.createElement("button", {
    className: "rx-btn",
    disabled: pair.loadingDbs,
    onClick: () => onLoadDbs(idx)
  }, pair.loadingDbs ? '加载中...' : '加载源库列表'), pair.testResult && React.createElement("span", {
    className: "desc-cell"
  }, React.createElement("span", {
    className: 'rx-tag-static ' + (pair.testResult.source.ok ? 'rx-success' : 'rx-danger'),
    style: {
      marginRight: 6
    }
  }, "\u6E90\u4E3B\u673A: ", pair.testResult.source.ok ? 'OK' : '失败'), React.createElement("span", {
    className: 'rx-tag-static ' + (pair.testResult.target.ok ? 'rx-success' : 'rx-danger')
  }, "\u76EE\u6807\u4E3B\u673A: ", pair.testResult.target.ok ? 'OK' : '失败'), (!pair.testResult.source.ok || !pair.testResult.target.ok) && React.createElement("span", {
    className: "rx-err",
    style: {
      marginLeft: 8
    }
  }, (!pair.testResult.source.ok ? '源:' + pair.testResult.source.message : '') + ' ' + (!pair.testResult.target.ok ? '目标:' + pair.testResult.target.message : '')))), pair.sourceDatabases.length > 0 && React.createElement("div", {
    className: "desc-cell",
    style: {
      marginTop: 8
    }
  }, "\u6E90\u4E3B\u673A\u4E0B ", React.createElement("b", null, pair.sourceDatabases.length), " \u4E2A\u7528\u6237\u5E93\uFF08\u5DF2\u6392\u9664\u7CFB\u7EDF\u5E93\uFF09\uFF1A", pair.sourceDatabases.map(db => React.createElement("span", {
    key: db,
    className: "rx-tag-static rx-info",
    style: {
      margin: '0 5px 5px 0'
    }
  }, db))), React.createElement("h4", {
    className: "block-h"
  }, "\u2461 \u5FFD\u7565\u914D\u7F6E\uFF08\u53EA\u9700\u914D\u7F6E\u201C\u5FFD\u7565\u54EA\u4E9B\u201D\uFF09"), React.createElement("p", {
    className: "desc-cell",
    style: {
      margin: '0 0 10px'
    }
  }, React.createElement("b", null, "\u5E93\u3001\u8868\u5747\u652F\u6301\u6B63\u5219 / \u901A\u914D"), "\uFF1A\u7CBE\u786E\u540D\u76F4\u63A5\u586B\uFF1B\u901A\u914D\u7528 ", React.createElement("code", {
    className: "k"
  }, "log_*"), "\uFF1B\u6B63\u5219\u4EE5 ", React.createElement("code", {
    className: "k"
  }, "re:"), " \u5F00\u5934\uFF08\u5982 ", React.createElement("code", {
    className: "k"
  }, "re:^tmp_.*"), "\uFF09\u3002", React.createElement("br", null), "\u5FFD\u7565\u8868\u91C7\u7528", React.createElement("b", null, "\u6309\u5E93\u5C42\u7EA7"), "\uFF1A\u6BCF\u4E2A\u5E93\u53EF\u914D\u7F6E\u4E0D\u540C\u7684\u5FFD\u7565\u8868\uFF1B\u6574\u5E93\u5FFD\u7565\u3001\u901A\u7528\u5FFD\u7565\u5BF9\u6240\u6709\u5E93\u751F\u6548\u3002"), React.createElement("div", {
    className: "rx-field"
  }, React.createElement("label", {
    className: "rx-label"
  }, "\u5FFD\u7565\u6574\u5E93"), React.createElement(Select, {
    value: pair.ignoreDatabases,
    multiple: true,
    allowCreate: true,
    filterable: true,
    loading: pair.loadingDbs,
    options: pair.sourceDatabases,
    placeholder: "\u4ECE\u6E90\u5E93\u52A8\u6001\u9009\u62E9\u8981\u8DF3\u8FC7\u7684\u5E93\uFF08\u4E5F\u53EF\u624B\u586B\u6B63\u5219/\u901A\u914D\uFF09\uFF0C\u56DE\u8F66\u6DFB\u52A0",
    onChange: v => onUpdatePair(idx, {
      ignoreDatabases: v
    })
  })), renderDbIgnore('dml'), renderDbIgnore('ddl'), React.createElement("h4", {
    className: "block-h"
  }, "\u2461-c \u901A\u7528\u5FFD\u7565\uFF08\u6240\u6709\u5E93\uFF09"), React.createElement("div", {
    className: "rx-field"
  }, React.createElement("label", {
    className: "rx-label"
  }, "\u901A\u7528\u5FFD\u7565(DML+DDL)"), React.createElement(Select, {
    value: pair.commonIgnoreTables,
    multiple: true,
    allowCreate: true,
    filterable: true,
    options: [],
    placeholder: "\u6240\u6709\u5E93\u751F\u6548\uFF0C\u7CBE\u786E\u540D\u6216 re: \u6B63\u5219\uFF08\u5982 re:^tmp_.*\uFF09\uFF0C\u56DE\u8F66\u6DFB\u52A0",
    onChange: v => onUpdatePair(idx, {
      commonIgnoreTables: v
    })
  })), React.createElement("div", {
    className: "rx-field"
  }, React.createElement("label", {
    className: "rx-label"
  }, "\u901A\u7528\u5FFD\u7565(\u4EC5DDL)"), React.createElement(Select, {
    value: pair.commonDdlIgnoreTables,
    multiple: true,
    allowCreate: true,
    filterable: true,
    options: [],
    placeholder: "\u6240\u6709\u5E93\u751F\u6548\u7684 DDL \u901A\u7528\u5FFD\u7565\uFF0C\u7CBE\u786E\u540D\u6216 re: \u6B63\u5219\uFF0C\u56DE\u8F66\u6DFB\u52A0",
    onChange: v => onUpdatePair(idx, {
      commonDdlIgnoreTables: v
    })
  })), React.createElement("h4", {
    className: "block-h"
  }, "\u2462 \u5B57\u6BB5\u8F6C\u6362\u89C4\u5219\uFF08\u6E90\u503C \u2192 \u76EE\u6807\u503C\uFF0C\u5982 IP \u66FF\u6362\uFF09"), React.createElement("div", {
    className: "rx-collapse",
    style: {
      border: '1px solid var(--border-strong)',
      borderRadius: 6,
      padding: '0 12px'
    }
  }, React.createElement("div", {
    className: "rx-collapse-head",
    style: {
      padding: '10px 0'
    },
    onClick: () => onUpdatePair(idx, {
      advActive: !pair.advActive
    })
  }, pair.advActive ? '▾' : '▸', " \u5B57\u6BB5\u8F6C\u6362\u89C4\u5219\uFF08\u5E93-\u8868-\u5B57\u6BB5\uFF0C\u652F\u6301 * \u901A\u914D\uFF1B\u4E3B\u952E\u5217\u4E0D\u53C2\u4E0E\u8F6C\u6362\uFF09"), pair.advActive && React.createElement("div", {
    className: "rx-collapse-body"
  }, (pair.transformRules || []).map((r, ridx) => React.createElement("div", {
    className: "rule-row",
    key: ridx
  }, React.createElement("input", {
    className: "rx-input",
    style: {
      width: 130,
      border: '1px solid rgba(255,255,255,0.16)',
      borderRadius: 4,
      padding: '5px 8px'
    },
    value: r.dbName,
    onChange: e => onUpdateRule(idx, ridx, {
      dbName: e.target.value
    }),
    placeholder: "\u5E93\u540D(*)"
  }), React.createElement("input", {
    className: "rx-input",
    style: {
      width: 130,
      border: '1px solid rgba(255,255,255,0.16)',
      borderRadius: 4,
      padding: '5px 8px'
    },
    value: r.tableName,
    onChange: e => onUpdateRule(idx, ridx, {
      tableName: e.target.value
    }),
    placeholder: "\u8868\u540D(*)"
  }), React.createElement("input", {
    className: "rx-input",
    style: {
      width: 130,
      border: '1px solid rgba(255,255,255,0.16)',
      borderRadius: 4,
      padding: '5px 8px'
    },
    value: r.fieldName,
    onChange: e => onUpdateRule(idx, ridx, {
      fieldName: e.target.value
    }),
    placeholder: "\u5B57\u6BB5\u540D"
  }), React.createElement("input", {
    className: "rx-input",
    style: {
      width: 130,
      border: '1px solid rgba(255,255,255,0.16)',
      borderRadius: 4,
      padding: '5px 8px'
    },
    value: r.sourceValue,
    onChange: e => onUpdateRule(idx, ridx, {
      sourceValue: e.target.value
    }),
    placeholder: "\u6E90\u503C"
  }), React.createElement("input", {
    className: "rx-input",
    style: {
      width: 130,
      border: '1px solid rgba(255,255,255,0.16)',
      borderRadius: 4,
      padding: '5px 8px'
    },
    value: r.targetValue,
    onChange: e => onUpdateRule(idx, ridx, {
      targetValue: e.target.value
    }),
    placeholder: "\u76EE\u6807\u503C"
  }), React.createElement("button", {
    className: "rx-btn rx-btn-danger",
    onClick: () => onRemoveRule(idx, ridx)
  }, "\u5220\u9664"))), React.createElement("button", {
    className: "rx-btn",
    onClick: () => onAddRule(idx)
  }, "+ \u6DFB\u52A0\u89C4\u5219"))), React.createElement("div", {
    className: "toolbar",
    style: {
      marginTop: 10
    }
  }, React.createElement("button", {
    className: "rx-btn rx-btn-primary",
    disabled: pair.adding,
    onClick: () => onSave(idx)
  }, pair.adding ? '保存中...' : '保存并开始同步'), React.createElement("span", {
    className: "desc-cell"
  }, "\u5C06\u6309\u6E90/\u76EE\u6807\u4E3B\u673A\u5BF9\u521B\u5EFA\u540C\u6B65\u4F5C\u4E1A\uFF0C\u81EA\u52A8\u540C\u6B65\u8BE5\u6E90\u4E3B\u673A\u4E0B\u6240\u6709\u7528\u6237\u5E93\u4E0E\u8868\uFF08\u5FFD\u7565\u9879\u9664\u5916\uFF09\u3002")));
}
function App() {
  const defaultBaseUrl = window.location.protocol === 'http:' || window.location.protocol === 'https:' ? window.location.origin : 'http://127.0.0.1:8080';
  const [baseUrl, setBaseUrl] = useState(defaultBaseUrl);
  const [loading, setLoading] = useState(false);
  const [activeTab, setActiveTab] = useState('stats');
  const [authed, setAuthed] = useState(false);
  const [authMode, setAuthMode] = useState('login');
  const [authLoading, setAuthLoading] = useState(false);
  const [authForm, setAuthForm] = useState({
    username: '',
    password: ''
  });
  const [currentUser, setCurrentUser] = useState('');
  const [hostPairs, setHostPairs] = useState([blankPair()]);
  const [mappingsList, setMappingsList] = useState([]);
  const [configMode, setConfigMode] = useState('form');
  const [configJson, setConfigJson] = useState('');
  const [jsonError, setJsonError] = useState('');
  const [adding, setAdding] = useState(false);
  const [logs, setLogs] = useState([]);
  const [logsTotal, setLogsTotal] = useState(0);
  const [logsPage, setLogsPage] = useState(1);
  const [logsPageSize] = useState(20);
  const [logTypes, setLogTypes] = useState([]);
  const nowStr = (() => {
    const d = new Date();
    const p = n => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
  })();
  const todayStr = (() => {
    const d = new Date();
    const p = n => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} 00:00:00`;
  })();
  const [logFilter, setLogFilter] = useState({
    username: '',
    operationType: '',
    resultStatus: '',
    clientIp: '',
    startTime: todayStr,
    endTime: nowStr
  });
  const [logDetail, setLogDetail] = useState(null);
  const toDtl = s => s ? s.replace(' ', 'T').substring(0, 16) : '';
  const fromDtl = s => s ? s.replace('T', ' ') + ':00' : '';
  const [status, setStatus] = useState({
    status: 1,
    desc: '',
    firstExceptionTime: ''
  });
  const [ipList, setIpList] = useState([]);
  const [databases, setDatabases] = useState([]);
  const [currentIp, setCurrentIp] = useState('');
  const [list, setList] = useState([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [pageSize] = useState(10);
  const [query, setQuery] = useState({
    ip: '',
    sourceDbName: '',
    state: null
  });
  const [selectedRows, setSelectedRows] = useState([]);
  const [drawer, setDrawer] = useState(false);
  const [current, setCurrent] = useState(null);
  const timerRef = useRef(null);
  const http = useCallback(async ({
    url,
    method = 'GET',
    data
  }) => {
    const opt = {
      method,
      headers: {},
      credentials: 'include'
    };
    if (data !== undefined) {
      opt.headers['Content-Type'] = 'application/json';
      opt.body = JSON.stringify(data);
    }
    const res = await fetch(baseUrl + url, opt);
    let json = {};
    try {
      json = await res.json();
    } catch (e) {}
    if (res.status === 401) {
      setAuthed(false);
      toast('warning', '登录已过期，请重新登录');
      throw new Error('unauth');
    }
    if (!res.ok) {
      const detail = json.message || json.msg || res.statusText;
      toast('error', '请求失败: ' + detail);
      throw new Error('http ' + res.status);
    }
    if (json && json.success === false) {
      const detail = json.message || '操作失败';
      toast('error', detail);
      throw new Error('business ' + json.code);
    }
    return json;
  }, [baseUrl]);
  const checkAuth = useCallback(async () => {
    try {
      const res = await fetch(baseUrl + '/auth/me', {
        credentials: 'include'
      });
      const json = await res.json().catch(() => ({}));
      const d = json.data || {};
      if (d.loggedIn && d.username) {
        setAuthed(true);
        setCurrentUser(d.username);
        await loadAll();
      } else {
        setAuthed(false);
        setCurrentUser('');
      }
    } catch (e) {
      setAuthed(false);
      setCurrentUser('');
    }
  }, [baseUrl]);
  const doLogin = async () => {
    if (!authForm.username || !authForm.password) {
      toast('warning', '请输入用户名和密码');
      return;
    }
    setAuthLoading(true);
    try {
      const r = await http({
        url: '/auth/login',
        method: 'POST',
        data: {
          ...authForm
        }
      });
      setAuthed(true);
      setCurrentUser(r.data && r.data.username || authForm.username);
      toast('success', '登录成功');
      await loadAll();
    } catch (e) {} finally {
      setAuthLoading(false);
    }
  };
  const doRegister = async () => {
    if (!authForm.username || !authForm.password) {
      toast('warning', '请输入用户名和密码');
      return;
    }
    setAuthLoading(true);
    try {
      await http({
        url: '/auth/register',
        method: 'POST',
        data: {
          ...authForm
        }
      });
      toast('success', '注册成功，正在登录...');
      await doLogin();
    } catch (e) {} finally {
      setAuthLoading(false);
    }
  };
  const doLogout = async () => {
    try {
      await http({
        url: '/auth/logout',
        method: 'POST'
      });
    } catch (e) {}
    setAuthed(false);
    setCurrentUser('');
  };
  const loadStatus = async () => {
    try {
      const r = await http({
        url: '/sync/status'
      });
      setStatus(s => ({
        ...s,
        ...r
      }));
    } catch (e) {}
  };
  const loadIpList = async () => {
    try {
      const r = await http({
        url: '/sync/ipList'
      });
      setIpList(Array.isArray(r) ? r : []);
    } catch (e) {}
  };
  const loadDatabases = async ip => {
    setCurrentIp(ip);
    try {
      const r = await http({
        url: '/sync/databases/' + encodeURIComponent(ip)
      });
      setDatabases(Array.isArray(r) ? r : []);
    } catch (e) {}
  };
  const loadList = async () => {
    const body = {
      page,
      pageSize,
      condition: {
        ...query
      }
    };
    try {
      const r = await http({
        url: '/sync/db/list',
        method: 'POST',
        data: body
      });
      setList(r.results || []);
      setTotal(r.total || 0);
    } catch (e) {}
  };
  const loadLogTypes = async () => {
    try {
      const r = await http({
        url: '/operation-log/types'
      });
      setLogTypes(r.types || []);
    } catch (e) {}
  };
  const loadLogs = async p => {
    const pg = p || logsPage;
    const body = {
      page: pg,
      pageSize: logsPageSize,
      ...logFilter
    };
    try {
      const r = await http({
        url: '/operation-log/list',
        method: 'POST',
        data: body
      });
      setLogs(r.results || []);
      setLogsTotal(r.total || 0);
    } catch (e) {}
  };
  const searchLogs = () => {
    setLogsPage(1);
    loadLogs(1);
  };
  const resetLogFilter = () => {
    const d = new Date();
    const p = n => String(n).padStart(2, '0');
    const ts = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} 00:00:00`;
    const ns = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
    setLogFilter({
      username: '',
      operationType: '',
      resultStatus: '',
      clientIp: '',
      startTime: ts,
      endTime: ns
    });
    setLogsPage(1);
    loadLogs(1);
  };
  const onLogsPage = p => {
    setLogsPage(p);
    loadLogs(p);
  };
  const loadAll = useCallback(async () => {
    setLoading(true);
    try {
      await Promise.all([loadStatus(), loadIpList(), loadList(), loadMappings()]);
    } catch (e) {}
    setLoading(false);
  }, [page, pageSize, query]);
  const search = () => {
    setPage(1);
    loadList();
  };
  const resetQuery = () => {
    setQuery({
      ip: '',
      sourceDbName: '',
      state: null
    });
    setPage(1);
    loadList();
  };
  const onPage = p => {
    setPage(p);
    loadList();
  };
  const toggleRow = (row, checked) => {
    setSelectedRows(prev => checked ? [...prev.filter(r => r.id !== row.id), row] : prev.filter(r => r.id !== row.id));
  };
  const toggleAll = checked => {
    setSelectedRows(checked ? [...list] : []);
  };
  const detail = async row => {
    try {
      const r = await http({
        url: '/sync/' + row.id
      });
      setCurrent(r.data);
      setDrawer(true);
    } catch (e) {}
  };
  const resync = async () => {
    if (!selectedRows.length) {
      toast('warning', '请先勾选要重新同步的记录');
      return;
    }
    const items = selectedRows.map(r => ({
      ip: r.sourceIp,
      sourceDbName: r.sourceDbName,
      dbName: r.sourceDbName
    }));
    try {
      const r = await http({
        url: '/sync/resyncDatabases',
        method: 'POST',
        data: items
      });
      const ok = r.data && r.data.success ? r.data.success.length : 0;
      const fail = r.data && r.data.failed ? r.data.failed.length : 0;
      toast('success', '已提交重新同步：成功 ' + ok + '，失败 ' + fail);
      setTimeout(loadAll, 1500);
    } catch (e) {}
  };
  const updatePair = (idx, patch) => setHostPairs(ps => ps.map((p, i) => i === idx ? {
    ...p,
    ...patch
  } : p));
  const updateEntry = (idx, kind, eIdx, patch) => {
    const listName = kind === 'dml' ? 'ignoreTablesByDb' : 'ignoreDdlTablesByDb';
    setHostPairs(ps => ps.map((p, i) => {
      if (i !== idx) return p;
      const list = p[listName].map((e, j) => j === eIdx ? {
        ...e,
        ...patch
      } : e);
      return {
        ...p,
        [listName]: list
      };
    }));
  };
  const updateRule = (idx, ridx, patch) => {
    setHostPairs(ps => ps.map((p, i) => {
      if (i !== idx) return p;
      const rules = p.transformRules.map((r, j) => j === ridx ? {
        ...r,
        ...patch
      } : r);
      return {
        ...p,
        transformRules: rules
      };
    }));
  };
  const addHostPair = () => setHostPairs(ps => [...ps, blankPair()]);
  const removeHostPair = idx => {
    if (hostPairs.length > 1) setHostPairs(ps => ps.filter((_, i) => i !== idx));
  };
  const testConnection = async idx => {
    const pair = hostPairs[idx];
    if (!pair.sourceHost || !pair.sourceUser || !pair.sourcePassword || !pair.targetHost || !pair.targetUser || !pair.targetPassword) {
      toast('warning', '请完整填写源主机与目标主机的连接信息');
      return;
    }
    updatePair(idx, {
      testing: true,
      testResult: null
    });
    try {
      const r = await http({
        url: '/sync/mapping/test',
        method: 'POST',
        data: {
          ...pair
        }
      });
      updatePair(idx, {
        testResult: r.data
      });
      if (r.data.source.ok && r.data.target.ok) toast('success', '源主机与目标主机连接测试均通过');else toast('error', '连接测试未通过');
    } catch (e) {} finally {
      updatePair(idx, {
        testing: false
      });
    }
  };
  const loadSourceDatabases = async idx => {
    const pair = hostPairs[idx];
    if (!pair.sourceHost || !pair.sourceUser || !pair.sourcePassword) {
      toast('warning', '请先填写源主机的主机、账号、密码');
      return;
    }
    updatePair(idx, {
      loadingDbs: true
    });
    try {
      const r = await http({
        url: '/sync/sourceDatabases',
        method: 'POST',
        data: {
          ...pair
        }
      });
      updatePair(idx, {
        sourceDatabases: r.data || []
      });
      toast('success', '已加载源主机库列表（' + (r.data || []).length + ' 个，已排除系统库）');
    } catch (e) {} finally {
      updatePair(idx, {
        loadingDbs: false
      });
    }
  };
  const loadSourceTables = async (idx, kind, eIdx) => {
    const pair = hostPairs[idx];
    const listName = kind === 'dml' ? 'ignoreTablesByDb' : 'ignoreDdlTablesByDb';
    const entry = pair[listName][eIdx];
    if (!isConcreteDb(entry.database)) {
      toast('warning', '库名为正则/通配时无法动态拉取表，请手动填写表规则');
      return;
    }
    if (!pair.sourceHost || !pair.sourceUser || !pair.sourcePassword) {
      toast('warning', '请先填写源主机连接信息');
      return;
    }
    updatePair(idx, {
      loadingTablesDb: entry.database
    });
    try {
      const r = await http({
        url: '/sync/sourceTables',
        method: 'POST',
        data: {
          sourceHost: pair.sourceHost,
          sourcePort: pair.sourcePort,
          sourceUser: pair.sourceUser,
          sourcePassword: pair.sourcePassword,
          database: entry.database
        }
      });
      const tableCache = {
        ...pair.tableCache,
        [entry.database]: r.data || []
      };
      updatePair(idx, {
        tableCache
      });
      toast('success', '已加载库 ' + entry.database + ' 的表清单（' + (r.data || []).length + ' 张）');
    } catch (e) {} finally {
      updatePair(idx, {
        loadingTablesDb: null
      });
    }
  };
  const addDbTableIgnore = (idx, kind) => {
    const listName = kind === 'dml' ? 'ignoreTablesByDb' : 'ignoreDdlTablesByDb';
    setHostPairs(ps => ps.map((p, i) => i === idx ? {
      ...p,
      [listName]: [...p[listName], {
        database: '',
        tables: []
      }]
    } : p));
  };
  const removeDbTableIgnore = (idx, kind, eIdx) => {
    const listName = kind === 'dml' ? 'ignoreTablesByDb' : 'ignoreDdlTablesByDb';
    setHostPairs(ps => ps.map((p, i) => {
      if (i !== idx) return p;
      const list = p[listName].filter((_, j) => j !== eIdx);
      return {
        ...p,
        [listName]: list
      };
    }));
  };
  const onAddRule = idx => setHostPairs(ps => ps.map((p, i) => i === idx ? {
    ...p,
    transformRules: [...p.transformRules, {
      dbName: '*',
      tableName: '*',
      fieldName: '',
      sourceValue: '',
      targetValue: ''
    }]
  } : p));
  const onRemoveRule = (idx, ridx) => setHostPairs(ps => ps.map((p, i) => i === idx ? {
    ...p,
    transformRules: p.transformRules.filter((_, j) => j !== ridx)
  } : p));
  const reportAddResult = res => {
    const createdN = (res.created || []).length;
    const skippedN = (res.skipped || []).length;
    const failedN = (res.failed || []).length;
    if (createdN > 0) toast('success', '已开始同步主机对 ' + createdN + ' 个' + (skippedN ? '，跳过 ' + skippedN + ' 个已存在' : '') + (failedN ? '，失败 ' + failedN : ''));else if (skippedN > 0 && failedN === 0) toast('warning', '该主机对已存在同步映射（已跳过）');else toast('error', '保存失败：' + (res.failed || []).join('; '));
  };
  const savePair = async idx => {
    const pair = hostPairs[idx];
    if (!pair.sourceHost || !pair.sourceUser || !pair.sourcePassword || !pair.targetHost || !pair.targetUser || !pair.targetPassword) {
      toast('warning', '请完整填写源主机与目标主机的连接信息');
      return;
    }
    updatePair(idx, {
      adding: true
    });
    try {
      const r = await http({
        url: '/sync/mapping/add',
        method: 'POST',
        data: buildPairConfig(pair)
      });
      reportAddResult(r.data || {});
      await loadMappings();
      await loadAll();
    } catch (e) {} finally {
      updatePair(idx, {
        adding: false
      });
    }
  };
  const parseJson = () => {
    try {
      const obj = JSON.parse(configJson);
      setJsonError('');
      return obj;
    } catch (e) {
      setJsonError(e.message);
      toast('error', 'JSON 解析失败：' + e.message);
      return null;
    }
  };
  const formatJson = () => {
    const obj = parseJson();
    if (obj) {
      setConfigJson(JSON.stringify(obj, null, 2));
      toast('success', 'JSON 格式正确');
    }
  };
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
      p.ignoreTablesByDb = (o.ignoreTablesByDb || []).map(e => ({
        database: e.database || '',
        tables: e.tables || []
      }));
      p.ignoreDdlTablesByDb = (o.ignoreDdlTablesByDb || []).map(e => ({
        database: e.database || '',
        tables: e.tables || []
      }));
      p.commonIgnoreTables = o.commonIgnoreTables || [];
      p.commonDdlIgnoreTables = o.commonDdlIgnoreTables || [];
      if (Array.isArray(o.transformRules) && o.transformRules.length) {
        p.transformRules = o.transformRules.map(r => ({
          dbName: r.dbName || '*',
          tableName: r.tableName || '*',
          fieldName: r.fieldName || '',
          sourceValue: r.sourceValue || '',
          targetValue: r.targetValue || ''
        }));
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
      if (!o.sourceHost || !o.sourceUser || !o.sourcePassword || !o.targetHost || !o.targetUser || !o.targetPassword) {
        toast('warning', 'JSON 中源主机与目标主机的连接信息必须完整');
        return;
      }
    }
    setAdding(true);
    try {
      let anyCreated = false,
        anyFail = false;
      for (const o of arr) {
        const r = await http({
          url: '/sync/mapping/add',
          method: 'POST',
          data: o
        });
        const res = r.data || {};
        if ((res.created || []).length > 0) anyCreated = true;
        if ((res.failed || []).length > 0) anyFail = true;
      }
      if (anyCreated) toast('success', '已开始同步主机对（JSON）');else if (anyFail) toast('error', '部分主机对保存失败');else toast('warning', '所有主机对已存在（已跳过）');
      await loadMappings();
      await loadAll();
    } catch (e) {} finally {
      setAdding(false);
    }
  };
  const onConfigModeChange = mode => {
    if (mode === 'json' && !configJson.trim()) formToJson();
  };
  const loadMappings = async () => {
    try {
      const r = await http({
        url: '/sync/mappings'
      });
      setMappingsList(r.data || []);
    } catch (e) {}
  };
  const removeMapping = async row => {
    try {
      await http({
        url: '/sync/mapping/remove',
        method: 'POST',
        data: {
          instanceKey: row.instanceKey
        }
      });
      toast('success', '已移除主机对：' + row.instanceKey);
      await loadMappings();
      await loadAll();
    } catch (e) {}
  };
  useEffect(() => {
    checkAuth();
  }, [checkAuth]);
  useEffect(() => {
    if (timerRef.current) clearInterval(timerRef.current);
    if (authed && activeTab === 'stats') {
      timerRef.current = setInterval(() => {
        if (authed && activeTab === 'stats') loadAll();
      }, 5000);
    }
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, [authed, activeTab, loadAll]);
  if (!authed) {
    return React.createElement("div", {
      className: "login-wrap"
    }, React.createElement("div", {
      className: "login-card"
    }, React.createElement("h2", null, "DRPlatform \u7BA1\u7406\u540E\u53F0"), React.createElement("div", {
      className: "tip"
    }, "\u8BF7\u767B\u5F55\u540E\u4F7F\u7528\u540C\u6B65\u914D\u7F6E\u4E0E\u76D1\u63A7\u529F\u80FD"), authMode === 'login' ? React.createElement("div", null, React.createElement("div", {
      className: "rx-field"
    }, React.createElement("label", {
      className: "rx-label"
    }, "\u7528\u6237\u540D"), React.createElement("input", {
      className: "rx-input",
      style: {
        border: '1px solid rgba(255,255,255,0.16)',
        borderRadius: 4,
        padding: '8px 10px',
        width: '100%',
        boxSizing: 'border-box'
      },
      value: authForm.username,
      onChange: e => setAuthForm(f => ({
        ...f,
        username: e.target.value
      })),
      placeholder: "\u7528\u6237\u540D",
      onKeyDown: e => e.key === 'Enter' && doLogin()
    })), React.createElement("div", {
      className: "rx-field"
    }, React.createElement("label", {
      className: "rx-label"
    }, "\u5BC6\u7801"), React.createElement("input", {
      className: "rx-input",
      type: "password",
      style: {
        border: '1px solid rgba(255,255,255,0.16)',
        borderRadius: 4,
        padding: '8px 10px',
        width: '100%',
        boxSizing: 'border-box'
      },
      value: authForm.password,
      onChange: e => setAuthForm(f => ({
        ...f,
        password: e.target.value
      })),
      placeholder: "\u5BC6\u7801",
      onKeyDown: e => e.key === 'Enter' && doLogin()
    })), React.createElement("button", {
      className: "rx-btn rx-btn-primary login-submit",
      style: {
        width: '100%'
      },
      disabled: authLoading,
      onClick: doLogin
    }, authLoading ? '登录中...' : '登 录'), React.createElement("div", {
      style: {
        marginTop: 12,
        textAlign: 'right'
      }
    }, React.createElement("span", {
      className: "rx-link",
      onClick: () => setAuthMode('register')
    }, "\u6CA1\u6709\u8D26\u53F7\uFF1F\u6CE8\u518C"))) : React.createElement("div", null, React.createElement("div", {
      className: "rx-field"
    }, React.createElement("label", {
      className: "rx-label"
    }, "\u7528\u6237\u540D"), React.createElement("input", {
      className: "rx-input",
      style: {
        border: '1px solid rgba(255,255,255,0.16)',
        borderRadius: 4,
        padding: '8px 10px',
        width: '100%',
        boxSizing: 'border-box'
      },
      value: authForm.username,
      onChange: e => setAuthForm(f => ({
        ...f,
        username: e.target.value
      })),
      placeholder: "3-32 \u4F4D"
    })), React.createElement("div", {
      className: "rx-field"
    }, React.createElement("label", {
      className: "rx-label"
    }, "\u5BC6\u7801"), React.createElement("input", {
      className: "rx-input",
      type: "password",
      style: {
        border: '1px solid rgba(255,255,255,0.16)',
        borderRadius: 4,
        padding: '8px 10px',
        width: '100%',
        boxSizing: 'border-box'
      },
      value: authForm.password,
      onChange: e => setAuthForm(f => ({
        ...f,
        password: e.target.value
      })),
      placeholder: "\u81F3\u5C11 6 \u4F4D"
    })), React.createElement("button", {
      className: "rx-btn rx-btn-primary login-submit",
      style: {
        width: '100%'
      },
      disabled: authLoading,
      onClick: doRegister
    }, authLoading ? '注册中...' : '注 册'), React.createElement("div", {
      style: {
        marginTop: 12,
        textAlign: 'right'
      }
    }, React.createElement("span", {
      className: "rx-link",
      onClick: () => setAuthMode('login')
    }, "\u5DF2\u6709\u8D26\u53F7\uFF1F\u767B\u5F55")))), React.createElement("div", {
      className: "login-viz",
      "aria-hidden": "true"
    }, React.createElement("div", {
      className: "viz-header"
    }, React.createElement("span", {
      className: "viz-dot"
    }), React.createElement("span", {
      className: "viz-title"
    }, "REAL-TIME SYNC TOPOLOGY")), React.createElement("svg", {
      className: "sync-topo",
      viewBox: "0 0 560 440",
      preserveAspectRatio: "xMidYMid meet"
    }, React.createElement("defs", null, React.createElement("filter", {
      id: "glow",
      x: "-50%",
      y: "-50%",
      width: "200%",
      height: "200%"
    }, React.createElement("feGaussianBlur", {
      stdDeviation: "2.5",
      result: "blur"
    }), React.createElement("feMerge", null, React.createElement("feMergeNode", {
      in: "blur"
    }), React.createElement("feMergeNode", {
      in: "SourceGraphic"
    })))), React.createElement("path", {
      id: "lk1",
      d: "M92,80 L468,80",
      className: "link"
    }), React.createElement("path", {
      id: "lk2",
      d: "M92,220 L468,220",
      className: "link"
    }), React.createElement("path", {
      id: "lk3",
      d: "M92,360 L468,360",
      className: "link"
    }), React.createElement("circle", {
      r: "3.5",
      className: "flow-dot",
      filter: "url(#glow)"
    }, React.createElement("animateMotion", {
      dur: "2.4s",
      repeatCount: "indefinite",
      begin: "0s"
    }, React.createElement("mpath", {
      href: "#lk1"
    }))), React.createElement("circle", {
      r: "3.5",
      className: "flow-dot",
      filter: "url(#glow)"
    }, React.createElement("animateMotion", {
      dur: "2.4s",
      repeatCount: "indefinite",
      begin: "1.2s"
    }, React.createElement("mpath", {
      href: "#lk1"
    }))), React.createElement("circle", {
      r: "3.5",
      className: "flow-dot",
      filter: "url(#glow)"
    }, React.createElement("animateMotion", {
      dur: "2.8s",
      repeatCount: "indefinite",
      begin: "0.3s"
    }, React.createElement("mpath", {
      href: "#lk2"
    }))), React.createElement("circle", {
      r: "3.5",
      className: "flow-dot",
      filter: "url(#glow)"
    }, React.createElement("animateMotion", {
      dur: "2.8s",
      repeatCount: "indefinite",
      begin: "1.7s"
    }, React.createElement("mpath", {
      href: "#lk2"
    }))), React.createElement("circle", {
      r: "3.5",
      className: "flow-dot",
      filter: "url(#glow)"
    }, React.createElement("animateMotion", {
      dur: "2.6s",
      repeatCount: "indefinite",
      begin: "0.6s"
    }, React.createElement("mpath", {
      href: "#lk3"
    }))), React.createElement("circle", {
      r: "3.5",
      className: "flow-dot",
      filter: "url(#glow)"
    }, React.createElement("animateMotion", {
      dur: "2.6s",
      repeatCount: "indefinite",
      begin: "1.9s"
    }, React.createElement("mpath", {
      href: "#lk3"
    }))), React.createElement("g", {
      className: "node node-src",
      transform: "translate(92,80)"
    }, React.createElement("rect", {
      x: "-52",
      y: "-18",
      width: "104",
      height: "36",
      rx: "5",
      className: "node-box"
    }), React.createElement("circle", {
      cx: "-38",
      cy: "0",
      r: "3",
      className: "node-led"
    }), React.createElement("text", {
      x: "-28",
      y: "-2",
      className: "node-ip"
    }, "10.0.1.10"), React.createElement("text", {
      x: "-28",
      y: "11",
      className: "node-db"
    }, "db_auth")), React.createElement("g", {
      className: "node node-dst",
      transform: "translate(468,80)"
    }, React.createElement("rect", {
      x: "-52",
      y: "-18",
      width: "104",
      height: "36",
      rx: "5",
      className: "node-box"
    }), React.createElement("circle", {
      cx: "-38",
      cy: "0",
      r: "3",
      className: "node-led node-led-dst"
    }), React.createElement("text", {
      x: "-28",
      y: "-2",
      className: "node-ip"
    }, "10.8.8.1"), React.createElement("text", {
      x: "-28",
      y: "11",
      className: "node-db"
    }, "db_auth")), React.createElement("g", {
      className: "node node-src",
      transform: "translate(92,220)"
    }, React.createElement("rect", {
      x: "-52",
      y: "-18",
      width: "104",
      height: "36",
      rx: "5",
      className: "node-box"
    }), React.createElement("circle", {
      cx: "-38",
      cy: "0",
      r: "3",
      className: "node-led"
    }), React.createElement("text", {
      x: "-28",
      y: "-2",
      className: "node-ip"
    }, "10.0.1.20"), React.createElement("text", {
      x: "-28",
      y: "11",
      className: "node-db"
    }, "db_log")), React.createElement("g", {
      className: "node node-dst",
      transform: "translate(468,220)"
    }, React.createElement("rect", {
      x: "-52",
      y: "-18",
      width: "104",
      height: "36",
      rx: "5",
      className: "node-box"
    }), React.createElement("circle", {
      cx: "-38",
      cy: "0",
      r: "3",
      className: "node-led node-led-dst"
    }), React.createElement("text", {
      x: "-28",
      y: "-2",
      className: "node-ip"
    }, "10.8.8.2"), React.createElement("text", {
      x: "-28",
      y: "11",
      className: "node-db"
    }, "db_log")), React.createElement("g", {
      className: "node node-src",
      transform: "translate(92,360)"
    }, React.createElement("rect", {
      x: "-52",
      y: "-18",
      width: "104",
      height: "36",
      rx: "5",
      className: "node-box"
    }), React.createElement("circle", {
      cx: "-38",
      cy: "0",
      r: "3",
      className: "node-led"
    }), React.createElement("text", {
      x: "-28",
      y: "-2",
      className: "node-ip"
    }, "10.0.1.30"), React.createElement("text", {
      x: "-28",
      y: "11",
      className: "node-db"
    }, "db_pay")), React.createElement("g", {
      className: "node node-dst",
      transform: "translate(468,360)"
    }, React.createElement("rect", {
      x: "-52",
      y: "-18",
      width: "104",
      height: "36",
      rx: "5",
      className: "node-box"
    }), React.createElement("circle", {
      cx: "-38",
      cy: "0",
      r: "3",
      className: "node-led node-led-dst"
    }), React.createElement("text", {
      x: "-28",
      y: "-2",
      className: "node-ip"
    }, "10.8.8.3"), React.createElement("text", {
      x: "-28",
      y: "11",
      className: "node-db"
    }, "db_pay")), React.createElement("text", {
      x: "92",
      y: "30",
      className: "col-label"
    }, "SOURCE \xB7 ACTIVE"), React.createElement("text", {
      x: "468",
      y: "30",
      className: "col-label"
    }, "TARGET \xB7 STANDBY")), React.createElement("div", {
      className: "viz-footer"
    }, React.createElement("span", {
      className: "vf-item"
    }, React.createElement("span", {
      className: "vf-led vf-led-link"
    }), "3 sync channels"), React.createElement("span", {
      className: "vf-item"
    }, "1:1 replication"))), React.createElement(ToastHost, null));
  }
  const selectedIds = new Set(selectedRows.map(r => r.id));
  const allChecked = list.length > 0 && list.every(r => selectedIds.has(r.id));
  return React.createElement("div", null, React.createElement("div", {
    className: "topbar"
  }, React.createElement("div", null, React.createElement("div", {
    className: "title"
  }, "DRPlatform \xB7 \u5730\u7406\u6570\u636E\u5E93\u707E\u5907\u540C\u6B65\u670D\u52A1"), React.createElement("div", {
    className: "sub"
  }, "Flink CDC \u65B9\u5411 \xB7 \u5185\u5D4C\u540C\u6B65\u5F15\u64CE\uFF08\u672C\u5730\u53EF\u8FD0\u884C\u7248\uFF09 \xB7 \u5B9E\u65F6\u707E\u5907\u4E00\u81F4\u6027\u4FDD\u969C")), React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      gap: 10
    }
  }, React.createElement("input", {
    className: "rx-input",
    style: {
      width: 240,
      border: '1px solid rgba(255,255,255,.4)',
      borderRadius: 4,
      padding: '5px 8px',
      background: 'rgba(255,255,255,.1)',
      color: '#fff'
    },
    value: baseUrl,
    onChange: e => setBaseUrl(e.target.value),
    placeholder: "\u540E\u7AEF\u5730\u5740"
  }), React.createElement("button", {
    className: "rx-btn",
    onClick: loadAll
  }, "\u5237\u65B0"), React.createElement("span", {
    className: "desc-cell",
    style: {
      opacity: .85
    }
  }, "Hi, ", currentUser), React.createElement("button", {
    className: "rx-btn",
    onClick: doLogout
  }, "\u9000\u51FA"))), React.createElement("div", {
    className: "wrap"
  }, React.createElement("div", {
    className: "rx-tabs"
  }, React.createElement("div", {
    className: 'rx-tab' + (activeTab === 'stats' ? ' rx-tab-active' : ''),
    onClick: () => setActiveTab('stats')
  }, "\u7EDF\u8BA1\u76D1\u63A7"), React.createElement("div", {
    className: 'rx-tab' + (activeTab === 'config' ? ' rx-tab-active' : ''),
    onClick: () => setActiveTab('config')
  }, "\u914D\u7F6E\u4E2D\u5FC3"), React.createElement("div", {
    className: 'rx-tab' + (activeTab === 'logs' ? ' rx-tab-active' : ''),
    onClick: () => {
      setActiveTab('logs');
      loadLogTypes();
      loadLogs(1);
    }
  }, "\u64CD\u4F5C\u65E5\u5FD7")), activeTab === 'stats' && React.createElement("div", null, React.createElement("div", {
    className: "card"
  }, React.createElement("h3", null, "\u540C\u6B65\u603B\u89C8"), React.createElement("div", {
    className: "stat-row"
  }, React.createElement("div", {
    className: "stat"
  }, React.createElement("div", {
    className: "k"
  }, "\u6574\u4F53\u72B6\u6001"), React.createElement("div", {
    className: 'v ' + (status.status === 'NORMAL' || status.status === 1 ? 'ok' : 'bad')
  }, status.desc || '加载中...')), React.createElement("div", {
    className: "stat"
  }, React.createElement("div", {
    className: "k"
  }, "\u4E3B\u673A\u5BF9 / \u8FD0\u884C\u4F5C\u4E1A"), React.createElement("div", {
    className: "v info"
  }, mappingsList.length, " / ", mappingsList.filter(m => m.running).length)), React.createElement("div", {
    className: "stat"
  }, React.createElement("div", {
    className: "k"
  }, "\u6700\u8FD1\u5F02\u5E38\u65F6\u95F4"), React.createElement("div", {
    className: "v warn"
  }, status.firstExceptionTime || '—')), React.createElement("div", {
    className: "stat"
  }, React.createElement("div", {
    className: "k"
  }, "\u5143\u6570\u636E\u5E93"), React.createElement("div", {
    className: "v info"
  }, "DRPlatform")))), React.createElement("div", {
    className: "card"
  }, React.createElement("h3", null, "\u8282\u70B9\u4E0E\u6570\u636E\u5E93"), React.createElement("div", {
    className: "rx-tablescroll"
  }, React.createElement("table", {
    className: "rx-table"
  }, React.createElement("thead", null, React.createElement("tr", null, React.createElement("th", {
    style: {
      width: 200
    }
  }, "IP"), React.createElement("th", {
    style: {
      width: 140
    }
  }, "\u89D2\u8272"), React.createElement("th", null, "\u64CD\u4F5C"))), React.createElement("tbody", null, ipList.map((row, i) => React.createElement("tr", {
    key: i
  }, React.createElement("td", null, row.ip), React.createElement("td", null, React.createElement("span", {
    className: 'rx-tag-static ' + (row.type === '生产中心' ? 'rx-danger' : 'rx-success')
  }, row.type)), React.createElement("td", null, React.createElement("button", {
    className: "rx-btn rx-btn-primary",
    onClick: () => loadDatabases(row.ip)
  }, "\u67E5\u770B\u5176\u4E0B\u6570\u636E\u5E93"))))))), databases.length > 0 && React.createElement("div", {
    style: {
      marginTop: 12
    }
  }, React.createElement("div", {
    className: "desc-cell",
    style: {
      marginBottom: 8
    }
  }, "IP ", React.createElement("code", {
    className: "k"
  }, currentIp), " \u4E0B\u7528\u6237\u6570\u636E\u5E93\uFF08", databases.length, "\uFF09\uFF1A"), databases.map(db => React.createElement("span", {
    key: db,
    className: "rx-tag-static rx-info",
    style: {
      margin: '0 6px 6px 0'
    }
  }, db)))), React.createElement("div", {
    className: "card"
  }, React.createElement("h3", null, "\u540C\u6B65\u8FDB\u5EA6\u5217\u8868"), React.createElement("div", {
    className: "toolbar stats-filter"
  }, React.createElement("input", {
    className: "rx-control",
    style: {
      width: 150
    },
    value: query.ip,
    onChange: e => setQuery(q => ({
      ...q,
      ip: e.target.value
    })),
    placeholder: "\u6E90IP\u7B5B\u9009"
  }), React.createElement("input", {
    className: "rx-control",
    style: {
      width: 160
    },
    value: query.sourceDbName,
    onChange: e => setQuery(q => ({
      ...q,
      sourceDbName: e.target.value
    })),
    placeholder: "\u6E90\u5E93\u540D(\u6A21\u7CCA)"
  }), React.createElement(Select, {
    style: {
      width: 130
    },
    value: query.state == null ? '' : String(query.state),
    options: [{
      value: '0',
      label: '失效'
    }, {
      value: '1',
      label: '全量同步'
    }, {
      value: '2',
      label: '同步中'
    }, {
      value: '3',
      label: '中止'
    }],
    filterable: false,
    placeholder: "\u72B6\u6001",
    onChange: v => setQuery(q => ({
      ...q,
      state: v === '' ? null : Number(v)
    }))
  }), React.createElement("button", {
    className: "rx-btn rx-btn-primary",
    onClick: search
  }, "\u67E5\u8BE2"), React.createElement("button", {
    className: "rx-btn",
    onClick: resetQuery
  }, "\u91CD\u7F6E"), React.createElement("button", {
    className: "rx-btn rx-btn-success",
    disabled: !selectedRows.length,
    onClick: resync
  }, "\u91CD\u65B0\u540C\u6B65\u9009\u4E2D(", selectedRows.length, ")")), React.createElement("div", {
    className: "rx-tablescroll"
  }, React.createElement("table", {
    className: "rx-table"
  }, React.createElement("thead", null, React.createElement("tr", null, React.createElement("th", {
    style: {
      width: 46
    }
  }, React.createElement("input", {
    type: "checkbox",
    checked: allChecked,
    onChange: e => toggleAll(e.target.checked)
  })), React.createElement("th", {
    style: {
      width: 70
    }
  }, "ID"), React.createElement("th", {
    style: {
      width: 200
    }
  }, "\u6E90(\u751F\u4EA7\u4E2D\u5FC3)"), React.createElement("th", {
    style: {
      width: 200
    }
  }, "\u76EE\u6807(\u707E\u5907\u4E2D\u5FC3)"), React.createElement("th", {
    style: {
      width: 100
    }
  }, "\u72B6\u6001"), React.createElement("th", {
    style: {
      width: 100
    }
  }, "\u504F\u5DEE\u72B6\u6001"), React.createElement("th", null, "\u5904\u7406/\u7EDF\u8BA1\u4FE1\u606F"), React.createElement("th", {
    style: {
      width: 170
    }
  }, "\u66F4\u65B0\u65F6\u95F4"), React.createElement("th", {
    style: {
      width: 90
    }
  }, "\u64CD\u4F5C"))), React.createElement("tbody", null, list.map(row => React.createElement("tr", {
    key: row.id
  }, React.createElement("td", null, React.createElement("input", {
    type: "checkbox",
    checked: selectedIds.has(row.id),
    onChange: e => toggleRow(row, e.target.checked)
  })), React.createElement("td", null, row.id), React.createElement("td", null, row.sourceIp, " / ", row.sourceDbName), React.createElement("td", null, row.targetIp, " / ", row.targetDbName), React.createElement("td", null, React.createElement("span", {
    className: 'rx-tag-static ' + stateTag(row.state)
  }, stateDesc(row.state))), React.createElement("td", null, row.deviationStatus === 1 ? React.createElement("span", {
    className: "rx-tag-static rx-success"
  }, "\u6B63\u5E38") : row.deviationStatus === 2 ? React.createElement("span", {
    className: "rx-tag-static rx-danger"
  }, "\u5F02\u5E38") : '—'), React.createElement("td", null, row.processingMethod), React.createElement("td", null, row.updateTime), React.createElement("td", null, React.createElement("button", {
    className: "rx-btn rx-btn-primary",
    onClick: () => detail(row)
  }, "\u8BE6\u60C5")))), list.length === 0 && React.createElement("tr", null, React.createElement("td", {
    colSpan: 9,
    style: {
      textAlign: 'center',
      color: 'var(--text-3)'
    }
  }, "\u6682\u65E0\u6570\u636E"))))), React.createElement("div", {
    style: {
      marginTop: 12,
      display: 'flex',
      justifyContent: 'flex-end',
      alignItems: 'center',
      gap: 14
    }
  }, React.createElement("span", {
    className: "desc-cell"
  }, "\u5171 ", total, " \u6761"), React.createElement(Pager, {
    total: total,
    page: page,
    pageSize: pageSize,
    onChange: onPage
  })))), activeTab === 'config' && React.createElement("div", null, React.createElement("div", {
    className: "card"
  }, React.createElement("div", {
    style: {
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'space-between',
      flexWrap: 'wrap',
      gap: 10
    }
  }, React.createElement("div", null, React.createElement("span", {
    className: "collapse-title"
  }, "\u914D\u7F6E\u65B9\u5F0F"), React.createElement("span", {
    className: "desc-cell",
    style: {
      marginLeft: 10
    }
  }, "\u8868\u5355\u9002\u5408\u5FEB\u901F\u5F55\u5165\uFF1BJSON \u9002\u5408\u6279\u91CF / \u590D\u5236\u7C98\u8D34 / \u7CBE\u7EC6\u63A7\u5236\uFF08\u4E24\u8005\u53CC\u5411\u540C\u6B65\uFF09\u3002")), React.createElement("div", {
    className: "rx-radio"
  }, React.createElement("button", {
    className: configMode === 'form' ? 'active' : '',
    onClick: () => {
      setConfigMode('form');
      onConfigModeChange('form');
    }
  }, "\u8868\u5355\u65B9\u5F0F"), React.createElement("button", {
    className: configMode === 'json' ? 'active' : '',
    onClick: () => {
      setConfigMode('json');
      onConfigModeChange('json');
    }
  }, "JSON \u65B9\u5F0F")))), configMode === 'form' && React.createElement("div", null, React.createElement("div", {
    className: "hp-actions"
  }, React.createElement("button", {
    className: "rx-btn rx-btn-primary",
    onClick: addHostPair
  }, "+ \u6DFB\u52A0\u4E3B\u673A\u5BF9"), React.createElement("span", {
    className: "desc-cell"
  }, "\u53EF\u914D\u7F6E", React.createElement("b", null, "\u591A\u5BF9\u4E3B\u673A"), "\uFF08\u5FAE\u670D\u52A1\u5F02\u5730\u591A\u673A\u623F\u5907\u4EFD\uFF09\uFF0C\u6BCF\u5BF9\u72EC\u7ACB\u4FDD\u5B58\u4E0E\u8FD0\u884C\u3002\u8BE5\u6E90\u4E3B\u673A\u4E0B", React.createElement("b", null, "\u6240\u6709\u7528\u6237\u5E93\u4E0E\u8868\u81EA\u52A8\u540C\u6B65"), "\uFF08\u7CFB\u7EDF\u5E93\u4E0E\u5FFD\u7565\u9879\u9664\u5916\uFF09\uFF0C\u65B0\u589E\u5E93/\u8868\u5B9E\u65F6\u7EB3\u5165\u3002")), hostPairs.map((pair, idx) => React.createElement(PairCard, {
    key: idx,
    pair: pair,
    idx: idx,
    canRemove: hostPairs.length > 1,
    onUpdatePair: updatePair,
    onUpdateEntry: updateEntry,
    onUpdateRule: updateRule,
    onTest: testConnection,
    onLoadDbs: loadSourceDatabases,
    onLoadTables: loadSourceTables,
    onAddDbIgnore: addDbTableIgnore,
    onRemoveDbIgnore: removeDbTableIgnore,
    onAddRule: onAddRule,
    onRemoveRule: onRemoveRule,
    onSave: savePair,
    onRemovePair: removeHostPair
  }))), configMode === 'json' && React.createElement("div", {
    className: "card"
  }, React.createElement("h3", null, "JSON \u914D\u7F6E\uFF08\u6574\u4EFD\u4E3B\u673A\u5BF9\u914D\u7F6E\uFF1A\u8FDE\u63A5 + \u5FFD\u7565\u9879 + \u8F6C\u6362\u89C4\u5219\uFF09"), React.createElement("p", {
    className: "desc-cell",
    style: {
      margin: '-6px 0 12px'
    }
  }, "\u53EF\u76F4\u63A5\u7F16\u8F91\u6216\u7C98\u8D34\u4E00\u4EFD JSON \u540E\u4FDD\u5B58\uFF1B", React.createElement("b", null, "\u5E93\u3001\u8868\u5747\u652F\u6301\u6B63\u5219"), "\u3002\u652F\u6301\u5355\u5BF9\u8C61\u6216\u5BF9\u8C61\u6570\u7EC4\uFF08\u591A\u4E3B\u673A\u5BF9\uFF09\u3002 \u5FFD\u7565\u8868\u91C7\u7528", React.createElement("b", null, "\u5C42\u7EA7\u7ED3\u6784"), " ", React.createElement("code", {
    className: "k"
  }, 'ignoreTablesByDb:[{"database":"sales","tables":["t_log","re:^tmp_.*"]}]'), "\uFF0C \u5E76\u63D0\u4F9B ", React.createElement("code", {
    className: "k"
  }, "commonIgnoreTables"), " / ", React.createElement("code", {
    className: "k"
  }, "commonDdlIgnoreTables"), "\uFF08\u6240\u6709\u5E93\u751F\u6548\uFF09\u3002\u4E5F\u53EF\u7528\u4E0B\u65B9\u6309\u94AE\u4E0E\u8868\u5355\u76F8\u4E92\u8F6C\u6362\u3002"), React.createElement("textarea", {
    className: "rx-textarea",
    rows: 20,
    value: configJson,
    onChange: e => setConfigJson(e.target.value),
    placeholder: "{ \"sourceHost\": \"127.0.0.1\", ... }"
  }), React.createElement("div", {
    className: "toolbar",
    style: {
      marginTop: 12
    }
  }, React.createElement("button", {
    className: "rx-btn",
    onClick: formToJson
  }, "\u2191 \u4ECE\u8868\u5355\u751F\u6210 JSON"), React.createElement("button", {
    className: "rx-btn",
    onClick: jsonToForm
  }, "\u2193 \u5E94\u7528 JSON \u5230\u8868\u5355"), React.createElement("button", {
    className: "rx-btn",
    onClick: formatJson
  }, "\u683C\u5F0F\u5316 / \u6821\u9A8C"), React.createElement("button", {
    className: "rx-btn rx-btn-primary",
    disabled: adding,
    onClick: saveAndSyncJson
  }, "\u4FDD\u5B58\u5E76\u5F00\u59CB\u540C\u6B65\uFF08JSON\uFF09"), jsonError && React.createElement("span", {
    className: "rx-err"
  }, "JSON \u9519\u8BEF\uFF1A", jsonError))), React.createElement("div", {
    className: "card"
  }, React.createElement("h3", null, "\u5DF2\u914D\u7F6E\u540C\u6B65\u4E3B\u673A\u5BF9"), React.createElement("div", {
    className: "rx-tablescroll"
  }, React.createElement("table", {
    className: "rx-table"
  }, React.createElement("thead", null, React.createElement("tr", null, React.createElement("th", {
    style: {
      width: 140
    }
  }, "\u6E90\u4E3B\u673A"), React.createElement("th", {
    style: {
      width: 140
    }
  }, "\u76EE\u6807\u4E3B\u673A"), React.createElement("th", {
    style: {
      width: 150
    }
  }, "\u8D26\u53F7"), React.createElement("th", {
    style: {
      width: 130
    }
  }, "\u5FFD\u7565\u5E93"), React.createElement("th", null, "\u6309\u5E93\u5FFD\u7565(DML+DDL)"), React.createElement("th", null, "\u6309\u5E93\u5FFD\u7565(\u4EC5DDL)"), React.createElement("th", null, "\u901A\u7528\u5FFD\u7565(\u6240\u6709\u5E93)"), React.createElement("th", {
    style: {
      width: 90
    }
  }, "\u8F6C\u6362\u89C4\u5219"), React.createElement("th", {
    style: {
      width: 80
    }
  }, "\u6765\u6E90"), React.createElement("th", {
    style: {
      width: 84
    }
  }, "\u72B6\u6001"), React.createElement("th", {
    style: {
      width: 84
    }
  }, "\u64CD\u4F5C"))), React.createElement("tbody", null, mappingsList.map((row, i) => React.createElement("tr", {
    key: i
  }, React.createElement("td", null, row.sourceHost), React.createElement("td", null, row.targetHost), React.createElement("td", null, row.sourceUser, " \u2192 ", row.targetUser), React.createElement("td", null, row.ignoreDatabases && row.ignoreDatabases.length ? row.ignoreDatabases.join(', ') : '—'), React.createElement("td", null, fmtDbIgnore(row.ignoreTablesByDb)), React.createElement("td", null, fmtDbIgnore(row.ignoreDdlTablesByDb)), React.createElement("td", null, row.commonIgnoreTables && row.commonIgnoreTables.length || row.commonDdlIgnoreTables && row.commonDdlIgnoreTables.length ? 'DML:' + ((row.commonIgnoreTables || []).join(',') || '—') + ' | DDL:' + ((row.commonDdlIgnoreTables || []).join(',') || '—') : '—'), React.createElement("td", null, row.transformRules && row.transformRules.length ? row.transformRules.length + ' 条' : '—'), React.createElement("td", null, React.createElement("span", {
    className: 'rx-tag-static ' + (row.source === 'dynamic' ? 'rx-warning' : 'rx-info')
  }, row.source === 'dynamic' ? '页面' : '配置')), React.createElement("td", null, React.createElement("span", {
    className: 'rx-tag-static ' + (row.running ? 'rx-success' : 'rx-info')
  }, row.running ? '运行中' : '已停止')), React.createElement("td", null, React.createElement("button", {
    className: "rx-btn rx-btn-danger",
    disabled: row.source !== 'dynamic',
    onClick: () => removeMapping(row)
  }, "\u79FB\u9664")))), mappingsList.length === 0 && React.createElement("tr", null, React.createElement("td", {
    colSpan: 11,
    style: {
      textAlign: 'center',
      color: 'var(--text-3)'
    }
  }, "\u6682\u65E0\u4E3B\u673A\u5BF9\u914D\u7F6E"))))), React.createElement("div", {
    className: "desc-cell"
  }, "\u63D0\u793A\uFF1A\u4EC5\u9875\u9762\u52A8\u6001\u6DFB\u52A0\u7684\u4E3B\u673A\u5BF9\u53EF\u79FB\u9664\uFF1B\u914D\u7F6E\u6587\u4EF6\uFF08application.yml\uFF09\u4E2D\u7684\u4E3B\u673A\u5BF9\u9700\u624B\u52A8\u6539\u914D\u7F6E\u3002"))), activeTab === 'logs' && React.createElement("div", {
    className: "card"
  }, React.createElement("h3", null, "\u64CD\u4F5C\u5BA1\u8BA1\u65E5\u5FD7"), React.createElement("div", {
    className: "filter-bar log-filter",
    style: {
      display: 'flex',
      gap: 12,
      flexWrap: 'wrap',
      alignItems: 'center',
      marginBottom: 16
    }
  }, React.createElement("div", {
    className: "rx-field",
    style: {
      minWidth: 120
    }
  }, React.createElement("label", {
    className: "rx-label"
  }, "\u7528\u6237\u540D"), React.createElement("input", {
    className: "rx-control",
    value: logFilter.username,
    placeholder: "\u7CBE\u786E\u5339\u914D",
    onChange: e => setLogFilter(f => ({
      ...f,
      username: e.target.value
    }))
  })), React.createElement("div", {
    className: "rx-field",
    style: {
      minWidth: 120
    }
  }, React.createElement("label", {
    className: "rx-label"
  }, "\u5BA2\u6237\u7AEF IP"), React.createElement("input", {
    className: "rx-control",
    value: logFilter.clientIp,
    placeholder: "\u6A21\u7CCA\u5339\u914D",
    onChange: e => setLogFilter(f => ({
      ...f,
      clientIp: e.target.value
    }))
  })), React.createElement("div", {
    className: "rx-field",
    style: {
      minWidth: 140
    }
  }, React.createElement("label", {
    className: "rx-label"
  }, "\u64CD\u4F5C\u7C7B\u578B"), React.createElement(Select, {
    value: logFilter.operationType,
    options: logTypes,
    filterable: false,
    placeholder: "\u5168\u90E8",
    onChange: v => setLogFilter(f => ({
      ...f,
      operationType: v
    }))
  })), React.createElement("div", {
    className: "rx-field",
    style: {
      minWidth: 100
    }
  }, React.createElement("label", {
    className: "rx-label"
  }, "\u7ED3\u679C"), React.createElement(Select, {
    value: logFilter.resultStatus,
    options: ['SUCCESS', 'FAILURE'],
    filterable: false,
    placeholder: "\u5168\u90E8",
    onChange: v => setLogFilter(f => ({
      ...f,
      resultStatus: v
    }))
  })), React.createElement("div", {
    className: "rx-field",
    style: {
      minWidth: 200
    }
  }, React.createElement("label", {
    className: "rx-label"
  }, "\u5F00\u59CB\u65F6\u95F4"), React.createElement(DateTimePicker, {
    value: logFilter.startTime,
    placeholder: "\u8D77\u59CB\u65F6\u95F4",
    onChange: v => setLogFilter(f => ({
      ...f,
      startTime: v
    }))
  })), React.createElement("div", {
    className: "rx-field",
    style: {
      minWidth: 200
    }
  }, React.createElement("label", {
    className: "rx-label"
  }, "\u7ED3\u675F\u65F6\u95F4"), React.createElement(DateTimePicker, {
    value: logFilter.endTime,
    placeholder: "\u7ED3\u675F\u65F6\u95F4",
    onChange: v => setLogFilter(f => ({
      ...f,
      endTime: v
    }))
  })), React.createElement("div", {
    style: {
      display: 'flex',
      gap: 8
    }
  }, React.createElement("button", {
    className: "rx-btn rx-btn-primary",
    onClick: searchLogs
  }, "\u67E5\u8BE2"), React.createElement("button", {
    className: "rx-btn",
    onClick: resetLogFilter
  }, "\u91CD\u7F6E"))), React.createElement("div", {
    className: "rx-tablescroll"
  }, React.createElement("table", {
    className: "rx-table"
  }, React.createElement("thead", null, React.createElement("tr", null, React.createElement("th", {
    style: {
      width: 60
    }
  }, "ID"), React.createElement("th", {
    style: {
      width: 100
    }
  }, "\u7528\u6237\u540D"), React.createElement("th", {
    style: {
      width: 120
    }
  }, "\u5BA2\u6237\u7AEF IP"), React.createElement("th", {
    style: {
      width: 140
    }
  }, "\u64CD\u4F5C\u7C7B\u578B"), React.createElement("th", null, "\u64CD\u4F5C\u63CF\u8FF0"), React.createElement("th", {
    style: {
      width: 80
    }
  }, "\u7ED3\u679C"), React.createElement("th", {
    style: {
      width: 80
    }
  }, "\u8017\u65F6(ms)"), React.createElement("th", {
    style: {
      width: 160
    }
  }, "\u64CD\u4F5C\u65F6\u95F4"), React.createElement("th", {
    style: {
      width: 60
    }
  }, "\u8BE6\u60C5"))), React.createElement("tbody", null, logs.length === 0 && React.createElement("tr", null, React.createElement("td", {
    colSpan: 9,
    style: {
      textAlign: 'center',
      padding: 24,
      color: 'var(--text-3)'
    }
  }, "\u6682\u65E0\u65E5\u5FD7\u8BB0\u5F55")), logs.map(row => React.createElement("tr", {
    key: row.id
  }, React.createElement("td", {
    style: {
      fontFamily: 'monospace'
    }
  }, row.id), React.createElement("td", null, row.username || '—'), React.createElement("td", {
    style: {
      fontFamily: 'monospace'
    }
  }, row.clientIp || '—'), React.createElement("td", null, React.createElement("span", {
    style: {
      fontFamily: 'monospace',
      fontSize: 12,
      background: 'var(--bg-2)',
      padding: '2px 6px',
      borderRadius: 3
    }
  }, row.operationType)), React.createElement("td", {
    style: {
      maxWidth: 300,
      overflow: 'hidden',
      textOverflow: 'ellipsis',
      whiteSpace: 'nowrap'
    },
    title: row.operationDesc
  }, row.operationDesc || '—'), React.createElement("td", null, row.resultStatus === 'SUCCESS' ? React.createElement("span", {
    style: {
      color: 'var(--accent)'
    }
  }, "\u6210\u529F") : React.createElement("span", {
    style: {
      color: 'var(--danger)'
    }
  }, "\u5931\u8D25")), React.createElement("td", {
    style: {
      fontFamily: 'monospace',
      fontVariantNumeric: 'tabular-nums'
    }
  }, row.durationMs == null ? '—' : row.durationMs), React.createElement("td", {
    style: {
      fontFamily: 'monospace',
      fontSize: 12
    }
  }, row.createTime), React.createElement("td", null, React.createElement("span", {
    className: "rx-link",
    onClick: () => setLogDetail(row)
  }, "\u67E5\u770B"))))))), React.createElement("div", {
    style: {
      display: 'flex',
      justifyContent: 'space-between',
      alignItems: 'center',
      marginTop: 12
    }
  }, React.createElement("span", {
    style: {
      color: 'var(--text-2)',
      fontSize: 13
    }
  }, "\u5171 ", logsTotal, " \u6761"), React.createElement("div", {
    style: {
      display: 'flex',
      gap: 8,
      alignItems: 'center'
    }
  }, React.createElement("button", {
    className: "rx-btn",
    disabled: logsPage <= 1,
    onClick: () => onLogsPage(logsPage - 1)
  }, "\u4E0A\u4E00\u9875"), React.createElement("span", {
    style: {
      fontFamily: 'monospace',
      fontSize: 13
    }
  }, logsPage, " / ", Math.max(1, Math.ceil(logsTotal / logsPageSize))), React.createElement("button", {
    className: "rx-btn",
    disabled: logsPage >= Math.ceil(logsTotal / logsPageSize),
    onClick: () => onLogsPage(logsPage + 1)
  }, "\u4E0B\u4E00\u9875")))), React.createElement("div", {
    className: "footer"
  }, "DRPlatform \xB7 \u672C\u5730\u5185\u5D4C\u540C\u6B65\u5F15\u64CE\u6F14\u793A \xB7 \u4E3B\u673A\u5BF9\u81EA\u52A8\u540C\u6B65 + \u5FFD\u7565\u5F0F\u914D\u7F6E + \u5B9E\u65F6\u65B0\u5E93\u65B0\u8868\u76D1\u6D4B")), React.createElement(Drawer, {
    open: drawer,
    title: "\u540C\u6B65\u8BE6\u60C5",
    onClose: () => setDrawer(false)
  }, current && React.createElement("div", {
    className: "rx-tablescroll"
  }, React.createElement("table", {
    className: "rx-dlist"
  }, React.createElement("tbody", null, React.createElement("tr", null, React.createElement("th", null, "ID"), React.createElement("td", null, current.id)), React.createElement("tr", null, React.createElement("th", null, "\u6E90IP"), React.createElement("td", null, current.sourceIp)), React.createElement("tr", null, React.createElement("th", null, "\u6E90\u5E93"), React.createElement("td", null, current.sourceDbName)), React.createElement("tr", null, React.createElement("th", null, "\u76EE\u6807IP"), React.createElement("td", null, current.targetIp)), React.createElement("tr", null, React.createElement("th", null, "\u76EE\u6807\u5E93"), React.createElement("td", null, current.targetDbName)), React.createElement("tr", null, React.createElement("th", null, "\u72B6\u6001"), React.createElement("td", null, stateDesc(current.state))), React.createElement("tr", null, React.createElement("th", null, "\u504F\u5DEE\u72B6\u6001"), React.createElement("td", null, current.deviationStatus === 1 ? '正常' : current.deviationStatus === 2 ? '异常' : '—')), React.createElement("tr", null, React.createElement("th", null, "\u504F\u5DEE\u6B21\u6570"), React.createElement("td", null, current.deviationTimes)), React.createElement("tr", null, React.createElement("th", null, "\u6E90Binlog"), React.createElement("td", null, current.sourceBinlogFile, " @ ", current.sourceBinlogTime)), React.createElement("tr", null, React.createElement("th", null, "\u5DF2\u540C\u6B65Binlog"), React.createElement("td", null, current.syncBinlogFile, " @ ", current.syncBinlogTime)), React.createElement("tr", null, React.createElement("th", null, "\u4E2D\u6B62\u539F\u56E0"), React.createElement("td", null, current.suspensionReason || '—')), React.createElement("tr", null, React.createElement("th", null, "\u5904\u7406\u4FE1\u606F"), React.createElement("td", null, current.processingMethod || '—')), React.createElement("tr", null, React.createElement("th", null, "\u66F4\u65B0\u65F6\u95F4"), React.createElement("td", null, current.updateTime)))))), React.createElement(Drawer, {
    open: !!logDetail,
    title: "\u65E5\u5FD7\u8BE6\u60C5",
    onClose: () => setLogDetail(null)
  }, logDetail && React.createElement("div", {
    className: "rx-tablescroll"
  }, React.createElement("table", {
    className: "rx-dlist"
  }, React.createElement("tbody", null, React.createElement("tr", null, React.createElement("th", null, "ID"), React.createElement("td", {
    style: {
      fontFamily: 'monospace'
    }
  }, logDetail.id)), React.createElement("tr", null, React.createElement("th", null, "\u7528\u6237\u540D"), React.createElement("td", null, logDetail.username || '—')), React.createElement("tr", null, React.createElement("th", null, "\u7528\u6237 ID"), React.createElement("td", {
    style: {
      fontFamily: 'monospace'
    }
  }, logDetail.userId || '—')), React.createElement("tr", null, React.createElement("th", null, "\u5BA2\u6237\u7AEF IP"), React.createElement("td", {
    style: {
      fontFamily: 'monospace'
    }
  }, logDetail.clientIp || '—')), React.createElement("tr", null, React.createElement("th", null, "\u64CD\u4F5C\u7C7B\u578B"), React.createElement("td", null, React.createElement("span", {
    style: {
      fontFamily: 'monospace',
      fontSize: 12,
      background: 'var(--bg-2)',
      padding: '2px 6px',
      borderRadius: 3
    }
  }, logDetail.operationType))), React.createElement("tr", null, React.createElement("th", null, "\u64CD\u4F5C\u63CF\u8FF0"), React.createElement("td", null, logDetail.operationDesc || '—')), React.createElement("tr", null, React.createElement("th", null, "\u76EE\u6807\u8D44\u6E90"), React.createElement("td", null, logDetail.targetResource || '—')), React.createElement("tr", null, React.createElement("th", null, "\u8BF7\u6C42\u65B9\u6CD5"), React.createElement("td", {
    style: {
      fontFamily: 'monospace'
    }
  }, logDetail.requestMethod || '—')), React.createElement("tr", null, React.createElement("th", null, "\u8BF7\u6C42 URL"), React.createElement("td", {
    style: {
      fontFamily: 'monospace',
      fontSize: 12,
      wordBreak: 'break-all'
    }
  }, logDetail.requestUrl || '—')), React.createElement("tr", null, React.createElement("th", null, "\u8BF7\u6C42\u53C2\u6570"), React.createElement("td", null, React.createElement("pre", {
    style: {
      margin: 0,
      maxHeight: 200,
      overflow: 'auto',
      fontSize: 12,
      fontFamily: 'monospace',
      background: 'var(--bg-2)',
      padding: 8,
      borderRadius: 4,
      whiteSpace: 'pre-wrap',
      wordBreak: 'break-all'
    }
  }, logDetail.requestParams || '—'))), React.createElement("tr", null, React.createElement("th", null, "\u6267\u884C\u7ED3\u679C"), React.createElement("td", null, logDetail.resultStatus === 'SUCCESS' ? React.createElement("span", {
    style: {
      color: 'var(--accent)'
    }
  }, "\u6210\u529F") : React.createElement("span", {
    style: {
      color: 'var(--danger)'
    }
  }, "\u5931\u8D25"))), React.createElement("tr", null, React.createElement("th", null, "\u9519\u8BEF\u4FE1\u606F"), React.createElement("td", {
    style: {
      color: 'var(--danger)',
      fontSize: 12,
      wordBreak: 'break-all'
    }
  }, logDetail.errorMsg || '—')), React.createElement("tr", null, React.createElement("th", null, "\u8017\u65F6"), React.createElement("td", {
    style: {
      fontFamily: 'monospace',
      fontVariantNumeric: 'tabular-nums'
    }
  }, logDetail.durationMs != null ? logDetail.durationMs + ' ms' : '—')), React.createElement("tr", null, React.createElement("th", null, "\u64CD\u4F5C\u65F6\u95F4"), React.createElement("td", {
    style: {
      fontFamily: 'monospace'
    }
  }, logDetail.createTime)))))), React.createElement(ToastHost, null));
}
ReactDOM.createRoot(document.getElementById('root')).render(React.createElement(App, null));