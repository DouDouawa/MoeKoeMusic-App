// 本机调试网关（临时脚手架，不参与正式打包）
//
// 作用：把 dist 静态资源和 /api 代理到同一个 origin。
// 原因：该项目的设备身份依赖 Set-Cookie，而浏览器禁止
//       (Access-Control-Allow-Origin: *) + withCredentials 的组合，
//       所以前端与后端必须同源，否则请求会被 CORS 全部拦死。
//
// 用法：node vite.lan.config.mjs   （文件名是上一版尝试的残留，内容已经是网关）
//       另开一个终端：pnpm api       （kugoumusicapi 监听 127.0.0.1:6521）
//
// 手机访问：http://<本机局域网IP>:8080

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const PORT = Number(process.env.GATEWAY_PORT || 8090);
const TARGET_HOST = '127.0.0.1';
const TARGET_PORT = Number(process.env.API_PORT || 6521);
const DIST = path.join(__dirname, 'dist');

const MIME = {
	'.html': 'text/html; charset=utf-8',
	'.js': 'text/javascript; charset=utf-8',
	'.mjs': 'text/javascript; charset=utf-8',
	'.css': 'text/css; charset=utf-8',
	'.json': 'application/json; charset=utf-8',
	'.webmanifest': 'application/manifest+json; charset=utf-8',
	'.svg': 'image/svg+xml',
	'.png': 'image/png',
	'.jpg': 'image/jpeg',
	'.jpeg': 'image/jpeg',
	'.gif': 'image/gif',
	'.webp': 'image/webp',
	'.ico': 'image/x-icon',
	'.woff': 'font/woff',
	'.woff2': 'font/woff2',
	'.ttf': 'font/ttf',
	'.mp3': 'audio/mpeg',
	'.mp4': 'video/mp4',
	'.wasm': 'application/wasm',
};

const proxyApi = (req, res) => {
	// /api/song/url -> /song/url
	const targetPath = req.url.replace(/^\/api/, '') || '/';

	const proxyReq = http.request(
		{
			host: TARGET_HOST,
			port: TARGET_PORT,
			method: req.method,
			path: targetPath,
			headers: { ...req.headers, host: `${TARGET_HOST}:${TARGET_PORT}` },
		},
		(proxyRes) => {
			// 上游发的是 ACAO:* + ACAC:true，这在带 credentials 的请求里是非法的，
			// 浏览器会直接拦死。这里改写成回显具体 Origin，让 APK 能跨源访问。
			const headers = { ...proxyRes.headers };
			const origin = req.headers.origin;
			if (origin) {
				headers['access-control-allow-origin'] = origin;
				headers['access-control-allow-credentials'] = 'true';
				headers.vary = 'Origin';
			}
			res.writeHead(proxyRes.statusCode || 502, headers);
			proxyRes.pipe(res);
		},
	);

	proxyReq.on('error', (err) => {
		console.error('[gateway] api error:', err.message);
		if (!res.headersSent) res.writeHead(502, { 'Content-Type': 'application/json' });
		res.end(JSON.stringify({ status: 0, error_msg: `gateway: ${err.message}` }));
	});

	req.pipe(proxyReq);
};

const serveStatic = (req, res) => {
	const urlPath = decodeURIComponent((req.url || '/').split('?')[0]);
	const safePath = path.normalize(urlPath).replace(/^(\.\.[/\\])+/, '');
	let filePath = path.join(DIST, safePath);

	const send = (file) => {
		const ext = path.extname(file).toLowerCase();
		res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
		fs.createReadStream(file).pipe(res);
	};

	if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
		return send(filePath);
	}

	// SPA 回退：非静态资源一律交给 index.html（hash 路由）
	filePath = path.join(DIST, 'index.html');
	if (fs.existsSync(filePath)) return send(filePath);

	res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
	res.end('dist/index.html 不存在，请先执行 vite build');
};

const server = http.createServer((req, res) => {
	if ((req.url || '').startsWith('/api')) return proxyApi(req, res);
	serveStatic(req, res);
});

server.listen(PORT, '0.0.0.0', () => {
	console.log(`[gateway] http://0.0.0.0:${PORT}  ->  dist + /api => ${TARGET_HOST}:${TARGET_PORT}`);
});
