package leilao.dominio;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Estado e regras de um único leilão.
 * Os métodos que leem ou alteram estado mutável são sincronizados.
 */
public class Leilao implements Serializable {

    private static final long serialVersionUID = 1L;    
    private static final long DURACAO_PADRAO_MS = 5*60_000;
    private static final long JANELA_ANTI_SNIPING_MS = 30_000;
    private static final long EXTENSAO_ANTI_SNIPING_MS = 30_000;

    private final int id;
    private final String descricaoItem;
    private final double precoInicial;
    private double maiorLanceAtual;
    private String vencedorAtual;
    private final List<Lance> historicoLances;
    private boolean ativo;
    private long horarioEncerramentoMs;

    public Leilao(int id, String descricaoItem, double precoInicial) {
        this.id = id;
        this.descricaoItem = descricaoItem;
        this.precoInicial = precoInicial;
        this.maiorLanceAtual = precoInicial;
        this.historicoLances = new ArrayList<>();
        this.ativo = true;
        this.horarioEncerramentoMs = System.currentTimeMillis() + DURACAO_PADRAO_MS;
    }

    /** Valida e registra atomicamente um novo lance. */
    public synchronized ResultadoLance adicionarLance(
            String nomeLicitante, double valor, long timestampLamport) {

        atualizarStatusSeExpirou();

        if (!ativo) {
            return ResultadoLance.recusado("Este leilão já foi encerrado.");
        }

        if (valor <= maiorLanceAtual) {
            return ResultadoLance.recusado(
                    String.format("Lance muito baixo. O lance atual é R$ %.2f.", maiorLanceAtual));
        }

        long tempoRestante = horarioEncerramentoMs - System.currentTimeMillis();
        boolean cronometroEstendido = tempoRestante <= JANELA_ANTI_SNIPING_MS;
        if (cronometroEstendido) {
            horarioEncerramentoMs += EXTENSAO_ANTI_SNIPING_MS;
        }

        Lance novoLance = new Lance(nomeLicitante, valor, timestampLamport);
        historicoLances.add(novoLance);
        maiorLanceAtual = valor;
        vencedorAtual = nomeLicitante;

        return ResultadoLance.aceito(novoLance, cronometroEstendido);
    }

    private void atualizarStatusSeExpirou() {
        if (ativo && System.currentTimeMillis() > horarioEncerramentoMs) {
            ativo = false;
        }
    }

    public synchronized String obterStatusFormatado() {
        atualizarStatusSeExpirou();

        StringBuilder texto = new StringBuilder();
        texto.append("=== LEILÃO #").append(id).append(" ===\n");
        texto.append("Item: ").append(descricaoItem).append("\n");
        texto.append("Lance Inicial: R$ ").append(String.format("%.2f", precoInicial)).append("\n");
        texto.append("Lance Atual: R$ ").append(String.format("%.2f", maiorLanceAtual)).append("\n");
        texto.append("Vencedor Atual: ").append(vencedorAtual != null ? vencedorAtual : "Nenhum").append("\n");
        texto.append("Total de Lances: ").append(historicoLances.size()).append("\n");
        texto.append("Status: ").append(ativo ? "ATIVO" : "ENCERRADO").append("\n");

        if (ativo) {
            texto.append("Tempo Restante: ").append(obterTempoRestanteSegundos()).append("s\n");
        }
        return texto.toString();
    }

    public synchronized List<Lance> obterHistoricoLances() {
        return new ArrayList<>(historicoLances);
    }

    public int obterId() {
        return id;
    }

    public String obterDescricaoItem() {
        return descricaoItem;
    }

    public synchronized double obterMaiorLanceAtual() {
        return maiorLanceAtual;
    }

    public synchronized String obterVencedorAtual() {
        return vencedorAtual;
    }

    public synchronized boolean estaAtivo() {
        atualizarStatusSeExpirou();
        return ativo;
    }

    public synchronized long obterTempoRestanteSegundos() {
        atualizarStatusSeExpirou();
        if (!ativo) {
            return 0;
        }
        return Math.max(0, (horarioEncerramentoMs - System.currentTimeMillis()) / 1000);
    }

    /** Resultado estruturado de uma tentativa de lance. */
    public static class ResultadoLance {
        public final boolean aceito;
        public final String mensagem;
        public final Lance lance;
        public final boolean cronometroEstendido;

        private ResultadoLance(
                boolean aceito, String mensagem, Lance lance, boolean cronometroEstendido) {
            this.aceito = aceito;
            this.mensagem = mensagem;
            this.lance = lance;
            this.cronometroEstendido = cronometroEstendido;
        }

        public static ResultadoLance aceito(Lance lance, boolean cronometroEstendido) {
            return new ResultadoLance(true, "Lance aceito!", lance, cronometroEstendido);
        }

        public static ResultadoLance recusado(String motivo) {
            return new ResultadoLance(false, motivo, null, false);
        }
    }
}
