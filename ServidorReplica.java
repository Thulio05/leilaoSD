import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;

/**
 * Réplica passiva do servidor. Recebe estado e heartbeat e assume o
 * atendimento dos clientes quando o primário deixa de responder.
 */
public class ServidorReplica {

    private static final int PORTA_REPLICACAO = 6000;
    private static final int PORTA_CLIENTES_APOS_PROMOCAO = 5555;
    private static final long INTERVALO_VERIFICACAO_MS = 2_000;
    private static final long TIMEOUT_FALHA_MS = 6_000;

    private final GerenciadorLeiloes gerenciadorLeiloes = new GerenciadorLeiloes();
    private final RegistroClientes registroClientes = new RegistroClientes();
    private volatile long ultimoSinalRecebidoEm;
    private volatile boolean primarioJaConectou;
    private volatile boolean assumiuControle;
    private ServerSocket servidorReplicacao;
    private Socket socketPrimarioAtual;

    public void iniciar() {
        System.out.println("==================================================");
        System.out.println(" SERVIDOR RÉPLICA INICIADO");
        System.out.println(" Aguardando o primário na porta " + PORTA_REPLICACAO);
        System.out.println("==================================================");

        Thread threadEscuta = new Thread(this::escutarPrimario, "Thread-Escuta-Primario");
        Thread threadMonitor = new Thread(this::monitorarHeartbeat, "Thread-Monitor-Heartbeat");
        threadEscuta.start();
        threadMonitor.start();

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
    }

    private void processarHeartbeat(String texto) {
        String[] partes = texto.split("\\|");
        String lamportRecebido = partes.length > 1 ? partes[1] : "?";

        System.out.println("[INFO] Heartbeat recebido "
                + "(Lamport do primário=" + lamportRecebido + ").");

        try {
            gerenciadorLeiloes.sincronizarLamport(Long.parseLong(lamportRecebido));
        } catch (NumberFormatException ignorado) {
            // Um heartbeat malformado não interrompe a replicação.
        }
    }

    private void monitorarHeartbeat() {
        while (!assumiuControle && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(INTERVALO_VERIFICACAO_MS);

                // A réplica só pode declarar falha depois de conhecer o primário.
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

    /** Failover determinístico para o único servidor sobrevivente. */
    private synchronized void promoverParaPrimario() {
        if (assumiuControle) {
            return;
        }

        assumiuControle = true;
        encerrarConexaoComPrimario();

        System.out.println("[FAILOVER] Réplica assumindo como novo servidor primário.");
        System.out.println("[FAILOVER] Estado recuperado:");
        System.out.println(gerenciadorLeiloes.listarLeiloes());

        Thread threadClientes =
                new Thread(this::aceitarClientesComoPrimario, "Thread-Novo-Primario");
        Thread threadMonitorLeiloes =
                new Thread(this::monitorarLeiloesComoPrimario, "Thread-Monitor-Leiloes");
        threadClientes.start();
        threadMonitorLeiloes.start();
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
            // Os sockets já estavam encerrados.
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
                                socketCliente, gerenciadorLeiloes, null, registroClientes);
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

        if (vencedor == null) {
            mensagem = "[ENCERRAMENTO] Leilão #" + leilao.obterId()
                    + " encerrado sem lances.";
        } else {
            mensagem = "[ENCERRAMENTO] Leilão #" + leilao.obterId()
                    + " encerrado. Vencedor: " + vencedor
                    + " — R$ " + leilao.obterMaiorLanceAtual();
        }

        System.out.println(mensagem);
        registroClientes.enviarParaTodos(mensagem);
    }

    public static void main(String[] args) {
        new ServidorReplica().iniciar();
    }
}
