package com.grswebservices.peopledb.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Specify that this annotation can contain multiple @SQL annotation
 * by defining a value method that returns an array of SQL annotations.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface MultiSQL {
    SQL[] value();
}
