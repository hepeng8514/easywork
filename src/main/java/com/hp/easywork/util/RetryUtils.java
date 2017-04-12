package com.hp.easywork.util;

import java.sql.SQLException;


public abstract class RetryUtils {

	private static final int dbRetryCount = 3; // 
	private static final long dbRetryDelay = 200L; //

	private static final int zkRetryCount = 10; //
	private static final long zkRetryDelay = 500L; //

	private static final int tmcRetryCount = 10; //
	private static final long tmcRetryDelay = 200L; //
	public static <T> T dbRetry(RetryOperation<T> operation) throws Exception {
		Exception exp = null;

		for (int i = 0; i < dbRetryCount; i++) {
			try {
				return (T) operation.execute();
			} catch (SQLException e) {
				if (exp == null) {
					exp = e;
				}
				retryDelay(i, dbRetryDelay);
			}
		}

		throw exp;
	}

	public static <T> T dbRetry(CheckOperation<T> operation, CheckResult checkResult) throws Exception {
		Exception exp = null;

		for (int i = 0; i < dbRetryCount; i++) {
			try {
				return (T) operation.execute(checkResult);
			} catch (SQLException e) {
				if (exp == null) {
					exp = e;
				}
				retryDelay(i, dbRetryDelay);
				checkResult = operation.check();
			}
		}

		throw exp;
	}

	public static <T> T zkRetry(RetryOperation<T> operation) throws Exception {
		KeeperException exp = null;

		for (int i = 0; i < zkRetryCount; i++) {
			try {
				return (T) operation.execute();
			} catch (KeeperException.SessionExpiredException e) {
				throw e;
			} catch (KeeperException.ConnectionLossException e) {
				if (exp == null) {
					exp = e;
				}
				retryDelay(i, zkRetryDelay);
			}
		}

		throw exp;
	}
	
	public static <T> T tmcRetry(RetryOperation<T> operation) throws Exception {
		Exception exp = null;

		for (int i = 0; i < tmcRetryCount; i++) {
			try {
				return (T) operation.execute();
			} catch (LinkException e) {
				if (exp == null) {
					exp = e;
				}
				retryDelay(i, tmcRetryDelay);
			}
		}

		throw exp;
	}

	private static void retryDelay(int attemptCount, long retryDelay) {
		if (attemptCount > 0) {
			try {
				Thread.sleep(attemptCount * retryDelay);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	public static void delay4Conflict() throws InterruptedException {
		long mod = System.currentTimeMillis() % 4L;
		Thread.sleep((mod + 2) * 100L);
	}

}
