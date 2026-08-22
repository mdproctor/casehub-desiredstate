package io.casehub.desiredstate.annotations;

import io.casehub.desiredstate.api.NodeSpec;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface DependsOn {
    String[] value() default {};

    Class<? extends NodeSpec>[] nodes() default {};
}
