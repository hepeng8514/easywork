package com.hp.easywork.mq;

import java.util.concurrent.atomic.AtomicLong;


/**
 * 引用计数基类，类似于C++智能指针实现
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
     * 资源是否能HOLD住
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
     * 资源是否可用，即是否可被HOLD
     */
    public boolean isAvailable() {
        return this.available;
    }


    /**
     * 禁止资源被访问 shutdown不允许调用多次，最好是由管理线程调用
     */
    public void shutdown(final long intervalForcibly) {
        if (this.available) {
            this.available = false;
            this.firstShutdownTimestamp = System.currentTimeMillis();
            this.release();
        }
        // 强制shutdown
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
     * 释放资源
     */
    public void release() {
        long value = this.refCount.decrementAndGet();
        if (value > 0)
            return;

        synchronized (this) {
            // cleanup内部要对是否clean做处理
            this.cleanupOver = this.cleanup(value);
        }
    }


    public abstract boolean cleanup(final long currentRef);


    /**
     * 资源是否被清理完成
     */
    public boolean isCleanupOver() {
        return this.refCount.get() <= 0 && this.cleanupOver;
    }
}
