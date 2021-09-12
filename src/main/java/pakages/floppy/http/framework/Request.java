package pakages.floppy.http.framework;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
public class Request {
  String method;
  String path;
  String params;
  Map<String, String> headers;
  Map<String, List<String>> query;
  Map<String, List<String>> form;
  byte[] multipart;
}
