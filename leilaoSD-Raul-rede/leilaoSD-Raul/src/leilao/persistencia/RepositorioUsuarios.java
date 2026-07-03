package leilao.persistencia;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

/** Cadastro e autenticação simples de usuários em arquivo texto. */
public class RepositorioUsuarios {

    private static final String SEPARADOR = "\\|";
    private final String caminhoArquivo;
    private final Map<String, String> usuarios = new HashMap<>();

    public RepositorioUsuarios(String nomeArquivo) {
        this.caminhoArquivo = nomeArquivo;
        carregarDoArquivo();
    }

    private void carregarDoArquivo() {
        try (BufferedReader leitor = new BufferedReader(new FileReader(caminhoArquivo))) {
            String linha;
            while ((linha = leitor.readLine()) != null) {
                String[] partes = linha.split(SEPARADOR, 2);
                if (partes.length == 2) {
                    usuarios.put(partes[0], partes[1]);
                }
            }
            System.out.println("[INFO] " + usuarios.size()
                    + " usuário(s) carregado(s) de '" + caminhoArquivo + "'.");
        } catch (IOException erro) {
            System.out.println("[INFO] Nenhum cadastro anterior encontrado. "
                    + "Um novo arquivo será criado em '" + caminhoArquivo + "'.");
        }
    }

    public synchronized void recarregarDoArquivo() {
        usuarios.clear();
        carregarDoArquivo();
    }

    public static class ResultadoAutenticacao {
        public final boolean sucesso;
        public final String mensagem;
        public final boolean cadastroNovo;

        private ResultadoAutenticacao(boolean sucesso, String mensagem, boolean cadastroNovo) {
            this.sucesso = sucesso;
            this.mensagem = mensagem;
            this.cadastroNovo = cadastroNovo;
        }

        public static ResultadoAutenticacao sucessoLogin() {
            return new ResultadoAutenticacao(true, "Login realizado com sucesso.", false);
        }

        public static ResultadoAutenticacao sucessoCadastro() {
            return new ResultadoAutenticacao(true, "Cadastro realizado com sucesso.", true);
        }

        public static ResultadoAutenticacao falha(String motivo) {
            return new ResultadoAutenticacao(false, motivo, false);
        }
    }

    public synchronized ResultadoAutenticacao autenticarOuCadastrar(String nome, String senha) {
        if (nome == null || nome.trim().isEmpty()) {
            return ResultadoAutenticacao.falha("Nome de usuário não pode ser vazio.");
        }
        if (senha == null || senha.isEmpty()) {
            return ResultadoAutenticacao.falha("Senha não pode ser vazia.");
        }

        String hashDaSenhaDigitada = calcularHash(senha);
        String hashSalvo = usuarios.get(nome);

        if (hashSalvo == null) {
            usuarios.put(nome, hashDaSenhaDigitada);
            salvarNoArquivo(nome, hashDaSenhaDigitada);
            return ResultadoAutenticacao.sucessoCadastro();
        }

        if (!hashSalvo.equals(hashDaSenhaDigitada)) {
            return ResultadoAutenticacao.falha("Senha incorreta para o usuário '" + nome + "'.");
        }

        return ResultadoAutenticacao.sucessoLogin();
    }

    public synchronized boolean existeUsuario(String nome) {
        return usuarios.containsKey(nome);
    }

    private void salvarNoArquivo(String nome, String hashDaSenha) {
        try (FileWriter escritor = new FileWriter(caminhoArquivo, true)) {
            escritor.write(nome + "|" + hashDaSenha + System.lineSeparator());
        } catch (IOException erro) {
            System.out.println("[ALERTA] Não foi possível salvar o usuário '"
                    + nome + "': " + erro.getMessage());
        }
    }

    private String calcularHash(String senha) {
        try {
            MessageDigest algoritmo = MessageDigest.getInstance("SHA-256");
            byte[] bytesDoHash = algoritmo.digest(senha.getBytes());

            StringBuilder hexadecimal = new StringBuilder();
            for (byte b : bytesDoHash) {
                hexadecimal.append(String.format("%02x", b));
            }
            return hexadecimal.toString();
        } catch (NoSuchAlgorithmException erro) {
            throw new IllegalStateException("Algoritmo de hash indisponível.", erro);
        }
    }
}
