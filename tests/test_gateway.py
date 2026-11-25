"""Gateway 最小测试：鉴权、配对、token 抓取、exec 默认关闭。

运行：python3 -m unittest discover -s tests -v
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request
import pathlib

GATEWAY = pathlib.Path(__file__).resolve().parent.parent / "gateway" / "gateway.py"
PORT = 3411
SECRET = "testsecret"


class GatewayTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        cls.data = os.path.join(cls.tmp, "data")
        os.makedirs(cls.data)
        with open(os.path.join(cls.data, "dsh.log"), "w", encoding="utf-8") as f:
            f.write("http://127.0.0.1:3080/?token=TESTTOKEN123\n")
        env = dict(
            os.environ,
            GATEWAY_DATA_DIR=cls.data,
            GATEWAY_PORT=str(PORT),
            DSH_PORT="9999",
            GATEWAY_SECRET=SECRET,
        )
        cls.proc = subprocess.Popen(
            [sys.executable, str(GATEWAY)],
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        for _ in range(80):
            try:
                urllib.request.urlopen("http://127.0.0.1:%d/health" % PORT, timeout=1)
                break
            except Exception:
                time.sleep(0.1)
        else:
            cls.proc.terminate()
            raise RuntimeError("gateway did not start")

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        try:
            cls.proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            cls.proc.kill()
        shutil.rmtree(cls.tmp, ignore_errors=True)

    # ---- helpers ----
    def get(self, path, auth=None):
        req = urllib.request.Request("http://127.0.0.1:%d%s" % (PORT, path))
        if auth:
            req.add_header("Authorization", "Bearer " + auth)
        try:
            with urllib.request.urlopen(req, timeout=3) as r:
                return r.status, r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", "replace")

    def post(self, path, body, auth=None):
        req = urllib.request.Request(
            "http://127.0.0.1:%d%s" % (PORT, path),
            data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json"},
        )
        if auth:
            req.add_header("Authorization", "Bearer " + auth)
        try:
            with urllib.request.urlopen(req, timeout=3) as r:
                return r.status, r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode("utf-8", "replace")

    # ---- tests ----
    def test_health_does_not_require_auth(self):
        status, _ = self.get("/health")
        self.assertEqual(status, 200)

    def test_token_requires_auth(self):
        status, _ = self.get("/token")
        self.assertEqual(status, 401)

    def test_token_returns_dsh_token(self):
        status, body = self.get("/token", auth=SECRET)
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["token"], "TESTTOKEN123")

    def test_pair_flow_and_rotation(self):
        with open(os.path.join(self.data, "pair-code"), encoding="utf-8") as f:
            code = f.read().strip()
        status, _ = self.post("/pair/claim", {"code": "000000"})
        self.assertEqual(status, 401)
        status, body = self.post("/pair/claim", {"code": code})
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["secret"], SECRET)
        # 用过的码立即失效
        status, _ = self.post("/pair/claim", {"code": code})
        self.assertEqual(status, 401)

    def test_exec_disabled_by_default(self):
        status, _ = self.post("/exec", {"cmd": "echo hi"}, auth=SECRET)
        self.assertEqual(status, 403)


if __name__ == "__main__":
    unittest.main()
