// 使用 @babel/standalone 编译 app.jsx -> app.js
const fs = require('fs');
const path = require('path');
const Babel = require('@babel/standalone');

const jsxFile = path.join(__dirname, 'app.jsx');
const jsFile = path.join(__dirname, 'app.js');

const code = fs.readFileSync(jsxFile, 'utf-8');

const result = Babel.transform(code, {
  presets: [['react', { runtime: 'classic' }]],
  filename: 'app.jsx',
});

fs.writeFileSync(jsFile, result.code, 'utf-8');
console.log('Build success: app.jsx -> app.js (' + result.code.length + ' bytes)');
