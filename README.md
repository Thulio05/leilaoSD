# Sistema de Leilão Distribuído — MVP de Terminal

Projeto acadêmico em Java que demonstra comunicação por sockets, concorrência,
relógio lógico de Lamport, replicação de estado, heartbeat e failover.

## Arquivos

| Arquivo | Responsabilidade |
|---|---|
| `RelogioLamport.java` | Gera e sincroniza timestamps lógicos |
| `Lance.java` | Representa um lance aceito |
| `Leilao.java` | Mantém as regras e o estado de um leilão |
| `GerenciadorLeiloes.java` | Coordena os leilões e cria o estado replicado |
| `RegistroClientes.java` | Mantém clientes conectados e realiza broadcast |
| `RepositorioUsuarios.java` | Cadastro e login de usuários, persistidos em arquivo |
| `LogDistribuido.java` | Grava eventos com timestamp de Lamport em arquivo |
| `TratadorCliente.java` | Atende um cliente em uma thread dedicada |
| `ServidorLeilao.java` | Servidor primário, replicação e heartbeat |
| `ServidorReplica.java` | Réplica passiva e failover por timeout |
| `ClienteLeilao.java` | Cliente de terminal com reconexão automática |

## Compilação

```bash
javac -encoding UTF-8 *.java
```

## Execução

Abra três terminais na pasta do projeto.

1. Inicie a réplica:

```bash
java ServidorReplica
```

2. Inicie o servidor primário:

```bash
java ServidorLeilao
```

3. Inicie o cliente:

```bash
java ClienteLeilao
```

## Comandos

```text
listar
status <id>
lance <id> <valor>
historico <id>
ajuda
sair
```

## Fluxo principal

1. Ao conectar, o cliente informa nome e senha. Nome novo cadastra na hora;
   nome já existente exige a senha cadastrada antes.
2. Cada cliente autenticado é atendido por uma thread de `TratadorCliente`.
3. `GerenciadorLeiloes.registrarLance()` ordena o processamento e avança
   o relógio de Lamport.
4. `Leilao.adicionarLance()` valida e altera atomicamente o leilão.
5. O primário tenta replicar o estado antes de confirmar o lance ao cliente.
6. O lance aceito é enviado por broadcast a todos os clientes conectados.
7. Ao conectar ou reconectar, o cliente recebe o estado e os últimos lances.
8. O primário envia heartbeat a cada 2 segundos.
9. Após 6 segundos sem sinal, a réplica assume a porta de clientes.
10. O cliente espera 8 segundos e tenta reconectar, reenviando nome e senha.
11. Eventos relevantes (login, lances, replicação, failover, encerramento)
    são gravados com o timestamp de Lamport em `log_primario.txt` ou
    `log_replica.txt`, dependendo de qual processo está ativo no momento.

## Conceitos demonstrados

- Sockets TCP entre processos com memórias separadas.
- Uma thread dedicada por cliente.
- `synchronized` para proteger seções críticas.
- `ConcurrentHashMap` para acesso concorrente ao conjunto de leilões.
- Registro concorrente de clientes e broadcast em tempo real.
- Relógio lógico de Lamport para ordenar lances.
- Replicação completa de estado por serialização Java.
- Heartbeat e detecção de falha por timeout.
- Failover automático para a réplica sobrevivente.
- Anti-sniping com extensão de 30 segundos.
- Cadastro e login persistidos em arquivo, com senha em hash SHA-256.
- Log distribuído de eventos com timestamp de Lamport, um arquivo por
  processo, para comparar a ordem de eventos entre réplicas.

O failover é determinístico para dois servidores. Ele não implementa o protocolo
completo do algoritmo Bully, pois não há mensagens de eleição nem disputa de IDs.

## Persistência de usuários

O arquivo `usuarios.txt` é compartilhado pelos dois processos (primário e
réplica), pois ambos rodam na mesma pasta. Cada processo carrega esse
arquivo apenas uma vez, no momento em que inicia. Por isso:

- Um cadastro feito no primário só aparece para a réplica depois que ela
  relê o arquivo. Isso acontece automaticamente no instante do failover
  (`promoverParaPrimario()` chama `recarregarDoArquivo()`), e não antes.
- Se o primário cair e voltar a subir como primário de novo, ele também
  relê o arquivo do zero ao iniciar, então cadastros feitos enquanto ele
  estava fora não se perdem.
- Isto é uma simplificação aceitável para o MVP: não há sincronização de
  usuários em tempo real entre os dois processos, só na borda do failover.

## Log distribuído

Cada processo grava em seu próprio arquivo (`log_primario.txt` ou
`log_replica.txt`). Os eventos registrados incluem: início do servidor,
login e cadastro de usuários, lances aceitos e recusados, confirmação de
replicação de estado, recebimento de estado pela réplica, failover e
encerramento de leilões — todos com o timestamp de Lamport correspondente.

Para demonstrar consistência entre réplicas, abra os dois arquivos lado a
lado depois de um teste com failover: os eventos replicados (como
`LANCE_ACEITO` seguido de `REPLICACAO_RECEBIDA`) devem aparecer com o
mesmo timestamp de Lamport nos dois lados.
