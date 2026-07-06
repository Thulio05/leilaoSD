package leilao.cliente;

import leilao.config.ConfiguracaoRede;
import leilao.config.DiscoveryMdns;

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
    private static final int PORTA_CLIENTES = CONFIGURACAO.obterPortaClientes();
    private static final int TIMEOUT_CONEXAO_MS = CONFIGURACAO.obterTimeoutConexaoMs();
    private static final long ESPERA_RECONEXAO_MS = 8_000;

    private Socket socket;
    private PrintWriter saida;
    private BufferedReader entrada;
    private volatile boolean conectado;
    private volatile boolean reconectando;
    private volatile boolean encerrando;
    private volatile boolean loginConcluido;
    private String nomeUsuario;
    private String senhaUsuario;

    public boolean conectar() {
        return conectarAUmServidor(false);
    }

    private boolean conectarAUmServidor(boolean reenviarNome) {
        // Tentativa 1: descoberta automática via broadcast (sem precisar de IP)
        DiscoveryMdns.ServidorEncontrado encontrado = DiscoveryMdns.descobrirPrimario();
        if (encontrado != null) {
            if (tentarConectar(encontrado.ip, encontrado.portaClientes, reenviarNome)) {
                return true;
            }
        }

        // Tentativa 2: fallback para os IPs do config.properties
        System.out.println("[INFO] Discovery não resolveu. Tentando IPs do config.properties...");
        String[] candidatos = {
                CONFIGURACAO.obterEnderecoPrimario(),
                CONFIGURACAO.obterEnderecoReplica()
        };
        String[] papeis = {"PRIMÁRIO", "SECUNDÁRIO"};

        for (int i = 0; i < candidatos.length; i++) {
            System.out.println("[INFO] Tentando servidor " + papeis[i]
                    + " (" + candidatos[i] + ":" + PORTA_CLIENTES + ")...");
            if (tentarConectar(candidatos[i], PORTA_CLIENTES, reenviarNome)) {
                return true;
            }
        }

        conectado = false;
        return false;
    }

    private boolean tentarConectar(String endereco, int porta, boolean reenviarNome) {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(endereco, porta), TIMEOUT_CONEXAO_MS);
            saida   = new PrintWriter(socket.getOutputStream(), true);
            entrada = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            conectado = true;

            if (reenviarNome && nomeUsuario != null && senhaUsuario != null) {
                saida.println(nomeUsuario);
                saida.println(senhaUsuario);
            }

            System.out.println("[INFO] Conectado a " + endereco + ":" + porta + ".");
            return true;
        } catch (IOException erro) {
            fecharConexaoAtual();
            System.out.println("[ALERTA] Falha em " + endereco + ":" + porta
                    + " — " + erro.getMessage());
            return false;
        }
    }

    public void iniciarInteracao() {
        if (!conectado) {
            return;
        }

        // Thread de leitura inicia ANTES do loop para capturar as mensagens
        // de login do servidor ("Digite seu nome", "Digite sua senha").
        // A flag loginConcluido controla quando o modo assíncrono entra de fato.
        iniciarThreadDeLeitura();

        try (Scanner scanner = new Scanner(System.in)) {
            while (!encerrando && scanner.hasNextLine()) {
                String textoDigitado = scanner.nextLine();

                if (nomeUsuario == null) {
                    nomeUsuario = textoDigitado;
                    if (!enviarTexto(textoDigitado)) {
                        tratarQuedaDeConexao();
                    }
                    // Pequena pausa para o servidor processar e enviar
                    // a pergunta da senha antes do usuário digitar
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    continue;
                }

                if (senhaUsuario == null) {
                    senhaUsuario = textoDigitado;
                    if (!enviarTexto(textoDigitado)) {
                        tratarQuedaDeConexao();
                    }
                    loginConcluido = true;
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
