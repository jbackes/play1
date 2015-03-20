package play.modules.ebean;

import com.avaje.ebean.EbeanServer;
import com.avaje.ebean.Transaction;
import play.Invoker;
import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.db.jpa.JPAPlugin;
import play.libs.F;

public class EbeanPlugin extends PlayPlugin {
	public EbeanPlugin() {
		super();
	}

	@Override
	public void onConfigurationRead() {
		PlayPlugin jpaPlugin = Play.pluginCollection.getPluginInstance(JPAPlugin.class);
		if (jpaPlugin != null && Play.pluginCollection.isEnabled(jpaPlugin)) {
			Logger.debug("EBEAN: Disabling JPAPlugin in order to replace JPA implementation");
			Play.pluginCollection.disablePlugin(jpaPlugin);
		}
	}

	@Override
	public void onApplicationStop() {
		Logger.debug("EBEAN: Clearing all servers");
		EbeanContext.clear();
	}

	@Override
	public void beforeInvocation() {
		final Transactional tx = Invoker.InvocationContext.current().getAnnotation(Transactional.class);

		if (tx != null) {
			final Transaction t = EbeanContext.server().beginTransaction(tx.mode());
			t.setReadOnly(tx.readOnly());
		}
	}

	@Override
	public void afterInvocation() {
		final EbeanServer server = EbeanContext.server();
		if (server == null)
			return;

		final Transactional tx = Invoker.InvocationContext.current().getAnnotation(Transactional.class);

		if (tx != null) {
			if (server.currentTransaction() == null || !server.currentTransaction().isActive()) {
				if (!tx.explicitCommit()) {
					Logger.warn("No active transaction after an @Transactional(explicitCommit = false) block");
				}
			} else {
				if (tx.explicitCommit() && !tx.readOnly()) {
					server.rollbackTransaction();
				} else {
					server.commitTransaction();
				}
			}
		} else {
			if (server.currentTransaction() != null && server.currentTransaction().isActive()) {
				Logger.warn("There is a non-committed transaction after invocation; rolling back...");
			}

			server.endTransaction();
		}
	}

	@Override
	public void onInvocationException(Throwable e) {
		final EbeanServer server = EbeanContext.server();
		if (server != null) {
			final Transactional tx = Invoker.InvocationContext.current().getAnnotation(Transactional.class);

			if (tx != null && !tx.explicitCommit() && (server.currentTransaction() == null || !server.currentTransaction().isActive())) {
				Logger.warn("No active transaction after exception in @Transactional(explicitCommit = false) block");
			}

			server.endTransaction();
		}
	}

	@Override
	public void invocationFinally() {
		final EbeanServer server = EbeanContext.server();
		if (server != null) {
			server.endTransaction();
		}
	}

	@Override
	public void enhance(ApplicationClass applicationClass) throws Exception {
		try {
			EbeanEnhancer.class.newInstance().enhanceThisClass(applicationClass);
		} catch (Throwable t) {
			Logger.error(t, "EbeanPlugin enhancement error");
		}
	}

	final private Filter filter = new Filter<Object>("EbeanFilter") {
		@Override
		public Object withinFilter(F.Function0<Object> objectFunction0) throws Throwable {
			return objectFunction0.apply();
		}
	};

	@Override
	public Filter getFilter() {
		return filter;
	}
}
