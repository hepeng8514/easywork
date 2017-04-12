package com.hp.easywork.mq;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.rocketmq.common.UtilALl;
import com.alibaba.rocketmq.common.constant.LoggerName;


/**
 * ��¼�洢ģ������һ�µ�ʱ���
 * 
 * @author shijia.wxr<vintage.wang@gmail.com>
 * @since 2013-7-21
 */
public class StoreCheckpoint {
    private static final Logger log = LoggerFactory.getLogger(LoggerName.StoreLoggerName);
    private final RandomAccessFile randomAccessFile;
    private final FileChannel fileChannel;
    private final MappedByteBuffer mappedByteBuffer;
    private volatile long physicMsgTimestamp = 0;
    private volatile long logicsMsgTimestamp = 0;
    private volatile long indexMsgTimestamp = 0;


    public StoreCheckpoint(final String scpPath) throws IOException {
        File file = new File(scpPath);
        MapedFile.ensureDirOK(file.getParent());
        boolean fileExists = file.exists();

        this.randomAccessFile = new RandomAccessFile(file, "rw");
        this.fileChannel = this.randomAccessFile.getChannel();
        this.mappedByteBuffer = fileChannel.map(MapMode.READ_WRITE, 0, MapedFile.OS_PAGE_SIZE);

        if (fileExists) {
            log.info("store checkpoint file exists, " + scpPath);
            this.physicMsgTimestamp = this.mappedByteBuffer.getLong(0);
            this.logicsMsgTimestamp = this.mappedByteBuffer.getLong(8);
            this.indexMsgTimestamp = this.mappedByteBuffer.getLong(16);

            log.info("store checkpoint file physicMsgTimestamp " + this.physicMsgTimestamp + ", "
                    + UtilALl.timeMillisToHumanString(this.physicMsgTimestamp));
            log.info("store checkpoint file logicsMsgTimestamp " + this.logicsMsgTimestamp + ", "
                    + UtilALl.timeMillisToHumanString(this.logicsMsgTimestamp));
            log.info("store checkpoint file indexMsgTimestamp " + this.indexMsgTimestamp + ", "
                    + UtilALl.timeMillisToHumanString(this.indexMsgTimestamp));
        }
        else {
            log.info("store checkpoint file not exists, " + scpPath);
        }
    }


    public void shutdown() {
        this.flush();

        // unmap mappedByteBuffer
        MapedFile.clean(this.mappedByteBuffer);

        try {
            this.fileChannel.close();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }


    public void flush() {
        this.mappedByteBuffer.putLong(0, this.physicMsgTimestamp);
        this.mappedByteBuffer.putLong(8, this.logicsMsgTimestamp);
        this.mappedByteBuffer.putLong(16, this.indexMsgTimestamp);
        this.mappedByteBuffer.force();
    }


    public long getPhysicMsgTimestamp() {
        return physicMsgTimestamp;
    }


    public void setPhysicMsgTimestamp(long physicMsgTimestamp) {
        this.physicMsgTimestamp = physicMsgTimestamp;
    }


    public long getLogicsMsgTimestamp() {
        return logicsMsgTimestamp;
    }


    public void setLogicsMsgTimestamp(long logicsMsgTimestamp) {
        this.logicsMsgTimestamp = logicsMsgTimestamp;
    }


    public long getMinTimestampIndex() {
        return Math.min(Math.min(this.physicMsgTimestamp, this.logicsMsgTimestamp), this.indexMsgTimestamp);
    }


    public long getMinTimestamp() {
        return Math.min(this.physicMsgTimestamp, this.logicsMsgTimestamp);
    }


    public long getIndexMsgTimestamp() {
        return indexMsgTimestamp;
    }


    public void setIndexMsgTimestamp(long indexMsgTimestamp) {
        this.indexMsgTimestamp = indexMsgTimestamp;
    }

}
