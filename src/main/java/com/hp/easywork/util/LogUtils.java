package com.hp.easywork.util;

import com.taobao.top.datapush.domain.NotifyData;
import com.taobao.top.datapush.domain.log.NotifyLog;
import com.taobao.top.datapush.domain.log.TaskLog;

/**
 * ��־��㹤���ࣨ����Ҫȷ��ÿ������Ĵ���ǵ��̵߳Ĳ�������
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

	//�����Ե�ʱ�õ�
	public static TaskLog getTaskLog() {
		return taskLog.get();
	}
	public static void delTaskLog() {
		taskLog.remove();
	}

	/**
	 * ����һ�������ѯ��¼������
	 */
	public static void addTaskSelectCount() {
		TaskLog log = taskLog.get();
		if (log != null) {
			log.addSelectCount();
		}
	}

	/**
	 * ����һ����������¼������
	 */
	public static void addTaskInsertCount() {
		TaskLog log = taskLog.get();
		if (log != null) {
			log.addInsertCount();
		}
	}

	/**
	 * ����һ��������¼�¼������
	 */
	public static void addTaskUpdateCount() {
		TaskLog log = taskLog.get();
		if (log != null) {
			log.addUpdateCount();
		}
	}

	/**
	 * �Ƿ��������Ե���������׷������
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
	 * ����һ�δ����¼
	 */
	public static void addTaskTotalCount() {
		TaskLog log = taskLog.get();
		if (log != null) {
			log.addTotalCount();
		}
	}

	/**
	 * ����һ������ɾ����¼������
	 */
	public static void addTaskDeleteCount() {
		TaskLog log = taskLog.get();
		if (log != null) {
			log.addDeleteCount();
		}
	}

	/**
	 * ����һ������ɾ����¼������
	 */
	public static void addTaskDeleteCounts(int delCount) {
		TaskLog log = taskLog.get();
		if (log != null) {
			log.addDeleteCount(delCount);
		}
	}

	/**
	 * ��������֪ͨ��Ϣ��־��¼
	 * 
	 * @param operation ��������
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
