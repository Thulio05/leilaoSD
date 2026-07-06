@echo off
cd /d "%~dp0"

REM ================================================================
REM  rodar_cliente.bat — Inicia o Cliente de Terminal
REM ================================================================
REM  ANTES de rodar:
REM    1. Rode compilar.bat uma vez
REM    2. Certifique-se que pelo menos o servidor primario esta rodando
REM
REM  O cliente tenta descobrir o servidor automaticamente via UDP.
REM  Se a rede bloquear o discovery, ele usa o config.properties.
REM ================================================================

echo Iniciando Cliente...
echo.

java -cp out leilao.cliente.ClienteLeilao

pause
