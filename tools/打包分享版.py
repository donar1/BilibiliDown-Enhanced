# -*- coding: utf-8 -*-
"""
把当前 BilibiliDown 整理成一个"干净的、可直接分享"的目录 + zip。

保留：主程序 jar、ffmpeg、JRE、补丁 classes/src、启动 bat、记录工具脚本、配置出厂模板
剔除：全部下载视频、登录凭证(cookies.config)、本机设备指纹、个人台账/断点/超大缓存

用法：python 打包分享版.py
输出：dist\BilibiliDown-补丁版\  以及  dist\BilibiliDown-补丁版.zip
"""
import os
import shutil
import sys
import zipfile

ROOT = os.path.dirname(os.path.abspath(__file__))
DIST_ROOT = os.path.join(ROOT, "dist")
DIST = os.path.join(DIST_ROOT, "BilibiliDown-补丁版")
ZIP_PATH = os.path.join(DIST_ROOT, "BilibiliDown-补丁版.zip")

log = []


def note(msg):
    log.append(msg)
    print(msg)


def copy_file(rel_src, rel_dst=None):
    src = os.path.join(ROOT, rel_src)
    dst = os.path.join(DIST, rel_dst or rel_src)
    if not os.path.exists(src):
        note("  [跳过] 源不存在: " + rel_src)
        return
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copy2(src, dst)
    note("  + %-52s %8.1f KB" % (rel_dst or rel_src, os.path.getsize(dst) / 1024.0))


def copy_dir(rel_src, rel_dst=None):
    src = os.path.join(ROOT, rel_src)
    dst = os.path.join(DIST, rel_dst or rel_src)
    if not os.path.isdir(src):
        note("  [跳过] 目录不存在: " + rel_src)
        return
    shutil.copytree(src, dst)
    total = sum(os.path.getsize(os.path.join(r, f)) for r, _, fs in os.walk(dst) for f in fs)
    note("  + %-52s %8.1f MB (目录)" % (rel_dst or rel_src, total / 1024.0 / 1024.0))


def write_text(rel_dst, text, bom=False):
    dst = os.path.join(DIST, rel_dst)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    with open(dst, "w", encoding="utf-8-sig" if bom else "utf-8", newline="\r\n") as f:
        f.write(text)
    note("  * %-52s 生成" % rel_dst)


# ---------------------------------------------------------------- 清空旧产物
if os.path.isdir(DIST):
    shutil.rmtree(DIST)
os.makedirs(DIST, exist_ok=True)
if os.path.exists(ZIP_PATH):
    os.remove(ZIP_PATH)

note("=" * 66)
note("打包开始 -> " + DIST)
note("=" * 66)

note("\n[1] 主程序")
copy_file("INeedBiliAV.jar")
copy_file("ffmpeg.exe")
copy_file("启动BilibiliDown.bat")
copy_file("使用说明.md")

note("\n[2] 自带 JRE（对方电脑无需装 Java）")
copy_dir(os.path.join("runtime"))

note("\n[3] 补丁")
copy_dir(os.path.join("upall-patch", "classes"), os.path.join("upall-patch", "classes"))
copy_dir(os.path.join("upall-patch", "src"), os.path.join("upall-patch", "src"))
copy_file(os.path.join("upall-patch", "界面接入说明.md"), os.path.join("upall-patch", "界面接入说明.md"))
copy_file(os.path.join("upall-patch", "改造方案.md"), os.path.join("upall-patch", "改造方案.md"))

note("\n[4] 记录工具（脚本 + 空模板，不含个人台账）")
copy_file(os.path.join("记录工具", "sync_download_record.py"),
          os.path.join("记录工具", "sync_download_record.py"))
copy_file(os.path.join("记录工具", "同步下载记录.bat"),
          os.path.join("记录工具", "同步下载记录.bat"))
copy_file(os.path.join("记录工具", "README.md"),
          os.path.join("记录工具", "README.md"))

note("\n[5] 配置（出厂模板 + 两处改良）")
copy_file(os.path.join("config", "favicon.ico"), os.path.join("config", "favicon.ico"))

cfg_src = os.path.join(ROOT, "config", "app.config.bak")
if not os.path.isfile(cfg_src):
    note("  [致命] 找不到 config/app.config.bak，无法生成干净配置")
    sys.exit(2)

with open(cfg_src, "r", encoding="utf-8-sig", newline="") as f:
    cfg = f.read()

FLAT_OLD = "bilibili.name.format = (:listName 0_listName\\)UpName\\avTitle-(:favTime favTime-)pAv2-qn"
FLAT_NEW = "bilibili.name.format = (:listName 0_listName\\)UpName\\avId-avTitle-(:favTime favTime-)pAv2-qn"
if FLAT_OLD in cfg:
    cfg = cfg.replace(FLAT_OLD, FLAT_NEW)
    note("  * name.format 已加入 avId（文件名带 BV 号，便于台账识别）")
else:
    note("  ! name.format 原文未命中，请人工检查 config/app.config.bak")

if "bilibili.alert.qualityUnexpected = true" in cfg:
    cfg = cfg.replace("bilibili.alert.qualityUnexpected = true",
                      "bilibili.alert.qualityUnexpected = false")
    note("  * qualityUnexpected 已设为 false（画质不足时降级下载而不是报错）")

# 清空设备指纹与本机 UA payload（实测清空后取详情/取链接完全正常）
import re
cfg, n1 = re.subn(r"(?m)^bilibili\.userAgent\.pc\.fingerprint.*$",
                  "bilibili.userAgent.pc.fingerprint = ", cfg)
cfg, n2 = re.subn(r"(?m)^bilibili\.userAgent\.pc\.payload.*$",
                  "bilibili.userAgent.pc.payload = ", cfg)
note("  * 已清空设备指纹 (%d 处) 与 UA payload (%d 处)" % (n1, n2))
cfg = cfg.replace("\n\n\n", "\n\n")

write_text(os.path.join("config", "app.config"), cfg, bom=False)

# 空表模板：补采记录 / 最近推送
write_text(os.path.join("记录工具", "补采记录.csv"),
           "BV号,标题,分P数,清单来源,清单页码,首次发现,最近比对,状态,尝试次数\r\n", bom=True)
write_text(os.path.join("记录工具", "最近推送.csv"),
           "BV号,标题,分P数,投递时间,qn\r\n", bom=True)

note("\n[6] 体积统计")
total = 0
for r, _, fs in os.walk(DIST):
    for f in fs:
        total += os.path.getsize(os.path.join(r, f))
note("  文件总大小 %.1f MB" % (total / 1024.0 / 1024.0))

note("\n[7] 打 zip")
with zipfile.ZipFile(ZIP_PATH, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for r, _, fs in os.walk(DIST):
        for f in fs:
            full = os.path.join(r, f)
            arc = os.path.relpath(full, DIST_ROOT).replace("\\", "/")
            z.write(full, arc)
note("  %s  %.1f MB" % (ZIP_PATH, os.path.getsize(ZIP_PATH) / 1024.0 / 1024.0))

note("\n完成。")
