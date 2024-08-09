package com.grswebservices.peopledb.annotation;

import com.grswebservices.peopledb.model.CrudOperation;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
@Repeatable(MultiSQL.class) // this annotation is repeatable
public @interface SQL {
    String value();
    CrudOperation operationType();
}
