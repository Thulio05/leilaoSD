import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

/** Atende um cliente em uma thread dedicada. */
public class TratadorCliente implements Runnable {

    private final Socket socket;
    private final GerenciadorLeiloes gerenciadorLeiloes;
    private final ServidorLeilao servidorPrimario;
    private final RegistroClientes registroClientes;
    private final RepositorioUsuarios repositorioUsuarios;
    private final LogDistribuido logDistribuido;
    private String nomeCliente;
    private BufferedReader entrada;
    private PrintWriter saida;
    private boolean encerramentoSolicitado;

    /**
     * servidorPrimario é null quando este tratador pertence à réplica promovida.
     */
    public TratadorCliente(
            Socket socket,
            GerenciadorLeiloes gerenciadorLeiloes,
            ServidorLeilao servidorPrimario,
            RegistroClientes registroClientes,
            RepositorioUsuarios repositorioUsuarios,
            LogDistribuido logDistribuido) {
        this.socket = socket;
        this.gerenciadorLeiloes = gerenciadorLeiloes;
        this.servidorPrimario = servidorPrimario;
        this.registroClientes = registroClientes;
        this.repositorioUsuarios = repositorioUsuarios;
        this.logDistribuido = logDistribuido;
    }

    @Override
    public void run() {
        try {
            entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            saida = new PrintWriter(socket.getOutputStream(), true);
            registroClientes.adicionar(this);

            enviarMensagem("Bem-vindo ao Sistema de Leilão Distribuído!");

            if (!autenticar()) {
                return;
            }

            enviarMensagem("Olá, " + nomeCliente + "! Digite 'ajuda' para ver os comandos.");

            String comando;
            while (!encerramentoSolicitado && (comando = entrada.readLine()) != null) {
                processarComando(comando.trim());
            }
        } catch (IOException erro) {
            System.out.println("[ALERTA] Conexão perdida com o cliente '"
                    + nomeCliente + "': " + erro.getMessage());
        } finally {
            registroClientes.remover(this);
            fecharConexao();
        }
    }

    /**
     * Pede nome e senha ao cliente. Se o nome ainda não existe, cadastra
     * na hora — assim o cliente de terminal continua simples, sem precisar
     * de um comando separado de "cadastrar" antes de "login".
     *
     * Retorna false se a conexão cair durante o login, para o chamador
     * saber que deve interromper o atendimento.
     */
    private boolean autenticar() throws IOException {
        enviarMensagem("Digite seu nome de usuário:");
        String nomeDigitado = entrada.readLine();
        if (nomeDigitado == null) {
            return false;
        }
        nomeDigitado = nomeDigitado.trim();

        enviarMensagem(repositorioUsuarios.existeUsuario(nomeDigitado)
                ? "Digite sua senha:"
                : "Usuário novo. Digite uma senha para cadastrar:");
        String senhaDigitada = entrada.readLine();
        if (senhaDigitada == null) {
            return false;
        }

        RepositorioUsuarios.ResultadoAutenticacao resultado =
                repositorioUsuarios.autenticarOuCadastrar(nomeDigitado, senhaDigitada);

        if (!resultado.sucesso) {
            enviarMensagem("✗ " + resultado.mensagem);
            enviarMensagem("Conexão encerrada. Reconecte e tente novamente.");
            return false;
        }

        nomeCliente = nomeDigitado;
        logDistribuido.registrar(gerenciadorLeiloes.obterLamportAtual(),
                (resultado.cadastroNovo ? "USUARIO_CADASTRADO " : "USUARIO_LOGIN ")
                        + "usuario=" + nomeCliente);
        enviarMensagem("✓ " + resultado.mensagem);

        // O mesmo resumo é enviado na conexão inicial e na reconexão.
        // Assim o cliente não precisa descobrir sozinho o que ocorreu enquanto esteve fora.
        enviarMensagem("[SINCRONIZAÇÃO] Estado atual recebido do servidor:");
        enviarMensagem(gerenciadorLeiloes.criarResumoAtualParaCliente());
        return true;
    }

    private void processarComando(String comando) {
        if (comando.isEmpty()) {
            return;
        }

        String[] partes = comando.split("\\s+");
        String acao = partes[0].toLowerCase();

        switch (acao) {
            case "listar":
                enviarMensagem(gerenciadorLeiloes.listarLeiloes());
                break;
            case "status":
                processarStatus(partes);
                break;
            case "lance":
            case "lancar":
                processarLance(partes);
                break;
            case "historico":
                processarHistorico(partes);
                break;
            case "ajuda":
                mostrarAjuda();
                break;
            case "sair":
                enviarMensagem("Desconectando... até a próxima!");
                encerramentoSolicitado = true;
                break;
            default:
                enviarMensagem("Comando não reconhecido. Digite 'ajuda' para ver as opções.");
        }
    }

    private void processarStatus(String[] partes) {
        if (partes.length < 2) {
            enviarMensagem("Uso correto: status <id_leilao>");
            return;
        }

        try {
            int idLeilao = Integer.parseInt(partes[1]);
            enviarMensagem(gerenciadorLeiloes.obterStatusLeilao(idLeilao));
        } catch (NumberFormatException erro) {
            enviarMensagem("ID de leilão inválido. Use um número.");
        }
    }

    private void processarLance(String[] partes) {
        if (partes.length < 3) {
            enviarMensagem("Uso correto: lance <id_leilao> <valor>");
            return;
        }

        try {
            int idLeilao = Integer.parseInt(partes[1]);
            double valor = Double.parseDouble(partes[2].replace(",", "."));

            if (valor <= 0) {
                enviarMensagem("O valor do lance deve ser positivo.");
                return;
            }

            Leilao.ResultadoLance resultado =
                    gerenciadorLeiloes.registrarLance(idLeilao, nomeCliente, valor);

            if (!resultado.aceito) {
                logDistribuido.registrar(gerenciadorLeiloes.obterLamportAtual(),
                        "LANCE_RECUSADO leilao=" + idLeilao + " usuario=" + nomeCliente
                                + " valor=" + valor + " motivo=\"" + resultado.mensagem + "\"");
                enviarMensagem("✗ Lance recusado: " + resultado.mensagem);
                return;
            }

            // A tentativa de replicação acontece antes da confirmação ao cliente.
            if (servidorPrimario != null) {
                servidorPrimario.replicarAposLanceAceito(idLeilao, resultado.lance);
            }

            logDistribuido.registrar(resultado.lance.obterTimestampLamport(),
                    "LANCE_ACEITO leilao=" + idLeilao + " usuario=" + nomeCliente
                            + " valor=" + valor);

            enviarMensagem("✓ " + resultado.mensagem);
            enviarMensagem(gerenciadorLeiloes.obterStatusLeilao(idLeilao));

            if (resultado.cronometroEstendido) {
                enviarMensagem("⏱ Lance nos últimos 30s: cronômetro estendido em mais 30s.");
            }

            // O broadcast torna a atualização visível para todos sem que
            // cada cliente precise executar o comando "status" repetidamente.
            String atualizacao = "[ATUALIZAÇÃO GLOBAL] Leilão #" + idLeilao
                    + ": " + resultado.lance;
            registroClientes.enviarParaTodos(atualizacao);
        } catch (NumberFormatException erro) {
            enviarMensagem("Formato inválido. Use: lance <id_leilao> <valor>  (ex: lance 1 150.50)");
        }
    }

    private void processarHistorico(String[] partes) {
        if (partes.length < 2) {
            enviarMensagem("Uso correto: historico <id_leilao>");
            return;
        }

        try {
            int idLeilao = Integer.parseInt(partes[1]);
            enviarMensagem(gerenciadorLeiloes.obterHistoricoLances(idLeilao));
        } catch (NumberFormatException erro) {
            enviarMensagem("ID de leilão inválido.");
        }
    }

    private void mostrarAjuda() {
        enviarMensagem("\n=== COMANDOS DISPONÍVEIS ===");
        enviarMensagem("listar                  - Lista todos os leilões");
        enviarMensagem("status <id>             - Mostra o status de um leilão");
        enviarMensagem("lance <id> <valor>      - Registra um lance");
        enviarMensagem("historico <id>          - Mostra o histórico de lances");
        enviarMensagem("ajuda                   - Mostra este menu");
        enviarMensagem("sair                    - Desconecta do servidor");
        enviarMensagem("=============================\n");
    }

    /**
     * Respostas de comandos e broadcasts podem partir de threads diferentes.
     * O synchronized impede que duas mensagens sejam escritas juntas no socket.
     */
    public synchronized boolean enviarMensagem(String mensagem) {
        if (saida == null || socket.isClosed()) {
            return false;
        }

        saida.println(mensagem);
        return !saida.checkError();
    }

    private void fecharConexao() {
        try {
            if (!socket.isClosed()) {
                socket.close();
            }
        } catch (IOException erro) {
            System.out.println("[ALERTA] Erro ao fechar conexão com '"
                    + nomeCliente + "': " + erro.getMessage());
        }
    }
}
