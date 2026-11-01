@echo off
setlocal
cd /d "%~dp0"
where git >nul 2>&1
if errorlevel 1 (
  echo Git for Windows is not installed.
  echo Install Git from https://git-scm.com/downloads/win and rerun this file.
  pause
  exit /b 1
)
echo Publishing Melody Trail to mohammaddhameri-blip/melody-trail ...
git init
if errorlevel 1 goto fail
git remote get-url origin >nul 2>&1
if errorlevel 1 (
  git remote add origin https://github.com/mohammaddhameri-blip/melody-trail.git
) else (
  git remote set-url origin https://github.com/mohammaddhameri-blip/melody-trail.git
)
git checkout -B main
if errorlevel 1 goto fail
git add index.html README.md PUBLISH_TO_GITHUB_WINDOWS.bat android .github
if errorlevel 1 goto fail
git diff --cached --quiet
if errorlevel 1 (
  git commit -m "Publish redesigned Melody Trail and Android APK builder"
  if errorlevel 1 (
    echo Please configure your Git name and email if Git asks for them.
    goto fail
  )
)
git push -u origin main
if errorlevel 1 goto fail
echo.
echo SUCCESS! Visit:
echo https://github.com/mohammaddhameri-blip/melody-trail
echo The APK is built automatically in GitHub Actions.
echo Enable GitHub Pages in repository Settings ^> Pages ^> Source: GitHub Actions.
pause
exit /b 0
:fail
echo.
echo GitHub publishing did not finish. Read the error above.
pause
exit /b 1
