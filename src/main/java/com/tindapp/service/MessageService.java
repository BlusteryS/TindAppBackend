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
        MessageRepository messageRepository,
        ChatRepository chatRepository,
        BlackListService blackListService,
        UserService userService,
        TranslationService translationService
    ) {
        this.messageRepository = messageRepository;
        this.chatRepository = chatRepository;
        this.blackListService = blackListService;
        this.userService = userService;
        this.translationService = translationService;
    }

    public List<Message> getChatMessages(String chatId, int page, int limit) {
        return messageRepository.findByChatId(chatId, page, limit);
    }

    public Message sendMessage(Long senderId, String chatId, String text, String replyToMessageId, List<Message.MessageAttachment> attachments) {
        Chat chat = chatRepository.findById(chatId)
            .orElseThrow(() -> new RuntimeException("Chat not found"));

        if (!chat.hasParticipant(senderId)) {
            throw new RuntimeException("User is not a participant of this chat");
        }

        Long companionId = chat.getCompanionId(senderId);
        if (companionId != null && !blackListService.canUsersInteract(senderId, companionId)) {
            throw new RuntimeException("User is blocked");
        }

        if (!Boolean.TRUE.equals(chat.getIsActive())) {
            throw new RuntimeException("Chat is not active");
        }

        String messageId = UUID.randomUUID().toString();
        Message message = new Message(messageId, chatId, senderId, text != null ? text : "");

        if (attachments != null && !attachments.isEmpty()) {
            message.setAttachments(attachments);
        }

        if (replyToMessageId != null) {
            Optional<Message> replyMessage = messageRepository.findById(replyToMessageId);
            if (replyMessage.isPresent()) {
                Message.ReplyInfo replyInfo = new Message.ReplyInfo(
                    replyToMessageId,
                    replyMessage.get().getText(),
                    "Собеседник" // В анонимном чате не показываем реальные имена
                );
                message.setReplyTo(replyInfo);
            }
        }

        User sender = userService.getUserById(senderId)
            .orElseThrow(() -> new RuntimeException("User not found"));
        List<User> recipients = new ArrayList<>();
        if (companionId != null) {
            userService.getUserById(companionId).ifPresent(recipients::add);
        }
        applyTranslations(message, sender, recipients);

        Message savedMessage = messageRepository.save(message);

        chat.setLastMessage(savedMessage);
        chat.setUnreadCount(chat.getUnreadCount() + 1);
        chatRepository.save(chat);

        return savedMessage;
    }

    public Message editMessage(String messageId, Long userId, String newText) {
        Message message = messageRepository.findById(messageId)
            .orElseThrow(() -> new RuntimeException("Message not found"));

        if (!message.getSenderId().equals(userId)) {
            throw new RuntimeException("User can only edit their own messages");
        }

        message.updateText(newText);

        Chat chat = chatRepository.findById(message.getChatId())
            .orElseThrow(() -> new RuntimeException("Chat not found"));
        User sender = userService.getUserById(userId)
            .orElseThrow(() -> new RuntimeException("User not found"));

        List<User> recipients = new ArrayList<>();
        Long companionId = chat.getCompanionId(userId);
        if (companionId != null) {
            userService.getUserById(companionId).ifPresent(recipients::add);
        }
        applyTranslations(message, sender, recipients);

        return messageRepository.save(message);
    }

    public void deleteMessage(String messageId, Long userId) {
        Message message = messageRepository.findById(messageId)
            .orElseThrow(() -> new RuntimeException("Message not found"));

        if (!message.getSenderId().equals(userId)) {
            throw new RuntimeException("User can only delete their own messages");
        }

        messageRepository.deleteById(messageId);
    }

    public void markMessagesAsRead(String chatId, Long userId, List<String> messageIds) {
        Chat chat = chatRepository.findById(chatId)
            .orElseThrow(() -> new RuntimeException("Chat not found"));

        if (!chat.hasParticipant(userId)) {
            throw new RuntimeException("User is not a participant of this chat");
        }

        messageRepository.markMessagesAsRead(chatId, messageIds);
        chat.resetUnreadCount();
        chatRepository.save(chat);
    }

    public long getUnreadMessagesCount(String chatId) {
        return messageRepository.countUnreadMessagesByChatId(chatId);
    }

    public List<Message> getRecentMessages(String chatId, int limit) {
        if (chatId == null || limit <= 0) {
            return List.of();
        }
        return messageRepository.findRecentByChatId(chatId, limit);
    }

    public Optional<Message> getMessageById(String messageId) {
        return messageRepository.findById(messageId);
    }

    private void applyTranslations(Message message, User sender, List<User> recipients) {
        if (translationService == null || message == null) {
            return;
        }

        String text = message.getText() != null ? message.getText().trim() : "";
        if (text.isEmpty() || recipients == null || recipients.isEmpty()) {
            message.setTranslations(null);
            return;
        }

        String sourceLanguage = sender != null ? sender.getNativeLanguage() : LanguageUtils.getDefaultLanguage();
        Map<String, Message.MessageTranslation> translationMap = new HashMap<>();

        for (User recipient : recipients) {
            if (recipient == null || !hasActiveSubscription(recipient)) {
                continue;
            }
            String targetLanguage = recipient.getNativeLanguage();
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

    private boolean hasActiveSubscription(User user) {
        if (user == null || user.getSubscription() == null) {
            return false;
        }
        return Boolean.TRUE.equals(user.getSubscription().getIsActive());
    }
}
