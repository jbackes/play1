package play.modules.ebean;

import com.avaje.ebean.TxIsolation;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Target(value={ElementType.METHOD,ElementType.TYPE})
public @interface Transactional {
	public TxIsolation mode() default TxIsolation.DEFAULT;
	public boolean explicitCommit() default false;
}
