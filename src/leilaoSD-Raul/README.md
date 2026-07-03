# Sistema de Leilão Distribuído — MVP de Terminal

Projeto acadêmico em Java que demonstra comunicação por sockets, concorrência,
relógio lógico de Lamport, replicação de estado, heartbeat, failover,
servidor de nomes (DNS simplificado) e balanceamento de carga.

## Estrutura

```text
config.properties          # endereços/portas configuráveis (novas máquinas mexem só aqui)
docs/
└── diagrama-dns.svg        # diagrama de elementos e fluxo do DNS simplificado
src/
└── leilao/
    ├── cliente/
    │   └── ClienteLeilao.java
    ├── config/
    │   └── ConfiguracaoRede.java
    ├── dns/
    │   ├── ServidorNomes.java
    │   └── ClienteDNS.java
    ├── balanceador/
    │   └── BalanceadorCarga.java
    ├── dominio/
    │   ├── GerenciadorLeiloes.java
    │   ├── Lance.java
    │   ├── Leilao.java
    │   └── RelogioLamport.java
    ├── persistencia/
    │   ├── LogDistribuido.java
    │   └── RepositorioUsuarios.java
    └── servidor/
        ├── CoordenadorPrimario.java
        ├── PainelMonitoramento.java
        ├── RegistroClientes.java
        ├── ServidorLeilao.java
        ├── ServidorReplica.java
        └── TratadorCliente.java
```

| Pacote | Responsabilidade |
|---|---|
| `leilao.cliente` | Cliente de terminal com reconexão automática |
| `leilao.config` | Leitura de `config.properties`: endereços e portas de todo o sistema |
| `leilao.dns` | Servidor de nomes simplificado ("DNS") e cliente que o consulta |
| `leilao.balanceador` | Balanceador de carga round-robin (proxy TCP) para os painéis web |
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

## Configuração de rede (rodar em mais de uma máquina)

Antes, os endereços dos servidores estavam fixos em `"localhost"` no código,
o que só funciona quando tudo roda na mesma máquina. Agora, todo endereço e
toda porta vêm de `config.properties`, na raiz do projeto:

```properties
endereco.primario=localhost
endereco.replica=localhost
endereco.dns=localhost
porta.clientes=5555
porta.replicacao=6000
porta.http=8080
porta.dns=5300
porta.balanceador=5500
```

Para rodar em mais de uma máquina, copie o projeto para cada uma e, em cada
`config.properties`, troque `localhost` pelo IP real daquela máquina (todas
precisam enxergar as outras na mesma rede). Se o arquivo não existir, o
sistema usa `localhost` como padrão e continua funcionando normalmente para
testes em um único computador — nada quebra por causa da nova configuração.

Qualquer valor também pode ser sobrescrito na hora de rodar, sem editar o
arquivo, com `-Dchave=valor`:

```bash
java -Dporta.http=8081 -cp out leilao.servidor.ServidorReplica
```

Isso é útil para testar dois processos com o mesmo papel (dois painéis web,
por exemplo) na mesma máquina, sem os dois brigarem pela mesma porta.

## DNS simplificado (servidor de nomes)

Em vez de o cliente já nascer sabendo o IP de cada servidor, ele pergunta a
um `ServidorNomes` (o "DNS" do projeto): "onde está o `primario`?" / "onde
está a `replica`?". O servidor de nomes responde com o endereço atual
(`IP:porta`), lido do próprio `config.properties`.

Protocolo (texto simples, uma consulta por conexão — igual a um DNS real,
só que bem mais simples de explicar):

```text
Cliente         -> RESOLVE primario
Servidor de nomes -> OK 192.168.0.10:5555
```

Veja o diagrama de elementos e fluxo em `docs/diagrama-dns.svg`.

Rodando:

```bash
java -cp out leilao.dns.ServidorNomes
```

Se o servidor de nomes estiver fora do ar, o cliente não trava: ele cai de
volta automaticamente para os endereços fixos do `config.properties`. Isso é
o `ClienteDNS.resolver()` fazendo essa checagem antes de cada tentativa de
conexão.

## Balanceador de carga

O primário e a réplica já rodam, cada um, o próprio painel web de
monitoramento (`PainelMonitoramento`, porta HTTP configurável — padrão
8080), e os dois ficam ativos ao mesmo tempo (diferente da porta de
clientes, que só o líder atende). O `BalanceadorCarga` aproveita isso:
ele escuta numa porta pública (padrão 5500) e encaminha cada nova conexão,
em round-robin, para um dos painéis configurados em `balanceador.backends`.

```properties
balanceador.backends=localhost:8080,localhost:8080
```

Rodando:

```bash
java -cp out leilao.balanceador.BalanceadorCarga
```

Depois, acesse `http://localhost:5500/monitor` várias vezes (ou dê refresh):
a cada nova conexão o balanceador alterna entre os backends, distribuindo o
acesso entre o primário e a réplica em vez de sobrecarregar um só. O
encaminhamento é feito byte a byte (proxy TCP simples), então funciona para
qualquer protocolo TCP — não só HTTP.

## Execução

Abra os terminais necessários na pasta do projeto (pelo menos 3; o servidor
de nomes e o balanceador são opcionais, mas fazem parte do trabalho):

1. (Opcional, mas recomendado) Inicie o servidor de nomes:

```bash
java -cp out leilao.dns.ServidorNomes
```

2. Inicie a réplica:

```bash
java -cp out leilao.servidor.ServidorReplica
```

3. Inicie o servidor primário:

```bash
java -cp out leilao.servidor.ServidorLeilao
```

4. Inicie o cliente:

```bash
java -cp out leilao.cliente.ClienteLeilao
```

5. (Opcional) Inicie o balanceador de carga para os painéis web:

```bash
java -cp out leilao.balanceador.BalanceadorCarga
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
11. Depois da promoção, a réplica também passa a replicar estado para um
    novo secundário.
12. Se o primário antigo voltar e a porta 5555 já estiver ocupada, ele não
    tenta recuperar a liderança: ele inicia automaticamente como nova réplica.
13. O cliente espera 8 segundos e tenta reconectar, reenviando nome e senha.
14. Eventos relevantes (login, criação de leilões, lances, replicação, failover, encerramento)
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
- Reintegração do servidor antigo como nova réplica após o retorno.
- Anti-sniping com extensão de 30 segundos.
- Cadastro e login persistidos em arquivo, com senha em hash SHA-256.
- Log distribuído de eventos com timestamp de Lamport, um arquivo por
  processo, para comparar a ordem de eventos entre réplicas.

O failover é determinístico para dois servidores. Ele não implementa o protocolo
completo do algoritmo Bully, pois não há mensagens de eleição nem disputa de IDs.
A liderança fica com o processo que mantém a porta 5555. Se o antigo primário
voltar enquanto a réplica promovida ainda estiver ativa, ele entra como
secundário para evitar dois primários ao mesmo tempo.

## Teste de failover com retorno do primário antigo

1. Compile o projeto.
2. Abra a réplica com `java -cp out leilao.servidor.ServidorReplica`.
3. Abra o primário com `java -cp out leilao.servidor.ServidorLeilao`.
4. Abra um cliente, faça login e execute `listar` ou envie um lance.
5. Pare o terminal do primário original.
6. Aguarde cerca de 6 segundos: a réplica deve assumir a porta 5555.
7. O cliente deve reconectar depois de 8 segundos.
8. Rode novamente `java -cp out leilao.servidor.ServidorLeilao`.
9. Como a porta 5555 já está ocupada, esse processo deve iniciar como réplica.
10. Faça outro lance ou crie um leilão: o novo primário deve replicar o estado
    para essa réplica de retorno.

## Persistência de usuários

O arquivo `usuarios.txt` é compartilhado pelos dois processos (primário e
réplica), pois ambos rodam na mesma pasta. Cada processo carrega esse
arquivo apenas uma vez, no momento em que inicia. Por isso:

- Um cadastro feito no primário só aparece para a réplica depois que ela
  relê o arquivo. Isso acontece automaticamente no instante do failover
  (`promoverParaPrimario()` chama `recarregarDoArquivo()`), e não antes.
- Se o primário antigo cair e voltar enquanto a réplica promovida ainda está
  ativa, ele entra como nova réplica e também relê o arquivo de usuários ao
  iniciar.
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
