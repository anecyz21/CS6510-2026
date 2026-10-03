package selfcheckout.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import selfcheckout.analytics.AnalyticsService;
import selfcheckout.domain.CatalogItem;
import selfcheckout.domain.CompletionResult;
import selfcheckout.domain.LowStockAlert;
import selfcheckout.domain.PopularItem;
import selfcheckout.domain.Receipt;
import selfcheckout.domain.ReceiptLine;
import selfcheckout.domain.Transaction;
import selfcheckout.transactions.TransactionService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

public final class SelfCheckoutRoutes implements HttpHandler {
    private final TransactionService transactions;
    private final AnalyticsService analytics;
    private final int defaultLowStockThreshold;
    public SelfCheckoutRoutes(TransactionService transactions, AnalyticsService analytics, int defaultLowStockThreshold) {
        this.transactions = transactions; this.analytics = analytics; this.defaultLowStockThreshold = defaultLowStockThreshold;
    }
    @Override public void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath(); String method = exchange.getRequestMethod();
            if (path.equals("/items") && method.equals("GET")) { ApiResponses.send(exchange, 200, catalog(transactions.catalog())); return; }
            if (path.equals("/transactions") && method.equals("POST")) { start(exchange); return; }
            if (path.equals("/inventory/low-stock") && method.equals("GET")) { lowStock(exchange); return; }
            if (path.equals("/analytics/popular-items") && method.equals("GET")) { popular(exchange); return; }
            if (path.matches("^/transactions/[^/]+/items$") && method.equals("POST")) { scan(exchange, segment(path, 2)); return; }
            if (path.matches("^/transactions/[^/]+/complete$") && method.equals("POST")) { complete(exchange, segment(path, 2)); return; }
            if (path.matches("^/transactions/[^/]+$") && method.equals("GET")) { status(exchange, segment(path, 2)); return; }
            ApiResponses.error(exchange, 404, "NOT_FOUND", "No such route");
        } catch (TransactionService.Invalid invalid) { ApiResponses.error(exchange, 400, "INVALID_REQUEST", invalid.getMessage());
        } catch (TransactionService.NotFound notFound) { ApiResponses.error(exchange, 404, "NOT_FOUND", notFound.getMessage());
        } catch (TransactionService.Conflict conflict) { ApiResponses.error(exchange, 409, conflict.error(), conflict.getMessage());
        } catch (Exception failure) { ApiResponses.error(exchange, 500, "INTERNAL_ERROR", "Unexpected server error"); }
    }
    private void start(HttpExchange ex) throws IOException { Transaction tx = transactions.start(Json.stringField(body(ex), "stationId")); ApiResponses.send(ex, 201, transaction(tx, 0.0)); }
    private void scan(HttpExchange ex, String id) throws IOException {
        var result = transactions.scan(id, Json.stringField(body(ex), "sku"));
        ApiResponses.send(ex, 200, "{\"transactionId\":" + Json.quote(result.transactionId()) + ",\"sku\":" + Json.quote(result.sku()) + ",\"name\":" + Json.quote(result.name()) + ",\"unitPrice\":" + result.unitPrice() + ",\"itemCount\":" + result.itemCount() + ",\"runningTotal\":" + result.runningTotal() + "}");
    }
    private void complete(HttpExchange ex, String id) throws IOException {
        CompletionResult result = transactions.complete(id); Receipt receipt = result.receipt(); StringBuilder lines = new StringBuilder();
        for (int i = 0; i < receipt.lines().size(); i++) { ReceiptLine line = receipt.lines().get(i); if (i > 0) lines.append(','); lines.append("{\"sku\":").append(Json.quote(line.sku())).append(",\"name\":").append(Json.quote(line.name())).append(",\"unitPrice\":").append(line.unitPrice()).append(",\"quantity\":").append(line.quantity()).append('}'); }
        StringBuilder body = new StringBuilder("{\"transactionId\":").append(Json.quote(receipt.transactionId())).append(",\"stationId\":").append(Json.quote(receipt.stationId())).append(",\"itemCount\":").append(receipt.itemCount()).append(",\"totalAmount\":").append(receipt.totalAmount()).append(",\"startedAt\":").append(Json.quote(receipt.startedAt().toString())).append(",\"completedAt\":").append(Json.quote(receipt.completedAt().toString())).append(",\"lines\":[").append(lines).append(']');
        if (!result.unavailableItems().isEmpty()) {
            StringBuilder unavailable = new StringBuilder();
            for (int i = 0; i < result.unavailableItems().size(); i++) { var item = result.unavailableItems().get(i); if (i > 0) unavailable.append(','); unavailable.append("{\"sku\":").append(Json.quote(item.sku())).append(",\"name\":").append(Json.quote(item.name())).append(",\"quantity\":").append(item.quantity()).append('}'); }
            body.append(",\"unavailableItems\":[").append(unavailable).append(']');
        }
        ApiResponses.send(ex, 200, body.append('}').toString());
    }
    private void status(HttpExchange ex, String id) throws IOException { Transaction tx = transactions.status(id); ApiResponses.send(ex, 200, transaction(tx, transactions.runningTotal(tx))); }
    private void lowStock(HttpExchange ex) throws IOException {
        int threshold = integerQuery(ex, "threshold", defaultLowStockThreshold); List<LowStockAlert> alerts = transactions.lowStock(threshold); StringBuilder body = new StringBuilder("{\"threshold\":").append(threshold).append(",\"generatedAt\":").append(Json.quote(Instant.now().toString())).append(",\"alerts\":[");
        for (int i = 0; i < alerts.size(); i++) { LowStockAlert alert = alerts.get(i); if (i > 0) body.append(','); body.append("{\"sku\":").append(Json.quote(alert.sku())).append(",\"name\":").append(Json.quote(alert.name())).append(",\"currentStock\":").append(alert.currentStock()).append(",\"threshold\":").append(alert.threshold()).append(",\"triggeredAt\":").append(Json.quote(alert.triggeredAt().toString())).append('}'); }
        ApiResponses.send(ex, 200, body.append("]}").toString());
    }
    private void popular(HttpExchange ex) throws IOException {
        var snapshot = analytics.popularItems(integerQuery(ex, "limit", 10)); StringBuilder body = new StringBuilder("{\"windowSize\":").append(snapshot.windowSize()).append(",\"slideInterval\":").append(snapshot.slideInterval()).append(",\"windowStart\":").append(snapshot.windowStart()).append(",\"windowEnd\":").append(snapshot.windowEnd()).append(",\"computedAt\":").append(Json.quote(snapshot.computedAt().toString())).append(",\"items\":[");
        for (int i = 0; i < snapshot.items().size(); i++) { PopularItem item = snapshot.items().get(i); if (i > 0) body.append(','); body.append("{\"sku\":").append(Json.quote(item.sku())).append(",\"name\":").append(Json.quote(item.name())).append(",\"scanCount\":").append(item.scanCount()).append(",\"rank\":").append(item.rank()).append('}'); }
        ApiResponses.send(ex, 200, body.append("]}").toString());
    }
    private static String catalog(List<CatalogItem> items) { StringBuilder body = new StringBuilder("{\"items\":["); for (int i = 0; i < items.size(); i++) { CatalogItem item = items.get(i); if (i > 0) body.append(','); body.append("{\"sku\":").append(Json.quote(item.sku())).append(",\"name\":").append(Json.quote(item.name())).append(",\"price\":").append(item.price()).append('}'); } return body.append("]}").toString(); }
    private static String transaction(Transaction tx, double runningTotal) { return "{\"transactionId\":" + Json.quote(tx.transactionId()) + ",\"stationId\":" + Json.quote(tx.stationId()) + ",\"status\":" + Json.quote(tx.status().name()) + ",\"itemCount\":" + tx.scannedSkus().size() + ",\"runningTotal\":" + runningTotal + ",\"startedAt\":" + Json.quote(tx.startedAt().toString()) + "}"; }
    private static String body(HttpExchange ex) throws IOException { return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); }
    private static String segment(String path, int index) { return path.split("/")[index]; }
    private static int integerQuery(HttpExchange ex, String name, int fallback) { String q = ex.getRequestURI().getQuery(); if (q == null) return fallback; for (String pair : q.split("&")) { String[] parts = pair.split("=", 2); if (parts.length == 2 && parts[0].equals(name)) try { return Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) { return fallback; } } return fallback; }
}
