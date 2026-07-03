package leilao.servidor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import leilao.config.ConfiguracaoRede;
import leilao.dominio.GerenciadorLeiloes;
import leilao.dominio.Lance;
import leilao.dominio.Leilao;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Painel web simples para acompanhar o estado dos leilões. */
public class PainelMonitoramento {

    private static final int PORTA_HTTP = ConfiguracaoRede.instancia().obterPortaHttp();
    private static final String ROTA_MONITOR = "/monitor";

    private final GerenciadorLeiloes gerenciadorLeiloes;
    private volatile String papelAtual;

    public PainelMonitoramento(GerenciadorLeiloes gerenciadorLeiloes, String papelInicial) {
        this.gerenciadorLeiloes = gerenciadorLeiloes;
        this.papelAtual = papelInicial;
    }

    public void atualizarPapel(String novoPapel) {
        this.papelAtual = novoPapel;
    }

    public void iniciar() {
        try {
            HttpServer httpServer = HttpServer.create(
                    new InetSocketAddress(PORTA_HTTP), 0);

            httpServer.createContext(ROTA_MONITOR, this::tratarRequisicao);
            httpServer.setExecutor(null);
            httpServer.start();

            System.out.println("[PAINEL] Painel web iniciado em http://localhost:"
                    + PORTA_HTTP + ROTA_MONITOR);
            System.out.println("[PAINEL] Na rede: http://<IP-desta-maquina>:"
                    + PORTA_HTTP + ROTA_MONITOR);

        } catch (IOException erro) {
            System.out.println("[PAINEL] Não foi possível iniciar o painel web: "
                    + erro.getMessage());
        }
    }

    private void tratarRequisicao(HttpExchange troca) throws IOException {
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
    }

    private String construirPaginaHtml() {
        String horaAtual = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));

        boolean isPrimario = papelAtual.contains("PRIMÁRIO");
        String corCabecalho = isPrimario ? "#1a6b1a" : "#8b1a1a";

        StringBuilder html = new StringBuilder();

        html.append("<!DOCTYPE html>");
        html.append("<html lang='pt-BR'>");
        html.append("<head>");
        html.append("<meta charset='UTF-8'>");
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

        html.append("<body>");
        html.append("<header>");
        html.append("<h1>🔨 Painel de Monitoramento — Leilão Distribuído</h1>");
        html.append("<p>Atualizado automaticamente a cada 2 segundos &bull; ")
            .append(horaAtual).append("</p>");
        html.append("</header>");

        html.append("<div class='container'>");

        html.append("<div class='info-box'>");
        html.append("<strong>Servidor respondendo:</strong> ").append(papelAtual);
        html.append("&nbsp;&nbsp;|&nbsp;&nbsp;");
        html.append("<strong>Relógio de Lamport atual:</strong> ")
            .append(gerenciadorLeiloes.obterLamportAtual());
        html.append("&nbsp;&nbsp;|&nbsp;&nbsp;");
        html.append("<strong>Clientes conectados (TCP):</strong> ")
            .append("(ver terminal)");
        html.append("</div>");

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

        html.append("<footer>");
        html.append("Projeto Acadêmico — Sistemas Distribuídos &bull; ");
        html.append("Acesse de qualquer dispositivo na rede neste endereço.");
        html.append("</footer>");

        html.append("</body></html>");
        return html.toString();
    }

    private String construirLinhasTabelaLeiloes() {
        StringBuilder linhas = new StringBuilder();

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

    private String escaparHtml(String texto) {
        return texto
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
