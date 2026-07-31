package com.tindapp;

import com.tindapp.auth.AuthHandler;
import com.tindapp.auth.TokenAuthHandler;
import com.tindapp.config.AppConfig;
import com.tindapp.config.DatabaseConfig;
import com.tindapp.db.DatabaseMigrator;
import com.tindapp.db.PostgresClientFactory;
import com.tindapp.handler.ApiHandler;
import com.tindapp.handler.VkPaymentHandler;
import com.tindapp.repository.BlackListRepository;
import com.tindapp.repository.ChatRepository;
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
import com.tindapp.service.EventStreamService;
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
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.pgclient.PgPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class MainVerticle extends AbstractVerticle {
    private static final Logger logger = LoggerFactory.getLogger(MainVerticle.class);

    private ApiHandler apiHandler;
    private VkPaymentHandler vkPaymentHandler;
    private TokenAuthHandler tokenAuthHandler;
    private AuthHandler authHandler;
    private PgPool pgPool;

    public static void main(final String[] args) {
        System.setProperty("vertx.logger-delegate-factory-class-name",
            "io.vertx.core.logging.SLF4JLogDelegateFactory");

        final Vertx vertx = Vertx.vertx(new VertxOptions());

        vertx.deployVerticle(new MainVerticle())
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
        initializeServices()
            .compose(v -> {
                final HttpServer server = vertx.createHttpServer();
                final Router router = createRouter();
                return server
                    .requestHandler(router)
                    .listen(AppConfig.HTTP_PORT, AppConfig.HTTP_HOST)
                    .mapEmpty()
                    .onSuccess(ignored -> logger.info(
                        "TindApp server started on {}:{}",
                        AppConfig.HTTP_HOST,
                        AppConfig.HTTP_PORT
                    ));
            })
            .onSuccess(v -> startPromise.complete())
            .onFailure(error -> {
                logger.error("Failed to initialize services", error);
                startPromise.fail(error);
            });
    }

    @Override
    public void stop() {
        if (pgPool != null) {
            pgPool.close();
        }
    }

    private Future<Void> initializeServices() {
        final DatabaseConfig databaseConfig = DatabaseConfig.fromEnvironment();
        if (!databaseConfig.isEnabled()) {
            return Future.failedFuture(new IllegalStateException("PostgreSQL must be configured for the application to start"));
        }

        return runMigrations(databaseConfig)
            .compose(v -> setupPgPool(databaseConfig))
            .compose(pool -> {
                pgPool = pool;
                logger.info("Using PostgreSQL repositories");

                final UserRepository userRepository = new PostgresUserRepository(pgPool);
                final ChatRepository chatRepository = new PostgresChatRepository(pgPool);
                final MessageRepository messageRepository = new PostgresMessageRepository(pgPool);
                final NotificationRepository notificationRepository = new PostgresNotificationRepository(pgPool);
                final SubscriptionRepository subscriptionRepository = new PostgresSubscriptionRepository(pgPool);
                final ReportRepository reportRepository = new PostgresReportRepository(pgPool);
                final BlackListRepository blackListRepository = new PostgresBlackListRepository(pgPool);

                final UserService userService = new UserService(vertx, userRepository);
                final EventStreamService eventStreamService = new EventStreamService(vertx, userService);
                final ProfileService profileService = new ProfileService(userRepository);
                final VkGroupNotificationService vkGroupNotificationService = new VkGroupNotificationService(
                    AppConfig.VK_COMMUNITY_ACCESS_TOKEN,
                    AppConfig.VK_COMMUNITY_GROUP_ID
                );
                final NotificationService notificationService = new NotificationService(
                    notificationRepository,
                    userService,
                    vkGroupNotificationService,
                    eventStreamService
                );
                final ChatService chatService = new ChatService(chatRepository, userRepository, userService, notificationService);
                final BlackListService blackListService = new BlackListService(blackListRepository, userRepository);
                final TranslationService translationService = new TranslationService();
                final MessageService messageService = new MessageService(messageRepository, chatRepository, blackListService, userService, translationService);
                final SubscriptionService subscriptionService = new SubscriptionService(subscriptionRepository, userRepository, notificationService);
                final ReportService reportService = new ReportService(reportRepository, userRepository);
                final TokenService tokenService = new TokenService(userService);

                return userService.markAllOffline()
                    .onSuccess(ignored -> {
                        logger.info("Reset stale online statuses during startup");
                        vertx.setPeriodic(AppConfig.ONLINE_STATUS_CLEANUP_INTERVAL_MS, timerId ->
                            userService.markStaleOnlineUsersOffline(AppConfig.ONLINE_STATUS_TTL)
                                .onFailure(error -> logger.warn("Failed to cleanup stale online statuses", error)));
                    })
                    .map(ignored -> {
                        tokenAuthHandler = new TokenAuthHandler(tokenService);
                        authHandler = new AuthHandler(AppConfig.VK_CLIENT_SECRET, userService, tokenService);
                        final LocationService locationService = LocationService.getInstance();
                        apiHandler = new ApiHandler(
                            userService,
                            chatService,
                            messageService,
                            notificationService,
                            subscriptionService,
                            reportService,
                            blackListService,
                            locationService,
                            profileService,
                            eventStreamService
                        );
                        vkPaymentHandler = new VkPaymentHandler(
                            AppConfig.VK_CLIENT_SECRET,
                            subscriptionService,
                            userService,
                            notificationService
                        );
                        return (Void) null;
                    });
            });
    }

    private Router createRouter() {
        final Router router = Router.router(vertx);

        final CorsHandler corsHandler = CorsHandler.create();
        for (final String origin : AppConfig.ALLOWED_ORIGINS) {
            corsHandler.addOrigin(origin);
        }
        corsHandler
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

        router.post("/buy").handler(vkPaymentHandler);

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
        apiRouter.get("/users/me/rewards").handler(apiHandler::getRewards);
        apiRouter.post("/users/me/purchase-coins").handler(apiHandler::purchaseCoins);
        apiRouter.post("/users/me/rewards").handler(apiHandler::claimReward);
        apiRouter.get("/users/me/stats").handler(apiHandler::getUserStats);
        apiRouter.get("/profiles").handler(apiHandler::getProfiles);
        apiRouter.post("/profiles/:profileId/chat").handler(apiHandler::startProfileChat);

        // Chats - specific routes before parameterized ones
        apiRouter.get("/chats/cost").handler(apiHandler::getChatCost);
        apiRouter.get("/chats/search-status").handler(apiHandler::getSearchStatus);
        apiRouter.post("/chats/search").handler(apiHandler::startCompanionSearch);
        apiRouter.delete("/chats/search").handler(apiHandler::stopCompanionSearch);
        apiRouter.get("/chats").handler(apiHandler::getChats);
        apiRouter.get("/chats/:chatId/messages").handler(apiHandler::getMessages);
        apiRouter.put("/chats/:chatId/messages/read").handler(apiHandler::markMessagesAsRead);
        apiRouter.put("/chats/:chatId/presence").handler(apiHandler::updateChatPresence);
        apiRouter.put("/chats/:chatId/typing").handler(apiHandler::updateTyping);
        apiRouter.get("/chats/:chatId").handler(apiHandler::getChat);
        apiRouter.post("/chats/:chatId/end").handler(apiHandler::endChat);

        apiRouter.post("/uploads/images").handler(apiHandler::uploadImage);

        apiRouter.post("/messages").handler(apiHandler::sendMessage);
        apiRouter.put("/messages/:messageId").handler(apiHandler::editMessage);
        apiRouter.delete("/messages/:messageId").handler(apiHandler::deleteMessage);

        apiRouter.post("/reports").handler(apiHandler::createReport);
        apiRouter.get("/reports").handler(apiHandler::getReports);
        apiRouter.patch("/reports/:reportId/status").handler(apiHandler::updateReportStatus);
        apiRouter.post("/blacklist").handler(apiHandler::blockUser);
        apiRouter.delete("/blacklist/:userId").handler(apiHandler::unblockUser);
        apiRouter.get("/blacklist/:userId/status").handler(apiHandler::getBlacklistStatus);
        apiRouter.get("/blacklist").handler(apiHandler::getBlacklist);
        apiRouter.post("/admin/users/:userId/ban").handler(apiHandler::banUser);
        apiRouter.delete("/admin/users/:userId/ban").handler(apiHandler::unbanUser);

        apiRouter.get("/subscriptions/plans").handler(apiHandler::getSubscriptionPlans);
        apiRouter.get("/subscriptions/active").handler(apiHandler::getActiveSubscription);
        apiRouter.post("/subscriptions/purchase").handler(apiHandler::purchaseSubscription);
        apiRouter.post("/subscriptions/cancel").handler(apiHandler::cancelSubscription);

        apiRouter.get("/notifications").handler(apiHandler::getNotifications);
        apiRouter.put("/notifications/read").handler(apiHandler::markNotificationsAsRead);
        apiRouter.delete("/notifications/:notificationId").handler(apiHandler::deleteNotification);
        apiRouter.post("/notifications/community").handler(apiHandler::updateCommunityNotifications);

        apiRouter.get("/stats/online").handler(apiHandler::getOnlineStats);

        apiRouter.get("/config").handler(apiHandler::getAppConfig);

        apiRouter.get("/events").handler(apiHandler::openEventStream);

        router.route("/api/v1/*").subRouter(apiRouter);
    }

    private Future<PgPool> setupPgPool(final DatabaseConfig dbConfig) {
        return PostgresClientFactory.createPool(vertx, dbConfig)
            .onSuccess(pool -> logger.info("Connected to PostgreSQL at {}", dbConfig.getSafeDescription()));
    }

    private Future<Void> runMigrations(final DatabaseConfig databaseConfig) {
        return vertx.<Void>executeBlocking(promise -> {
            try {
                DatabaseMigrator.migrate(databaseConfig);
                promise.complete();
            } catch (final Exception e) {
                promise.fail(e);
            }
        }, false);
    }
}
