package leilao.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Configuração de rede centralizada do sistema.
 *
 * Antes, endereços e portas estavam fixos ("localhost", 5555, 6000, 8080...)
 * espalhados pelas classes de servidor e cliente. Isso impedia rodar o
 * projeto em mais de uma máquina, porque "localhost" sempre aponta para a
 * própria máquina.
 *
 * Esta classe lê um arquivo texto simples ("config.properties", formato
 * chave=valor) que fica na pasta onde o processo é executado. Cada máquina
 * da rede pode ter seu próprio arquivo com os IPs corretos. Se o arquivo
 * não existir, valores padrão de "localhost" são usados, então o projeto
 * continua funcionando exatamente como antes para quem só quer testar em
 * uma única máquina.
 *
 * Qualquer chave também pode ser sobrescrita na hora de rodar o programa
 * com "-Dchave=valor", sem precisar editar arquivo nenhum. Exemplo:
 *
 *   java -Dendereco.replica=192.168.0.11 -cp out leilao.servidor.ServidorLeilao
 *
 * Ordem de prioridade de cada valor: argumento -D (linha de comando)
 * > config.properties > valor padrão.
 */
public final class ConfiguracaoRede {

    private static final String ARQUIVO_CONFIGURACAO = "config.properties";
    private static ConfiguracaoRede instanciaUnica;

    private final Properties propriedades = new Properties();

    private ConfiguracaoRede() {
        carregarArquivoSeExistir();
    }

    public static synchronized ConfiguracaoRede instancia() {
        if (instanciaUnica == null) {
            instanciaUnica = new ConfiguracaoRede();
        }
        return instanciaUnica;
    }

    private void carregarArquivoSeExistir() {
        Path caminhoBase = Paths.get("").toAbsolutePath().normalize();
        Path caminhoArquivo = caminhoBase.resolve(ARQUIVO_CONFIGURACAO);

        if (!Files.exists(caminhoArquivo)) {
            System.out.println("[CONFIG] " + ARQUIVO_CONFIGURACAO
                    + " não encontrado em " + caminhoArquivo
                    + ". Usando valores padrão (localhost).");
            return;
        }

        try (InputStream entrada = Files.newInputStream(caminhoArquivo)) {
            propriedades.load(entrada);
            System.out.println("[CONFIG] " + ARQUIVO_CONFIGURACAO + " carregado de " + caminhoArquivo + ".");
        } catch (IOException erro) {
            System.out.println("[CONFIG] Erro ao ler " + caminhoArquivo + ": " + erro.getMessage());
        }
    }

    private String obterTexto(String chave, String valorPadrao) {
        String valorLinhaDeComando = System.getProperty(chave);
        if (valorLinhaDeComando != null && !valorLinhaDeComando.isBlank()) {
            return valorLinhaDeComando;
        }
        return propriedades.getProperty(chave, valorPadrao);
    }

    private int obterNumero(String chave, int valorPadrao) {
        try {
            return Integer.parseInt(obterTexto(chave, String.valueOf(valorPadrao)));
        } catch (NumberFormatException erro) {
            System.out.println("[CONFIG] Valor inválido para " + chave
                    + ". Usando padrão: " + valorPadrao);
            return valorPadrao;
        }
    }

    // ---- Endereços dos processos (o que muda quando vai para outra máquina) ----

    public String obterEnderecoPrimario() {
        return obterTexto("endereco.primario", "localhost");
    }

    public String obterEnderecoReplica() {
        return obterTexto("endereco.replica", "localhost");
    }

    // ---- Portas de cada serviço ----

    public int obterPortaClientes() {
        return obterNumero("porta.clientes", 5555);
    }

    public int obterPortaReplicacao() {
        return obterNumero("porta.replicacao", 6000);
    }

    public int obterPortaHttp() {
        return obterNumero("porta.http", 8080);
    }

    public int obterPortaDiscovery() {
        return obterNumero("porta.discovery", 5354);
    }

    public int obterPortaGatewayWeb() {
        return obterNumero("porta.gateway.web", 8088);
    }

    // ---- Tolerância a falha de rede ----

    /**
     * Tempo máximo (em milissegundos) que qualquer tentativa de conexão
     * (cliente -> servidor ou servidor -> servidor) espera antes de desistir.
     * Sem esse limite, uma queda de rede (cabo desconectado, Wi-Fi
     * derrubado) poderia deixar o processo travado por muito tempo esperando
     * o sistema operacional decidir sozinho que a conexão falhou.
     */
    public int obterTimeoutConexaoMs() {
        return obterNumero("rede.timeout.conexao.ms", 3_000);
    }

    public int obterTimeoutDiscoveryMs() {
        return obterNumero("rede.timeout.discovery.ms", 5_000);
    }
}
