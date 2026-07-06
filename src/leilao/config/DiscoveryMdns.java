package leilao.config;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Descoberta automática de serviços na rede local via broadcast UDP.
 *
 * O objetivo é evitar que clientes precisem digitar o IP da máquina do
 * servidor. O primário e a réplica anunciam suas portas na rede; clientes e
 * gateways escutam esses anúncios e descobrem o endereço real automaticamente.
 */
public class DiscoveryMdns {

    public static final String PREFIXO_PRIMARIO = "LEILAO_PRIMARIO";
    public static final String PREFIXO_REPLICA = "LEILAO_REPLICA";

    private static final ConfiguracaoRede CONFIGURACAO = ConfiguracaoRede.instancia();
    private static final long INTERVALO_ANUNCIO_MS = 2_000;
    private static final int TAMANHO_BUFFER = 256;

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

        public String obterUrlHttp() {
            return "http://" + ip + ":" + portaHttp;
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

        System.out.println("[Discovery] Anunciando como primário via UDP broadcast "
                + "na porta " + CONFIGURACAO.obterPortaDiscovery() + ".");
        System.out.println("[Discovery] Gateway local dos clientes pode acessar este painel sem IP.");
        System.out.println("[Discovery] Acesso direto na rede: http://<IP-desta-maquina>:"
                + portaHttp + "/monitor");
    }

    /** Inicia anúncio periódico da réplica via broadcast. */
    public static void anunciarComoReplica(int portaReplicacao) {
        String mensagem = PREFIXO_REPLICA + "|" + portaReplicacao;
        iniciarAnuncioPeriodico(mensagem, "Thread-Discovery-Replica");

        System.out.println("[Discovery] Anunciando como réplica via UDP broadcast "
                + "na porta " + CONFIGURACAO.obterPortaDiscovery() + ".");
    }

    private static void iniciarAnuncioPeriodico(String mensagem, String nomeThread) {
        byte[] bytes = mensagem.getBytes(StandardCharsets.UTF_8);

        Thread thread = new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);

                while (!Thread.currentThread().isInterrupted()) {
                    for (InetAddress enderecoBroadcast : listarEnderecosBroadcast()) {
                        DatagramPacket pacote = new DatagramPacket(
                                bytes,
                                bytes.length,
                                enderecoBroadcast,
                                CONFIGURACAO.obterPortaDiscovery());
                        socket.send(pacote);
                    }

                    Thread.sleep(INTERVALO_ANUNCIO_MS);
                }
            } catch (IOException | InterruptedException erro) {
                if (!(erro instanceof InterruptedException)) {
                    System.out.println("[Discovery] Erro no anúncio: " + erro.getMessage());
                }
                Thread.currentThread().interrupt();
            }
        }, nomeThread);

        thread.setDaemon(true);
        thread.start();
    }

    /** Procura o primário na rede via broadcast. */
    public static ServidorEncontrado descobrirPrimario() {
        System.out.println("[Discovery] Procurando servidor primário na rede...");
        return descobrir(PREFIXO_PRIMARIO, ServidorEncontrado.class, false);
    }

    /** Procura a réplica na rede via broadcast. */
    public static ReplicaEncontrada descobrirReplica() {
        System.out.println("[Discovery] Procurando réplica na rede...");
        return descobrir(PREFIXO_REPLICA, ReplicaEncontrada.class, true);
    }

    @SuppressWarnings("unchecked")
    private static <T> T descobrir(
            String prefixoEsperado,
            Class<T> tipo,
            boolean ignorarAnunciosDaPropriaMaquina) {

        int portaDiscovery = CONFIGURACAO.obterPortaDiscovery();
        int timeoutMs = CONFIGURACAO.obterTimeoutDiscoveryMs();

        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new java.net.InetSocketAddress(portaDiscovery));
            socket.setSoTimeout(Math.min(1_000, timeoutMs));

            byte[] buffer = new byte[TAMANHO_BUFFER];
            long inicio = System.currentTimeMillis();

            while (System.currentTimeMillis() - inicio < timeoutMs) {
                DatagramPacket pacote = new DatagramPacket(buffer, buffer.length);

                try {
                    socket.receive(pacote);
                } catch (SocketTimeoutException timeout) {
                    continue;
                }

                String mensagem = new String(
                        pacote.getData(), 0, pacote.getLength(), StandardCharsets.UTF_8);

                if (!mensagem.startsWith(prefixoEsperado + "|")) {
                    continue;
                }

                String ip = pacote.getAddress().getHostAddress();
                if (ignorarAnunciosDaPropriaMaquina && isEnderecoLocal(ip)) {
                    continue;
                }

                T resultado = converterMensagem(tipo, ip, mensagem);
                if (resultado != null) {
                    return resultado;
                }
            }
        } catch (IOException erro) {
            System.out.println("[Discovery] Erro ao procurar: " + erro.getMessage());
        }

        System.out.println("[Discovery] Nenhum '" + prefixoEsperado + "' encontrado em "
                + (timeoutMs / 1000) + "s. Usando config.properties.");
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T converterMensagem(Class<T> tipo, String ip, String mensagem) {
        String[] partes = mensagem.split("\\|");

        try {
            if (tipo == ServidorEncontrado.class && partes.length == 3) {
                int portaClientes = Integer.parseInt(partes[1]);
                int portaHttp = Integer.parseInt(partes[2]);
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
        } catch (NumberFormatException erro) {
            System.out.println("[Discovery] Anúncio ignorado por formato inválido: " + mensagem);
        }

        return null;
    }

    /**
     * Retorna 255.255.255.255 e também os broadcasts reais de cada placa,
     * como 192.168.1.255. Isso aumenta a chance de funcionar em redes Wi-Fi
     * que não encaminham o broadcast global.
     */
    private static Set<InetAddress> listarEnderecosBroadcast() throws IOException {
        Set<InetAddress> enderecos = new LinkedHashSet<>();
        enderecos.add(InetAddress.getByName("127.0.0.1"));
        enderecos.add(InetAddress.getByName("255.255.255.255"));

        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface iface = interfaces.nextElement();
            if (!iface.isUp() || iface.isLoopback()) {
                continue;
            }

            for (InterfaceAddress enderecoInterface : iface.getInterfaceAddresses()) {
                InetAddress broadcast = enderecoInterface.getBroadcast();
                if (broadcast instanceof Inet4Address) {
                    enderecos.add(broadcast);
                }
            }
        }

        return enderecos;
    }

    /** Verifica se um IP pertence a esta máquina. */
    private static boolean isEnderecoLocal(String ip) {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                Enumeration<InetAddress> enderecos = iface.getInetAddresses();
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
