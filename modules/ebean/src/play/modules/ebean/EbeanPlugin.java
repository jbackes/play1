package play.modules.ebean;

import com.avaje.ebean.EbeanServer;
import com.avaje.ebean.EbeanServerFactory;
import com.avaje.ebean.Query;
import com.avaje.ebean.Update;
import com.avaje.ebean.config.ServerConfig;
import play.Invoker;
import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.data.binding.Binder;
import play.db.DB;
import play.db.Model;
import play.db.Model.ModelProperty;
import play.db.jpa.JPAPlugin;
import play.exceptions.UnexpectedException;
import play.libs.F;
import play.mvc.Http;
import play.mvc.Http.Request;

import javax.persistence.*;
import javax.sql.DataSource;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.*;

public class EbeanPlugin extends PlayPlugin
{
  private static EbeanServer              defaultServer;
  private static final Map<String, EbeanServer> SERVERS = new HashMap<String,EbeanServer>();

  @SuppressWarnings({ "unchecked", "rawtypes" })
  private static EbeanServer createServer(String name, DataSource dataSource)
  {
    EbeanServer result = null;
    ServerConfig cfg = new ServerConfig();
    cfg.loadFromProperties();
    cfg.setName(name);
    cfg.setClasses((List) Play.classloader.getAllClasses());
    cfg.setDataSource(new EbeanDataSourceWrapper(dataSource));
    cfg.setRegister("default".equals(name));
    cfg.setDefaultServer("default".equals(name));
    cfg.add(new EbeanModelAdapter());
    try {
      result = EbeanServerFactory.create(cfg);
    } catch (Throwable t) {
      Logger.error("Failed to create ebean server (%s)", t.getMessage());
    }
    return result;
  }

  protected static EbeanServer checkServer(String name, DataSource ds)
  {
    EbeanServer server = null;
    if (name != null) {
      synchronized (SERVERS) {
        server = SERVERS.get(name);
        if (server == null) {
          server = createServer(name, ds);
          SERVERS.put(name, server);
        }
      }
    }
    return server;
  }
  
  public EbeanPlugin()
  {
    super();
  }

  @Override
  public void onConfigurationRead()
  {
    PlayPlugin jpaPlugin = Play.pluginCollection.getPluginInstance(JPAPlugin.class);
    if (jpaPlugin != null && Play.pluginCollection.isEnabled(jpaPlugin)) {
      Logger.debug("EBEAN: Disabling JPAPlugin in order to replace JPA implementation");
      Play.pluginCollection.disablePlugin(jpaPlugin);
    }
  }

  @Override
  public void onApplicationStart()
  {
    if (DB.datasource != null) {
      Logger.debug("EBEAN: Creating default server");
      defaultServer = createServer("default", DB.datasource);
    }
  }

  @Override
  public void onApplicationStop()
  {
    Logger.debug("EBEAN: close all servers");
    SERVERS.clear();
  }

  @Override
  @SuppressWarnings("unchecked")
  public void beforeInvocation()
  {
    EbeanServer server = defaultServer;

    Request currentRequest = Http.Request.current();
    if (currentRequest != null) {
      // Hook to introduce more data sources
      Map<String, DataSource> ds = (Map<String, DataSource>) currentRequest.args.get("dataSources");
      if (ds != null && ds.size() > 0) {
        // Currently we support single data source
        Map.Entry<String,DataSource> firstEntry = ds.entrySet().iterator().next();
        server = checkServer(firstEntry.getKey(),firstEntry.getValue());
      }
    }
    EbeanContext.set(server);

	final Transactional tx = Invoker.InvocationContext.current().getAnnotation(Transactional.class);

	if(tx != null) {
		server.beginTransaction(tx.mode());
	}
  }

  @Override
  public void afterInvocation()
  {
    final EbeanServer server = EbeanContext.server();
    if (server != null) {
	    final Transactional tx = Invoker.InvocationContext.current().getAnnotation(Transactional.class);

	    if(tx != null) {
		    if(server.currentTransaction() == null || !server.currentTransaction().isActive()) {
			    if(!tx.explicitCommit()) {
			        Logger.warn("No active transaction after an @Transactional(explicitCommit = false) block");
			    }
		    }
		    else {
				if(tx.explicitCommit()) {
			        server.rollbackTransaction();
				}
				else {
					server.commitTransaction();
				}
		    }
	    } else {
		    if(server.currentTransaction() != null && server.currentTransaction().isActive()) {
			    Logger.warn("There is a non-committed transaction after invocation; rolling back...");
		    }

		    server.endTransaction();
	    }
    }
  }

	@Override
	public void onInvocationException(Throwable e) {
		final EbeanServer server = EbeanContext.server();
		if(server != null) {
			final Transactional tx = Invoker.InvocationContext.current().getAnnotation(Transactional.class);

			if(tx != null && !tx.explicitCommit() && (server.currentTransaction() == null || !server.currentTransaction().isActive())) {
				Logger.warn("No active transaction after exception in @Transactional(explicitCommit = false) block");
			}

			server.endTransaction();
		}
	}

	@Override
  public void invocationFinally()
  {
    EbeanServer ebean = null;
    try {
      ebean = EbeanContext.server();
    } catch(IllegalStateException e) {
	    Logger.error("No ebean server in finally");
    }
    if (ebean != null) {
	    ebean.endTransaction();
    }
    EbeanContext.set(null);
  }

  @Override
  public void enhance(ApplicationClass applicationClass) throws Exception
  {
    try {
      EbeanEnhancer.class.newInstance().enhanceThisClass(applicationClass);
    } catch (Throwable t) {
      Logger.error(t, "EbeanPlugin enhancement error");
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public Model.Factory modelFactory(Class<? extends Model> modelClass)
  {
    if (EbeanSupport.class.isAssignableFrom(modelClass) && modelClass.isAnnotationPresent(Entity.class)) {
      return new EbeanModelLoader((Class<EbeanSupport>) modelClass);
    }
    return null;
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  @Override
  public Object bind(String name, Class clazz, java.lang.reflect.Type type, Annotation[] annotations, Map<String, String[]> params)
  {
    if (EbeanSupport.class.isAssignableFrom(clazz)) {
      String keyName = Model.Manager.factoryFor(clazz).keyName();
      String idKey = name + "." + keyName;
      if (params.containsKey(idKey) && params.get(idKey).length > 0 && params.get(idKey)[0] != null && params.get(idKey)[0].trim().length() > 0) {
        String id = params.get(idKey)[0];
        Object o;
        try {
          o = EbeanContext.server().find(clazz, play.data.binding.Binder.directBind(name, annotations, id, Model.Manager.factoryFor(clazz).keyType()));
        } catch (Exception e) {
          throw new UnexpectedException(e);
        }
        return EbeanSupport.edit(o, name, params, annotations);
      }
      return EbeanSupport.create(clazz, name, params, annotations);
    }
    return super.bind(name, clazz, type, annotations, params);
  }

  @Override
  public Object bind(String name, Object o, Map<String, String[]> params)
  {
    if (o instanceof EbeanSupport) {
      return EbeanSupport.edit(o, name, params, null);
    }
    return null;
  }
  
  public static class EbeanModelLoader implements Model.Factory
  {

    private Class<? extends EbeanSupport> modelClass;

    public EbeanModelLoader(Class<EbeanSupport> modelClass)
    {
      this.modelClass = modelClass;
    }

    public String keyName()
    {
      return keyField().getName();
    }

    public Class<?> keyType()
    {
      return keyField().getType();
    }

    public Object keyValue(Model m)
    {
      try {
        return keyField().get(m);
      } catch (Exception ex) {
        throw new UnexpectedException(ex);
      }
    }

    public Model findById(Object id)
    {
      if (id == null) return null;

      try {
        return EbeanContext.server().find(modelClass, Binder.directBind(id.toString(), this.keyType()));
      } catch (Exception e) {
        return null;
      }
    }

    @SuppressWarnings("unchecked")
    public List<Model> fetch(int offset, int size, String orderBy, String order, List<String> searchFields, String keywords, String where)
    {
      Query<?> q = EbeanContext.server().createQuery(modelClass);
      String filter = null;
      if (where != null && !where.trim().equals("")) {
        filter = where.trim();
      }
      if (keywords != null && !keywords.equals("")) {
        String searchQuery = getSearchQuery(searchFields);
        if (!searchQuery.equals("")) {
          filter = (filter != null ? "(" + filter + ") and " : "") + "(" + searchQuery + ")";
        }
      }

      if (filter != null) { 
        q.where(filter);
        if (filter.contains(":keywords") && keywords != null)  q.setParameter("keywords",  "%" + keywords.toLowerCase() + "%");
      }

      if (orderBy == null && order == null) {
        orderBy = "id";
        order = "ASC";
      }
      if (orderBy == null) {
        orderBy = "id";
      }
      if (order == null || (!order.equals("ASC") && !order.equals("DESC"))) {
        order = "ASC";
      }
      q.orderBy(orderBy + " " + order);
      q.setFirstRow(offset).setMaxRows(size);
      return (List<Model>) q.findList();
    }

    public Long count(List<String> searchFields, String keywords, String where)
    {
      Query<?> q = EbeanContext.server().createQuery(modelClass);
      String filter = null;
      if (where != null && !where.trim().equals("")) {
        filter = where.trim();
      }
      if (keywords != null && !keywords.equals("")) {
        String searchQuery = getSearchQuery(searchFields);
        if (!searchQuery.equals("")) {
          filter = (filter != null ? "(" + filter + ") and " : "") + "(" + searchQuery + ")";
        }
      }
      if (filter != null) { 
        q.where(filter);
        if (filter.contains(":keywords") && keywords != null)  q.setParameter("keywords", "%" + keywords.toLowerCase() + "%");
      }

      return (long) q.findRowCount();
    }

    public void deleteAll()
    {
      String query = "delete from " + modelClass.getSimpleName();
      Update<?> deleteAll = EbeanContext.server().createUpdate(modelClass, query);
      deleteAll.execute();
    }

    public List<ModelProperty> listProperties()
    {
      return listProperties(true);
    }

    private List<ModelProperty> listProperties(boolean includeTransient)
    {
      List<Model.ModelProperty> properties = new ArrayList<Model.ModelProperty>();
      Set<Field> fields = new HashSet<Field>();
      Class<?> tclazz = modelClass;
      while (!tclazz.equals(Object.class)) {
        Collections.addAll(fields, tclazz.getDeclaredFields());
        tclazz = tclazz.getSuperclass();
      }
      for (Field f : fields) {
        if (Modifier.isStatic(f.getModifiers()) || f.getName().toLowerCase().startsWith("_ebean")) {
          continue;
        }
        if (!includeTransient && (Modifier.isTransient(f.getModifiers()) || f.getAnnotation(javax.persistence.Transient.class) != null)) {
          continue;
        }
        Model.ModelProperty mp = buildProperty(f);
        if (mp != null) {
          properties.add(mp);
        }
      }
      return properties;
    }
    
    private Field keyField()
    {
      Class<?> c = modelClass;
      try {
        while (!c.equals(EbeanSupport.class)) {
          for (Field field : c.getDeclaredFields()) {
            if (field.isAnnotationPresent(Id.class)) {
              field.setAccessible(true);
              return field;
            }
          }
          c = c.getSuperclass();
        }
      } catch (Exception e) {
        throw new UnexpectedException("Error while determining the object @Id for an object of type " + modelClass);
      }
      throw new UnexpectedException("Cannot get the object @Id for an object of type " + modelClass);
    }

    Model.ModelProperty buildProperty(final Field field)
    {
      Model.ModelProperty modelProperty = new Model.ModelProperty();
      modelProperty.type = field.getType();
      modelProperty.field = field;
      if (Model.class.isAssignableFrom(field.getType())) {
        if (field.isAnnotationPresent(OneToOne.class)) {
          if (field.getAnnotation(OneToOne.class).mappedBy().equals("")) {
            modelProperty.isRelation = true;
            modelProperty.relationType = field.getType();
            modelProperty.choices = new Model.Choices() {

              @SuppressWarnings("unchecked")
              public List<Object> list()
              {
                List<?> result = EbeanContext.server().find(field.getType()).findList();
                return (List<Object>) result;
              }
            };
          }
        }
        if (field.isAnnotationPresent(ManyToOne.class)) {
          modelProperty.isRelation = true;
          modelProperty.relationType = field.getType();
          modelProperty.choices = new Model.Choices() {

            @SuppressWarnings("unchecked")
            public List<Object> list()
            {
              List<?> result = EbeanContext.server().find(field.getType()).findList();
              return (List<Object>) result;
            }
          };
        }
      }
      if (Collection.class.isAssignableFrom(field.getType())) {
        final Class<?> fieldType = (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
        if (field.isAnnotationPresent(OneToMany.class)) {
          if (field.getAnnotation(OneToMany.class).mappedBy().equals("")) {
            modelProperty.isRelation = true;
            modelProperty.isMultiple = true;
            modelProperty.relationType = fieldType;
            modelProperty.choices = new Model.Choices() {

              @SuppressWarnings("unchecked")
              public List<Object> list()
              {
                List<?> result = EbeanContext.server().find(fieldType).findList();
                return (List<Object>) result;
              }
            };
          }
        }
        if (field.isAnnotationPresent(ManyToMany.class)) {
          if (field.getAnnotation(ManyToMany.class).mappedBy().equals("")) {
            modelProperty.isRelation = true;
            modelProperty.isMultiple = true;
            modelProperty.relationType = fieldType;
            modelProperty.choices = new Model.Choices() {

              @SuppressWarnings("unchecked")
              public List<Object> list()
              {
                List<?> result = EbeanContext.server().find(fieldType).findList();
                return (List<Object>) result;
              }
            };
          }
        }
      }
      if (field.getType().isEnum()) {
        modelProperty.choices = new Model.Choices() {

          @SuppressWarnings("unchecked")
          public List<Object> list()
          {
            return Arrays.asList(field.getType().getEnumConstants());
          }
        };
      }
      modelProperty.name = field.getName();
      if (field.getType().equals(String.class)) {
        modelProperty.isSearchable = true;
      }
      if (field.isAnnotationPresent(GeneratedValue.class)) {
        modelProperty.isGenerated = true;
      }
      return modelProperty;
    }

    String getSearchQuery(List<String> searchFields)
    {
      String q = "";
      for (Model.ModelProperty property : listProperties(false)) {
        if (property.isSearchable && (searchFields == null || searchFields.isEmpty() || searchFields.contains(property.name))) {
          if (!q.equals("")) {
            q += " or ";
          }
          q += "lower(" + property.name + ") like :keywords";
        }
      }
      return q;
    }
  }

	private Filter filter = new Filter<Object>("EbeanFilter") {
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
