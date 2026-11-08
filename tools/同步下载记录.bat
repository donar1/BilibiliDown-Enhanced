@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"
title BilibiliDown 下载记录同步
echo ==========================================
echo    BilibiliDown 下载记录  同步中...
echo ==========================================
echo.

set PY=
where py >nul 2>nul && set PY=py -3
if not defined PY (
    where python >nul 2>nul && set PY=python
)
if not defined PY (
    for %%D in ("%LOCALAPPDATA%\Programs\Python\Python313\python.exe" "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" "%LOCALAPPDATA%\Programs\Python\Python311\python.exe" "C:\Python313\python.exe" "C:\Python312\python.exe" "D:\Python\python.exe") do (
        if not defined PY if exist "%%~D" set PY="%%~D"
    )
)
if not defined PY (
    echo [错误] 没找到可用的 Python 3。
    echo        请先安装 Python 3，安装时勾选 Add python.exe to PATH。
    echo.
    pause
    exit /b 1
)

%PY% "%~dp0sync_download_record.py" %*
if errorlevel 1 (
    echo.
    echo [错误] 同步失败，请把上面的报错信息发给维护者。
    echo.
    pause
    exit /b 1
)

echo.
echo 正在打开台账页面...
if not defined BILI_NOOPEN if exist "%~dp0下载记录.html" start "" "%~dp0下载记录.html"
echo.
echo 台账文件：下载记录.html（网页查看）  下载记录.csv（Excel 筛选）
if not defined BILI_NOOPEN (
    echo 窗口 8 秒后自动关闭，按任意键立即关闭。
    timeout /t 8 >nul
)
exit /b 0