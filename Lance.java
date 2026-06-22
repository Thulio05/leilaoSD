import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Representa um lance aceito pelo servidor. */
public class Lance implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String nomeLicitante;
    private final double valor;
    private final long timestampLamport;
    private final LocalDateTime horarioReal;

    public Lance(String nomeLicitante, double valor, long timestampLamport) {
        this.nomeLicitante = nomeLicitante;
        this.valor = valor;
        this.timestampLamport = timestampLamport;
        this.horarioReal = LocalDateTime.now();
    }

    public long obterTimestampLamport() {
        return timestampLamport;
    }

    @Override
    public String toString() {
        String horario = horarioReal.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        return String.format("[%s | Lamport=%d] %s ofereceu R$ %.2f",
                horario, timestampLamport, nomeLicitante, valor);
    }
}
