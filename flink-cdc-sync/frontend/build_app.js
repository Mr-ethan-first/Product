/*
 * 构建期预编译：frontend/app.jsx -> frontend/app.js（纯浏览器经典脚本，无 ESM，无运行时 Babel）
 * 依赖：@babel/standalone（安装在受管 node workspace，通过 NODE_PATH 解析）
 * 用法：NODE_PATH=<workspace>/node_modules node frontend/build_app.js
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const Babel = require('@babel/standalone');

const FE = __dirname;
const SRC = path.join(FE, 'app.jsx');
const OUT = path.join(FE, 'app.js');
const HTML = path.join(FE, 'index.html');

const code = fs.readFileSync(SRC, 'utf8');
let result;
try {
  result = Babel.transform(code, {
    presets: [['react', { runtime: 'classic' }]],
    sourceType: 'script', // 关键：脚本模式，Babel 不会产出任何 import/export
    compact: false,
    comments: false,
    filename: 'app.jsx',
  });
} catch (e) {
  console.error('BABEL_ERROR:', e.message);
  process.exit(1);
}

let out = result.code;
if (/^\s*(import|export)\s/m.test(out) || /\bimport\s*\(/.test(out)) {
  console.error('GUARD_FAIL: 编译产物仍含 import/export');
  process.exit(1);
}
const finalJs = '/* AUTO-GENERATED from app.jsx by build_app.js. DO NOT EDIT. */\n' + out;
fs.writeFileSync(OUT, finalJs, 'utf8');

// 用产物内容哈希自动改写 index.html 的缓存版本号，确保内容变化时 URL 必变，彻底击穿浏览器缓存
const hash = crypto.createHash('md5').update(finalJs).digest('hex').slice(0, 10);
if (fs.existsSync(HTML)) {
  let html = fs.readFileSync(HTML, 'utf8');
  const before = html;
  html = html.replace(/(src=")app\.js(?:\?v=[^"]*)?(")/g, `$1app.js?v=${hash}$2`);
  if (html !== before) {
    fs.writeFileSync(HTML, html, 'utf8');
    console.log('OK stamped index.html app.js?v=' + hash);
  } else {
    console.warn('WARN: index.html 未找到 app.js 引用，未改写版本号');
  }
}
console.log('OK compiled app.jsx -> app.js  bytes=' + fs.statSync(OUT).size + '  hash=' + hash);
