package leilao.dns;

import leilao.config.ConfiguracaoRede;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Servidor de nomes simplificado, o "DNS" do projeto.
 *
 * Em vez de os clientes precisarem saber de cor o IP e a porta de cada
 * servidor, eles perguntam a este processo: "onde está o servidor
 * 'primario'?" ou "onde está o servidor 'replica'?". Este processo responde
 * com o endereço atual (IP:porta) daquele nome lógico.
 *
 * O registro de nomes vem do config.properties (chaves dns.primario e
 * dns.replica). É a mesma ideia de um DNS de verdade — nome lógico
 * resolvido para um endereço de rede — só que num protocolo de texto bem
 * mais simples de explicar em uma apresentação:
 *
 *   Cliente -> Servidor de Nomes : "RESOLVE primario"
 *   Servidor de Nomes -> Cliente : "OK 192.168.0.10:5555"
 *
 * Se o nome não existir no registro:
 *
 *   Servidor de Nomes -> Cliente : "ERRO NAO_ENCONTRADO"
 *
 * Cada consulta abre uma conexão, envia uma linha, recebe uma linha e
 * fecha — sem estado entre requisições, igual a uma consulta DNS real.
 */
public class ServidorNomes {

    private final Map<String, String> registroDeNomes = new HashMap<>();
    private final int portaDns;

    public ServidorNomes() {
        ConfiguracaoRede configuracao = ConfiguracaoRede.instancia();
        this.portaDns = configuracao.obterPortaDns();

        registroDeNomes.put("primario", configuracao.obterRegistroDnsPrimario());
        registroDeNomes.put("replica", configuracao.obterRegistroDnsReplica());
    }

    public void iniciar() {
        System.out.println("==================================================");
        System.out.println(" SERVIDOR DE NOMES (DNS SIMPLIFICADO) INICIADO");
        System.out.println(" Porta: " + portaDns);
        System.out.println(" Registro atual:");
        registroDeNomes.forEach((nome, endereco) ->
                System.out.println("   " + nome + " -> " + endereco));
        System.out.println("==================================================");

        try (ServerSocket servidor = new ServerSocket(portaDns)) {
            while (true) {
                Socket socketConsulta = servidor.accept();
                Thread threadConsulta =
                        new Thread(() -> atenderConsulta(socketConsulta), "Thread-Consulta-DNS");
                threadConsulta.start();
            }
        } catch (IOException erro) {
            System.out.println("[DNS] Erro fatal no servidor de nomes: " + erro.getMessage());
        }
    }

    private void atenderConsulta(Socket socketConsulta) {
        try (socketConsulta;
             BufferedReader entrada = new BufferedReader(
                     new InputStreamReader(socketConsulta.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter saida = new PrintWriter(socketConsulta.getOutputStream(), true)) {

            String linha = entrada.readLine();
            if (linha == null || !linha.startsWith("RESOLVE ")) {
                saida.println("ERRO REQUISICAO_INVALIDA");
                return;
            }

            String nomeConsultado = linha.substring("RESOLVE ".length()).trim().toLowerCase();
            String enderecoResolvido = registroDeNomes.get(nomeConsultado);

            if (enderecoResolvido == null) {
                System.out.println("[DNS] Consulta por '" + nomeConsultado + "': não encontrado.");
                saida.println("ERRO NAO_ENCONTRADO");
            } else {
                System.out.println("[DNS] Consulta por '" + nomeConsultado
                        + "' resolvida para " + enderecoResolvido + ".");
                saida.println("OK " + enderecoResolvido);
            }
        } catch (IOException erro) {
            System.out.println("[DNS] Falha ao atender consulta: " + erro.getMessage());
        }
    }

    public static void main(String[] args) {
        new ServidorNomes().iniciar();
    }
}
