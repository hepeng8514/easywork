package com.hp.easywork.util;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;

/**
 * @author Clinton Begin
 */
public class GetFieldInvoker implements Invoker {
  private Field field;

  public GetFieldInvoker(Field field) {
    this.field = field;
  }

  public Object invoke(Object target, Object[] args) throws Exception{
    return field.get(target);
  }

  public Class<?> getType() {
    return field.getType();
  }
}
