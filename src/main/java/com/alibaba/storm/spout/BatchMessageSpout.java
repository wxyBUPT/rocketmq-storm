package com.alibaba.storm.spout;

import backtype.storm.Config;
import backtype.storm.spout.SpoutOutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.IRichSpout;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Values;

import com.alibaba.rocketmq.client.consumer.listener.*;
import com.alibaba.rocketmq.client.exception.MQClientException;
import com.alibaba.rocketmq.common.message.MessageExt;
import com.alibaba.rocketmq.common.message.MessageQueue;
import com.alibaba.storm.mq.MQConfig;
import com.alibaba.storm.mq.MessageConsumer;
import com.alibaba.storm.mq.MessageTuple;
import com.google.common.collect.MapMaker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author Von Gosling
 */
public class BatchMessageSpout implements IRichSpout {
    private static final long      serialVersionUID = 4641537253577312163L;

    private static final Logger    LOG              = LoggerFactory
                                                            .getLogger(BatchMessageSpout.class);
    protected final MQConfig       config;

    protected MessageConsumer      mqClient;

    protected String               topologyName;

    protected SpoutOutputCollector collector;

    public BatchMessageSpout(final MQConfig config) {
        super();
        this.config = config;
    }

    protected final BlockingQueue<MessageTuple> batchQueue = new LinkedBlockingQueue<MessageTuple>();
    protected Map<UUID, MessageTuple>           batchCache = new MapMaker().makeMap();

    public void open(final Map conf, final TopologyContext context,
                     final SpoutOutputCollector collector) {
        this.collector = collector;

        this.topologyName = (String) conf.get(Config.TOPOLOGY_NAME);
        int taskId = context.getThisTaskId();

        if (mqClient == null) {
            try {
                mqClient = new MessageConsumer(config);

                mqClient.init(buildMessageListener(), String.valueOf(taskId));
            } catch (Throwable e) {
                LOG.error("Failed to init consumer!", e);
                throw new RuntimeException(e);
            }
        }

        LOG.info("Topology {} opened {} spout successfully!",
                new Object[] { topologyName, config.getTopic() });
    }

    public void nextTuple() {
        MessageTuple msgs = null;
        try {
            msgs = batchQueue.take();
        } catch (InterruptedException e) {
            return;
        }
        if (msgs == null) {
            return;
        }

        UUID uuid = msgs.getBatchId();
        collector.emit(new Values(msgs.getMsgList(), msgs.buildMsgAttribute()), uuid);
        return;
    }

    public MessageTuple finish(UUID batchId) {
        MessageTuple batchMsgs = batchCache.remove(batchId);
        if (batchMsgs == null) {
            LOG.warn("Failed to get cached values {}!", batchId);
            return null;
        } else {
            batchMsgs.done();
            return batchMsgs;
        }
    }

    public void ack(final Object id) {
        if (id instanceof UUID) {
            UUID batchId = (UUID) id;
            finish(batchId);
            return;
        } else {
            LOG.error("Id isn't UUID, type {}!", id.getClass().getName());
        }
    }

    protected void handleFail(UUID batchId) {
        MessageTuple msgs = batchCache.get(batchId);
        if (msgs == null) {
            LOG.warn("No MessageTuple entry of {}!", batchId);
            return;
        }

        LOG.info("Fail to handle {}!", msgs.toSimpleString());

        int failureTimes = msgs.getFailureTimes().incrementAndGet();
        if (config.getMaxFailTimes() < 0 || failureTimes < config.getMaxFailTimes()) {
            batchQueue.offer(msgs);
            return;
        } else {
            LOG.info("Skip messages {}!", msgs);
            finish(batchId);
            return;
        }

    }

    public void fail(final Object id) {
        if (id instanceof UUID) {
            UUID batchId = (UUID) id;
            handleFail(batchId);
            return;
        } else {
            LOG.error("Id isn't UUID, type:{}!", id.getClass().getName());
        }
    }

    public void declareOutputFields(final OutputFieldsDeclarer declarer) {
        declarer.declare(new Fields("MessageExtList", "MessageStat"));
    }

    public boolean isDistributed() {
        return true;
    }

    public Map<String, Object> getComponentConfiguration() {
        return null;
    }

    public void activate() {
        mqClient.resume();
    }

    public void deactivate() {
        mqClient.pause();
    }

    public void close() {
        cleanup();
    }

    public void cleanup() {
        for (Entry<UUID, MessageTuple> entry : batchCache.entrySet()) {
            MessageTuple msgs = entry.getValue();
            msgs.fail();
        }
        mqClient.cleanup();
    }

    public BlockingQueue<MessageTuple> getBatchQueue() {
        return batchQueue;
    }

    public Set<MessageQueue> getAllPartitions() throws MQClientException {
        return mqClient.getAllPartitions();
    }

    public MessageListener buildMessageListener() {
        if (config.isOrdered()) {
            MessageListener listener = new MessageListenerOrderly() {
                public ConsumeOrderlyStatus consumeMessage(List<MessageExt> msgs,
                                                           ConsumeOrderlyContext context) {
                    boolean isSuccess = BatchMessageSpout.this.consumeMessage(msgs,
                            context.getMessageQueue());
                    if (isSuccess) {
                        return ConsumeOrderlyStatus.SUCCESS;
                    } else {
                        return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
                    }
                }
            };
            LOG.debug("Successfully create ordered listener!");
            return listener;
        } else {
            MessageListener listener = new MessageListenerConcurrently() {
                public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs,
                                                                ConsumeConcurrentlyContext context) {
                    boolean isSuccess = BatchMessageSpout.this.consumeMessage(msgs,
                            context.getMessageQueue());
                    if (isSuccess) {
                        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                    } else {
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }

            };
            LOG.debug("Successfully create concurrently listener!");
            return listener;
        }
    }

    public boolean consumeMessage(List<MessageExt> msgs, MessageQueue mq) {
        LOG.info("Receiving {} messages {} from MQ {}", new Object[] { msgs.size(), msgs, mq });

        if (msgs == null || msgs.isEmpty()) {
            return true;
        }

        MessageTuple batchMsgs = new MessageTuple(msgs, mq);

        batchCache.put(batchMsgs.getBatchId(), batchMsgs);

        batchQueue.offer(batchMsgs);

        try {
            boolean isDone = batchMsgs.waitFinish();
            if (!isDone) {
                batchCache.remove(batchMsgs.getBatchId());
                return false;
            }
        } catch (InterruptedException e) {
            batchCache.remove(batchMsgs.getBatchId());
            return false;
        }

        return batchMsgs.isSuccess();
    }

}