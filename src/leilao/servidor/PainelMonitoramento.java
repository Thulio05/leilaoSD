package leilao.servidor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import leilao.config.ConfiguracaoRede;
import leilao.dominio.GerenciadorLeiloes;
import leilao.dominio.Lance;
import leilao.dominio.Leilao;
import leilao.persistencia.LogDistribuido;
import leilao.persistencia.RepositorioUsuarios;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Interface web do sistema de leilão.
 *
 * A mesma classe mantém o painel de monitoramento e oferece ações web para
 * login, criação de leilões e envio de lances. As ações só ficam habilitadas
 * quando o processo atual está atuando como primário.
 */
public class PainelMonitoramento {

    private static final int PORTA_HTTP = ConfiguracaoRede.instancia().obterPortaHttp();
    private static final String ROTA_INICIO = "/";
    private static final String ROTA_MONITOR = "/monitor";
    private static final String ROTA_LOGIN = "/login";
    private static final String ROTA_LOGOUT = "/logout";
    private static final String ROTA_LANCE = "/lance";
    private static final String ROTA_CRIAR_LEILAO = "/criar-leilao";
    private static final String COOKIE_SESSAO = "LEILAO_SESSAO";

    private final GerenciadorLeiloes gerenciadorLeiloes;
    private final CoordenadorPrimario coordenadorPrimario;
    private final RegistroClientes registroClientes;
    private final RepositorioUsuarios repositorioUsuarios;
    private final LogDistribuido logDistribuido;
    private final BooleanSupplier aceitaComandos;
    private final Map<String, String> sessoes = new ConcurrentHashMap<>();

    private volatile String papelAtual;

    public PainelMonitoramento(GerenciadorLeiloes gerenciadorLeiloes, String papelInicial) {
        this(
                gerenciadorLeiloes,
                papelInicial,
                null,
                null,
                null,
                null,
                () -> false);
    }

    public PainelMonitoramento(
            GerenciadorLeiloes gerenciadorLeiloes,
            String papelInicial,
            CoordenadorPrimario coordenadorPrimario,
            RegistroClientes registroClientes,
            RepositorioUsuarios repositorioUsuarios,
            LogDistribuido logDistribuido,
            BooleanSupplier aceitaComandos) {
        this.gerenciadorLeiloes = gerenciadorLeiloes;
        this.papelAtual = papelInicial;
        this.coordenadorPrimario = coordenadorPrimario;
        this.registroClientes = registroClientes;
        this.repositorioUsuarios = repositorioUsuarios;
        this.logDistribuido = logDistribuido;
        this.aceitaComandos = aceitaComandos;
    }

    public void atualizarPapel(String novoPapel) {
        this.papelAtual = novoPapel;
    }

    public void iniciar() {
        try {
            HttpServer httpServer = HttpServer.create(
                    new InetSocketAddress(PORTA_HTTP), 0);

            httpServer.createContext(ROTA_INICIO, this::tratarRequisicao);
            httpServer.setExecutor(null);
            httpServer.start();

            System.out.println("[PAINEL] Interface web iniciada em http://localhost:"
                    + PORTA_HTTP + ROTA_INICIO);
            System.out.println("[PAINEL] Monitoramento também disponível em http://localhost:"
                    + PORTA_HTTP + ROTA_MONITOR);
            System.out.println("[PAINEL] Na rede: http://<IP-desta-maquina>:"
                    + PORTA_HTTP + ROTA_INICIO);

        } catch (IOException erro) {
            System.out.println("[PAINEL] Não foi possível iniciar a interface web: "
                    + erro.getMessage());
        }
    }

    private void tratarRequisicao(HttpExchange troca) throws IOException {
        String caminho = troca.getRequestURI().getPath();
        String metodo = troca.getRequestMethod();

        try {
            if ("GET".equalsIgnoreCase(metodo)
                    && (ROTA_INICIO.equals(caminho) || ROTA_MONITOR.equals(caminho))) {
                responderHtml(troca, construirPaginaHtml(troca, obterMensagemDaUrl(troca)));
                return;
            }

            if ("POST".equalsIgnoreCase(metodo) && ROTA_LOGIN.equals(caminho)) {
                processarLogin(troca);
                return;
            }

            if ("POST".equalsIgnoreCase(metodo) && ROTA_LOGOUT.equals(caminho)) {
                processarLogout(troca);
                return;
            }

            if ("POST".equalsIgnoreCase(metodo) && ROTA_LANCE.equals(caminho)) {
                processarLanceWeb(troca);
                return;
            }

            if ("POST".equalsIgnoreCase(metodo) && ROTA_CRIAR_LEILAO.equals(caminho)) {
                processarCriacaoWeb(troca);
                return;
            }

            redirecionar(troca, "/?msg=" + codificarUrl("Página não encontrada."));
        } catch (RuntimeException erro) {
            redirecionar(troca, "/?msg=" + codificarUrl("Erro inesperado: " + erro.getMessage()));
        } finally {
            troca.close();
        }
    }

    private void processarLogin(HttpExchange troca) throws IOException {
        if (!servidorAceitaComandos()) {
            redirecionar(troca, "/?msg="
                    + codificarUrl("Este servidor está como réplica. Ações web bloqueadas."));
            return;
        }

        Map<String, String> formulario = lerFormulario(troca);
        String usuario = formulario.getOrDefault("usuario", "").trim();
        String senha = formulario.getOrDefault("senha", "");

        RepositorioUsuarios.ResultadoAutenticacao resultado =
                repositorioUsuarios.autenticarOuCadastrar(usuario, senha);

        if (!resultado.sucesso) {
            redirecionar(troca, "/?msg=" + codificarUrl("Falha no login: " + resultado.mensagem));
            return;
        }

        String idSessao = UUID.randomUUID().toString();
        sessoes.put(idSessao, usuario);

        logDistribuido.registrar(gerenciadorLeiloes.obterLamportAtual(),
                (resultado.cadastroNovo ? "USUARIO_CADASTRADO_WEB " : "USUARIO_LOGIN_WEB ")
                        + "usuario=" + usuario);

        troca.getResponseHeaders().add("Set-Cookie",
                COOKIE_SESSAO + "=" + idSessao + "; Path=/; HttpOnly; SameSite=Lax");
        redirecionar(troca, "/?msg=" + codificarUrl(resultado.mensagem));
    }

    private void processarLogout(HttpExchange troca) throws IOException {
        String idSessao = obterIdSessao(troca);
        if (idSessao != null) {
            sessoes.remove(idSessao);
        }

        troca.getResponseHeaders().add("Set-Cookie",
                COOKIE_SESSAO + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
        redirecionar(troca, "/?msg=" + codificarUrl("Você saiu da sessão web."));
    }

    private void processarLanceWeb(HttpExchange troca) throws IOException {
        String usuario = obterUsuarioLogado(troca);
        if (usuario == null) {
            redirecionar(troca, "/?msg=" + codificarUrl("Faça login antes de dar lance."));
            return;
        }

        if (!servidorAceitaComandos()) {
            redirecionar(troca, "/?msg="
                    + codificarUrl("Este servidor não é o primário atual. Lance não enviado."));
            return;
        }

        Map<String, String> formulario = lerFormulario(troca);

        try {
            int idLeilao = Integer.parseInt(formulario.getOrDefault("idLeilao", ""));
            double valor = Double.parseDouble(
                    formulario.getOrDefault("valor", "").replace(",", "."));

            Leilao.ResultadoLance resultado =
                    gerenciadorLeiloes.registrarLance(idLeilao, usuario, valor);

            if (!resultado.aceito) {
                logDistribuido.registrar(gerenciadorLeiloes.obterLamportAtual(),
                        "LANCE_RECUSADO_WEB leilao=" + idLeilao
                                + " usuario=" + usuario
                                + " valor=" + valor
                                + " motivo=\"" + resultado.mensagem + "\"");
                redirecionar(troca, "/?msg=" + codificarUrl("Lance recusado: "
                        + resultado.mensagem));
                return;
            }

            coordenadorPrimario.replicarAposLanceAceito(idLeilao, resultado.lance);
            logDistribuido.registrar(resultado.lance.obterTimestampLamport(),
                    "LANCE_ACEITO_WEB leilao=" + idLeilao
                            + " usuario=" + usuario
                            + " valor=" + valor);

            String mensagemGlobal = "[WEB] Leilão #" + idLeilao
                    + ": novo lance de " + usuario + " no valor de R$ "
                    + String.format("%.2f", valor);
            registroClientes.enviarParaTodos(mensagemGlobal);

            String mensagem = resultado.mensagem;
            if (resultado.cronometroEstendido) {
                mensagem += " Cronômetro estendido pelo anti-sniping.";
            }
            redirecionar(troca, "/?msg=" + codificarUrl(mensagem));
        } catch (NumberFormatException erro) {
            redirecionar(troca, "/?msg="
                    + codificarUrl("Informe um ID e um valor de lance válidos."));
        }
    }

    private void processarCriacaoWeb(HttpExchange troca) throws IOException {
        String usuario = obterUsuarioLogado(troca);
        if (usuario == null) {
            redirecionar(troca, "/?msg=" + codificarUrl("Faça login antes de criar leilão."));
            return;
        }

        if (!servidorAceitaComandos()) {
            redirecionar(troca, "/?msg="
                    + codificarUrl("Este servidor não é o primário atual. Leilão não criado."));
            return;
        }

        Map<String, String> formulario = lerFormulario(troca);
        String descricao = formulario.getOrDefault("descricao", "").trim();

        try {
            double precoInicial = Double.parseDouble(
                    formulario.getOrDefault("precoInicial", "").replace(",", "."));

            GerenciadorLeiloes.ResultadoCriacaoLeilao resultado =
                    gerenciadorLeiloes.criarLeilaoDinamico(descricao, precoInicial);

            if (!resultado.aceito) {
                redirecionar(troca, "/?msg=" + codificarUrl("Criação recusada: "
                        + resultado.mensagem));
                return;
            }

            coordenadorPrimario.replicarAposLeilaoCriado(
                    resultado.leilao, resultado.timestampLamport);
            logDistribuido.registrar(resultado.timestampLamport,
                    "LEILAO_CRIADO_WEB leilao=" + resultado.leilao.obterId()
                            + " usuario=" + usuario
                            + " precoInicial=" + precoInicial
                            + " item=\"" + descricao + "\"");

            String mensagemGlobal = "[WEB] Novo leilão #"
                    + resultado.leilao.obterId()
                    + " criado por " + usuario
                    + ": " + resultado.leilao.obterDescricaoItem();
            registroClientes.enviarParaTodos(mensagemGlobal);

            redirecionar(troca, "/?msg=" + codificarUrl(resultado.mensagem));
        } catch (NumberFormatException erro) {
            redirecionar(troca, "/?msg="
                    + codificarUrl("Informe um preço inicial válido."));
        }
    }

    private String construirPaginaHtml(HttpExchange troca, String mensagem) {
        String usuario = obterUsuarioLogado(troca);
        boolean podeComandar = servidorAceitaComandos();

        String horaAtual = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));

        String corPrimaria = podeComandar ? "#176b3a" : "#8b1a1a";
        String statusServidor = podeComandar
                ? "Ações habilitadas neste servidor"
                : "Modo leitura: este processo não é o primário atual";

        StringBuilder html = new StringBuilder();

        html.append("<!DOCTYPE html>");
        html.append("<html lang='pt-BR'>");
        html.append("<head>");
        html.append("<meta charset='UTF-8'>");
        html.append("<meta name='viewport' content='width=device-width, initial-scale=1.0'>");
        html.append("<title>Leilão Online Distribuído</title>");
        html.append("<style>");
        adicionarCss(html, corPrimaria);
        html.append("</style>");
        html.append("</head>");

        html.append("<body>");
        construirCabecalho(html, horaAtual, statusServidor);

        html.append("<main class='container'>");
        if (mensagem != null && !mensagem.isBlank()) {
            html.append("<section class='alerta'>").append(escaparHtml(mensagem)).append("</section>");
        }

        construirBlocoSessao(html, usuario, podeComandar);

        if (usuario != null && podeComandar) {
            construirFormularioCriacao(html);
        }

        construirListaLeiloes(html, usuario, podeComandar);
        html.append("</main>");

        html.append("<footer>");
        html.append("Sistema de Leilão Distribuído • Sockets TCP, HTTP, Lamport, replicação e failover");
        html.append("</footer>");
        html.append("</body></html>");

        return html.toString();
    }

    private void construirCabecalho(StringBuilder html, String horaAtual, String statusServidor) {
        html.append("<header>");
        html.append("<div>");
        html.append("<span class='etiqueta'>Sistemas Distribuídos</span>");
        html.append("<h1>Leilão Online</h1>");
        html.append("<p>Participe dos leilões pelo navegador. Atualize a página para ver novos lances.</p>");
        html.append("</div>");
        html.append("<div class='status-servidor'>");
        html.append("<strong>").append(escaparHtml(papelAtual)).append("</strong>");
        html.append("<span>").append(escaparHtml(statusServidor)).append("</span>");
        html.append("<small>Lamport: ").append(gerenciadorLeiloes.obterLamportAtual())
                .append(" • ").append(horaAtual).append("</small>");
        html.append("</div>");
        html.append("</header>");
    }

    private void construirBlocoSessao(StringBuilder html, String usuario, boolean podeComandar) {
        html.append("<section class='painel sessao'>");

        if (!podeComandar) {
            html.append("<div>");
            html.append("<h2>Servidor em modo réplica</h2>");
            html.append("<p>Este nó está exibindo o estado replicado, mas não aceita login, lances ou criação de leilões.</p>");
            html.append("</div>");
        } else if (usuario == null) {
            html.append("<div>");
            html.append("<h2>Entrar ou cadastrar</h2>");
            html.append("<p>Se o usuário não existir, ele será cadastrado automaticamente.</p>");
            html.append("</div>");
            html.append("<form method='post' action='/login' class='form-inline'>");
            html.append("<input name='usuario' placeholder='Usuário' required>");
            html.append("<input name='senha' type='password' placeholder='Senha' required>");
            html.append("<button type='submit'>Entrar</button>");
            html.append("</form>");
        } else {
            html.append("<div>");
            html.append("<h2>Olá, ").append(escaparHtml(usuario)).append("</h2>");
            html.append("<p>Você pode criar leilões e enviar lances pela interface web.</p>");
            html.append("</div>");
            html.append("<form method='post' action='/logout'>");
            html.append("<button class='secundario' type='submit'>Sair</button>");
            html.append("</form>");
        }

        html.append("</section>");
    }

    private void construirFormularioCriacao(StringBuilder html) {
        html.append("<section class='painel'>");
        html.append("<div class='titulo-secao'>");
        html.append("<h2>Criar novo leilão</h2>");
        html.append("<p>O novo leilão será replicado para o servidor secundário.</p>");
        html.append("</div>");
        html.append("<form method='post' action='/criar-leilao' class='form-grid'>");
        html.append("<label>Descrição do item");
        html.append("<input name='descricao' placeholder='Ex: PlayStation 5' required>");
        html.append("</label>");
        html.append("<label>Preço inicial");
        html.append("<input name='precoInicial' type='number' step='0.01' min='0.01' placeholder='2500.00' required>");
        html.append("</label>");
        html.append("<button type='submit'>Criar leilão</button>");
        html.append("</form>");
        html.append("</section>");
    }

    private void construirListaLeiloes(StringBuilder html, String usuario, boolean podeComandar) {
        List<Leilao> leiloes = new ArrayList<>(gerenciadorLeiloes.obterTodosLeiloes().values());
        leiloes.sort(Comparator.comparingInt(Leilao::obterId));

        html.append("<section>");
        html.append("<div class='titulo-secao'>");
        html.append("<h2>Leilões disponíveis</h2>");
        html.append("<p>").append(leiloes.size()).append(" item(ns) encontrados.</p>");
        html.append("</div>");

        if (leiloes.isEmpty()) {
            html.append("<div class='vazio'>Nenhum leilão disponível no momento.</div>");
            html.append("</section>");
            return;
        }

        html.append("<div class='grade-leiloes'>");
        for (Leilao leilao : leiloes) {
            construirCardLeilao(html, leilao, usuario, podeComandar);
        }
        html.append("</div>");
        html.append("</section>");
    }

    private void construirCardLeilao(
            StringBuilder html, Leilao leilao, String usuario, boolean podeComandar) {

        boolean ativo = leilao.estaAtivo();
        String vencedorAtual = leilao.obterVencedorAtual();
        List<Lance> historico = leilao.obterHistoricoLances();
        String lamportUltimoLance = historico.isEmpty()
                ? "—"
                : String.valueOf(historico.get(historico.size() - 1).obterTimestampLamport());

        html.append("<article class='card'>");
        html.append("<div class='card-topo'>");
        html.append("<span class='id'>#").append(leilao.obterId()).append("</span>");
        html.append("<span class='badge ").append(ativo ? "ativo" : "encerrado").append("'>")
                .append(ativo ? "ATIVO" : "ENCERRADO").append("</span>");
        html.append("</div>");

        html.append("<h3>").append(escaparHtml(leilao.obterDescricaoItem())).append("</h3>");

        html.append("<dl class='detalhes'>");
        html.append("<div><dt>Lance atual</dt><dd>R$ ")
                .append(formatarMoeda(leilao.obterMaiorLanceAtual())).append("</dd></div>");
        html.append("<div><dt>Vencedor atual</dt><dd>")
                .append(vencedorAtual != null ? escaparHtml(vencedorAtual) : "Nenhum")
                .append("</dd></div>");
        html.append("<div><dt>Tempo restante</dt><dd>")
                .append(ativo ? leilao.obterTempoRestanteSegundos() + "s" : "Encerrado")
                .append("</dd></div>");
        html.append("<div><dt>Lamport último lance</dt><dd>")
                .append(lamportUltimoLance).append("</dd></div>");
        html.append("</dl>");

        if (ativo && usuario != null && podeComandar) {
            html.append("<form method='post' action='/lance' class='lance-form'>");
            html.append("<input type='hidden' name='idLeilao' value='").append(leilao.obterId()).append("'>");
            html.append("<input name='valor' type='number' step='0.01' min='0.01' placeholder='Seu lance' required>");
            html.append("<button type='submit'>Dar lance</button>");
            html.append("</form>");
        } else if (ativo && usuario == null && podeComandar) {
            html.append("<p class='dica'>Faça login para participar deste leilão.</p>");
        } else if (!podeComandar) {
            html.append("<p class='dica'>Ações bloqueadas nesta réplica.</p>");
        }

        construirHistoricoResumido(html, historico);
        html.append("</article>");
    }

    private void construirHistoricoResumido(StringBuilder html, List<Lance> historico) {
        html.append("<div class='historico'>");
        html.append("<strong>Últimos lances</strong>");

        if (historico.isEmpty()) {
            html.append("<p>Nenhum lance ainda.</p>");
            html.append("</div>");
            return;
        }

        html.append("<ul>");
        int inicio = Math.max(0, historico.size() - 3);
        for (int indice = historico.size() - 1; indice >= inicio; indice--) {
            Lance lance = historico.get(indice);
            html.append("<li>");
            html.append(escaparHtml(lance.obterNomeLicitante()));
            html.append(" • R$ ").append(formatarMoeda(lance.obterValor()));
            html.append(" • L").append(lance.obterTimestampLamport());
            html.append("</li>");
        }
        html.append("</ul>");
        html.append("</div>");
    }

    private void adicionarCss(StringBuilder html, String corPrimaria) {
        html.append(":root { --primaria: ").append(corPrimaria).append("; --fundo: #f4f6f8; --texto: #1f2933; }");
        html.append("* { box-sizing: border-box; }");
        html.append("body { margin: 0; font-family: Arial, sans-serif; color: var(--texto); background: var(--fundo); }");
        html.append("header { background: linear-gradient(135deg, var(--primaria), #102a43); color: white; padding: 28px clamp(18px, 4vw, 48px); display: flex; justify-content: space-between; gap: 24px; align-items: center; flex-wrap: wrap; }");
        html.append("h1, h2, h3, p { margin-top: 0; }");
        html.append("h1 { margin-bottom: 8px; font-size: clamp(2rem, 5vw, 3.5rem); }");
        html.append("h2 { margin-bottom: 6px; }");
        html.append("h3 { margin: 14px 0; font-size: 1.25rem; }");
        html.append(".etiqueta { display: inline-block; padding: 5px 10px; border-radius: 999px; background: rgba(255,255,255,.18); font-size: .82rem; margin-bottom: 12px; }");
        html.append(".status-servidor { background: rgba(255,255,255,.13); border: 1px solid rgba(255,255,255,.28); border-radius: 16px; padding: 16px; min-width: min(340px, 100%); }");
        html.append(".status-servidor strong, .status-servidor span, .status-servidor small { display: block; }");
        html.append(".status-servidor span { margin: 6px 0; opacity: .95; }");
        html.append(".container { width: min(1180px, calc(100% - 32px)); margin: 24px auto 40px; }");
        html.append(".painel, .card, .alerta, .vazio { background: white; border: 1px solid #e3e8ef; border-radius: 18px; box-shadow: 0 10px 30px rgba(15,23,42,.06); }");
        html.append(".painel { padding: 20px; margin-bottom: 20px; }");
        html.append(".sessao { display: flex; align-items: center; justify-content: space-between; gap: 18px; flex-wrap: wrap; }");
        html.append(".alerta { padding: 14px 16px; border-left: 5px solid var(--primaria); margin-bottom: 18px; font-weight: 600; }");
        html.append(".titulo-secao { display: flex; justify-content: space-between; gap: 14px; align-items: end; flex-wrap: wrap; margin: 24px 0 12px; }");
        html.append(".titulo-secao p { color: #62748a; margin-bottom: 0; }");
        html.append(".form-inline, .form-grid, .lance-form { display: flex; gap: 10px; align-items: end; flex-wrap: wrap; }");
        html.append(".form-grid label { display: grid; gap: 6px; flex: 1 1 240px; font-size: .9rem; font-weight: 700; color: #52606d; }");
        html.append("input { border: 1px solid #cbd5e1; border-radius: 12px; padding: 12px 13px; min-width: 160px; font: inherit; background: #fff; }");
        html.append("button { border: 0; border-radius: 12px; padding: 12px 16px; font: inherit; font-weight: 700; cursor: pointer; color: white; background: var(--primaria); }");
        html.append("button.secundario { background: #52606d; }");
        html.append(".grade-leiloes { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 18px; }");
        html.append(".card { padding: 18px; display: flex; flex-direction: column; gap: 10px; }");
        html.append(".card-topo { display: flex; justify-content: space-between; align-items: center; }");
        html.append(".id { font-weight: 800; color: var(--primaria); }");
        html.append(".badge { padding: 4px 10px; border-radius: 999px; font-size: .75rem; font-weight: 800; }");
        html.append(".badge.ativo { background: #dcfce7; color: #166534; }");
        html.append(".badge.encerrado { background: #fee2e2; color: #991b1b; }");
        html.append(".detalhes { display: grid; gap: 10px; margin: 0; }");
        html.append(".detalhes div { display: flex; justify-content: space-between; gap: 12px; border-bottom: 1px dashed #d9e2ec; padding-bottom: 8px; }");
        html.append("dt { color: #62748a; }");
        html.append("dd { margin: 0; font-weight: 700; text-align: right; }");
        html.append(".lance-form input { flex: 1 1 150px; }");
        html.append(".historico { background: #f8fafc; border-radius: 14px; padding: 12px; margin-top: auto; }");
        html.append(".historico strong { display: block; margin-bottom: 8px; }");
        html.append(".historico p { color: #62748a; margin: 0; }");
        html.append(".historico ul { margin: 0; padding-left: 18px; color: #3e4c59; }");
        html.append(".dica { color: #62748a; background: #f8fafc; border-radius: 12px; padding: 10px; margin-bottom: 0; }");
        html.append(".vazio { padding: 28px; text-align: center; color: #62748a; }");
        html.append("footer { text-align: center; color: #7b8794; padding: 24px; font-size: .9rem; }");
        html.append("@media (max-width: 640px) { .form-inline input, .form-inline button, .form-grid button, .lance-form button { width: 100%; } }");
    }

    private boolean servidorAceitaComandos() {
        return coordenadorPrimario != null
                && registroClientes != null
                && repositorioUsuarios != null
                && logDistribuido != null
                && aceitaComandos.getAsBoolean();
    }

    private Map<String, String> lerFormulario(HttpExchange troca) throws IOException {
        String corpo = new String(troca.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> valores = new HashMap<>();

        if (corpo.isBlank()) {
            return valores;
        }

        for (String par : corpo.split("&")) {
            String[] partes = par.split("=", 2);
            String chave = decodificarUrl(partes[0]);
            String valor = partes.length > 1 ? decodificarUrl(partes[1]) : "";
            valores.put(chave, valor);
        }

        return valores;
    }

    private String obterMensagemDaUrl(HttpExchange troca) {
        String consulta = troca.getRequestURI().getRawQuery();
        if (consulta == null || consulta.isBlank()) {
            return null;
        }

        for (String par : consulta.split("&")) {
            String[] partes = par.split("=", 2);
            if ("msg".equals(decodificarUrl(partes[0]))) {
                return partes.length > 1 ? decodificarUrl(partes[1]) : "";
            }
        }

        return null;
    }

    private String obterUsuarioLogado(HttpExchange troca) {
        String idSessao = obterIdSessao(troca);
        return idSessao != null ? sessoes.get(idSessao) : null;
    }

    private String obterIdSessao(HttpExchange troca) {
        List<String> cookies = troca.getRequestHeaders().get("Cookie");
        if (cookies == null) {
            return null;
        }

        for (String cabecalho : cookies) {
            for (String cookie : cabecalho.split(";")) {
                String[] partes = cookie.trim().split("=", 2);
                if (partes.length == 2 && COOKIE_SESSAO.equals(partes[0])) {
                    return partes[1];
                }
            }
        }

        return null;
    }

    private void responderHtml(HttpExchange troca, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        troca.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        troca.sendResponseHeaders(200, bytes.length);

        try (OutputStream saida = troca.getResponseBody()) {
            saida.write(bytes);
        }
    }

    private void redirecionar(HttpExchange troca, String destino) throws IOException {
        troca.getResponseHeaders().set("Location", destino);
        troca.sendResponseHeaders(303, -1);
    }

    private String escaparHtml(String texto) {
        if (texto == null) {
            return "";
        }

        return texto
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String codificarUrl(String texto) {
        return URLEncoder.encode(texto, StandardCharsets.UTF_8);
    }

    private String decodificarUrl(String texto) {
        return URLDecoder.decode(texto, StandardCharsets.UTF_8);
    }

    private String formatarMoeda(double valor) {
        return String.format("%.2f", valor);
    }
}
