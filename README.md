# Sistema de Leilão Distribuído — Guia Completo

Projeto acadêmico em Java que demonstra os principais conceitos de
Sistemas Distribuídos: comunicação por sockets TCP, concorrência,
Relógio de Lamport, replicação de estado, heartbeat e failover automático.

---

## O que cada arquivo faz

### Configuração

**`leilao/config/ConfiguracaoRede.java`**
Lê o arquivo `config.properties` e fornece os endereços IP e portas para
todo o sistema. É o único lugar onde ficam os endereços de rede — quando
você precisa mudar de `localhost` para IPs reais de duas máquinas, só
mexe no `config.properties`, sem tocar em nenhum outro arquivo Java.

Método principal: `instancia()` — retorna a configuração carregada.

---

### Domínio (as regras do leilão)

**`leilao/dominio/RelogioLamport.java`**
Implementa o Relógio Lógico de Lamport. Em vez de depender do relógio
do computador (que pode estar dessincronizado entre máquinas), mantém
um contador inteiro que só cresce. Garante que os lances sejam ordenados
corretamente mesmo que cheguem ao mesmo milissegundo.

Métodos:
- `avancar()` — incrementa o contador e retorna o novo valor. Chamado antes de processar cada lance.
- `sincronizarRecebimento(timestamp)` — aplica a regra `L = max(L, recebido) + 1`. Chamado ao receber mensagens de outra máquina.
- `obterValorAtual()` — consulta o valor atual sem incrementar.

---

**`leilao/dominio/Lance.java`**
Representa um lance aceito. Armazena quem deu o lance, quanto ofereceu,
e qual era o timestamp de Lamport naquele momento. Implementa
`Serializable` para poder ser enviado pelo socket entre máquinas.

---

**`leilao/dominio/Leilao.java`**
Representa um item sendo leiloado. Contém as regras de negócio: validar
se um lance é maior que o atual, registrar o vencedor, controlar o
cronômetro e aplicar o anti-sniping (extensão de 30s se o lance chegar
nos últimos 30s).

Método principal: `adicionarLance(nome, valor, timestamp)` — é
`synchronized`, o que significa que apenas uma thread por vez consegue
entrar neste método. Isso evita que dois lances simultâneos de valores
diferentes sejam ambos aceitos, o que seria uma inconsistência.

---

**`leilao/dominio/GerenciadorLeiloes.java`**
Mantém a lista de todos os leilões em memória e coordena o Relógio de
Lamport. É o "cérebro" do sistema: quando chega um lance, primeiro avança
o relógio (`relogioLamport.avancar()`), depois repassa para o leilão
correspondente.

Métodos principais:
- `registrarLance(id, nome, valor)` — avança o Lamport e tenta registrar o lance no leilão.
- `criarEstadoReplicado()` — empacota todos os leilões + relógio em um objeto para enviar à réplica.
- `restaurarEstadoReplicado(estado)` — substitui o estado local pelo estado recebido do primário.

---

### Persistência (salvar dados em arquivo)

**`leilao/persistencia/RepositorioUsuarios.java`**
Salva e carrega usuários de um arquivo `usuarios.txt`. Nunca guarda a
senha em texto puro — usa hash SHA-256 (função que embaralha a senha de
forma irreversível) antes de salvar. Ao fazer login, embaralha a senha
digitada e compara com o hash salvo.

Método principal: `autenticarOuCadastrar(nome, senha)` — se o nome não
existe, cria o cadastro. Se existe, verifica a senha.

---

**`leilao/persistencia/LogDistribuido.java`**
Grava eventos em um arquivo de texto (`log_primario.txt` ou
`log_replica.txt`), sempre incluindo o timestamp de Lamport. Isso permite
comparar os dois arquivos depois de um teste com failover e verificar que
os mesmos eventos aparecem na mesma ordem lógica nos dois servidores.

---

### Servidor

**`leilao/servidor/CoordenadorPrimario.java`**
Interface (contrato) que define o que um servidor primário deve saber
fazer: replicar após um lance aceito e replicar após um leilão criado.
Tanto o `ServidorLeilao` quanto o `ServidorReplica` (quando promovido)
implementam essa interface — o `TratadorCliente` usa ela sem precisar
saber quem é quem.

---

**`leilao/servidor/RegistroClientes.java`**
Mantém a lista de todos os clientes TCP conectados no momento. Quando um
lance é aceito, o servidor usa este registro para fazer broadcast —
enviar a notificação para todos os clientes de uma vez.

Método principal: `enviarParaTodos(mensagem)` — percorre todos os
clientes conectados e envia a mensagem a cada um.

---

**`leilao/servidor/PainelMonitoramento.java`**
Sobe um mini servidor HTTP na porta 8080 usando apenas bibliotecas do
próprio Java (sem instalar nada extra). Qualquer navegador na rede pode
acessar `http://<IP>:8080/monitor` e ver os leilões em tempo real.

A página usa `<meta http-equiv="refresh" content="2">` para recarregar
sozinha a cada 2 segundos, sem precisar de JavaScript.

Método `atualizarPapel(texto)` — chamado no momento do failover para
mudar o texto e a cor da página de verde (primário) para vermelho
(secundário que assumiu).

---

**`leilao/servidor/TratadorCliente.java`**
Atende UM cliente em uma thread dedicada. O servidor cria uma instância
desta classe e uma thread nova para cada cliente que se conecta, usando
`new Thread(tratador).start()`. Isso é a base da concorrência: cada
cliente tem sua própria thread independente.

Responsabilidades: autenticar o usuário, processar comandos (`listar`,
`lance`, `status`, `historico`, `criarleilao`, `sair`) e enviar as
respostas.

---

**`leilao/servidor/ServidorLeilao.java`**
O servidor primário. Tem quatro tarefas rodando em paralelo em threads
separadas:

1. Aceitar novos clientes TCP (thread principal)
2. Enviar heartbeat à réplica a cada 2s (Thread-Heartbeat)
3. Verificar leilões encerrados (Thread-Monitor-Leiloes)
4. Servir o painel web HTTP (Thread-Painel-Web, daemon)

Quando um lance é aceito pelo `TratadorCliente`, ele chama
`replicarAposLanceAceito()`, que empacota todo o estado e envia pelo
socket para o `ServidorReplica`.

---

**`leilao/servidor/ServidorReplica.java`**
O servidor secundário (réplica). Enquanto o primário está de pé, só
escuta e atualiza seu estado interno com os snapshots recebidos.

Detecção de falha: uma thread verifica a cada 2 segundos se já passou
mais de 6 segundos sem nenhum sinal do primário. Se sim, chama
`promoverParaPrimario()`, que:
1. Assume a porta 5555 e começa a atender clientes
2. Muda a cor do painel web para vermelho
3. Relê o arquivo de usuários para pegar cadastros novos feitos pelo primário

---

### Cliente

**`leilao/cliente/ClienteLeilao.java`**
Cliente de terminal. Tenta conectar ao primário e, se não conseguir, tenta
a réplica. Usa duas threads:
- Thread principal: lê o que o usuário digita e envia ao servidor
- Thread daemon: fica lendo respostas do servidor e exibindo na tela

Se a conexão cair, tenta reconectar automaticamente a cada 8 segundos
(tempo suficiente para o failover terminar no servidor).

**`leilao/cliente/GatewayWebLocal.java`**
Gateway HTTP local para clientes web. O usuário abre `http://localhost:8088`
no próprio computador; o gateway descobre automaticamente o servidor primário
na rede e encaminha as requisições para o painel real. Isso evita digitar o IP
da máquina servidora no navegador.

---

## Como rodar em duas máquinas

### Passo 1 — Instalar o Java nas duas máquinas

Acesse **https://adoptium.net**, baixe e instale o Java 21 (LTS).

Para confirmar: abra o Prompt de Comando e digite `java -version`.
Deve aparecer algo com `21`.

### Passo 2 — Copiar os arquivos

Copie a pasta inteira do projeto para as duas máquinas. Pode usar um
pendrive, Google Drive, ou qualquer forma que preferir.

### Passo 3 — Descobrir os IPs das máquinas

Em cada máquina, abra o Prompt de Comando e digite:

```
ipconfig
```

Procure por **Endereço IPv4**. Vai ser algo como `192.168.1.10`.

> ⚠️ Os dois computadores precisam estar na mesma rede Wi-Fi ou cabo
> para se enxergar. Se um estiver no Wi-Fi e outro no cabo do roteador,
> ainda funciona, desde que seja o mesmo roteador.

### Passo 4 — Configurar o arquivo de rede

Na pasta do projeto existe dois arquivos modelo:
- `config_maquina1_primario.properties`
- `config_maquina2_replica.properties`

**Na Máquina 1** (vai rodar o primário):
1. Copie `config_maquina1_primario.properties` e renomeie para `config.properties`
2. Abra o arquivo com o Bloco de Notas
3. Substitua `192.168.1.10` pelo IP da Máquina 1
4. Substitua `192.168.1.11` pelo IP da Máquina 2
5. Salve

**Na Máquina 2** (vai rodar a réplica):
1. Copie `config_maquina2_replica.properties` e renomeie para `config.properties`
2. Abra o arquivo com o Bloco de Notas
3. Substitua `192.168.1.10` pelo IP da Máquina 1
4. Substitua `192.168.1.11` pelo IP da Máquina 2
5. Salve

### Passo 5 — Compilar (em qualquer uma das máquinas)

Abra o Prompt de Comando na pasta do projeto e execute:

```
compilar.bat
```

Se aparecer `COMPILACAO OK`, está pronto.

> Os arquivos compilados ficam na pasta `out`. Você pode compilar só numa
> máquina e copiar a pasta `out` para a outra — não precisa compilar duas vezes.

### Passo 6 — Rodar

Abra um Prompt de Comando em cada máquina e vá para a pasta do projeto.

**Na Máquina 2 — inicie a réplica PRIMEIRO:**
```
rodar_replica.bat
```
Aguarde aparecer: `SERVIDOR RÉPLICA INICIADO`

**Na Máquina 1 — inicie o primário:**
```
rodar_primario.bat
```
Aguarde aparecer: `Heartbeat enviado para a réplica`

Isso confirma que as duas máquinas estão se comunicando.

**Em qualquer máquina (ou até de um celular no mesmo Wi-Fi):**
```
rodar_cliente.bat
```

### Passo 7 — Acessar o painel web

Em qualquer navegador na mesma rede, acesse:

```
http://192.168.1.10:8080/monitor   (painel do primário)
http://192.168.1.11:8080/monitor   (painel da réplica)
```

(Substitua pelos IPs reais.)

### Opção recomendada para clientes web: gateway local

Em cada computador cliente que vai usar navegador, rode:

```
rodar_gateway_web.bat
```

Depois abra no navegador desse mesmo computador:

```
http://localhost:8088
```

Nesse caso, `localhost` aponta para o gateway local do cliente, não para o
servidor do leilão. O gateway faz discovery por UDP, encontra o primário ativo
na rede e encaminha as requisições HTTP automaticamente.

Se o primário cair e a réplica assumir, o gateway tenta descobrir o novo
primário e volta a encaminhar as requisições sem o usuário precisar saber IP.

---

## Demonstração do failover para a banca

1. Certifique-se que as duas máquinas estão rodando e que o cliente está conectado
2. Dê alguns lances para ter histórico visível
3. Na Máquina 1 (primário), pressione `Ctrl+C` para derrubar o servidor
4. Observe a Máquina 2: em até 6 segundos aparece `[FAILOVER] Réplica assumindo`
5. O painel web da Máquina 2 muda de cor para vermelho e mostra os lances anteriores
6. O cliente reconecta automaticamente em até 8 segundos
7. Continue dando lances — tudo funciona como antes, agora atendido pela Máquina 2

---

## Portas usadas (para liberar no firewall se necessário)

| Porta | Para quê |
|-------|----------|
| 5555  | Clientes TCP se conectam aqui |
| 6000  | Canal de replicação entre servidores |
| 8080  | Painel web (navegador) |
| 5354/UDP | Discovery automático de servidores |
| 8088  | Gateway web local no computador cliente |

Se a conexão entre as máquinas não funcionar, pode ser o firewall do
Windows bloqueando essas portas. Para liberar, abra o Prompt de Comando
**como Administrador** e execute:

```
netsh advfirewall firewall add rule name="Leilao TCP" protocol=TCP dir=in localport=5555,6000,8080,8088 action=allow
netsh advfirewall firewall add rule name="Leilao Discovery UDP" protocol=UDP dir=in localport=5354 action=allow
```

---

## Estrutura de pastas

```
leilao-distribuido/
├── leilao/
│   ├── config/
│   │   └── ConfiguracaoRede.java       ← IPs e portas
│   ├── dominio/
│   │   ├── RelogioLamport.java         ← Contador lógico
│   │   ├── Lance.java                  ← Um lance
│   │   ├── Leilao.java                 ← Um item leiloado
│   │   └── GerenciadorLeiloes.java     ← Todos os leilões
│   ├── persistencia/
│   │   ├── RepositorioUsuarios.java    ← Cadastro com SHA-256
│   │   └── LogDistribuido.java         ← Log com Lamport
│   ├── servidor/
│   │   ├── CoordenadorPrimario.java    ← Interface de replicação
│   │   ├── RegistroClientes.java       ← Lista de clientes + broadcast
│   │   ├── PainelMonitoramento.java    ← Painel web HTTP
│   │   ├── TratadorCliente.java        ← Thread por cliente
│   │   ├── ServidorLeilao.java         ← Servidor primário
│   │   └── ServidorReplica.java        ← Servidor réplica / failover
│   └── cliente/
│       ├── ClienteLeilao.java          ← Cliente de terminal
│       └── GatewayWebLocal.java        ← Gateway local para navegador
├── out/                                ← Arquivos compilados (gerado pelo compilar.bat)
├── config.properties                   ← EDITE COM OS IPs REAIS
├── config_maquina1_primario.properties ← Modelo para a Máquina 1
├── config_maquina2_replica.properties  ← Modelo para a Máquina 2
├── compilar.bat                        ← Compila tudo
├── rodar_primario.bat                  ← Inicia o servidor primário
├── rodar_replica.bat                   ← Inicia o servidor réplica
├── rodar_cliente.bat                   ← Inicia o cliente
└── rodar_gateway_web.bat               ← Inicia o gateway web local
```
