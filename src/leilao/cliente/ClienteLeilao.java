package leilao.cliente;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Scanner;
import java.util.UUID;
import leilao.config.ConfiguracaoRede;

/** Cliente de terminal com leitura assíncrona e reconexão automática. */
public class ClienteLeilao {

    private static final ConfiguracaoRede CONFIGURACAO = ConfiguracaoRede.instancia();
    private static final int PORTA_CLIENTES = CONFIGURACAO.obterPortaClientes();
    private static final int TIMEOUT_CONEXAO_MS = CONFIGURACAO.obterTimeoutConexaoMs();
    private static final long ESPERA_RECONEXAO_MS = 8_000;

    private Socket socket;
    private PrintWriter saida;
    private BufferedReader entrada;
    private volatile boolean conectado;
    private volatile boolean reconectando;
    private volatile boolean encerrando;
    private volatile String comandoLancePendente;
    private String nomeUsuario;
    private String senhaUsuario;

    public boolean conectar() {
        return conectarAUmServidor(false);
    }

    /**
     * Tenta o servidor primário e, se estiver fora do ar (ou sem rede até
     * ele), tenta a réplica em seguida. Os dois endereços vêm do
     * config.properties, então funcionam tanto numa única máquina quanto
     * em máquinas diferentes na rede.
     */
    private boolean conectarAUmServidor(boolean reenviarNome) {
        String[] enderecosCandidatos = {
                CONFIGURACAO.obterEnderecoPrimario(),
                CONFIGURACAO.obterEnderecoReplica()
        };
        String[] papeis = {"PRIMÁRIO", "SECUNDÁRIO"};

        for (int indice = 0; indice < enderecosCandidatos.length; indice++) {
            String endereco = enderecosCandidatos[indice];
            String papel = papeis[indice];

            System.out.println("[INFO] Tentando conectar ao servidor " + papel
                    + " (" + endereco + ":" + PORTA_CLIENTES + ")...");

            try {
                socket = new Socket();
                socket.connect(
                        new InetSocketAddress(endereco, PORTA_CLIENTES), TIMEOUT_CONEXAO_MS);
                saida = new PrintWriter(socket.getOutputStream(), true);
                entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                conectado = true;

                if (reenviarNome && nomeUsuario != null && senhaUsuario != null) {
                    saida.println(nomeUsuario);
                    saida.println(senhaUsuario);

                    if (comandoLancePendente != null) {
                        System.out.println("[IDEMPOTÊNCIA] Reenviando lance pendente após reconexão.");
                        saida.println(comandoLancePendente);
                    }
                }

                System.out.println("[INFO] Conectado ao servidor " + papel + ".");
                return true;
            } catch (IOException erro) {
                fecharConexaoAtual();
                System.out.println("[ALERTA] Servidor " + papel
                        + " indisponível: " + erro.getMessage());
            }
        }

        conectado = false;
        return false;
    }

    public void iniciarInteracao() {
        if (!conectado) {
            return;
        }

        iniciarThreadDeLeitura();

        try (Scanner scanner = new Scanner(System.in)) {
            while (!encerrando && scanner.hasNextLine()) {
                String textoDigitado = scanner.nextLine();

                if (nomeUsuario == null) {
                    nomeUsuario = textoDigitado;
                    if (!enviarTexto(textoDigitado)) {
                        tratarQuedaDeConexao();
                    }
                    continue;
                }

                if (senhaUsuario == null) {
                    senhaUsuario = textoDigitado;
                    if (!enviarTexto(textoDigitado)) {
                        tratarQuedaDeConexao();
                    }
                    continue;
                }

                if (textoDigitado.equalsIgnoreCase("sair")) {
                    encerrando = true;
                    enviarTexto(textoDigitado);
                    break;
                }

                String textoParaEnviar = prepararComandoDeLanceSeNecessario(textoDigitado);
                if (!enviarTexto(textoParaEnviar)) {
                    tratarQuedaDeConexao();
                }
            }
        }

        encerrando = true;
        fecharConexaoAtual();
        System.out.println("[INFO] Cliente encerrado.");
    }

    private void iniciarThreadDeLeitura() {
        Thread threadLeitura =
                new Thread(this::ouvirServidor, "Thread-Leitura-Servidor");
        threadLeitura.setDaemon(true);
        threadLeitura.start();
    }

    private void ouvirServidor() {
        BufferedReader entradaDestaConexao = entrada;

        try {
            String linha;
            while ((linha = entradaDestaConexao.readLine()) != null) {
                System.out.println(linha);

                if (comandoLancePendente != null && linha.contains("Lance")) {
                    comandoLancePendente = null;
                }
            }

            if (!encerrando) {
                System.out.println("[ALERTA] O servidor encerrou a conexão.");
                tratarQuedaDeConexao();
            }
        } catch (IOException erro) {
            if (!encerrando && conectado) {
                System.out.println("[ALERTA] Conexão perdida com o servidor.");
                tratarQuedaDeConexao();
            }
        }
    }

    private boolean enviarTexto(String texto) {
        if (!conectado || saida == null) {
            return false;
        }

        saida.println(texto);
        return !saida.checkError();
    }

    private synchronized void tratarQuedaDeConexao() {
        if (reconectando || encerrando) {
            return;
        }

        reconectando = true;
        conectado = false;
        fecharConexaoAtual();

        while (!encerrando && !conectado) {
            System.out.println("[INFO] Nova tentativa de conexão em 8 segundos...");

            try {
                Thread.sleep(ESPERA_RECONEXAO_MS);
            } catch (InterruptedException erro) {
                Thread.currentThread().interrupt();
                break;
            }

            if (!encerrando && conectarAUmServidor(true)) {
                System.out.println("[INFO] Reconectado. A sessão foi restaurada.");
                iniciarThreadDeLeitura();
            }
        }

        reconectando = false;
    }

    private String prepararComandoDeLanceSeNecessario(String textoDigitado) {
        String[] partes = textoDigitado.trim().split("\\s+");

        if (partes.length == 3
                && ("lance".equalsIgnoreCase(partes[0])
                || "lancar".equalsIgnoreCase(partes[0]))) {
            String bidId = UUID.randomUUID().toString();
            String comandoComBidId = textoDigitado.trim() + " " + bidId;
            comandoLancePendente = comandoComBidId;
            return comandoComBidId;
        }

        return textoDigitado;
    }

    private void fecharConexaoAtual() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignorado) {
        }
    }

    public static void main(String[] args) {
        ClienteLeilao cliente = new ClienteLeilao();
        if (cliente.conectar()) {
            cliente.iniciarInteracao();
        }
    }
}
