@echo off
cd /d "%~dp0"
where py >nul 2>nul
if %errorlevel%==0 (
    py SIM_Instant_Share_AllInOne.py
    goto :eof
)
python SIM_Instant_Share_AllInOne.py
if %errorlevel% neq 0 pause
