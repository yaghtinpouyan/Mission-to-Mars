import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;

public final class StaticFileServer {
    private StaticFileServer() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: StaticFileServer <frontend-directory> <port>");
        }

        Path root = Paths.get(args[0]).toRealPath();
        int port = Integer.parseInt(args[1]);
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        server.createContext("/", exchange -> serveFile(exchange, root));
        server.start();

        System.out.println("Marswalk Planner: http://127.0.0.1:" + port + "/");
        System.out.println("Press Ctrl+C to stop the server.");
        new CountDownLatch(1).await();
    }

    private static void serveFile(HttpExchange exchange, Path root) throws IOException {
        String method = exchange.getRequestMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }

        Path requested = root.resolve("." + exchange.getRequestURI().getPath()).normalize();
        if (!requested.startsWith(root)) {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
            return;
        }
        if (Files.isDirectory(requested)) {
            requested = requested.resolve("index.html");
        }
        if (!Files.isRegularFile(requested)) {
            byte[] body = "Not found".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", contentType(requested));
        if (method.equals("HEAD")) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }

        byte[] body = Files.readAllBytes(requested);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        return "application/octet-stream";
    }
}
