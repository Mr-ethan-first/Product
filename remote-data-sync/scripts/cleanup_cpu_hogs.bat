@echo off
title CPU cleanup - orphan python + explorer
echo ============================================
echo  CPU cleanup one-click
echo  [1] Kill orphan Anaconda python (spinning)
echo  [2] Restart explorer.exe (clear thread leak)
echo ============================================
echo.
echo [1/2] Killing orphan Anaconda python...
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter 'Name=''python.exe''' | Where-Object { $_.CommandLine -eq 'C:\mySoftware\Anaconda3\python.exe' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force; Write-Host ('  Killed orphan python PID=' + $_.ProcessId) }"
echo.
echo [2/2] Restarting explorer.exe ...
taskkill /f /im explorer.exe >nul 2>&1
echo.
echo Done. If explorer does not auto-restart, open Task Manager (Ctrl+Shift+Esc) and run: explorer
echo.
pause
