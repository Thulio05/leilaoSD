@echo off
cd /d "%~dp0"

REM ================================================================
REM  rodar_gateway_web.bat — Inicia o Gateway Web Local do Cliente
REM ================================================================
REM  Use este arquivo nos computadores clientes que querem abrir o
REM  leilao pelo navegador sem digitar o IP da maquina servidora.
REM
REM  Depois de iniciar, acesse:
REM    http://localhost:8088
REM ================================================================

echo Iniciando Gateway Web Local...
echo.
echo Depois abra no navegador deste computador:
echo   http://localhost:8088
echo.
echo Para encerrar: pressione Ctrl+C
echo.

java -cp out leilao.cliente.GatewayWebLocal

pause
