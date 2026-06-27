import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mantém os clientes que estão conectados neste servidor.
 *
 * O conjunto vem de ConcurrentHashMap porque várias threads podem adicionar,
 * remover e percorrer clientes ao mesmo tempo: existe uma thread por cliente
 * e também há threads de monitoramento que podem disparar notificações.
 */
public class RegistroClientes {

    private final Set<TratadorCliente> clientes = ConcurrentHashMap.newKeySet();

    public void adicionar(TratadorCliente cliente) {
        clientes.add(cliente);
    }

    public void remover(TratadorCliente cliente) {
        clientes.remove(cliente);
    }

    /**
     * Broadcast: envia a mesma informação para todos os clientes conectados.
     * Se uma escrita falhar, o cliente já desconectado é retirado do conjunto.
     */
    public void enviarParaTodos(String mensagem) {
        for (TratadorCliente cliente : clientes) {
            if (!cliente.enviarMensagem(mensagem)) {
                clientes.remove(cliente);
            }
        }
    }

    public int obterQuantidade() {
        return clientes.size();
    }
}
