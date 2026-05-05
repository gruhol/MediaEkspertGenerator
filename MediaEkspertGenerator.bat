@echo off
chcp 65001 >nul
cd /d "%~dp0"
java -jar "C:\Users\WDabrowski\IdeaProjects\MediaEkspertGenerator\target\MediaEkspertGenerator-1.0.jar"
echo.
if exist output.xml (
    echo Gotowe! Plik output.xml zostal wygenerowany.
) else (
    echo BLAD: plik output.xml nie zostal utworzony.
)
echo.
pause