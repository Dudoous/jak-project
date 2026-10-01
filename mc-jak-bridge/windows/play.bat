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
rem rip_collision also exports Jak's level collision as .obj files; the Minecraft mod turns them
rem into invisible blocks so Steve can walk on Jak's ground.
extractor.exe --proj-path "%~dp0data" --extract --decompile --compile --decomp-config-override "{\"rip_collision\": true}" "%ISO%"
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
rem tell the Minecraft mod where this folder is (it reads Jak's collision from here)
if not exist "%APPDATA%\JakCraft" mkdir "%APPDATA%\JakCraft"
> "%APPDATA%\JakCraft\location.txt" echo %~dp0
echo.
echo  Starting Jak. Load your save and get into gameplay, then start Minecraft with the
echo  jakbridge mod and join a Superflat "The Void" world. You play in the Minecraft window;
echo  keep this Jak window open behind it, not minimised.
echo.
gk.exe --proj-path "%~dp0data" -- -boot -fakeiso
if errorlevel 1 pause
endlocal
