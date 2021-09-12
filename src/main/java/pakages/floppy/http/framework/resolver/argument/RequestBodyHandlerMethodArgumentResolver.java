package pakages.floppy.http.framework.resolver.argument;

import pakages.floppy.http.framework.Part;
import pakages.floppy.http.framework.Request;
import pakages.floppy.http.framework.annotation.RequestBody;
import pakages.floppy.http.framework.exception.MalformedRequestException;
import pakages.floppy.http.framework.exception.ServerException;
import pakages.floppy.http.framework.exception.UnsupportedParameterException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class RequestBodyHandlerMethodArgumentResolver implements HandlerMethodArgumentResolver{
    private final Class<RequestBody> annotation = RequestBody.class;

    @Override
    public boolean supportsParameter(Parameter parameter) {
        return parameter.getType().isAssignableFrom(byte[].class) && parameter.isAnnotationPresent(annotation);
    }

    @Override
    public Object resolveArgument(Parameter parameter, Request request, OutputStream response) {
        if (!supportsParameter(parameter)) {
            throw new UnsupportedParameterException(parameter.getType().getName());
        }
        final RequestBody annotation = parameter.getAnnotation(this.annotation);
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();

        if (request.getHeaders().get("Content-Type").equals("application/x-www-form-urlencoded")) {
            final Map<String, List<String>> body = new HashMap<>();
            final List<String> values = new ArrayList<>();
            final var splited = request.getForm().get(annotation.value());
            for(String val:splited){
                values.addAll(Arrays.asList(val.split(",")));
            }
            body.put(annotation.value(), values);

            ObjectOutputStream oos;
            try {
                oos = new ObjectOutputStream(bos);
                oos.writeObject(body);
                oos.flush();
            } catch (IOException e) {
                throw new ServerException(e);
            }

            return bos.toByteArray();
        }

        if (request.getHeaders().get("Content-Type").split("; ")[0].equals("multipart/form-data")){
            final byte[] multi = request.getMultipart();
            String str = new String(multi, StandardCharsets.UTF_8);
            Map<String, Part> multipart = new HashMap<>();
            final String boundary = str.split("\r\n")[0];
            final String[] parts = str.split(boundary + "\r\n");
            for(int i = 1; i < parts.length; i++){
                String[] content_data = parts[i].split("\r\n\r\n");
                byte[] data = content_data[1].replace("\r\n", "").getBytes();
                Part part = new Part(content_data[0], data);
                if(part.getName().equals(annotation.value())){
                    multipart.put(part.getName(), part);
                }
            }
            if(multipart.get(annotation.value()) == null){
                throw new MalformedRequestException();
            }

            ObjectOutputStream oos;
            try {
                oos = new ObjectOutputStream(bos);
                oos.writeObject(multipart);
            } catch (IOException e) {
                throw new ServerException(e);
            }

            return bos.toByteArray();
        }
        throw new MalformedRequestException();
    }
}
