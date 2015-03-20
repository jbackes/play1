package play.modules.ebean;

import com.avaje.ebean.EbeanServer;
import com.avaje.ebean.EbeanServerFactory;
import com.avaje.ebean.config.ServerConfig;
import play.Logger;
import play.Play;
import play.db.DB;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class EbeanContext {
	private static Map<String, EbeanServer> servers = new ConcurrentHashMap<>();

	private EbeanContext() {
	}

	public static EbeanServer server() {
		return server("default");
	}

	public static EbeanServer server(String name) {
		return servers.computeIfAbsent(name, (key) -> createServer(name) );
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private static EbeanServer createServer(String name) {
		final DataSource dataSource = DB.getDataSource(name);

		if(dataSource == null) {
			Logger.warn("Failed to create ebean server since data source %s does not exist", name);
			return null;
		}

		ServerConfig cfg = new ServerConfig();
		cfg.loadFromProperties();
		cfg.setName(name);
		cfg.setClasses((List) Play.classloader.getAllClasses());
		cfg.setDataSource(dataSource);
		cfg.setRegister("default".equals(name));
		cfg.setDefaultServer("default".equals(name));
		cfg.add(new EbeanModelAdapter());
		try {
			return EbeanServerFactory.create(cfg);
		} catch (Throwable t) {
			Logger.error(t, "Failed to create ebean server %s", name);
			return null;
		}
	}

	public static void clear() {
		servers.clear();
	}
}
