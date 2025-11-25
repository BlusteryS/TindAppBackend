package com.tindapp;

import com.tindapp.auth.AuthHandler;
import com.tindapp.auth.TokenAuthHandler;
import com.tindapp.config.AppConfig;
import com.tindapp.config.DatabaseConfig;
import com.tindapp.db.PostgresClientFactory;
import com.tindapp.handler.ApiHandler;
import com.tindapp.handler.VkPaymentHandler;
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
import com.tindapp.repository.postgres.PostgresBlackListRepository;
import com.tindapp.repository.postgres.PostgresChatRepository;
import com.tindapp.repository.postgres.PostgresMessageRepository;
import com.tindapp.repository.postgres.PostgresNotificationRepository;
import com.tindapp.repository.postgres.PostgresReportRepository;
import com.tindapp.repository.postgres.PostgresSubscriptionRepository;
import com.tindapp.repository.postgres.PostgresUserRepository;
import com.tindapp.service.BlackListService;
import com.tindapp.service.ChatService;
import com.tindapp.service.LocationService;
import com.tindapp.service.MessageService;
import com.tindapp.service.NotificationService;
import com.tindapp.service.ProfileService;
import com.tindapp.service.ReportService;
import com.tindapp.service.SubscriptionService;
import com.tindapp.service.TokenService;
import com.tindapp.service.TranslationService;
import com.tindapp.service.UserService;
import com.tindapp.service.VkGroupNotificationService;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.pgclient.PgPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.function.Supplier;

public class MainVerticle extends AbstractVerticle {
    private static final Logger logger = LoggerFactory.getLogger(MainVerticle.class);

    private WebSocketHandler webSocketHandler;
    private ApiHandler apiHandler;
    private VkPaymentHandler vkPaymentHandler;
    private TokenAuthHandler tokenAuthHandler;
    private AuthHandler authHandler;
    private PgPool pgPool;

    public static void main(final String[] args) {
        System.setProperty("vertx.logger-delegate-factory-class-name",
            "io.vertx.core.logging.SLF4JLogDelegateFactory");

        final Vertx vertx = Vertx.vertx(new VertxOptions());

        final JsonObject config = new JsonObject()
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

    @Override
    public void start(final Promise<Void> startPromise) {
        vertx.executeBlocking(promise -> {
            try {
                initializeServices();
                promise.complete();
            } catch (final Exception e) {
                promise.fail(e);
            }
        }, false, ar -> {
            if (ar.failed()) {
                logger.error("Failed to initialize services", ar.cause());
                startPromise.fail(ar.cause());
                return;
            }

            final HttpServer server = vertx.createHttpServer();
            final Router router = createRouter();

            final int port = config().getInteger("http.port", AppConfig.HTTP_PORT);

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
        });
    }

    @Override
    public void stop() {
        if (pgPool != null) {
            pgPool.close();
        }
    }

    private void initializeServices() {
        pgPool = setupPgPool();

        final boolean usePostgres = pgPool != null;
        if (usePostgres) {
            logger.info("Using PostgreSQL repositories");
        } else {
            logger.warn("PostgreSQL is disabled or not available, falling back to in-memory repositories");
        }

        final UserRepository userRepository = createRepository(
            () -> new PostgresUserRepository(pgPool),
            InMemoryUserRepository::new,
            "User"
        );
        final ChatRepository chatRepository = createRepository(
            () -> new PostgresChatRepository(pgPool),
            InMemoryChatRepository::new,
            "Chat"
        );
        final MessageRepository messageRepository = createRepository(
            () -> new PostgresMessageRepository(pgPool),
            InMemoryMessageRepository::new,
            "Message"
        );
        final NotificationRepository notificationRepository = createRepository(
            () -> new PostgresNotificationRepository(pgPool),
            InMemoryNotificationRepository::new,
            "Notification"
        );
        final SubscriptionRepository subscriptionRepository = createRepository(
            () -> new PostgresSubscriptionRepository(pgPool),
            InMemorySubscriptionRepository::new,
            "Subscription"
        );
        final ReportRepository reportRepository = createRepository(
            () -> new PostgresReportRepository(pgPool),
            InMemoryReportRepository::new,
            "Report"
        );
        final BlackListRepository blackListRepository = createRepository(
            () -> new PostgresBlackListRepository(pgPool),
            InMemoryBlackListRepository::new,
            "BlackList"
        );

        final UserService userService = new UserService(userRepository);
        final ProfileService profileService = new ProfileService(userRepository);
        final VkGroupNotificationService vkGroupNotificationService = new VkGroupNotificationService(
            AppConfig.VK_COMMUNITY_ACCESS_TOKEN,
            AppConfig.VK_COMMUNITY_GROUP_ID
        );
        final NotificationService notificationService = new NotificationService(notificationRepository, userService, vkGroupNotificationService);
        final ChatService chatService = new ChatService(chatRepository, userRepository, userService, notificationService);
        final BlackListService blackListService = new BlackListService(blackListRepository, userRepository);
        final TranslationService translationService = new TranslationService();
        final MessageService messageService = new MessageService(messageRepository, chatRepository, blackListService, userService, translationService);
        final SubscriptionService subscriptionService = new SubscriptionService(subscriptionRepository, userRepository, notificationService);
        final ReportService reportService = new ReportService(reportRepository, userRepository);
        final TokenService tokenService = new TokenService(userService);

        tokenAuthHandler = new TokenAuthHandler(tokenService);
        authHandler = new AuthHandler(config().getString("vk.client.secret", AppConfig.VK_CLIENT_SECRET), userService, tokenService);
        final LocationService locationService = new LocationService();
        webSocketHandler = new WebSocketHandler(
            vertx,
            chatService,
            messageService,
            userService,
            tokenService,
            profileService,
            notificationService
        );
        apiHandler = new ApiHandler(
            userService,
            chatService,
            messageService,
            notificationService,
            subscriptionService,
            reportService,
            blackListService,
            webSocketHandler,
            locationService,
            profileService
        );
        vkPaymentHandler = new VkPaymentHandler(
            config().getString("vk.client.secret", AppConfig.VK_CLIENT_SECRET),
            subscriptionService,
            userService
        );
    }

    private Router createRouter() {
        final Router router = Router.router(vertx);

        final CorsHandler corsHandler = CorsHandler.create();
        boolean wildcardOrigin = false;
        boolean credentialsAllowed = false;
        for (final String origin : AppConfig.ALLOWED_ORIGINS) {
            if (origin == null || origin.isBlank()) {
                continue;
            }
            if ("*".equals(origin)) {
                corsHandler.addOrigin("*");
                wildcardOrigin = true;
            } else {
                corsHandler.addOrigin(origin);
                credentialsAllowed = true;
            }
        }
        if (!wildcardOrigin && credentialsAllowed) {
            corsHandler.allowCredentials(true);
        }
        corsHandler
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

        final File uploadDir = new File(AppConfig.UPLOAD_DIR);
        if (!uploadDir.exists() && !uploadDir.mkdirs()) {
            logger.warn("Failed to create upload directory at {}", uploadDir.getAbsolutePath());
        }

        final BodyHandler bodyHandler = BodyHandler.create()
            .setUploadsDirectory(AppConfig.UPLOAD_DIR)
            .setDeleteUploadedFilesOnEnd(false)
            .setMergeFormAttributes(true)
            .setBodyLimit(AppConfig.MAX_UPLOAD_SIZE_BYTES);

        router.route().handler(bodyHandler);

        router.get("/uploads/*").handler(StaticHandler.create(AppConfig.UPLOAD_DIR)
            .setCachingEnabled(true)
            .setIncludeHidden(false));

        router.post("/buy").blockingHandler(vkPaymentHandler, false);

        setupApiRoutes(router);

        router.get("/health").handler(ctx -> {
            final JsonObject health = AppConfig.getAppInfo()
                .put("status", "UP")
                .put("timestamp", System.currentTimeMillis());
            ctx.response()
                .putHeader("Content-Type", "application/json")
                .end(health.encode());
        });

        return router;
    }

    private void setupApiRoutes(final Router router) {
        final Router apiRouter = Router.router(vertx);

        blocking(apiRouter, io.vertx.core.http.HttpMethod.GET, "/auth", authHandler);

        apiRouter.route("/*").handler(ctx -> {
            logger.info("API Request: {} {} from {}",
                ctx.request().method(),
                ctx.request().path(),
                ctx.request().remoteAddress());
            ctx.next();
        });

        apiRouter.route("/*").blockingHandler(tokenAuthHandler, false);

        blocking(apiRouter, io.vertx.core.http.HttpMethod.GET, "/geo/countries", apiHandler::getCountries);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.GET, "/geo/countries/:countryId/cities", apiHandler::getCitiesByCountry);

        blocking(apiRouter, io.vertx.core.http.HttpMethod.GET, "/users/me", apiHandler::getCurrentUser);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.PUT, "/users/me", apiHandler::updateProfile);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.GET, "/users/:userId", apiHandler::getUser);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.POST, "/users/me/verify", apiHandler::verifyUser);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.GET, "/users/me/balance", apiHandler::getBalance);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.GET, "/users/me/rewards", apiHandler::getRewards);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.POST, "/users/me/purchase-coins", apiHandler::purchaseCoins);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.POST, "/users/me/rewards", apiHandler::claimReward);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.GET, "/users/me/stats", apiHandler::getUserStats);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.GET, "/profiles", apiHandler::getProfiles);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.POST, "/profiles/:profileId/chat", apiHandler::startProfileChat);

        // Chats - specific routes before parameterized ones
        blocking(apiRouter, io.vertx.core.http.HttpMethod.GET, "/chats/cost", apiHandler::getChatCost);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.GET, "/chats/search-status", apiHandler::getSearchStatus);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.GET, "/chats", apiHandler::getChats);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.GET, "/chats/:chatId", apiHandler::getChat);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.POST, "/chats/:chatId/end", apiHandler::endChat);

        blocking(apiRouter, io.vertx.core.http.HttpMethod.POST, "/uploads/images", apiHandler::uploadImage);

        blocking(apiRouter, io.vertx.core.http.HttpMethod.GET, "/chats/:chatId/messages", apiHandler::getMessages);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.POST, "/messages", apiHandler::sendMessage);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.PUT, "/messages/:messageId", apiHandler::editMessage);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.DELETE, "/messages/:messageId", apiHandler::deleteMessage);

        blocking(apiRouter, io.vertx.core.http.HttpMethod.POST, "/reports", apiHandler::createReport);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.GET, "/reports", apiHandler::getReports);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.PATCH, "/reports/:reportId/status", apiHandler::updateReportStatus);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.POST, "/blacklist", apiHandler::blockUser);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.DELETE, "/blacklist/:userId", apiHandler::unblockUser);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.GET, "/blacklist", apiHandler::getBlacklist);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.POST, "/admin/users/:userId/ban", apiHandler::banUser);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.DELETE, "/admin/users/:userId/ban", apiHandler::unbanUser);

        blocking(apiRouter, io.vertx.core.http.HttpMethod.GET, "/subscriptions/plans", apiHandler::getSubscriptionPlans);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.GET, "/subscriptions/active", apiHandler::getActiveSubscription);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.POST, "/subscriptions/purchase", apiHandler::purchaseSubscription);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.POST, "/subscriptions/cancel", apiHandler::cancelSubscription);

        blocking(apiRouter, io.vertx.core.http.HttpMethod.GET, "/notifications", apiHandler::getNotifications);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.PUT, "/notifications/read", apiHandler::markNotificationsAsRead);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.DELETE, "/notifications/:notificationId", apiHandler::deleteNotification);
        blocking(apiRouter, io.vertx.core.http.HttpMethod.POST, "/notifications/community", apiHandler::updateCommunityNotifications);

        blocking(apiRouter, io.vertx.core.http.HttpMethod.GET, "/stats/online", apiHandler::getOnlineStats);

        blocking(apiRouter, io.vertx.core.http.HttpMethod.GET, "/config", apiHandler::getAppConfig);

        router.route("/api/v1/*").subRouter(apiRouter);
    }

    private <T> T createRepository(final Supplier<T> postgresSupplier, final Supplier<T> fallbackSupplier, final String repoName) {
        if (pgPool == null) {
            return fallbackSupplier.get();
        }
        try {
            return postgresSupplier.get();
        } catch (final Exception e) {
            logger.error("Failed to initialize {} repository with PostgreSQL, using in-memory fallback", repoName, e);
            return fallbackSupplier.get();
        }
    }

    private PgPool setupPgPool() {
        final DatabaseConfig dbConfig = DatabaseConfig.fromEnvironment();
        if (!dbConfig.isEnabled()) {
            return null;
        }

        final PgPool pool = PostgresClientFactory.createPool(vertx, dbConfig);
        if (pool != null) {
            logger.info("Connected to PostgreSQL at {}", dbConfig.getSafeDescription());
        }
        return pool;
    }

    private void blocking(final Router router, final io.vertx.core.http.HttpMethod method, final String path, final io.vertx.core.Handler<RoutingContext> handler) {
        router.route(method, path).blockingHandler(handler, false);
    }
}
