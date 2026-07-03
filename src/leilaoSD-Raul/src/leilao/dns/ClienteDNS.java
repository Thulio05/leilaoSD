package leilao.dns;

import leilao.config.ConfiguracaoRede;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Consulta o {@link ServidorNomes} para resolver um nome lógico
 * ("primario" ou "replica") em um endereço real (IP:porta).
 *
 * Usado pelo {@code ClienteLeilao} antes de se conectar a um servidor.
 * Se o servidor de nomes estiver fora do ar, o método retorna null e quem
 * chamou pode cair de volta para os endereços fixos do config.properties —
 * assim uma eventual falha do DNS não derruba o sistema inteiro.
 */
public final class ClienteDNS {

    private static final int TIMEOUT_CONSULTA_MS = 2_000;

    private ClienteDNS() {
    }

    /**
     * @param nomeLogico "primario" ou "replica"
     * @return endereço no formato "ip:porta", ou null se não foi possível resolver
     */
    public static String resolver(String nomeLogico) {
        ConfiguracaoRede configuracao = ConfiguracaoRede.instancia();
        String enderecoDns = configuracao.obterEnderecoDns();
        int portaDns = configuracao.obterPortaDns();

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(enderecoDns, portaDns), TIMEOUT_CONSULTA_MS);

            PrintWriter saida = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader entrada = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            saida.println("RESOLVE " + nomeLogico);
            String resposta = entrada.readLine();

            if (resposta != null && resposta.startsWith("OK ")) {
                return resposta.substring("OK ".length()).trim();
            }

            System.out.println("[DNS-CLIENTE] Não foi possível resolver '" + nomeLogico
                    + "': " + resposta);
            return null;
        } catch (IOException erro) {
            System.out.println("[DNS-CLIENTE] Servidor de nomes indisponível ("
                    + erro.getMessage() + "). Usando endereço de fallback.");
            return null;
        }
    }
}
