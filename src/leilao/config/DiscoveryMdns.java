package leilao.config;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Descoberta automática de serviços na rede local via broadcast UDP.
 *
 * Usa broadcast (255.255.255.255) em vez de multicast porque muitos
 * roteadores domésticos e redes de faculdade bloqueiam tráfego multicast
 * entre dispositivos, mas broadcast funciona em praticamente qualquer rede.
 *
 * Cada processo anuncia quem é a cada 2s. Os outros escutam e descobrem
 * o IP automaticamente — sem precisar editar config.properties.
 *
 * Formato das mensagens (separadas por '|'):
 *
 *   Anúncio do primário:
 *     LEILAO_PRIMARIO|<porta-clientes>|<porta-http>
 *
 *   Anúncio da réplica:
 *     LEILAO_REPLICA|<porta-replicacao>
 */
public class DiscoveryMdns {

    public static final int    PORTA_DISCOVERY   = 5354;  // porta própria, evita conflito com mDNS
    public static final String PREFIXO_PRIMARIO  = "LEILAO_PRIMARIO";
    public static final String PREFIXO_REPLICA   = "LEILAO_REPLICA";

    private static final int   TIMEOUT_DESCOBERTA_MS = 5_000;
    private static final long  INTERVALO_ANUNCIO_MS  = 2_000;
    private static final int   TAMANHO_BUFFER        = 256;

    /** Resultado de descoberta do primário. */
    public static class ServidorEncontrado {
        public final String ip;
        public final int portaClientes;
        public final int portaHttp;

        public ServidorEncontrado(String ip, int portaClientes, int portaHttp) {
            this.ip = ip;
            this.portaClientes = portaClientes;
            this.portaHttp = portaHttp;
        }
    }

    /** Resultado de descoberta da réplica. */
    public static class ReplicaEncontrada {
        public final String ip;
        public final int portaReplicacao;

        public ReplicaEncontrada(String ip, int portaReplicacao) {
            this.ip = ip;
            this.portaReplicacao = portaReplicacao;
        }
    }

    /** Inicia anúncio periódico do primário via broadcast. */
    public static void anunciarComoPrimario(int portaClientes, int portaHttp) {
        String mensagem = PREFIXO_PRIMARIO + "|" + portaClientes + "|" + portaHttp;
        iniciarAnuncioPeriodico(mensagem, "Thread-Discovery-Primario");

        System.out.println("[Discovery] Anunciando como primário via broadcast na rede.");
        System.out.println("[Discovery] Browsers podem acessar: http://<IP-desta-maquina>:"
                + portaHttp + "/monitor");
    }

    /** Inicia anúncio periódico da réplica via broadcast. */
    public static void anunciarComoReplica(int portaReplicacao) {
        String mensagem = PREFIXO_REPLICA + "|" + portaReplicacao;
        iniciarAnuncioPeriodico(mensagem, "Thread-Discovery-Replica");

        System.out.println("[Discovery] Anunciando como réplica via broadcast (porta "
                + portaReplicacao + ").");
    }

    private static void iniciarAnuncioPeriodico(String mensagem, String nomeThread) {
        byte[] bytes = mensagem.getBytes(StandardCharsets.UTF_8);

        Thread thread = new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                InetAddress broadcast = InetAddress.getByName("255.255.255.255");
                DatagramPacket pacote =
                        new DatagramPacket(bytes, bytes.length, broadcast, PORTA_DISCOVERY);

                while (!Thread.currentThread().isInterrupted()) {
                    socket.send(pacote);
                    Thread.sleep(INTERVALO_ANUNCIO_MS);
                }
            } catch (IOException | InterruptedException erro) {
                if (!(erro instanceof InterruptedException)) {
                    System.out.println("[Discovery] Erro no anúncio: " + erro.getMessage());
                }
            }
        }, nomeThread);

        thread.setDaemon(true);
        thread.start();
    }

    /** Procura o primário na rede via broadcast. */
    public static ServidorEncontrado descobrirPrimario() {
        System.out.println("[Discovery] Procurando servidor primário na rede...");
        return descobrir(PREFIXO_PRIMARIO, ServidorEncontrado.class);
    }

    /** Procura a réplica na rede via broadcast. */
    public static ReplicaEncontrada descobrirReplica() {
        System.out.println("[Discovery] Procurando réplica na rede...");
        return descobrir(PREFIXO_REPLICA, ReplicaEncontrada.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> T descobrir(String prefixoEsperado, Class<T> tipo) {
        // Escuta na porta de discovery aguardando broadcasts dos outros processos.
        // SO_REUSEADDR permite que primário e réplica escutem na mesma porta
        // ao mesmo tempo na mesma máquina (útil para testes locais).
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new java.net.InetSocketAddress(PORTA_DISCOVERY));
            socket.setSoTimeout(TIMEOUT_DESCOBERTA_MS);

            byte[] buffer = new byte[TAMANHO_BUFFER];
            long inicio = System.currentTimeMillis();

            while (System.currentTimeMillis() - inicio < TIMEOUT_DESCOBERTA_MS) {
                DatagramPacket pacote = new DatagramPacket(buffer, buffer.length);

                try {
                    socket.receive(pacote);
                } catch (SocketTimeoutException timeout) {
                    break;
                }

                String mensagem = new String(
                        pacote.getData(), 0, pacote.getLength(), StandardCharsets.UTF_8);

                if (!mensagem.startsWith(prefixoEsperado + "|")) {
                    continue;
                }

                String ip = pacote.getAddress().getHostAddress();

                // Ignora anúncios da própria máquina para evitar que o
                // primário descubra a si mesmo como réplica.
                if (isEnderecoLocal(ip)) {
                    continue;
                }

                String[] partes = mensagem.split("\\|");

                if (tipo == ServidorEncontrado.class && partes.length == 3) {
                    int portaClientes = Integer.parseInt(partes[1]);
                    int portaHttp     = Integer.parseInt(partes[2]);
                    System.out.println("[Discovery] Primário encontrado: " + ip
                            + " clientes=" + portaClientes + " http=" + portaHttp);
                    return (T) new ServidorEncontrado(ip, portaClientes, portaHttp);
                }

                if (tipo == ReplicaEncontrada.class && partes.length == 2) {
                    int portaReplicacao = Integer.parseInt(partes[1]);
                    System.out.println("[Discovery] Réplica encontrada: " + ip
                            + " porta=" + portaReplicacao);
                    return (T) new ReplicaEncontrada(ip, portaReplicacao);
                }
            }
        } catch (IOException erro) {
            System.out.println("[Discovery] Erro ao procurar: " + erro.getMessage());
        }

        System.out.println("[Discovery] Nenhum '" + prefixoEsperado + "' encontrado em "
                + (TIMEOUT_DESCOBERTA_MS / 1000) + "s. Usando config.properties.");
        return null;
    }

    /** Verifica se um IP pertence a esta máquina. */
    private static boolean isEnderecoLocal(String ip) {
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                java.util.Enumeration<InetAddress> enderecos = iface.getInetAddresses();
                while (enderecos.hasMoreElements()) {
                    if (enderecos.nextElement().getHostAddress().equals(ip)) {
                        return true;
                    }
                }
            }
        } catch (IOException ignorado) {
        }
        return false;
    }
}
