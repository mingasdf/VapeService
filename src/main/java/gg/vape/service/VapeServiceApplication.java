package gg.vape.service;

import gg.vape.service.http.LegacyHttpServer;
import gg.vape.service.store.FileStore;
import gg.vape.service.zeus.ZeusServer;
import java.util.concurrent.CountDownLatch;

public final class VapeServiceApplication {
    private VapeServiceApplication() {
    }

    public static void main(String[] args) throws Exception {
        ServiceConfig config = ServiceConfig.fromEnvironment();
        FileStore store = new FileStore(config.dataFile());
        LegacyHttpServer httpServer = new LegacyHttpServer(config.bindAddress(), config.httpPort(), store);
        ZeusServer zeusServer = new ZeusServer(config.bindAddress(), config.zeusPort(), store);

        httpServer.start();
        zeusServer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            httpServer.close();
            zeusServer.close();
        }, "vape-service-shutdown"));

        System.out.printf("Vape experimental service started: http://%s:%d, zeus://%s:%d, data=%s%n",
                config.bindAddress(), config.httpPort(), config.bindAddress(), config.zeusPort(),
                config.dataFile().toAbsolutePath());
        new CountDownLatch(1).await();
    }
}
