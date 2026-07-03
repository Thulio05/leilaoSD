package leilao.cliente;

import leilao.config.ConfiguracaoRede;
import leilao.dns.ClienteDNS;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Scanner;

/** Cliente de terminal com leitura assíncrona e reconexão automática. */
public class ClienteLeilao {

    private static final ConfiguracaoRede CONFIGURACAO = ConfiguracaoRede.instancia();
    private static final int TIMEOUT_CONEXAO_MS = 3_000;
    private static final long ESPERA_RECONEXAO_MS = 8_000;
    private static final String[] NOMES_CANDIDATOS = {"primario", "replica"};

    private Socket socket;
    private PrintWriter saida;
    private BufferedReader entrada;
    private volatile boolean conectado;
    private volatile boolean reconectando;
    private volatile boolean encerrando;
    private String nomeUsuario;
    private String senhaUsuario;

    public boolean conectar() {
        return conectarAUmServidor(false);
    }

    private boolean conectarAUmServidor(boolean reenviarNome) {
        for (int indice = 0; indice < NOMES_CANDIDATOS.length; indice++) {
            String nomeLogico = NOMES_CANDIDATOS[indice];
            String papel = indice == 0 ? "PRIMÁRIO" : "SECUNDÁRIO";
            String[] enderecoEPorta = resolverEndereco(nomeLogico);
            String endereco = enderecoEPorta[0];
            int porta = Integer.parseInt(enderecoEPorta[1]);

            System.out.println("[INFO] Tentando conectar ao servidor " + papel
                    + " (" + nomeLogico + " -> " + endereco + ":" + porta + ")...");

            try {
                socket = new Socket();
                socket.connect(
                        new InetSocketAddress(endereco, porta), TIMEOUT_CONEXAO_MS);
                saida = new PrintWriter(socket.getOutputStream(), true);
                entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                conectado = true;

                if (reenviarNome && nomeUsuario != null && senhaUsuario != null) {
                    saida.println(nomeUsuario);
                    saida.println(senhaUsuario);
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

    /**
     * Descobre o endereço (IP:porta) de um servidor a partir do nome lógico
     * ("primario" ou "replica"). Primeiro pergunta ao servidor de nomes
     * (DNS simplificado); se ele não responder, usa o endereço fixo do
     * config.properties como reserva, para o cliente continuar funcionando
     * mesmo sem o serviço de nomes no ar.
     */
    private String[] resolverEndereco(String nomeLogico) {
        String resolvidoPeloDns = ClienteDNS.resolver(nomeLogico);
        if (resolvidoPeloDns != null && resolvidoPeloDns.contains(":")) {
            return resolvidoPeloDns.split(":", 2);
        }

        String enderecoFallback = nomeLogico.equals("primario")
                ? CONFIGURACAO.obterEnderecoPrimario()
                : CONFIGURACAO.obterEnderecoReplica();
        return new String[]{enderecoFallback, String.valueOf(CONFIGURACAO.obterPortaClientes())};
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

                if (!enviarTexto(textoDigitado)) {
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
