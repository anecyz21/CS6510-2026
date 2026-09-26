package selfcheckout.api;

import com.sun.net.httpserver.HttpServer;
import selfcheckout.analytics.AnalyticsService;
import selfcheckout.transactions.TransactionService;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public final class SelfCheckoutServer {
    private SelfCheckoutServer() { }
    public static HttpServer create(int port, TransactionService transactions, AnalyticsService analytics, int threshold) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", new SelfCheckoutRoutes(transactions, analytics, threshold));
        server.setExecutor(Executors.newCachedThreadPool());
        return server;
    }
}
