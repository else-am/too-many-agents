"""Serve only explicitly registered browser fixtures on an ephemeral loopback port."""
import atexit
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread
from urllib.parse import quote, urlsplit


_pages = {}
_server = None


class _Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        body = _pages.get(urlsplit(self.path).path)
        if body is None:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_):
        pass


def fixture_url(path):
    global _server
    route = '/' + quote(path.name, safe='')
    _pages[route] = path.read_bytes()
    if _server is None:
        _server = ThreadingHTTPServer(('127.0.0.1', 0), _Handler)
        _server.daemon_threads = True
        Thread(target=_server.serve_forever, daemon=True).start()
        atexit.register(_server.server_close)
        atexit.register(_server.shutdown)
    return f'http://127.0.0.1:{_server.server_port}{route}'
