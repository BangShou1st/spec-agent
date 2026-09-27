// 文件名:find-dangling-imports.cjs
// 用途:扫描后端 Java 源码,找出引用了源码树中不存在的 com.specagent 类的
//       import 语句(即"悬空导入"),通常出现在包迁移之后,用于快速定位残留。
const fs = require('fs'), path = require('path');
const idx = new Set();
// 递归遍历目录,把所有 Java 类的简单类名收进索引
function walk(d) {
  for (const f of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, f.name);
    if (f.isDirectory()) walk(p);
    else if (f.name.endsWith('.java')) idx.add(f.name.slice(0, -5));
  }
}
walk('backend/src/main/java'); walk('backend/src/test/java');
const bad = [];
// 再次遍历,逐条检查 com.specagent 的 import 是否命中类名索引
function scan(d) {
  for (const f of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, f.name);
    if (f.isDirectory()) scan(p);
    else if (f.name.endsWith('.java')) {
      const t = fs.readFileSync(p, 'utf8');
      const re = /import\s+(?:static\s+)?([\w.]+)\.(\w+);/g;
      let m;
      while ((m = re.exec(t))) {
        if (m[1].startsWith('com.specagent') && !idx.has(m[2])) {
          bad.push(m[0] + '   <- ' + p.replace(/\\/g, '/'));
        }
      }
    }
  }
}
scan('backend/src/main/java'); scan('backend/src/test/java');
console.log(bad.length + ' dangling imports');
for (const b of bad) console.log(b);
