@echo off
chcp 65001 >nul
REM ============================================================
REM DRPlatform 全自动化测试 (Windows)
REM 直接调用 Maven 3.9.9 的 classworlds 启动器（绕过损坏的系统 mvn）
REM 用法: 双击或在项目根目录执行 run-tests.bat
REM ============================================================
cd /d %~dp0

set MVN_HOME=D:\software\apache-maven-3.9.9
set CW=%MVN_HOME%\boot\plexus-classworlds-2.8.0.jar
set MCONF=%MVN_HOME%\bin\m2.conf

if not exist "%CW%" (
  echo [ERROR] 未找到 Maven: %CW%
  echo 请确认已安装/解压 Maven 3.9.9 到 D:\software\apache-maven-3.9.9
  pause
  exit /b 1
)

echo [INFO] 运行全自动化测试 (JUnit5 集成测试)...
java -classpath "%CW%" ^
  "-Dclassworlds.conf=%MCONF%" ^
  "-Dmaven.home=%MVN_HOME%" ^
  "-Dmaven.multiModuleProjectDirectory=%CD%" ^
  "-Dlibrary.jansi.path=%MVN_HOME%\lib\jansi-native" ^
  org.codehaus.plexus.classworlds.launcher.Launcher test

set RC=%ERRORLEVEL%
if %RC%==0 (
  echo.
  echo [SUCCESS] 全部测试通过 ✅
) else (
  echo.
  echo [FAIL] 测试存在失败用例，请查看上方输出 (RC=%RC%)
)
pause
exit /b %RC%
