@echo off
cd /d "%~dp0"

REM ================================================================
REM  compilar.bat — Compila todo o projeto de uma vez
REM ================================================================

echo Compilando o projeto...

if not exist out mkdir out

javac -encoding UTF-8 -d out src\leilao\config\*.java src\leilao\dominio\*.java src\leilao\persistencia\*.java src\leilao\servidor\*.java src\leilao\cliente\*.java

if %errorlevel% == 0 (
    echo.
    echo COMPILACAO OK! Agora voce pode rodar:
    echo.
    echo   Para o servidor primario:  rodar_primario.bat
    echo   Para o servidor replica:   rodar_replica.bat
    echo   Para o cliente:            rodar_cliente.bat
    echo   Para o navegador cliente:  rodar_gateway_web.bat
    echo.
) else (
    echo.
    echo ERRO NA COMPILACAO. Verifique se o Java esta instalado:
    echo   java -version
    echo.
)

pause
