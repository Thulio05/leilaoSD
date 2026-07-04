@echo off
cd /d "%~dp0"

REM ================================================================
REM  rodar_replica.bat — Inicia o Servidor Replica (Secundario)
REM ================================================================
REM  ANTES de rodar:
REM    1. Edite o arquivo "config.properties" com os IPs corretos
REM    2. Rode compilar.bat uma vez
REM
REM  Este processo deve ser iniciado ANTES do primario.
REM ================================================================

echo Iniciando Servidor Replica...
echo Painel web disponivel em: http://localhost:8080/monitor
echo.
echo Para encerrar: pressione Ctrl+C
echo.

java -cp out leilao.servidor.ServidorReplica

pause
