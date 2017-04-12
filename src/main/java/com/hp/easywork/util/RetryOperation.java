package com.hp.easywork.util;

public interface RetryOperation<T> {

	public T execute() throws Exception;

}
