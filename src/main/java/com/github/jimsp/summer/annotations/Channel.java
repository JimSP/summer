package com.github.jimsp.summer.annotations;

import jakarta.inject.Qualifier;
import java.lang.annotation.*;

@Qualifier
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Channel {
    String value();
}
