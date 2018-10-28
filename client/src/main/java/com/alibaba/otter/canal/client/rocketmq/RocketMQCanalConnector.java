package com.alibaba.otter.canal.client.rocketmq;

import com.alibaba.fastjson.JSON;
import com.alibaba.otter.canal.protocol.FlatMessage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeOrderlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerOrderly;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalMessageDeserializer;
import com.alibaba.otter.canal.protocol.Message;
import com.alibaba.otter.canal.protocol.exception.CanalClientException;

public class RocketMQCanalConnector implements CanalConnector {

    private static final Logger logger = LoggerFactory.getLogger(RocketMQCanalConnector.class);

    private String nameServer;
    private String topic;
    private String groupName;
    private volatile boolean connected = false;
    private DefaultMQPushConsumer rocketMQConsumer;
    private BlockingQueue<ConsumerBatchMessage> messageBlockingQueue;
    Map<Long, ConsumerBatchMessage> messageCache;
    private long batchProcessTimeout = 3000;
    private boolean flatMessage;

    public RocketMQCanalConnector(String nameServer, String topic, String groupName, boolean flatMessage) {
        this.nameServer = nameServer;
        this.topic = topic;
        this.groupName = groupName;
        this.flatMessage = flatMessage;
        messageBlockingQueue = new LinkedBlockingQueue<>();
        messageCache = new ConcurrentHashMap<>();
    }

    @Override
    public void connect() throws CanalClientException {
        rocketMQConsumer = new DefaultMQPushConsumer(groupName);
        if (!StringUtils.isBlank(nameServer)) {
            rocketMQConsumer.setNamesrvAddr(nameServer);
        }
    }

    @Override
    public void disconnect() throws CanalClientException {
        rocketMQConsumer.shutdown();
    }

    @Override
    public boolean checkValid() throws CanalClientException {
        return connected;
    }

    @Override
    public synchronized void subscribe(String filter) throws CanalClientException {
        if (connected) {
            return;
        }
        try {
            if (rocketMQConsumer == null) {
                this.connect();
            }
            rocketMQConsumer.subscribe(this.topic, "*");
            rocketMQConsumer.registerMessageListener(new MessageListenerOrderly() {

                @Override
                public ConsumeOrderlyStatus consumeMessage(List<MessageExt> messageExts,
                    ConsumeOrderlyContext context) {
                    context.setAutoCommit(true);
                    boolean isSuccess = process(messageExts);
                    if (isSuccess) {
                        return ConsumeOrderlyStatus.SUCCESS;
                    } else {
                        return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
                    }
                }
            });
            rocketMQConsumer.start();
        } catch (MQClientException ex) {
            connected = false;
            logger.error("Start RocketMQ consumer error", ex);
        }
        connected = true;
    }

    private boolean process(List<MessageExt> messageExts) {
        logger.info("Get Message:{}", messageExts);
        BlockingQueue messageList = new LinkedBlockingQueue<>();
        for (MessageExt messageExt : messageExts) {
            byte[] data = messageExt.getBody();
            if (data != null){
                try {
                    if (!flatMessage) {
                        Message message = CanalMessageDeserializer.deserializer(data);
                        messageList.put(message);
                    } else {
                        FlatMessage flatMessage = JSON.parseObject(data, FlatMessage.class);
                        messageList.put(flatMessage);
                    }
                } catch (Exception ex) {
                    logger.error("Add message error", ex);
                    throw new CanalClientException(ex);
                }
            }else{
                logger.warn("Received message data is null");
            }
        }
        ConsumerBatchMessage batchMessage;
        if (!flatMessage) {
            batchMessage = new ConsumerBatchMessage<Message>(messageList);
        } else {
            batchMessage = new ConsumerBatchMessage<FlatMessage>(messageList);
        }
        try {
            messageBlockingQueue.put(batchMessage);
        } catch (InterruptedException e) {
            logger.error("Put message to queue error", e);
            throw new RuntimeException(e);
        }
        boolean isCompleted;
        try {
            isCompleted = batchMessage.waitFinish(batchProcessTimeout);
        } catch (InterruptedException e) {
            logger.error("Interrupted when waiting messages to be finished.", e);
            throw new RuntimeException(e);
        }
        boolean isSuccess = batchMessage.isSuccess();
        return isCompleted && isSuccess;
    }

    @Override
    public void subscribe() throws CanalClientException {
        this.subscribe(null);
    }

    @Override
    public void unsubscribe() throws CanalClientException {
        this.rocketMQConsumer.unsubscribe(this.topic);
    }

    /**
     * 暂时不支持batchSize 参数
     *
     * @param batchSize 暂时不支持
     * @return
     * @throws CanalClientException
     */
    @Override
    public Message get(int batchSize) throws CanalClientException {
        Message message = getWithoutAck(batchSize);
        if (message != null) {
            ack(message.getId());
        }
        return message;
    }

    @Override
    public Message get(int batchSize, Long timeout, TimeUnit unit) throws CanalClientException {
        Message message = getWithoutAck(batchSize, timeout, unit);
        if (message != null) {
            ack(message.getId());
        }
        return message;
    }

    private Message getMessage(ConsumerBatchMessage consumerBatchMessage) {
        BlockingQueue<Message> messageList = consumerBatchMessage.getData();
        if (messageList != null & messageList.size() > 0) {
            Message message = messageList.poll();
            messageCache.put(message.getId(), consumerBatchMessage);
            return message;
        }
        return null;
    }

    private FlatMessage getFlatMessage(ConsumerBatchMessage consumerBatchMessage) {
        BlockingQueue<FlatMessage> messageList = consumerBatchMessage.getData();
        if (messageList != null & messageList.size() > 0) {
            FlatMessage message = messageList.poll();
            messageCache.put(message.getId(), consumerBatchMessage);
            return message;
        }
        return null;
    }

    /**
     * 暂时不支持该参数设置
     *
     * @param batchSize
     * @return
     * @throws CanalClientException
     */
    @Override
    public Message getWithoutAck(int batchSize) throws CanalClientException {
        ConsumerBatchMessage batchMessage = messageBlockingQueue.poll();
        if (batchMessage != null) {
            return getMessage(batchMessage);
        }
        return null;
    }

    @Override
    public Message getWithoutAck(int batchSize, Long timeout, TimeUnit unit) throws CanalClientException {
        try {
            ConsumerBatchMessage batchMessage = messageBlockingQueue.poll(timeout, unit);
            if (batchMessage != null) {
                return getMessage(batchMessage);
            }
        } catch (InterruptedException ex) {
            logger.warn("Get message timeout", ex);
            throw new CanalClientException("Failed to fetch the data after: " + timeout);
        }
        return null;
    }

    public FlatMessage getFlatMessageWithoutAck() {
        return getFlatMessageWithoutAck(null, null);
    }

    public FlatMessage getFlatMessageWithoutAck(Long timeout,
        TimeUnit unit) throws CanalClientException {
        try {
            ConsumerBatchMessage batchMessage = null;
            if (timeout == null || timeout == 0) {
                batchMessage = messageBlockingQueue.poll();
            } else {
                batchMessage = messageBlockingQueue.poll(timeout, unit);
            }
            if (batchMessage != null) {
                return getFlatMessage(batchMessage);
            }
        } catch (InterruptedException ex) {
            logger.warn("Get flat message timeout", ex);
            throw new CanalClientException("Failed to fetch the flat message data after: " + timeout);
        }
        return null;
    }

    public FlatMessage getFlatMessage() throws CanalClientException {
        FlatMessage message = getFlatMessageWithoutAck(null, null);
        if (message != null) {
            ack(message.getId());
        }
        return message;
    }

    public FlatMessage getFlatMessage(Long timeout, TimeUnit unit) throws CanalClientException {
        FlatMessage message = getFlatMessageWithoutAck(timeout, unit);
        ack(message.getId());
        return message;
    }

    @Override
    public void ack(long batchId) throws CanalClientException {
        ConsumerBatchMessage batchMessage = messageCache.get(batchId);
        if (batchMessage != null) {
            batchMessage.ack();
            messageCache.remove(batchId);
        }
    }

    @Override
    public void rollback(long batchId) throws CanalClientException {

    }

    @Override
    public void rollback() throws CanalClientException {

    }

    @Override
    public void stopRunning() throws CanalClientException {
        this.rocketMQConsumer.shutdown();
        connected = false;
    }

}
