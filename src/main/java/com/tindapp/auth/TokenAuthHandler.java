package com.tindapp.auth;

import com.tindapp.model.User;
import com.tindapp.service.TokenService;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TokenAuthHandler implements Handler<RoutingContext> {

    private static final Logger logger = LoggerFactory.getLogger(TokenAuthHandler.class);
    private static final String AUTH_HEADER = "Authorization";
    private static final String TOKEN_PREFIX = "Bearer ";

    public enum ErrorCodes {
        UNAUTHORIZED("UNAUTHORIZED");

        private final String code;

        ErrorCodes(final String code) {
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    private final TokenService tokenService;

    public TokenAuthHandler(final TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public void handle(final RoutingContext context) {
        try {
            final String token = extractToken(context);

            if (token == null) {
                logger.warn("Missing authorization token for request: {}", context.request().path());
                sendUnauthorized(context, ErrorCodes.UNAUTHORIZED, "Missing authorization token");
                return;
            }

            final User user = tokenService.validateToken(token);

            if (user == null) {
                logger.warn("Invalid or expired token for request: {}", context.request().path());
                sendUnauthorized(context, ErrorCodes.UNAUTHORIZED, "Invalid or expired token");
                return;
            }

            context.put("currentUser", user);
            context.put("userId", user.getId());
            context.put("authToken", token);

            logger.info("TokenAuthHandler calling context.next() for path: {}", context.request().path());
            context.next();
            logger.info("TokenAuthHandler returned from context.next() for path: {}", context.request().path());

        } catch (final Exception e) {
            logger.error("Token authentication error for request: " + context.request().path(), e);
            sendUnauthorized(context, ErrorCodes.UNAUTHORIZED, "Authentication error");
        }
    }

    private String extractToken(final RoutingContext context) {
        final String authHeader = context.request().getHeader(AUTH_HEADER);

        if (authHeader == null || authHeader.isEmpty()) {
            return null;
        }

        if (authHeader.startsWith(TOKEN_PREFIX)) {
            return authHeader.substring(TOKEN_PREFIX.length()).trim();
        }

        if (authHeader.startsWith("VK ")) {
            return authHeader.substring(3).trim();
        }

        return authHeader.trim();
    }

    private void sendUnauthorized(final RoutingContext context, final ErrorCodes errorCode, final String message) {
        final JsonObject error = new JsonObject()
            .put("success", false)
            .put("error", message)
            .put("code", errorCode.getCode());

        context.response()
            .setStatusCode(401)
            .putHeader("Content-Type", "application/json")
            .end(error.encode());
    }
}
