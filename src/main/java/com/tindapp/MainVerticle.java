package com.tindapp;

import com.tindapp.auth.TokenAuthHandler;
import com.tindapp.auth.VKAuthHandler;
import com.tindapp.config.AppConfig;
import com.tindapp.handler.ApiHandler;
import com.tindapp.handler.AuthHandler;
import com.tindapp.handler.WebSocketHandler;
import com.tindapp.repository.BlackListRepository;
import com.tindapp.repository.ChatRepository;
import com.tindapp.repository.InMemoryBlackListRepository;
import com.tindapp.repository.InMemoryChatRepository;
import com.tindapp.repository.InMemoryMessageRepository;
import com.tindapp.repository.InMemoryNotificationRepository;
import com.tindapp.repository.InMemoryReportRepository;
import com.tindapp.repository.InMemorySubscriptionRepository;
import com.tindapp.repository.InMemoryUserRepository;
import com.tindapp.repository.MessageRepository;
import com.tindapp.repository.NotificationRepository;
import com.tindapp.repository.ReportRepository;
import com.tindapp.repository.SubscriptionRepository;
import com.tindapp.repository.UserRepository;
import com.tindapp.service.BlackListService;
import com.tindapp.service.ChatService;
import com.tindapp.service.LocationService;
import com.tindapp.service.MessageService;
import com.tindapp.service.NotificationService;
import com.tindapp.service.ReportService;
import com.tindapp.service.SubscriptionService;
import com.tindapp.service.TokenService;
import com.tindapp.service.UserService;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.StaticHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class MainVerticle extends AbstractVerticle {

    private static final Logger logger = LoggerFactory.getLogger(MainVerticle.class);

    private UserService userService;
    private ChatService chatService;
    private MessageService messageService;
    private NotificationService notificationService;
    private SubscriptionService subscriptionService;
    private ReportService reportService;
    private BlackListService blackListService;
    private TokenService tokenService;
    private LocationService locationService;
    private WebSocketHandler webSocketHandler;
    private ApiHandler apiHandler;
    private VKAuthHandler vkAuthHandler;
    private TokenAuthHandler tokenAuthHandler;
    private AuthHandler authHandler;

    @Override
    public void start(Promise<Void> startPromise) {
        initializeServices();

        HttpServer server = vertx.createHttpServer();
        Router router = createRouter();

        int port = config().getInteger("http.port", AppConfig.HTTP_PORT);

        server
            .requestHandler(router)
            .webSocketHandler(webSocketHandler::handle)
            .listen(port)
            .onComplete(result -> {
                if (result.succeeded()) {
                    logger.info("TindApp server started on port {}", port);
                    startPromise.complete();
                } else {
                    logger.error("Failed to start server", result.cause());
                    startPromise.fail(result.cause());
                }
            });
    }

    private void initializeServices() {
        UserRepository userRepository = new InMemoryUserRepository();
        ChatRepository chatRepository = new InMemoryChatRepository();
        MessageRepository messageRepository = new InMemoryMessageRepository();
        NotificationRepository notificationRepository = new InMemoryNotificationRepository();
        SubscriptionRepository subscriptionRepository = new InMemorySubscriptionRepository();
        ReportRepository reportRepository = new InMemoryReportRepository();
        BlackListRepository blackListRepository = new InMemoryBlackListRepository();

        userService = new UserService(userRepository);
        chatService = new ChatService(chatRepository, userRepository, userService);
        messageService = new MessageService(messageRepository, chatRepository);
        notificationService = new NotificationService(notificationRepository);
        subscriptionService = new SubscriptionService(subscriptionRepository);
        reportService = new ReportService(reportRepository, userRepository);
        blackListService = new BlackListService(blackListRepository, userRepository);
        tokenService = new TokenService(userService);

        vkAuthHandler = new VKAuthHandler(config().getString("vk.client.secret", AppConfig.VK_CLIENT_SECRET));
        tokenAuthHandler = new TokenAuthHandler(tokenService);
        authHandler = new AuthHandler(config().getString("vk.client.secret", AppConfig.VK_CLIENT_SECRET), userService, tokenService);
        locationService = new LocationService();
        webSocketHandler = new WebSocketHandler(vertx, chatService, messageService, userService, tokenService);
        apiHandler = new ApiHandler(
            userService,
            chatService,
            messageService,
            notificationService,
            subscriptionService,
            reportService,
            blackListService,
            webSocketHandler,
            locationService
        );
    }

    private Router createRouter() {
        Router router = Router.router(vertx);

        CorsHandler corsHandler = CorsHandler.create()
            .addOrigin("*")
            .allowCredentials(true)
            .allowedMethod(io.vertx.core.http.HttpMethod.GET)
            .allowedMethod(io.vertx.core.http.HttpMethod.POST)
            .allowedMethod(io.vertx.core.http.HttpMethod.PUT)
            .allowedMethod(io.vertx.core.http.HttpMethod.DELETE)
            .allowedMethod(io.vertx.core.http.HttpMethod.OPTIONS)
            .allowedMethod(io.vertx.core.http.HttpMethod.PATCH)
            .allowedHeader("Content-Type")
            .allowedHeader("Authorization")
            .allowedHeader("Accept")
            .allowedHeader("Origin")
            .allowedHeader("X-Requested-With")
            .allowedHeader("Access-Control-Request-Method")
            .allowedHeader("Access-Control-Request-Headers");
        router.route().handler(corsHandler);

        File uploadDir = new File(AppConfig.UPLOAD_DIR);
        if (!uploadDir.exists() && !uploadDir.mkdirs()) {
            logger.warn("Failed to create upload directory at {}", uploadDir.getAbsolutePath());
        }

        BodyHandler bodyHandler = BodyHandler.create()
            .setUploadsDirectory(AppConfig.UPLOAD_DIR)
            .setDeleteUploadedFilesOnEnd(false)
            .setMergeFormAttributes(true)
            .setBodyLimit(AppConfig.MAX_UPLOAD_SIZE_BYTES);

        router.route().handler(bodyHandler);

        router.get("/uploads/*").handler(StaticHandler.create(AppConfig.UPLOAD_DIR)
            .setCachingEnabled(true)
            .setIncludeHidden(false));

        setupApiRoutes(router);

        router.get("/health").handler(ctx -> {
            JsonObject health = AppConfig.getAppInfo()
                .put("status", "UP")
                .put("timestamp", System.currentTimeMillis());
            ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(health.encode());
        });

        return router;
    }

    private void setupApiRoutes(Router router) {
        Router apiRouter = Router.router(vertx);

        apiRouter.get("/auth").handler(authHandler);

        apiRouter.route("/*").handler(ctx -> {
            logger.info("API Request: {} {} from {}",
                ctx.request().method(),
                ctx.request().path(),
                ctx.request().remoteAddress());
            ctx.next();
        });

        apiRouter.route("/*").handler(tokenAuthHandler);

        apiRouter.get("/geo/countries").handler(apiHandler::getCountries);
        apiRouter.get("/geo/countries/:countryId/cities").handler(apiHandler::getCitiesByCountry);

        apiRouter.get("/users/me").handler(apiHandler::getCurrentUser);
        apiRouter.put("/users/me").handler(apiHandler::updateProfile);
        apiRouter.get("/users/:userId").handler(apiHandler::getUser);
        apiRouter.post("/users/me/verify").handler(apiHandler::verifyUser);
        apiRouter.get("/users/me/balance").handler(apiHandler::getBalance);
        apiRouter.post("/users/me/purchase-coins").handler(apiHandler::purchaseCoins);
        apiRouter.get("/users/me/stats").handler(apiHandler::getUserStats);

        // Chats - specific routes before parameterized ones
        apiRouter.get("/chats/cost").handler(apiHandler::getChatCost);
        apiRouter.get("/chats/search-status").handler(apiHandler::getSearchStatus);
        apiRouter.get("/chats").handler(apiHandler::getChats);
        apiRouter.get("/chats/:chatId").handler(apiHandler::getChat);
        apiRouter.post("/chats/:chatId/end").handler(apiHandler::endChat);

        apiRouter.post("/uploads/images").handler(apiHandler::uploadImage);

        apiRouter.get("/chats/:chatId/messages").handler(apiHandler::getMessages);
        apiRouter.post("/messages").handler(apiHandler::sendMessage);
        apiRouter.put("/messages/:messageId").handler(apiHandler::editMessage);
        apiRouter.delete("/messages/:messageId").handler(apiHandler::deleteMessage);

        apiRouter.post("/reports").handler(apiHandler::createReport);
        apiRouter.get("/reports").handler(apiHandler::getReports);
        apiRouter.post("/blacklist").handler(apiHandler::blockUser);
        apiRouter.delete("/blacklist/:userId").handler(apiHandler::unblockUser);
        apiRouter.get("/blacklist").handler(apiHandler::getBlacklist);

        apiRouter.get("/subscriptions/active").handler(apiHandler::getActiveSubscription);
        apiRouter.post("/subscriptions/purchase").handler(apiHandler::purchaseSubscription);
        apiRouter.post("/subscriptions/cancel").handler(apiHandler::cancelSubscription);

        apiRouter.get("/notifications").handler(apiHandler::getNotifications);
        apiRouter.put("/notifications/read").handler(apiHandler::markNotificationsAsRead);
        apiRouter.delete("/notifications/:notificationId").handler(apiHandler::deleteNotification);

        apiRouter.get("/stats/online").handler(apiHandler::getOnlineStats);

        apiRouter.get("/config").handler(apiHandler::getAppConfig);

        router.route("/api/v1/*").subRouter(apiRouter);
    }

    public static void main(String[] args) {
        System.setProperty("vertx.logger-delegate-factory-class-name",
            "io.vertx.core.logging.SLF4JLogDelegateFactory");

        Vertx vertx = Vertx.vertx(new VertxOptions());

        JsonObject config = new JsonObject()
            .put("http", AppConfig.getHttpConfig())
            .put("vk", AppConfig.getVkConfig());

        vertx.deployVerticle(new MainVerticle(),
                new io.vertx.core.DeploymentOptions().setConfig(config))
            .onSuccess(id -> {
                logger.info("Application started successfully with deployment ID: " + id);
            })
            .onFailure(error -> {
                logger.error("Failed to start application", error);
                vertx.close();
                System.exit(1);
            });
    }
}
