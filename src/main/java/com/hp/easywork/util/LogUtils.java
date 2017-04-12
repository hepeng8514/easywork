package com.hp.easywork.util;

import com.taobao.top.datapush.domain.NotifyData;
import com.taobao.top.datapush.domain.log.NotifyLog;
import com.taobao.top.datapush.domain.log.TaskLog;

/**
 * 日志打点工具类（这里要确保每个任务的打点是单线程的操作）。
 * 
 * @author fengsheng
 * @since 1.0, Jan 18, 2013
 */
public abstract class LogUtils {

	private static final ThreadLocal<TaskLog> taskLog = new ThreadLocal<TaskLog>();
	private static final ThreadLocal<NotifyLog> notifyLog = new ThreadLocal<NotifyLog>();
	private static final ThreadLocal<NotifyData> notifyData = new ThreadLocal<NotifyData>();

	public static void setTaskLog(TaskLog log) {
		taskLog.set(log);
	}

	//并发对单时用到
	public static TaskLog getTaskLog() {
		return taskLog.get();
	}
	public static void delTaskLog() {
		taskLog.remove();
	}

	/**
	 * 增加一次任务查询记录次数。
	 */
	public static void addTaskSelectCount() {
		TaskLog log = taskLog.get();
		if (log != null) {
			log.addSelectCount();
		}
	}

	/**
	 * 增加一次任务插入记录次数。
	 */
	public static void addTaskInsertCount() {
		TaskLog log = taskLog.get();
		if (log != null) {
			log.addInsertCount();
		}
	}

	/**
	 * 增加一次任务更新记录次数。
	 */
	public static void addTaskUpdateCount() {
		TaskLog log = taskLog.get();
		if (log != null) {
			log.addUpdateCount();
		}
	}

	/**
	 * 是否是正常对单，而不是追单任务
	 */
	public static boolean isTradeChaseNormal() {
		boolean result=false;
		TaskLog log = taskLog.get();
		if (log != null) {
			if(TaskLog.CHASE_TYPE_DEFAULT.equals(log.getChaseType())&&log.getType().startsWith("check_trade")){
				result=true;
			}
		}
		return result;
	}
	public static String showTaskLogInfo() {
		TaskLog log = taskLog.get();
		if (log != null) {
			return log.getChaseType()+"--"+log.getType();
		}else{
			return null;
		}
		
	}
	/**
	 * 增加一次处理记录
	 */
	public static void addTaskTotalCount() {
		TaskLog log = taskLog.get();
		if (log != null) {
			log.addTotalCount();
		}
	}

	/**
	 * 增加一次任务删除记录次数。
	 */
	public static void addTaskDeleteCount() {
		TaskLog log = taskLog.get();
		if (log != null) {
			log.addDeleteCount();
		}
	}

	/**
	 * 增加一次任务删除记录次数。
	 */
	public static void addTaskDeleteCounts(int delCount) {
		TaskLog log = taskLog.get();
		if (log != null) {
			log.addDeleteCount(delCount);
		}
	}

	/**
	 * 设置主动通知消息日志记录
	 * 
	 * @param operation 操作类型
	 */
	public static void setNotifyLog(NotifyLog log) {
		notifyLog.set(log);
	}

	public static void delNotifyLog() {
		notifyLog.remove();
	}
	public static void addNotifyLogMsg(String msg) {
		if(notifyLog.get() != null){
			notifyLog.get().setErrMsg(msg);
		}
	}

	public static void setNotifyBizTimeCost(long timeCost) {
		if (notifyLog.get() != null) {
			notifyLog.get().setNotifyTimeCost(timeCost);
		}
	}

	public static void setSendTmcTimeCost(long timeCost) {
		if (notifyLog.get() != null) {
			notifyLog.get().setSendTmcTimeCost(timeCost);
		}
	}

	public static void addNotifyOpCount() {
		NotifyLog nlog = notifyLog.get();
		if (nlog != null) {
			nlog.setOpCount(nlog.getOpCount() + 1);
		}

		TaskLog tlog = taskLog.get();
		if (tlog != null) {
			tlog.addOpCount();
		}
	}


	public static void setNotifyData(NotifyData data) {
		notifyData.set(data);
	}

	public static void delNotifyData() {
		notifyData.remove();
	}

}
