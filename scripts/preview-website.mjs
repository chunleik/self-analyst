import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, sep, extname } from 'node:path';

const root = fileURLToPath(new URL('../website/', import.meta.url));
const base = '/self-analyst/';
const types = { '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.png': 'image/png' };
const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url, 'http://localhost');
    if (url.pathname === '/self-analyst') { response.writeHead(302, { Location: base }); response.end(); return; }
    if (!url.pathname.startsWith(base)) { response.writeHead(404); response.end(); return; }
    let path = resolve(root, decodeURIComponent(url.pathname.slice(base.length)));
    if (path !== resolve(root) && !path.startsWith(resolve(root) + sep)) { response.writeHead(403); response.end(); return; }
    if ((await stat(path)).isDirectory()) {
      if (!url.pathname.endsWith('/')) { response.writeHead(302, { Location: url.pathname + '/' }); response.end(); return; }
      path = resolve(path, 'index.html');
    }
    response.writeHead(200, { 'Content-Type': types[extname(path)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    response.end(await readFile(path));
  } catch { response.writeHead(404); response.end('Not found'); }
});
server.listen(4173, '127.0.0.1', () => console.log('http://127.0.0.1:4173/self-analyst/'));
