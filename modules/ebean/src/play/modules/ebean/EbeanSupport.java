package play.modules.ebean;

import com.avaje.ebean.EbeanServer;
import com.avaje.ebean.Query;
import com.avaje.ebean.Update;
import play.Play;
import play.PlayPlugin;
import play.data.binding.BeanWrapper;
import play.data.binding.Binder;
import play.data.validation.Validation;
import play.db.Model;
import play.exceptions.UnexpectedException;
import play.mvc.Scope.Params;

import javax.persistence.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.*;

@SuppressWarnings("unchecked")
@MappedSuperclass
public abstract class EbeanSupport implements play.db.Model {
	private transient List<EbeanSupport> detachedManys = null;
	private transient boolean isSaving = false;

	public static <T extends EbeanSupport> T create(Class<?> type, String name, Map<String, String[]> params, Annotation[] annotations) {
		try {
			Constructor<?> c = type.getDeclaredConstructor();
			c.setAccessible(true);
			Object model = c.newInstance();
			return edit(model, name, params, annotations);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public static <T extends EbeanSupport> T edit(Object o, String name, Map<String, String[]> params, Annotation[] annotations) {
		try {
			BeanWrapper bw = new BeanWrapper(o.getClass());
			// Start with relations
			Set<Field> fields = new HashSet<>();
			Class<?> clazz = o.getClass();
			while (!clazz.equals(Object.class)) {
				Collections.addAll(fields, clazz.getDeclaredFields());
				clazz = clazz.getSuperclass();
			}
			for (Field field : fields) {
				boolean isEntity = false;
				String relation = null;
				boolean multiple = false;
				//
				if (field.isAnnotationPresent(OneToOne.class) || field.isAnnotationPresent(ManyToOne.class)) {
					isEntity = true;
					relation = field.getType().getName();
				}
				if (field.isAnnotationPresent(OneToMany.class) || field.isAnnotationPresent(ManyToMany.class)) {
					Class<?> fieldType = (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
					isEntity = true;
					relation = fieldType.getName();
					multiple = true;
				}

				if (isEntity) {
					Class<Model> cls = (Class<Model>) Play.classloader.loadClass(relation);
					if (EbeanSupport.class.isAssignableFrom(cls)) {
						String keyName = Model.Manager.factoryFor(cls).keyName();
						if (multiple && Collection.class.isAssignableFrom(field.getType())) {
							Collection<Object> l = new ArrayList<>();
							if (SortedSet.class.isAssignableFrom(field.getType())) {
								l = new TreeSet<>();
							} else if (Set.class.isAssignableFrom(field.getType())) {
								l = new HashSet<>();
							}
							String[] ids = params.get(name + "." + field.getName() + "." + keyName);
							if (ids != null) {
								params.remove(name + "." + field.getName() + "." + keyName);
								for (String _id : ids) {
									if (!_id.equals("")) {
										Object result = ebean().find(cls, Binder.directBind(_id, Model.Manager.factoryFor((Class<Model>) Play.classloader.loadClass(relation)).keyType()));
										if (result != null)
											l.add(result);
										else
											Validation.addError(name + "." + field.getName(), "validation.notFound", _id);
									}
								}
								bw.set(field.getName(), o, l);
							}
						} else {
							String[] ids = params.get(name + "." + field.getName() + "." + keyName);
							if (ids != null && ids.length > 0 && !ids[0].equals("")) {
								params.remove(name + "." + field.getName() + "." + keyName);
								Object to = ebean().find(cls, Binder.directBind(ids[0], Model.Manager.factoryFor((Class<Model>) Play.classloader.loadClass(relation)).keyType()));
								if (to != null)
									bw.set(field.getName(), o, to);
								else
									Validation.addError(name + "." + field.getName(), "validation.notFound", ids[0]);
							} else if (ids != null && ids.length > 0 && ids[0].equals("")) {
								bw.set(field.getName(), o, null);
								params.remove(name + "." + field.getName() + "." + keyName);
							}
						}
					}
				}
			}
			bw.bind(name, o.getClass(), params, "", o, annotations != null ? annotations : new Annotation[0]);
			return (T) o;
		} catch (Exception e) {
			throw new UnexpectedException(e);
		}
	}

	public <T extends EbeanSupport> T edit(String name, Map<String, String[]> params) {
		edit(this, name, params, null);
		return (T) this;
	}

	public boolean validateAndSave() {
		if (Validation.current().valid(this).ok) {
			save();
			return true;
		}
		return false;
	}

	public <T extends EbeanSupport> T save() {
		_save();
		return (T) this;
	}

	public <T extends EbeanSupport> T refresh() {
		ebean().refresh(this);
		return (T) this;
	}

	public <T extends EbeanSupport> T delete() {
		_delete();
		return (T) this;
	}

	public static <T extends EbeanSupport> T create(String name, Params params) {
		throw enhancementError();
	}

	public static long count() {
		throw enhancementError();
	}

	public static long count(String query, Object... params) {
		throw enhancementError();
	}

	public static <T extends EbeanSupport> List<T> findAll() {
		throw enhancementError();
	}

	public static <T extends EbeanSupport> T findById(Object id) {
		throw enhancementError();
	}

	public static <T extends EbeanSupport> T findUnique(String query, Object... params) {
		throw enhancementError();
	}

	public static <T extends EbeanSupport> Query<T> find(String query, Object... params) {
		throw enhancementError();
	}

	public static <T extends EbeanSupport> Query<T> all() {
		throw enhancementError();
	}

	public static int delete(String query, Object... params) {
		throw enhancementError();
	}

	public static int deleteAll() {
		throw enhancementError();
	}

	protected static <T extends EbeanSupport> Query<T> createQuery(Class<T> beanType, String where, Object[] params) {
		Query<T> q = ebean().createQuery(beanType);
		if (where != null) {
			q.where().raw(where);
			for (int i = 0; i < params.length; i++)
				q.setParameter(i + 1, params[i]);
		}
		return q;
	}

	protected static <T extends EbeanSupport> Update<T> createDeleteQuery(Class<T> beanType, String where, Object[] params) {
		String delete = "delete from " + beanType.getSimpleName();
		if (where != null) delete = delete + " where " + where;
		Update<T> d = ebean().createUpdate(beanType, delete);
		if (params != null) {
			for (int i = 0; i < params.length; i++)
				d.setParameter(i + 1, params[i]);
		}
		return d;
	}

	protected void afterLoad() {
	}

	protected void beforeSave(boolean isInsert) {
	}

	protected void afterSave(boolean isInsert) {
	}

	protected static EbeanServer ebean() {
		return EbeanContext.server();
	}

	private static UnsupportedOperationException enhancementError() {
		return new UnsupportedOperationException("Please annotate your JPA model with @javax.persistence.Entity annotation.");
	}

	public void _save() {
		this.isSaving = true;
		ebean().save(this);
		if (detachedManys != null)
			detachedManys.stream().filter(es -> !es.isSaving).forEach(play.modules.ebean.EbeanSupport::save);
		this.isSaving = false;
		PlayPlugin.postEvent("JPASupport.objectPersisted", this);
	}

	public void _delete() {
		ebean().delete(this);
		PlayPlugin.postEvent("JPASupport.objectDeleted", this);
	}

	protected void detachMany(final EbeanSupport es) {
		if (detachedManys == null)
			detachedManys = new LinkedList<>();

		detachedManys.add(es);
	}
}