package com.chat.project.chat.service;

import com.chat.project.chat.entity.OfflineMessage;
import com.chat.project.chat.repository.OfflineMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OfflineMessageDeliveryService {

    private final OfflineMessageRepository offlineMessageRepository;

    public OfflineMessageDeliveryService(OfflineMessageRepository offlineMessageRepository) {
        this.offlineMessageRepository = offlineMessageRepository;
    }

    /**
     * 在单个事务内读取并删除指定用户的离线消息，返回待投递列表。
     * 调用方（ChatWebSocketHandler）在事务提交后通过 WebSocket 发送消息。
     * 将读写分离到独立 Service 是因为 WebSocket handler 的 afterConnectionEstablished
     * 通过基础设施直接调用，绕过 CGLIB 代理，@Transactional 无效。
     */
    @Transactional
    public List<OfflineMessage> fetchAndDeleteOfflineMessages(String username) {
        List<OfflineMessage> pending =
                offlineMessageRepository.findByToUsernameOrderByTimestampAsc(username);
        if (!pending.isEmpty()) {
            // 按主键批量删除：只删除已读取的那批，避免并发写入的新消息被误删
            List<Long> ids = pending.stream().map(OfflineMessage::getId).toList();
            offlineMessageRepository.deleteAllByIdInBatch(ids);
        }
        return pending;
    }
}
