import json
import subprocess
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Dict, Optional, Tuple


@dataclass
class HttpResult:
    status: int
    text: str


class Api:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")

    def request(self, method: str, path: str, token: Optional[str] = None, body: Optional[Dict[str, Any]] = None, accept: Optional[str] = None) -> HttpResult:
        url = self.base_url + path
        headers: Dict[str, str] = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        if accept:
            headers["Accept"] = accept
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                return HttpResult(resp.status, resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            return HttpResult(e.code, e.read().decode("utf-8"))

    def json(self, method: str, path: str, token: Optional[str] = None, body: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        res = self.request(method, path, token=token, body=body)
        return json.loads(res.text)


def grant_quota_mysql(username: str, quota: int) -> None:
    cmd = [
        "docker",
        "exec",
        "-i",
        "smart_code_ark-mysql-1",
        "mysql",
        "-usmartark",
        "-psmartark",
        "smartark",
        "-e",
        f"UPDATE users SET quota={quota} WHERE username='{username}';",
    ]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL)


def download_size(url: str, token: str) -> Tuple[int, int]:
    headers = {"Authorization": "Bearer " + token}
    req = urllib.request.Request(url, headers=headers, method="GET")
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = resp.read()
        return resp.status, len(data)


def main() -> None:
    api = Api("http://localhost:8080")
    user = f"smoke_{int(time.time())}"
    password = "pass1234"

    api.request("POST", "/api/auth/register", body={"username": user, "password": password})
    login = api.json("POST", "/api/auth/login", body={"username": user, "password": password})
    token = (login.get("data") or {}).get("token")
    if not token:
        raise SystemExit(f"login_failed: {login}")

    start = api.json("POST", "/api/chat/start", token=token, body={"title": "smoke", "projectType": "web", "description": "verify"})
    session_id = (start.get("data") or {}).get("sessionId")
    if not session_id:
        raise SystemExit(f"chat_start_failed: {start}")

    confirm = api.json(
        "POST",
        "/api/projects/confirm",
        token=token,
        body={
            "sessionId": session_id,
            "stack": {"backend": "Spring Boot 3", "frontend": "Vue 3", "db": "MySQL 8"},
            "description": "verify",
        },
    )
    project_id = (confirm.get("data") or {}).get("projectId")
    if not project_id:
        raise SystemExit(f"confirm_failed: {confirm}")

    grant_quota_mysql(user, 100)

    gen = api.json("POST", "/api/generate", token=token, body={"projectId": project_id})
    task_id = (gen.get("data") or {}).get("taskId")
    if not task_id:
        raise SystemExit(f"generate_failed: {gen}")

    deadline = time.time() + 180
    last = None
    while time.time() < deadline:
        status_obj = api.json("GET", f"/api/task/{task_id}/status", token=token)
        data = status_obj.get("data") or {}
        s = data.get("status")
        p = data.get("progress")
        step = data.get("current_step") or data.get("step")
        line = f"status={s} progress={p} step={step}"
        if line != last:
            print(line)
            last = line
        if s in ("finished", "failed"):
            break
        time.sleep(2)

    final_status = api.json("GET", f"/api/task/{task_id}/status", token=token)
    final = final_status.get("data") or {}
    print(f"user={user}")
    print(f"sessionId={session_id}")
    print(f"projectId={project_id}")
    print(f"taskId={task_id}")
    print(f"finalStatus={final.get('status')}")

    if final.get("status") == "finished":
        http_status, size = download_size(f"http://localhost:8080/api/task/{task_id}/download", token)
        print(f"downloadHttp={http_status}")
        print(f"downloadBytes={size}")


if __name__ == "__main__":
    main()
