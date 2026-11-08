# -*- coding: utf-8 -*-
"""
把 dist 里的完整包作为 Release 附件发布到 GitHub。

- 每次运行自动生成新 tag（v6.41-enhanced.年月日.时分），不会覆盖历史版本
- 打包好的 zip 不进 git，只作为 Release 资产，仓库历史不会越长越大
- token 从本机 git 凭证管理器现取，不写进任何文件

用法：python 发布Release.py [tag]
"""
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.abspath(__file__))
REPO = "donar1/BilibiliDown-Enhanced"
ZIP_PATH = os.path.join(ROOT, "dist", "BilibiliDown-补丁版.zip")
ASSET_NAME = "BilibiliDown-Enhanced.zip"


def note(m):
    print(m)


if not os.path.isfile(ZIP_PATH):
    note("[错误] 找不到 " + ZIP_PATH + "，请先跑 打包分享版.py")
    sys.exit(2)

# ---- token ----
env = dict(os.environ)
env["GIT_TERMINAL_PROMPT"] = "0"
stdin = "protocol=https\nhost=github.com\nurl=https://github.com/%s.git\n\n" % REPO
p = subprocess.run(["git", "credential", "fill"], input=stdin, capture_output=True, text=True, env=env)
token = None
for line in p.stdout.splitlines():
    if line.startswith("password="):
        token = line.split("=", 1)[1].strip()
if not token:
    note("[错误] 拿不到 GitHub 令牌。请在 GitHub 上登录一次 git（或用凭据管理器登录）后重试。")
    sys.exit(3)

# ---- 计算校验值 ----
with open(ZIP_PATH, "rb") as f:
    data = f.read()
sha = hashlib.sha256(data).hexdigest()
size_mb = len(data) / 1024.0 / 1024.0
note("包大小 %.1f MB，SHA256=%s" % (size_mb, sha))

tag = sys.argv[1] if len(sys.argv) > 1 else "v6.41-enhanced." + time.strftime("%Y%m%d.%H%M")

BODY = """## 下载

**`BilibiliDown-Enhanced.zip`（%.1f MB）** —— 解压后双击 `启动BilibiliDown.bat` 即用，**自带 Java，无需安装**。

## 包含内容

- BilibiliDown v6.41 主程序（原 jar 未改动一字节）
- 零侵入补丁，新增三个页签：**下载记录 / UP主批量 / 补采清单**
- 自带 Java 11 运行时（84.8 MB）+ ffmpeg
- 台账脚本、可移植配置模板、完整文档

## 本版配置

- 下载路径按 UP 主分目录：`download/<UP主>/BV号-标题-pNN-画质.mp4`
- 文件名带 BV 号，便于台账与补采稳定识别
- `bilibili.alert.qualityUnexpected = false`：视频没有目标画质时按实际最高画质下载，不再抛错中断
- 已清空登录凭证与设备指纹；首次使用请自行扫码登录（未登录只能下 720P 及以下）

## 校验

SHA256: `%s`

    certutil -hashfile BilibiliDown-Enhanced.zip SHA256
""" % (size_mb, sha)


def api(url, payload=None, method="GET", ctype="application/json"):
    body = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=body, method=method, headers={
        "Authorization": "Bearer " + token,
        "User-Agent": "bd-publish",
        "Accept": "application/vnd.github+json",
        "Content-Type": ctype,
    })
    with urllib.request.urlopen(req, timeout=180) as r:
        return json.loads(r.read().decode("utf-8"))


note("\n[1/2] 创建 Release（tag=%s）" % tag)
try:
    rel = api("https://api.github.com/repos/%s/releases" % REPO, {
        "tag_name": tag,
        "name": "BilibiliDown 增强版 " + tag,
        "body": BODY,
        "draft": False,
        "prerelease": False,
    }, method="POST")
except urllib.error.HTTPError as e:
    note("[错误] 创建 Release 失败：%s %s" % (e.code, e.read().decode("utf-8", "ignore")[:300]))
    note("       如果 tag 已存在，请换一个 tag 名：python 发布Release.py v6.41-enhanced.2")
    sys.exit(4)

rel_id = rel["id"]
note("  release id=%s  %s" % (rel_id, rel["html_url"]))

note("\n[2/2] 上传附件 %s" % ASSET_NAME)
# 用 curl 上传：GitHub 的 uploads.github.com 在受限网络下会让 schannel 吊销检查失败，
# --ssl-no-revoke 跳过吊销检查（不影响证书链校验），实测可通过。
cmd = [
    "curl.exe", "--ssl-no-revoke", "-sS", "--retry", "2", "--retry-delay", "3", "-X", "POST",
    "-H", "Authorization: Bearer " + token,
    "-H", "Content-Type: application/zip",
    "-H", "User-Agent: bd-publish",
    "--data-binary", "@" + ZIP_PATH,
    "https://uploads.github.com/repos/%s/releases/%s/assets?name=%s" % (REPO, rel_id, ASSET_NAME),
]
r = subprocess.run(cmd, capture_output=True, text=True)
if '"state"' not in (r.stdout or ""):
    note("[错误] 上传失败：")
    note((r.stdout or "")[:400])
    note((r.stderr or "")[:400])
    note("  Release 已建但没附件，去网页手动补一个，或重跑本脚本换个 tag。")
    sys.exit(5)

asset = json.loads(r.stdout)
note("  附件 state=%s  size=%d bytes" % (asset["state"], asset["size"]))
note("\n完成：%s" % rel["html_url"])
note("下载直链：https://github.com/%s/releases/download/%s/%s" % (REPO, tag, ASSET_NAME))
