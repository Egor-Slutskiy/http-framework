package pakages.floppy.http.framework;

import lombok.Data;

import java.io.Serializable;


@Data
public class Part implements Serializable {
    private String contentDisposition;
    private String name;
    private String filename;
    private String contentType;
    private String charset;
    private String contentTransferEncoding;
    private byte[] data;

    public Part(String contentInfo, byte[] data){
        final String[] lines = contentInfo.split("\r\n");
        final String[] firstLine = lines[0].split("; ");


        this.contentDisposition = firstLine[0].split(": ")[1];
        this.name = firstLine[1].split("=")[1].replace("\"", "");

        if(firstLine.length >2){
            this.filename = firstLine[2].split("=")[1].replace("\n", "");
        }

        String[] charset = lines[1].split("; ");
        this.contentType = charset[0].split(": ")[1];
        if(charset.length>1){
            this.charset = charset[1].split("=")[1];
        }

        this.contentTransferEncoding = lines[2].split(": ")[1];


        this.data = data;

    }
}
