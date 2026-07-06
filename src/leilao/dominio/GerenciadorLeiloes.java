package leilao.dominio;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Coordena os leilões, o relógio lógico e o estado replicado. */
public class GerenciadorLeiloes {

    private final ConcurrentHashMap<Integer, Leilao> leiloes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Leilao.ResultadoLance> lancesPorBidId =
            new ConcurrentHashMap<>();
    private final RelogioLamport relogioLamport = new RelogioLamport();
    private int proximoId = 1;

    public synchronized Leilao criarLeilao(String descricaoItem, double precoInicial) {
        Leilao leilao = new Leilao(proximoId, descricaoItem, precoInicial);
        leiloes.put(proximoId, leilao);
        proximoId++;
        return leilao;
    }

    public synchronized ResultadoCriacaoLeilao criarLeilaoDinamico(
            String descricaoItem, double precoInicial) {

        if (descricaoItem == null || descricaoItem.trim().isEmpty()) {
            return ResultadoCriacaoLeilao.recusado("A descrição do item não pode ser vazia.");
        }

        if (precoInicial <= 0) {
            return ResultadoCriacaoLeilao.recusado("O preço inicial deve ser positivo.");
        }

        long timestamp = relogioLamport.avancar();
        Leilao leilao = criarLeilao(descricaoItem.trim(), precoInicial);

        System.out.println("[LAMPORT] Criação do leilão #" + leilao.obterId()
                + " recebeu timestamp lógico " + timestamp + ".");

        return ResultadoCriacaoLeilao.aceito(leilao, timestamp);
    }

    public synchronized Leilao.ResultadoLance registrarLance(
            int idLeilao, String nomeLicitante, double valor, String bidId) {

        if (bidId != null) {
            Leilao.ResultadoLance resultadoAnterior = lancesPorBidId.get(bidId);
            if (resultadoAnterior != null) {
                System.out.println("[IDEMPOTÊNCIA] bidId=" + bidId
                        + " já processado. Reenvio detectado para o leilão #"
                        + idLeilao + ".");
                return Leilao.ResultadoLance.duplicado(resultadoAnterior);
            }
        }

        Leilao leilao = leiloes.get(idLeilao);
        if (leilao == null) {
            return Leilao.ResultadoLance.recusado("Leilão #" + idLeilao + " não existe.");
        }

        long timestamp = relogioLamport.avancar();
        System.out.println("[LAMPORT] Lance de '" + nomeLicitante
                + "' recebeu timestamp lógico " + timestamp + ".");

        Leilao.ResultadoLance resultado = leilao.adicionarLance(nomeLicitante, valor, timestamp);

        if (bidId != null && resultado.aceito) {
            lancesPorBidId.put(bidId, resultado);
        }

        return resultado;
    }

    public String obterStatusLeilao(int idLeilao) {
        Leilao leilao = leiloes.get(idLeilao);
        return leilao != null ? leilao.obterStatusFormatado() : "Leilão não encontrado.";
    }

    public String listarLeiloes() {
        if (leiloes.isEmpty()) {
            return "Nenhum leilão disponível no momento.";
        }

        StringBuilder texto = new StringBuilder("=== LEILÕES DISPONÍVEIS ===\n");
        for (Leilao leilao : leiloes.values()) {
            texto.append(String.format(
                    "#%d - %-30s | Lance Atual: R$ %8.2f | %s | %ds restantes\n",
                    leilao.obterId(),
                    leilao.obterDescricaoItem(),
                    leilao.obterMaiorLanceAtual(),
                    leilao.estaAtivo() ? "ATIVO" : "ENCERRADO",
                    leilao.obterTempoRestanteSegundos()));
        }
        return texto.toString();
    }

    public String obterHistoricoLances(int idLeilao) {
        Leilao leilao = leiloes.get(idLeilao);
        if (leilao == null) {
            return "Leilão não encontrado.";
        }

        StringBuilder texto = new StringBuilder(
                "=== HISTÓRICO DE LANCES - LEILÃO #" + idLeilao + " ===\n");
        List<Lance> historico = leilao.obterHistoricoLances();
        if (historico.isEmpty()) {
            texto.append("(nenhum lance registrado ainda)\n");
        } else {
            for (Lance lance : historico) {
                texto.append(lance).append("\n");
            }
        }
        return texto.toString();
    }

    public String criarResumoAtualParaCliente() {
        List<Leilao> leiloesOrdenados = new ArrayList<>(leiloes.values());
        leiloesOrdenados.sort(Comparator.comparingInt(Leilao::obterId));

        if (leiloesOrdenados.isEmpty()) {
            return "Nenhum leilão disponível no momento.";
        }

        StringBuilder texto = new StringBuilder("=== ESTADO ATUAL DO SISTEMA ===\n");
        for (Leilao leilao : leiloesOrdenados) {
            texto.append(leilao.obterStatusFormatado());

            List<Lance> historico = leilao.obterHistoricoLances();
            if (historico.isEmpty()) {
                texto.append("Últimos Lances: nenhum\n");
            } else {
                texto.append("Últimos Lances:\n");
                int primeiroIndice = Math.max(0, historico.size() - 5);
                for (int indice = primeiroIndice; indice < historico.size(); indice++) {
                    texto.append("  ").append(historico.get(indice)).append("\n");
                }
            }
            texto.append("------------------------------\n");
        }
        return texto.toString();
    }

    public Map<Integer, Leilao> obterTodosLeiloes() {
        return new HashMap<>(leiloes);
    }

    public long obterLamportAtual() {
        return relogioLamport.obterValorAtual();
    }

    public void sincronizarLamport(long timestampRecebido) {
        relogioLamport.sincronizarRecebimento(timestampRecebido);
    }

    public synchronized EstadoReplicado criarEstadoReplicado(Map<String, String> usuarios) {
        Map<String, String> copiaUsuarios =
                usuarios == null ? new HashMap<>() : new HashMap<>(usuarios);

        return new EstadoReplicado(
                new HashMap<>(leiloes),
                new HashMap<>(lancesPorBidId),
                proximoId,
                relogioLamport.obterValorAtual(),
                copiaUsuarios);
    }

    public synchronized void restaurarEstadoReplicado(EstadoReplicado estado) {
        leiloes.clear();
        leiloes.putAll(estado.obterLeiloes());

        lancesPorBidId.clear();
        if (estado.obterLancesPorBidId() != null) {
            lancesPorBidId.putAll(estado.obterLancesPorBidId());
        }

        proximoId = estado.obterProximoId();
        relogioLamport.sincronizarRecebimento(estado.obterTimestampLamport());
    }

    /** Dados enviados do servidor primário para a réplica. */
    public static class EstadoReplicado implements Serializable {
        private static final long serialVersionUID = 3L;

        private final Map<Integer, Leilao> leiloes;
        private final Map<String, Leilao.ResultadoLance> lancesPorBidId;
        private final int proximoId;
        private final long timestampLamport;
        private final Map<String, String> usuarios;

        public EstadoReplicado(
                Map<Integer, Leilao> leiloes,
                Map<String, Leilao.ResultadoLance> lancesPorBidId,
                int proximoId,
                long timestampLamport,
                Map<String, String> usuarios) {
            this.leiloes = leiloes;
            this.lancesPorBidId = lancesPorBidId;
            this.proximoId = proximoId;
            this.timestampLamport = timestampLamport;
            this.usuarios = usuarios;
        }

        public Map<Integer, Leilao> obterLeiloes() {
            return leiloes;
        }

        public Map<String, Leilao.ResultadoLance> obterLancesPorBidId() {
            return lancesPorBidId;
        }

        public int obterProximoId() {
            return proximoId;
        }

        public long obterTimestampLamport() {
            return timestampLamport;
        }

        public Map<String, String> obterUsuarios() {
            return usuarios;
        }
    }

    public static class ResultadoCriacaoLeilao {
        public final boolean aceito;
        public final String mensagem;
        public final Leilao leilao;
        public final long timestampLamport;

        private ResultadoCriacaoLeilao(
                boolean aceito, String mensagem, Leilao leilao, long timestampLamport) {
            this.aceito = aceito;
            this.mensagem = mensagem;
            this.leilao = leilao;
            this.timestampLamport = timestampLamport;
        }

        public static ResultadoCriacaoLeilao aceito(Leilao leilao, long timestampLamport) {
            return new ResultadoCriacaoLeilao(
                    true, "Leilão criado com sucesso!", leilao, timestampLamport);
        }

        public static ResultadoCriacaoLeilao recusado(String motivo) {
            return new ResultadoCriacaoLeilao(false, motivo, null, -1);
        }
    }
}
