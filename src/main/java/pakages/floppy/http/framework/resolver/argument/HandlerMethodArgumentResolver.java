package pakages.floppy.http.framework.resolver.argument;

import pakages.floppy.http.framework.Request;

import java.io.OutputStream;
import java.lang.reflect.Parameter;

public interface HandlerMethodArgumentResolver {
  boolean supportsParameter(Parameter parameter);
  Object resolveArgument(Parameter parameter, Request request, OutputStream response);
}
