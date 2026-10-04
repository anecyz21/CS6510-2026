package selfcheckout;

import selfcheckout.analytics.AnalyticsService;
import selfcheckout.api.SelfCheckoutServer;
import selfcheckout.dataaccess.InMemorySelfCheckoutStore;
import selfcheckout.transactions.TransactionService;

public final class LayeredServerApplication {
    public static void main(String[] args) throws Exception {
        int port = argument(args, 0, 8080);
        int catalogSize = argument(args, 1, 2000);
        int stock = argument(args, 2, 10000);
        int threshold = argument(args, 3, 50);
        var store = new InMemorySelfCheckoutStore(catalogSize, stock);
        var analytics = new AnalyticsService(1000, 500, store::findItem);
        var transactions = new TransactionService(store, analytics);
        var server = SelfCheckoutServer.create(port, transactions, analytics, threshold);
        Runtime.getRuntime().addShutdownHook(new Thread(analytics::close, "analytics-shutdown"));
        server.start();
        System.out.println("Layered self-checkout server listening on port " + port);
    }
    private static int argument(String[] args, int index, int fallback) {
        return args.length > index ? Integer.parseInt(args[index]) : fallback;
    }
}
