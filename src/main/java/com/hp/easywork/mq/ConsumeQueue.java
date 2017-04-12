package com.hp.easywork.mq;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;



/**
 * ���Ѷ���ʵ��
 * 
 * @author shijia.wxr<vintage.wang@gmail.com>
 * @since 2013-7-21
 */
public class ConsumeQueue {
    // �洢��Ԫ��С
    public static final int CQStoreUnitSize = 20;
    // �洢�������
    private final DefaultMessageStore defaultMessageStore;
    // �洢��Ϣ�����Ķ���
    private final MapedFileQueue mapedFileQueue;
    // Topic
    private final String topic;
    // queueId
    private final int queueId;
    // д����ʱ�õ���ByteBuffer
    private final ByteBuffer byteBufferIndex;
    // ����
    private final String storePath;
    private final int mapedFileSize;
    // ���һ����Ϣ��Ӧ������Offset
    private long maxPhysicOffset = -1;
    // �߼����е���СOffset��ɾ�������ļ�ʱ�������������СOffset
    // ʵ��ʹ����Ҫ���� StoreUnitSize
    private volatile long minLogicOffset = 0;


    public ConsumeQueue(//
            final String topic,//
            final int queueId,//
            final String storePath,//
            final int mapedFileSize,//
            final DefaultMessageStore defaultMessageStore) {
        this.storePath = storePath;
        this.mapedFileSize = mapedFileSize;
        this.defaultMessageStore = defaultMessageStore;

        this.topic = topic;
        this.queueId = queueId;

        String queueDir = this.storePath//
                + File.separator + topic//
                + File.separator + queueId;//

        this.mapedFileQueue = new MapedFileQueue(queueDir, mapedFileSize, null);

        this.byteBufferIndex = ByteBuffer.allocate(CQStoreUnitSize);
    }


    public boolean load() {
        boolean result = this.mapedFileQueue.load();
        log.info("load consume queue " + this.topic + "-" + this.queueId + " " + (result ? "OK" : "Failed"));
        return result;
    }


    public void recover() {
        final List<MapedFile> mapedFiles = this.mapedFileQueue.getMapedFiles();
        if (!mapedFiles.isEmpty()) {
            // �ӵ����������ļ���ʼ�ָ�
            int index = mapedFiles.size() - 3;
            if (index < 0)
                index = 0;

            int mapedFileSizeLogics = this.mapedFileSize;
            MapedFile mapedFile = mapedFiles.get(index);
            ByteBuffer byteBuffer = mapedFile.sliceByteBuffer();
            long processOffset = mapedFile.getFileFromOffset();
            long mapedFileOffset = 0;
            while (true) {
                for (int i = 0; i < mapedFileSizeLogics; i += CQStoreUnitSize) {
                    long offset = byteBuffer.getLong();
                    int size = byteBuffer.getInt();
                    long tagsCode = byteBuffer.getLong();

                    // ˵����ǰ�洢��Ԫ��Ч
                    // TODO �����ж���Ч�Ƿ����
                    if (offset >= 0 && size > 0) {
                        mapedFileOffset = i + CQStoreUnitSize;
                        this.maxPhysicOffset = offset;
                    }
                    else {
                        log.info("recover current consume queue file over,  " + mapedFile.getFileName() + " "
                                + offset + " " + size + " " + tagsCode);
                        break;
                    }
                }

                // �ߵ��ļ�ĩβ���л�����һ���ļ�
                if (mapedFileOffset == mapedFileSizeLogics) {
                    index++;
                    if (index >= mapedFiles.size()) {
                        // ��ǰ������֧�����ܷ���
                        log.info("recover last consume queue file over, last maped file "
                                + mapedFile.getFileName());
                        break;
                    }
                    else {
                        mapedFile = mapedFiles.get(index);
                        byteBuffer = mapedFile.sliceByteBuffer();
                        processOffset = mapedFile.getFileFromOffset();
                        mapedFileOffset = 0;
                        log.info("recover next consume queue file, " + mapedFile.getFileName());
                    }
                }
                else {
                    log.info("recover current consume queue queue over " + mapedFile.getFileName() + " "
                            + (processOffset + mapedFileOffset));
                    break;
                }
            }

            processOffset += mapedFileOffset;
            this.mapedFileQueue.truncateDirtyFiles(processOffset);
        }
    }


    /**
     * ���ֲ��Ҳ�����Ϣ����ʱ����ӽ�timestamp�߼����е�offset
     */
    public long getOffsetInQueueByTime(final long timestamp) {
        MapedFile mapedFile = this.mapedFileQueue.getMapedFileByTime(timestamp);
        if (mapedFile != null) {
            long offset = 0;
            // low:��һ��������Ϣ����ʼλ��
            // minLogicOffset������ֵ���
            // minLogicOffset-mapedFile.getFileFromOffset()λ�ÿ�ʼ������Чֵ
            int low =
                    minLogicOffset > mapedFile.getFileFromOffset() ? (int) (minLogicOffset - mapedFile
                        .getFileFromOffset()) : 0;

            // high:���һ��������Ϣ����ʼλ��
            int high = 0;
            int midOffset = -1, targetOffset = -1, leftOffset = -1, rightOffset = -1;
            long leftIndexValue = -1L, rightIndexValue = -1L;

            // ȡ����mapedFile�������е�ӳ��ռ�(û��ӳ��Ŀռ䲢���᷵��,���᷵���ļ��ն�)
            SelectMapedBufferResult sbr = mapedFile.selectMapedBuffer(0);
            if (null != sbr) {
                ByteBuffer byteBuffer = sbr.getByteBuffer();
                high = byteBuffer.limit() - CQStoreUnitSize;
                try {
                    while (high >= low) {
                        midOffset = (low + high) / (2 * CQStoreUnitSize) * CQStoreUnitSize;
                        byteBuffer.position(midOffset);
                        long phyOffset = byteBuffer.getLong();
                        int size = byteBuffer.getInt();

                        // �Ƚ�ʱ��, �۰�
                        long storeTime =
                                this.defaultMessageStore.getCommitLog().pickupStoretimestamp(phyOffset, size);
                        if (storeTime < 0) {
                            // û�д������ļ��ҵ���Ϣ����ʱֱ�ӷ���0
                            return 0;
                        }
                        else if (storeTime == timestamp) {
                            targetOffset = midOffset;
                            break;
                        }
                        else if (storeTime > timestamp) {
                            high = midOffset - CQStoreUnitSize;
                            rightOffset = midOffset;
                            rightIndexValue = storeTime;
                        }
                        else {
                            low = midOffset + CQStoreUnitSize;
                            leftOffset = midOffset;
                            leftIndexValue = storeTime;
                        }
                    }

                    if (targetOffset != -1) {
                        // ��ѯ��ʱ����������Ϣ������¼д���ʱ��
                        offset = targetOffset;
                    }
                    else {
                        if (leftIndexValue == -1) {
                            // timestamp ʱ��С�ڸ�MapedFile�е�һ����¼��¼��ʱ��
                            offset = rightOffset;
                        }
                        else if (rightIndexValue == -1) {
                            // timestamp ʱ����ڸ�MapedFile�����һ����¼��¼��ʱ��
                            offset = leftOffset;
                        }
                        else {
                            // ȡ��ӽ�timestamp��offset
                            offset =
                                    Math.abs(timestamp - leftIndexValue) > Math.abs(timestamp
                                            - rightIndexValue) ? rightOffset : leftOffset;
                        }
                    }

                    return (mapedFile.getFileFromOffset() + offset) / CQStoreUnitSize;
                }
                finally {
                    sbr.release();
                }
            }
        }

        // ӳ���ļ������Ϊ������ʱ����0
        return 0;
    }


    /**
     * ��������Offsetɾ����Ч�߼��ļ�
     */
    public void truncateDirtyLogicFiles(long phyOffet) {
        // �߼�����ÿ���ļ���С
        int logicFileSize = this.mapedFileSize;

        // �ȸı��߼����д洢������Offset
        this.maxPhysicOffset = phyOffet - 1;

        while (true) {
            MapedFile mapedFile = this.mapedFileQueue.getLastMapedFile2();
            if (mapedFile != null) {
                ByteBuffer byteBuffer = mapedFile.sliceByteBuffer();
                // �Ƚ�Offset���
                mapedFile.setWrotePostion(0);
                mapedFile.setCommittedPosition(0);

                for (int i = 0; i < logicFileSize; i += CQStoreUnitSize) {
                    long offset = byteBuffer.getLong();
                    int size = byteBuffer.getInt();
                    byteBuffer.getLong();

                    // �߼��ļ���ʼ��Ԫ
                    if (0 == i) {
                        if (offset >= phyOffet) {
                            this.mapedFileQueue.deleteLastMapedFile();
                            break;
                        }
                        else {
                            int pos = i + CQStoreUnitSize;
                            mapedFile.setWrotePostion(pos);
                            mapedFile.setCommittedPosition(pos);
                            this.maxPhysicOffset = offset;
                        }
                    }
                    // �߼��ļ��м䵥Ԫ
                    else {
                        // ˵����ǰ�洢��Ԫ��Ч
                        if (offset >= 0 && size > 0) {
                            // ����߼����д洢���������offset��������������offset���򷵻�
                            if (offset >= phyOffet) {
                                return;
                            }

                            int pos = i + CQStoreUnitSize;
                            mapedFile.setWrotePostion(pos);
                            mapedFile.setCommittedPosition(pos);
                            this.maxPhysicOffset = offset;

                            // ������һ��MapedFileɨ���꣬�򷵻�
                            if (pos == logicFileSize) {
                                return;
                            }
                        }
                        else {
                            return;
                        }
                    }
                }
            }
            else {
                break;
            }
        }
    }


    /**
     * �������һ����Ϣ��Ӧ������е�Next Offset
     */
    public long getLastOffset() {
        // �������Offset
        long lastOffset = -1;
        // �߼�����ÿ���ļ���С
        int logicFileSize = this.mapedFileSize;

        MapedFile mapedFile = this.mapedFileQueue.getLastMapedFile2();
        if (mapedFile != null) {
            ByteBuffer byteBuffer = mapedFile.sliceByteBuffer();

            // �Ƚ�Offset���
            mapedFile.setWrotePostion(0);
            mapedFile.setCommittedPosition(0);

            for (int i = 0; i < logicFileSize; i += CQStoreUnitSize) {
                long offset = byteBuffer.getLong();
                int size = byteBuffer.getInt();
                byteBuffer.getLong();

                // ˵����ǰ�洢��Ԫ��Ч
                if (offset >= 0 && size > 0) {
                    lastOffset = offset + size;
                    int pos = i + CQStoreUnitSize;
                    mapedFile.setWrotePostion(pos);
                    mapedFile.setCommittedPosition(pos);
                    this.maxPhysicOffset = offset;
                }
                else {
                    break;
                }
            }
        }

        return lastOffset;
    }


    public boolean commit(final int flushLeastPages) {
        return this.mapedFileQueue.commit(flushLeastPages);
    }


    public int deleteExpiredFile(long offset) {
        int cnt = this.mapedFileQueue.deleteExpiredFileByOffset(offset, CQStoreUnitSize);
        // �����Ƿ�ɾ���ļ�������Ҫ��������Сֵ����Ϊ�п��������ļ�ɾ���ˣ�
        // �����߼��ļ�һ��Ҳɾ������
        this.correctMinOffset(offset);
        return cnt;
    }


    /**
     * �߼����е���СOffsetҪ�ȴ����������СphyMinOffset��
     */
    public void correctMinOffset(long phyMinOffset) {
        MapedFile mapedFile = this.mapedFileQueue.getFirstMapedFileOnLock();
        if (mapedFile != null) {
            SelectMapedBufferResult result = mapedFile.selectMapedBuffer(0);
            if (result != null) {
                try {
                    // ����Ϣ����
                    for (int i = 0; i < result.getSize(); i += ConsumeQueue.CQStoreUnitSize) {
                        long offsetPy = result.getByteBuffer().getLong();
                        result.getByteBuffer().getInt();
                        result.getByteBuffer().getLong();

                        if (offsetPy >= phyMinOffset) {
                            this.minLogicOffset = result.getMapedFile().getFileFromOffset() + i;
                            log.info("compute logics min offset: " + this.getMinOffsetInQuque() + ", topic: "
                                    + this.topic + ", queueId: " + this.queueId);
                            break;
                        }
                    }
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
                finally {
                    result.release();
                }
            }
        }
    }


    public long getMinOffsetInQuque() {
        return this.minLogicOffset / CQStoreUnitSize;
    }


    public void putMessagePostionInfoWrapper(long offset, int size, long tagsCode, long storeTimestamp,
            long logicOffset) {
        final int MaxRetries = 5;
        boolean canWrite = this.defaultMessageStore.getRunningFlags().isWriteable();
        for (int i = 0; i < MaxRetries && canWrite; i++) {
            boolean result = this.putMessagePostionInfo(offset, size, tagsCode, logicOffset);
            if (result) {
                this.defaultMessageStore.getStoreCheckpoint().setLogicsMsgTimestamp(storeTimestamp);
                return;
            }
            // ֻ��һ�������ʧ�ܣ������µ�MapedFileʱ������߳�ʱ
            else {
                log.warn("put commit log postion info to " + topic + ":" + queueId + " " + offset
                        + " failed, retry " + i + " times");

                try {
                    Thread.sleep(1000 * 5);
                }
                catch (InterruptedException e) {
                    log.warn("", e);
                }
            }
        }

        this.defaultMessageStore.getRunningFlags().makeLogicsQueueError();
    }


    /**
     * �洢һ��20�ֽڵ���Ϣ��putMessagePostionInfoֻ��һ���̵߳��ã����Բ���Ҫ����
     * 
     * @param offset
     *            ��Ϣ��Ӧ��CommitLog offset
     * @param size
     *            ��Ϣ��CommitLog�洢�Ĵ�С
     * @param tagsCode
     *            tags ��������ĳ�����
     * @return �Ƿ�ɹ�
     */
    private boolean putMessagePostionInfo(final long offset, final int size, final long tagsCode,
            final long cqOffset) {
        // �����ݻָ�ʱ���ߵ��������
        if (offset <= this.maxPhysicOffset) {
            return true;
        }

        this.byteBufferIndex.flip();
        this.byteBufferIndex.limit(CQStoreUnitSize);
        this.byteBufferIndex.putLong(offset);
        this.byteBufferIndex.putInt(size);
        this.byteBufferIndex.putLong(tagsCode);

        final long realLogicOffset = cqOffset * CQStoreUnitSize;

        MapedFile mapedFile = this.mapedFileQueue.getLastMapedFile(realLogicOffset);
        if (mapedFile != null) {
            // ����MapedFile�߼���������˳��
            if (mapedFile.isFirstCreateInQueue() && cqOffset != 0 && mapedFile.getWrotePostion() == 0) {
                this.minLogicOffset = realLogicOffset;
                this.fillPreBlank(mapedFile, realLogicOffset);
                log.info("fill pre blank space " + mapedFile.getFileName() + " " + realLogicOffset + " "
                        + mapedFile.getWrotePostion());
            }

            if (cqOffset != 0) {
                if (realLogicOffset != (mapedFile.getWrotePostion() + mapedFile.getFileFromOffset())) {
                    log.warn("logic queue order maybe wrong " + realLogicOffset + " "
                            + (mapedFile.getWrotePostion() + mapedFile.getFileFromOffset()));
                }
            }

            // ��¼����������offset
            this.maxPhysicOffset = offset;
            return mapedFile.appendMessage(this.byteBufferIndex.array());
        }

        return false;
    }


    private void fillPreBlank(final MapedFile mapedFile, final long untilWhere) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(CQStoreUnitSize);
        byteBuffer.putLong(0L);
        byteBuffer.putInt(Integer.MAX_VALUE);
        byteBuffer.putLong(0L);

        int until = (int) (untilWhere % this.mapedFileQueue.getMapedFileSize());
        for (int i = 0; i < until; i += CQStoreUnitSize) {
            mapedFile.appendMessage(byteBuffer.array());
        }
    }


    /**
     * ����Index Buffer
     * 
     * @param startIndex
     *            ��ʼƫ��������
     */
    public SelectMapedBufferResult getIndexBuffer(final long startIndex) {
        int mapedFileSize = this.mapedFileSize;
        long offset = startIndex * CQStoreUnitSize;
        MapedFile mapedFile = this.mapedFileQueue.findMapedFileByOffset(offset);
        if (mapedFile != null) {
            SelectMapedBufferResult result = mapedFile.selectMapedBuffer((int) (offset % mapedFileSize));
            return result;
        }

        return null;
    }


    public long rollNextFile(final long index) {
        int mapedFileSize = this.mapedFileSize;
        int totalUnitsInFile = mapedFileSize / CQStoreUnitSize;
        return (index + totalUnitsInFile - index % totalUnitsInFile);
    }


    public String getTopic() {
        return topic;
    }


    public int getQueueId() {
        return queueId;
    }


    public long getMaxPhysicOffset() {
        return maxPhysicOffset;
    }


    public void setMaxPhysicOffset(long maxPhysicOffset) {
        this.maxPhysicOffset = maxPhysicOffset;
    }


    public void destroy() {
        this.maxPhysicOffset = -1;
        this.minLogicOffset = 0;
        this.mapedFileQueue.destroy();
    }


    public long getMinLogicOffset() {
        return minLogicOffset;
    }


    public void setMinLogicOffset(long minLogicOffset) {
        this.minLogicOffset = minLogicOffset;
    }


    /**
     * ��ȡ��ǰ�����е���Ϣ����
     */
    public long getMessageTotalInQueue() {
        return this.getMaxOffsetInQuque() - this.getMinOffsetInQuque();
    }


    public long getMaxOffsetInQuque() {
        return this.mapedFileQueue.getMaxOffset() / CQStoreUnitSize;
    }
}
