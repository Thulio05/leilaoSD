# Sistema de Leilão Distribuído — MVP de Terminal

Projeto acadêmico em Java que demonstra comunicação por sockets, concorrência,
relógio lógico de Lamport, replicação de estado, heartbeat e failover — agora
também preparado para rodar em mais de uma máquina e tolerar queda de rede.

## Estrutura

```text
config.properties          # endereços/portas configuráveis (novas máquinas mexem só aqui)
src/
└── leilao/
    ├── cliente/
    │   └── ClienteLeilao.java
    ├── config/
    │   └── ConfiguracaoRede.java
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
| `leilao.config` | Leitura de `config.properties`: endereços, portas e timeout de rede |
| `leilao.dominio` | Regras do leilão, lances, relógio de Lamport e estado replicado |
| `leilao.persistencia` | Cadastro de usuários e logs em arquivo |
| `leilao.servidor` | Servidor primário, réplica, painel, broadcast e atendimento dos clientes |

> Este projeto entrega só a parte de **rede configurável / execução em mais
> de uma máquina** e a **tolerância a queda de conexão**. As outras partes
> do trabalho (servidor de nomes / DNS e balanceador de carga) ficam por
> conta do restante do grupo, em pacotes novos (por exemplo `leilao.dns` e
> `leilao.balanceador`) sem precisar mexer no que já está pronto aqui.

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

## Tolerância a queda de rede / internet

O professor vai testar desligando a internet no meio da avaliação. Isso já
foi levado em conta no código, sem precisar de nenhuma peça nova:

- **Toda tentativa de conexão tem um limite de tempo** (`rede.timeout.conexao.ms`,
  padrão 3 segundos). Antes, `new Socket(host, porta)` podia ficar esperando
  o sistema operacional decidir sozinho que a rede caiu — o que às vezes
  demora muito. Agora o próprio código desiste depois de 3s e tenta de novo
  mais tarde, então nenhum processo trava esperando uma máquina que saiu do ar.
- **O primário nunca depende da réplica para continuar atendendo clientes.**
  Se a conexão com a réplica cair, o primário só registra o alerta no
  console e segue atendendo quem já está conectado a ele — as tentativas de
  replicação e o heartbeat continuam tentando reconectar sozinhas, sem
  derrubar o processo.
- **A réplica detecta a queda do primário sozinha, sem depender da rede
  avisar.** Ela não fica bloqueada esperando dados chegarem: uma thread à
  parte só compara "quanto tempo faz desde o último sinal do primário"
  a cada 2 segundos. Depois de 6 segundos sem sinal (mesmo que o cabo tenha
  sido arrancado, sem nenhum aviso educado do sistema operacional), ela
  assume sozinha a porta de clientes.
- **O cliente nunca desiste.** Se a conexão cai, ele entra em loop de
  reconexão a cada 8 segundos, para sempre, até um dos dois servidores
  responder — e quando reconecta, reenvia login/senha automaticamente e
  volta a funcionar sem o usuário precisar reiniciar nada.

Testei esse cenário simulando a queda do primário (matando o processo, que
para efeitos de rede é o mesmo tipo de falha de "perder o sinal"): o cliente
com um lance em andamento continuou funcionando, a réplica assumiu sozinha em
~6s, o cliente reconectou sozinho e o próximo lance foi aceito normalmente
no novo primário — sem reiniciar nada manualmente.

### Como simular a queda de rede/internet para o professor testar

Numa única máquina (mais fácil de ensaiar antes da apresentação):

1. Suba a réplica, o primário e um cliente (veja "Execução" abaixo).
2. Faça login e dê um lance para confirmar que está tudo normal.
3. Force o fim do processo do primário (`Ctrl+C` no terminal dele, ou
   `taskkill` / `kill -9`) — isso simula a queda de rede daquela máquina.
4. Espere uns 6-8 segundos: no terminal da réplica aparece
   `[FAILOVER] Réplica assumindo como novo servidor primário`, e no terminal
   do cliente aparece a reconexão automática.
5. Dê outro lance: ele deve ser aceito normalmente pelo novo primário.

Em duas máquinas reais, o teste do professor (desconectar o cabo/Wi-Fi de
uma delas) tem o mesmo efeito: quem perde o sinal simplesmente some da rede,
e o lado que sobrou continua funcionando e detecta a falta de sinal do mesmo
jeito.

**Uma limitação para deixar clara para o professor:** o cadastro de usuários
(`usuarios.txt`) é lido do disco só uma vez, quando cada processo inicia. Em
uma única máquina isso não é problema, porque os dois processos compartilham
a mesma pasta. Em duas máquinas diferentes, cada uma tem seu próprio
`usuarios.txt` — então, para o failover reconhecer os mesmos usuários dos
dois lados, copie o `usuarios.txt` para as duas máquinas antes de começar o
teste. Não há sincronização desse arquivo em tempo real entre as máquinas;
é uma simplificação aceitável para o escopo deste MVP.

## Execução

Abra três terminais na pasta do projeto (cada máquina roda o processo que
lhe cabe, com o `config.properties` daquela máquina já ajustado):

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
