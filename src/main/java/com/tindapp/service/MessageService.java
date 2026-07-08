package com.tindapp.service;

import com.tindapp.model.Chat;
import com.tindapp.model.Message;
import com.tindapp.model.User;
import com.tindapp.repository.ChatRepository;
import com.tindapp.repository.MessageRepository;
import com.tindapp.util.FutureUtils;
import com.tindapp.util.LanguageUtils;
import io.vertx.core.Future;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class MessageService {

    private final MessageRepository messageRepository;
    private final ChatRepository chatRepository;
    private final BlackListService blackListService;
    private final UserService userService;
    private final TranslationService translationService;

    public MessageService(
        final MessageRepository messageRepository,
        final ChatRepository chatRepository,
        final BlackListService blackListService,
        final UserService userService,
        final TranslationService translationService
    ) {
        this.messageRepository = messageRepository;
        this.chatRepository = chatRepository;
        this.blackListService = blackListService;
        this.userService = userService;
        this.translationService = translationService;
    }

    public Future<List<Message>> getChatMessages(final String chatId, final int page, final int limit) {
        return messageRepository.findByChatId(chatId, page, limit);
    }

    public Future<Message> sendMessage(final Long senderId, final String chatId, final String text, final String replyToMessageId,
                                       final List<Message.MessageAttachment> attachments, final String clientMessageId) {
        return FutureUtils.requirePresent(chatRepository.findById(chatId), "Chat not found")
            .compose(chat -> {
                if (!chat.hasParticipant(senderId)) {
                    return FutureUtils.failed("User is not a participant of this chat");
                }
                if (!Boolean.TRUE.equals(chat.getIsActive())) {
                    return FutureUtils.failed("Chat is not active");
                }
                final Long companionId = chat.getCompanionId(senderId);
                return blackListService.canUsersInteract(senderId, companionId)
                    .compose(canInteract -> {
                        if (!canInteract) {
                            return FutureUtils.failed("User is blocked");
                        }
                        return buildMessage(senderId, chat, text, replyToMessageId, attachments, companionId, clientMessageId)
                            .compose(messageRepository::save)
                            .compose(savedMessage -> {
                                chat.setLastMessage(savedMessage);
                                chat.setUnreadCount((chat.getUnreadCount() != null ? chat.getUnreadCount() : 0) + 1);
                                return chatRepository.save(chat).map(savedChat -> savedMessage);
                            });
                    });
            });
    }

    public Future<Message> editMessage(final String messageId, final Long userId, final String newText) {
        return FutureUtils.requirePresent(messageRepository.findById(messageId), "Message not found")
            .compose(message -> {
                if (!message.getSenderId().equals(userId)) {
                    return FutureUtils.failed("User can only edit their own messages");
                }
                message.updateText(newText);
                return FutureUtils.requirePresent(chatRepository.findById(message.getChatId()), "Chat not found")
                    .compose(chat -> FutureUtils.requirePresent(userService.getUserById(userId), "User not found")
                        .compose(sender -> loadRecipients(chat, userId)
                            .compose(recipients -> applyTranslations(message, sender, recipients))
                            .compose(v -> messageRepository.save(message))));
            });
    }

    public Future<Void> deleteMessage(final String messageId, final Long userId) {
        return FutureUtils.requirePresent(messageRepository.findById(messageId), "Message not found")
            .compose(message -> {
                if (!message.getSenderId().equals(userId)) {
                    return FutureUtils.failed("User can only delete their own messages");
                }
                return messageRepository.deleteById(messageId);
            });
    }

    public Future<Void> markMessagesAsRead(final String chatId, final Long userId, final List<String> messageIds) {
        return FutureUtils.requirePresent(chatRepository.findById(chatId), "Chat not found")
            .compose(chat -> {
                if (!chat.hasParticipant(userId)) {
                    return FutureUtils.failed("User is not a participant of this chat");
                }
                chat.resetUnreadCount();
                return messageRepository.markMessagesAsRead(chatId, messageIds)
                    .compose(v -> chatRepository.save(chat).mapEmpty());
            });
    }

    public Future<Long> countMessages(final String chatId) {
        return messageRepository.countMessagesByChatId(chatId);
    }

    public Future<List<Message>> getRecentMessages(final String chatId, final int limit) {
        if (chatId == null || limit <= 0) {
            return Future.succeededFuture(List.of());
        }
        return messageRepository.findRecentByChatId(chatId, limit);
    }

    public Future<Optional<Message>> getMessageById(final String messageId) {
        return messageRepository.findById(messageId);
    }

    private Future<Message> buildMessage(final Long senderId, final Chat chat, final String text, final String replyToMessageId,
                                         final List<Message.MessageAttachment> attachments, final Long companionId,
                                         final String clientMessageId) {
        final Message message = new Message(UUID.randomUUID().toString(), chat.getId(), senderId, text != null ? text : "");
        message.setClientMessageId(clientMessageId);
        if (attachments != null && !attachments.isEmpty()) {
            message.setAttachments(attachments);
        }

        final Future<Void> replyFuture;
        if (replyToMessageId == null) {
            replyFuture = Future.succeededFuture();
        } else {
            replyFuture = messageRepository.findById(replyToMessageId).map(replyMessage -> {
                replyMessage.ifPresent(found -> message.setReplyTo(new Message.ReplyInfo(
                    replyToMessageId,
                    found.getText(),
                    "Собеседник"
                )));
                return (Void) null;
            });
        }

        return replyFuture
            .compose(v -> FutureUtils.requirePresent(userService.getUserById(senderId), "User not found"))
            .compose(sender -> loadRecipients(chat, senderId)
                .compose(recipients -> applyTranslations(message, sender, recipients).map(message)));
    }

    private Future<List<User>> loadRecipients(final Chat chat, final Long senderId) {
        final Long companionId = chat.getCompanionId(senderId);
        if (companionId == null) {
            return Future.succeededFuture(List.of());
        }
        return userService.getUserById(companionId)
            .map(userOpt -> userOpt.map(List::of).orElseGet(List::of));
    }

    private Future<Void> applyTranslations(final Message message, final User sender, final List<User> recipients) {
        if (translationService == null || message == null) {
            return Future.succeededFuture();
        }

        final String text = message.getText() != null ? message.getText().trim() : "";
        if (text.isEmpty() || recipients == null || recipients.isEmpty()) {
            message.setTranslations(null);
            return Future.succeededFuture();
        }

        final String sourceLanguage = sender != null ? sender.getNativeLanguage() : LanguageUtils.getDefaultLanguage();
        final Map<String, Message.MessageTranslation> translationMap = new HashMap<>();
        final List<Future<Void>> translationFutures = new ArrayList<>();

        for (final User recipient : recipients) {
            if (!hasActiveSubscription(recipient)) {
                continue;
            }
            final String targetLanguage = recipient.getNativeLanguage();
            if (!LanguageUtils.canTranslate(sourceLanguage, targetLanguage)) {
                continue;
            }
            translationFutures.add(
                translationService.translate(text, sourceLanguage, targetLanguage)
                    .map(translation -> {
                        translation.ifPresent(value -> translationMap.put(value.getTo(), value));
                        return (Void) null;
                    })
            );
        }

        return FutureUtils.all(translationFutures).map(v -> {
            message.setTranslations(translationMap.isEmpty() ? null : translationMap);
            return (Void) null;
        });
    }

    private boolean hasActiveSubscription(final User user) {
        return user != null
            && user.getSubscription() != null
            && Boolean.TRUE.equals(user.getSubscription().getIsActive());
    }
}
