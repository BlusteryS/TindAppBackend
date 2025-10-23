package com.tindapp.service;

import com.tindapp.model.Chat;
import com.tindapp.model.Message;
import com.tindapp.repository.ChatRepository;
import com.tindapp.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class MessageService {

    private static final Logger logger = LoggerFactory.getLogger(MessageService.class);

    private final MessageRepository messageRepository;
    private final ChatRepository chatRepository;

    public MessageService(MessageRepository messageRepository, ChatRepository chatRepository) {
        this.messageRepository = messageRepository;
        this.chatRepository = chatRepository;
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

    public Optional<Message> getMessageById(String messageId) {
        return messageRepository.findById(messageId);
    }
}
