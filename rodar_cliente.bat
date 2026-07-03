@echo off
REM ================================================================
REM  rodar_cliente.bat — Inicia o Cliente de Terminal
REM ================================================================
REM  ANTES de rodar:
REM    1. Edite o arquivo "config.properties" com os IPs corretos
REM    2. Rode compilar.bat uma vez
REM    3. Certifique-se que pelo menos o servidor primario esta rodando
REM ================================================================

echo Iniciando Cliente...
echo.

java -cp out leilao.cliente.ClienteLeilao

pause
