package leilao.servidor;

import leilao.dominio.GerenciadorLeiloes;
import leilao.dominio.Lance;
import leilao.dominio.Leilao;
import leilao.persistencia.LogDistribuido;
import leilao.persistencia.RepositorioUsuarios;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;

/** Servidor primário: atende clientes, replica estado e envia heartbeat. */
public class ServidorLeilao {

    private static final int PORTA_CLIENTES = 5555;
    private static final String ENDERECO_REPLICA = "localhost";
    private static final int PORTA_REPLICACAO = 6000;
    private static final long INTERVALO_HEARTBEAT_MS = 2_000;
    private static final String ARQUIVO_USUARIOS = "usuarios.txt";
    private static final String ARQUIVO_LOG = "log_primario.txt";

    private final GerenciadorLeiloes gerenciadorLeiloes = new GerenciadorLeiloes();
    private final RegistroClientes registroClientes = new RegistroClientes();
    private final RepositorioUsuarios repositorioUsuarios =
            new RepositorioUsuarios(ARQUIVO_USUARIOS);
    private final LogDistribuido logDistribuido = new LogDistribuido(ARQUIVO_LOG);
    private ServerSocket servidorClientes;
    private Socket socketReplicacao;
    private ObjectOutputStream saidaReplicacao;

    private final PainelMonitoramento painel =
            new PainelMonitoramento(gerenciadorLeiloes, "SERVIDOR PRIMÁRIO");

    public void iniciar() {
        try {
            servidorClientes = new ServerSocket(PORTA_CLIENTES);

            System.out.println("==================================================");
            System.out.println(" SERVIDOR PRIMÁRIO INICIADO");
            System.out.println(" Porta de clientes: " + PORTA_CLIENTES);
            System.out.println("==================================================");

            criarLeiloesDeExemplo();
            logDistribuido.registrar(gerenciadorLeiloes.obterLamportAtual(),
                    "SERVIDOR_INICIADO papel=PRIMARIO");
            enviarEstadoParaReplica(gerenciadorLeiloes.criarEstadoReplicado());

            Thread threadPainel = new Thread(painel::iniciar, "Thread-Painel-Web");
            threadPainel.setDaemon(true);
            threadPainel.start();

            Thread threadHeartbeat = new Thread(this::executarHeartbeat, "Thread-Heartbeat");
            threadHeartbeat.start();

            Thread threadMonitorLeiloes =
                    new Thread(this::monitorarLeiloes, "Thread-Monitor-Leiloes");
            threadMonitorLeiloes.start();

            aceitarClientes();
        } catch (IOException erro) {
            System.out.println("[ALERTA] Erro fatal ao iniciar o servidor: " + erro.getMessage());
        }
    }

    private void criarLeiloesDeExemplo() {
        gerenciadorLeiloes.criarLeilao("Notebook Dell XPS 13", 2000.0);
        gerenciadorLeiloes.criarLeilao("iPhone 15 Pro", 3500.0);
        gerenciadorLeiloes.criarLeilao("Relógio Inteligente", 800.0);
        System.out.println("[INFO] Leilões inicializados: 3 itens disponíveis.");
    }

    private void aceitarClientes() {
        while (true) {
            try {
                Socket socketCliente = servidorClientes.accept();
                System.out.println("[INFO] Novo cliente conectado: "
                        + socketCliente.getInetAddress().getHostAddress());

                TratadorCliente tratador =
                        new TratadorCliente(
                                socketCliente, gerenciadorLeiloes, this, registroClientes,
                                repositorioUsuarios, logDistribuido);
                Thread threadCliente =
                        new Thread(tratador, "Thread-Cliente-" + socketCliente.getPort());
                threadCliente.start();
            } catch (IOException erro) {
                System.out.println("[ALERTA] Erro ao aceitar cliente: " + erro.getMessage());
            }
        }
    }

    public void replicarAposLanceAceito(int idLeilao, Lance lance) {
        System.out.println("[REPLICAÇÃO] Lance no leilão #" + idLeilao
                + " (Lamport=" + lance.obterTimestampLamport() + "). Enviando estado...");

        boolean replicado = enviarEstadoParaReplica(gerenciadorLeiloes.criarEstadoReplicado());
        logDistribuido.registrar(lance.obterTimestampLamport(),
                "REPLICACAO_ESTADO leilao=" + idLeilao
                        + " sucesso=" + replicado);
    }

    public void replicarAposLeilaoCriado(Leilao leilao, long timestampLamport) {
        System.out.println("[REPLICAÇÃO] Leilão #" + leilao.obterId()
                + " criado (Lamport=" + timestampLamport + "). Enviando estado...");

        boolean replicado = enviarEstadoParaReplica(gerenciadorLeiloes.criarEstadoReplicado());
        logDistribuido.registrar(timestampLamport,
                "REPLICACAO_ESTADO leilao=" + leilao.obterId()
                        + " tipo=criacao sucesso=" + replicado);
    }

    private synchronized boolean enviarEstadoParaReplica(
            GerenciadorLeiloes.EstadoReplicado estado) {

        if (saidaReplicacao == null && !tentarConectarReplica()) {
            System.out.println("[ALERTA] Estado mantido no primário, mas a réplica está indisponível.");
            return false;
        }

        try {
            saidaReplicacao.writeObject(estado);
            saidaReplicacao.flush();
            saidaReplicacao.reset();

            System.out.println("[REPLICAÇÃO] Estado enviado com sucesso "
                    + "(Lamport=" + estado.obterTimestampLamport() + ").");
            return true;
        } catch (IOException erro) {
            System.out.println("[ALERTA] Falha ao replicar estado: " + erro.getMessage());
            fecharConexaoReplicacao();
            return false;
        }
    }

    private void executarHeartbeat() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(INTERVALO_HEARTBEAT_MS);
                enviarHeartbeat();
            } catch (InterruptedException erro) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private synchronized void enviarHeartbeat() {
        if (saidaReplicacao == null) {
            if (!tentarConectarReplica()) {
                return;
            }
            enviarEstadoParaReplica(gerenciadorLeiloes.criarEstadoReplicado());
        }

        try {
            String heartbeat = "HEARTBEAT|" + gerenciadorLeiloes.obterLamportAtual();
            saidaReplicacao.writeObject(heartbeat);
            saidaReplicacao.flush();
            saidaReplicacao.reset();
            System.out.println("[INFO] Heartbeat enviado para a réplica.");
        } catch (IOException erro) {
            System.out.println("[ALERTA] Falha ao enviar heartbeat: " + erro.getMessage());
            fecharConexaoReplicacao();
        }
    }

    private synchronized boolean tentarConectarReplica() {
        if (saidaReplicacao != null) {
            return true;
        }

        try {
            socketReplicacao = new Socket(ENDERECO_REPLICA, PORTA_REPLICACAO);
            saidaReplicacao = new ObjectOutputStream(socketReplicacao.getOutputStream());

            System.out.println("[INFO] Conectado à réplica em "
                    + ENDERECO_REPLICA + ":" + PORTA_REPLICACAO + ".");
            return true;
        } catch (IOException erro) {
            fecharConexaoReplicacao();
            System.out.println("[INFO] Réplica indisponível. Nova tentativa no próximo heartbeat.");
            return false;
        }
    }

    private synchronized void fecharConexaoReplicacao() {
        try {
            if (socketReplicacao != null) {
                socketReplicacao.close();
            }
        } catch (IOException ignorado) {
        }
        socketReplicacao = null;
        saidaReplicacao = null;
    }

    private void monitorarLeiloes() {
        Set<Integer> leiloesAnunciados = new HashSet<>();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(1_000);

                for (Leilao leilao : gerenciadorLeiloes.obterTodosLeiloes().values()) {
                    if (leilao.estaAtivo() || !leiloesAnunciados.add(leilao.obterId())) {
                        continue;
                    }

                    anunciarEncerramento(leilao);
                    enviarEstadoParaReplica(gerenciadorLeiloes.criarEstadoReplicado());
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

    public static void main(String[] args) {
        new ServidorLeilao().iniciar();
    }
}
