package play.modules.ebean;

import io.ebean.annotation.TxIsolation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Target(value={ElementType.METHOD,ElementType.TYPE})
public @interface Transactional {
	public TxIsolation mode() default TxIsolation.DEFAULT;
	public boolean explicitCommit() default false;
}
