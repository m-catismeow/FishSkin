@echo off
rem Builds every loader / Minecraft version of this branch and copies the jars to dist\.
setlocal enabledelayedexpansion
cd /d "%~dp0"
if not exist dist mkdir dist
for /d %%d in (fabric-* forge-*) do (
  if exist "%%d\gradlew.bat" (
    echo == %%d
    pushd "%%d"
    call gradlew.bat build --no-daemon -q || exit /b 1
    popd
    for %%j in ("%%d\build\libs\*.jar") do (
      set "name=%%~nj"
      if /i not "!name:~-8!"=="-sources" copy /y "%%j" dist\ >nul
    )
  )
)
echo Jars are in dist\
