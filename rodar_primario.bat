@echo off
cd /d "%~dp0"

REM ================================================================
REM  rodar_primario.bat — Inicia o Servidor Primario
REM ================================================================
REM  ANTES de rodar:
REM    1. Edite o arquivo "config.properties" com os IPs corretos
REM    2. Rode compilar.bat uma vez
REM  
REM  ORDEM de inicializacao:
REM    Maquina 2: rodar_replica.bat   (iniciar PRIMEIRO)
REM    Maquina 1: rodar_primario.bat  (iniciar SEGUNDO)
REM    Qualquer maquina: rodar_cliente.bat
REM ================================================================

echo Iniciando Servidor Primario...
echo Interface web disponivel em: http://localhost:8080/
echo.
echo Para encerrar: pressione Ctrl+C
echo.

java -cp out leilao.servidor.ServidorLeilao

pause
