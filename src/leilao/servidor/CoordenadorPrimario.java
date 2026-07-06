package leilao.servidor;

import leilao.dominio.Lance;
import leilao.dominio.Leilao;

/**
 * Contrato usado por quem está atuando como servidor primário.
 *
 * Tanto o ServidorLeilao original quanto a ServidorReplica promovida precisam
 * replicar alterações depois de lances e criação de leilões.
 */
public interface CoordenadorPrimario {

    void replicarAposLanceAceito(int idLeilao, Lance lance);

    void replicarAposLeilaoCriado(Leilao leilao, long timestampLamport);
}
