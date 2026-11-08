# BilibiliDown-Enhanced

B 站视频下载器 [BilibiliDown](https://github.com/nICEE1221/BilibiliDown) v6.41 的增强版。
在原版基础上以**零侵入补丁**的方式追加了三个页签：**下载记录 / UP主批量 / 补采清单**，
并自带 Java 运行时，**解压双击即用，无需安装 Java**。

原版 jar 一个字节都没有改动 —— 补丁通过 `-cp` 引导类 `UpallBoot` 在启动时注入，
补丁缺失时自动回退到原样启动。

## 直接使用（推荐）

1. 打开 [Releases](https://github.com/donar1/BilibiliDown-Enhanced/releases) 页面
2. 下载最新版本的 `BilibiliDown-Enhanced.zip`（约 34 MB）
3. 解压到任意目录（路径别含特殊字符）
4. 双击 `启动BilibiliDown.bat`
5. 首次运行若被 SmartScreen 拦，点「更多信息」→「仍要运行」

详细操作见 [使用说明.md](使用说明.md)。

## 新增的三个页签

| 页签 | 作用 |
|---|---|
| 下载记录 | 台账，所有视频的去向（在库 / 已移走 / 仅记录 / 清单），带封面缩略图 |
| UP主批量 | 输入 UID / 空间链接 / 昵称，拉取该 UP 全部投稿（带断点），勾选后批量入队 |
| 补采清单 | 每批下载结束自动比对"应下 vs 已下"，缺失项进这里，可二次补推 |

页签行形如：`主页 | 下载页 | 下载记录 | UP主批量 | 补采清单`

## 其他改动

- **按 UP 主分目录**：`download/<UP主名>/BV号-标题-pNN-画质.mp4`
- **文件名带 BV 号**：台账和补采才能稳定识别
- **画质不足不再中断**：`bilibili.alert.qualityUnexpected = false`，
  视频没有目标画质时按实际最高画质下载，而不是抛 `QualityTooLowException`
- **台账工具**：`记录工具/` 下 Python 脚本，合并下载目录 + 仓库记录 + UP 清单，
  输出带封面的 HTML 台账（需要 Python 3，仅此功能需要）

## 仓库结构

```
使用说明.md              用户手册
config/app.config        可移植配置模板（已清空设备指纹和登录信息）
src/                     补丁源码（23 个 .java，包名 nicelee.bilibili.upall）
docs/                    界面接入说明、改造方案（含每一步为什么这么改）
tools/                   打包脚本、发布脚本、台账脚本
LICENSE/                 原项目 Apache-2.0 许可证 + 第三方组件许可证
```

完整可运行包不放仓库，见 [Releases](https://github.com/donar1/BilibiliDown-Enhanced/releases)（**下载量不占 git 历史，更新也不会撑大仓库**）。

## 自己从源码打包

```bash
# 需要有 JDK 11+ 和本地已解压的原版 BilibiliDown v6.41
cd BilibiliDown
javac --release 11 -encoding UTF-8 -cp "INeedBiliAV.jar" -d "upall-patch\classes" upall-patch\src\nicelee\bilibili\upall\*.java
python 打包分享版.py
```

产物在 `dist/`。

## 技术要点

补丁不碰原 jar，靠引导类注入：

```bat
java -cp "INeedBiliAV.jar;upall-patch\classes" nicelee.bilibili.upall.UpallBoot
```

- `UpallBoot` 反射拿到 `FrameMain` 主窗口，等窗口可见后在 EDT 里追加页签
- `TabInjectHook` 直接 `addTab`，而不是 `JTabbedPane.setCorner`（后者无 `TOP_RIGHT` 常量，编译不过）
- 下载任务必须在 **EDT** 里创建 `DownloadRunnable` 对应的面板，否则任务不显示也不启动
- 补采比对以「最近一次推送清单」为基准，不是整个 UP 投稿列表，避免误点把几万条全灌进来

细节见 [docs/界面接入说明.md](docs/界面接入说明.md)。

## 许可证与来源

- 主程序 BilibiliDown v6.41 版权归原作者 nICEE 所有，遵循 **Apache-2.0**（见 [LICENSE/LICENSE.txt](LICENSE/LICENSE.txt)）
- 内置 ffmpeg、JSON.org、zxing、AV/BV 转换等第三方组件许可证见 `LICENSE/third-party/`
- 本仓库的补丁部分同样以 Apache-2.0 发布；对原项目文件的任何修改均已在 `docs/改造方案.md` 中列明
- 本仓库**不包含**任何账号凭证、设备指纹或个人下载数据；`config/app.config` 是清空后的模板

## 免责声明

仅供个人学习、研究与**自己作品的本地备份**使用。
请遵守[B 站用户协议](https://www.bilibili.com/blackboard/activity-useragreement.html)与著作权法，
不要用于批量搬运、二次传播或任何商业用途。下载内容的权利归原 UP 主所有。
