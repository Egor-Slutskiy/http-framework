package pakages.floppy.http.framework;

import lombok.Data;
import pakages.floppy.http.framework.exception.MalformedRequestException;

import java.util.*;

@Data
public class URLDecoder {
    private String url;
    private Map<String, List<String>> query;

    public void decode(String url){
        String queryStr = null;
        if(url.indexOf('?')!=-1){
            final var urlQuery = url.split("\\?");
            queryStr = urlQuery[1];
            this.url = urlQuery[0];
        } else{
            this.url = url;
        }

        this.query = new HashMap<String, List<String>>();
        if(queryStr!=null){
            if(queryStr.indexOf('&')!=-1){
                final var paramsArray = queryStr.split("&");
                for(String part:paramsArray){
                    final var paramKV = part.split("=");
                    final List<String> paramValues = new ArrayList<>();
                    if(paramKV[1].contains("%2C")){
                        paramValues.addAll(Arrays.asList(paramKV[1].split("%2C")));
                    }else{
                        paramValues.add(paramKV[1]);
                    }
                    query.put(paramKV[0], paramValues);
                }
            }else{
                final var paramsArray = queryStr.split("=");
                if(paramsArray.length < 2){
                    throw new MalformedRequestException();
                }else{
                    query.put(paramsArray[0], List.of(paramsArray[1]));
                }
            }
        }
    }
}
