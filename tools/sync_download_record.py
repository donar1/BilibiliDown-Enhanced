# -*- coding: utf-8 -*-
"""
BilibiliDown 下载记录同步器（累积台账版）
--------------------------------
用途：把你用 BilibiliDown 下载过的视频整理成可长期查询的台账。

核心原则：**记录只增不丢**。
  老版本每次都是从磁盘现状重新推算，文件一旦被移动/删除/改名，
  那一行记录就整个消失，看起来就是"一条记录都没有"。
  本版把 CSV 当作唯一真相来源：
    - 磁盘上还能找到的文件 -> 状态"在库"，补全/更新元数据
    - 曾经记录过、现在文件不在 -> 状态"已移走"，整行原样保留
    - 只在 repo.config 里有记录 -> 状态"仅记录"
    - 每次同步只追加新增行，绝不删除历史行

数据来源（三重互补）：
  1) download 目录的文件名 —— BV/av 号（BilibiliDown 命名格式已含 avId）
  2) config/repo.config —— BilibiliDown 自带的已下载仓库记录
  3) B 站公开接口 —— 补全标题、UP 主、时长、发布日期、封面

输出：
  下载记录.csv   —— 主台账（Excel 可直接筛选）
  下载记录.md    —— Markdown 摘要
  下载记录.html  —— 可搜索/可筛选/可排序的可视化台账（双击即可看）

用法：
  python sync_download_record.py                 # 正常同步（联网补全）
  python sync_download_record.py --no-api        # 离线同步，不联网
  python sync_download_record.py --download-dir "D:\\其它目录"   # 追加扫描目录，可写多次
  python sync_download_record.py --include-upall   # 把 UP 主批量任务清单也补进台账（状态=清单）
"""

import csv
import html
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime
from pathlib import Path

BASE = Path(__file__).resolve().parent
APP_DIR = BASE.parent
DEFAULT_DIRS = [APP_DIR / "download"]
REPO_FILE = APP_DIR / "config" / "repo.config"

CSV_PATH = BASE / "下载记录.csv"
MD_PATH = BASE / "下载记录.md"
HTML_PATH = BASE / "下载记录.html"
CACHE_PATH = BASE / ".meta_cache.json"
COVERS_PATH = BASE / ".covers.json"

BV_RE = re.compile(r"(BV[0-9A-Za-z]{10})")
AV_RE = re.compile(r"(?:^|[^0-9A-Za-z])av(\d{3,})", re.IGNORECASE)
TRAIL_QN_RE = re.compile(r"-(6|16|32|64|74|80|112|116|120|125|126|127)$")
TRAIL_PAGE_RE = re.compile(r"-(?:pn|p)?0*(\d{1,3})$")

VIDEO_EXT = {
    ".mp4", ".flv", ".mkv", ".m4s", ".ts", ".webm",
    ".mp3", ".m4a", ".aac", ".flac", ".wav", ".ass", ".srt", ".json",
}

QN_NAME = {
    6: "240P", 16: "360P", 32: "480P", 64: "720P", 74: "720P60",
    80: "1080P", 112: "1080P+", 116: "1080P60", 120: "4K", 125: "HDR",
    126: "杜比视界", 127: "8K",
}

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")

CSV_FIELDS = [
    "首次发现时间", "BV号", "分P", "标题", "UP主", "时长", "发布日期",
    "视频链接", "清晰度", "文件大小MB", "文件路径", "来源", "备注",
    "状态", "最近校验",
]

STATUS_IN = "在库"
STATUS_MOVED = "已移走"
STATUS_REPO = "仅记录"
STATUS_LIST = "清单"


# ----------------------------------------------------------------------------- 小工具
def now_str() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def fmt_mtime(ts: float) -> str:
    return datetime.fromtimestamp(ts).strftime("%Y-%m-%d %H:%M:%S")


def fmt_duration(sec) -> str:
    try:
        sec = int(sec)
    except (TypeError, ValueError):
        return ""
    if sec <= 0:
        return ""
    h, rem = divmod(sec, 3600)
    m, s = divmod(rem, 60)
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


def human_size(num_bytes: float) -> str:
    return f"{num_bytes / 1024 / 1024:.2f}"


# 封面单独存一个缓存文件，不污染 CSV 台账
COVERS = {}
COVERS_DIRTY = [False]


def load_covers():
    global COVERS
    if COVERS_PATH.exists():
        try:
            COVERS = json.loads(COVERS_PATH.read_text(encoding="utf-8"))
        except Exception:
            COVERS = {}


def remember_cover(bvid: str, pic: str):
    if bvid and pic and COVERS.get(bvid) != pic:
        COVERS[bvid] = pic
        COVERS_DIRTY[0] = True


def save_covers():
    if not COVERS_DIRTY[0]:
        return
    try:
        COVERS_PATH.write_text(
            json.dumps(COVERS, ensure_ascii=False, indent=0), encoding="utf-8"
        )
    except Exception:
        pass


# ----------------------------------------------------------------------------- 元数据
class MetaFetcher:
    """带本地缓存的 B 站元数据查询（避免重复请求）"""

    def __init__(self, enabled: bool = True):
        self.enabled = enabled
        self.cache = {}
        if CACHE_PATH.exists():
            try:
                self.cache = json.loads(CACHE_PATH.read_text(encoding="utf-8"))
            except Exception:
                self.cache = {}
        self.dirty = False
        self.fail = 0

    def _get(self, url: str, timeout: int = 12):
        req = urllib.request.Request(
            url, headers={"User-Agent": UA, "Referer": "https://www.bilibili.com/"}
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8", "ignore"))

    def get(self, bvid: str = "", aid: str = "") -> dict:
        key = bvid or f"av{aid}"
        cached = self.cache.get(key)
        # 旧缓存没有封面字段时补查一次
        if cached and "pic" in cached:
            return cached
        if not self.enabled or self.fail >= 5:
            return cached or {}
        url = (
            f"https://api.bilibili.com/x/web-interface/view?bvid={bvid}"
            if bvid
            else f"https://api.bilibili.com/x/web-interface/view?aid={aid}"
        )
        try:
            data = self._get(url)
            if data.get("code") != 0:
                raise ValueError(f"code={data.get('code')} {data.get('message')}")
            d = data["data"]
            meta = {
                "bvid": d.get("bvid", bvid),
                "aid": str(d.get("aid", aid)),
                "title": d.get("title", ""),
                "owner": (d.get("owner") or {}).get("name", ""),
                "duration": d.get("duration", 0),
                "pubdate": datetime.fromtimestamp(d.get("pubdate", 0)).strftime("%Y-%m-%d")
                if d.get("pubdate") else "",
                "pages": len(d.get("pages") or []),
                "pic": d.get("pic", ""),
            }
            self.cache[key] = meta
            self.dirty = True
            time.sleep(0.35)  # 温和限速，别把接口打急
            return meta
        except Exception as exc:  # 网络/接口异常一律降级，不阻断台账生成
            self.fail += 1
            print(f"  [警告] 元数据查询失败 {key}: {exc}")
            return cached or {}

    def save(self):
        if self.dirty:
            try:
                CACHE_PATH.write_text(
                    json.dumps(self.cache, ensure_ascii=False, indent=1), encoding="utf-8"
                )
            except Exception:
                pass


# ----------------------------------------------------------------------------- 解析
def parse_filename(stem: str):
    """从文件名解析 (bvid, aid, 标题, 分P, 清晰度)

    BilibiliDown 当前命名格式：BV号-av标题-p01-清晰度值
    例：BV1GJ411x7h7-某个标题-p01-80
    """
    bvid, aid = "", ""
    rest = stem

    m = BV_RE.search(stem)
    if m:
        bvid = m.group(1)
        rest = stem.replace(bvid, "", 1)
    else:
        m2 = AV_RE.search(stem)
        if m2:
            aid = m2.group(1)
            rest = stem[: m2.start(1)] + stem[m2.end():]

    rest = rest.strip(" -_")

    qn = ""
    mq = TRAIL_QN_RE.search(rest)
    if mq:
        qn = QN_NAME.get(int(mq.group(1)), mq.group(1))
        rest = rest[: mq.start()]

    page = ""
    mp = TRAIL_PAGE_RE.search(rest)
    if mp:
        page = str(int(mp.group(1)))
        rest = rest[: mp.start()]

    rest = rest.strip(" -_")
    title = rest if rest else stem
    return bvid, aid, title, page, qn


def scan_dirs(dirs):
    """扫描下载目录，返回条目列表"""
    items = []
    for d in dirs:
        if not d.exists():
            continue
        for f in d.rglob("*"):
            try:
                if not f.is_file():
                    continue
            except OSError:
                continue
            if f.suffix.lower() not in VIDEO_EXT:
                continue
            if f.name.endswith((".part", ".tmp", ".download", ".r.txt", ".emule.td")):
                continue
            stem = f.stem
            for tag in (".audio", ".video", ".dash"):
                if stem.lower().endswith(tag):
                    stem = stem[: -len(tag)]
            bvid, aid, title, page, qn = parse_filename(stem)
            if not bvid and not aid:
                continue
            try:
                st = f.stat()
            except OSError:
                continue
            items.append({
                "bvid": bvid,
                "aid": aid,
                "title": title,
                "page": page,
                "qn": qn,
                "size": st.st_size,
                "mtime": st.st_mtime,
                "path": str(f),
            })
    return items


def scan_repo():
    """读取 BilibiliDown 自带仓库记录（repo.config），兜底已下载但文件已移动的条目"""
    if not REPO_FILE.exists():
        return []
    try:
        raw = REPO_FILE.read_text(encoding="utf-8", errors="ignore")
    except Exception:
        return []
    if not raw.strip():
        return []

    ids = []
    ids += [("bvid", m.group(1)) for m in BV_RE.finditer(raw)]
    ids += [
        ("aid", m.group(1))
        for m in re.finditer(r"(?<![0-9A-Za-z])av?(\d{6,})", raw, re.IGNORECASE)
    ]

    seen, out = set(), []
    for kind, val in ids:
        key = f"{kind}:{val}"
        if key in seen:
            continue
        seen.add(key)
        out.append((kind, val))
    return out


UPALL_DIR = APP_DIR / "config" / "upall"


def scan_upall():
    """读取 UP 主批量下载任务留下的清单（checkpoint_*.json）

    注意：这是"任务清单"而不是"下载完成"的凭证，只作为历史参考导入，
    台账里统一标为"清单"，不冒充"在库"。
    """
    items, seen = [], set()
    if not UPALL_DIR.exists():
        return items
    for f in sorted(UPALL_DIR.glob("checkpoint_*.json")):
        try:
            data = json.loads(f.read_text(encoding="utf-8", errors="ignore"))
        except Exception:
            continue
        mtime = f.stat().st_mtime
        for it in data.get("items") or []:
            bvid = (it.get("bvid") or "").strip()
            if not bvid or bvid in seen:
                continue
            seen.add(bvid)
            created = it.get("created") or 0
            items.append({
                "bvid": bvid,
                "aid": str(it.get("aid") or ""),
                "title": it.get("title") or "",
                "duration": it.get("duration") or 0,
                "pubdate": datetime.fromtimestamp(created).strftime("%Y-%m-%d")
                if created else "",
                "pic": it.get("pic") or "",
                "mtime": mtime,
            })
    return items


# ----------------------------------------------------------------------------- 合并
def load_existing():
    rows = {}
    if not CSV_PATH.exists():
        return rows
    try:
        with CSV_PATH.open("r", encoding="utf-8-sig", newline="") as fh:
            for row in csv.DictReader(fh):
                bv = (row.get("BV号") or "").strip()
                if not bv:
                    continue
                page = (row.get("分P") or "").strip()
                rows[(bv, page)] = {k: (row.get(k) or "") for k in CSV_FIELDS}
    except Exception as exc:
        print(f"  [警告] 旧台账读取失败（本次将只写新记录）：{exc}")
    return rows


def collect_current(fetcher):
    """按 (BV号, 分P) 汇总本次从磁盘/仓库看到的条目"""
    files = scan_dirs(SCAN_DIRS)
    repo_ids = scan_repo()

    grouped = {}
    for it in files:
        key = (it["bvid"] or f"av{it['aid']}", it["page"])
        grouped.setdefault(key, []).append(it)

    for kind, val in repo_ids:
        key = (val, "") if kind == "bvid" else (f"av{val}", "")
        if any(k[0] == key[0] for k in grouped):
            continue
        grouped.setdefault(key, [])

    rows = []
    for (ident, page), items in grouped.items():
        bvid = ident if ident.startswith("BV") else ""
        aid = ident[2:] if ident.startswith("av") else ""

        meta = fetcher.get(bvid=bvid, aid=aid)
        if meta.get("bvid"):
            bvid = meta["bvid"]
        if meta.get("aid"):
            aid = meta["aid"]

        if items:
            biggest = max(items, key=lambda x: x["size"])
            title = meta.get("title") or biggest["title"]
            qn = biggest["qn"]
            size_mb = human_size(sum(i["size"] for i in items))
            path = biggest["path"]
            first_seen = fmt_mtime(min(i["mtime"] for i in items))
            source = "下载目录"
        else:
            title = meta.get("title") or "(文件已不在下载目录，仅仓库有记录)"
            qn, size_mb, path = "", "", ""
            first_seen = now_str()
            source = "仓库记录"

        link = f"https://www.bilibili.com/video/{bvid}" if bvid else (
            f"https://www.bilibili.com/video/av{aid}" if aid else ""
        )
        if link and page and page != "1":
            link += f"?p={page}"

        rows.append({
            "首次发现时间": first_seen,
            "BV号": bvid or f"av{aid}",
            "分P": page,
            "标题": title,
            "UP主": meta.get("owner", ""),
            "时长": fmt_duration(meta.get("duration")),
            "发布日期": meta.get("pubdate", ""),
            "视频链接": link,
            "清晰度": qn,
            "文件大小MB": size_mb,
            "文件路径": path,
            "来源": source,
            "备注": "" if items else "文件已移动或删除",
            "封面": meta.get("pic", ""),
        })
    return rows, files, repo_ids


def merge(existing, current):
    """把本次看到的条目合并进历史台账：只增不删"""
    now = now_str()
    out, new_rows, matched = [], [], set()

    for row in current:
        bv, page = row["BV号"], row["分P"]
        old = existing.get((bv, page))
        if old is None and page:
            old = existing.get((bv, ""))
        if old is not None:
            matched.add((bv, page))
            matched.add((old.get("BV号", bv), old.get("分P", page)))
            # 首次记录时间永远沿用最早的那次
            row["首次发现时间"] = old.get("首次发现时间") or row["首次发现时间"]
            # 交叉补齐：接口拿不到时用历史值
            for f in ("标题", "UP主", "时长", "发布日期", "封面"):
                if not row.get(f) and old.get(f):
                    row[f] = old[f]
        else:
            new_rows.append(row)
        row["状态"] = STATUS_IN
        row["最近校验"] = now
        out.append(row)

    for key, old in existing.items():
        if key in matched:
            continue
        # 只有"分P未知"的仓库行才允许被同一视频的更精确记录吸收，
        # 其余历史行一律保留，保证只增不丢
        if key[1] == "" and any(r["BV号"] == key[0] for r in out):
            continue
        old = dict(old)
        if old.get("文件路径"):
            old["状态"] = STATUS_MOVED
            old["备注"] = old.get("备注") or "文件已不在下载目录（历史记录保留）"
        elif old.get("状态") == STATUS_LIST:
            old["状态"] = STATUS_LIST
        else:
            old["状态"] = STATUS_REPO
            old["备注"] = old.get("备注") or "仅有仓库记录"
        old["最近校验"] = now
        out.append(old)

    for r in out:
        if r.get("封面"):
            remember_cover(r["BV号"], r["封面"])
        else:
            r["封面"] = COVERS.get(r["BV号"], "")

    out.sort(key=lambda r: (r.get("首次发现时间", ""), r.get("标题", "")))
    out.reverse()
    new_rows.sort(key=lambda r: (r.get("首次发现时间", ""), r.get("标题", "")), reverse=True)
    return out, new_rows


def merge_upall(rows, items):
    """把 UP 主批量任务清单里、台账中还没有的条目补进来（状态=清单）"""
    have = {r["BV号"] for r in rows}
    added = []
    now = now_str()
    for it in items:
        remember_cover(it["bvid"], it["pic"])
        if it["bvid"] in have:
            continue
        have.add(it["bvid"])
        row = {
            "首次发现时间": fmt_mtime(it["mtime"]),
            "BV号": it["bvid"],
            "分P": "",
            "标题": it["title"],
            "UP主": "",
            "时长": fmt_duration(it["duration"]),
            "发布日期": it["pubdate"],
            "视频链接": f"https://www.bilibili.com/video/{it['bvid']}",
            "清晰度": "",
            "文件大小MB": "",
            "文件路径": "",
            "来源": "UP批量清单",
            "备注": "来自 UP 主批量任务清单，未核对本地文件",
            "状态": STATUS_LIST,
            "最近校验": now,
            "封面": it["pic"],
        }
        rows.append(row)
        added.append(row)
        remember_cover(it["bvid"], it["pic"])
    return added


# ----------------------------------------------------------------------------- 输出
def write_csv(rows):
    try:
        with CSV_PATH.open("w", encoding="utf-8-sig", newline="") as fh:
            w = csv.DictWriter(fh, fieldnames=CSV_FIELDS, extrasaction="ignore")
            w.writeheader()
            for r in rows:
                w.writerow({k: r.get(k, "") for k in CSV_FIELDS})
        return True
    except PermissionError:
        print(f"  [警告] {CSV_PATH.name} 正被其它程序占用（可能 Excel 开着），本次台账未更新。")
        print("         请关闭 Excel 后重新同步一次；Markdown/HTML 仍会正常生成。")
        return False


def status_counts(rows):
    c = {STATUS_IN: 0, STATUS_MOVED: 0, STATUS_REPO: 0, STATUS_LIST: 0}
    for r in rows:
        c[r.get("状态", STATUS_IN)] = c.get(r.get("状态", STATUS_IN), 0) + 1
    return c


def total_size(rows):
    s = 0.0
    for r in rows:
        if r.get("状态") != STATUS_IN:
            continue
        try:
            s += float(r.get("文件大小MB") or 0)
        except ValueError:
            pass
    return s


def write_md(rows, new_rows):
    cnt = status_counts(rows)
    lines = [
        "# BilibiliDown 下载记录",
        "",
        f"- 生成时间：{now_str()}",
        f"- 记录条目：**{len(rows)}** 条（本次新增 {len(new_rows)} 条）",
        f"- 明细：在库 {cnt[STATUS_IN]} 条 ｜ 已移走 {cnt[STATUS_MOVED]} 条 ｜ 仅记录 {cnt[STATUS_REPO]} 条 ｜ 清单 {cnt[STATUS_LIST]} 条",
        f"- 在库占用：约 {total_size(rows):.2f} MB",
        f"- Excel 台账：`{CSV_PATH}`",
        f"- 网页台账：`{HTML_PATH}`",
        "",
    ]
    if new_rows:
        lines += ["## 本次新增", "", "| 时间 | 标题 | UP主 | 链接 |", "| --- | --- | --- | --- |"]
        for r in new_rows:
            lines.append(f"| {r['首次发现时间']} | {md_cell(r['标题'])} | {md_cell(r['UP主'])} | {r['视频链接']} |")
        lines.append("")

    lines += [
        "## 全部记录",
        "",
        "| 首次记录 | 标题 | UP主 | 分P | 清晰度 | 大小(MB) | 状态 | 链接 |",
        "| --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    for r in rows:
        lines.append(
            f"| {r.get('首次发现时间','')} | {md_cell(r.get('标题',''))} | {md_cell(r.get('UP主',''))} "
            f"| {r.get('分P','')} | {r.get('清晰度','')} | {r.get('文件大小MB','')} "
            f"| {r.get('状态','')} | {r.get('视频链接','')} |"
        )
    try:
        MD_PATH.write_text("\n".join(lines) + "\n", encoding="utf-8")
    except PermissionError:
        print(f"  [警告] {MD_PATH.name} 被占用，跳过。")


def md_cell(s):
    return (s or "").replace("|", "\\|").replace("\n", " ")


def write_html(rows, new_rows):
    cnt = status_counts(rows)
    total = len(rows)
    new_keys = {(r["BV号"], r["分P"]) for r in new_rows}

    body = []
    for r in rows:
        st = r.get("状态", STATUS_IN)
        cls = {STATUS_IN: "in", STATUS_MOVED: "moved", STATUS_REPO: "repo", STATUS_LIST: "list"}.get(st, "in")
        cover = r.get("封面") or ""
        cover_html = (
            f'<img class="cover" loading="lazy" referrerpolicy="no-referrer" '
            f'src="{html.escape(cover)}" alt="" '
            f"onerror=\"this.style.visibility='hidden'\">"
            if cover else '<span class="cover ph"></span>'
        )
        title = html.escape(r.get("标题", "") or "(无标题)")
        link = r.get("视频链接", "") or ""
        title_html = (
            f'<a href="{html.escape(link)}" target="_blank" rel="noopener">{title}</a>'
            if link else title
        )
        path = r.get("文件路径", "") or ""
        path_html = f'<span class="path" title="{html.escape(path)}">{html.escape(path)}</span>' if path else '<span class="muted">—</span>'
        badge = "new" if (r["BV号"], r["分P"]) in new_keys else ""
        body.append(
            "<tr class='{cls}'>"
            "<td class='c-cover'>{cover}{badge}</td>"
            "<td class='c-time'>{time}</td>"
            "<td class='c-title'>{title}</td>"
            "<td class='c-up'>{up}</td>"
            "<td class='c-p'>{p}</td>"
            "<td class='c-qn'>{qn}</td>"
            "<td class='c-size'>{size}</td>"
            "<td class='c-st'><span class='st {cls}'>{st}</span></td>"
            "<td class='c-path'>{path}</td>"
            "</tr>".format(
                cls=cls,
                cover=cover_html,
                badge="<i class='nb'>新增</i>" if badge else "",
                time=html.escape(r.get("首次发现时间", "")),
                title=title_html,
                up=html.escape(r.get("UP主", "")),
                p=html.escape(r.get("分P", "") or "—"),
                qn=html.escape(r.get("清晰度", "") or "—"),
                size=html.escape(r.get("文件大小MB", "") or "—"),
                st=st,
                path=path_html,
            )
        )

    doc = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>BilibiliDown 下载记录</title>
<style>
:root{--bg:#0f1114;--card:#171a1f;--line:#262b32;--txt:#e6e8eb;--dim:#8b939e;--acc:#00a1d6;--ok:#3fb950;--warn:#d29922;--gray:#6e7681}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--txt);font:14px/1.6 "Microsoft YaHei","Segoe UI",system-ui,sans-serif}
header{padding:20px 24px 8px}
h1{margin:0 0 6px;font-size:20px;font-weight:600}
.meta{color:var(--dim);font-size:13px}
.bar{display:flex;flex-wrap:wrap;gap:10px;align-items:center;padding:12px 24px}
input[type=search]{flex:1 1 260px;min-width:200px;padding:9px 12px;border-radius:8px;border:1px solid var(--line);background:var(--card);color:var(--txt);outline:none}
input[type=search]:focus{border-color:var(--acc)}
.chip{padding:7px 12px;border-radius:8px;border:1px solid var(--line);background:var(--card);color:var(--dim);cursor:pointer;user-select:none}
.chip.on{color:#fff;border-color:var(--acc);background:rgba(0,161,214,.18)}
.wrap{padding:0 16px 28px;overflow-x:auto}
table{width:100%;border-collapse:collapse;background:var(--card);border-radius:10px;overflow:hidden}
thead th{position:sticky;top:0;z-index:2;background:#1e2228;color:var(--dim);font-weight:500;text-align:left;padding:10px 10px;border-bottom:1px solid var(--line);cursor:pointer;white-space:nowrap}
thead th:hover{color:var(--txt)}
tbody td{padding:8px 10px;border-bottom:1px solid #202429;vertical-align:middle}
tbody tr:hover{background:#1b1f25}
a{color:var(--acc);text-decoration:none}
a:hover{text-decoration:underline}
.cover{width:96px;height:54px;object-fit:cover;border-radius:6px;background:#222;display:block}
.cover.ph{display:block}
.c-cover{width:104px;position:relative}
.nb{position:absolute;left:4px;top:4px;font-style:normal;font-size:11px;background:var(--ok);color:#04210c;border-radius:4px;padding:0 4px}
.c-time{width:140px;color:var(--dim);font-size:12.5px;white-space:nowrap}
.c-title{min-width:240px;width:auto}
.c-title a{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;line-height:1.45}
.c-up{width:104px;color:var(--dim);font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.c-p{width:52px;text-align:center;color:var(--dim);white-space:nowrap}
.c-qn{width:78px;color:var(--dim);font-size:13px;white-space:nowrap}
.c-size{width:80px;text-align:right;color:var(--dim);font-variant-numeric:tabular-nums;white-space:nowrap}
.c-st{width:70px}
.st{display:inline-block;padding:2px 8px;border-radius:6px;font-size:12px;white-space:nowrap}
.st.in{background:rgba(63,185,80,.16);color:var(--ok)}
.st.moved{background:rgba(210,153,34,.16);color:var(--warn)}
.st.repo{background:rgba(110,118,129,.18);color:var(--gray)}
.st.list{background:rgba(88,166,255,.14);color:#7cb7ff}
.c-path{width:200px;max-width:200px}
.path{display:block;color:var(--dim);font-size:12px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.muted{color:var(--gray)}
footer{padding:0 24px 30px;color:var(--gray);font-size:12px}
.empty{padding:28px;text-align:center;color:var(--dim)}
@media (max-width:1200px){.c-path{display:none}}
@media (max-width:980px){.c-cover{display:none}.c-title{min-width:0}}
</style>
</head>
<body>
<header>
<h1>BilibiliDown 下载记录</h1>
<div class="meta">生成时间 __NOW__ ｜ 共 <b>__TOTAL__</b> 条 ｜ 在库 <b>__INB__</b> ｜ 已移走 <b>__MOVED__</b> ｜ 仅记录 <b>__REPO__</b> ｜ 清单 <b>__LIST__</b> ｜ 在库占用约 <b>__SIZE__ MB</b></div>
</header>
<div class="bar">
<input id="q" type="search" placeholder="搜索标题 / UP主 / BV号 / 路径 …">
<span class="chip on" data-st="all">全部</span>
<span class="chip" data-st="in">在库</span>
<span class="chip" data-st="moved">已移走</span>
<span class="chip" data-st="repo">仅记录</span>
<span class="chip" data-st="list">清单</span>
<span class="meta" id="cnt"></span>
</div>
<div class="wrap">
<table id="tb">
<thead><tr>
<th class="c-cover" data-k="0">封面</th><th class="c-time" data-k="1">首次记录</th><th class="c-title" data-k="2">标题</th><th class="c-up" data-k="3">UP主</th>
<th class="c-p" data-k="4">分P</th><th class="c-qn" data-k="5">清晰度</th><th class="c-size" data-k="6">大小(MB)</th><th class="c-st" data-k="7">状态</th><th class="c-path" data-k="8">文件路径</th>
</tr></thead>
<tbody>
__ROWS__
</tbody>
</table>
</div>
<footer>台账文件：下载记录.csv ｜ 本页由 sync_download_record.py 自动生成，可反复同步，历史记录只增不丢。</footer>
<script>
const q=document.getElementById('q'),tb=document.getElementById('tb'),cnt=document.getElementById('cnt');
const rows=[...tb.tBodies[0].rows];
let stFilter='all';
function apply(){
  const kw=q.value.trim().toLowerCase();let n=0;
  for(const r of rows){
    const txt=r.innerText.toLowerCase();
    const ok=(stFilter==='all'||r.classList.contains(stFilter))&&(!kw||txt.includes(kw));
    r.style.display=ok?'':'none';if(ok)n++;
  }
  cnt.textContent='显示 '+n+' / '+rows.length+' 条';
}
q.addEventListener('input',apply);
document.querySelectorAll('.chip').forEach(c=>c.addEventListener('click',()=>{
  document.querySelectorAll('.chip').forEach(x=>x.classList.remove('on'));
  c.classList.add('on');stFilter=c.dataset.st;apply();
}));
apply();
let dir={};
document.querySelectorAll('thead th').forEach(th=>th.addEventListener('click',()=>{
  const k=+th.dataset.k;dir[k]=!dir[k];const sign=dir[k]?1:-1;
  const val=r=>{const t=r.cells[k]?r.cells[k].innerText.trim():'';const f=parseFloat(t.replace(/,/g,''));return isNaN(f)?t.toLowerCase():f;};
  rows.sort((a,b)=>{const x=val(a),y=val(b);return (x>y?1:x<y?-1:0)*sign;});
  const tb2=tb.tBodies[0];rows.forEach(r=>tb2.appendChild(r));
}));
</script>
</body>
</html>
"""
    rows_html = "\n".join(body) if body else (
        "<tr><td colspan='9' class='empty'>还没有记录。用 BilibiliDown 下载一个视频后再同步一次。</td></tr>"
    )
    doc = (doc
           .replace("__NOW__", now_str())
           .replace("__TOTAL__", str(total))
           .replace("__INB__", str(cnt[STATUS_IN]))
           .replace("__MOVED__", str(cnt[STATUS_MOVED]))
           .replace("__REPO__", str(cnt[STATUS_REPO]))
           .replace("__LIST__", str(cnt[STATUS_LIST]))
           .replace("__SIZE__", f"{total_size(rows):.1f}")
           .replace("__ROWS__", rows_html))
    try:
        HTML_PATH.write_text(doc, encoding="utf-8")
    except PermissionError:
        print(f"  [警告] {HTML_PATH.name} 被占用，跳过。")


# ----------------------------------------------------------------------------- 主流程
SCAN_DIRS = list(DEFAULT_DIRS)


def parse_args(argv):
    dirs = list(DEFAULT_DIRS)
    no_api = False
    upall = False
    i = 1
    while i < len(argv):
        a = argv[i]
        if a == "--no-api":
            no_api = True
        elif a in ("--include-upall", "--upall"):
            upall = True
        elif a in ("--download-dir", "--dir") and i + 1 < len(argv):
            extra = Path(argv[i + 1])
            if extra not in dirs:
                dirs.append(extra)
            i += 1
        elif a in ("-h", "--help"):
            print(__doc__)
            sys.exit(0)
        else:
            print(f"[提示] 忽略未知参数：{a}")
        i += 1
    return dirs, no_api, upall


def main():
    global SCAN_DIRS
    dirs, no_api, include_upall = parse_args(sys.argv)
    SCAN_DIRS = dirs

    for d in dirs:
        if not d.exists():
            print(f"提示：下载目录尚不存在（{d}），本次只读取仓库记录。")

    fetcher = MetaFetcher(enabled=not no_api)
    load_covers()
    existing = load_existing()
    current, files, repo_ids = collect_current(fetcher)
    rows, new_rows = merge(existing, current)

    if include_upall:
        added = merge_upall(rows, scan_upall())
        if added:
            rows.sort(key=lambda r: (r.get("首次发现时间", ""), r.get("标题", "")), reverse=True)
            new_rows.extend(added)
            new_rows.sort(key=lambda r: (r.get("首次发现时间", ""), r.get("标题", "")), reverse=True)
        print(f"从 UP 主批量任务清单补入 {len(added)} 条（状态=清单）")

    for r in rows:
        if not r.get("封面"):
            r["封面"] = COVERS.get(r["BV号"], "")

    fetcher.save()
    save_covers()

    print(f"磁盘文件 {len(files)} 个，仓库记录 {len(repo_ids)} 条，历史上已有 {len(existing)} 条")
    ok = write_csv(rows)
    write_md(rows, new_rows)
    write_html(rows, new_rows)

    cnt = status_counts(rows)
    print("")
    if ok:
        print(f"已写入：{CSV_PATH}")
    print(f"已写入：{MD_PATH}")
    print(f"已写入：{HTML_PATH}")
    print(f"台账共 {len(rows)} 条（在库 {cnt[STATUS_IN]}｜已移走 {cnt[STATUS_MOVED]}｜仅记录 {cnt[STATUS_REPO]}｜清单 {cnt[STATUS_LIST]}），本次新增 {len(new_rows)} 条。")
    if new_rows:
        print("")
        print("本次新增：")
        for r in new_rows[:20]:
            print(f"  + {r['标题']}  {r['视频链接']}")
        if len(new_rows) > 20:
            print(f"  ... 其余 {len(new_rows) - 20} 条见台账")


if __name__ == "__main__":
    main()
