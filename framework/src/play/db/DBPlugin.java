package play.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jregex.Matcher;
import org.apache.commons.lang.StringUtils;
import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.db.DB.ExtendedDatasource;
import play.exceptions.DatabaseException;
import play.mvc.Http;
import play.mvc.Http.Request;
import play.mvc.Http.Response;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.sql.DataSource;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.*;
import java.util.*;
/**
 * The DB plugin
 */
public class DBPlugin extends PlayPlugin {

    public static String url = "";
    org.h2.tools.Server h2Server;

    @Override
    public boolean rawInvocation(Request request, Response response) throws Exception {
        if (Play.mode.isDev() && request.path.equals("/@db")) {
            response.status = Http.StatusCode.FOUND;
            String serverOptions[] = new String[] { };

            // For H2 embeded database, we'll also start the Web console
            if (h2Server != null) {
                h2Server.stop();
            }

            String domain = request.domain;
            if (domain.equals("")) {
                domain = "localhost";
            }

            if (!domain.equals("localhost")) {
                serverOptions = new String[] {"-webAllowOthers"};
            }
            
            h2Server = org.h2.tools.Server.createWebServer(serverOptions);
            h2Server.start();

            response.setHeader("Location", "http://" + domain + ":8082/");
            return true;
        }
        return false;
    }

   
    @Override
    public void onApplicationStart() {
        if (changed()) {
            String dbName = "";
            try {
                // Destroy all connections
                if (!DB.datasources.isEmpty()) {
                    DB.destroyAll();
                }
                
                Set<String> dbNames = Configuration.getDbNames();
                Iterator<String> it = dbNames.iterator();
                while(it.hasNext()) {
                    dbName = it.next();
                    Configuration dbConfig = new Configuration(dbName);
                    
                    boolean isJndiDatasource = false;
                    String datasourceName = dbConfig.getProperty("db", "");

                    // Identify datasource JNDI lookup name by 'jndi:' or 'java:' prefix 
                    if (datasourceName.startsWith("jndi:")) {
                        datasourceName = datasourceName.substring("jndi:".length());
                        isJndiDatasource = true;
                    }

                    if (isJndiDatasource || datasourceName.startsWith("java:")) {
                        Context ctx = new InitialContext();
                        DataSource ds =  (DataSource) ctx.lookup(datasourceName);
                        DB.datasource = ds;
                        DB.destroyMethod = "";
                        DB.ExtendedDatasource extDs = new DB.ExtendedDatasource(ds, "");
                        DB.datasources.put(dbName, extDs);  
                    } else {

                        // Try the driver
                        String driver = dbConfig.getProperty("db.driver");
                        try {
                            Driver d = (Driver) Class.forName(driver, true, Play.classloader).newInstance();
                            DriverManager.registerDriver(new ProxyDriver(d));
                        } catch (Exception e) {
                            throw new Exception("Database [" + dbName + "] Driver not found (" + driver + ")");
                        }

                        // Try the connection
                        Connection fake = null;
                        try {
                            if (dbConfig.getProperty("db.user") == null) {
                                fake = DriverManager.getConnection(dbConfig.getProperty("db.url"));
                            } else {
                                fake = DriverManager.getConnection(dbConfig.getProperty("db.url"), dbConfig.getProperty("db.user"), dbConfig.getProperty("db.pass"));
                            }
                        } finally {
                            if (fake != null) {
                                fake.close();
                            }
                        }

                        //System.setProperty("com.mchange.v2.log.MLog", "com.mchange.v2.log.FallbackMLog");
                        //System.setProperty("com.mchange.v2.log.FallbackMLog.DEFAULT_CUTOFF_LEVEL", "OFF");

	                    HikariConfig hc = new HikariConfig();
	                    hc.setDriverClassName(dbConfig.getProperty("db.driver"));
                        hc.setJdbcUrl(dbConfig.getProperty("db.url"));
                        hc.setUsername(dbConfig.getProperty("db.user"));
                        hc.setPassword(dbConfig.getProperty("db.pass"));

	                    if(StringUtils.isNotBlank(dbConfig.getProperty("db.autoCommit")))
		                    hc.setAutoCommit(Boolean.parseBoolean(dbConfig.getProperty("db.autoCommit")));
	                    if(StringUtils.isNotBlank(dbConfig.getProperty("db.readOnly")))
		                    hc.setReadOnly(Boolean.parseBoolean(dbConfig.getProperty("db.readOnly")));
	                    if(StringUtils.isNotBlank(dbConfig.getProperty("db.transactionIsolation")))
		                    hc.setTransactionIsolation(dbConfig.getProperty("db.transactionIsolation"));
	                    if(StringUtils.isNotBlank(dbConfig.getProperty("db.catalog")))
		                    hc.setCatalog(dbConfig.getProperty("db.catalog"));
	                    if(StringUtils.isNotBlank(dbConfig.getProperty("db.connectionTimeout")))
		                    hc.setConnectionTimeout(Long.parseLong(dbConfig.getProperty("db.connectionTimeout")));
	                    if(StringUtils.isNotBlank(dbConfig.getProperty("db.idleTimeout")))
		                    hc.setIdleTimeout(Long.parseLong(dbConfig.getProperty("db.idleTimeout")));
	                    if(StringUtils.isNotBlank(dbConfig.getProperty("db.maxLifetime")))
		                    hc.setMaxLifetime(Long.parseLong(dbConfig.getProperty("db.maxLifetime")));
	                    if(StringUtils.isNotBlank(dbConfig.getProperty("db.leakDetectionThreshold")))
		                    hc.setLeakDetectionThreshold(Long.parseLong(dbConfig.getProperty("db.leakDetectionThreshold")));
	                    if(StringUtils.isNotBlank(dbConfig.getProperty("db.initializationFailFast")))
		                    hc.setInitializationFailFast(Boolean.parseBoolean(dbConfig.getProperty("db.initializationFailFast")));
	                    if(StringUtils.isNotBlank(dbConfig.getProperty("db.jdbc4ConnectionTest")))
		                    hc.setJdbc4ConnectionTest(Boolean.parseBoolean(dbConfig.getProperty("db.jdbc4ConnectionTest")));
	                    if(StringUtils.isNotBlank(dbConfig.getProperty("db.connectionTestQuery")))
		                    hc.setConnectionTestQuery(dbConfig.getProperty("db.connectionTestQuery"));
	                    if(StringUtils.isNotBlank(dbConfig.getProperty("db.connectionInitSql")))
		                    hc.setConnectionInitSql(dbConfig.getProperty("db.connectionInitSql"));
	                    if(StringUtils.isNotBlank(dbConfig.getProperty("db.minimumIdle")))
		                    hc.setMinimumIdle(Integer.parseInt(dbConfig.getProperty("db.minimumIdle")));
	                    if(StringUtils.isNotBlank(dbConfig.getProperty("db.maximumPoolSize")))
		                    hc.setMaximumPoolSize(Integer.parseInt(dbConfig.getProperty("db.maximumPoolSize")));
	                    if(StringUtils.isNotBlank(dbConfig.getProperty("db.poolName")))
		                    hc.setPoolName(dbConfig.getProperty("db.poolName"));
	                    if(StringUtils.isNotBlank(dbConfig.getProperty("db.registerMbeans")))
		                    hc.setRegisterMbeans(Boolean.parseBoolean(dbConfig.getProperty("db.registerMbeans")));
	                    if(StringUtils.isNotBlank(dbConfig.getProperty("db.isolateInternalQueries")))
		                    hc.setIsolateInternalQueries(Boolean.parseBoolean(dbConfig.getProperty("db.isolateInternalQueries")));

	                    HikariDataSource ds = new HikariDataSource(hc);

	                    // Current datasource. This is actually deprecated.
                        String destroyMethod = dbConfig.getProperty("db.destroyMethod", "");
                        DB.datasource = ds;
                        DB.destroyMethod = destroyMethod;

                        DB.ExtendedDatasource extDs = new DB.ExtendedDatasource(ds, destroyMethod);

                        url = ds.getJdbcUrl();
                        Connection c = null;
                        try {
                            c = ds.getConnection();
                        } finally {
                            if (c != null) {
                                c.close();
                            }
                        }
                        Logger.info("Connected to %s for %s", ds.getJdbcUrl(), dbName);
                        DB.datasources.put(dbName, extDs);
                    }
                }
                
            } catch (Exception e) {
                DB.datasource = null;
                Logger.error(e, "Database [%s] Cannot connected to the database : %s", dbName, e.getMessage());
                if (e.getCause() instanceof InterruptedException) {
                    throw new DatabaseException("Cannot connected to the database["+ dbName + "]. Check the configuration.", e);
                }
                throw new DatabaseException("Cannot connected to the database["+ dbName + "], " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void onApplicationStop() {
        if (Play.mode.isProd()) {
            DB.destroyAll();
        }
    }

    @Override
    public String getStatus() {
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);     
        Set<String> dbNames = Configuration.getDbNames();
               
        for (String dbName : dbNames) {
            DataSource ds = DB.getDataSource(dbName);
            if (!(ds instanceof HikariDataSource)) {
                out.println("Datasource:");
                out.println("~~~~~~~~~~~");
                out.println("(not yet connected)");
                return sw.toString();
            }
	        HikariDataSource datasource = (HikariDataSource) ds;
            out.println("Datasource (" + dbName + "):");
            out.println("~~~~~~~~~~~");
            out.println("Jdbc url: " + datasource.getJdbcUrl());
            out.println("Jdbc user: " + datasource.getUsername());
    	    if (Play.mode.isDev()) {
              out.println("Jdbc password: " + datasource.getPassword());
            }
            out.println("Max pool size: " + datasource.getMaximumPoolSize());
            out.println("Connection timeout: " + datasource.getConnectionTimeout());
	        out.println("Connection idle: " + datasource.getIdleTimeout());
            out.println("Test query : " + datasource.getConnectionTestQuery());
            out.println("\r\n");
        }
        return sw.toString();
    }

    @Override
    public void invocationFinally() {
        DB.closeAll();
    }

    private static void check(Configuration config, String mode, String property) {
        if (!StringUtils.isEmpty(config.getProperty(property))) {
            Logger.warn("Ignoring " + property + " because running the in " + mode + " db.");
        }
    }

    private static boolean changed() {
        Set<String> dbNames = Configuration.getDbNames();
        
        for (String dbName : dbNames) {
            Configuration dbConfig = new Configuration(dbName);
            
            if ("mem".equals(dbConfig.getProperty("db")) && dbConfig.getProperty("db.url") == null) {
                dbConfig.put("db.driver", "org.h2.Driver");
                dbConfig.put("db.url", "jdbc:h2:mem:play;MODE=MYSQL");
                dbConfig.put("db.user", "sa");
                dbConfig.put("db.pass", "");
            }

            if ("fs".equals(dbConfig.getProperty("db")) && dbConfig.getProperty("db.url") == null) {
                dbConfig.put("db.driver", "org.h2.Driver");
                dbConfig.put("db.url", "jdbc:h2:" + (new File(Play.applicationPath, "db/h2/play").getAbsolutePath()) + ";MODE=MYSQL");
                dbConfig.put("db.user", "sa");
                dbConfig.put("db.pass", "");
            }
            String datasourceName = dbConfig.getProperty("db", "");
            DataSource ds = DB.getDataSource(dbName);
                     
            if ((datasourceName.startsWith("java:")) && dbConfig.getProperty("db.url") == null) {
                if (ds == null) {
                    return true;
                }
            } else {
                // Internal pool is c3p0, we should call the close() method to destroy it.
                check(dbConfig, "internal pool", "db.destroyMethod");

                dbConfig.put("db.destroyMethod", "close");
            }

            Matcher m = new jregex.Pattern("^mysql:(//)?(({user}[a-zA-Z0-9_]+)(:({pwd}[^@]+))?@)?(({host}[^/]+)/)?({name}[a-zA-Z0-9_]+)(\\?)?({parameters}[^\\s]+)?$").matcher(dbConfig.getProperty("db", ""));
            if (m.matches()) {
                String user = m.group("user");
                String password = m.group("pwd");
                String name = m.group("name");
                String host = m.group("host");
                String parameters = m.group("parameters");
        		
                Map<String, String> paramMap = new HashMap<String, String>();
                paramMap.put("useUnicode", "yes");
                paramMap.put("characterEncoding", "UTF-8");
                paramMap.put("connectionCollation", "utf8_general_ci");
                addParameters(paramMap, parameters);
                
                dbConfig.put("db.driver", "com.mysql.jdbc.Driver");
                dbConfig.put("db.url", "jdbc:mysql://" + (host == null ? "localhost" : host) + "/" + name + "?" + toQueryString(paramMap));
                if (user != null) {
                    dbConfig.put("db.user", user);
                }
                if (password != null) {
                    dbConfig.put("db.pass", password);
                }
            }
            
            m = new jregex.Pattern("^postgres:(//)?(({user}[a-zA-Z0-9_]+)(:({pwd}[^@]+))?@)?(({host}[^/]+)/)?({name}[^\\s]+)$").matcher(dbConfig.getProperty("db", ""));
            if (m.matches()) {
                String user = m.group("user");
                String password = m.group("pwd");
                String name = m.group("name");
                String host = m.group("host");
                dbConfig.put("db.driver", "org.postgresql.Driver");
                dbConfig.put("db.url", "jdbc:postgresql://" + (host == null ? "localhost" : host) + "/" + name);
                if (user != null) {
                    dbConfig.put("db.user", user);
                }
                if (password != null) {
                    dbConfig.put("db.pass", password);
                }
            }

            if(dbConfig.getProperty("db.url") != null && dbConfig.getProperty("db.url").startsWith("jdbc:h2:mem:")) {
                dbConfig.put("db.driver", "org.h2.Driver");
                dbConfig.put("db.user", "sa");
                dbConfig.put("db.pass", "");
            }

            if ((dbConfig.getProperty("db.driver") == null) || (dbConfig.getProperty("db.url") == null)) {
                return false;
            }
            
            if (ds == null) {
                return true;
            } else {
                HikariDataSource cds = (HikariDataSource) ds;
                if (!dbConfig.getProperty("db.url").equals(cds.getJdbcUrl())) {
                    return true;
                }
                if (!dbConfig.getProperty("db.user", "").equals(cds.getUsername())) {
                    return true;
                }
                if (!dbConfig.getProperty("db.pass", "").equals(cds.getPassword())) {
                    return true;
                }
            }

            ExtendedDatasource extDataSource = DB.datasources.get(dbName);

            if (extDataSource != null && !dbConfig.getProperty("db.destroyMethod", "").equals(extDataSource.getDestroyMethod())) {
                return true;
            }
        }
        return false;
    }
    
    private static void addParameters(Map<String, String> paramsMap, String urlQuery) {
    	if (!StringUtils.isBlank(urlQuery)) {
	    	String[] params = urlQuery.split("[\\&]");
	    	for (String param : params) {
				String[] parts = param.split("[=]");
				if (parts.length > 0 && !StringUtils.isBlank(parts[0])) {
				    paramsMap.put(parts[0], parts.length > 1 ? StringUtils.stripToNull(parts[1]) : null);
				}
			}
    	}
    }
    
    private static String toQueryString(Map<String, String> paramMap) {
    	StringBuilder builder = new StringBuilder();
    	for (Map.Entry<String, String> entry : paramMap.entrySet()) {
    		if (builder.length() > 0) builder.append("&");
			builder.append(entry.getKey()).append("=").append(entry.getValue() != null ? entry.getValue() : "");
		}
    	return builder.toString();
    }

    /**
     * Needed because DriverManager will not load a driver ouside of the system classloader
     */
    public static class ProxyDriver implements Driver {

        private Driver driver;

        ProxyDriver(Driver d) {
            this.driver = d;
        }

        public boolean acceptsURL(String u) throws SQLException {
            return this.driver.acceptsURL(u);
        }

        public Connection connect(String u, Properties p) throws SQLException {
            return this.driver.connect(u, p);
        }

        public int getMajorVersion() {
            return this.driver.getMajorVersion();
        }

        public int getMinorVersion() {
            return this.driver.getMinorVersion();
        }

        public DriverPropertyInfo[] getPropertyInfo(String u, Properties p) throws SQLException {
            return this.driver.getPropertyInfo(u, p);
        }

        public boolean jdbcCompliant() {
            return this.driver.jdbcCompliant();
        }
      
        // Method not annotated with @Override since getParentLogger() is a new method
        // in the CommonDataSource interface starting with JDK7 and this annotation
        // would cause compilation errors with JDK6.
        public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            try {
                return (java.util.logging.Logger) Driver.class.getDeclaredMethod("getParentLogger").invoke(this.driver);
            } catch (Throwable e) {
                return null;
            }
        }
    }
}
