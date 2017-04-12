package com.hp.easywork.mq;

import java.util.concurrent.atomic.AtomicLong;


/**
 * ���ü������࣬������C++����ָ��ʵ��
 * 
 * @author shijia.wxr<vintage.wang@gmail.com>
 * @since 2013-7-21
 */
public abstract class ReferenceResource {
    protected final AtomicLong refCount = new AtomicLong(1);
    protected volatile boolean available = true;
    protected volatile boolean cleanupOver = false;
    private volatile long firstShutdownTimestamp = 0;


    /**
     * ��Դ�Ƿ���HOLDס
     */
    public synchronized boolean hold() {
        if (this.isAvailable()) {
            if (this.refCount.getAndIncrement() > 0) {
                return true;
            }
            else {
                this.refCount.getAndDecrement();
            }
        }

        return false;
    }


    /**
     * ��Դ�Ƿ���ã����Ƿ�ɱ�HOLD
     */
    public boolean isAvailable() {
        return this.available;
    }


    /**
     * ��ֹ��Դ������ shutdown��������ö�Σ�������ɹ����̵߳���
     */
    public void shutdown(final long intervalForcibly) {
        if (this.available) {
            this.available = false;
            this.firstShutdownTimestamp = System.currentTimeMillis();
            this.release();
        }
        // ǿ��shutdown
        else if (this.getRefCount() > 0) {
            if ((System.currentTimeMillis() - this.firstShutdownTimestamp) >= intervalForcibly) {
                this.refCount.set(-1000 - this.getRefCount());
                this.release();
            }
        }
    }


    public long getRefCount() {
        return this.refCount.get();
    }


    /**
     * �ͷ���Դ
     */
    public void release() {
        long value = this.refCount.decrementAndGet();
        if (value > 0)
            return;

        synchronized (this) {
            // cleanup�ڲ�Ҫ���Ƿ�clean������
            this.cleanupOver = this.cleanup(value);
        }
    }


    public abstract boolean cleanup(final long currentRef);


    /**
     * ��Դ�Ƿ��������
     */
    public boolean isCleanupOver() {
        return this.refCount.get() <= 0 && this.cleanupOver;
    }
}
