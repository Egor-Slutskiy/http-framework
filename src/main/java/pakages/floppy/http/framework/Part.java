package pakages.floppy.http.framework;

import lombok.Data;

import java.io.Serializable;


@Data
public class Part implements Serializable {
    private String content_disposition;
    private String name;
    private String filename;
    private String content_type;
    private String charset;
    private String content_transfer_encoding;
    private byte[] data;

    public Part(String content_info, byte[] data){
        final String[] lines = content_info.split("\r\n");
        final String[] first_line = lines[0].split("; ");


        this.content_disposition = first_line[0].split(": ")[1];
        this.name = first_line[1].split("=")[1].replace("\"", "");

        if(first_line.length >2){
            this.filename = first_line[2].split("=")[1].replace("\n", "");
        }

        String[] ct_charset = lines[1].split("; ");
        this.content_type = ct_charset[0].split(": ")[1];
        if(ct_charset.length>1){
            this.charset = ct_charset[1].split("=")[1];
        }

        this.content_transfer_encoding = lines[2].split(": ")[1];


        this.data = data;

    }
}
