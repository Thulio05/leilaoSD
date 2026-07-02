# Sistema de Leilão Distribuído — MVP de Terminal

Projeto acadêmico em Java que demonstra comunicação por sockets, concorrência,
relógio lógico de Lamport, replicação de estado, heartbeat e failover.

## Estrutura

```text
src/
└── leilao/
    ├── cliente/
    │   └── ClienteLeilao.java
    ├── dominio/
    │   ├── GerenciadorLeiloes.java
    │   ├── Lance.java
    │   ├── Leilao.java
    │   └── RelogioLamport.java
    ├── persistencia/
    │   ├── LogDistribuido.java
    │   └── RepositorioUsuarios.java
    └── servidor/
        ├── PainelMonitoramento.java
        ├── RegistroClientes.java
        ├── ServidorLeilao.java
        ├── ServidorReplica.java
        └── TratadorCliente.java
```

| Pacote | Responsabilidade |
|---|---|
| `leilao.cliente` | Cliente de terminal com reconexão automática |
| `leilao.dominio` | Regras do leilão, lances, relógio de Lamport e estado replicado |
| `leilao.persistencia` | Cadastro de usuários e logs em arquivo |
| `leilao.servidor` | Servidor primário, réplica, painel, broadcast e atendimento dos clientes |

## Compilação

No PowerShell:

```powershell
javac -encoding UTF-8 -d out (Get-ChildItem -Recurse src -Filter *.java).FullName
```

No Git Bash:

```bash
javac -encoding UTF-8 -d out $(find src -name "*.java")
```

## Execução

Abra três terminais na pasta do projeto.

1. Inicie a réplica:

```bash
java -cp out leilao.servidor.ServidorReplica
```

2. Inicie o servidor primário:

```bash
java -cp out leilao.servidor.ServidorLeilao
```

3. Inicie o cliente:

```bash
java -cp out leilao.cliente.ClienteLeilao
```

## Comandos

```text
listar
status <id>
lance <id> <valor>
historico <id>
criarleilao <preco_inicial> <descricao>
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
7. Um cliente também pode criar novos leilões com `criarleilao`; o novo
   estado é replicado e divulgado por broadcast.
8. Ao conectar ou reconectar, o cliente recebe o estado e os últimos lances.
9. O primário envia heartbeat a cada 2 segundos.
10. Após 6 segundos sem sinal, a réplica assume a porta de clientes.
11. O cliente espera 8 segundos e tenta reconectar, reenviando nome e senha.
12. Eventos relevantes (login, criação de leilões, lances, replicação, failover, encerramento)
    são gravados com o timestamp de Lamport em `log_primario.txt` ou
    `log_replica.txt`, dependendo de qual processo está ativo no momento.

## Conceitos demonstrados

- Sockets TCP entre processos com memórias separadas.
- Uma thread dedicada por cliente.
- `synchronized` para proteger seções críticas.
- `ConcurrentHashMap` para acesso concorrente ao conjunto de leilões.
- Registro concorrente de clientes e broadcast em tempo real.
- Criação dinâmica de leilões e replicação do novo estado.
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
