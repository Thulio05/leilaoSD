package leilao.persistencia;

import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Grava eventos do servidor com timestamp de Lamport. */
public class LogDistribuido {

    private static final DateTimeFormatter FORMATO_HORARIO =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String caminhoArquivo;

    public LogDistribuido(String nomeArquivo) {
        this.caminhoArquivo = nomeArquivo;
    }

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
