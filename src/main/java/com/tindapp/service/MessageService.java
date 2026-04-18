package com.tindapp.service;

import com.tindapp.model.Chat;
import com.tindapp.model.Message;
import com.tindapp.model.User;
import com.tindapp.repository.ChatRepository;
import com.tindapp.repository.MessageRepository;
import com.tindapp.util.LanguageUtils;

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

    public List<Message> getChatMessages(final String chatId, final int page, final int limit) {
        return messageRepository.findByChatId(chatId, page, limit);
    }

    public Message sendMessage(final Long senderId, final String chatId, final String text, final String replyToMessageId, final List<Message.MessageAttachment> attachments) {
        final Chat chat = chatRepository.findById(chatId)
            .orElseThrow(() -> new RuntimeException("Chat not found"));

        if (!chat.hasParticipant(senderId)) {
            throw new RuntimeException("User is not a participant of this chat");
        }

        final Long companionId = chat.getCompanionId(senderId);
        if (companionId != null && !blackListService.canUsersInteract(senderId, companionId)) {
            throw new RuntimeException("User is blocked");
        }

        if (!Boolean.TRUE.equals(chat.getIsActive())) {
            throw new RuntimeException("Chat is not active");
        }

        final String messageId = UUID.randomUUID().toString();
        final Message message = new Message(messageId, chatId, senderId, text != null ? text : "");

        if (attachments != null && !attachments.isEmpty()) {
            message.setAttachments(attachments);
        }

        if (replyToMessageId != null) {
            final Optional<Message> replyMessage = messageRepository.findById(replyToMessageId);
            if (replyMessage.isPresent()) {
                final Message.ReplyInfo replyInfo = new Message.ReplyInfo(
                    replyToMessageId,
                    replyMessage.get().getText(),
                    "Собеседник" // В анонимном чате не показываем реальные имена
                );
                message.setReplyTo(replyInfo);
            }
        }

        final User sender = userService.getUserById(senderId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        final List<User> recipients = new ArrayList<>();
        if (companionId != null) {
            userService.getUserById(companionId).ifPresent(recipients::add);
        }
        applyTranslations(message, sender, recipients);

        final Message savedMessage = messageRepository.save(message);

        chat.setLastMessage(savedMessage);
        chat.setUnreadCount(chat.getUnreadCount() + 1);
        chatRepository.save(chat);

        return savedMessage;
    }

    public Message editMessage(final String messageId, final Long userId, final String newText) {
        final Message message = messageRepository.findById(messageId)
            .orElseThrow(() -> new RuntimeException("Message not found"));

        if (!message.getSenderId().equals(userId)) {
            throw new RuntimeException("User can only edit their own messages");
        }

        message.updateText(newText);

        final Chat chat = chatRepository.findById(message.getChatId())
            .orElseThrow(() -> new RuntimeException("Chat not found"));
        final User sender = userService.getUserById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

        final List<User> recipients = new ArrayList<>();
        final Long companionId = chat.getCompanionId(userId);
        if (companionId != null) {
            userService.getUserById(companionId).ifPresent(recipients::add);
        }
        applyTranslations(message, sender, recipients);

        return messageRepository.save(message);
    }

    public void deleteMessage(final String messageId, final Long userId) {
        final Message message = messageRepository.findById(messageId)
            .orElseThrow(() -> new RuntimeException("Message not found"));

        if (!message.getSenderId().equals(userId)) {
            throw new RuntimeException("User can only delete their own messages");
        }

        messageRepository.deleteById(messageId);
    }

    public void markMessagesAsRead(final String chatId, final Long userId, final List<String> messageIds) {
        final Chat chat = chatRepository.findById(chatId)
            .orElseThrow(() -> new RuntimeException("Chat not found"));

        if (!chat.hasParticipant(userId)) {
            throw new RuntimeException("User is not a participant of this chat");
        }

        messageRepository.markMessagesAsRead(chatId, messageIds);
        chat.resetUnreadCount();
        chatRepository.save(chat);
    }

    public long countMessages(final String chatId) {
        return messageRepository.countMessagesByChatId(chatId);
    }

    public List<Message> getRecentMessages(final String chatId, final int limit) {
        if (chatId == null || limit <= 0) {
            return List.of();
        }
        return messageRepository.findRecentByChatId(chatId, limit);
    }

    public Optional<Message> getMessageById(final String messageId) {
        return messageRepository.findById(messageId);
    }

    private void applyTranslations(final Message message, final User sender, final List<User> recipients) {
        if (translationService == null || message == null) {
            return;
        }

        final String text = message.getText() != null ? message.getText().trim() : "";
        if (text.isEmpty() || recipients == null || recipients.isEmpty()) {
            message.setTranslations(null);
            return;
        }

        final String sourceLanguage = sender != null ? sender.getNativeLanguage() : LanguageUtils.getDefaultLanguage();
        final Map<String, Message.MessageTranslation> translationMap = new HashMap<>();

        for (final User recipient : recipients) {
            if (!hasActiveSubscription(recipient)) {
                continue;
            }
            final String targetLanguage = recipient.getNativeLanguage();
            if (!LanguageUtils.canTranslate(sourceLanguage, targetLanguage)) {
                continue;
            }
            translationService.translate(text, sourceLanguage, targetLanguage)
                .ifPresent(translation -> translationMap.put(translation.getTo(), translation));
        }

        if (translationMap.isEmpty()) {
            message.setTranslations(null);
        } else {
            message.setTranslations(translationMap);
        }
    }

    private boolean hasActiveSubscription(final User user) {
        if (user == null || user.getSubscription() == null) {
            return false;
        }
        return Boolean.TRUE.equals(user.getSubscription().getIsActive());
    }
}
