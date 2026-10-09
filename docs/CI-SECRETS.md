# CI Secrets 与令牌维护

## 依赖的 secret

| 仓库 | secret | 用途 |
|---|---|---|
| Neko-UI | `GH_PAT` | 克隆私有仓 Nekobox-MG（协议魔改源）、查询 geo/协议仓库 API |
| Nekobox-MG | `GH_PAT` | 解析/钉扎外部 mod 仓库 commit |

两处 `GH_PAT` **必须同时更新**，值 = 一个有 `repo` + `workflow` 权限的 PAT（把 PAT 自身写进 secret）。

## 令牌过期后的表现

构建在 **"Clone Nekobox-MG (protocol mods)"** 步骤失败：

```
remote: Invalid username or token. Password authentication is not supported for Git operations.
fatal: Authentication failed for 'https://github.com/oh5uosnvh/Nekobox-MG.git/'
```

## 修复步骤（API 方式，sealed box 加密）

```bash
pip install pynacl   # 或 apk add py3-pynacl
python3 - <<'EOF'
import json, urllib.request, base64
from nacl import encoding, public

TOKEN = "<新PAT>"          # 同时用于调 API 和写入 secret
SECRET_NAME = "GH_PAT"
SECRET_VALUE = TOKEN       # PAT 自身

def api(method, url, payload=None):
    req = urllib.request.Request(url, method=method,
        data=json.dumps(payload).encode() if payload is not None else None,
        headers={"Authorization": f"token {TOKEN}", "Accept": "application/vnd.github+json"})
    try:
        with urllib.request.urlopen(req) as r:
            b = r.read().decode()
            return r.status, json.loads(b) if b else {}
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()

for repo in ["oh5uosnvh/Neko-UI", "oh5uosnvh/Nekobox-MG"]:
    st, pk = api("GET", f"https://api.github.com/repos/{repo}/actions/secrets/public-key")
    assert st == 200, pk
    sealed = base64.b64encode(public.SealedBox(
        public.PublicKey(pk["key"].encode(), encoding.Base64Encoder())
    ).encrypt(SECRET_VALUE.encode())).decode()
    st2, r2 = api("PUT", f"https://api.github.com/repos/{repo}/actions/secrets/{SECRET_NAME}",
                  {"encrypted_value": sealed, "key_id": pk["key_id"]})
    print(repo, st2)   # 204 = OK
EOF
```

或网页手动：两个仓库 → Settings → Secrets and variables → Actions → `GH_PAT` → Update。

## 建议

- 短效 token 每次过期都要重来一遍——条件允许时换 **fine-grained token**
  （仅勾 `oh5uosnvh/Nekobox-MG` + `oh5uosnvh/Neko-UI` 两仓，Contents/Actions 只读+写，
  有效期拉到最长）。
- PAT 不要提交进仓库、不要写进 workflow；只放 secret。
