package play.mvc;

public interface ActionWrapper {
	public Object execute(final ActionExecutor executor) throws Exception;
}
