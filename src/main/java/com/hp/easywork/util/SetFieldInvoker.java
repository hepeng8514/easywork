package com.hp.easywork.util;

import java.lang.reflect.Field;

/**
 * @author Clinton Begin
 */
public class SetFieldInvoker implements Invoker {
  private Field field;

  public SetFieldInvoker(Field field) {
    this.field = field;
  }

  public Object invoke(Object target, Object[] args) throws Exception{
    field.set(target, args[0]);
    return null;
  }

  public Class<?> getType() {
    return field.getType();
  }
}
