/*
 * 前端自动化测试（headless / jsdom）
 * 目标：证明预编译产物 app.js 能在浏览器环境正常挂载 React 应用，
 *      且不再出现 "Cannot use import statement outside a module" 之类运行时错误。
 *
 * 运行：
 *   NODE_PATH=<managed workspace>/node_modules \
 *   node frontend/test/render.test.js
 * 依赖：jsdom, react@18.3.1, react-dom@18.3.1（安装在受管 node workspace）
 */
const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const FE = path.resolve(__dirname, '..');
const results = [];
function check(name, cond, extra) {
  results.push({ name, ok: !!cond, extra: extra || '' });
  console.log((cond ? 'PASS ' : 'FAIL ') + name + (extra ? '  -> ' + extra : ''));
}

// 1) 静态断言：index.html 不含 text/babel / import / babel.min.js
const indexHtml = fs.readFileSync(path.join(FE, 'index.html'), 'utf8');
check('index.html 无 text/babel 运行时转译', !/text\/babel/.test(indexHtml));
check('index.html 无 babel.min.js 依赖', !/babel(\.min)?\.js/.test(indexHtml));
check('index.html 无 ESM import/export', !/\b(import|export)\s/.test(indexHtml));
check('index.html 引用了预编译 app.js', /<script[^>]+src=["']app\.js/.test(indexHtml));

// 2) 静态断言：app.js 为纯经典脚本，零 ESM
const appJs = fs.readFileSync(path.join(FE, 'app.js'), 'utf8');
check('app.js 无 import/export', !/^\s*(import|export)\s/m.test(appJs) && !/\bimport\s*\(/.test(appJs));
check('app.js 已编译为 React.createElement', /React\.createElement/.test(appJs));
check('app.js 使用 createRoot 挂载', /ReactDOM\.createRoot/.test(appJs));

// 2.1) 移动端自适应：viewport / 媒体查询 / 表格横向滚动包裹
check('index.html 含响应式 viewport(meta)', /name=["']viewport["'][^>]*width=device-width/.test(indexHtml));
check('index.html 含移动端媒体查询 @media(max-width:768px)', /@media\s*\(max-width:\s*768px\)/.test(indexHtml));
check('index.html 含窄屏媒体查询 @media(max-width:480px)', /@media\s*\(max-width:\s*480px\)/.test(indexHtml));
const tsCount = (appJs.match(/rx-tablescroll/g) || []).length;
check('app.js 为 4 张表格包裹横向滚动容器 rx-tablescroll', tsCount === 4, 'count=' + tsCount);
check('移动端断点将抽屉改为占满(width:100%)', /rx-drawer[\s\S]*?width:\s*100%\s*!important/.test(indexHtml));
check('移动端断点将统计卡改为两列网格', /stat-row\s*\{\s*display:\s*grid/.test(indexHtml));

// 3) 动态断言：在 jsdom 中真实挂载，断言登录页渲染且无异常
// react/react-dom 的 package.json exports 不暴露 ./umd/*，故用 NODE_PATH 目录直接定位物理文件
const NM = (process.env.NODE_PATH || '').split(path.delimiter).find(p => p && fs.existsSync(path.join(p, 'react', 'umd')))
  || path.join(process.env.NODE_PATH || '', '');
const reactUmd = fs.readFileSync(path.join(NM, 'react', 'umd', 'react.production.min.js'), 'utf8');
const reactDomUmd = fs.readFileSync(path.join(NM, 'react-dom', 'umd', 'react-dom.production.min.js'), 'utf8');

const dom = new JSDOM('<!DOCTYPE html><html><body><div id="root"></div></body></html>', {
  runScripts: 'outside-only',
  pretendToBeVisual: true,
  url: 'http://127.0.0.1:8080/',
});
const { window } = dom;

// 收集运行时错误（尤其是我们要根治的 import 报错）
const runtimeErrors = [];
window.addEventListener('error', (e) => runtimeErrors.push(String(e.message || e.error)));
window.onerror = (msg) => { runtimeErrors.push(String(msg)); };

// 后端未启动：stub fetch 让 checkAuth 走 catch -> 停留登录页
window.fetch = () => Promise.reject(new Error('offline-test'));
// React18 调度器需要 MessageChannel（jsdom 提供）；补齐 matchMedia 以防万一
if (!window.matchMedia) window.matchMedia = () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} });

let evalError = '';
try {
  window.eval(reactUmd);
  window.eval(reactDomUmd);
  if (!window.React || !window.ReactDOM) throw new Error('React/ReactDOM 未挂载到 window');
  window.eval(appJs); // 这一步若产物含 import 会立刻抛错
} catch (e) {
  evalError = e.message + '\n' + (e.stack || '');
}
check('React/ReactDOM UMD 加载成功', !!(window.React && window.ReactDOM));
check('app.js 执行无抛错（无 import 运行时错误）', !evalError, evalError.slice(0, 300));

// 等一个微/宏任务，让 React 完成首次渲染
setTimeout(() => {
  const rootHtml = window.document.getElementById('root').innerHTML;
  check('根节点已渲染内容', rootHtml && rootHtml.length > 0, 'len=' + (rootHtml ? rootHtml.length : 0));
  check('登录页正确渲染（含“GeoDRSync 管理后台”）', /GeoDRSync 管理后台/.test(rootHtml));
  check('登录页含“登 录”按钮', /登\s*录/.test(rootHtml));
  const importErr = runtimeErrors.find(m => /Cannot use import statement/.test(m));
  check('无 “Cannot use import statement” 运行时错误', !importErr, importErr || '');

  const failed = results.filter(r => !r.ok);
  console.log('\n==== 前端测试汇总: ' + (results.length - failed.length) + '/' + results.length + ' 通过 ====');
  if (failed.length) { console.error('FAILED: ' + failed.map(f => f.name).join('; ')); process.exit(1); }
  console.log('ALL FRONTEND TESTS PASSED');
  process.exit(0);
}, 200);
