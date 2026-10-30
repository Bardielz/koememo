# Build-only loopback cache; upstream downloads use curl with HTTPS verification.
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from pathlib import Path
import subprocess, hashlib, threading, os
import sys
ROOT=Path(sys.argv[1]); ROOT.mkdir(parents=True, exist_ok=True)
BASE={'google':'https://dl.google.com/dl/android/maven2/', 'central':'https://repo.maven.apache.org/maven2/'}
locks={}; guard=threading.Lock()
class Handler(BaseHTTPRequestHandler):
    def do_HEAD(self): self.fetch(False)
    def do_GET(self): self.fetch(True)
    def fetch(self,body):
        parts=self.path.lstrip('/').split('/',1)
        if len(parts)!=2 or parts[0] not in BASE or '..' in parts[1] or '?' in parts[1]: self.send_error(400); return
        key=hashlib.sha256(self.path.encode()).hexdigest(); path=ROOT/key
        with guard: lock=locks.setdefault(key,threading.Lock())
        with lock:
            if not path.exists():
                temp=path.with_suffix('.part')
                status=subprocess.run(['curl','--http1.1','-sSL','--connect-timeout','15','--max-time','180','--retry','2','-o',str(temp),'-w','%{http_code}',BASE[parts[0]]+parts[1]],capture_output=True,text=True)
                if status.returncode or status.stdout!='200':
                    temp.unlink(missing_ok=True)
                    self.send_error(404 if status.stdout=='404' else 502); return
                temp.rename(path)
        self.send_response(200); self.send_header('Content-Length',str(path.stat().st_size)); self.end_headers()
        if body:
            try:
                with path.open('rb') as source:
                    import shutil
                    shutil.copyfileobj(source,self.wfile)
            except BrokenPipeError: pass
    def log_message(self,*args): pass
server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
Path(sys.argv[2]).write_text(str(server.server_port))
server.serve_forever()
