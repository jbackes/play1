package play.modules.ebean;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.ebean.enhance.Transformer;
import io.ebean.enhance.common.AgentManifest;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtMethod;
import javassist.CtNewConstructor;
import play.Logger;
import play.Play;
import play.classloading.ApplicationClasses.ApplicationClass;
import play.classloading.enhancers.Enhancer;
import play.exceptions.UnexpectedException;

import io.ebean.enhance.common.ClassBytesReader;
import io.ebean.enhance.common.InputStreamTransform;

public class EbeanEnhancer extends Enhancer
{
  private static ClassFileTransformer transformer = new Transformer(new PlayClassBytesReader(), "packages=play/modules/ebean,models;transientInternalFields=true;debug=0", AgentManifest.read(Play.classloader, Stream.of("models.*").collect(Collectors.toSet())));

  public void enhanceThisClass(ApplicationClass applicationClass) throws Exception
  {
    CtClass ctClass = makeClass(applicationClass);

    // Enhance only JPA entities
    if (!hasAnnotation(ctClass, "javax.persistence.Entity") && !hasAnnotation(ctClass, "javax.persistence.MappedSuperclass")) {
      return;
    }

    // Ebean transformations
    byte[] buffer = transformer.transform(Play.classloader, applicationClass.name, null, null, applicationClass.enhancedByteCode);
    if (buffer != null) applicationClass.enhancedByteCode = buffer;

    ctClass = makeClass(applicationClass);

    if (!ctClass.subtypeOf(classPool.get("de.peloba.ebean.EbeanSupport"))) {
      // We don't want play style enhancements to happen to classes other than subclasses of EbeanSupport
      return;
    }

    String entityName = ctClass.getName();

    // Add a default constructor if needed
    try {
      boolean hasDefaultConstructor = false;
      for (CtConstructor constructor : ctClass.getConstructors()) {
        if (constructor.getParameterTypes().length == 0) {
          hasDefaultConstructor = true;
          break;
        }
      }
      if (!hasDefaultConstructor && !ctClass.isInterface()) {
        CtConstructor defaultConstructor = CtNewConstructor.make("private " + ctClass.getSimpleName() + "() {}", ctClass);
        ctClass.addConstructor(defaultConstructor);
      }
    } catch (Throwable t) {
      Logger.error(t, "Error in EbeanEnhancer");
      throw new UnexpectedException("Error in EbeanEnhancer", t);
    }

    // create     
    ctClass.addMethod(CtMethod.make("public static de.peloba.ebean.EbeanSupport create(String name, play.mvc.Scope.Params params) { return create(" + entityName + ".class,name, params.all(), null); }",ctClass));

    // count
    ctClass.addMethod(CtMethod.make("public static long count() { return (long) ebean().createQuery(" + entityName + ".class).findCount(); }", ctClass));
    ctClass.addMethod(CtMethod.make("public static long count(String query, Object[] params) { return (long) createQuery(" + entityName + ".class,query,params).findCount(); }", ctClass));

    // findAll
    ctClass.addMethod(CtMethod.make("public static java.util.List findAll() { return ebean().createQuery(" + entityName + ".class).findList(); }", ctClass));

    // findById
    ctClass.addMethod(CtMethod.make("public static de.peloba.ebean.EbeanSupport findById(Object id) { return id == null ? null : (" + entityName + ") ebean().find(" + entityName + ".class, id); }", ctClass));

    // findUnique
    ctClass.addMethod(CtMethod.make("public static de.peloba.ebean.EbeanSupport findUnique(String query, Object[] params) { return (" + entityName + ") createQuery(" + entityName + ".class,query,params).findOne(); }", ctClass));

    // find
    ctClass.addMethod(CtMethod.make("public static io.ebean.Query find(String query, Object[] params) { return createQuery(" + entityName + ".class,query,params); }", ctClass));

    // all
    ctClass.addMethod(CtMethod.make("public static io.ebean.Query all() { return ebean().createQuery(" + entityName + ".class); }", ctClass));

    // delete
    ctClass.addMethod(CtMethod.make("public static int delete(String query, Object[] params) { return createDeleteQuery(" + entityName + ".class,query,params).execute(); }", ctClass));

    // deleteAll
    ctClass.addMethod(CtMethod.make("public static int deleteAll() { return  createDeleteQuery(" + entityName + ".class,null,null).execute(); }", ctClass));

    // Done.
    applicationClass.enhancedByteCode = ctClass.toBytecode();
    ctClass.defrost();
    Logger.debug("EBEAN: Class '%s' has been enhanced",ctClass.getName());
  }

  static class PlayClassBytesReader implements ClassBytesReader
  {

    public byte[] getClassBytes(String className, ClassLoader classLoader)
    {
      ApplicationClass ac = Play.classes.getApplicationClass(className.replace("/", "."));
      return ac != null ? ac.enhancedByteCode : getBytesFromClassPath(className);
    }

    private byte[] getBytesFromClassPath(String className)
    {
      String resource = className + ".class";
      byte[] classBytes = null;
      InputStream is = Play.classloader.getResourceAsStream(resource);
      try {
        classBytes = InputStreamTransform.readBytes(is);
      } catch (IOException e) {
        throw new RuntimeException("IOException reading bytes for " + className, e);
      } finally {
        if (is != null) {
          try {
            is.close();
          } catch (IOException e) {
            throw new RuntimeException("Error closing InputStream for " + className, e);
          }
        }
      }
      return classBytes;
    }

  }

}
