# 前后端自动化测试与 Babel 运行时错误根治报告

> 关联需求：修复反复出现的 `Uncaught SyntaxError: Cannot use import statement outside a module`（硬清缓存仍复现），并补齐前端 + 后端自动化测试。

## 一、错误根因（推翻上一轮"浏览器缓存"结论）

用户硬清缓存后错误仍在，且新报错栈变为 `transformScriptTags.ts:114`（新版 `@babel/standalone` 源码文件名），说明浏览器加载的是**全新的 Babel**，并非陈旧缓存。

真正根因：页面采用**运行时 Babel 转译**（`<script type="text/babel">` + 从 unpkg 拉取**不固定版本**的 `@babel/standalone`）。新版 `@babel/standalone`（v7.28+）默认 `sourceType: "module"`，在浏览器内联转译 → 动态 `appendChild` 注入编译结果的路径上，会以浏览器无法接受的方式注入脚本，从而抛出 `Cannot use import statement outside a module`。

结论：**只要依赖"运行时 Babel + latest CDN"，此类错误就有复现风险**。这是架构性隐患，不是某一行代码的 bug。

## 二、根治方案：去掉运行时 Babel，改为构建期预编译

| 项 | 改造前 | 改造后 |
|---|---|---|
| JSX 转译 | 浏览器运行时 `@babel/standalone`（latest） | **构建期**预编译为纯 JS |
| 页面脚本 | `<script type="text/babel">` 内联 JSX | `<script src="app.js">` 经典脚本 |
| React 版本 | `react@18`（latest，会漂移） | **固定 `react@18.3.1`** UMD |
| Babel 依赖 | 页面强依赖 `babel.min.js` | **完全移除** |
| 产物校验 | 无 | 编译期 + 测试期双重 `import/export` 断言 |

### 文件变更
- `frontend/app.jsx`（**新增**）：从 index.html 抽出的 React 源码（JSX）。
- `frontend/app.js`（**新增，自动生成**）：由 `build_app.js` 用 `sourceType:'script'` 预编译，`React.createElement` 形态，零 ESM。
- `frontend/index.html`（**重写**）：删除内联 JSX 与 `babel.min.js`，固定 React 18.3.1，改引 `app.js?v=`。由 969 行瘦身到 113 行。
- `frontend/build_app.js`（**新增**）：JSX→JS 预编译器（内含"产物不得含 import/export"硬校验）。
- `frontend/build-and-test.sh`（**新增**）：一键"编译 + 前端渲染测试"。
- `pom.xml`：静态资源 includes 增加 `app.js`（连同 index.html 一起打入 jar 的 `static/`）。

## 三、前端自动化测试（headless / jsdom，13 项全通过）

位置：`frontend/test/render.test.js`。用 jsdom 载入固定版 React UMD + 预编译 `app.js`，**真实挂载**应用并断言：

```
PASS index.html 无 text/babel 运行时转译
PASS index.html 无 babel.min.js 依赖
PASS index.html 无 ESM import/export
PASS index.html 引用了预编译 app.js
PASS app.js 无 import/export
PASS app.js 已编译为 React.createElement
PASS app.js 使用 createRoot 挂载
PASS React/ReactDOM UMD 加载成功
PASS app.js 执行无抛错（无 import 运行时错误）
PASS 根节点已渲染内容  -> len=781
PASS 登录页正确渲染（含"GeoDRSync 管理后台"）
PASS 登录页含"登 录"按钮
PASS 无 "Cannot use import statement" 运行时错误
==== 前端测试汇总: 13/13 通过 ====
```

关键：第 9、13 项直接证明——预编译产物在浏览器环境执行时**不会再触发 import 报错**，登录页正常渲染。

运行方式：
```bash
bash frontend/build-and-test.sh
```

## 四、后端自动化测试（JUnit 集成测试，8 用例）

位置：`src/test/java/.../GeoDRSyncConsistencyTest`（连真实 MySQL：源 127.0.0.1:3306 / 目标 192.168.88.88:3306）。随 `mvn clean package` 执行，覆盖跨主机十库同步一致性、忽略规则、层级忽略、字段转换等。

> 运行前须停掉运行态 app，避免打爆 MySQL `root` 的 `max_user_connections`。

结果：**8/8 通过**（BUILD SUCCESS），本次前端改造未破坏后端行为。

## 五、部署验证（线上 HTTP）

- 重新打包后 jar 内嵌 `static/index.html` + `static/app.js`。
- `--server.port=8080` 重启，`GET /` → 200；`GET /app.js` → 200。
- 线上 `index.html` 与 `app.js` 均 **零 `import`/`export` 子串**，`index.html` 已无 `text/babel`。

## 六、用户操作指引

**普通刷新（F5）即可**。若极端情况下仍见旧页面，Ctrl+F5 强刷一次（本次已把 CDN 换成固定版本 + `app.js?v=` 版本号 + 服务端 `Cache-Control: no-store`，理论上无需强刷）。

## 七、后续维护约定（重要）

前端改代码只改 `frontend/app.jsx`，然后：
```bash
bash frontend/build-and-test.sh                 # 1) 预编译 app.js + 跑前端测试
bash /d/WorkSpace/flink-cdc-sync/mvn3.sh -o clean package   # 2) 重新打包（含后端测试）
# 3) 停旧进程 → --server.port=8080 重启
```
**不要手改 `frontend/app.js`**（它是自动生成物，会被覆盖）。
