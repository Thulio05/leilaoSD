/**
 * Relógio lógico compartilhado pelas threads do servidor.
 */
public class RelogioLamport {

    private long contador = 0;

    /** Gera o próximo timestamp lógico. */
    public synchronized long avancar() {
        contador++;
        return contador;
    }

    /** Aplica a regra de recebimento de Lamport: L = max(L, T) + 1. */
    public synchronized long sincronizarRecebimento(long timestampRecebido) {
        contador = Math.max(contador, timestampRecebido) + 1;
        return contador;
    }

    public synchronized long obterValorAtual() {
        return contador;
    }
}
