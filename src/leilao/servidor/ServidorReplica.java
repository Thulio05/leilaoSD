package leilao.servidor;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import leilao.config.ConfiguracaoRede;
import leilao.dominio.GerenciadorLeiloes;
import leilao.dominio.Lance;
import leilao.dominio.Leilao;
import leilao.persistencia.LogDistribuido;
import leilao.persistencia.RepositorioUsuarios;

/**
 * Réplica passiva do servidor. Recebe estado e heartbeat e assume o
 * atendimento dos clientes quando o primário deixa de responder.
 */
public class ServidorReplica implements CoordenadorPrimario {

    private static final ConfiguracaoRede CONFIGURACAO = ConfiguracaoRede.instancia();
    private static final int PORTA_REPLICACAO = CONFIGURACAO.obterPortaReplicacao();
    private static final int PORTA_CLIENTES_APOS_PROMOCAO = CONFIGURACAO.obterPortaClientes();
    private static final String ENDERECO_REPLICA_RETORNO = CONFIGURACAO.obterEnderecoPrimario();
    private static final long INTERVALO_VERIFICACAO_MS = 2_000;
    private static final long INTERVALO_HEARTBEAT_RETORNO_MS = 2_000;
    private static final long TIMEOUT_FALHA_MS = 6_000;
    private static final String ARQUIVO_USUARIOS = "usuarios.txt";
    private static final String ARQUIVO_LOG = "log_replica.txt";

    private final GerenciadorLeiloes gerenciadorLeiloes = new GerenciadorLeiloes();
    private final RegistroClientes registroClientes = new RegistroClientes();
    private final RepositorioUsuarios repositorioUsuarios =
            new RepositorioUsuarios(ARQUIVO_USUARIOS);
    private final LogDistribuido logDistribuido = new LogDistribuido(ARQUIVO_LOG);
    private volatile long ultimoSinalRecebidoEm;
    private volatile boolean primarioJaConectou;
    private volatile boolean assumiuControle;
    private ServerSocket servidorReplicacao;
    private Socket socketPrimarioAtual;
    private Socket socketReplicaRetorno;
    private ObjectOutputStream saidaReplicaRetorno;

    private final PainelMonitoramento painel =
            new PainelMonitoramento(
                    gerenciadorLeiloes,
                    "SERVIDOR SECUNDÁRIO",
                    this,
                    registroClientes,
                    repositorioUsuarios,
                    logDistribuido,
                    () -> assumiuControle);

    public void iniciar() {
        System.out.println("==================================================");
        System.out.println(" SERVIDOR RÉPLICA INICIADO");
        System.out.println(" Aguardando o primário na porta " + PORTA_REPLICACAO);
        System.out.println("==================================================");

        Thread threadEscuta = new Thread(this::escutarPrimario, "Thread-Escuta-Primario");
        Thread threadMonitor = new Thread(this::monitorarHeartbeat, "Thread-Monitor-Heartbeat");
        threadEscuta.start();
        threadMonitor.start();

        Thread threadPainel = new Thread(painel::iniciar, "Thread-Painel-Web");
        threadPainel.setDaemon(true);
        threadPainel.start();

        try {
            threadEscuta.join();
            threadMonitor.join();
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
        }
    }

    private void escutarPrimario() {
        try (ServerSocket servidor = new ServerSocket(PORTA_REPLICACAO)) {
            servidorReplicacao = servidor;

            while (!assumiuControle) {
                System.out.println("[INFO] Aguardando conexão do primário...");
                socketPrimarioAtual = servidor.accept();

                if (assumiuControle) {
                    socketPrimarioAtual.close();
                    break;
                }

                primarioJaConectou = true;
                ultimoSinalRecebidoEm = System.currentTimeMillis();
                System.out.println("[INFO] Primário conectado. Recebendo estado e heartbeat.");

                receberMensagensDoPrimario(socketPrimarioAtual);
            }
        } catch (IOException erro) {
            if (!assumiuControle) {
                System.out.println("[ALERTA] Falha na porta de replicação: " + erro.getMessage());
            }
        }
    }

    private void receberMensagensDoPrimario(Socket socketPrimario) {
        try (ObjectInputStream entrada =
                     new ObjectInputStream(socketPrimario.getInputStream())) {

            while (!assumiuControle) {
                Object mensagem = entrada.readObject();
                ultimoSinalRecebidoEm = System.currentTimeMillis();

                if (mensagem instanceof GerenciadorLeiloes.EstadoReplicado estado) {
                    processarEstadoRecebido(estado);
                } else if (mensagem instanceof String texto
                        && texto.startsWith("HEARTBEAT")) {
                    processarHeartbeat(texto);
                } else {
                    System.out.println("[ALERTA] Mensagem desconhecida recebida: " + mensagem);
                }
            }
        } catch (IOException | ClassNotFoundException erro) {
            if (!assumiuControle) {
                System.out.println("[ALERTA] Conexão com o primário perdida: " + erro.getMessage());
            }
        }
    }

    private void processarEstadoRecebido(GerenciadorLeiloes.EstadoReplicado estado) {
        gerenciadorLeiloes.restaurarEstadoReplicado(estado);
        System.out.println("[REPLICAÇÃO] Estado recebido e aplicado "
                + "(Lamport=" + estado.obterTimestampLamport() + ").");
        logDistribuido.registrar(estado.obterTimestampLamport(),
                "REPLICACAO_RECEBIDA");
    }

    private void processarHeartbeat(String texto) {
        String[] partes = texto.split("\\|");
        String lamportRecebido = partes.length > 1 ? partes[1] : "?";

        System.out.println("[INFO] Heartbeat recebido "
                + "(Lamport do primário=" + lamportRecebido + ").");

        try {
            gerenciadorLeiloes.sincronizarLamport(Long.parseLong(lamportRecebido));
        } catch (NumberFormatException ignorado) {
        }
    }

    private void monitorarHeartbeat() {
        while (!assumiuControle && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(INTERVALO_VERIFICACAO_MS);

                if (!primarioJaConectou) {
                    continue;
                }

                long tempoSemSinal = System.currentTimeMillis() - ultimoSinalRecebidoEm;
                if (tempoSemSinal > TIMEOUT_FALHA_MS) {
                    System.out.println("[ALERTA] Primário sem sinal há "
                            + (tempoSemSinal / 1000) + " segundos.");
                    promoverParaPrimario();
                }
            } catch (InterruptedException erro) {
                Thread.currentThread().interrupt();
            }
        }
    }

private synchronized void promoverParaPrimario() {
    if (assumiuControle) return;
    assumiuControle = true;

    long tempoParadoMs = System.currentTimeMillis() - ultimoSinalRecebidoEm;
    System.out.println("[FAILOVER] Tempo de failover detectado: " + tempoParadoMs + " ms.");
    gerenciadorLeiloes.pausarCronometrosPorFailover(tempoParadoMs);

    encerrarConexaoComPrimario();

    System.out.println("[FAILOVER] Réplica assumindo como novo servidor primário.");
    logDistribuido.registrar(gerenciadorLeiloes.obterLamportAtual(),
            "FAILOVER replica_assumiu_controle");
    painel.atualizarPapel("SERVIDOR SECUNDÁRIO → ASSUMIU COMO PRIMÁRIO ⚡");
    repositorioUsuarios.recarregarDoArquivo();

    // Abre a porta de clientes AQUI, de forma síncrona,
    // antes de iniciar as threads — assim qualquer processo que
    // tentar abrir essa porta depois vai encontrá-la ocupada.
    ServerSocket servidorClientes;
    try {
        servidorClientes = new ServerSocket(PORTA_CLIENTES_APOS_PROMOCAO);
        System.out.println("[INFO] Novo primário aceitando clientes na porta "
                + PORTA_CLIENTES_APOS_PROMOCAO + ".");
    } catch (IOException erro) {
        System.out.println("[ALERTA] Não foi possível abrir porta de clientes: "
                + erro.getMessage());
        return;
    }

    // Captura final para usar dentro da lambda
    final ServerSocket socketFinal = servidorClientes;

    Thread threadClientes = new Thread(() -> {
        try {
            while (true) {
                Socket socketCliente = socketFinal.accept();
                TratadorCliente tratador = new TratadorCliente(
                        socketCliente, gerenciadorLeiloes, this,
                        registroClientes, repositorioUsuarios, logDistribuido);
                new Thread(tratador, "Thread-Cliente-" + socketCliente.getPort()).start();
            }
        } catch (IOException erro) {
            System.out.println("[ALERTA] Falha ao aceitar cliente: " + erro.getMessage());
        }
    }, "Thread-Novo-Primario");

    Thread threadMonitorLeiloes =
            new Thread(this::monitorarLeiloesComoPrimario, "Thread-Monitor-Leiloes");
    Thread threadHeartbeatRetorno =
            new Thread(this::executarHeartbeatComoPrimario, "Thread-Heartbeat-Novo-Primario");

    threadClientes.start();
    threadMonitorLeiloes.start();
    threadHeartbeatRetorno.start();
}

    private void encerrarConexaoComPrimario() {
        try {
            if (socketPrimarioAtual != null) {
                socketPrimarioAtual.close();
            }
            if (servidorReplicacao != null) {
                servidorReplicacao.close();
            }
        } catch (IOException ignorado) {
        }
    }

    private void aceitarClientesComoPrimario() {
        try (ServerSocket servidorClientes =
                     new ServerSocket(PORTA_CLIENTES_APOS_PROMOCAO)) {

            System.out.println("[INFO] Novo primário aceitando clientes na porta "
                    + PORTA_CLIENTES_APOS_PROMOCAO + ".");

            while (true) {
                Socket socketCliente = servidorClientes.accept();
                System.out.println("[INFO] Cliente conectado ao novo primário: "
                        + socketCliente.getInetAddress().getHostAddress());

                TratadorCliente tratador =
                        new TratadorCliente(
                                socketCliente, gerenciadorLeiloes, this, registroClientes,
                                repositorioUsuarios, logDistribuido);
                Thread threadCliente =
                        new Thread(tratador, "Thread-Cliente-" + socketCliente.getPort());
                threadCliente.start();
            }
        } catch (IOException erro) {
            System.out.println("[ALERTA] Falha ao abrir porta após promoção: " + erro.getMessage());
        }
    }

    private void monitorarLeiloesComoPrimario() {
        Set<Integer> leiloesAnunciados = new HashSet<>();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(1_000);
                for (Leilao leilao : gerenciadorLeiloes.obterTodosLeiloes().values()) {
                    if (!leilao.estaAtivo() && leiloesAnunciados.add(leilao.obterId())) {
                        anunciarEncerramento(leilao);
                        enviarEstadoParaReplicaRetorno(gerenciadorLeiloes.criarEstadoReplicado());
                    }
                }
            } catch (InterruptedException erro) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void anunciarEncerramento(Leilao leilao) {
        String vencedor = leilao.obterVencedorAtual();
        String mensagem;
        String evento;

        if (vencedor == null) {
            mensagem = "[ENCERRAMENTO] Leilão #" + leilao.obterId()
                    + " encerrado sem lances.";
            evento = "LEILAO_ENCERRADO leilao=" + leilao.obterId() + " vencedor=nenhum";
        } else {
            mensagem = "[ENCERRAMENTO] Leilão #" + leilao.obterId()
                    + " encerrado. Vencedor: " + vencedor
                    + " — R$ " + leilao.obterMaiorLanceAtual();
            evento = "LEILAO_ENCERRADO leilao=" + leilao.obterId()
                    + " vencedor=" + vencedor + " valor=" + leilao.obterMaiorLanceAtual();
        }

        System.out.println(mensagem);
        logDistribuido.registrar(gerenciadorLeiloes.obterLamportAtual(), evento);
        registroClientes.enviarParaTodos(mensagem);
    }

    @Override
    public void replicarAposLanceAceito(int idLeilao, Lance lance) {
        System.out.println("[REPLICAÇÃO] Novo primário replicando lance do leilão #"
                + idLeilao + " (Lamport=" + lance.obterTimestampLamport() + ").");

        boolean replicado = enviarEstadoParaReplicaRetorno(
                gerenciadorLeiloes.criarEstadoReplicado());
        logDistribuido.registrar(lance.obterTimestampLamport(),
                "REPLICACAO_ESTADO leilao=" + idLeilao
                        + " sucesso=" + replicado);
    }

    @Override
    public void replicarAposLeilaoCriado(Leilao leilao, long timestampLamport) {
        System.out.println("[REPLICAÇÃO] Novo primário replicando criação do leilão #"
                + leilao.obterId() + " (Lamport=" + timestampLamport + ").");

        boolean replicado = enviarEstadoParaReplicaRetorno(
                gerenciadorLeiloes.criarEstadoReplicado());
        logDistribuido.registrar(timestampLamport,
                "REPLICACAO_ESTADO leilao=" + leilao.obterId()
                        + " tipo=criacao sucesso=" + replicado);
    }

    private void executarHeartbeatComoPrimario() {
        while (assumiuControle && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(INTERVALO_HEARTBEAT_RETORNO_MS);
                enviarHeartbeatComoPrimario();
            } catch (InterruptedException erro) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private synchronized void enviarHeartbeatComoPrimario() {
        if (saidaReplicaRetorno == null) {
            if (!tentarConectarReplicaRetorno()) {
                return;
            }
            enviarEstadoParaReplicaRetorno(gerenciadorLeiloes.criarEstadoReplicado());
        }

        try {
            String heartbeat = "HEARTBEAT|" + gerenciadorLeiloes.obterLamportAtual();
            saidaReplicaRetorno.writeObject(heartbeat);
            saidaReplicaRetorno.flush();
            saidaReplicaRetorno.reset();
            System.out.println("[INFO] Heartbeat enviado para a réplica de retorno.");
        } catch (IOException erro) {
            System.out.println("[ALERTA] Falha ao enviar heartbeat para a réplica de retorno: "
                    + erro.getMessage());
            fecharConexaoReplicaRetorno();
        }
    }

    private synchronized boolean enviarEstadoParaReplicaRetorno(
            GerenciadorLeiloes.EstadoReplicado estado) {

        if (saidaReplicaRetorno == null && !tentarConectarReplicaRetorno()) {
            System.out.println("[INFO] Nenhuma réplica de retorno conectada no momento.");
            return false;
        }

        try {
            saidaReplicaRetorno.writeObject(estado);
            saidaReplicaRetorno.flush();
            saidaReplicaRetorno.reset();

            System.out.println("[REPLICAÇÃO] Estado enviado para a réplica de retorno "
                    + "(Lamport=" + estado.obterTimestampLamport() + ").");
            return true;
        } catch (IOException erro) {
            System.out.println("[ALERTA] Falha ao replicar para a réplica de retorno: "
                    + erro.getMessage());
            fecharConexaoReplicaRetorno();
            return false;
        }
    }

    private synchronized boolean tentarConectarReplicaRetorno() {
        if (saidaReplicaRetorno != null) {
            return true;
        }

        try {
            socketReplicaRetorno = new Socket();
            socketReplicaRetorno.connect(
                    new java.net.InetSocketAddress(ENDERECO_REPLICA_RETORNO, PORTA_REPLICACAO),
                    CONFIGURACAO.obterTimeoutConexaoMs());
            saidaReplicaRetorno = new ObjectOutputStream(socketReplicaRetorno.getOutputStream());

            System.out.println("[INFO] Réplica de retorno conectada em "
                    + ENDERECO_REPLICA_RETORNO + ":" + PORTA_REPLICACAO + ".");
            return true;
        } catch (IOException erro) {
            fecharConexaoReplicaRetorno();
            return false;
        }
    }

    private synchronized void fecharConexaoReplicaRetorno() {
        try {
            if (socketReplicaRetorno != null) {
                socketReplicaRetorno.close();
            }
        } catch (IOException ignorado) {
        }
        socketReplicaRetorno = null;
        saidaReplicaRetorno = null;
    }

    public static void main(String[] args) {
        new ServidorReplica().iniciar();
    }
}
