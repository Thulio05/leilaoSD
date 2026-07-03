@echo off
REM ================================================================
REM  compilar.bat — Compila todo o projeto de uma vez
REM ================================================================
REM  Como usar:
REM    1. Abra o Prompt de Comando nesta pasta
REM    2. Digite:  compilar.bat
REM    3. Se aparecer "COMPILACAO OK", está pronto para rodar
REM ================================================================

echo Compilando o projeto...

REM Cria a pasta de saida se nao existir
if not exist out mkdir out

REM Compila todos os arquivos .java encontrados nas subpastas
REM O resultado vai para a pasta "out"
javac -encoding UTF-8 -sourcepath . ^
    leilao\config\ConfiguracaoRede.java ^
    leilao\dominio\RelogioLamport.java ^
    leilao\dominio\Lance.java ^
    leilao\dominio\Leilao.java ^
    leilao\dominio\GerenciadorLeiloes.java ^
    leilao\persistencia\LogDistribuido.java ^
    leilao\persistencia\RepositorioUsuarios.java ^
    leilao\servidor\CoordenadorPrimario.java ^
    leilao\servidor\RegistroClientes.java ^
    leilao\servidor\PainelMonitoramento.java ^
    leilao\servidor\TratadorCliente.java ^
    leilao\servidor\ServidorLeilao.java ^
    leilao\servidor\ServidorReplica.java ^
    leilao\cliente\ClienteLeilao.java ^
    -d out

REM Verifica se compilou com sucesso
if %errorlevel% == 0 (
    echo.
    echo COMPILACAO OK! Agora voce pode rodar:
    echo.
    echo   Para o servidor primario:  rodar_primario.bat
    echo   Para o servidor replica:   rodar_replica.bat
    echo   Para o cliente:            rodar_cliente.bat
    echo.
) else (
    echo.
    echo ERRO NA COMPILACAO. Verifique se o Java esta instalado:
    echo   java -version
    echo.
)

pause
