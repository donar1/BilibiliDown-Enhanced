# -*- coding: utf-8 -*-
"""
把 dist 里的完整包作为 Release 附件发布到 GitHub。

- tag 自动递增（v6.41-enhanced.1 → .2 → .3），也可命令行指定
- 打包好的 zip 不进 git，只作为 Release 资产，仓库历史不会越推越大
- 上传失败自动删掉刚建的空 Release，不留"有版本没附件"的坑
- token 从本机 git 凭证管理器现取，经 stdin 交给 curl，不出现在命令行里

用法：python 发布Release.py [tag]
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.abspath(__file__))
REPO = "donar1/BilibiliDown-Enhanced"
ZIP_PATH = os.path.join(ROOT, "dist", "BilibiliDown-补丁版.zip")
ASSET_NAME = "BilibiliDown-Enhanced.zip"
API = "https://api.github.com/repos/%s" % REPO


def note(m):
    print(m)


if not os.path.isfile(ZIP_PATH):
    note("[错误] 找不到 " + ZIP_PATH + "，请先跑 打包分享版.py")
    sys.exit(2)

# ---- token（只存在内存里，不写文件、不进命令行）----
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

# ---- 读包并算校验值 ----
with open(ZIP_PATH, "rb") as f:
    data = f.read()
sha = hashlib.sha256(data).hexdigest()
size_mb = len(data) / 1024.0 / 1024.0
age_h = (time.time() - os.path.getmtime(ZIP_PATH)) / 3600.0
note("包大小 %.1f MB，SHA256=%s" % (size_mb, sha))
if age_h > 24:
    note("[提醒] 这个 zip 是 %.1f 小时前打包的，不是最新产物。" % age_h)
    note("       若本意是发新版本，先重跑 打包分享版.py，否则会把旧包当成新版发出去。")


def api(url, payload=None, method="GET", retries=3):
    """GitHub API 调用；网络类错误重试，4xx 立即抛出。"""
    body = json.dumps(payload).encode("utf-8") if payload is not None else None
    last = None
    for attempt in range(retries):
        req = urllib.request.Request(url, data=body, method=method, headers={
            "Authorization": "Bearer " + token,
            "User-Agent": "bd-publish",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
        })
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                raw = r.read().decode("utf-8")
                return json.loads(raw) if raw.strip() else {}
        except urllib.error.HTTPError as e:
            raise
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            last = e
            if attempt < retries - 1:
                note("  网络异常，第 %d 次重试：%s" % (attempt + 1, e))
                time.sleep(3 * (attempt + 1))
    raise last


def next_tag():
    """读最近 10 个 release，把形如 v6.41-enhanced.N 的最大 N 加一。"""
    try:
        rels = api(API + "/releases?per_page=10")
    except Exception as e:
        note("[提醒] 取不到历史 release（%s），将从 .1 开始" % e)
        return "v6.41-enhanced.1"
    nums = []
    for r in rels:
        m = re.match(r"v6\.41-enhanced\.([0-9]+)$", r.get("tag_name", ""))
        if m:
            nums.append(int(m.group(1)))
    return "v6.41-enhanced.%d" % (max(nums) + 1 if nums else 1)


tag = sys.argv[1] if len(sys.argv) > 1 else next_tag()

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

note("\n[1/2] 创建 Release（tag=%s）" % tag)
try:
    rel = api(API + "/releases", {
        "tag_name": tag,
        "name": "BilibiliDown 增强版 " + tag,
        "body": BODY,
        "draft": False,
        "prerelease": False,
    }, method="POST")
except urllib.error.HTTPError as e:
    detail = e.read().decode("utf-8", "ignore")[:300]
    note("[错误] 创建 Release 失败：%s %s" % (e.code, detail))
    if e.code == 422:
        note("       tag 已存在。换一个名字：python 发布Release.py %s" % (tag + "-b"))
    sys.exit(4)

rel_id = rel["id"]
note("  release id=%s  %s" % (rel_id, rel["html_url"]))

note("\n[2/2] 上传附件 %s" % ASSET_NAME)
# curl --config - 从 stdin 读配置：token 不出现在进程命令行里。
# --ssl-no-revoke：GitHub uploads 域在受限网络下会让 schannel 吊销检查失败，
# 跳过吊销检查（不影响证书链校验），实测可通过。
upload_url = "https://uploads.github.com/repos/%s/releases/%s/assets?name=%s" % (REPO, rel_id, ASSET_NAME)
cfg = (
    'url = "%s"\n'
    'header = "Authorization: Bearer %s"\n'
    'header = "Content-Type: application/zip"\n'
    'header = "User-Agent: bd-publish"\n'
    'upload-file = "%s"\n'
) % (upload_url, token, ZIP_PATH.replace("\\", "\\\\"))

r = subprocess.run(
    ["curl.exe", "--ssl-no-revoke", "-sS", "--retry", "2", "--retry-delay", "3",
     "-X", "POST", "--config", "-"],
    input=cfg, capture_output=True, text=True, encoding="utf-8", errors="ignore",
)

asset = None
try:
    asset = json.loads(r.stdout or "")
except Exception:
    asset = None

if not asset or asset.get("state") != "uploaded" or asset.get("size") != len(data):
    note("[错误] 上传失败或附件不完整：")
    note((r.stdout or "")[:400])
    note((r.stderr or "")[:400])
    if asset:
        note("  服务器报 size=%s，本地是 %d 字节，不一致。" % (asset.get("size"), len(data)))
    # 回滚：删掉刚建的空 Release，避免留下"有版本、没附件"的坑
    try:
        api(API + "/releases/%s" % rel_id, method="DELETE")
        note("  已删掉刚创建的空 Release（%s），修复后重跑本脚本即可。" % tag)
    except Exception as e2:
        note("  自动回滚失败（%s），请到网页手动删除这个 Release。" % e2)
    sys.exit(5)

note("  附件 state=%s  size=%d bytes" % (asset["state"], asset["size"]))
note("\n完成：%s" % rel["html_url"])
note("下载直链：https://github.com/%s/releases/download/%s/%s" % (REPO, tag, ASSET_NAME))
