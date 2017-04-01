package com.hp.easywork.util;

import java.lang.reflect.InvocationTargetException;

/**
 * @author Clinton Begin
 */
public interface Invoker {
  Object invoke(Object target, Object[] args) throws Exception;

  Class<?> getType();
}
