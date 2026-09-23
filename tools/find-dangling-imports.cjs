const fs = require('fs'), path = require('path');
const idx = new Set();
function walk(d) {
  for (const f of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, f.name);
    if (f.isDirectory()) walk(p);
    else if (f.name.endsWith('.java')) idx.add(f.name.slice(0, -5));
  }
}
walk('backend/src/main/java'); walk('backend/src/test/java');
const bad = [];
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
