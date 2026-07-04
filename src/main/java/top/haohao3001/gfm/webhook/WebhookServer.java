package top.haohao3001.gfm.webhook;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the HTTP server lifecycle for receiving GitHub webhooks.
 */
public class WebhookServer {

    private static final Logger LOGGER = Logger.getLogger("GitForMinecraft");

    private HttpServer server;
    private final int port;
    private final String path;
    private final WebhookHandler handler;

    public WebhookServer(int port, String path, WebhookHandler handler) {
        this.port = port;
        this.path = path;
        this.handler = handler;
    }

    public void start() throws IOException {
        if (server != null) {
            stop();
        }

        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/" + path, handler);
        server.setExecutor(null);
        server.start();

        LOGGER.log(Level.INFO, "GitForMinecraft webhook listening on port " + port + " at path /" + path);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            LOGGER.log(Level.INFO, "GitForMinecraft webhook server stopped.");
        }
    }

    public boolean isRunning() {
        return server != null;
    }
}
