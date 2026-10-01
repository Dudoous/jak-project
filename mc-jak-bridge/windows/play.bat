@echo off
setlocal EnableExtensions
cd /d "%~dp0"
title JakCraft - Jak and Daxter x Minecraft

rem First run: build the game from YOUR Jak and Daxter disc image (takes 5-15 minutes, only once).
rem After that: just boots the game.
rem (No parenthesised blocks touch the ISO path, because names like "Jak (USA).iso" break them.)

if exist "data\jakcraft-installed.txt" goto play

echo.
echo  ===================================================================
echo   JakCraft first-time setup
echo   This needs your own Jak and Daxter PS2 disc image, an .iso file.
echo   It is extracted and compiled once; later runs start instantly.
echo  ===================================================================
echo.

set "ISO=%~1"
if not "%~1"=="" goto have_iso
echo  Drag your Jak and Daxter .iso file onto this window, then press Enter:
set /p "ISO="
if not defined ISO goto no_iso
rem strip the quotes that drag-and-drop adds
set "ISO=%ISO:"=%"

:have_iso
if exist "%ISO%" goto extract
:no_iso
echo.
echo  Could not find that file. Run play.bat again and drop the .iso file onto the window.
pause
exit /b 1

:extract
echo.
echo  Extracting and compiling... this window will show a lot of text, that's normal.
echo.
extractor.exe --proj-path "%~dp0data" --extract --decompile --compile "%ISO%"
if errorlevel 1 goto extract_failed
echo installed> "data\jakcraft-installed.txt"
echo.
echo  Setup done!
goto play

:extract_failed
echo.
echo  Setup failed - see the messages above, and the data\log folder for details.
echo  Send Claude the last 30 lines of this window.
pause
exit /b 1

:play
echo.
echo  Starting Jak. When you're in gameplay, start Minecraft with the jakbridge mod
echo  from the minecraft-mod folder and join a world: your Minecraft view drives Jak's camera.
echo  To test without Minecraft:  python tools\fake_minecraft.py look
echo.
gk.exe --proj-path "%~dp0data" -- -boot -fakeiso
if errorlevel 1 pause
endlocal
