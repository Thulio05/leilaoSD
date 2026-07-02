import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Grava eventos do servidor em um arquivo de texto, sempre com o
 * timestamp de Lamport associado ao evento.
 *
 * Cada processo (primário e réplica) escreve no seu próprio arquivo.
 * Comparando os dois arquivos lado a lado é possível verificar que os
 * eventos replicados aparecem na mesma ordem lógica nos dois servidores,
 * o que é a forma mais simples de demonstrar consistência entre réplicas.
 */
public class LogDistribuido {

    private static final DateTimeFormatter FORMATO_HORARIO =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String caminhoArquivo;

    public LogDistribuido(String nomeArquivo) {
        this.caminhoArquivo = nomeArquivo;
    }

    /**
     * Acrescenta uma linha ao arquivo de log.
     *
     * synchronized porque heartbeat, lances e failover podem gerar eventos
     * em threads diferentes ao mesmo tempo; sem isso duas linhas poderiam
     * se intercalar no meio da escrita.
     */
    public synchronized void registrar(long timestampLamport, String evento) {
        String horario = LocalDateTime.now().format(FORMATO_HORARIO);
        String linha = String.format("[Lamport=%-5d | %s] %s", timestampLamport, horario, evento);

        System.out.println("[LOG] " + linha);

        try (FileWriter escritor = new FileWriter(caminhoArquivo, true)) {
            escritor.write(linha + System.lineSeparator());
        } catch (IOException erro) {
            System.out.println("[ALERTA] Não foi possível gravar no log '"
                    + caminhoArquivo + "': " + erro.getMessage());
        }
    }
}
