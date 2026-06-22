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

1. Cada cliente é atendido por uma thread de `TratadorCliente`.
2. `GerenciadorLeiloes.registrarLance()` ordena o processamento e avança
   o relógio de Lamport.
3. `Leilao.adicionarLance()` valida e altera atomicamente o leilão.
4. O primário tenta replicar o estado antes de confirmar o lance ao cliente.
5. O lance aceito é enviado por broadcast a todos os clientes conectados.
6. Ao conectar ou reconectar, o cliente recebe o estado e os últimos lances.
7. O primário envia heartbeat a cada 2 segundos.
8. Após 6 segundos sem sinal, a réplica assume a porta de clientes.
9. O cliente espera 8 segundos e tenta reconectar, reenviando o nome de usuário.

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

O failover é determinístico para dois servidores. Ele não implementa o protocolo
completo do algoritmo Bully, pois não há mensagens de eleição nem disputa de IDs.
