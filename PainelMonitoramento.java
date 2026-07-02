import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * ============================================================================
 *  CLASSE PainelMonitoramento — PAINEL WEB DE MONITORAMENTO
 * ============================================================================
 * Abre um mini servidor HTTP na porta 8080 usando APENAS o HttpServer que
 * já vem dentro do JDK (pacote com.sun.net.httpserver). Não precisamos
 * instalar NADA além do Java que o projeto já usa.
 *
 * COMO ACESSAR:
 *   Qualquer dispositivo na mesma rede digita no navegador:
 *   http://<IP-DA-MAQUINA-DO-SERVIDOR>:8080/monitor
 *
 * POR QUE NÃO QUEBRA A LÓGICA DE SOCKETS?
 *   Esta classe roda em uma thread completamente separada. Ela só lê
 *   (nunca escreve) o GerenciadorLeiloes, que já é thread-safe por
 *   design (ConcurrentHashMap + métodos synchronized). Portanto, o
 *   painel web e o servidor TCP convivem sem interferência alguma.
 *
 * ATUALIZAÇÃO AUTOMÁTICA SEM JAVASCRIPT:
 *   A página usa a tag HTML <meta http-equiv="refresh" content="2">.
 *   Isso instrui o próprio navegador a recarregar a página a cada 2
 *   segundos — sem uma linha de JavaScript.
 * ============================================================================
 */
public class PainelMonitoramento {

    private static final int PORTA_HTTP = 8080;
    private static final String ROTA_MONITOR = "/monitor";

    // Referência ao gerenciador de leilões — SOMENTE LEITURA aqui.
    private final GerenciadorLeiloes gerenciadorLeiloes;

    // Identifica quem está servindo este painel: "PRIMÁRIO" ou "SECUNDÁRIO".
    // É passado pelo construtor e atualizado em tempo real se o servidor
    // for promovido (ver ServidorReplica.promoverParaPrimario()).
    // "volatile" porque pode ser alterado por outra thread (a thread de
    // failover do ServidorReplica) enquanto o painel está servindo requests.
    private volatile String papelAtual;

    private HttpServer httpServer;

    public PainelMonitoramento(GerenciadorLeiloes gerenciadorLeiloes, String papelInicial) {
        this.gerenciadorLeiloes = gerenciadorLeiloes;
        this.papelAtual = papelInicial;
    }

    /**
     * Atualiza o papel exibido no painel — chamado pelo ServidorReplica
     * no momento exato do failover, para que a página passe a mostrar
     * "SERVIDOR SECUNDÁRIO (PROMOVIDO A PRIMÁRIO)" em vez de "SECUNDÁRIO".
     */
    public void atualizarPapel(String novoPapel) {
        this.papelAtual = novoPapel;
    }

    /**
     * Inicia o servidor HTTP em uma thread daemon separada.
     *
     * "daemon = true" significa que esta thread NÃO impede o programa de
     * encerrar se as threads principais (TCP) terminarem. Isso é correto:
     * o painel web é um acessório, não o coração do sistema.
     */
    public void iniciar() {
        try {
            // HttpServer é a classe nativa do JDK para criar servidores HTTP
            // simples. O segundo argumento (0) é o backlog de conexões TCP
            // pendentes — zero significa "usar o padrão do sistema".
            httpServer = HttpServer.create(
                    new InetSocketAddress(PORTA_HTTP), 0);

            // Registra a rota /monitor: toda requisição GET para esse
            // caminho será tratada pelo método tratarRequisicao().
            // Qualquer outra rota (ex: /favicon.ico) retorna 404 automaticamente.
            httpServer.createContext(ROTA_MONITOR, this::tratarRequisicao);

            // null = usa o executor padrão do Java (cria threads conforme
            // necessário). Didaticamente mais simples do que configurar um pool.
            httpServer.setExecutor(null);
            httpServer.start();

            System.out.println("[PAINEL] Painel web iniciado em http://localhost:"
                    + PORTA_HTTP + ROTA_MONITOR);
            System.out.println("[PAINEL] Na rede: http://<IP-desta-maquina>:"
                    + PORTA_HTTP + ROTA_MONITOR);

        } catch (IOException erro) {
            // O painel não é crítico: se a porta 8080 estiver ocupada,
            // o servidor TCP continua funcionando normalmente.
            System.out.println("[PAINEL] Não foi possível iniciar o painel web: "
                    + erro.getMessage());
        }
    }

    /**
     * Chamado pelo HttpServer a cada requisição GET em /monitor.
     * Monta o HTML como uma String e devolve como resposta HTTP 200.
     *
     * O método é simples de propósito: gera HTML concatenando strings,
     * sem templates nem bibliotecas. Cada linha de HTML tem um objetivo
     * claro que podemos apontar para a banca.
     */
    private void tratarRequisicao(HttpExchange troca) throws IOException {
        // Só atendemos GET. Para qualquer outro método (POST, etc.), 405.
        if (!"GET".equalsIgnoreCase(troca.getRequestMethod())) {
            troca.sendResponseHeaders(405, -1);
            troca.close();
            return;
        }

        String html = construirPaginaHtml();
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);

        troca.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        troca.sendResponseHeaders(200, bytes.length);

        try (OutputStream saida = troca.getResponseBody()) {
            saida.write(bytes);
        }
        // O try-with-resources já fecha o OutputStream, que por sua vez
        // finaliza a resposta HTTP. Não precisamos de mais nada.
    }

    /**
     * Monta o HTML completo da página de monitoramento.
     * Dividimos em métodos menores para facilitar a explicação:
     *   construirPaginaHtml()  → estrutura geral
     *   construirTabelaLeiloes() → dados dos leilões
     */
    private String construirPaginaHtml() {
        String horaAtual = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));

        // Cor do cabeçalho muda conforme o papel — detalhe visual que
        // torna o failover imediatamente visível na tela durante a demo.
        boolean isPrimario = papelAtual.contains("PRIMÁRIO");
        String corCabecalho = isPrimario ? "#1a6b1a" : "#8b1a1a";

        StringBuilder html = new StringBuilder();

        // ── <head> ──────────────────────────────────────────────────────────
        html.append("<!DOCTYPE html>");
        html.append("<html lang='pt-BR'>");
        html.append("<head>");
        html.append("<meta charset='UTF-8'>");

        // ESTE É O SEGREDO DO AUTO-REFRESH SEM JAVASCRIPT:
        // O atributo content='2' faz o navegador recarregar a cada 2 segundos.
        // É uma tag HTML pura, suportada por todos os browsers desde os anos 90.
        html.append("<meta http-equiv='refresh' content='2'>");

        html.append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>");
        html.append("<title>Painel — Leilão Distribuído</title>");
        html.append("<style>");
        html.append("  body { font-family: Arial, sans-serif; margin: 0; background: #f0f0f0; }");
        html.append("  header { background: ").append(corCabecalho).append("; color: white; padding: 16px 24px; }");
        html.append("  header h1 { margin: 0; font-size: 1.4em; }");
        html.append("  header p  { margin: 4px 0 0; font-size: 0.9em; opacity: 0.85; }");
        html.append("  .container { padding: 20px 24px; }");
        html.append("  .info-box { background: white; border-left: 5px solid ")
            .append(corCabecalho)
            .append("; padding: 12px 16px; margin-bottom: 20px; border-radius: 4px; }");
        html.append("  .info-box strong { font-size: 1.05em; }");
        html.append("  table { border-collapse: collapse; width: 100%; background: white; border-radius: 4px; overflow: hidden; }");
        html.append("  th { background: #333; color: white; padding: 10px 14px; text-align: left; font-size: 0.85em; text-transform: uppercase; letter-spacing: 0.05em; }");
        html.append("  td { padding: 10px 14px; border-bottom: 1px solid #e8e8e8; font-size: 0.95em; }");
        html.append("  tr:last-child td { border-bottom: none; }");
        html.append("  .badge-ativo    { background: #d4edda; color: #155724; padding: 2px 10px; border-radius: 12px; font-size: 0.82em; font-weight: bold; }");
        html.append("  .badge-encerrado{ background: #f8d7da; color: #721c24; padding: 2px 10px; border-radius: 12px; font-size: 0.82em; font-weight: bold; }");
        html.append("  footer { text-align: center; color: #999; font-size: 0.78em; padding: 16px; }");
        html.append("</style>");
        html.append("</head>");

        // ── <body> ──────────────────────────────────────────────────────────
        html.append("<body>");

        // Cabeçalho — muda de cor conforme quem está respondendo
        html.append("<header>");
        html.append("<h1>🔨 Painel de Monitoramento — Leilão Distribuído</h1>");
        html.append("<p>Atualizado automaticamente a cada 2 segundos &bull; ")
            .append(horaAtual).append("</p>");
        html.append("</header>");

        html.append("<div class='container'>");

        // Caixa de informações do servidor
        html.append("<div class='info-box'>");
        html.append("<strong>Servidor respondendo:</strong> ").append(papelAtual);
        html.append("&nbsp;&nbsp;|&nbsp;&nbsp;");
        html.append("<strong>Relógio de Lamport atual:</strong> ")
            .append(gerenciadorLeiloes.obterLamportAtual());
        html.append("&nbsp;&nbsp;|&nbsp;&nbsp;");
        html.append("<strong>Clientes conectados (TCP):</strong> ")
            .append("(ver terminal)");
        html.append("</div>");

        // Tabela de leilões
        html.append("<table>");
        html.append("<thead><tr>");
        html.append("<th>#</th>");
        html.append("<th>Item</th>");
        html.append("<th>Lance Atual</th>");
        html.append("<th>Vencedor Atual</th>");
        html.append("<th>Tempo Restante</th>");
        html.append("<th>Status</th>");
        html.append("<th>Lamport (último lance)</th>");
        html.append("</tr></thead>");
        html.append("<tbody>");
        html.append(construirLinhasTabelaLeiloes());
        html.append("</tbody></table>");

        html.append("</div>");

        // Rodapé
        html.append("<footer>");
        html.append("Projeto Acadêmico — Sistemas Distribuídos &bull; ");
        html.append("Acesse de qualquer dispositivo na rede neste endereço.");
        html.append("</footer>");

        html.append("</body></html>");
        return html.toString();
    }

    /**
     * Itera sobre todos os leilões e gera uma linha de tabela HTML por leilão.
     * Usa os mesmos métodos públicos que o TratadorCliente já usa —
     * nenhuma lógica nova, só formatação visual diferente.
     */
    private String construirLinhasTabelaLeiloes() {
        StringBuilder linhas = new StringBuilder();

        // obterTodosLeiloes() já retorna uma cópia defensiva (HashMap novo),
        // então não há risco de ConcurrentModificationException aqui.
        List<Leilao> leiloes = new java.util.ArrayList<>(
                gerenciadorLeiloes.obterTodosLeiloes().values());
        leiloes.sort(java.util.Comparator.comparingInt(Leilao::obterId));

        if (leiloes.isEmpty()) {
            linhas.append("<tr><td colspan='7' style='text-align:center;color:#999;'>")
                  .append("Nenhum leilão disponível.</td></tr>");
            return linhas.toString();
        }

        for (Leilao leilao : leiloes) {
            boolean ativo = leilao.estaAtivo();

            // Pega o Lamport do ÚLTIMO lance aceito (se existir)
            List<Lance> historico = leilao.obterHistoricoLances();
            String lamportUltimoLance = historico.isEmpty()
                    ? "—"
                    : String.valueOf(historico.get(historico.size() - 1).obterTimestampLamport());

            String vencedorAtual = leilao.obterVencedorAtual();
            String tempoRestante = ativo
                    ? leilao.obterTempoRestanteSegundos() + "s"
                    : "—";
            String badgeStatus = ativo
                    ? "<span class='badge-ativo'>ATIVO</span>"
                    : "<span class='badge-encerrado'>ENCERRADO</span>";

            linhas.append("<tr>");
            linhas.append("<td>").append(leilao.obterId()).append("</td>");
            linhas.append("<td>").append(escaparHtml(leilao.obterDescricaoItem())).append("</td>");
            linhas.append("<td>R$ ").append(String.format("%.2f", leilao.obterMaiorLanceAtual())).append("</td>");
            linhas.append("<td>").append(vencedorAtual != null ? escaparHtml(vencedorAtual) : "Nenhum").append("</td>");
            linhas.append("<td>").append(tempoRestante).append("</td>");
            linhas.append("<td>").append(badgeStatus).append("</td>");
            linhas.append("<td>").append(lamportUltimoLance).append("</td>");
            linhas.append("</tr>");
        }

        return linhas.toString();
    }

    /**
     * Escapa caracteres HTML especiais para evitar que nomes de usuário
     * ou itens com < > & quebrem a página — boa prática mesmo em um MVP.
     */
    private String escaparHtml(String texto) {
        return texto
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    public void parar() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }
}
