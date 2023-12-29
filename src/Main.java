import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class Main {

    public static void main(String[] args) throws IOException {

        Selector selector = Selector.open();

        ServerSocketChannel socketChannel = ServerSocketChannel.open();
        socketChannel.bind(new InetSocketAddress(3000));
        socketChannel.configureBlocking(false);
        socketChannel.register(selector, SelectionKey.OP_ACCEPT);

        while (true) {
            selector.select();

            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> iterator = selectedKeys.iterator();
            while (iterator.hasNext()) {
                SelectionKey key = iterator.next();

                if (key.isAcceptable()) {
                    newConnection(selector, key);
                } else if (key.isReadable()) {
                    echo(key);
                } else if (key.isWritable()) {
                    continueEcho(key);
                }

                iterator.remove();
            }
        }

    }

    private static class Context {
        private final ByteBuffer buffer = ByteBuffer.allocate(512);
        private String currentLine;
        private boolean terminating = false;
    }

    private static final Map<SocketChannel, Context> contexts = new HashMap<>();

    private static void newConnection(Selector selector, SelectionKey key) throws IOException {
        ServerSocketChannel channel = (ServerSocketChannel) key.channel();
        SocketChannel socketChannel = channel.accept();
        socketChannel
                .configureBlocking(false)
                .register(selector, SelectionKey.OP_READ);
        contexts.put(socketChannel, new Context());
    }

    private static final Pattern QUIT = Pattern.compile("(\\r)?(\\n)?/quit$");

    private static void echo(SelectionKey key) throws IOException {
        Context context = contexts.get(key.channel());
        SocketChannel socketChannel = (SocketChannel) key.channel();
        try {
            socketChannel.read(context.buffer);
            context.buffer.flip();
            context.currentLine = context.currentLine + Charset.defaultCharset().decode(context.buffer);

            if (QUIT.matcher(context.currentLine).find()) {
                context.terminating = true;
            } else if (context.currentLine.length() > 16) {
                context.currentLine = context.currentLine.substring(8);
            }

            context.buffer.flip();
            int count = socketChannel.write(context.buffer);
            if (count < context.buffer.limit()) {
                key.cancel();
                socketChannel.register(key.selector(), SelectionKey.OP_WRITE);
            } else {
                context.buffer.clear();
                if (context.terminating) {
                    cleanup(socketChannel);
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
            cleanup(socketChannel);
        }
    }

    private static void continueEcho(SelectionKey key) throws IOException {
        Context context = contexts.get(key.channel());
        SocketChannel socketChannel = (SocketChannel) key.channel();
        try {
            int remainingBytes = context.buffer.limit() - context.buffer.position();
            int count = socketChannel.write(context.buffer);
            if (count == remainingBytes) {
                context.buffer.clear();
                key.cancel();
                if (context.terminating) {
                    cleanup(socketChannel);
                } else {
                    socketChannel.register(key.selector(), SelectionKey.OP_READ);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            cleanup(socketChannel);
        }
    }

    private static void cleanup(SocketChannel socketChannel) throws IOException {
        socketChannel.close();
        contexts.remove(socketChannel);
    }

}
