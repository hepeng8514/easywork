package com.hp.easywork.mq;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.rocketmq.common.ServiceThread;
import com.alibaba.rocketmq.common.SystemClock;
import com.alibaba.rocketmq.common.UtilALl;
import com.alibaba.rocketmq.common.constant.LoggerName;
import com.alibaba.rocketmq.common.message.MessageDecoder;
import com.alibaba.rocketmq.common.message.MessageExt;
import com.alibaba.rocketmq.common.protocol.heartbeat.SubscriptionData;
import com.alibaba.rocketmq.common.running.RunningStats;
import com.alibaba.rocketmq.common.sysflag.MessageSysFlag;
import com.alibaba.rocketmq.store.config.BrokerRole;
import com.alibaba.rocketmq.store.config.MessageStoreConfig;
import com.alibaba.rocketmq.store.ha.HAService;
import com.alibaba.rocketmq.store.index.IndexService;
import com.alibaba.rocketmq.store.index.QueryOffsetResult;
import com.alibaba.rocketmq.store.schedule.ScheduleMessageService;
import com.alibaba.rocketmq.store.transaction.TransactionCheckExecuter;
import com.alibaba.rocketmq.store.transaction.TransactionStateService;


/**
 * �洢��Ĭ��ʵ��
 * 
 * @author shijia.wxr<vintage.wang@gmail.com>
 * @since 2013-7-21
 */
public class DefaultMessageStore implements MessageStore {
    private static final Logger log = LoggerFactory.getLogger(LoggerName.StoreLoggerName);
    // ��Ϣ����
    private final MessageFilter messageFilter = new DefaultMessageFilter();
    // �洢����
    private final MessageStoreConfig messageStoreConfig;
    // CommitLog
    private final CommitLog commitLog;
    // ConsumeQueue����
    private final ConcurrentHashMap<String/* topic */, ConcurrentHashMap<Integer/* queueId */, ConsumeQueue>> consumeQueueTable;
    // �߼�����ˢ�̷���
    private final FlushConsumeQueueService flushConsumeQueueService;
    // ���������ļ�����
    private final CleanCommitLogService cleanCommitLogService;
    // �����߼��ļ�����
    private final CleanConsumeQueueService cleanConsumeQueueService;
    // �ַ���Ϣ��������
    private final DispatchMessageService dispatchMessageService;
    // ��Ϣ��������
    private final IndexService indexService;
    // Ԥ����MapedFile�������
    private final AllocateMapedFileService allocateMapedFileService;
    // ��������н�����Ϣ���·��͵��߼�����
    private final ReputMessageService reputMessageService;
    // HA����
    private final HAService haService;
    // ��ʱ����
    private final ScheduleMessageService scheduleMessageService;
    // �ֲ�ʽ�������
    private final TransactionStateService transactionStateService;
    // ����ʱ����ͳ��
    private final StoreStatsService storeStatsService;
    // ���й��̱�־λ
    private final RunningFlags runningFlags = new RunningFlags();
    // �Ż���ȡʱ�����ܣ�����1ms
    private final SystemClock systemClock = new SystemClock(1);
    // ����ز�ӿ�
    private final TransactionCheckExecuter transactionCheckExecuter;
    // �洢�����Ƿ�����
    private volatile boolean shutdown = true;
    // �洢����
    private StoreCheckpoint storeCheckpoint;
    // Ȩ�޿��ƺ󣬴�ӡ�������
    private AtomicLong printTimes = new AtomicLong(0);


    public DefaultMessageStore(final MessageStoreConfig messageStoreConfig) throws IOException {
        this(messageStoreConfig, null);
    }


    public DefaultMessageStore(final MessageStoreConfig messageStoreConfig,
            final TransactionCheckExecuter transactionCheckExecuter) throws IOException {
        this.messageStoreConfig = messageStoreConfig;
        this.transactionCheckExecuter = transactionCheckExecuter;
        this.allocateMapedFileService = new AllocateMapedFileService();
        this.commitLog = new CommitLog(this);
        this.consumeQueueTable =
                new ConcurrentHashMap<String/* topic */, ConcurrentHashMap<Integer/* queueId */, ConsumeQueue>>(
                    32);

        this.flushConsumeQueueService = new FlushConsumeQueueService();
        this.cleanCommitLogService = new CleanCommitLogService();
        this.cleanConsumeQueueService = new CleanConsumeQueueService();
        this.dispatchMessageService =
                new DispatchMessageService(this.messageStoreConfig.getPutMsgIndexHightWater());
        this.storeStatsService = new StoreStatsService();
        this.indexService = new IndexService(this);
        this.haService = new HAService(this);
        this.transactionStateService = new TransactionStateService(this);

        switch (this.messageStoreConfig.getBrokerRole()) {
        case SLAVE:
            this.reputMessageService = new ReputMessageService();
            this.scheduleMessageService = null;
            break;
        case ASYNC_MASTER:
        case SYNC_MASTER:
            this.reputMessageService = null;
            this.scheduleMessageService = new ScheduleMessageService(this);
            break;
        default:
            this.reputMessageService = null;
            this.scheduleMessageService = null;
        }

        // load���������˷���������ǰ����
        this.allocateMapedFileService.start();
        this.dispatchMessageService.start();
    }


    public void truncateDirtyLogicFiles(long phyOffet) {
        ConcurrentHashMap<String, ConcurrentHashMap<Integer, ConsumeQueue>> tables =
                DefaultMessageStore.this.consumeQueueTable;

        for (ConcurrentHashMap<Integer, ConsumeQueue> maps : tables.values()) {
            for (ConsumeQueue logic : maps.values()) {
                logic.truncateDirtyLogicFiles(phyOffet);
            }
        }
    }


    /**
     * ��������
     * 
     * @throws IOException
     */
    public boolean load() {
        boolean result = false;

        try {
            boolean lastExitOK = !this.isTempFileExist();
            log.info("last shutdown " + (lastExitOK ? "normally" : "abnormally"));

            // load Commit Log
            result = this.commitLog.load();

            // load Consume Queue
            result = result && this.loadConsumeQueue();

            // load ����ģ��
            result = result && this.transactionStateService.load();

            // load ��ʱ����
            if (null != scheduleMessageService) {
                result = result && this.scheduleMessageService.load();
            }

            if (result) {
                this.storeCheckpoint = new StoreCheckpoint(this.messageStoreConfig.getStoreCheckpoint());

                this.indexService.load(lastExitOK);

                // ���Իָ�����
                this.recover(lastExitOK);

                log.info("load over, and the max phy offset = " + this.getMaxPhyOffset());
            }
        }
        catch (Exception e) {
            e.printStackTrace();
            result = false;
        }

        if (!result) {
            this.allocateMapedFileService.shutdown();
        }

        return result;
    }


    /**
     * �����洢����
     * 
     * @throws Exception
     */
    public void start() throws Exception {
        this.cleanCommitLogService.start();
        this.cleanConsumeQueueService.start();
        this.indexService.start();
        // �ڹ��캯���Ѿ�start�ˡ�
        // this.dispatchMessageService.start();
        this.flushConsumeQueueService.start();
        this.commitLog.start();
        this.storeStatsService.start();

        if (this.scheduleMessageService != null) {
            this.scheduleMessageService.start();
        }

        if (this.reputMessageService != null) {
            this.reputMessageService.setReputFromOffset(this.commitLog.getMaxOffset());
            this.reputMessageService.start();
        }

        this.transactionStateService.start();
        this.haService.start();

        this.createTempFile();
        this.shutdown = false;
    }


    /**
     * �رմ洢����
     */
    public void shutdown() {
        if (!this.shutdown) {
            this.shutdown = true;

            try {
                // �ȴ���������ֹͣ
                Thread.sleep(1000 * 3);
            }
            catch (InterruptedException e) {
                log.error("shutdown Exception, ", e);
            }

            this.transactionStateService.shutdown();

            if (this.scheduleMessageService != null) {
                this.scheduleMessageService.shutdown();
            }

            this.haService.shutdown();

            this.storeStatsService.shutdown();
            this.cleanCommitLogService.shutdown();
            this.cleanConsumeQueueService.shutdown();
            this.dispatchMessageService.shutdown();
            this.indexService.shutdown();
            this.flushConsumeQueueService.shutdown();
            this.commitLog.shutdown();
            this.allocateMapedFileService.shutdown();
            if (this.reputMessageService != null) {
                this.reputMessageService.shutdown();
            }
            this.storeCheckpoint.flush();
            this.storeCheckpoint.shutdown();
            this.deleteFile(this.messageStoreConfig.getAbortFile());
        }
    }


    public void destroy() {
        this.destroyLogics();
        this.commitLog.destroy();
        this.indexService.destroy();
        this.deleteFile(this.messageStoreConfig.getAbortFile());
        this.deleteFile(this.messageStoreConfig.getStoreCheckpoint());
    }


    public void destroyLogics() {
        for (ConcurrentHashMap<Integer, ConsumeQueue> maps : this.consumeQueueTable.values()) {
            for (ConsumeQueue logic : maps.values()) {
                logic.destroy();
            }
        }
    }


    public PutMessageResult putMessage(MessageExtBrokerInner msg) {
        if (this.shutdown) {
            log.warn("message store has shutdown, so putMessage is forbidden");
            return new PutMessageResult(PutMessageStatus.SERVICE_NOT_AVAILABLE, null);
        }

        if (BrokerRole.SLAVE == this.messageStoreConfig.getBrokerRole()) {
            long value = this.printTimes.getAndIncrement();
            if ((value % 50000) == 0) {
                log.warn("message store is slave mode, so putMessage is forbidden ");
            }

            return new PutMessageResult(PutMessageStatus.SERVICE_NOT_AVAILABLE, null);
        }

        if (!this.runningFlags.isWriteable()) {
            long value = this.printTimes.getAndIncrement();
            if ((value % 50000) == 0) {
                log.warn("message store is not writeable, so putMessage is forbidden "
                        + this.runningFlags.getFlagBits());
            }

            return new PutMessageResult(PutMessageStatus.SERVICE_NOT_AVAILABLE, null);
        }
        else {
            this.printTimes.set(0);
        }

        // message topic����У��
        if (msg.getTopic().length() > Byte.MAX_VALUE) {
            log.warn("putMessage message topic length too long " + msg.getTopic().length());
            return new PutMessageResult(PutMessageStatus.MESSAGE_ILLEGAL, null);
        }

        // message properties����У��
        if (msg.getPropertiesString() != null && msg.getPropertiesString().length() > Short.MAX_VALUE) {
            log.warn("putMessage message properties length too long " + msg.getPropertiesString().length());
            return new PutMessageResult(PutMessageStatus.MESSAGE_ILLEGAL, null);
        }

        long beginTime = this.getSystemClock().now();
        PutMessageResult result = this.commitLog.putMessage(msg);
        // ��������ͳ��
        long eclipseTime = this.getSystemClock().now() - beginTime;
        if (eclipseTime > 1000) {
            log.warn("putMessage not in lock eclipse time(ms) " + eclipseTime);
        }
        this.storeStatsService.setPutMessageEntireTimeMax(eclipseTime);
        this.storeStatsService.getSinglePutMessageTopicTimesTotal(msg.getTopic()).incrementAndGet();

        if (null == result || !result.isOk()) {
            this.storeStatsService.getPutMessageFailedTimes().incrementAndGet();
        }

        return result;
    }


    public SystemClock getSystemClock() {
        return systemClock;
    }


    public GetMessageResult getMessage(final String topic, final int queueId, final long offset,
            final int maxMsgNums, final SubscriptionData subscriptionData) {
        if (this.shutdown) {
            log.warn("message store has shutdown, so getMessage is forbidden");
            return null;
        }

        if (!this.runningFlags.isReadable()) {
            log.warn("message store is not readable, so getMessage is forbidden "
                    + this.runningFlags.getFlagBits());
            return null;
        }

        long beginTime = this.getSystemClock().now();

        // ö�ٱ�����ȡ��Ϣ���
        GetMessageStatus status = GetMessageStatus.NO_MESSAGE_IN_QUEUE;
        // �������˺󣬷�����һ�ο�ʼ��Offset
        long nextBeginOffset = offset;
        // �߼������е���СOffset
        long minOffset = 0;
        // �߼������е����Offset
        long maxOffset = 0;

        GetMessageResult getResult = new GetMessageResult();

        ConsumeQueue consumeQueue = findConsumeQueue(topic, queueId);
        if (consumeQueue != null) {
            minOffset = consumeQueue.getMinOffsetInQuque();
            maxOffset = consumeQueue.getMaxOffsetInQuque();

            if (maxOffset == 0) {
                status = GetMessageStatus.NO_MESSAGE_IN_QUEUE;
                nextBeginOffset = 0;
            }
            else if (offset < minOffset) {
                status = GetMessageStatus.OFFSET_TOO_SMALL;
                nextBeginOffset = minOffset;
            }
            else if (offset == maxOffset) {
                status = GetMessageStatus.OFFSET_OVERFLOW_ONE;
                nextBeginOffset = offset;
            }
            else if (offset > maxOffset) {
                status = GetMessageStatus.OFFSET_OVERFLOW_BADLY;
                nextBeginOffset = maxOffset;
            }
            else {
                SelectMapedBufferResult bufferConsumeQueue = consumeQueue.getIndexBuffer(offset);
                if (bufferConsumeQueue != null) {
                    try {
                        status = GetMessageStatus.NO_MATCHED_MESSAGE;

                        long nextPhyFileStartOffset = Long.MIN_VALUE;
                        long maxPhyOffsetPulling = 0;

                        int i = 0;
                        final int MaxFilterMessageCount = 16000;
                        for (; i < bufferConsumeQueue.getSize() && i < MaxFilterMessageCount; i +=
                                ConsumeQueue.CQStoreUnitSize) {
                            long offsetPy = bufferConsumeQueue.getByteBuffer().getLong();
                            int sizePy = bufferConsumeQueue.getByteBuffer().getInt();
                            long tagsCode = bufferConsumeQueue.getByteBuffer().getLong();

                            maxPhyOffsetPulling = offsetPy;

                            // ˵�������ļ����ڱ�ɾ��
                            if (nextPhyFileStartOffset != Long.MIN_VALUE) {
                                if (offsetPy < nextPhyFileStartOffset)
                                    continue;
                            }

                            // ������Ϣ�ﵽ������
                            if (this.isTheBatchFull(offsetPy, sizePy, maxMsgNums,
                                getResult.getBufferTotalSize(), getResult.getMessageCount())) {
                                break;
                            }

                            // ��Ϣ����
                            if (this.messageFilter.isMessageMatched(subscriptionData, tagsCode)) {
                                SelectMapedBufferResult selectResult =
                                        this.commitLog.getMessage(offsetPy, sizePy);
                                if (selectResult != null) {
                                    this.storeStatsService.getGetMessageTransferedMsgCount()
                                        .incrementAndGet();
                                    getResult.addMessage(selectResult);
                                    status = GetMessageStatus.FOUND;
                                    nextPhyFileStartOffset = Long.MIN_VALUE;
                                }
                                else {
                                    if (getResult.getBufferTotalSize() == 0) {
                                        status = GetMessageStatus.MESSAGE_WAS_REMOVING;
                                    }

                                    // �����ļ����ڱ�ɾ������������
                                    nextPhyFileStartOffset = this.commitLog.rollNextFile(offsetPy);
                                }
                            }
                            else {
                                if (getResult.getBufferTotalSize() == 0) {
                                    status = GetMessageStatus.NO_MATCHED_MESSAGE;
                                }

                                if (log.isDebugEnabled()) {
                                    log.debug("message type not matched, client: " + subscriptionData
                                            + " server: " + tagsCode);
                                }
                            }
                        }

                        nextBeginOffset = offset + (i / ConsumeQueue.CQStoreUnitSize);

                        long diff = maxOffset - maxPhyOffsetPulling;
                        long memory =
                                (long) (StoreUtil.TotalPhysicalMemorySize * (this.messageStoreConfig
                                    .getAccessMessageInMemoryMaxRatio() / 100.0));
                        getResult.setSuggestPullingFromSlave(diff > memory);
                    }
                    finally {
                        // �����ͷ���Դ
                        bufferConsumeQueue.release();
                    }
                }
                else {
                    status = GetMessageStatus.OFFSET_FOUND_NULL;
                    nextBeginOffset = consumeQueue.rollNextFile(offset);
                    log.warn("consumer request topic: " + topic + "offset: " + offset + " minOffset: "
                            + minOffset + " maxOffset: " + maxOffset + ", but access logic queue failed.");
                }
            }
        }
        // ����Ķ���Idû��
        else {
            status = GetMessageStatus.NO_MATCHED_LOGIC_QUEUE;
            nextBeginOffset = 0;
        }

        if (GetMessageStatus.FOUND == status) {
            this.storeStatsService.getGetMessageTimesTotalFound().incrementAndGet();
        }
        else {
            this.storeStatsService.getGetMessageTimesTotalMiss().incrementAndGet();
        }
        long eclipseTime = this.getSystemClock().now() - beginTime;
        this.storeStatsService.setGetMessageEntireTimeMax(eclipseTime);

        getResult.setStatus(status);
        getResult.setNextBeginOffset(nextBeginOffset);
        getResult.setMaxOffset(maxOffset);
        getResult.setMinOffset(minOffset);
        return getResult;
    }


    /**
     * ���ص��ǵ�ǰ������Ч�����Offset�����Offset�ж�Ӧ����Ϣ
     */
    public long getMaxOffsetInQuque(String topic, int queueId) {
        ConsumeQueue logic = this.findConsumeQueue(topic, queueId);
        if (logic != null) {
            long offset = logic.getMaxOffsetInQuque();
            //
            // if (offset > 0)
            // offset -= 1;
            return offset;
        }

        return 0;
    }


    public long getMinOffsetInQuque(String topic, int queueId) {
        ConsumeQueue logic = this.findConsumeQueue(topic, queueId);
        if (logic != null) {
            return logic.getMinOffsetInQuque();
        }

        return -1;
    }


    public long getOffsetInQueueByTime(String topic, int queueId, long timestamp) {
        ConsumeQueue logic = this.findConsumeQueue(topic, queueId);
        if (logic != null) {
            return logic.getOffsetInQueueByTime(timestamp);
        }

        return 0;
    }


    public MessageExt lookMessageByOffset(long commitLogOffset) {
        SelectMapedBufferResult sbr = this.commitLog.getMessage(commitLogOffset, 4);
        if (null != sbr) {
            try {
                // 1 TOTALSIZE
                int size = sbr.getByteBuffer().getInt();
                return lookMessageByOffset(commitLogOffset, size);
            }
            finally {
                sbr.release();
            }
        }

        return null;
    }


    @Override
    public SelectMapedBufferResult selectOneMessageByOffset(long commitLogOffset) {
        SelectMapedBufferResult sbr = this.commitLog.getMessage(commitLogOffset, 4);
        if (null != sbr) {
            try {
                // 1 TOTALSIZE
                int size = sbr.getByteBuffer().getInt();
                return this.commitLog.getMessage(commitLogOffset, size);
            }
            finally {
                sbr.release();
            }
        }

        return null;
    }


    @Override
    public SelectMapedBufferResult selectOneMessageByOffset(long commitLogOffset, int msgSize) {
        return this.commitLog.getMessage(commitLogOffset, msgSize);
    }


    public String getRunningDataInfo() {
        return this.storeStatsService.toString();
    }


    @Override
    public HashMap<String, String> getRuntimeInfo() {
        HashMap<String, String> result = this.storeStatsService.getRuntimeInfo();
        // ��������ļ����̿ռ�
        {
            String storePathPhysic = DefaultMessageStore.this.getMessageStoreConfig().getStorePathCommitLog();
            double physicRatio = UtilALl.getDiskPartitionSpaceUsedPercent(storePathPhysic);
            result.put(RunningStats.commitLogDiskRatio.name(), String.valueOf(physicRatio));

        }

        // ����߼��ļ����̿ռ�
        {
            String storePathLogics =
                    DefaultMessageStore.this.getMessageStoreConfig().getStorePathConsumeQueue();
            double logicsRatio = UtilALl.getDiskPartitionSpaceUsedPercent(storePathLogics);
            result.put(RunningStats.consumeQueueDiskRatio.name(), String.valueOf(logicsRatio));
        }

        // ��ʱ����
        {
            if (this.scheduleMessageService != null) {
                this.scheduleMessageService.buildRunningStats(result);
            }
        }

        result.put(RunningStats.commitLogMinOffset.name(),
            String.valueOf(DefaultMessageStore.this.getMinPhyOffset()));
        result.put(RunningStats.commitLogMaxOffset.name(),
            String.valueOf(DefaultMessageStore.this.getMaxPhyOffset()));

        return result;
    }


    @Override
    public long getMaxPhyOffset() {
        return this.commitLog.getMaxOffset();
    }


    @Override
    public long getEarliestMessageTime(String topic, int queueId) {
        ConsumeQueue logicQueue = this.findConsumeQueue(topic, queueId);
        if (logicQueue != null) {
            long minLogicOffset = logicQueue.getMinLogicOffset();

            SelectMapedBufferResult result =
                    logicQueue.getIndexBuffer(minLogicOffset / ConsumeQueue.CQStoreUnitSize);
            if (result != null) {
                try {
                    final long phyOffset = result.getByteBuffer().getLong();
                    final int size = result.getByteBuffer().getInt();
                    long storeTime = this.getCommitLog().pickupStoretimestamp(phyOffset, size);
                    return storeTime;
                }
                catch (Exception e) {
                }
                finally {
                    result.release();
                }
            }
        }

        return -1;
    }


    @Override
    public long getMessageStoreTimeStamp(String topic, int queueId, long offset) {
        ConsumeQueue logicQueue = this.findConsumeQueue(topic, queueId);
        if (logicQueue != null) {
            SelectMapedBufferResult result = logicQueue.getIndexBuffer(offset);
            if (result != null) {
                try {
                    final long phyOffset = result.getByteBuffer().getLong();
                    final int size = result.getByteBuffer().getInt();
                    long storeTime = this.getCommitLog().pickupStoretimestamp(phyOffset, size);
                    return storeTime;
                }
                catch (Exception e) {
                }
                finally {
                    result.release();
                }
            }
        }

        return -1;
    }


    @Override
    public long getMessageTotalInQueue(String topic, int queueId) {
        ConsumeQueue logicQueue = this.findConsumeQueue(topic, queueId);
        if (logicQueue != null) {
            return logicQueue.getMessageTotalInQueue();
        }

        return -1;
    }


    @Override
    public SelectMapedBufferResult getCommitLogData(final long offset) {
        if (this.shutdown) {
            log.warn("message store has shutdown, so getPhyQueueData is forbidden");
            return null;
        }

        return this.commitLog.getData(offset);
    }


    @Override
    public boolean appendToCommitLog(long startOffset, byte[] data) {
        if (this.shutdown) {
            log.warn("message store has shutdown, so appendToPhyQueue is forbidden");
            return false;
        }

        boolean result = this.commitLog.appendData(startOffset, data);
        if (result) {
            this.reputMessageService.wakeup();
        }
        else {
            log.error("appendToPhyQueue failed " + startOffset + " " + data.length);
        }

        return result;
    }


    @Override
    public void excuteDeleteFilesManualy() {
        this.cleanCommitLogService.excuteDeleteFilesManualy();
    }


    @Override
    public QueryMessageResult queryMessage(String topic, String key, int maxNum, long begin, long end) {
        QueryOffsetResult queryOffsetResult = this.indexService.queryOffset(topic, key, maxNum, begin, end);
        QueryMessageResult queryMessageResult = new QueryMessageResult();

        queryMessageResult.setIndexLastUpdatePhyoffset(queryOffsetResult.getIndexLastUpdatePhyoffset());
        queryMessageResult.setIndexLastUpdateTimestamp(queryOffsetResult.getIndexLastUpdateTimestamp());

        for (Long offset : queryOffsetResult.getPhyOffsets()) {
            SelectMapedBufferResult result = this.commitLog.getData(offset, false);
            if (result != null) {
                int size = result.getByteBuffer().getInt(0);
                result.getByteBuffer().limit(size);
                result.setSize(size);
                queryMessageResult.addMessage(result);
            }
        }

        return queryMessageResult;
    }


    @Override
    public void updateHaMasterAddress(String newAddr) {
        this.haService.updateMasterAddress(newAddr);
    }


    @Override
    public long now() {
        return this.systemClock.now();
    }


    public CommitLog getCommitLog() {
        return commitLog;
    }


    public MessageExt lookMessageByOffset(long commitLogOffset, int size) {
        SelectMapedBufferResult sbr = this.commitLog.getMessage(commitLogOffset, size);
        if (null != sbr) {
            try {
                return MessageDecoder.decode(sbr.getByteBuffer());
            }
            finally {
                sbr.release();
            }
        }

        return null;
    }


    public ConsumeQueue findConsumeQueue(String topic, int queueId) {
        ConcurrentHashMap<Integer, ConsumeQueue> map = consumeQueueTable.get(topic);
        if (null == map) {
            ConcurrentHashMap<Integer, ConsumeQueue> newMap =
                    new ConcurrentHashMap<Integer, ConsumeQueue>(128);
            ConcurrentHashMap<Integer, ConsumeQueue> oldMap = consumeQueueTable.putIfAbsent(topic, newMap);
            if (oldMap != null) {
                map = oldMap;
            }
            else {
                map = newMap;
            }
        }

        ConsumeQueue logic = map.get(queueId);
        if (null == logic) {
            ConsumeQueue newLogic = new ConsumeQueue(//
                topic,//
                queueId,//
                this.getMessageStoreConfig().getStorePathConsumeQueue(),//
                this.getMessageStoreConfig().getMapedFileSizeConsumeQueue(),//
                this);
            ConsumeQueue oldLogic = map.putIfAbsent(queueId, newLogic);
            if (oldLogic != null) {
                logic = oldLogic;
            }
            else {
                logic = newLogic;
            }
        }

        return logic;
    }


    private boolean isTheBatchFull(long offsetPy, int sizePy, int maxMsgNums, int bufferTotal,
            int messageTotal) {
        long maxOffsetPy = this.commitLog.getMaxOffset();
        long memory =
                (long) (StoreUtil.TotalPhysicalMemorySize * (this.messageStoreConfig
                    .getAccessMessageInMemoryMaxRatio() / 100.0));

        // ��һ����Ϣ���Բ�������
        if (0 == bufferTotal || 0 == messageTotal) {
            return false;
        }

        if ((messageTotal + 1) >= maxMsgNums) {
            return true;
        }

        // ��Ϣ�ڴ���
        if ((maxOffsetPy - offsetPy) > memory) {
            if ((bufferTotal + sizePy) > this.messageStoreConfig.getMaxTransferBytesOnMessageInDisk()) {
                return true;
            }

            if ((messageTotal + 1) > this.messageStoreConfig.getMaxTransferCountOnMessageInDisk()) {
                return true;
            }
        }
        // ��Ϣ���ڴ�
        else {
            if ((bufferTotal + sizePy) > this.messageStoreConfig.getMaxTransferBytesOnMessageInMemory()) {
                return true;
            }

            if ((messageTotal + 1) > this.messageStoreConfig.getMaxTransferCountOnMessageInMemory()) {
                return true;
            }
        }

        return false;
    }


    private void deleteFile(final String fileName) {
        File file = new File(fileName);
        boolean result = file.delete();
        log.info(fileName + (result ? " delete OK" : " delete Failed"));
    }


    /**
     * ����������ڴ洢��Ŀ¼������ʱ�ļ��������� UNIX VI�༭����
     * 
     * @throws IOException
     */
    private void createTempFile() throws IOException {
        String fileName = this.messageStoreConfig.getAbortFile();
        File file = new File(fileName);
        MapedFile.ensureDirOK(file.getParent());
        boolean result = file.createNewFile();
        log.info(fileName + (result ? " create OK" : " already exists"));
    }


    private boolean isTempFileExist() {
        String fileName = this.messageStoreConfig.getAbortFile();
        File file = new File(fileName);
        return file.exists();
    }


    private boolean loadConsumeQueue() {
        File dirLogic = new File(this.messageStoreConfig.getStorePathConsumeQueue());
        File[] fileTopicList = dirLogic.listFiles();
        if (fileTopicList != null) {
            // TOPIC ����
            for (File fileTopic : fileTopicList) {
                String topic = fileTopic.getName();
                // TOPIC �¶��б���
                File[] fileQueueIdList = fileTopic.listFiles();
                if (fileQueueIdList != null) {
                    for (File fileQueueId : fileQueueIdList) {
                        int queueId = Integer.parseInt(fileQueueId.getName());
                        ConsumeQueue logic = new ConsumeQueue(//
                            topic,//
                            queueId,//
                            this.getMessageStoreConfig().getStorePathConsumeQueue(),//
                            this.getMessageStoreConfig().getMapedFileSizeConsumeQueue(),//
                            this);
                        this.putConsumeQueue(topic, queueId, logic);
                        if (!logic.load()) {
                            return false;
                        }
                    }
                }
            }
        }

        log.info("load logics queue all over, OK");

        return true;
    }


    public MessageStoreConfig getMessageStoreConfig() {
        return messageStoreConfig;
    }


    private void putConsumeQueue(final String topic, final int queueId, final ConsumeQueue consumeQueue) {
        ConcurrentHashMap<Integer/* queueId */, ConsumeQueue> map = this.consumeQueueTable.get(topic);
        if (null == map) {
            map = new ConcurrentHashMap<Integer/* queueId */, ConsumeQueue>();
            map.put(queueId, consumeQueue);
            this.consumeQueueTable.put(topic, map);
        }
        else {
            map.put(queueId, consumeQueue);
        }
    }


    private void recover(final boolean lastExitOK) {
        // �Ȱ����������ָ̻�Consume Queue
        this.recoverConsumeQueue();

        // �Ȱ����������ָ̻�Tran Redo Log
        this.transactionStateService.getTranRedoLog().recover();

        // �������ݻָ�
        if (lastExitOK) {
            this.commitLog.recoverNormally();
        }
        // �쳣���ݻָ���OS CRASH����JVM CRASH���߻�������
        else {
            this.commitLog.recoverAbnormally();

            // ��֤��Ϣ���ܴ�DispatchService������н��뵽�����Ķ���
            while (this.dispatchMessageService.hasRemainMessage()) {
                try {
                    Thread.sleep(500);
                    log.info("waiting dispatching message over");
                }
                catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        // �ָ�����ģ��
        this.transactionStateService.recoverStateTable(lastExitOK);

        this.recoverTopicQueueTable();
    }


    private void recoverTopicQueueTable() {
        HashMap<String/* topic-queueid */, Long/* offset */> table = new HashMap<String, Long>(1024);
        long minPhyOffset = this.commitLog.getMinOffset();
        for (ConcurrentHashMap<Integer, ConsumeQueue> maps : this.consumeQueueTable.values()) {
            for (ConsumeQueue logic : maps.values()) {
                // �ָ�д����Ϣʱ����¼�Ķ���offset
                String key = logic.getTopic() + "-" + logic.getQueueId();
                table.put(key, logic.getMaxOffsetInQuque());
                // �ָ�ÿ�����е���Сoffset
                logic.correctMinOffset(minPhyOffset);
            }
        }

        this.commitLog.setTopicQueueTable(table);
    }


    private void recoverConsumeQueue() {
        for (ConcurrentHashMap<Integer, ConsumeQueue> maps : this.consumeQueueTable.values()) {
            for (ConsumeQueue logic : maps.values()) {
                logic.recover();
            }
        }
    }


    public void putMessagePostionInfo(String topic, int queueId, long offset, int size, long tagsCode,
            long storeTimestamp, long logicOffset) {
        ConsumeQueue cq = this.findConsumeQueue(topic, queueId);
        cq.putMessagePostionInfoWrapper(offset, size, tagsCode, storeTimestamp, logicOffset);
    }


    public void putDispatchRequest(final DispatchRequest dispatchRequest) {
        this.dispatchMessageService.putRequest(dispatchRequest);
    }


    public DispatchMessageService getDispatchMessageService() {
        return dispatchMessageService;
    }


    public AllocateMapedFileService getAllocateMapedFileService() {
        return allocateMapedFileService;
    }


    public StoreStatsService getStoreStatsService() {
        return storeStatsService;
    }


    public RunningFlags getAccessRights() {
        return runningFlags;
    }


    public ConcurrentHashMap<String, ConcurrentHashMap<Integer, ConsumeQueue>> getConsumeQueueTable() {
        return consumeQueueTable;
    }


    public StoreCheckpoint getStoreCheckpoint() {
        return storeCheckpoint;
    }


    public HAService getHaService() {
        return haService;
    }


    public ScheduleMessageService getScheduleMessageService() {
        return scheduleMessageService;
    }


    public TransactionStateService getTransactionStateService() {
        return transactionStateService;
    }


    public RunningFlags getRunningFlags() {
        return runningFlags;
    }


    public TransactionCheckExecuter getTransactionCheckExecuter() {
        return transactionCheckExecuter;
    }

    /**
     * ���������ļ�����
     */
    class CleanCommitLogService extends ServiceThread {
        // �ֹ�����һ�����ɾ������
        private final static int MaxManualDeleteFileTimes = 20;
        // ���̿ռ侯��ˮλ����������ֹͣ��������Ϣ�����ڱ�������Ŀ�ģ�
        private final double DiskSpaceWarningLevelRatio = Double.parseDouble(System.getProperty(
            "rocketmq.broker.diskSpaceWarningLevelRatio", "0.90"));
        // ���̿ռ�ǿ��ɾ���ļ�ˮλ
        private final double DiskSpaceCleanForciblyRatio = Double.parseDouble(System.getProperty(
            "rocketmq.broker.diskSpaceCleanForciblyRatio", "0.85"));
        private long lastRedeleteTimestamp = 0;
        // �ֹ�����ɾ����Ϣ
        private volatile int manualDeleteFileSeveralTimes = 0;
        // ���̿�ʼǿ��ɾ���ļ�
        private volatile boolean cleanImmediately = false;


        public void excuteDeleteFilesManualy() {
            this.manualDeleteFileSeveralTimes = MaxManualDeleteFileTimes;
            DefaultMessageStore.log.info("excuteDeleteFilesManualy was invoked");
        }


        public void run() {
            DefaultMessageStore.log.info(this.getServiceName() + " service started");
            int cleanResourceInterval =
                    DefaultMessageStore.this.getMessageStoreConfig().getCleanResourceInterval();
            while (!this.isStoped()) {
                try {
                    this.waitForRunning(cleanResourceInterval);

                    this.deleteExpiredFiles();

                    this.redeleteHangedFile();
                }
                catch (Exception e) {
                    DefaultMessageStore.log.warn(this.getServiceName() + " service has exception. ", e);
                }
            }

            DefaultMessageStore.log.info(this.getServiceName() + " service end");
        }


        @Override
        public String getServiceName() {
            return CleanCommitLogService.class.getSimpleName();
        }


        /**
         * ��ǰ����ļ��п���Hangס�����ڼ��һ��
         */
        private void redeleteHangedFile() {
            int interval = DefaultMessageStore.this.getMessageStoreConfig().getRedeleteHangedFileInterval();
            long currentTimestamp = System.currentTimeMillis();
            if ((currentTimestamp - this.lastRedeleteTimestamp) > interval) {
                this.lastRedeleteTimestamp = currentTimestamp;
                int destroyMapedFileIntervalForcibly =
                        DefaultMessageStore.this.getMessageStoreConfig()
                            .getDestroyMapedFileIntervalForcibly();
                if (DefaultMessageStore.this.commitLog.retryDeleteFirstFile(destroyMapedFileIntervalForcibly)) {
                    DefaultMessageStore.this.cleanConsumeQueueService.wakeup();
                }
            }
        }


        private void deleteExpiredFiles() {
            int deleteCount = 0;
            long fileReservedTime = DefaultMessageStore.this.getMessageStoreConfig().getFileReservedTime();
            int deletePhysicFilesInterval =
                    DefaultMessageStore.this.getMessageStoreConfig().getDeleteCommitLogFilesInterval();
            int destroyMapedFileIntervalForcibly =
                    DefaultMessageStore.this.getMessageStoreConfig().getDestroyMapedFileIntervalForcibly();

            boolean timeup = this.isTimeToDelete();
            boolean spacefull = this.isSpaceToDelete();
            boolean manualDelete = this.manualDeleteFileSeveralTimes > 0;

            // ɾ����������ļ�
            if (timeup || spacefull || manualDelete) {
                log.info("begin to delete before " + fileReservedTime + " hours file. timeup: " + timeup
                        + " spacefull: " + spacefull + " manualDeleteFileSeveralTimes: "
                        + this.manualDeleteFileSeveralTimes);

                if (manualDelete)
                    this.manualDeleteFileSeveralTimes--;

                // Сʱת���ɺ���
                fileReservedTime *= 60 * 60 * 1000;

                // �Ƿ�����ǿ��ɾ���ļ�
                boolean cleanAtOnce =
                        DefaultMessageStore.this.getMessageStoreConfig().isCleanFileForciblyEnable()
                                && this.cleanImmediately;

                deleteCount =
                        DefaultMessageStore.this.commitLog.deleteExpiredFile(fileReservedTime,
                            deletePhysicFilesInterval, destroyMapedFileIntervalForcibly, cleanAtOnce);
                if (deleteCount > 0) {
                    DefaultMessageStore.this.cleanConsumeQueueService.wakeup();
                }
                // Σ��������������ˣ��������޷�ɾ���ļ�
                else if (spacefull) {
                    log.warn("disk space will be full soon, but delete file failed.");
                }
            }
        }


        /**
         * �Ƿ����ɾ���ļ����ռ��Ƿ�����
         */
        private boolean isSpaceToDelete() {
            double ratio =
                    DefaultMessageStore.this.getMessageStoreConfig().getDiskMaxUsedSpaceRatio() / 100.0;

            cleanImmediately = false;

            // ��������ļ����̿ռ�
            {
                String storePathPhysic =
                        DefaultMessageStore.this.getMessageStoreConfig().getStorePathCommitLog();
                double physicRatio = UtilALl.getDiskPartitionSpaceUsedPercent(storePathPhysic);
                if (physicRatio > DiskSpaceWarningLevelRatio) {
                    boolean diskok = DefaultMessageStore.this.runningFlags.getAndMakeDiskFull();
                    if (diskok) {
                        DefaultMessageStore.log.error("physic disk maybe full soon " + physicRatio
                                + ", so mark disk full");
                        System.gc();
                    }

                    cleanImmediately = true;
                }
                else if (physicRatio > DiskSpaceCleanForciblyRatio) {
                    cleanImmediately = true;
                }
                else {
                    boolean diskok = DefaultMessageStore.this.runningFlags.getAndMakeDiskOK();
                    if (!diskok) {
                        DefaultMessageStore.log.info("physic disk space OK " + physicRatio
                                + ", so mark disk ok");
                    }
                }

                if (physicRatio < 0 || physicRatio > ratio) {
                    DefaultMessageStore.log.info("physic disk maybe full soon, so reclaim space, "
                            + physicRatio);
                    return true;
                }
            }

            // ����߼��ļ����̿ռ�
            {
                String storePathLogics =
                        DefaultMessageStore.this.getMessageStoreConfig().getStorePathConsumeQueue();
                double logicsRatio = UtilALl.getDiskPartitionSpaceUsedPercent(storePathLogics);
                if (logicsRatio > DiskSpaceWarningLevelRatio) {
                    boolean diskok = DefaultMessageStore.this.runningFlags.getAndMakeDiskFull();
                    if (diskok) {
                        DefaultMessageStore.log.error("logics disk maybe full soon " + logicsRatio
                                + ", so mark disk full");
                        System.gc();
                    }

                    cleanImmediately = true;
                }
                else if (logicsRatio > DiskSpaceCleanForciblyRatio) {
                    cleanImmediately = true;
                }
                else {
                    boolean diskok = DefaultMessageStore.this.runningFlags.getAndMakeDiskOK();
                    if (!diskok) {
                        DefaultMessageStore.log.info("logics disk space OK " + logicsRatio
                                + ", so mark disk ok");
                    }
                }

                if (logicsRatio < 0 || logicsRatio > ratio) {
                    DefaultMessageStore.log.info("logics disk maybe full soon, so reclaim space, "
                            + logicsRatio);
                    return true;
                }
            }

            return false;
        }


        /**
         * �Ƿ����ɾ���ļ���ʱ���Ƿ�����
         */
        private boolean isTimeToDelete() {
            String when = DefaultMessageStore.this.getMessageStoreConfig().getDeleteWhen();
            if (UtilALl.isItTimeToDo(when)) {
                DefaultMessageStore.log.info("it's time to reclaim disk space, " + when);
                return true;
            }

            return false;
        }


        public int getManualDeleteFileSeveralTimes() {
            return manualDeleteFileSeveralTimes;
        }


        public void setManualDeleteFileSeveralTimes(int manualDeleteFileSeveralTimes) {
            this.manualDeleteFileSeveralTimes = manualDeleteFileSeveralTimes;
        }
    }

    /**
     * �����߼��ļ�����
     */
    class CleanConsumeQueueService extends ServiceThread {
        private long lastPhysicalMinOffset = 0;


        private void deleteExpiredFiles() {
            int deleteLogicsFilesInterval =
                    DefaultMessageStore.this.getMessageStoreConfig().getDeleteConsumeQueueFilesInterval();

            long minOffset = DefaultMessageStore.this.commitLog.getMinOffset();
            if (minOffset > this.lastPhysicalMinOffset) {
                this.lastPhysicalMinOffset = minOffset;

                // ɾ���߼������ļ�
                ConcurrentHashMap<String, ConcurrentHashMap<Integer, ConsumeQueue>> tables =
                        DefaultMessageStore.this.consumeQueueTable;

                for (ConcurrentHashMap<Integer, ConsumeQueue> maps : tables.values()) {
                    for (ConsumeQueue logic : maps.values()) {
                        int deleteCount = logic.deleteExpiredFile(minOffset);

                        if (deleteCount > 0 && deleteLogicsFilesInterval > 0) {
                            try {
                                Thread.sleep(deleteLogicsFilesInterval);
                            }
                            catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }

                // ɾ����־RedoLog
                DefaultMessageStore.this.transactionStateService.getTranRedoLog()
                    .deleteExpiredFile(minOffset);
                // ɾ����־StateTable
                DefaultMessageStore.this.transactionStateService.deleteExpiredStateFile(minOffset);
                // ɾ������
                DefaultMessageStore.this.indexService.deleteExpiredFile(minOffset);
            }
        }


        public void run() {
            DefaultMessageStore.log.info(this.getServiceName() + " service started");
            int cleanResourceInterval =
                    DefaultMessageStore.this.getMessageStoreConfig().getCleanResourceInterval();
            while (!this.isStoped()) {
                try {
                    this.deleteExpiredFiles();

                    this.waitForRunning(cleanResourceInterval);
                }
                catch (Exception e) {
                    DefaultMessageStore.log.warn(this.getServiceName() + " service has exception. ", e);
                }
            }

            DefaultMessageStore.log.info(this.getServiceName() + " service end");
        }


        @Override
        public String getServiceName() {
            return CleanConsumeQueueService.class.getSimpleName();
        }
    }

    /**
     * �߼�����ˢ�̷���
     */
    class FlushConsumeQueueService extends ServiceThread {
        private static final int RetryTimesOver = 3;
        private long lastFlushTimestamp = 0;


        private void doFlush(int retryTimes) {
            /**
             * �������壺�������0�����ʶ���ˢ�̱���ˢ���ٸ�page�����=0�����ж���ˢ����
             */
            int flushConsumeQueueLeastPages =
                    DefaultMessageStore.this.getMessageStoreConfig().getFlushConsumeQueueLeastPages();

            if (retryTimes == RetryTimesOver) {
                flushConsumeQueueLeastPages = 0;
            }

            long logicsMsgTimestamp = 0;

            // ��ʱˢ��
            int flushConsumeQueueThoroughInterval =
                    DefaultMessageStore.this.getMessageStoreConfig().getFlushConsumeQueueThoroughInterval();
            long currentTimeMillis = System.currentTimeMillis();
            if (currentTimeMillis >= (this.lastFlushTimestamp + flushConsumeQueueThoroughInterval)) {
                this.lastFlushTimestamp = currentTimeMillis;
                flushConsumeQueueLeastPages = 0;
                logicsMsgTimestamp = DefaultMessageStore.this.getStoreCheckpoint().getLogicsMsgTimestamp();
            }

            ConcurrentHashMap<String, ConcurrentHashMap<Integer, ConsumeQueue>> tables =
                    DefaultMessageStore.this.consumeQueueTable;

            for (ConcurrentHashMap<Integer, ConsumeQueue> maps : tables.values()) {
                for (ConsumeQueue cq : maps.values()) {
                    boolean result = false;
                    for (int i = 0; i < retryTimes && !result; i++) {
                        result = cq.commit(flushConsumeQueueLeastPages);
                    }
                }
            }

            // ����Redolog
            DefaultMessageStore.this.transactionStateService.getTranRedoLog().commit(
                flushConsumeQueueLeastPages);

            if (0 == flushConsumeQueueLeastPages) {
                if (logicsMsgTimestamp > 0) {
                    DefaultMessageStore.this.getStoreCheckpoint().setLogicsMsgTimestamp(logicsMsgTimestamp);
                }
                DefaultMessageStore.this.getStoreCheckpoint().flush();
            }
        }


        public void run() {
            DefaultMessageStore.log.info(this.getServiceName() + " service started");

            while (!this.isStoped()) {
                try {
                    int interval =
                            DefaultMessageStore.this.getMessageStoreConfig().getFlushIntervalConsumeQueue();
                    this.waitForRunning(interval);
                    this.doFlush(1);
                }
                catch (Exception e) {
                    DefaultMessageStore.log.warn(this.getServiceName() + " service has exception. ", e);
                }
            }

            // ����shutdownʱ��Ҫ��֤ȫ��ˢ�̲��˳�
            this.doFlush(RetryTimesOver);

            DefaultMessageStore.log.info(this.getServiceName() + " service end");
        }


        @Override
        public String getServiceName() {
            return FlushConsumeQueueService.class.getSimpleName();
        }


        @Override
        public long getJointime() {
            return 1000 * 60;
        }
    }

    /**
     * �ַ���Ϣ��������
     */
    class DispatchMessageService extends ServiceThread {
        private volatile List<DispatchRequest> requestsWrite;
        private volatile List<DispatchRequest> requestsRead;
        // �ѻ�������������δ�����������
        private volatile int indexRequestCnt = 0;


        public DispatchMessageService(int putMsgIndexHightWater) {
            putMsgIndexHightWater *= 1.5;
            this.requestsWrite = new ArrayList<DispatchRequest>(putMsgIndexHightWater);
            this.requestsRead = new ArrayList<DispatchRequest>(putMsgIndexHightWater);
        }


        public boolean hasRemainMessage() {
            List<DispatchRequest> reqs = this.requestsWrite;
            if (reqs != null && !reqs.isEmpty()) {
                return true;
            }

            reqs = this.requestsRead;
            if (reqs != null && !reqs.isEmpty()) {
                return true;
            }

            return false;
        }


        public void putRequest(final DispatchRequest dispatchRequest) {
            int requestsWriteSize = 0;
            int putMsgIndexHightWater =
                    DefaultMessageStore.this.getMessageStoreConfig().getPutMsgIndexHightWater();
            synchronized (this) {
                this.requestsWrite.add(dispatchRequest);
                requestsWriteSize = this.requestsWrite.size();
                if (!this.hasNotified) {
                    this.hasNotified = true;
                    this.notify();
                }
            }

            DefaultMessageStore.this.getStoreStatsService().setDispatchMaxBuffer(requestsWriteSize);

            if ((requestsWriteSize > putMsgIndexHightWater) || (this.indexRequestCnt > putMsgIndexHightWater)) {
                try {
                    if (log.isDebugEnabled()) {
                        log.debug("Message index buffer size " + requestsWriteSize + " "
                                + this.indexRequestCnt + " > high water " + putMsgIndexHightWater);
                    }

                    Thread.sleep(1);
                }
                catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }


        private void swapRequests() {
            List<DispatchRequest> tmp = this.requestsWrite;
            this.requestsWrite = this.requestsRead;
            this.requestsRead = tmp;
        }


        private void doDispatch() {
            if (!this.requestsRead.isEmpty()) {
                for (DispatchRequest req : this.requestsRead) {

                    final int tranType = MessageSysFlag.getTransactionValue(req.getSysFlag());
                    // 1���ַ���Ϣλ����Ϣ��ConsumeQueue
                    switch (tranType) {
                    case MessageSysFlag.TransactionNotType:
                    case MessageSysFlag.TransactionCommitType:
                        // �����󷢵������Consume Queue
                        DefaultMessageStore.this.putMessagePostionInfo(req.getTopic(), req.getQueueId(),
                            req.getCommitLogOffset(), req.getMsgSize(), req.getTagsCode(),
                            req.getStoreTimestamp(), req.getConsumeQueueOffset());
                        break;
                    case MessageSysFlag.TransactionPreparedType:
                    case MessageSysFlag.TransactionRollbackType:
                        break;
                    }

                    // 2������Transaction State Table
                    if (req.getProducerGroup() != null) {
                        switch (tranType) {
                        case MessageSysFlag.TransactionNotType:
                            break;
                        case MessageSysFlag.TransactionPreparedType:
                            // ��Prepared�����¼����
                            DefaultMessageStore.this.getTransactionStateService().appendPreparedTransaction(//
                                req.getCommitLogOffset(),//
                                req.getMsgSize(),//
                                (int) (req.getStoreTimestamp() / 1000),//
                                req.getProducerGroup().hashCode());
                            break;
                        case MessageSysFlag.TransactionCommitType:
                        case MessageSysFlag.TransactionRollbackType:
                            DefaultMessageStore.this.getTransactionStateService().updateTransactionState(//
                                req.getTranStateTableOffset(),//
                                req.getPreparedTransactionOffset(),//
                                req.getProducerGroup().hashCode(),//
                                tranType//
                                );
                            break;
                        }
                    }
                    // 3����¼Transaction Redo Log
                    switch (tranType) {
                    case MessageSysFlag.TransactionNotType:
                        break;
                    case MessageSysFlag.TransactionPreparedType:
                        // ��¼redolog
                        DefaultMessageStore.this.getTransactionStateService().getTranRedoLog()
                            .putMessagePostionInfoWrapper(//
                                req.getCommitLogOffset(),//
                                req.getMsgSize(),//
                                TransactionStateService.PreparedMessageTagsCode,//
                                req.getStoreTimestamp(),//
                                0L//
                            );
                        break;
                    case MessageSysFlag.TransactionCommitType:
                    case MessageSysFlag.TransactionRollbackType:
                        // ��¼redolog
                        DefaultMessageStore.this.getTransactionStateService().getTranRedoLog()
                            .putMessagePostionInfoWrapper(//
                                req.getCommitLogOffset(),//
                                req.getMsgSize(),//
                                req.getPreparedTransactionOffset(),//
                                req.getStoreTimestamp(),//
                                0L//
                            );
                        break;
                    }
                }

                if (DefaultMessageStore.this.getMessageStoreConfig().isMessageIndexEnable()) {
                    this.indexRequestCnt =
                            DefaultMessageStore.this.indexService.putRequest(this.requestsRead.toArray());
                }

                this.requestsRead.clear();
            }
        }


        public void run() {
            DefaultMessageStore.log.info(this.getServiceName() + " service started");

            while (!this.isStoped()) {
                try {
                    this.waitForRunning(0);
                    this.doDispatch();
                }
                catch (Exception e) {
                    DefaultMessageStore.log.warn(this.getServiceName() + " service has exception. ", e);
                }
            }

            // ������shutdown����£�Ҫ��֤������Ϣ��dispatch
            try {
                Thread.sleep(5 * 1000);
            }
            catch (InterruptedException e) {
                DefaultMessageStore.log.warn("DispatchMessageService Exception, ", e);
            }

            synchronized (this) {
                this.swapRequests();
            }

            this.doDispatch();

            DefaultMessageStore.log.info(this.getServiceName() + " service end");
        }


        @Override
        protected void onWaitEnd() {
            this.swapRequests();
        }


        @Override
        public String getServiceName() {
            return DispatchMessageService.class.getSimpleName();
        }
    }

    /**
     * SLAVE: ���������Load��Ϣ�����ַ��������߼�����
     */
    class ReputMessageService extends ServiceThread {
        // �����￪ʼ��������������ݣ����ַ����߼�����
        private volatile long reputFromOffset = 0;


        public long getReputFromOffset() {
            return reputFromOffset;
        }


        public void setReputFromOffset(long reputFromOffset) {
            this.reputFromOffset = reputFromOffset;
        }


        private void doReput() {
            for (boolean doNext = true; doNext;) {
                SelectMapedBufferResult result = DefaultMessageStore.this.commitLog.getData(reputFromOffset);
                if (result != null) {
                    try {
                        for (int readSize = 0; readSize < result.getSize() && doNext;) {
                            DispatchRequest dispatchRequest =
                                    DefaultMessageStore.this.commitLog.checkMessageAndReturnSize(
                                        result.getByteBuffer(), false, false);
                            int size = dispatchRequest.getMsgSize();
                            // ��������
                            if (size > 0) {
                                DefaultMessageStore.this.putDispatchRequest(dispatchRequest);

                                this.reputFromOffset += size;
                                readSize += size;
                                DefaultMessageStore.this.storeStatsService
                                    .getSinglePutMessageTopicTimesTotal(dispatchRequest.getTopic())
                                    .incrementAndGet();
                                DefaultMessageStore.this.storeStatsService.getSinglePutMessageTopicSizeTotal(
                                    dispatchRequest.getTopic()).addAndGet(dispatchRequest.getMsgSize());
                            }
                            // �ļ��м��������
                            else if (size == -1) {
                                doNext = false;
                            }
                            // �ߵ��ļ�ĩβ���л�����һ���ļ�
                            else if (size == 0) {
                                this.reputFromOffset =
                                        DefaultMessageStore.this.commitLog.rollNextFile(this.reputFromOffset);
                                readSize = result.getSize();
                            }
                        }
                    }
                    finally {
                        result.release();
                    }
                }
                else {
                    doNext = false;
                }
            }
        }


        @Override
        public void run() {
            DefaultMessageStore.log.info(this.getServiceName() + " service started");

            while (!this.isStoped()) {
                try {
                    this.waitForRunning(1000);
                    this.doReput();
                }
                catch (Exception e) {
                    DefaultMessageStore.log.warn(this.getServiceName() + " service has exception. ", e);
                }
            }

            DefaultMessageStore.log.info(this.getServiceName() + " service end");
        }


        @Override
        public String getServiceName() {
            return ReputMessageService.class.getSimpleName();
        }

    }


    @Override
    public long getCommitLogOffsetInQueue(String topic, int queueId, long cqOffset) {
        ConsumeQueue consumeQueue = findConsumeQueue(topic, queueId);
        if (consumeQueue != null) {
            SelectMapedBufferResult bufferConsumeQueue = consumeQueue.getIndexBuffer(cqOffset);
            if (bufferConsumeQueue != null) {
                try {
                    long offsetPy = bufferConsumeQueue.getByteBuffer().getLong();
                    return offsetPy;
                }
                finally {
                    bufferConsumeQueue.release();
                }
            }
        }

        return 0;
    }


    @Override
    public long getMinPhyOffset() {
        return this.commitLog.getMinOffset();
    }
}
