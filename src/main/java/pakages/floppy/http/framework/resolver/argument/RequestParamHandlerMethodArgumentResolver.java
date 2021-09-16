package pakages.floppy.http.framework.resolver.argument;

import pakages.floppy.http.framework.Request;
import pakages.floppy.http.framework.annotation.RequestParam;
import pakages.floppy.http.framework.exception.MalformedRequestException;
import pakages.floppy.http.framework.exception.UnsupportedParameterException;

import java.io.OutputStream;
import java.lang.reflect.Parameter;
import java.util.*;

public class RequestParamHandlerMethodArgumentResolver implements HandlerMethodArgumentResolver{
    private final Class<RequestParam> annotation = RequestParam.class;

    @Override
    public boolean supportsParameter(Parameter parameter) {
        return parameter.getType().isAssignableFrom(Map.class) && parameter.isAnnotationPresent(annotation);
    }

    @Override
    public Object resolveArgument(Parameter parameter, Request request, OutputStream response) {
        if (!supportsParameter(parameter)) {
            throw new UnsupportedParameterException(parameter.getType().getName());
        }

        final RequestParam annotation = parameter.getAnnotation(this.annotation);

        if(request.getQuery().get(annotation.value()) == null){
            throw new MalformedRequestException();
        }

        final Map<String,List<String>> query = new HashMap<>();
        final List<String> values = new ArrayList<>();
        final List<String> queryVal = request.getQuery().get(annotation.value());
        for(String value: queryVal){
            final var valuesArr = value.split("%2C");
            values.addAll(Arrays.asList(valuesArr));
        }
        query.put(annotation.value(), values);

        return query;
    }
}
