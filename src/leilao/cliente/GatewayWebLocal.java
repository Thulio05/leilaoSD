package leilao.cliente;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import leilao.config.ConfiguracaoRede;
import leilao.config.DiscoveryMdns;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Gateway web local para clientes acessarem o leilão sem digitar IP do servidor.
 *
 * O usuário abre http://localhost:8088 no próprio computador. O gateway descobre
 * o primário ativo na rede e encaminha as requisições HTTP para ele.
 */
public class GatewayWebLocal {

    private static final ConfiguracaoRede CONFIGURACAO = ConfiguracaoRede.instancia();
    private static final int PORTA_GATEWAY = CONFIGURACAO.obterPortaGatewayWeb();

    private volatile DiscoveryMdns.ServidorEncontrado primarioAtual;

    public void iniciar() {
        try {
            HttpServer servidor = HttpServer.create(
                    new InetSocketAddress("localhost", PORTA_GATEWAY), 0);
            servidor.createContext("/", this::processarRequisicao);
            servidor.setExecutor(Executors.newCachedThreadPool());
            servidor.start();

            System.out.println("==================================================");
            System.out.println(" GATEWAY WEB LOCAL INICIADO");
            System.out.println(" Acesse no navegador deste computador:");
            System.out.println(" http://localhost:" + PORTA_GATEWAY);
            System.out.println("==================================================");
            System.out.println("[Gateway] O gateway descobrirá o primário automaticamente.");
        } catch (IOException erro) {
            System.out.println("[Gateway] Não foi possível iniciar em localhost:"
                    + PORTA_GATEWAY + ": " + erro.getMessage());
        }
    }

    private void processarRequisicao(HttpExchange troca) throws IOException {
        try {
            if ("/__gateway/status".equals(troca.getRequestURI().getPath())) {
                responderStatus(troca);
                return;
            }

            encaminharParaPrimario(troca, true);
        } catch (IOException erro) {
            responderErroGateway(troca,
                    "Não foi possível falar com o servidor primário: " + erro.getMessage());
        } finally {
            troca.close();
        }
    }

    private void encaminharParaPrimario(HttpExchange troca, boolean podeRedescobrir)
            throws IOException {

        DiscoveryMdns.ServidorEncontrado primario = obterPrimarioAtivo(false);
        if (primario == null) {
            responderErroGateway(troca,
                    "Nenhum servidor primário foi encontrado na rede.");
            return;
        }

        try {
            HttpURLConnection conexao = abrirConexaoComPrimario(troca, primario);
            int codigoResposta = conexao.getResponseCode();
            byte[] corpoResposta = lerCorpoResposta(conexao);

            copiarCabecalhosResposta(conexao, troca.getResponseHeaders());

            if ("HEAD".equalsIgnoreCase(troca.getRequestMethod())
                    || codigoResposta == HttpURLConnection.HTTP_NO_CONTENT
                    || codigoResposta == HttpURLConnection.HTTP_NOT_MODIFIED) {
                troca.sendResponseHeaders(codigoResposta, -1);
            } else {
                troca.sendResponseHeaders(codigoResposta, corpoResposta.length);
                try (OutputStream saida = troca.getResponseBody()) {
                    saida.write(corpoResposta);
                }
            }
        } catch (IOException erro) {
            primarioAtual = null;

            if (podeRedescobrir) {
                System.out.println("[Gateway] Falha no primário atual. Tentando descobrir outro...");
                obterPrimarioAtivo(true);
                encaminharParaPrimario(troca, false);
                return;
            }

            throw erro;
        }
    }

    private HttpURLConnection abrirConexaoComPrimario(
            HttpExchange troca,
            DiscoveryMdns.ServidorEncontrado primario) throws IOException {

        URL urlDestino = new URL(montarUrlDestino(troca, primario));
        HttpURLConnection conexao = (HttpURLConnection) urlDestino.openConnection();

        conexao.setConnectTimeout(CONFIGURACAO.obterTimeoutConexaoMs());
        conexao.setReadTimeout(CONFIGURACAO.obterTimeoutConexaoMs());
        conexao.setInstanceFollowRedirects(false);
        conexao.setRequestMethod(troca.getRequestMethod());

        copiarCabecalhosRequisicao(troca.getRequestHeaders(), conexao);

        if (metodoPodeTerCorpo(troca.getRequestMethod())) {
            byte[] corpo = lerTodos(troca.getRequestBody());
            conexao.setDoOutput(true);
            conexao.setFixedLengthStreamingMode(corpo.length);
            try (OutputStream saida = conexao.getOutputStream()) {
                saida.write(corpo);
            }
        }

        return conexao;
    }

    private String montarUrlDestino(
            HttpExchange troca,
            DiscoveryMdns.ServidorEncontrado primario) {

        String caminho = troca.getRequestURI().getRawPath();
        String consulta = troca.getRequestURI().getRawQuery();

        StringBuilder url = new StringBuilder(primario.obterUrlHttp());
        url.append(caminho == null || caminho.isBlank() ? "/" : caminho);
        if (consulta != null && !consulta.isBlank()) {
            url.append('?').append(consulta);
        }
        return url.toString();
    }

    private DiscoveryMdns.ServidorEncontrado obterPrimarioAtivo(boolean forcarNovaBusca) {
        if (!forcarNovaBusca && primarioAtual != null) {
            return primarioAtual;
        }

        DiscoveryMdns.ServidorEncontrado encontrado = DiscoveryMdns.descobrirPrimario();
        if (encontrado != null) {
            primarioAtual = encontrado;
            System.out.println("[Gateway] Encaminhando para primário em "
                    + encontrado.obterUrlHttp());
            return primarioAtual;
        }

        String enderecoFallback = CONFIGURACAO.obterEnderecoPrimario();
        primarioAtual = new DiscoveryMdns.ServidorEncontrado(
                enderecoFallback,
                CONFIGURACAO.obterPortaClientes(),
                CONFIGURACAO.obterPortaHttp());
        System.out.println("[Gateway] Discovery falhou. Tentando fallback em "
                + primarioAtual.obterUrlHttp());
        return primarioAtual;
    }

    private void copiarCabecalhosRequisicao(Headers origem, HttpURLConnection destino) {
        for (Map.Entry<String, List<String>> entrada : origem.entrySet()) {
            String nome = entrada.getKey();
            if (nome == null || deveIgnorarCabecalhoRequisicao(nome)) {
                continue;
            }

            destino.setRequestProperty(nome, String.join(",", entrada.getValue()));
        }
    }

    private void copiarCabecalhosResposta(HttpURLConnection origem, Headers destino) {
        for (Map.Entry<String, List<String>> entrada : origem.getHeaderFields().entrySet()) {
            String nome = entrada.getKey();
            if (nome == null || deveIgnorarCabecalhoResposta(nome)) {
                continue;
            }

            destino.put(nome, entrada.getValue());
        }
    }

    private boolean deveIgnorarCabecalhoRequisicao(String nome) {
        return "Host".equalsIgnoreCase(nome)
                || "Connection".equalsIgnoreCase(nome)
                || "Content-Length".equalsIgnoreCase(nome)
                || "Transfer-Encoding".equalsIgnoreCase(nome)
                || "Upgrade".equalsIgnoreCase(nome)
                || "Accept-Encoding".equalsIgnoreCase(nome);
    }

    private boolean deveIgnorarCabecalhoResposta(String nome) {
        return "Connection".equalsIgnoreCase(nome)
                || "Content-Length".equalsIgnoreCase(nome)
                || "Transfer-Encoding".equalsIgnoreCase(nome);
    }

    private byte[] lerCorpoResposta(HttpURLConnection conexao) throws IOException {
        InputStream entrada = conexao.getResponseCode() >= 400
                ? conexao.getErrorStream()
                : conexao.getInputStream();

        if (entrada == null) {
            return new byte[0];
        }

        try (InputStream fluxo = entrada) {
            return lerTodos(fluxo);
        }
    }

    private byte[] lerTodos(InputStream entrada) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] bytes = new byte[8192];
        int quantidade;
        while ((quantidade = entrada.read(bytes)) != -1) {
            buffer.write(bytes, 0, quantidade);
        }
        return buffer.toByteArray();
    }

    private boolean metodoPodeTerCorpo(String metodo) {
        return "POST".equalsIgnoreCase(metodo)
                || "PUT".equalsIgnoreCase(metodo)
                || "PATCH".equalsIgnoreCase(metodo)
                || "DELETE".equalsIgnoreCase(metodo);
    }

    private void responderStatus(HttpExchange troca) throws IOException {
        DiscoveryMdns.ServidorEncontrado primario = primarioAtual;
        String destino = primario == null
                ? "Nenhum primário em cache ainda."
                : primario.obterUrlHttp();

        responderHtml(troca, 200,
                "<!doctype html><html><head><meta charset='utf-8'>"
                        + "<title>Status do Gateway</title></head><body>"
                        + "<h1>Gateway Web Local</h1>"
                        + "<p>Destino atual: " + escaparHtml(destino) + "</p>"
                        + "<p><a href='/'>Abrir leilão</a></p>"
                        + "</body></html>");
    }

    private void responderErroGateway(HttpExchange troca, String mensagem) throws IOException {
        responderHtml(troca, 503,
                "<!doctype html><html><head><meta charset='utf-8'>"
                        + "<meta http-equiv='refresh' content='3'>"
                        + "<title>Gateway aguardando servidor</title>"
                        + "<style>body{font-family:Arial,sans-serif;margin:40px;}"
                        + ".box{max-width:720px;padding:24px;border:1px solid #ddd;"
                        + "border-radius:12px;background:#fafafa}</style></head><body>"
                        + "<div class='box'>"
                        + "<h1>Gateway aguardando o servidor do leilão</h1>"
                        + "<p>" + escaparHtml(mensagem) + "</p>"
                        + "<p>A página tentará novamente automaticamente em 3 segundos.</p>"
                        + "<p>Confira se o primário está ligado e se o firewall liberou UDP "
                        + CONFIGURACAO.obterPortaDiscovery() + " e TCP "
                        + CONFIGURACAO.obterPortaHttp() + ".</p>"
                        + "</div></body></html>");
    }

    private void responderHtml(HttpExchange troca, int codigo, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        troca.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        troca.sendResponseHeaders(codigo, bytes.length);
        try (OutputStream saida = troca.getResponseBody()) {
            saida.write(bytes);
        }
    }

    private String escaparHtml(String texto) {
        return texto
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    public static void main(String[] args) {
        new GatewayWebLocal().iniciar();
    }
}
