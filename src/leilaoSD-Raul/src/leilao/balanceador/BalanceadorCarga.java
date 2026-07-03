package leilao.balanceador;

import leilao.config.ConfiguracaoRede;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Balanceador de carga round-robin.
 *
 * O primário e a réplica desta aplicação já rodam, cada um, um painel web
 * de monitoramento (porta HTTP configurável, padrão 8080) que qualquer um
 * pode consultar a qualquer momento — os dois ficam ativos ao mesmo tempo,
 * diferente da porta de clientes (5555), que só o servidor líder atende.
 *
 * Este processo escuta em uma porta pública (padrão 5500) e, a cada nova
 * conexão recebida, encaminha (proxy TCP transparente) para um dos
 * backends configurados em "balanceador.backends" do config.properties,
 * alternando entre eles em round-robin. Isso distribui a carga de acesso
 * ao painel entre os dois servidores em vez de sobrecarregar um só —
 * o requisito mínimo de "Load Balance" para o trabalho.
 *
 * Uso típico: em vez de acessar http://ip-do-primario:8080/monitor,
 * qualquer pessoa acessa http://ip-do-balanceador:5500/monitor, e a cada
 * nova conexão o balanceador decide (round-robin) para qual dos backends
 * mandar a requisição.
 *
 * O encaminhamento é feito no nível de bytes (proxy TCP simples), então
 * funciona também para qualquer outro protocolo TCP, não só HTTP —
 * inclusive poderia balancear a porta de clientes (5555) caso, no futuro,
 * mais de um servidor esteja apto a atender clientes ao mesmo tempo.
 */
public class BalanceadorCarga {

    private final int portaBalanceador;
    private final List<String[]> backends = new ArrayList<>();
    private final AtomicInteger proximoIndice = new AtomicInteger(0);

    public BalanceadorCarga() {
        ConfiguracaoRede configuracao = ConfiguracaoRede.instancia();
        this.portaBalanceador = configuracao.obterPortaBalanceador();
        carregarBackends(configuracao.obterBackendsBalanceador());
    }

    private void carregarBackends(String listaConfigurada) {
        for (String entrada : listaConfigurada.split(",")) {
            String[] partes = entrada.trim().split(":");
            if (partes.length == 2) {
                backends.add(new String[]{partes[0], partes[1]});
            }
        }

        if (backends.isEmpty()) {
            throw new IllegalStateException(
                    "Nenhum backend configurado em 'balanceador.backends'.");
        }
    }

    public void iniciar() {
        System.out.println("==================================================");
        System.out.println(" BALANCEADOR DE CARGA INICIADO");
        System.out.println(" Porta pública: " + portaBalanceador);
        System.out.println(" Backends (round-robin):");
        for (String[] backend : backends) {
            System.out.println("   - " + backend[0] + ":" + backend[1]);
        }
        System.out.println("==================================================");

        try (ServerSocket servidor = new ServerSocket(portaBalanceador)) {
            while (true) {
                Socket socketCliente = servidor.accept();
                String[] backendEscolhido = escolherProximoBackend();

                System.out.println("[BALANCEADOR] Conexão de "
                        + socketCliente.getInetAddress().getHostAddress()
                        + " encaminhada para " + backendEscolhido[0] + ":" + backendEscolhido[1]);

                Thread threadProxy = new Thread(
                        () -> encaminharConexao(socketCliente, backendEscolhido),
                        "Thread-Proxy-" + socketCliente.getPort());
                threadProxy.start();
            }
        } catch (IOException erro) {
            System.out.println("[BALANCEADOR] Erro fatal: " + erro.getMessage());
        }
    }

    private String[] escolherProximoBackend() {
        int indice = proximoIndice.getAndUpdate(atual -> (atual + 1) % backends.size());
        return backends.get(indice);
    }

    private void encaminharConexao(Socket socketCliente, String[] backend) {
        try (socketCliente;
             Socket socketBackend = new Socket(backend[0], Integer.parseInt(backend[1]))) {

            Thread clienteParaBackend = new Thread(
                    () -> copiarBytes(socketCliente, socketBackend), "Proxy-Cliente->Backend");
            Thread backendParaCliente = new Thread(
                    () -> copiarBytes(socketBackend, socketCliente), "Proxy-Backend->Cliente");

            clienteParaBackend.start();
            backendParaCliente.start();
            clienteParaBackend.join();
            backendParaCliente.join();
        } catch (IOException erro) {
            System.out.println("[BALANCEADOR] Backend " + backend[0] + ":" + backend[1]
                    + " indisponível: " + erro.getMessage());
        } catch (InterruptedException erro) {
            Thread.currentThread().interrupt();
        }
    }

    private void copiarBytes(Socket origem, Socket destino) {
        try (InputStream entrada = origem.getInputStream();
             OutputStream saida = destino.getOutputStream()) {

            byte[] buffer = new byte[4096];
            int quantidadeLida;
            while ((quantidadeLida = entrada.read(buffer)) != -1) {
                saida.write(buffer, 0, quantidadeLida);
                saida.flush();
            }
        } catch (IOException ignorado) {
            // Conexão encerrada por um dos lados; comportamento normal ao fechar.
        } finally {
            try {
                destino.shutdownOutput();
            } catch (IOException ignorado) {
            }
        }
    }

    public static void main(String[] args) {
        new BalanceadorCarga().iniciar();
    }
}
