package pakages.floppy.http.framework;

import io.github.classgraph.ClassGraph;
import lombok.extern.java.Log;
import pakages.floppy.http.framework.annotation.RequestMapping;
import pakages.floppy.http.framework.guava.Bytes;
import pakages.floppy.http.framework.resolver.argument.HandlerMethodArgumentResolver;
import pakages.floppy.http.framework.exception.*;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;

@Log
public class Server {
  private static final byte[] CRLF = new byte[]{'\r', '\n'};
  private static final byte[] CRLFCRLF = new byte[]{'\r', '\n', '\r', '\n'};
  private final static int headersLimit = 4096;
  private final static long bodyLimit = 10 * 1024 * 1024;
  private final Object closeLock = new Object();
  private boolean closed = false;
  private final ExecutorService service = Executors.newFixedThreadPool(64, r -> {
    final var thread = new Thread(r);
    thread.setDaemon(true);
    return thread;
  });
  // GET, "/search", handler
  private final Map<String, Map<String, HandlerMethod>> routes = new HashMap<>();
  // 404 Not Found ->
  // 500 Internal Server error ->
  private final Handler notFoundHandler = (request, response) -> {
    // language=JSON
    final var body = "{\"status\": \"error\"}";
    try {
      response.write(
          (
          // language=HTTP
          "HTTP/1.1 404 Not Found\r\n" +
          "Content-Length: " + body.length() + "\r\n" +
          "Content-Type: application/json\r\n" +
          "Connection: close\r\n" +
          "\r\n" +
          body
          ).getBytes(StandardCharsets.UTF_8)
      );
    } catch (IOException e) {
      throw new RequestHandleException(e);
    }
  };
  private final Handler internalErrorHandler = (request, response) -> {
    // language=JSON
    final var body = "{\"status\": \"error\"}";
    try {
      response.write(
          (
          // language=HTTP
          "HTTP/1.1 500 Internal Server Error\r\n" +
          "Content-Length: " + body.length() + "\r\n" +
          "Content-Type: application/json\r\n" +
          "Connection: close\r\n" +
          "\r\n" +
          body
          ).getBytes(StandardCharsets.UTF_8)
      );
    } catch (IOException e) {
      throw new RequestHandleException(e);
    }
  };

  private final Handler badRequestHandler = ((request, response) -> {
    final var body = "{\"status\": \"error\"}" +
                      "\n{\"message\": \"Bad request\"}";
    try {
      response.write(
              (
              "HTTP/1.1 500 Internal Server Error\r\n" +
              "Content-Length: " + body.length() + "\r\n" +
              "Content-Type: application/json\r\n" +
              "Connection: close\r\n" +
              "\r\n" +
              body
              ).getBytes(StandardCharsets.UTF_8)
      );
    } catch (IOException e) {
      throw new RequestHandleException(e);
    }
  });

  private final List<HandlerMethodArgumentResolver> argumentResolvers = new ArrayList<>();

  public void get(String path, Handler handler) {
    registerHandler(HttpMethods.GET, path, handler);
  }

  public void post(String path, Handler handler) {
    registerHandler(HttpMethods.POST, path, handler);
  }

  public void autoRegisterHandlers(String pkg) {
    try (final var scanResult = new ClassGraph().enableAllInfo().acceptPackages(pkg).scan()) {
      for (final var classInfo : scanResult.getClassesWithMethodAnnotation(RequestMapping.class.getName())) {
        final var handler = classInfo.loadClass().getConstructor().newInstance();
        for (final var method : handler.getClass().getMethods()) {
          if (method.isAnnotationPresent(RequestMapping.class)) {
            final RequestMapping mapping = method.getAnnotation(RequestMapping.class);

            final var handlerMethod = new HandlerMethod(handler, method);
            Optional.ofNullable(routes.get(mapping.method()))
                .ifPresentOrElse(
                    map -> map.put(mapping.path(), handlerMethod),
                    () -> routes.put(mapping.method(), new HashMap<>(Map.of(mapping.path(), handlerMethod)))
                );
          }
        }
      }
    } catch (InvocationTargetException | InstantiationException | IllegalAccessException | NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
  }

  public void registerHandler(String method, String path, Handler handler) {
    try {
      final var handle = handler.getClass().getMethod("handle", Request.class, OutputStream.class);
      final var handlerMethod = new HandlerMethod(handler, handle);
      Optional.ofNullable(routes.get(method))
          .ifPresentOrElse(
              map -> map.put(path, handlerMethod),
              () -> routes.put(method, new HashMap<>(Map.of(path, handlerMethod)))
          );
    } catch (NoSuchMethodException e) {
      throw new HandlerRegistrationException(e);
    }
//    final var map = routes.get(method);
//    if (map != null) {
//      map.put(path, handler);
//      return;
//    }
//    routes.put(method, new HashMap<>(Map.of(path, handler)));
  }

  public void addArgumentResolver(HandlerMethodArgumentResolver... resolvers) {
    argumentResolvers.addAll(List.of(resolvers));
  }

  public void listen(int port) {
    try (final ServerSocket serverSocket = new ServerSocket(port)){
      log.log(Level.INFO, "server started at port: " + serverSocket.getLocalPort());
      while(!isClosed()){
        Future<Socket> acceptThread = service.submit(() -> {
          try {
            return serverSocket.accept();
          } catch (IOException e) {
            throw new ServerException(e);
          }});
        while(true){
          if(isClosed() | acceptThread.isDone()){
            break;
          }
        }
        if(!isClosed()) {
          try {
            Socket socket = acceptThread.get();
            service.submit(() -> handle(socket));
          } catch (InterruptedException | ExecutionException e) {
            throw new ServerException(e);
          }
        }
      }
    } catch (IOException e) {
      throw new ServerException(e);
    }
  }

  public void stop() {
    synchronized (closeLock){
      if(isClosed()){
        if(service.isShutdown()){
          service.shutdownNow();
        }
        return;
      }
      closed = true;
      if(service.isShutdown()){
        service.shutdownNow();
      }
    }
  }

  public boolean isClosed(){
    return closed;
  }

  public void handle(final Socket socket) {
    try (
        socket;
        final var in = new BufferedInputStream(socket.getInputStream());
        final var out = new BufferedOutputStream(socket.getOutputStream());
    ) {
      log.log(Level.INFO, "connected: " + socket.getPort());
      final var buffer = new byte[headersLimit];
      in.mark(headersLimit);

      final var read = in.read(buffer);

      try {
        final var requestLineEndIndex = Bytes.indexOf(buffer, CRLF, 0, read) + CRLF.length;
        if (requestLineEndIndex == 1) {
          throw new MalformedRequestException("request line end not found");
        }

        final var requestLineParts = new String(buffer, 0, requestLineEndIndex).trim().split(" ");
        if (requestLineParts.length != 3) {
          throw new MalformedRequestException("request line must contains 3 parts");
        }

        final var method = requestLineParts[0];
        String params = null;
        String uri;

        // TODO: uri split ? -> URLDecoder
        if(requestLineParts[1].indexOf('?')!=-1){
          final var splited = requestLineParts[1].split("\\?");
          params = splited[1];
          uri = splited[0];
        } else{
          uri = requestLineParts[1];
        }

        final var query = new HashMap<String, List<String>>();
        if( params!=null ){
          if(params.indexOf('&')!=-1){
            final var params_splited = params.split("&");
            for(String part:params_splited){
              final var param_kv = part.split("=");
              final List<String> param_values = new ArrayList<>();
              if(param_kv[1].indexOf('%') != -1){
                param_values.addAll(Arrays.asList(param_kv[1].split("%2C")));
              }else{
                param_values.add(param_kv[1]);
              }
              query.put(param_kv[0], param_values);
            }
          }else{
            final var params_splited = params.split("=");
            if(params_splited.length < 2){
              throw new MalformedRequestException();
            }else{
              query.put(params_splited[0], List.of(params_splited[1]));
            }
          }
        }

        final var headersEndIndex = Bytes.indexOf(buffer, CRLFCRLF, requestLineEndIndex, read) + CRLFCRLF.length;
        if (headersEndIndex == 3) {
          throw new MalformedRequestException("headers too big");
        }

        var lastIndex = requestLineEndIndex;
        final var headers = new HashMap<String, String>();
        while (lastIndex < headersEndIndex - CRLF.length) {
          final var headerEndIndex = Bytes.indexOf(buffer, CRLF, lastIndex, headersEndIndex) + CRLF.length;
          if (headerEndIndex == 1) {
            throw new MalformedRequestException("can't find header end index");
          }
          final var header = new String(buffer, lastIndex, headerEndIndex - lastIndex);
          final var headerParts = Arrays.stream(header.split(":", 2))
              .map(String::trim)
              .collect(Collectors.toList());

          if (headerParts.size() != 2) {
            throw new MalformedRequestException("Invalid header: " + header);
          }

          headers.put(headerParts.get(0), headerParts.get(1));
          lastIndex = headerEndIndex;
        }

        final var contentLength = Integer.parseInt(headers.getOrDefault("Content-Length", "0"));

        if (contentLength > bodyLimit) {
          throw new RequestBodyTooLarge();
        }

        in.reset();
        in.skipNBytes(headersEndIndex);
        byte[] multipart = in.readNBytes(contentLength);

        final var contentType = String.valueOf(headers.getOrDefault("Content-Type", "text/plain"));

        final var body = new HashMap<String,List<String>>();

        final var body_test = new String(multipart, StandardCharsets.UTF_8) ;
        log.log(Level.INFO, "Body: " + body_test);
        if(contentType.equals("application/x-www-form-urlencoded")){
          for(String part:body_test.split("&")){
            final var body_kv = part.split("=");
            if(body_kv.length<2){
              throw new MalformedRequestException();
            }
            final List<String> values = new ArrayList<>(Arrays.asList(body_kv[1].split("%2C")));
            body.put(body_kv[0], values);
          }
        }

        // TODO: annotation monkey
        final var request = Request.builder()
                .method(method)
                .path(uri)
                .headers(headers)
                .query(query)
                .form(body)
                .multipart(multipart)
                .build();

        final var response = out;

        final var handlerMethod = Optional.ofNullable(routes.get(request.getMethod()))
            .map(o -> o.get(request.getPath()))
            .orElse(new HandlerMethod(notFoundHandler, notFoundHandler.getClass().getMethod("handle", Request.class, OutputStream.class)));

        try {
          final var invokableMethod = handlerMethod.getMethod();
          final var invokableHandler = handlerMethod.getHandler();

          final var arguments = new ArrayList<>(invokableMethod.getParameterCount());
          for (final var parameter : invokableMethod.getParameters()) {
            var resolved = false;
            for (final var argumentResolver : argumentResolvers) {
              if (!argumentResolver.supportsParameter(parameter)) {
                continue;
              }

              final var argument = argumentResolver.resolveArgument(parameter, request, response);
              arguments.add(argument);

              resolved = true;
              break;
            }
            if (!resolved) {
              throw new UnsupportedParameterException(parameter.getType().getName());
            }
          }

          invokableMethod.invoke(invokableHandler, arguments.toArray());
        }catch (MalformedRequestException e){
          // language=HTML
          final var html = "<h1>Mailformed request</h1>";
          out.write(
                  (
                          // language=HTTP
                          "HTTP/1.1 400 Bad Request\r\n" +
                                  "Server: nginx\r\n" +
                                  "Content-Length: " + html.length() + "\r\n" +
                                  "Content-Type: text/html; charset=UTF-8\r\n" +
                                  "Connection: close\r\n" +
                                  "\r\n" +
                                  html
                  ).getBytes(StandardCharsets.UTF_8)
          );
        } catch (Exception e) {
          internalErrorHandler.handle(request, response);
        }
      } catch (MalformedRequestException e) {
        // language=HTML
        final var html = "<h1>Mailformed request</h1>";
        out.write(
            (
                // language=HTTP
                "HTTP/1.1 400 Bad Request\r\n" +
                    "Server: nginx\r\n" +
                    "Content-Length: " + html.length() + "\r\n" +
                    "Content-Type: text/html; charset=UTF-8\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    html
            ).getBytes(StandardCharsets.UTF_8)
        );
      } catch (NoSuchMethodException e) {
        e.printStackTrace();
        log.log(Level.INFO, e.getMessage());
        // TODO:
      }
    } catch (IOException e) {
      e.printStackTrace();
      log.log(Level.INFO, e.getMessage());
      // TODO:
    }
  }
}